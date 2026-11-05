import { util } from '@kit.ArkTS';
import { gb18030Decode } from './Gb18030Table';

// One code unit per byte: IMAP literal lengths are octets, not UTF-8 characters.
export function mailBinary(bytes: Uint8Array): string {
  let text = '';
  for (let index = 0; index < bytes.length; index++) { text += String.fromCharCode(bytes[index]); }
  return text;
}
export function mailBytes(text: string): Uint8Array {
  let bytes = new Uint8Array(text.length);
  for (let index = 0; index < text.length; index++) { bytes[index] = text.charCodeAt(index) & 255; }
  return bytes;
}

export class ImapFrames {
  private buffer: string = '';
  push(bytes: Uint8Array): string[] {
    this.buffer += mailBinary(bytes);
    if (this.buffer.length > 2 * 1024 * 1024) { throw new Error('邮件服务器响应超过安全上限'); }
    let frames: string[] = [];
    let cursor = 0;
    while (true) {
      let end = this.buffer.indexOf('\r\n', cursor);
      if (end < 0) { break; }
      let line = this.buffer.substring(cursor, end);
      let literal = /\{(\d+)\+?\}$/.exec(line);
      if (literal !== null) {
        let count = Number(literal[1]);
        if (count > 1024 * 1024) { throw new Error('邮件单段内容超过安全上限'); }
        if (this.buffer.length < end + 2 + count) { break; }
        cursor = end + 2 + count;
        continue;
      }
      frames.push(this.buffer.substring(0, end));
      this.buffer = this.buffer.substring(end + 2);
      cursor = 0;
    }
    return frames;
  }
}

export class ImapValue {
  text: string = '';
  list: boolean = false;
  children: ImapValue[] = [];
}

class ImapParser {
  private position: number = 0;
  private source: string;
  constructor(source: string) { this.source = source; }
  read(depth: number = 0): ImapValue {
    if (depth > 32) { throw new Error('邮件结构嵌套过深'); }
    while (this.source[this.position] === ' ') { this.position++; }
    let value = new ImapValue();
    let start = this.position;
    let ch = this.source[this.position++];
    if (ch === '(') {
      value.list = true;
      while (this.position < this.source.length) {
        while (this.source[this.position] === ' ') { this.position++; }
        if (this.source[this.position] === ')') { this.position++; return value; }
        value.children.push(this.read(depth + 1));
      }
      throw new Error('邮件结构不完整');
    }
    if (ch === '"') {
      while (this.position < this.source.length) {
        let next = this.source[this.position++];
        if (next === '"') { return value; }
        if (next === '\\') { next = this.source[this.position++]; }
        value.text += next;
      }
      throw new Error('邮件字符串不完整');
    }
    if (ch === '{') {
      let end = this.source.indexOf('}\r\n', start);
      if (end < 0) { throw new Error('邮件字节段无效'); }
      let length = Number(this.source.substring(start + 1, end).replace('+', ''));
      this.position = end + 3;
      if (!Number.isInteger(length) || length < 0 || this.position + length > this.source.length) {
        throw new Error('邮件字节段不完整');
      }
      value.text = this.source.substring(this.position, this.position + length);
      this.position += length;
      return value;
    }
    // FETCH section keys contain spaces inside brackets (HEADER.FIELDS (...)).
    let bracket = ch === '[' ? 1 : 0;
    while (this.position < this.source.length) {
      let next = this.source[this.position];
      if (next === '[') { bracket++; }
      if (next === ']') { bracket--; }
      if (bracket === 0 && (next === ' ' || next === ')')) { break; }
      this.position++;
    }
    value.text = this.source.substring(start, this.position);
    return value;
  }
}

export function imapFetch(frame: string): ImapValue | null {
  let start = frame.indexOf(' FETCH (');
  return start < 0 ? null : new ImapParser(frame.substring(start + 7)).read();
}
export function imapField(fetch: ImapValue, key: string): ImapValue {
  for (let index = 0; index + 1 < fetch.children.length; index += 2) {
    if (fetch.children[index].text.toUpperCase() === key.toUpperCase()) { return fetch.children[index + 1]; }
  }
  return new ImapValue();
}
export function imapBody(fetch: ImapValue): string {
  for (let index = 0; index + 1 < fetch.children.length; index += 2) {
    if (fetch.children[index].text.toUpperCase().startsWith('BODY[')) { return fetch.children[index + 1].text; }
  }
  return '';
}

export interface MailBodyPart { section: string; charset: string; encoding: string; html: boolean; size: number; }

function hasAttachment(value: ImapValue): boolean {
  if (!value.list) { return false; }
  if (value.children.length > 0 && /^(ATTACHMENT)$/i.test(value.children[0].text)) { return true; }
  for (let index = 0; index + 1 < value.children.length; index++) {
    if (/^(NAME|FILENAME)(?:\*\d*\*?)?$/i.test(value.children[index].text) && value.children[index + 1].text !== 'NIL') { return true; }
  }
  return value.children.some((child: ImapValue): boolean => hasAttachment(child));
}

