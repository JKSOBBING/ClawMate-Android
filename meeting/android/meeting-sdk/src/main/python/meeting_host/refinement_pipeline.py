"""Common baseline, diarization and conservative fusion for all providers."""
import time
from .refinement_config import public_profile
from .refinement_provider import candidate
from .refinement_fusion import fuse


def refine(engine, samples, speakers, profile, job=None):
    began=time.perf_counter()
    result=engine.process(samples,speakers,False,'sensevoice',True,*([job] if job else []))
    audit=[]
    candidate_seconds=0.
    for index,segment in enumerate(result['segments']):
        if job:job.progress('精修与融合',.65+.3*index/max(len(result['segments']),1))
        start,end=round(segment['start']*16000),round(segment['end']*16000)
        baseline=segment['text']
        t=time.perf_counter()
        try:
            suggestion=candidate(profile,samples[start:end],baseline,engine)
            error=None
        except Exception:
            # A provider outage cannot replace readable SenseVoice text or leak request secrets.
            suggestion=baseline
            error='此段精修接口未完成，已保留 SenseVoice 底稿；可在模型设置中测试连接'
        candidate_seconds+=time.perf_counter()-t
        if job:job.check()
        try:
            fused=fuse(baseline,suggestion,
                       lambda:engine.decode_sensevoice(samples[max(0,start-9600):min(len(samples),end+9600)]))
        except Exception:
            fused=fuse(baseline,suggestion)
            error='上下文音频复核未完成，已保留 SenseVoice 底稿'
        segment['text']=fused['text']
        audit.append(dict(segment_id=index,start=segment['start'],end=segment['end'],
                          baseline=baseline,candidate=suggestion,error=error,**fused))
    elapsed=time.perf_counter()-began
    result.update(refinement=dict(profile=public_profile(profile),strategy='sensevoice_baseline_conservative_fusion',
                                  candidate_seconds=candidate_seconds,audit=audit,
                                  accepted_edits=sum(e['accepted'] for row in audit for e in row['edits']),
                                  proposed_edits=sum(len(row['edits']) for row in audit),
                                  failed_segments=sum(bool(row['error']) for row in audit)),
                  offline_primary_model='fusion',
                  transcript='\n'.join(f"[{s['speaker']}] {s['text']}" for s in result['segments']))
    result['engines']['offline']='SenseVoice 底稿 + '+profile['name']+' 纠错候选'
    result['metrics'].update(processing_seconds=elapsed,rtf=elapsed/max(len(samples)/16000,.001))
    return result
