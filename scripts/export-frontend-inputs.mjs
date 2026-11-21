import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkedPath, lockedInputs } from '../shared-frontend/bootstrap.mjs';

const repo = fileURLToPath(new URL('../', import.meta.url));
const [upstream] = process.argv.slice(2);
if (process.argv.length !== 3) throw new Error('Usage: node scripts/export-frontend-inputs.mjs <upstream Git checkout>');
const output = checkedPath(repo, '.arkui-x-validation/upstream-inputs');
if (fs.existsSync(output)) throw new Error('Pinned inputs already exist at ' + output);
fs.mkdirSync(path.dirname(output), { recursive: true });
const staging = fs.mkdtempSync(path.join(path.dirname(output), 'inputs-'));
try {
  const { files } = lockedInputs(repo);
  for (const input of files) {
    const bytes = execFileSync('git', ['-C', upstream, 'cat-file', 'blob', input.ref + ':' + input.path],
      { maxBuffer: 8 * 1024 * 1024, windowsHide: true });
    const hash = createHash('sha1').update('blob ' + bytes.length + '\0').update(bytes).digest('hex');
    if (hash !== input.gitBlob) throw new Error('Pinned blob mismatch: ' + input.path);
    const name = path.resolve(staging, input.path);
    if (!name.startsWith(staging + path.sep)) throw new Error('Invalid locked path: ' + input.path);
    fs.mkdirSync(path.dirname(name), { recursive: true });
    fs.writeFileSync(name, bytes, { flag: 'wx' });
  }
  fs.renameSync(staging, output);
  console.log(files.length + ' verified inputs in ' + output);
} finally {
  if (fs.existsSync(staging)) fs.rmSync(staging, { recursive: true });
}
