import fs from 'node:fs';
import path from 'node:path';

// Limit substitutions to dialog declarations, preserving their handlers and button order.
export function styleDialogs(source) {
  const calls = /\b(?:showAlertDialog|AlertDialog\.show|showDialog)\(\s*\{/g;
  let match;
  const edits = [];
  while ((match = calls.exec(source))) {
    const start = calls.lastIndex;
    let end = start, depth = 1, quote = '';
    for (; end < source.length && depth; end++) {
      const ch = source[end];
      if (quote) {
        if (ch === '\\') { end++; continue; }
        if (ch === quote) quote = '';
      } else if (ch === '"' || ch === "'" || ch === '\x60') quote = ch;
      else if (ch === '/' && source[end + 1] === '/') {
        const next = source.indexOf('\n', end);
        end = next < 0 ? source.length : next;
      } else if (ch === '{') depth++;
      else if (ch === '}') depth--;
    }
    if (depth) throw new Error('Unclosed dialog options');
    const body = source.slice(start, end - 1);
    const style = [];
    if (!/\balignment\s*:/.test(body)) style.push('alignment: DialogAlignment.Center');
    if (!/\bbackgroundColor\s*:/.test(body)) style.push("backgroundColor: $r('app.color.ui_surface')");
    if (!/\bshadow\s*:/.test(body)) style.push('shadow: ShadowStyle.OUTER_DEFAULT_MD');
    if (!/showDialog\(/.test(match[0]) && !/\bcornerRadius\s*:/.test(body)) style.push('cornerRadius: 24');
    const colored = styleDialogs(body).replace(/(\bcolor:\s*)'(#[\da-f]{6})'/gi, (full, key, color) => {
      const token = /^(#B42318|#FF4D4F|#D92D20|#C62828)$/i.test(color) ? 'ui_accent_red' :
        /^(#65718B|#666666|#737A87|#7C879A)$/i.test(color) ? 'ui_text_secondary' : 'ui_accent_blue';
      return key + "$r('app.color." + token + "')";
    });
    edits.push([start, end - 1, (style.length ? ' ' + style.join(', ') + ', ' : '') + colored]);
    calls.lastIndex = end;
  }
  for (const [start, end, text] of edits.reverse()) source = source.slice(0, start) + text + source.slice(end);
  return source;
}

export function styleFrontendDialogs(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) styleFrontendDialogs(file);
    else if (entry.name.endsWith('.ets')) {
      const original = fs.readFileSync(file, 'utf8');
      const styled = styleDialogs(original);
      if (styled !== original) fs.writeFileSync(file, styled);
    }
  }
}
