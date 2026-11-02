"""Provision a debug shared host from an ignored local JSON, preserving other settings.

Example: python scripts/configure-shared-cloud.py --adb <adb> --config <local.json>
JSON keys: baseUrl (HTTPS), model, apiKey. Never changes privacy or cloud grants.
"""
import argparse
import json
import subprocess
import xml.etree.ElementTree as ET
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--config', required=True)
    args = parser.parse_args()
    with open(args.config, encoding='utf-8-sig') as source:
        config = json.load(source)
    url = urlparse(config['baseUrl'])
    if url.scheme != 'https' or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError('baseUrl must be HTTPS without credentials, query or fragment')
    if not config['model'].strip() or not config['apiKey'].strip():
        raise ValueError('model and apiKey are required')
    adb = [args.adb, '-s', args.serial]

    def run(*command, data=None):
        result = subprocess.run(adb + list(command), input=data, capture_output=True, check=False)
        if result.returncode:
            raise RuntimeError('ADB operation failed (details suppressed to protect credentials)')
        return result.stdout

    package = 'com.clawmate.shared'
    target = 'files/preference/shared_frontend_settings'
    run('shell', 'am', 'force-stop', package)
    # Require an existing settings file; onboarding and consent remain user actions.
    original = run('exec-out', 'run-as', package, 'cat', target)
    root = ET.fromstring(original)
    if root.tag != 'preferences':
        raise ValueError('Unexpected settings format')
    for name, value in [('cloudPlannerBaseUrl', config['baseUrl'].rstrip('/')),
                        ('cloudPlannerModel', config['model'].strip()),
                        ('cloudPlannerApiKey', config['apiKey'])]:
        for old in list(root):
            if old.get('key') == name:
                root.remove(old)
        ET.SubElement(root, 'string', {'key': name}).text = value
    updated = ET.tostring(root, encoding='utf-8', xml_declaration=True)
    # Backup remains in the application's private directory, never on shared storage.
    run('shell', 'run-as', package, 'cp', target, target + '.cloud-backup')
    run('shell', 'run-as', package, 'sh', '-c',
        f"'cat > {target}.cloud-new && chmod 600 {target}.cloud-new && mv {target}.cloud-new {target}'", data=updated)
    if run('exec-out', 'run-as', package, 'cat', target) != updated:
        run('shell', 'run-as', package, 'cp', target + '.cloud-backup', target)
        raise RuntimeError('Readback failed; original settings restored')
    print('Planner configuration saved and verified; privacy/cloud grants preserved. Restart the app.')


if __name__ == '__main__':
    main()