export function mailBodyPart(structure: ImapValue, prefix: string = ''): MailBodyPart | null {
  let items = structure.children;
  if (items.length === 0) { return null; }
  if (items[0].list) {
    let end = 0;
    while (end < items.length && items[end].list) { end++; }
    // A multipart attachment can contain ordinary text children: skip that
    // entire container without excluding unrelated attachments in the message.
    if (items.slice(end + 1).some((value: ImapValue): boolean => hasAttachment(value))) { return null; }
    let best: MailBodyPart | null = null;
    for (let index = 0; index < items.length && items[index].list; index++) {
      let part = mailBodyPart(items[index], prefix ? prefix + '.' + (index + 1) : String(index + 1));
      if (part !== null && (best === null || (!part.html && best.html))) { best = part; }
    }
    return best;
  }
  if (items.length < 7 || items[0].text.toUpperCase() !== 'TEXT' ||
    !/^(PLAIN|HTML)$/i.test(items[1].text) || hasAttachment(structure)) { return null; }
  let charset = 'us-ascii';
  let params = items[2].children;
  for (let index = 0; index + 1 < params.length; index += 2) {
    if (params[index].text.toUpperCase() === 'CHARSET') { charset = params[index + 1].text; }
  }
  return { section: prefix || '1', charset: charset, encoding: items[5].text,
    html: items[1].text.toUpperCase() === 'HTML', size: Number(items[6].text) };
}

export function mailQuoted(value: string): string {
  if (/[\x00-\x1f\x7f]/.test(value)) { throw new Error('邮箱配置包含无效控制字符'); }
  return '"' + value.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

export function mailDecode(raw: string, encoding: string, charset: string): string {
  let bytes = mailBytes(raw);
  if (encoding.toUpperCase() === 'BASE64') {
    let base64 = raw.replace(/\s/g, '');
    // A partial fetch can end between base64 quartets.
    base64 = base64.substring(0, base64.length - base64.length % 4);
    bytes = new util.Base64Helper().decodeSync(base64);
  } else if (encoding.toUpperCase() === 'QUOTED-PRINTABLE') {
    bytes = mailBytes(raw.replace(/=\r?\n/g, '').replace(/=([0-9a-f]{2})/gi,
      (_match: string, hex: string): string => String.fromCharCode(parseInt(hex, 16))));
  }
  let name = charset.toLowerCase().replace(/^"|"$/g, '');
  if (name === 'gb2312' || name === 'gbk' || name === 'x-gbk' || name === 'cp936' || name === 'gb18030') {
    return gb18030Decode(bytes);
  }
  // 没有声明字符集、或声明成 us-ascii，却出现高位字节：按内容判断。
  // 注意 GBK 的两字节序列常常“碰巧”也是合法 UTF-8 的一部分，只看合法性不够，
  // 所以这里要求 UTF-8 解码结果里中文占多数，否则按 GB18030（交大 Exchange 代理就是这种）。
  let asciiLike = name === 'us-ascii' || name === 'ascii' || name === '' || name === 'unknown-8bit';
  if (asciiLike && !sniffUtf8(bytes)) { return gb18030Decode(bytes); }
  if (name === 'utf-8' && !sniffUtf8(bytes)) { return gb18030Decode(bytes); }
  return decodeAs(bytes, name);
}

function decodeAs(bytes: Uint8Array, name: string): string {
  try { return new util.TextDecoder(name).decodeToString(bytes); }
  catch (e) { throw new Error('暂不支持邮件字符集：' + name.replace(/[^a-z0-9_-]/gi, '').slice(0, 30)); }
}

/** 判断一串字节更像 UTF-8（中文字符正常）还是 GBK。 */
function sniffUtf8(bytes: Uint8Array): boolean {
  let hasHigh = false;
  for (let index = 0; index < bytes.length; index++) { if (bytes[index] >= 0x80) { hasHigh = true; break; } }
  if (!hasHigh) { return true; }
  if (!validUtf8(bytes)) { return false; }
  let text = '';
  try { text = new util.TextDecoder('utf-8').decodeToString(bytes); } catch (e) { return false; }
  let cjk = 0; let odd = 0;
  for (let index = 0; index < text.length; index++) {
    let code = text.charCodeAt(index);
    if (code >= 0x4E00 && code <= 0x9FFF) { cjk++; }
    // 拉丁补充、私有区、拉丁扩展等通常是“GBK 被当成 UTF-8”造成的乱码。
    else if (code >= 0xE000 && code <= 0xF8FF) { odd += 2; }
    else if ((code >= 0x80 && code <= 0x24F) || (code >= 0x250 && code <= 0x36F)) { odd++; }
  }
  return cjk >= odd;
}

/** 严格检查一串字节是否为合法 UTF-8，用于判断没有声明字符集的邮件实际编码。 */
function validUtf8(bytes: Uint8Array): boolean {
  let index = 0;
  while (index < bytes.length) {
    let b = bytes[index];
    if (b < 0x80) { index++; continue; }
    let need = 0;
    if ((b & 0xE0) === 0xC0) { need = 1; } else if ((b & 0xF0) === 0xE0) { need = 2; }
    else if ((b & 0xF8) === 0xF0) { need = 3; } else { return false; }
    if (index + need >= bytes.length) { return false; }
    for (let k = 1; k <= need; k++) { if ((bytes[index + k] & 0xC0) !== 0x80) { return false; } }
    index += need + 1;
  }
  return true;
}

export function mailHeader(raw: string, name: string): string {
  let value = mailHeaderValue(raw, name);
  if (value.length === 0) { return ''; }
  if (/[\x80-\xff]/.test(value)) { value = mailDecode(value, '8BIT', 'us-ascii'); }
  value = value.replace(/(\?=)\s+(=\?)/g, '$1$2');
  return decodeWords(value).slice(0, 1000);
}

/** 按头部块取值：只有真正的头部起始行（不以空白开头）参与比较，折叠行并入前一个头部。 */
function mailHeaderValue(raw: string, name: string): string {
  let valueParts: string[] = [];
  let wanted = name.toLowerCase() + ':';
  let current = '';
  let lines = raw.split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    let line = lines[index];
    if (line.length === 0) { continue; }
    if ((line[0] === ' ' || line[0] === '\t') && current.length > 0) {
      if (current === wanted) { valueParts.push(line.trim()); }
      continue;
    }
    let colon = line.indexOf(':');
    let key = colon > 0 ? line.substring(0, colon + 1).toLowerCase() : '';
    current = key;
    if (key === wanted) { valueParts = [line.substring(colon + 1).trim()]; }
  }
  return valueParts.join(' ').trim();
}

