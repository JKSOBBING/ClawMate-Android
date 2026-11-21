"""Embedded APK backend: persistent app storage and native Android libraries."""
import ctypes, ctypes.util, json, os, pathlib, subprocess, threading, time, urllib.request

server = None
api_thread = None
llama = None
summary_runtime = None
download_lock = threading.Lock()
model_status = {'state':'idle','message':''}
root = None

def start(files, native, label, config_json='{}'):
    global root, server, api_thread, summary_runtime
    if server and server.started and not server.should_exit:
        return 'ready'
    settings=json.loads(config_json)
    root=pathlib.Path(files)/settings.get('storage_directory','meeting')
    model_dir=root/settings.get('model_directory','models')
    for directory in (model_dir,root/'data',root/'logs'): directory.mkdir(parents=True,exist_ok=True)
    os.environ['MEETING_ROOT']=str(root)
    os.environ['MEETING_DATA']=str(root/'data')
    os.environ['MEETING_MODELS']=str(model_dir)
    os.environ['MEETING_DEVICE_LABEL']=label+' / APK'
    summary_port=int(settings.get('summary_port',18081))
    threads=int(settings.get('threads',4))
    os.environ['MEETING_LLM_URL']=f'http://127.0.0.1:{summary_port}/v1/chat/completions'
    os.environ['MEETING_THREADS']=str(threads)
    os.environ['OMP_NUM_THREADS']=str(threads)
    os.environ['MEETING_MODEL_IDLE_SECONDS']=str(settings.get('model_idle_seconds',120))
    native=pathlib.Path(native)
    mappings=json.loads((root/'native-libraries.json').read_text())
    loaded=set()
    def load(name):
        if name in loaded or name not in mappings:return
        entry=mappings[name]
        for dependency in entry['dependencies']:load(dependency)
        ctypes.CDLL(str(native/entry['file']),mode=ctypes.RTLD_GLOBAL)
        loaded.add(name)
    for name in mappings:load(name)
    ctypes.CDLL(str(native/'libonnxruntime.so'),mode=ctypes.RTLD_GLOBAL)
    original=ctypes.util.find_library
    ctypes.util.find_library=lambda name:str(native/mappings['libsndfile.so']['file']) if name=='sndfile' else original(name)
    import uvicorn
    from fastapi import Body, HTTPException
    from meeting_host.app import create_app
    from meeting_host.summary_runtime import SummaryRuntime
    from meeting_host.model_catalog import GROUPS, group_for, group_status
    app = create_app()
    summary_runtime=SummaryRuntime(native,model_dir/'qwen.gguf',root/'logs',summary_port,threads,settings.get('summary_idle_seconds',60))
    app.state.runtime.engine.summary_runtime=summary_runtime
    manifest=json.loads((root/'model-sources.json').read_text())
    @app.get('/api/v1/models/status')
    def status():return dict(model_status,groups=group_status(model_dir,manifest['downloads']))
    @app.post('/api/v1/models/download',status_code=202)
    def download(body: dict = Body(default={})):
        groups=body.get('groups')
        if groups is not None:
            if not isinstance(groups,list) or not groups or any(not isinstance(g,str) or g not in GROUPS for g in groups):
                raise HTTPException(422,'请选择有效的模型组')
            statuses=group_status(model_dir,manifest['downloads'])
            if any(s['id'] in groups and not s['ready'] and not s['downloadable'] for s in statuses):
                raise HTTPException(422,'所选模型暂无下载来源，请手动导入并保留模型许可')
        if download_lock.acquire(blocking=False):
            model_status.update(state='downloading',message='正在准备本地模型…')
            def work():
                try:download_models(groups,model_dir)
                finally:download_lock.release()
            threading.Thread(target=work,daemon=True).start()
        return dict(model_status)
    @app.get('/api/v1/device')
    def device():return dict(native_capture=True,app_storage=str(root),python_pid=os.getpid())
    server=uvicorn.Server(uvicorn.Config(app,host='127.0.0.1',port=int(settings.get('api_port',18080)),loop='asyncio',http='h11',ws='websockets',log_level='info'))
    api_thread=threading.Thread(target=server.run,daemon=True,name='meeting-api')
    api_thread.start()
    deadline=time.monotonic()+60
    while not server.started:
        if not api_thread.is_alive():raise RuntimeError('本地接口初始化失败，请检查启动日志后重试')
        if time.monotonic()>deadline:raise RuntimeError('本地接口初始化超时，请重新打开应用')
        time.sleep(.05)
    return 'ready'

def download_models(groups=None,model_dir=None):
    import hashlib, tarfile
    from meeting_host.model_catalog import group_for
    model_dir=model_dir or root/'models'
    def destination_for(value):
        relative=pathlib.PurePosixPath(value.replace('\\','/'))
        if relative.is_absolute() or '..' in relative.parts or relative.parts[0]!='models':raise ValueError('Unsafe model path')
        return model_dir.joinpath(*relative.parts[1:])
    model_status.update(state='downloading',message='正在准备本地模型…')
    try:
        manifest=json.loads((root/'model-sources.json').read_text())
        for index,item in enumerate(manifest['downloads']):
            if groups is not None and group_for(item['path']) not in groups:continue
            target=destination_for(item['path']);target.parent.mkdir(parents=True,exist_ok=True)
            if not (target.is_file() and target.stat().st_size==item['bytes']):
                model_status['message']=f"下载 {index+1}/{len(manifest['downloads'])}：{target.name}"
                partial=target.with_name(target.name+'.partial')
                h=hashlib.sha256()
                with urllib.request.urlopen(item['url'],timeout=60) as response,partial.open('wb') as output:
                    while chunk:=response.read(1024*1024):output.write(chunk);h.update(chunk)
                if item.get('sha256') and h.hexdigest()!=item['sha256']:raise ValueError('Model checksum mismatch: '+target.name)
                partial.replace(target)
            if item.get('extract_to'):
                destination=destination_for(item['extract_to']);destination.mkdir(parents=True,exist_ok=True)
                with tarfile.open(target) as archive:
                    for member in archive.getmembers():
                        if not member.isfile():continue
                        relative=pathlib.PurePosixPath(member.name)
                        parts=relative.parts[1:] if item.get('strip_root') else relative.parts
                        if relative.is_absolute() or '..' in parts or not parts:raise ValueError('Unsafe archive entry')
                        output=destination.joinpath(*parts)
                        output.parent.mkdir(parents=True,exist_ok=True)
                        with archive.extractfile(member) as source,output.open('wb') as writer:
                            while chunk:=source.read(1024*1024):writer.write(chunk)
        if groups:
            from meeting_host.model_catalog import group_status
            if any(s['id'] in groups and not s['ready'] for s in group_status(model_dir)):
                raise ValueError('所选模型组仍缺少文件，请检查下载清单或手动导入')
        model_status.update(state='ready',message='所选本地模型已准备')
    except Exception as error:model_status.update(state='failed',message=str(error))

def stop():
    global server, api_thread, summary_runtime
    if server:server.should_exit=True
    if api_thread:
        api_thread.join(timeout=10)
        if api_thread.is_alive():raise RuntimeError('本地接口尚未停止，请重新启动应用进程')
    if summary_runtime:summary_runtime.close()
    server=api_thread=summary_runtime=None
