"""Conservative post-recording verification using independent clean windows."""
import numpy as np
from .live_speakers import LiveSpeakers


def attribute_segment(turns,start,end):
    overlap=lambda t:max(0,min(end,t['end'])-max(start,t['start']))
    candidates=[t for t in turns if overlap(t)>0]
    if not candidates:return dict(speaker='unknown',speaker_status='unconfirmed')
    best=max(candidates,key=overlap);label=best['speaker']
    matching=sorted((max(start,t['start']),min(end,t['end'])) for t in candidates if t['speaker']==label)
    covered,last=0.,start
    for left,right in matching:
        covered+=max(0,right-max(left,last));last=max(last,right)
    conflicting=any(t['speaker']!=label and overlap(t)>.15 for t in candidates)
    if conflicting:return dict(speaker='unknown',speaker_status='overlap',
                               speaker_candidates=sorted({t['speaker'] for t in candidates}))
    status=best.get('speaker_status','unconfirmed')
    if covered/max(end-start,.001)<.8:status='unconfirmed'
    return dict(speaker=label,speaker_status=status)


def verify_turns(samples, turns):
    extractor = LiveSpeakers()
    evidence = []
    for index, turn in enumerate(turns):
        # Exclude overlap instead of teaching both speakers the mixed voice.
        spans = [(turn['start'], turn['end'])]
        for other in turns:
            if other is turn:
                continue
            updated = []
            for a, b in spans:
                if other['start'] >= b or other['end'] <= a:
                    updated.append((a, b))
                else:
                    if a < other['start']: updated.append((a, other['start']))
                    if other['end'] < b: updated.append((other['end'], b))
            spans = updated
        for a, b in spans:
            # Include clean 1–2 second responses; use independent, non-overlapping samples.
            for start in np.arange(a, b-.99, 2.):
                vector = extractor.embedding(samples[round(start*16000):round(min(start+2,b)*16000)])
                if vector is not None:
                    evidence.append((index, turn['speaker'], vector))
    verified = []
    for index, turn in enumerate(turns):
        vectors = [(n,v) for n,(i,_,v) in enumerate(evidence) if i == index]
        votes, scores = [], []
        for window_id,vector in vectors:
            similarities = {}
            for label in {label for _, label, _ in evidence}:
                # Never match a window against itself. Separate windows from a long
                # turn are valid evidence even when a person speaks only once.
                peers = [v for n,(_,name,v) in enumerate(evidence) if n != window_id and name == label]
                if peers:
                    center = np.mean(peers, axis=0)
                    norm = np.linalg.norm(center)
                    if norm: similarities[label] = float(np.dot(vector, center/norm))
            ranked = sorted(similarities, key=similarities.get, reverse=True)
            if not ranked: continue
            best = ranked[0]
            score = similarities[best]
            margin = score-similarities[ranked[1]] if len(ranked)>1 else score
            # Confirm only the global cluster's own assignment; do not silently swap identities.
            votes.append(best == turn['speaker'] and score >= .68 and margin >= .12)
            scores.append(score)
        confirmed = bool(votes) and sum(votes)/len(votes) >= .75
        verified.append(dict(turn, candidate_speaker=turn['speaker'],
                             speaker=turn['speaker'],
                             speaker_status='verified' if confirmed else 'unconfirmed',
                             speaker_evidence_windows=len(vectors),
                             speaker_similarity=min(scores) if scores else None))
    return verified