/** 解码 RFC 2047 编码字（`=?GB2312?B?...?=` / `=?utf-8?Q?...?=`）。 */
function decodeWords(value: string): string {
  return value.replace(/=\?([^?]+)\?([bq])\?([^?]*)\?=/gi,
    (_match: string, charset: string, encoding: string, content: string): string => {
      // charset 只到第一个 `?`；`utf-8*zh` 这类 RFC 2231 语言后缀也去掉。
      let name = charset.split('*')[0].trim();
      try {
        return mailDecode(encoding.toUpperCase() === 'Q' ? content.replace(/_/g, ' ') : content,
          encoding.toUpperCase() === 'Q' ? 'QUOTED-PRINTABLE' : 'BASE64', name);
      } catch (error) {
        // 单个编码字解不开时保留原文，不让整封邮件读取失败。
        return _match;
      }
    });
}

export function mailPlainHtml(html: string): string {
  return html.replace(/<!--[\s\S]*?-->/g, '').replace(/<(script|style|head)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, '')
    .replace(/<(br|\/p|\/div|\/tr|\/li|\/h[1-6])\b[^>]*>/gi, '\n').replace(/<[^>]*>/g, '')
    .replace(/&#(x[0-9a-f]+|\d+);/gi, (_match: string, code: string): string => {
      let point = code[0].toLowerCase() === 'x' ? parseInt(code.substring(1), 16) : Number(code);
      return point > 0 && point <= 0x10ffff ? String.fromCodePoint(point) : '';
    }).replace(/&nbsp;/gi, ' ').replace(/&lt;/gi, '<').replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"').replace(/&#39;|&apos;/gi, "'").replace(/&amp;/gi, '&')
    .replace(/[ \t]+/g, ' ').replace(/\n\s*\n\s*\n/g, '\n\n').trim();
}

export function mailInternalDate(value: string): number {
  let match = /^(\d{1,2})-([a-z]{3})-(\d{4}) (\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2})(\d{2})$/i.exec(value.trim());
  if (match === null) { return NaN; }
  let month = ['jan','feb','mar','apr','may','jun','jul','aug','sep','oct','nov','dec'].indexOf(match[2].toLowerCase());
  if (month < 0) { return NaN; }
  let offset = (Number(match[8]) * 60 + Number(match[9])) * (match[7] === '+' ? 1 : -1);
  return Date.UTC(Number(match[3]), month, Number(match[1]), Number(match[4]), Number(match[5]), Number(match[6])) - offset * 60000;
}

export function mailSearchDate(now: number, days: number): string {
  let date = new Date(now);
  date.setDate(date.getDate() + days);
  return date.getDate() + '-' + ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][date.getMonth()] + '-' + date.getFullYear();
}
