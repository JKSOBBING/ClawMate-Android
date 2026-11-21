"""Keep baseline punctuation; treat model disagreement as evidence to check."""
from difflib import SequenceMatcher
import unicodedata


def tokens(text):
    chars,positions=[],[]
    for index,char in enumerate(text):
        if char.isalnum():
            for normalized in unicodedata.normalize('NFKC',char).casefold():
                if normalized.isalnum(): chars.append(normalized);positions.append(index)
    return ''.join(chars),positions


def punctuation_count(text):
    return sum(unicodedata.category(c).startswith('P') for c in text)


def fuse(baseline, candidate, verify=None):
    a,positions=tokens(baseline)
    b,candidate_positions=tokens(candidate)
    matcher=SequenceMatcher(None,a,b,autojunk=False)
    edits=[]
    for tag,i,j,k,l in matcher.get_opcodes():
        if tag=='equal': continue
        old,new=a[i:j],b[k:l]
        # Short lexical replacements only. Insertions/deletions, numbers and rewrites stay suggestions.
        eligible=(tag=='replace' and 0<len(old)<=4 and 0<len(new)<=4
                  and matcher.ratio()>=.75 and len(a)>=6
                  and not any(c.isdigit() or c in '零〇一二三四五六七八九十百千万亿两' for c in old+new))
        replacement=candidate[candidate_positions[k]:candidate_positions[l-1]+1] if new else ''
        eligible=eligible and replacement.isalnum()
        edits.append(dict(start=i,end=j,before=old,after=new,eligible=eligible,
                          replacement=replacement,
                          accepted=False,reason='待复核' if eligible else '保留底稿：增删、数字或大幅改写'))
    checked=''
    if any(e['eligible'] for e in edits) and verify is not None:
        checked=tokens(verify())[0]
    replacements=[]
    for edit in edits:
        i,j=edit['start'],edit['end']
        left,right=a[max(0,i-3):i],a[j:j+3]
        target=left+edit['after']+right
        # Re-decoded SenseVoice context must independently support the proposed phrase.
        if edit['eligible'] and checked and target in checked and left+edit['before']+right not in checked:
            start,end=positions[i],positions[j-1]+1
            # Do not delete baseline sentence punctuation embedded in an aligned change.
            if all(c.isalnum() for c in baseline[start:end]):
                replacements.append((start,end,edit['replacement']))
                edit.update(accepted=True,reason='上下文音频重识别支持')
        elif edit['eligible']:
            edit['reason']='保留底稿：音频复核证据不足'
    text=baseline
    for start,end,replacement in reversed(replacements): text=text[:start]+replacement+text[end:]
    return dict(text=text,edits=edits,baseline_punctuation=punctuation_count(baseline),
                candidate_punctuation=punctuation_count(candidate),final_punctuation=punctuation_count(text))
