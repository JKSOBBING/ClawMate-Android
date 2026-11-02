"""Record the rehearsed PKB110 shared-app demo; requires prepared public demo tasks.

No cloud requests or private-data exports. Device coordinates are intentionally
specific to the verified 1080x2374 layout. Review all captured frames before sharing.
"""
import argparse
import json
import pathlib
import subprocess
import time
import os

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--output', required=True)
p.add_argument('--scrcpy', required=True)
args = p.parse_args()
out = pathlib.Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=False)
base = [args.adb, '-s', args.serial]
def adb(*v):
    return subprocess.run(base + list(map(str, v)), check=True, capture_output=True).stdout
def tap(x, y):
    adb('shell', 'input', 'tap', x, y)
    time.sleep(1)
def swipe():
    adb('shell', 'input', 'swipe', 1010, 2000, 1010, 800, 600)
    time.sleep(1)
pkg = 'com.clawmate.shared'
index = json.loads(adb('exec-out','run-as',pkg,'cat','files/files/workflows/task-index.json'))
if [t['id'] for t in index['tasks'][:2]] != ['mentor-demo-cancel','mentor-demo-success']:
    raise SystemExit('Prepared demo tasks must be the first two entries; see docs/MENTOR_DEMO.md')
if '1080x2374' not in adb('shell','wm','size').decode():
    raise SystemExit('This rehearsed driver requires the verified 1080x2374 display')
adb('shell', 'am', 'force-stop', pkg)
adb('shell', 'am', 'start', '-n', pkg + '/.SharedActivity')
time.sleep(3)
rec = subprocess.Popen([args.scrcpy, '--serial', args.serial, '--no-playback', '--no-audio', '--no-control', '--time-limit=125', '--record='+str(out/'raw.mp4')], env={**os.environ,'ADB':args.adb}, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(3)
if rec.poll() is not None:
    raise RuntimeError(rec.communicate())
start = time.monotonic()
stages = []
def stage(name, seconds=6):
    stages.append({'time': round(time.monotonic()-start, 2), 'title': name})
    data = adb('exec-out', 'screencap', '-p')
    (out / ('%02d.png' % len(stages))).write_bytes(data)
    print(name, flush=True)
    time.sleep(seconds)
try:
    stage('共享 ArkTS / ArkUI 前端在 Android 真机运行', 7)
    tap(540,2190)
    stage('聊天界面与动态形象；本片不展示云模型效果', 6)
    tap(730,2190); tap(810,490)
    stage('预先配置的公开演示任务：本机等待与 echo', 5)
    tap(255,1955)
    stage('实际执行本机工作流：等待 3 秒，再输出文本', 6)
    stage('结果状态 success：由 Android 执行器实际产生', 7)
    tap(255,1390)
    stage('第二条任务：30 秒等待，运行期间可停止', 4)
    tap(255,1480)
    stage('主动停止后变为 cancelled，终止后续步骤', 7)
    tap(535,1480); swipe(); swipe()
    stage('共享图形编辑器：查看节点与失败策略', 8)
    # Close the editor by restarting without modifying its configuration.
    adb('shell','am','force-stop',pkg)
    adb('shell','am','start','-n',pkg+'/.SharedActivity')
    time.sleep(3)
    tap(730,2190); tap(540,730)
    stage('Android 本应用截图入口：先预览、后确认', 5)
    tap(540,880)
    stage('真实截图已生成本地预览，本次不上传', 8)
    tap(540,250); tap(157,2190)
    stage('迁移阶段成果：共享界面 + Android 本机能力', 7)
finally:
    (out/'chapters.json').write_text(json.dumps(stages,ensure_ascii=False,indent=2),encoding='utf8')
    stdout, stderr = rec.communicate(timeout=60)
    (out/'recorder.log').write_bytes(stdout+b'\n'+stderr)
    if rec.returncode != 0:
        raise RuntimeError('Recording failed; inspect recorder.log')
print(out/'raw.mp4')
