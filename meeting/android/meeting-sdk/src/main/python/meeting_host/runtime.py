"""Application services: inference queues, recording ownership and processing jobs."""

import asyncio
import io
import logging
import math
import os
import time
from concurrent.futures import ThreadPoolExecutor
import numpy as np
import soundfile as sf
from scipy.signal import resample_poly
from fastapi import HTTPException, WebSocketDisconnect
from .clawmate_engine import Engine
from .engine import MODEL, ROOT
from . import store, workspace_store as workspace, audio_store as audio_assets
from .persistence import (
    documents as document_store,
    metadata as metadata_store,
    tasks as task_store,
)
from .refinement_config import RefinementConfig
from .refinement_pipeline import refine


class MeetingRuntime:
    store = store
    workspace = workspace
    WebSocketDisconnect = WebSocketDisconnect
    MAX_SECONDS = 3600
    MAX_BYTES = 128 * 1024 * 1024
    MODEL = MODEL
    ROOT = ROOT
    logger = logging.getLogger("meeting_host")

    def __init__(self, engine=None):
        self.engine = engine if engine is not None else Engine()
        self.refinement = RefinementConfig(store.ROOT / "refinement-settings.json")
        self.pool = ThreadPoolExecutor(max_workers=1)
        self.qwen_pool = ThreadPoolExecutor(max_workers=1)
        self.refinement_pool = ThreadPoolExecutor(max_workers=1)
        self.active = set()
        self.tasks = set()
        self.idle_seconds = int(os.getenv("MEETING_MODEL_IDLE_SECONDS", "0"))
        self.last_used = time.monotonic()
        self.maintenance_active = False
        self.inflight = 0

    def initialize(self):
        store.initialize()
        workspace.initialize()
        workspace.recover_recordings()
        from . import handoff
        handoff.initialize()

    def close(self):
        self.pool.shutdown(wait=True)
        self.qwen_pool.shutdown(wait=True)
        self.refinement_pool.shutdown(wait=True)

    async def infer_refinement(self, fn, *args):
        return await self._infer(self.refinement_pool, fn, args)

    async def infer(self, fn, *args):
        return await self._infer(self.pool, fn, args)

    async def infer_qwen(self, fn, *args):
        return await self._infer(self.qwen_pool, fn, args)

    async def _infer(self, pool, fn, args):
        if self.maintenance_active:
            raise HTTPException(409, "模型正在释放，请稍后重试")
        self.inflight += 1
        try:
            return await asyncio.get_running_loop().run_in_executor(pool, fn, *args)
        finally:
            self.inflight -= 1
            self.last_used = time.monotonic()

    def meeting(self, mid):
        try:
            item = self.store.get(mid)
            item.update(metadata_store.metadata(mid))
            item["document"] = document_store.document(mid)
            aid = item["document"].get("audio_id")
            item["document_audio"] = audio_assets.info(mid, aid) if aid else None
            item["active_audio_id"] = audio_assets.active_id(mid)
            if item["result"] and item["result"].get("audio_id") != aid:
                item["result"] = dict(
                    audio_id=aid,
                    segments=item["document"]["segments"],
                    transcript=item["document"]["transcript"],
                )
            return item
        except KeyError:
            raise HTTPException(404, "Meeting not found")

    def reserve(self, mid):
        if self.maintenance_active:
            raise HTTPException(409, "模型正在释放，请稍后重试")
        self.last_used = time.monotonic()
        item = self.meeting(mid)
        if item["trashed_at"]:
            raise HTTPException(409, "请先从回收站恢复此记录")
        if mid in self.active or item["status"] in (
            "recording",
            "processing",
            "summarizing",
        ):
            raise HTTPException(409, "Meeting is busy")
        if len(self.active) >= 4:
            raise HTTPException(429, "Local job queue is full")
        self.active.add(mid)
        return item

    async def release_models(self, force=False):
        if self.active or self.tasks or self.inflight or self.maintenance_active:
            if force:
                raise HTTPException(409, "录音或任务正在运行，不能释放模型")
            return False
        if not force and (not self.idle_seconds or time.monotonic() - self.last_used < self.idle_seconds):
            return False
        self.maintenance_active = True
        try:
            await asyncio.get_running_loop().run_in_executor(self.pool, self.engine.release_models)
            summary = getattr(self.engine, "summary_runtime", None)
            if summary:
                await asyncio.to_thread(summary.release, force)
            self.last_used = time.monotonic()
            return True
        finally:
            self.maintenance_active = False

    async def idle_monitor(self):
        while True:
            await asyncio.sleep(5)
            await self.release_models()
            summary = getattr(self.engine, "summary_runtime", None)
            if summary and not self.active:
                await asyncio.to_thread(summary.release)

    def audio_path(self, mid):
        return audio_assets.path(mid)

    def capture_audio(self, mid):
        aid, path = audio_assets.allocate(mid, "recording")
        writer = audio_assets.CaptureWriter(mid, aid, path)
        try:
            document_store.begin_audio(mid, aid)
        except Exception:
            writer.close(dict(segments=[], audio_id=aid), "unavailable")
            raise
        return writer

    def recording_selection(self, mid):
        template = metadata_store.metadata(mid)["template"]
        if template:
            return template["auto_refine"], self.refinement.snapshot(
                template["refinement_profile_id"]
            )
        return self.refinement.recording_selection()

    def decode_audio(self, raw):
        try:
            with sf.SoundFile(io.BytesIO(raw)) as f:
                if (
                    not 8000 <= f.samplerate <= 192000
                    or f.channels > 8
                    or len(f) / f.samplerate > self.MAX_SECONDS
                ):
                    raise ValueError(
                        "Audio exceeds supported sample rate, channels, or 1 hour duration"
                    )
                rate = f.samplerate
                samples = f.read(dtype="float32", always_2d=True).mean(axis=1)
        except Exception as e:
            raise ValueError("Invalid or unsupported audio: " + str(e)) from e
        if not len(samples) or not np.isfinite(samples).all():
            raise ValueError("Empty/nonfinite audio")
        if rate != 16000:
            gcd = math.gcd(rate, 16000)
            samples = resample_poly(samples, 16000 // gcd, rate // gcd).astype(
                np.float32
            )
        return np.ascontiguousarray(samples)

    async def process_job(
        self,
        mid,
        speakers,
        compare,
        offline_model="qwen3_asr",
        strict_speakers=False,
        profile=None,
    ):
        aid = audio_assets.active_id(mid)
        job = task_store.Job(
            mid,
            "refine",
            dict(
                speakers=speakers,
                compare=compare,
                offline_model=offline_model,
                refinement_profile_id=profile["id"] if profile else None,
                audio_id=aid,
            ),
        )
        try:
            job.progress("读取音频", 0.02)
            samples, _ = await self.infer(
                lambda: sf.read(str(audio_assets.path(mid, aid)), dtype="float32")
            )
            previous = self.store.get(mid)["result"] or {}
            if previous.get("audio_id") != aid:
                previous = {}
            if profile is not None:
                result = await self.infer(
                    refine, self.engine, samples, speakers, profile, job
                )
            else:
                result = await self.infer(
                    self.engine.process,
                    samples,
                    speakers,
                    compare,
                    offline_model,
                    strict_speakers,
                    job,
                )
            # Preserve the actual completed stream for comparison after refinement and reload.
            if "original_segments" in previous:
                result["original_segments"] = previous["original_segments"]
            elif previous.get("segments") and (
                not previous.get("engines") or previous.get("streaming_mode")
            ):
                result["original_segments"] = previous["segments"]
            if previous.get("streaming_mode"):
                result["streaming_mode"] = previous["streaming_mode"]
            if previous.get("engines", {}).get("streaming"):
                result["engines"]["streaming"] = previous["engines"]["streaming"]
            if previous.get("auto_refinement"):
                result["auto_refinement"] = True
            if "qwen_live" in previous:
                result["qwen_live"] = previous["qwen_live"]
            result["audio_id"] = aid
            job.progress("保存版本", 0.98)
            document_store.capture_model(mid, result)
            self.store.update(
                mid, status="ready", result=result, error=None, summary=None
            )
            job.finish("completed")
        except task_store.JobCancelled:
            self.store.update(
                mid,
                status="ready" if self.store.get(mid)["result"] else "recorded",
                error=None,
            )
            job.finish("cancelled")
        except Exception as e:
            self.logger.exception("Processing failed")
            self.store.update(mid, status="failed", error=str(e))
            job.finish("failed", str(e))
        finally:
            self.active.discard(mid)

    def launch(self, coro):
        task = asyncio.create_task(coro)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    async def summary_job(self, mid, text, source_hash=None, instructions=None):
        if instructions is None:
            instructions = (metadata_store.metadata(mid)["template"] or {}).get(
                "summary_instructions", ""
            )
        job = task_store.Job(mid, "summary", {})
        try:
            job.progress("生成纪要", 0.1)
            summary = await self.infer(self.engine.summary, text, instructions, job)
            job.check()
            if (
                source_hash
                and document_store.document(mid)["source_hash"] != source_hash
            ):
                raise ValueError("正文已更新，请重新生成纪要")
            summary["source_hash"] = source_hash
            self.store.update(mid, status="ready", summary=summary, error=None)
            job.finish("completed")
        except task_store.JobCancelled:
            self.store.update(mid, status="ready", error=None)
            job.finish("cancelled")
        except Exception as e:
            self.logger.exception("Summary failed")
            self.store.update(mid, status="ready", error="Summary failed: " + str(e))
            job.finish("failed", str(e))
        finally:
            self.active.discard(mid)

    async def reprocess(self, mid, body):
        if body.speakers == 0:
            raise HTTPException(422, "speakers must be -1 or 1..20")
        selected = metadata_store.metadata(mid)["template"]
        if not body.refinement_profile_id and selected:
            body.refinement_profile_id = (
                selected["refinement_profile_id"] or self.refinement.view()["active_id"]
            )
        try:
            profile = (
                self.refinement.snapshot(body.refinement_profile_id)
                if body.refinement_profile_id
                else None
            )
        except ValueError as e:
            raise HTTPException(422, str(e))
        self.reserve(mid)
        document = document_store.document(mid)
        if body.audio_id and body.audio_id != document.get("audio_id"):
            self.active.discard(mid)
            raise HTTPException(
                409, "任务关联的音频已不是当前正文音频，请先恢复对应正文版本"
            )
        if not document.get("audio_id"):
            self.active.discard(mid)
            raise HTTPException(409, "此正文没有可确认的关联音频，不能精修其他录音")
        if not self.audio_path(mid).is_file():
            self.active.discard(mid)
            raise HTTPException(409, "No audio recorded")
        self.store.update(mid, status="processing", error=None)
        self.launch(
            self.process_job(
                mid, body.speakers, body.compare, body.offline_model, profile=profile
            )
        )
        return dict(id=mid, status="processing")

    async def summarize(self, mid):
        item = self.reserve(mid)
        document = document_store.document(mid)
        text = document["transcript"]
        if not text.strip():
            self.active.discard(mid)
            raise HTTPException(409, "No transcript")
        self.store.update(mid, status="summarizing", error=None)
        self.launch(
            self.summary_job(
                mid,
                text,
                document["source_hash"],
                (item.get("template") or {}).get("summary_instructions", ""),
            )
        )
        return dict(id=mid, status="summarizing")
