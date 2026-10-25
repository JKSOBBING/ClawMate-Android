"""Export a verified SDK runtime and the compiled HAP into the isolated Android app.

This probe includes all arm64 SDK plug-ins for diagnosis. It does not measure
production APK size or perform the official CLI's dependency-based trimming.
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--project', required=True, type=Path)
parser.add_argument('--sdk', required=True, type=Path)
args = parser.parse_args()
project = args.project.resolve()
sdk = args.sdk.resolve()
app = project / '.arkui-x/android/app'
assets = app / 'src/main/assets/arkui-x'
entry = assets / 'entry'
entry.mkdir(parents=True, exist_ok=True)
hap = project / 'entry/build/default/outputs/default/entry-default-unsigned.hap'
with zipfile.ZipFile(hap) as archive:
    for member in archive.infolist():
        destination = (entry / member.filename).resolve()
        if not destination.is_relative_to(entry.resolve()):
            raise ValueError('HAP path escapes the isolated asset directory')
    archive.extractall(entry)
shutil.copytree(sdk / 'engine/systemres', assets / 'systemres', dirs_exist_ok=True)
shutil.copy2(sdk / 'arkui-x.json', assets / 'arkui-x.json')
libs = app / 'libs'
native = libs / 'arm64-v8a'
native.mkdir(parents=True, exist_ok=True)
copied = []
for folder in ('engine/lib', 'plugins/api/lib', 'plugins/component/lib'):
    for source in (sdk / folder).rglob('*'):
        if not source.is_file():
            continue
        if source.suffix == '.jar':
            target = libs / source.name
        elif source.suffix == '.so' and 'android-arm64-release' in source.parts:
            target = native / source.name
        else:
            continue
        if target.exists() and hashlib.sha256(target.read_bytes()).digest() != hashlib.sha256(source.read_bytes()).digest():
            raise ValueError('SDK contains conflicting library names: ' + source.name)
        shutil.copy2(source, target)
        copied.append(source.name)
print({'hap': str(hap), 'exportedLibraries': len(copied), 'abi': 'arm64-v8a'})
