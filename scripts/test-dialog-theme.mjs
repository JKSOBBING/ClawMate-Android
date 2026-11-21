import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { styleDialogs } from '../shared-frontend/dialog-style.mjs';

function files(directory, suffix) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name);
    return entry.isDirectory() ? files(file, suffix) : file.endsWith(suffix) ? [file] : [];
  });
}
const main = '.arkui-x-validation/application/project/entry/src/main';
const pages = JSON.parse(fs.readFileSync(main + '/resources/base/profile/main_pages.json')).src;
for (const page of pages) assert.ok(fs.existsSync(path.join(main, 'ets', page + '.ets')), page);
let dialogs = 0, sheets = 0, custom = 0;
for (const file of files(main + '/ets', '.ets')) {
  const source = fs.readFileSync(file, 'utf8');
  const calls = source.match(/\b(?:showAlertDialog|AlertDialog\.show|showDialog)\(\s*\{/g) || [];
  dialogs += calls.length;
  if (calls.length) assert.equal(styleDialogs(source), source, file + ' contains a dialog outside the prepared theme');
  if (source.includes('.openCustomDialog(content,')) {
    custom++;
    assert.match(source, /\.backgroundColor\(AppTheme\./, file);
    assert.match(source, /\.borderRadius\(/, file);
  }
  for (const sheet of source.matchAll(/\.bindSheet\([\s\S]*?\}\)/g)) {
    sheets++;
    assert.match(sheet[0], /backgroundColor: (?:AppTheme\.background|\$r\('app\.color\.ui_surface'\))/, file);
  }
}
let native = 0;
for (const root of ['android-capabilities/src/main/java', 'shared-app/src/main/java']) {
  for (const file of files(root, '.kt')) {
    for (const call of fs.readFileSync(file, 'utf8').matchAll(/AlertDialog\.Builder\(([^)]*)\)/g)) {
      native++;
      assert.match(call[1], /R\.style\.ClawMateDialog/, file);
    }
  }
}
const nested = "ui.showAlertDialog({primaryButton:{value:'继续',action:()=>{promptAction.showDialog({title:'详情',buttons:[{text:'删除', color:'#B42318'}]});}}});";
const branded = styleDialogs(nested);
assert.equal((branded.match(/shadow:/g) || []).length, 2);
assert.ok(branded.includes("ui_accent_red"));
assert.equal(styleDialogs(branded), branded);
assert.ok(dialogs && sheets && custom && native);
console.log(JSON.stringify({routes:pages.length, dialogs, custom, sheets, native}));
