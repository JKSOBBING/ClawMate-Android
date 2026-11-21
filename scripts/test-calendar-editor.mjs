import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { setImmediate } from 'node:timers/promises';
import { runInNewContext } from 'node:vm';

// Run the ArkTS page controller with a controllable local clock and Calendar Provider.
const pageUrl = new URL('../shared-frontend/adapters/pages/settings/CalendarSourcesPage.ets', import.meta.url);
const page = readFileSync(pageUrl, 'utf8');
const controller = page.slice(0, page.indexOf('  @Builder'))
  .replace(/^import .*;\r?\n/gm, '').replace(/^@(Entry|Component)\s*$/gm, '')
  .replace('struct PersonalSourcesPage', 'class PersonalSourcesPage').replace(/@State /g, '');
const script = stripTypeScriptTypes(controller + '}\nPersonalSourcesPage');

function fixture(permission = async () => true) {
  let now = new Date(2028, 1, 28, 23, 59, 40).getTime();
  class Clock extends Date {
    constructor(...parts) { super(...(parts.length ? parts : [now])); }
    static now() { return now; }
  }
  const permissions = [], writes = [], dialogs = [];
  const appStorage = new Map();
  const Page = runInNewContext(script, {
    Date: Clock, Scroller: class { scrollTo() {} }, PersonalSourceSync: class {}, CloudModelClient: class {},
    AppStorage: { get: key => appStorage.get(key), setOrCreate: (key, value) => appStorage.set(key, value) },
    PersonalSources: {
      empty: () => ({ items: [], sources: [], selected: [], syncedAt: 0 }),
      calendarPermission(write) { permissions.push(write); return permission(); },
      async operation(name, raw) {
        if (name === 'calendarTargets') return JSON.stringify([{ id: '7', name: '验收日历' }]);
        assert.equal(name, 'calendarInsert');
        writes.push(JSON.parse(raw));
        return '{}';
      }
    }
  });
  const view = new Page();
  view.mounted = true;
  view.active = true;
  view.getUIContext = () => ({ showDatePickerDialog(options) { dialogs.push(options); } });
  function tap(day, after = 100) { now += after; view.tapDay(day); }
  function wheel(start) {
    view.pickEventTime(start);
    const dialog = dialogs.shift();
    assert.equal(dialog.showTime, true);
    assert.equal(dialog.useMilitaryTime, true);
    assert.equal(dialog.selected.getTime(), view.eventTime(start ? view.eventStart : view.eventEnd));
    return dialog;
  }
  return { view, tap, wheel, permissions, writes, appStorage };
}

const slow = fixture();
slow.tap('2028-02-27');
assert.equal(slow.view.selectedDate, '2028-02-27');
assert.equal(slow.view.editingEvent, false);
slow.tap('2028-02-28');
slow.tap('2028-02-28', 1000);
assert.deepEqual(slow.permissions, [], 'separate dates and slow taps only select the day');
slow.tap('2028-02-28');
await setImmediate();
assert.deepEqual(slow.permissions, [true]);
assert.equal(slow.view.editingEvent, true);
assert.equal(slow.view.eventStart.slice(0, 10), '2028-02-28', 'late-evening default remains on tapped day');
assert.ok(slow.view.eventTime(slow.view.eventEnd) > slow.view.eventTime(slow.view.eventStart));

let grant;
const pending = fixture(() => new Promise(resolve => { grant = resolve; }));
pending.view.cursorYear = 2028;
pending.view.cursorMonth = 3;
const adjacent = pending.view.monthCells().find(cell => cell.date === '2028-02-29');
assert.equal(adjacent.inMonth, false);
pending.tap(adjacent.date);
pending.tap(adjacent.date);
pending.tap(adjacent.date);
pending.tap('2028-03-01');
assert.deepEqual(pending.permissions, [true], 'third tap cannot open a second editor');
grant(true);
await setImmediate();
assert.equal(pending.view.editingEvent, true);
assert.equal(pending.view.eventStart.slice(0, 10), adjacent.date, 'permission delay preserves the tapped date');
assert.deepEqual(pending.writes, []);

const { view, wheel, writes } = pending;
const initialStart = view.eventStart, initialEnd = view.eventEnd;
const cancelled = wheel(true);
cancelled.onDateChange?.(new Date(2028, 2, 2, 12, 5));
cancelled.onCancel?.();
assert.equal(view.eventStart, initialStart);
assert.equal(view.eventEnd, initialEnd);
const start = new Date(2028, 1, 29, 23, 52);
wheel(true).onDateAccept(start);
assert.equal(view.eventStart, '2028-02-29 23:52');
assert.equal(view.eventEnd, initialEnd);
view.eventTitle = '  跨月日程  ';
wheel(false).onDateAccept(start);
await view.saveEvent();
assert.deepEqual(writes, [], 'an empty time interval cannot be saved');
assert.equal(view.editingEvent, true);
const end = new Date(2028, 2, 1, 0, 7);
wheel(false).onDateAccept(end);
assert.equal(view.eventEnd, '2028-03-01 00:07');
await view.saveEvent();
assert.deepEqual(writes, [{ title: '跨月日程', start: start.getTime(), end: end.getTime(), calendarId: '7' }]);
assert.equal(view.editingEvent, false);
assert.equal(pending.appStorage.get('androidCalendarSourceRevision'), 1);
assert.equal(view.selectedDate, adjacent.date);

const denied = fixture(async () => false);
denied.tap('2028-03-01');
denied.tap('2028-03-01');
await setImmediate();
assert.deepEqual(denied.permissions, [true]);
assert.equal(denied.view.editingEvent, false);
assert.deepEqual(denied.writes, []);

assert.ok(/\.onClick\(\(\)\s*=>\s*\{\s*this\.tapDay\(dateKeyValue\)/.test(page), 'date cells must handle taps');
assert.ok(!/this\.Action\(\{\s*label:\s*'添加系统日程'/.test(page), 'remove the separate add action');
assert.ok(!/TextInput\(\{[^}]*this\.event(?:Start|End)/.test(page), 'time fields must use the wheel');
assert.ok(page.includes("this.EventTimeRow('开始时间', true)"));
assert.ok(page.includes("this.EventTimeRow('结束时间', false)"));
console.log('PASS date taps, delayed calendar permission, local date/time wheels and cross-month system event write');
