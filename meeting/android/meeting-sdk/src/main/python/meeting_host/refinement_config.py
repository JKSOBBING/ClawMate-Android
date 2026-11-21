"""Device-local refinement profiles; secrets never appear in public snapshots."""
import copy
import json
import os
import threading
import uuid
from pathlib import Path
from urllib.parse import urlsplit

BUILTINS = [
    dict(id='local-firered', name='本地 FireRed · 逐段精修', kind='local_firered', model='firered2', builtin=True),
    dict(id='local-qwen', name='本地 Qwen3-ASR-0.6B', kind='local_qwen', model='qwen3_asr', builtin=True),
    dict(id='local-sensevoice', name='仅本地 SenseVoice', kind='local_sensevoice', model='sensevoice', builtin=True),
]


def public_profile(profile):
    return {**{k:v for k,v in profile.items() if k != 'api_key'},
            'has_api_key':bool(profile.get('api_key'))}


class RefinementConfig:
    def __init__(self, path):
        self.path = Path(path)
        self.lock = threading.RLock()

    def _read(self):
        if not self.path.exists():
            return dict(active_id='local-qwen', auto_enabled=True, profiles=[])
        return json.loads(self.path.read_text(encoding='utf-8'))

    def _write(self, data):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix('.tmp')
        with temp.open('w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False)
        if os.name != 'nt': os.chmod(temp, 0o600)
        os.replace(temp, self.path)

    def view(self):
        with self.lock:
            data = self._read()
            return dict(active_id=data['active_id'], auto_enabled=data['auto_enabled'],
                        profiles=[public_profile(p) for p in BUILTINS+data['profiles']])

    def snapshot(self, profile_id=None):
        with self.lock:
            data = self._read()
            profile_id = profile_id or data['active_id']
            for profile in BUILTINS+data['profiles']:
                if profile['id'] == profile_id:
                    return copy.deepcopy(profile)
            raise ValueError('精修模型配置不存在，请重新选择')

    def recording_selection(self):
        with self.lock:
            data=self._read()
            return data['auto_enabled'],self.snapshot(data['active_id'])

    def settings(self, active_id, auto_enabled):
        with self.lock:
            self.snapshot(active_id)
            data = self._read()
            data.update(active_id=active_id, auto_enabled=auto_enabled)
            self._write(data)
            return self.view()

    def save(self, values, profile_id=None):
        with self.lock:
            data = self._read()
            previous = None
            if profile_id:
                if any(p['id']==profile_id for p in BUILTINS): raise ValueError('内置模型无需编辑，可直接切换')
                previous = next((p for p in data['profiles'] if p['id']==profile_id), None)
                if previous is None: raise ValueError('精修模型配置不存在')
            base = values['base_url'].strip().rstrip('/')
            url = urlsplit(base)
            if url.scheme not in ('http','https') or not url.hostname or url.username or url.password or url.query or url.fragment:
                raise ValueError('接口地址必须是 http(s) 基础地址，不含账号、查询参数或片段')
            # Remote credentials should not accidentally be pasted into the model field.
            name, model = values['name'].strip(), values['model'].strip()
            if not name or not model or any(c in model for c in '\r\n'):
                raise ValueError('请填写名称和有效模型 ID')
            key = values.get('api_key')
            if key is None: key = (previous or {}).get('api_key','')
            key = key.strip()
            if any(c in key for c in '\r\n'): raise ValueError('API Key 格式无效')
            profile = dict(id=profile_id or 'custom-'+uuid.uuid4().hex[:12], name=name,
                           kind=values['kind'], base_url=base, model=model, api_key=key,
                           timeout_seconds=values['timeout_seconds'], builtin=False)
            data['profiles'] = [p for p in data['profiles'] if p['id']!=profile['id']]+[profile]
            self._write(data)
            return public_profile(profile)

    def delete(self, profile_id):
        with self.lock:
            data = self._read()
            if any(p['id']==profile_id for p in BUILTINS): raise ValueError('不能删除内置模型')
            if not any(p['id']==profile_id for p in data['profiles']): raise ValueError('精修模型配置不存在')
            data['profiles'] = [p for p in data['profiles'] if p['id']!=profile_id]
            if data['active_id']==profile_id: data['active_id']='local-qwen'
            self._write(data)
            return self.view()
