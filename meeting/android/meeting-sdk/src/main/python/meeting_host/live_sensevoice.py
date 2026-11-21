"""Experimental cumulative-prefix SenseVoice drafts with VAD final re-decoding.

This uses the existing sherpa-onnx INT8 runtime, not the community PyTorch package.
"""
import asyncio
import json
import time
import wave
import numpy as np
from .live_qwen import QwenLiveBuffer
from .live_speakers import LiveSpeakers
from .streaming.progressive import ProgressiveRefiner

async def dual_stream(ws, mid, api):
    reserved = False
    handed_off = False
    wav = None
    count = 0
    segments = []
    total_compute = 0.0
    seq = 0
    start = 0
    last_preview = 0
    buffer = None
    paused = False
    buffer_origin = 0
    audio_id = None
    refiner = None
    progressive_selected = False
    progressive_enabled = False
    warning = None
    phase = 'recording'
    send_lock = asyncio.Lock()

    async def send(message):
        async with send_lock:
            await ws.send_json(message)

    async def refined(segment_id, update):
        item = segments[segment_id]
        item.update(update, refinement_revision=1)
        wav.checkpoint(snapshot(), phase, True)
        await send(dict(type='refined' if update['refinement_status']=='refined' else 'refinement_failed',
                        audio_id=audio_id, segment_id=segment_id, refinement_revision=1, **update))

    def snapshot(interrupted=False):
        return dict(audio_id=audio_id,streaming_mode='sensevoice_dual',segments=segments,
            transcript='\n'.join(s['text'] for s in segments),recording_interrupted=interrupted,
            progressive_refinement=dict(enabled=progressive_enabled, model='FireRedASR2-AED INT8', warning=warning, **(dict(refiner.stats) if refiner else {})),
            engines=dict(streaming='SenseVoice INT8 cumulative-prefix experiment',vad='Silero',speaker='CAM++ online session profiles'),
            metrics=dict(audio_seconds=count/16000,processing_seconds=total_compute,rtf=total_compute/max(count/16000,.001)))
    try:
        api.reserve(mid)
        reserved = True
        hello = await asyncio.wait_for(ws.receive_json(), 30)
        if hello != dict(type='start', sample_rate=16000, channels=1, format='pcm_s16le'):
            raise ValueError('Expected 16 kHz mono PCM16 start')
        auto_refine,refinement_profile=api.recording_selection(mid)
        progressive_selected = refinement_profile['kind'] == 'local_firered'
        progressive_enabled = auto_refine and progressive_selected and api.engine.firered_available()
        if auto_refine and progressive_selected and not progressive_enabled:
            warning = 'FireRed 模型未准备，已保留 SenseVoice 草稿；请在设置中测试本地模型'
        await api.infer(api.engine.sensevoice)
        speakers = await api.infer(LiveSpeakers)
        preview_speaker = "unknown"
        last_speaker_preview = 0
        buffer = QwenLiveBuffer(max_speech_seconds=6)
        wav = api.capture_audio(mid)
        audio_id = wav.aid
        if progressive_enabled:
            refiner = ProgressiveRefiner(api.infer_refinement, api.engine.decode_firered, wav.read_samples, refined)
        api.store.update(mid, status='recording', error=None)
        await send(dict(type='ready', id=mid, audio_id=audio_id, qwen_compare=False, mode='sensevoice_dual', preview_interval_ms=400, max_segment_seconds=6, speaker_model='CAM++ online', speaker_window_seconds=2, progressive_refinement=progressive_enabled, refinement_warning=warning))
        while True:
            msg = await asyncio.wait_for(ws.receive(), 60)
            if msg['type'] == 'websocket.disconnect':
                raise api.WebSocketDisconnect()
            data = msg.get('bytes')
            control = json.loads(msg.get('text', '{}')).get('type') if data is None else None
            if control == 'ping':
                await send(dict(type='pong', audio_seconds=count/16000))
                continue
            if control == 'resume':
                if paused:
                    buffer = await api.infer(lambda: QwenLiveBuffer(max_speech_seconds=6))
                    buffer_origin = count
                    paused = False
                    phase = 'recording'
                    wav.checkpoint(snapshot(),'recording',True)
                await send(dict(type='resumed', audio_seconds=count/16000))
                continue
            if control == 'pause' and paused:
                await send(dict(type='paused', audio_seconds=count/16000))
                continue
            finish = control == 'stop'
            phase = 'finalizing' if finish else 'paused' if control == 'pause' or paused else 'recording'
            pending_refinements = []
            boundary = finish or control == 'pause'
            if data is None:
                if not boundary:
                    raise ValueError('Unknown control message')
                samples = np.zeros(0, dtype=np.float32)
            else:
                if paused:
                    raise ValueError('Resume before sending PCM frames')
                if not data or len(data) % 2 or len(data) > 32000:
                    raise ValueError('Invalid PCM frame')
                count += len(data)//2
                if count > api.MAX_SECONDS*16000:
                    raise ValueError('Recording exceeds one hour')
                wav.write(data)
                samples = np.frombuffer(data, dtype='<i2').astype(np.float32)/32768
            began = time.perf_counter()
            pieces = [] if paused and finish else await api.infer(buffer.feed, samples, boundary)
            seq += 1
            for piece in pieces:
                piece_start = buffer_origin+round(piece['start']*16000)
                a = max(start, piece_start)
                b = buffer_origin+round(piece['end']*16000)
                crop = max(0, a-piece_start)
                audio = piece['waveform'][crop:]
                rows = await api.infer(speakers.split, audio)
                for row in rows:
                    text = await api.infer(api.engine.decode_sensevoice, audio[row['start']:row['end']])
                    item = dict(start=(a+row['start'])/16000, end=(a+row['end'])/16000,
                                text=text, speaker=row['speaker'], speaker_confidence=row['confidence'])
                    index = len(segments)
                    if progressive_enabled:
                        item.update(sensevoice_text=text, refinement_status='draft', refinement_revision=0)
                        pending_refinements.append((index, a+row['start'], a+row['end']))
                    segments.append(item)
                    speakers.remember(index, row)
                    await send(dict(type='final', seq=seq, segment_id=index,
                        mode='sensevoice_dual', closed_audio_seconds=count/16000, **item))
                for revision in speakers.revisions():
                    segments[revision['segment_id']].update({k:v for k,v in revision.items() if k != 'segment_id'})
                    await send(dict(type='speaker_update', **revision))
                preview_speaker = 'unknown'
                start = b
                last_preview = count
            if not boundary and count-last_preview >= 6400 and buffer.vad.is_speech_detected():
                # Drop leading silence but retain 200 ms of onset context.
                offset = buffer_origin+buffer.offset
                a = max(start, offset, buffer_origin+buffer.vad.current_segment.start-3200)
                draft = buffer.audio[a-offset:count-offset].copy()
                if len(draft) >= 6400:
                    if count-last_speaker_preview >= 16000 and len(draft) >= 24000:
                        vector = await api.infer(speakers.embedding, draft[-32000:])
                        preview_speaker, _ = speakers.match(vector)
                        last_speaker_preview = count
                    text = await api.infer(api.engine.decode_sensevoice, draft)
                    if any(ch.isalnum() for ch in text):
                        await send(dict(type='partial', seq=seq, segment_id=len(segments),
                            start=a/16000, end=count/16000, text=text, speaker=preview_speaker, mode='sensevoice_dual'))
                last_preview = count
            elapsed = time.perf_counter()-began
            total_compute += elapsed
            if control == 'pause':
                paused = True
                start = count
                last_preview = count
            wav.checkpoint(snapshot(),'finalizing' if finish else 'paused' if paused else 'recording',boundary or bool(pieces))
            for work in pending_refinements:
                refiner.submit(*work)
            await send(dict(type='ack', seq=seq, audio_seconds=count/16000, processing_seconds=elapsed))
            if control == 'pause':await send(dict(type='paused', audio_seconds=count/16000))
            if finish:
                break
        if refiner:
            await send(dict(type='refinement_progress', **dict(refiner.stats)))
            await refiner.finish()
        # Stop may leave a non-speech tail; the VAD flush above is authoritative.
        result = snapshot()
        result['speaker_count']=len(speakers.profiles)
        wav.close(result,'completed')
        wav = None
        api.workspace.capture_model(mid,result)
        result['auto_refinement'] = auto_refine and not progressive_selected
        if auto_refine and not progressive_selected:
            api.store.update(mid, status='processing', result=result)
            api.launch(api.process_job(mid, -1, False, 'sensevoice', True, refinement_profile))
            handed_off = True
        else:
            api.store.update(mid, status='ready' if progressive_enabled else 'recorded', result=result)
        await send(dict(type='done', id=mid, audio_seconds=count/16000,
                                refinement_status='processing' if handed_off else 'completed' if progressive_enabled else 'disabled',
                                progressive_refinement=progressive_enabled, refinement_failed=refiner.stats['failed'] if refiner else 0,
                                refinement_model=refinement_profile['name']))
    except (api.WebSocketDisconnect, asyncio.TimeoutError):
        if reserved and not handed_off:
            api.store.update(mid,status='interrupted',error='Disconnected; saved audio can be reprocessed')
    except Exception as error:
        if reserved and not handed_off:
            api.store.update(mid,status='failed',error=str(error))
        try:
            await send(dict(type='error',message=str(error)))
        except Exception:
            pass
    finally:
        try:
            if refiner:
                await refiner.cancel()
            if wav:
                result=snapshot(True)
                wav.close(result,'interrupted')
                api.workspace.capture_model(mid,result)
                api.store.update(mid,result=result)
        finally:
            if reserved and not handed_off:api.active.discard(mid)
            try:await ws.close()
            except Exception:pass
