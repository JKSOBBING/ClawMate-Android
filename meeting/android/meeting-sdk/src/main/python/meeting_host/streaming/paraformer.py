"""Legacy Paraformer stream, retaining its PCM and comparison protocol."""

import asyncio, json, time
import numpy as np
from ..live_qwen import QwenLiveBuffer


async def paraformer_stream(ws, mid, api, compare_qwen=False):
    reserved = False
    wav = None
    completed = False
    count = 0
    segments = []
    start = 0.0
    seq = 0
    streaming_processing_seconds = 0.0
    qtask = None
    qsegments = []
    qerrors = []
    qqueue = asyncio.Queue(maxsize=8)
    send_lock = asyncio.Lock()
    session_start = None

    async def send(event):
        async with send_lock:
            await ws.send_json(event)

    async def qworker():
        while True:
            piece = await qqueue.get()
            if piece is None:
                break
            try:
                t = asyncio.get_running_loop().time()
                text = await api.infer_qwen(api.engine.decode_qwen, piece["waveform"])
                now = asyncio.get_running_loop().time()
                item = dict(
                    start=piece["start"],
                    end=piece["end"],
                    speaker="unknown",
                    text=text,
                    processing_seconds=now - t,
                    queue_wait_seconds=t - piece["queued_at"],
                    wall_seconds_from_start=now - session_start,
                    closed_audio_seconds=piece["closed_audio_seconds"],
                )
                qsegments.append(item)
                await send(
                    dict(
                        type="qwen_final",
                        seq=len(qsegments),
                        mode="vad_segmented",
                        **item
                    )
                )
            except Exception as e:
                qerrors.append(str(e))
                await send(dict(type="qwen_error", message=str(e)))

    async def enqueue(pieces):
        for piece in pieces:
            if qerrors:
                return
            piece["queued_at"] = asyncio.get_running_loop().time()
            try:
                qqueue.put_nowait(piece)
            except asyncio.QueueFull:
                qerrors.append(
                    "Qwen comparison fell behind; use offline refinement for the complete audio"
                )
                await send(dict(type="qwen_error", message=qerrors[-1]))
                return

    try:
        api.reserve(mid)
        reserved = True
        hello = await asyncio.wait_for(ws.receive_json(), 30)
        if hello != {
            "type": "start",
            "sample_rate": 16000,
            "channels": 1,
            "format": "pcm_s16le",
        }:
            raise ValueError(
                "Send start with sample_rate=16000, channels=1, format=pcm_s16le"
            )
        rec = await api.infer(api.engine.streaming)
        s = await api.infer(rec.create_stream)
        qbuffer = None
        if compare_qwen:
            await api.infer_qwen(api.engine.qwen_asr)
            qbuffer = QwenLiveBuffer()
            qtask = asyncio.create_task(qworker())
        wav = api.capture_audio(mid)
        api.store.update(mid, status="recording", error=None)
        session_start = asyncio.get_running_loop().time()
        await send(
            dict(
                type="ready",
                id=mid,
                qwen_compare=compare_qwen,
                qwen_mode="vad_segmented" if compare_qwen else None,
            )
        )

        def accept(data, finish=False):
            with api.engine.lock:
                processing_start = time.perf_counter()
                s.accept_waveform(16000, data)
                if finish:
                    s.accept_waveform(16000, np.zeros(8000, dtype=np.float32))
                    s.input_finished()
                while rec.is_ready(s):
                    rec.decode_stream(s)
                text, endpoint = rec.get_result(s), rec.is_endpoint(s)
                return text, endpoint, time.perf_counter() - processing_start

        while True:
            message = await asyncio.wait_for(ws.receive(), 60)
            if message["type"] == "websocket.disconnect":
                raise api.WebSocketDisconnect()
            data = message.get("bytes")
            finish = False
            if data is not None:
                if not data or len(data) % 2 or len(data) > 32000:
                    raise ValueError("PCM frames must contain 1..16000 int16 samples")
                count += len(data) // 2
                if count > api.MAX_SECONDS * 16000:
                    raise ValueError("Recording exceeds 1 hour")
                wav.write(data)
                waveform = np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768
                text, endpoint, processing_seconds = await api.infer(accept, waveform)
                if qbuffer and not qerrors:
                    await enqueue(qbuffer.feed(waveform))
            else:
                control = json.loads(message.get("text", "{}"))
                if control.get("type") != "stop":
                    raise ValueError("Unknown control message")
                text, endpoint, processing_seconds = await api.infer(
                    accept, np.zeros(0, dtype=np.float32), True
                )
                finish = True
                if qbuffer and not qerrors:
                    await enqueue(qbuffer.feed(np.zeros(0, dtype=np.float32), True))
            seq += 1
            end = count / 16000
            streaming_processing_seconds += processing_seconds
            event = dict(
                type="final" if endpoint or finish else "partial",
                seq=seq,
                start=start,
                end=end,
                text=text,
                processing_seconds=processing_seconds,
            )
            await send(event)
            if endpoint or finish:
                if text:
                    segments.append(
                        dict(start=start, end=end, text=text, speaker="unknown")
                    )
                if not finish:
                    await api.infer(rec.reset, s)
                start = end
            wav.checkpoint(
                dict(
                    audio_id=wav.aid,
                    segments=segments,
                    transcript="\n".join(s["text"] for s in segments),
                    metrics=dict(audio_seconds=count / 16000),
                ),
                "recording",
                endpoint,
            )
            if finish:
                completed = True
                break
        if qtask:
            await qqueue.put(None)
            await qtask
        result = dict(
            audio_id=wav.aid,
            segments=segments,
            transcript="\n".join(x["text"] for x in segments),
            metrics=dict(
                audio_seconds=count / 16000,
                processing_seconds=streaming_processing_seconds,
                rtf=streaming_processing_seconds / max(count / 16000, 0.001),
            ),
        )
        if compare_qwen:
            result["qwen_live"] = dict(
                label="Qwen3-ASR-0.6B INT8（VAD 分段）",
                mode="vad_segmented",
                segments=qsegments,
                errors=qerrors,
                metrics=dict(
                    processing_seconds=sum(s["processing_seconds"] for s in qsegments),
                    first_text_seconds=next(
                        (s["wall_seconds_from_start"] for s in qsegments if s["text"]),
                        None,
                    ),
                ),
            )
        wav.close(result, "completed")
        wav = None
        api.workspace.capture_model(mid, result)
        api.store.update(mid, status="recorded", result=result)
        await send(dict(type="done", id=mid, audio_seconds=count / 16000))
    except (api.WebSocketDisconnect, asyncio.TimeoutError):
        if reserved:
            api.store.update(
                mid,
                status="interrupted",
                error="Stream disconnected or timed out; saved audio can be reprocessed",
            )
    except Exception as e:
        if reserved:
            api.store.update(mid, status="failed", error=str(e))
        try:
            await ws.send_json(dict(type="error", message=str(e)))
        except Exception:
            pass
    finally:
        if qtask and not qtask.done():
            qtask.cancel()
            try:
                await qtask
            except asyncio.CancelledError:
                pass
        try:
            if wav:
                result = dict(
                    audio_id=wav.aid,
                    segments=segments,
                    recording_interrupted=True,
                    transcript="\n".join(s["text"] for s in segments),
                    metrics=dict(audio_seconds=count / 16000),
                )
                wav.close(result, "interrupted")
                api.workspace.capture_model(mid, result)
                api.store.update(mid, result=result)
        finally:
            if reserved:
                api.active.discard(mid)
            try:
                await ws.close()
            except Exception:
                pass
