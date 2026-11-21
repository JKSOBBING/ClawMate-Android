"""VAD-triggered offline ASR, not the native vLLM streaming implementation."""
import numpy as np
import sherpa_onnx as so
from .engine import MODEL

class QwenLiveBuffer:
    def __init__(self, max_speech_seconds=8):
        cfg=so.VadModelConfig()
        cfg.silero_vad.model=str(MODEL/'silero_vad.onnx')
        cfg.silero_vad.min_silence_duration=.4
        cfg.silero_vad.min_speech_duration=.25
        cfg.silero_vad.max_speech_duration=max_speech_seconds
        self.max_speech_seconds=max_speech_seconds
        cfg.sample_rate=16000;cfg.num_threads=1
        self.vad=so.VoiceActivityDetector(cfg,buffer_size_in_seconds=30)
        self.window=cfg.silero_vad.window_size
        self.pending=np.zeros(0,dtype=np.float32)
        self.audio=np.zeros(0,dtype=np.float32)
        self.offset=0;self.total=0

    def feed(self,samples,finish=False):
        self.total+=len(samples)
        self.audio=np.concatenate([self.audio,samples])
        self.pending=np.concatenate([self.pending,samples])
        count=len(self.pending)//self.window
        if count:
            for offset in range(0,count*self.window,self.window):
                self.vad.accept_waveform(self.pending[offset:offset+self.window])
                if self.vad.is_speech_detected():
                    current=self.vad.current_segment
                    if len(current.samples)>=self.max_speech_seconds*16000:self.vad.flush()
            self.pending=self.pending[count*self.window:]
        if finish:
            if len(self.pending):self.vad.accept_waveform(np.pad(self.pending,(0,self.window-len(self.pending))))
            self.vad.flush()
        ready=[]
        while not self.vad.empty():
            segment=self.vad.front
            start=max(self.offset,segment.start-3200)
            end=min(self.total,segment.start+len(segment.samples)+3200)
            if end>start:
                ready.append(dict(start=start/16000,end=end/16000,
                                  waveform=self.audio[start-self.offset:end-self.offset].copy(),
                                  closed_audio_seconds=self.total/16000))
            self.vad.pop()
        keep=30*16000
        if len(self.audio)>keep:
            trim=len(self.audio)-keep;self.audio=self.audio[trim:];self.offset+=trim
        return ready
