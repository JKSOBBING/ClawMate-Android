"""Session-local CAM++ profiles. No model training or cross-meeting enrollment."""
import numpy as np
import sherpa_onnx as so
from .engine import MODEL


class LiveSpeakers:
    def __init__(self):
        self.extractor = so.SpeakerEmbeddingExtractor(
            so.SpeakerEmbeddingExtractorConfig(model=str(MODEL/'campplus.onnx'), num_threads=1))
        self.profiles = []
        self.history = []

    def embedding(self, audio):
        audio = np.ascontiguousarray(audio, dtype=np.float32)
        if len(audio) < 16000 or np.sqrt(np.mean(audio**2)) < .008 or np.mean(np.abs(audio) > .99) > .02:
            return None
        stream = self.extractor.create_stream()
        stream.accept_waveform(16000, audio)
        stream.input_finished()
        if not self.extractor.is_ready(stream):
            return None
        vector = np.asarray(self.extractor.compute(stream), dtype=np.float32)
        norm = np.linalg.norm(vector)
        return vector/norm if norm > 0 else None

    def match(self, vector, update=False):
        if vector is None:
            return 'unknown', 0.
        scores = [float(np.dot(vector, p['vector'])) for p in self.profiles]
        order = np.argsort(scores)
        best = int(order[-1]) if scores else -1
        score = scores[best] if scores else 0.
        margin = score - scores[int(order[-2])] if len(scores) > 1 else 1.
        if best >= 0 and score >= .55 and margin >= .08:
            if update and score >= .65 and margin >= .12:
                profile = self.profiles[best]
                weight = min(profile['count'], 20)
                mean = profile['vector']*weight + vector
                profile['vector'] = mean/np.linalg.norm(mean)
                profile['count'] += 1
            return f'speaker_{best:02d}', score
        if update and (not scores or score < .4) and len(self.profiles) < 20:
            self.profiles.append(dict(vector=vector.copy(), count=1))
            return f'speaker_{len(self.profiles)-1:02d}', 1.
        return 'unknown', score

    def split(self, audio):
        # Non-overlapping windows avoid feeding the same samples into a profile repeatedly.
        # VAD boundaries alone cannot detect a change without a pause.
        edges = list(range(0, len(audio), 32000))
        if len(edges) > 1 and len(audio)-edges[-1] < 16000:
            edges.pop()
        edges.append(len(audio))
        rows = []
        for a, b in zip(edges, edges[1:]):
            vector = self.embedding(audio[a:b])
            speaker, confidence = self.match(vector, update=True)
            if rows and speaker != 'unknown' and rows[-1]['speaker'] == speaker:
                rows[-1]['end'] = b
                rows[-1]['confidence'] = min(rows[-1]['confidence'], confidence)
            else:
                rows.append(dict(start=a, end=b, speaker=speaker, confidence=confidence, vector=vector))
        return rows

    def remember(self, segment_id, row):
        if row['speaker'] == 'unknown' and row['vector'] is not None:
            self.history.append((segment_id, row['vector']))
            self.history = self.history[-30:]

    def revisions(self):
        changes, pending = [], []
        for index, vector in self.history:
            speaker, score = self.match(vector)
            if speaker != 'unknown' and score >= .65:
                changes.append(dict(segment_id=index, speaker=speaker, speaker_confidence=score))
            else:
                pending.append((index, vector))
        self.history = pending
        return changes
