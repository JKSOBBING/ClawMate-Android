"""Evaluation-only normalization; never rewrites stored model outputs."""
import unicodedata
from opencc import OpenCC
converter=OpenCC('t2s')
def normalize(text,simplify=True):
    text=unicodedata.normalize('NFKC',text).lower()
    if simplify:text=converter.convert(text)
    text=text.translate(str.maketrans('零一二三四五六七八九','0123456789'))
    return ''.join(c for c in text if not c.isspace() and not unicodedata.category(c).startswith('P'))
def cer(reference,text,simplify=True):
    a,b=normalize(reference,simplify),normalize(text,simplify);row=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        nxt=[i]
        for j,y in enumerate(b,1):nxt.append(min(row[j]+1,nxt[-1]+1,row[j-1]+(x!=y)))
        row=nxt
    return row[-1]/max(len(a),1)
