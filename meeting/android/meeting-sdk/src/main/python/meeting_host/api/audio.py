"""Audio HTTP routes using explicit application services."""

from typing import Literal

import soundfile as sf
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse

from .. import audio_store as audio_assets
from .common import WorkspaceAccess, guard
from .schemas import ProcessRequest


def router(services):
    router = APIRouter(prefix="/api/v1", tags=["audio"])
    access = WorkspaceAccess(services)
    exists = access.exists
    mutate = access.mutate

    @router.post("/meetings/{mid}/audio", status_code=202)
    async def upload(
        mid: str,
        file: UploadFile = File(...),
        speakers: int = Form(-1),
        compare: bool = Form(True),
        offline_model: Literal["qwen3_asr", "sensevoice"] = Form("qwen3_asr"),
        refinement_profile_id: str | None = Form(None),
    ):
        if speakers != -1 and not 1 <= speakers <= 20:
            raise HTTPException(422, "speakers must be -1 or 1..20")
        try:
            profile = (
                services.refinement.snapshot(refinement_profile_id)
                if refinement_profile_id
                else None
            )
        except ValueError as e:
            raise HTTPException(422, str(e))
        services.reserve(mid)
        try:
            raw = bytearray()
            while chunk := await file.read(1024 * 1024):
                raw.extend(chunk)
                if len(raw) > services.MAX_BYTES:
                    raise HTTPException(413, "Audio exceeds 128 MiB")
            samples = await services.infer(services.decode_audio, raw)
            aid, path = audio_assets.allocate(mid, "upload")
            await services.infer(sf.write, str(path), samples, 16000, "PCM_16")
            audio_assets.update(aid, "completed", len(samples) / 16000)
            services.workspace.begin_audio(mid, aid)
            services.store.update(mid, status="processing", error=None)
            services.launch(
                services.process_job(
                    mid, speakers, compare, offline_model, profile=profile
                )
            )
            return dict(id=mid, status="processing")
        except ValueError as e:
            services.active.discard(mid)
            raise HTTPException(422, str(e))
        except Exception:
            services.active.discard(mid)
            raise
        finally:
            await file.close()

    @router.post("/meetings/{mid}/process", status_code=202)
    async def reprocess(mid: str, body: ProcessRequest):
        return await services.reprocess(mid, body)

    @router.get("/meetings/{mid}/audio")
    def audio(mid: str, audio_id: str | None = None):
        item = services.meeting(mid)
        aid = audio_id or item["document"].get("audio_id")
        if not aid:
            raise HTTPException(404, "此正文没有可确认的关联音频")
        try:
            path = audio_assets.path(mid, aid)
        except ValueError as error:
            raise HTTPException(404, str(error))
        if not path.is_file():
            raise HTTPException(404, "No audio")
        return FileResponse(
            path,
            media_type="audio/wav",
            filename=aid + ".wav",
            headers={"Cache-Control": "no-store"},
        )

    @router.get("/meetings/{mid}/audio/versions")
    def audio_versions(mid: str):
        exists(mid)
        return dict(
            active_audio_id=audio_assets.active_id(mid), items=audio_assets.listing(mid)
        )

    @router.post("/meetings/{mid}/audio/{audio_id}/recover")
    def recover_audio(mid: str, audio_id: str):
        mutate(mid)
        if mid in services.active:
            raise HTTPException(409, "请等待当前处理结束")
        asset = guard(lambda: audio_assets.info(mid, audio_id))
        if not asset["recoverable"]:
            raise HTTPException(409, "此音频没有可恢复的中断录音")
        duration = guard(
            lambda: audio_assets.repair_pcm(audio_assets.path(mid, audio_id))
        )
        audio_assets.update(audio_id, "recovered", duration)
        if audio_assets.active_id(mid) == audio_id:
            services.store.update(mid, status="recorded", error=None)
        return audio_assets.info(mid, audio_id)

    return router
