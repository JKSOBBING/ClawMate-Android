"""Render a reviewed screenrecord with Chinese chapter captions (Pillow + FFmpeg)."""
import argparse,json,pathlib,subprocess
from PIL import Image,ImageDraw,ImageFont
p=argparse.ArgumentParser()
p.add_argument('--take',required=True)
p.add_argument('--output',required=True)
p.add_argument('--font',required=True)
p.add_argument('--caption-offset',type=float,default=2.0,help='Calibrate against actual recording frames')
a=p.parse_args();take=pathlib.Path(a.take).resolve();out=pathlib.Path(a.output).resolve()
out.parent.mkdir(parents=True,exist_ok=True)
if out.exists(): raise SystemExit('Output exists; choose a new name')
def font(n): return ImageFont.truetype(a.font,n)
im=Image.new('RGB',(1920,1080),'#eef3fa');d=ImageDraw.Draw(im)
d.rounded_rectangle((70,35,615,1045),radius=40,fill='#17233b')
d.text((700,110),'ClawMate',font=font(72),fill='#152747')
d.text((700,210),'鸿蒙 → Android 迁移演示',font=font(46),fill='#24416c')
d.text((704,310),'OPPO 真机录屏  /  共享版  /  2026-11-02',font=font(27),fill='#526884')
d.rounded_rectangle((690,420,1840,660),radius=22,fill='white')
d.text((720,445),'当前演示',font=font(24),fill='#3476d5')
for i,t in enumerate(['共享 ArkTS / ArkUI-X 前端 + Kotlin 平台适配',
                       '公开演示任务：真实本机执行与取消',
                       '截图先本地预览，确认后才允许上传']):
 d.text((710,730+i*60),t,font=font(30),fill='#24416c')
d.text((710,970),'范围：不代表云模型质量、跨应用自动循环或发布验收通过',font=font(23),fill='#66758c')
im.save(take/'layout.png')
duration=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',str(take/'raw.mp4')]))
chapters=json.loads((take/'chapters.json').read_text(encoding='utf8'))
for i,c in enumerate(chapters):
 c['time']=min(duration,c['time']+(a.caption_offset if i else 0))
def ts(t):
 n=int(t*100);return f'{n//360000}:{n//6000%60:02d}:{n//100%60:02d}.{n%100:02d}'
head='''[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Microsoft YaHei,35,&H00472715,&H00472715,&H00FFFFFF,&H00FFFFFF,0,0,0,0,100,100,0,0,1,0,0,7,720,120,510,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
'''
for i,c in enumerate(chapters):
 end=chapters[i+1]['time'] if i+1<len(chapters) else duration
 head+=f"Dialogue: 0,{ts(c['time'])},{ts(end)},Default,,0,0,0,,{c['title']}\n"
(take/'captions.ass').write_text(head,encoding='utf-8-sig')
subprocess.run(['ffmpeg','-hide_banner','-loglevel','warning','-i','raw.mp4','-loop','1','-i','layout.png',
 '-filter_complex','[0:v]crop=1080:2250:0:105,scale=460:958:flags=lanczos[phone];[1:v][phone]overlay=112:61,ass=captions.ass[v]',
 '-map','[v]','-t',str(duration),'-r','30','-c:v','libx264','-crf','20','-preset','fast','-pix_fmt','yuv420p','-movflags','+faststart',str(out)],cwd=take,check=True)
print(out)
