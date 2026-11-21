import fs from 'node:fs';
import assert from 'node:assert/strict';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';

const source = fs.readFileSync('shared-frontend/adapters/utils/gallery/GalleryPhotoPicker.ets', 'utf8')
  .replace(/^import .*;\s*/gm, '').replace(/^export /gm, '');
for (const status of ['done', 'cancelled', 'failed', 'incompatible']) {
  const calls = [];
  const uri = 'content://fixture.provider/document/test';
  const Picker = runInNewContext(stripTypeScriptTypes(source) + '\nGalleryPhotoPicker;', {
    setTimeout, Bridge: { createBridge: () => ({ callMethod: async (method, arg) => {
      calls.push([method, arg]);
      if (method === 'photoPickerStart') return 'operation';
      if (method === 'photoPickerPoll') return JSON.stringify({ v: status === 'incompatible' ? 2 : 1, status, uris: [uri, uri + '2'] });
      return true;
    } }) }
  });
  if (status === 'done') assert.deepEqual(Array.from(await new Picker().selectImageUris(1)), [uri]);
  else if (status === 'cancelled') assert.equal((await new Picker().selectImageUris()).length, 0);
  else await assert.rejects(new Picker().selectImageUris());
  assert.equal(calls.at(-1)[0], 'photoPickerCancel');
}
console.log('PASS content URI preservation, selection bound, cancel, failure, version and cleanup');
