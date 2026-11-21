// Runs Code.js + Core.js against the fake runtime in gas.js: endpoints, sheet
// reads, settings in script properties, token checks. The Exercise tab here is made up but laid out
// like the real one (dates in row 2 from column D, newest first, a spacer column,
// section labels in column B carried down, unit suffixes on exercise names).
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadScript, dateCell, SHEET_ID_FOR_TESTS } = require('./gas.js');

const TOKEN = 't0k';
const PAGE = 'p4ge';
const TZ = 'America/New_York';

function exerciseTab() {
  const d = (iso) => dateCell(iso);
  return [
    ['Exercise Record'],
    ['', '', 'Date', d('2026-11-17'), d('2026-11-16'), '', d('2026-11-15')],
    ['', '', '', '', '', '', '0e'], // a note row before any section
    ['', 'Push', 'Incline bench press [lb per side] (count)', '1/2:45(8)', '', '', '1/2:45(7)'],
    ['', '', 'Cable overhead tricep extension [lb total] (count)', '3/4:35(9)', '', '', ''],
    [],
    ['', 'Pull', 'Ring rows [difficulty] (count)', '5/6:-1(8)', '', 'stray', '5/6:-1(8)'],
    ['', '', 'Rope bicep curls [lb total] (count)', '7/8:25(8)', '', '', ''],
    [],
    ['', 'Legs', 'Squats [lb per side] (count) (s:17,2)', '', '1/2:60(7)', '', ''],
    ['', '', 'Deadlifts [lb per side] (count:1e)', '', '3/4:55(8)', '', ''],
    ['', '', 'Slanted tib raises [lb total] (count)', '', '5/6:20(12)', '', ''],
    [],
    ['', 'Core', 'Foot-fixed weighted crunches [lb total] (count)', '9/10:5(10)', '7/8:5(8)', '', ''],
    [],
    ['', 'Free', 'Figure skating [hr.min]', '', '', '', '2:1.20'],
  ];
}

const GOALS = [
  { name: 'Upper', rules: [{ type: 'section', name: 'Push', min: 2 }, { type: 'section', name: 'Pull', min: 2 },
    { type: 'section', name: 'Core', min: 1 }] },
  { name: 'Lower', rules: [{ type: 'section', name: 'Legs', min: 3 }, { type: 'section', name: 'Core', min: 1 }] },
  { name: 'Skate', rules: [{ type: 'exercise', name: 'Figure skating', min: 1 }] },
];

// Goals saved long ago, so setup() doesn't seed its own (they'd be dated by the real clock).
function setup(props = {}) {
  const s = loadScript({
    sheets: { Exercise: exerciseTab() },
    timeZone: TZ,
    props: { SHEET_ID: SHEET_ID_FOR_TESTS, TOKEN, PAGE_TOKEN: PAGE, ALLOW_FAKE_TODAY: 'true', 'GOALS_2026-01-01': JSON.stringify(GOALS), ...props },
  });
  s.ctx.setup();
  return s;
}

const settingKeys = (s) => Object.keys(s.props).filter((k) => /_\d{4}-\d{2}-\d{2}$/.test(k)).sort();

test('setup keeps an existing token, adds a separate page token, seeds goals once and adds no tabs', () => {
  const s = loadScript({ sheets: { Exercise: exerciseTab() }, timeZone: TZ, props: { SHEET_ID: SHEET_ID_FOR_TESTS, TOKEN } });
  s.ctx.setup();
  const page = s.props.PAGE_TOKEN;
  s.ctx.setup();
  assert.equal(s.props.TOKEN, TOKEN);
  assert.ok(page && page !== TOKEN);
  assert.equal(s.props.PAGE_TOKEN, page);
  const goals = settingKeys(s);
  assert.equal(goals.length, 1);
  assert.match(goals[0], /^GOALS_/);
  assert.deepEqual(JSON.parse(s.props[goals[0]]).map((w) => w.name), ['Upper', 'Lower', 'Skate']);
  assert.deepEqual(Object.keys(s.ss.sheets), ['Exercise']);
});

test('setup needs SHEET_ID and an Exercise tab', () => {
  const none = loadScript({ sheets: { Exercise: exerciseTab() }, props: {} });
  assert.throws(() => none.ctx.setup(), /SHEET_ID/);
  const wrong = loadScript({ sheets: { Log: [] }, props: { SHEET_ID: SHEET_ID_FOR_TESTS } });
  assert.throws(() => wrong.ctx.setup(), /no Exercise tab/);
});

test('a wrong or missing token is refused', () => {
  const s = setup();
  assert.deepEqual(s.get({ token: 'nope' }), { error: 'unauthorized' });
  assert.deepEqual(s.get({}), { error: 'unauthorized' });
  assert.deepEqual(s.post({ token: 'nope' }, { action: 'saveDefault' }), { error: 'unauthorized' });
});

test('the page token opens only the page, and the app token not the page', () => {
  const s = setup();
  assert.deepEqual(s.get({ token: PAGE }), { error: 'unauthorized' });
  assert.deepEqual(s.get({ token: PAGE, action: 'schedule' }), { error: 'unauthorized' });
  assert.deepEqual(s.post({ token: PAGE }, { action: 'skipToday' }), { error: 'unauthorized' });
  assert.match(s.page({ token: TOKEN }), /Unauthorized/);
  assert.doesNotMatch(s.page({ token: PAGE }), /Unauthorized/);
  assert.doesNotMatch(s.page({ token: PAGE }), new RegExp(TOKEN));
  // Not when it's missing or the same as the app's.
  assert.match(setup({ PAGE_TOKEN: TOKEN }).page({ token: TOKEN }), /Unauthorized/);
  const none = setup();
  delete none.props.PAGE_TOKEN;
  assert.match(none.page({ token: PAGE }), /Unauthorized/);
});

test('status reads the Exercise tab: sections, exercise goals, time zone', () => {
  const s = setup();
  // Default week from 2026-11-15: Sun Skate, Mon Lower, Tue Upper, the rest Upper/Rest.
  const saved = s.post({ token: TOKEN, today: '2026-11-14' },
    { action: 'saveDefault', days: ['Skate', 'Lower', 'Upper', 'Rest', 'Upper', 'Upper', 'Rest'] });
  assert.equal(saved.ok, true, JSON.stringify(saved));
  assert.equal(saved.effectiveFrom, '2026-11-15');
  assert.deepEqual(JSON.parse(s.props['DEFAULT_2026-11-15']), ['Skate', 'Lower', 'Upper', 'Rest', 'Upper', 'Upper', 'Rest']);

  const st = s.get({ token: TOKEN, today: '2026-11-17' });
  assert.equal(st.timeZone, TZ);
  assert.equal(st.workout, 'Upper');
  assert.deepEqual(st.goalsProgress.map((g) => [g.name, g.logged, g.met]), [['Push', 2, true], ['Pull', 2, true], ['Core', 1, true]]);
  assert.equal(st.blocked, false);
  // Sun: skating logged (Exercise goal), Mon: Legs 3 + Core 1, Tue: done -> streak 3.
  assert.deepEqual(st.history.map((h) => h.event), ['done', 'done', 'done']);
  assert.equal(st.streak, 3);
  assert.deepEqual(st.week.map((d) => d.source), Array(7).fill('default'));
});

test('a day with too little logged stays blocked and shows progress', () => {
  const s = setup();
  s.post({ token: TOKEN, today: '2026-11-14' },
    { action: 'saveDefault', days: ['Upper', 'Upper', 'Lower', 'Rest', 'Upper', 'Upper', 'Rest'] });
  const st = s.get({ token: TOKEN, today: '2026-11-15' });
  // 11/15 had 1 Push, 1 Pull (the stray value sits in a spacer column) and no Core.
  assert.equal(st.blocked, true);
  assert.deepEqual(st.goalsProgress.map((g) => g.logged), [1, 1, 0]);
});

test('saveSchedule stores the week; locked days are refused, short weeks are fine', () => {
  const s = setup();
  const week = ['Rest', 'Upper', 'Lower', 'Upper', 'Rest', 'Skate', 'Rest'];
  const ok = s.post({ token: TOKEN, today: '2026-11-21' }, { action: 'saveSchedule', weekStart: '2026-11-22', days: week });
  assert.equal(ok.ok, true, JSON.stringify(ok));
  assert.deepEqual(JSON.parse(s.props['WEEK_2026-11-22']), week);
  // Saving the same week again replaces it.
  const keys = settingKeys(s);
  s.post({ token: TOKEN, today: '2026-11-21' }, { action: 'saveSchedule', weekStart: '2026-11-22', days: week });
  assert.deepEqual(settingKeys(s), keys);

  const locked = s.post({ token: TOKEN, today: '2026-11-23' },
    { action: 'saveSchedule', weekStart: '2026-11-22', days: ['Upper', 'Upper', 'Lower', 'Upper', 'Rest', 'Skate', 'Rest'] });
  assert.equal(locked.ok, false);
  assert.match(locked.errors.join(), /2026-11-22 is over/);

  const short = s.post({ token: TOKEN, today: '2026-11-21' },
    { action: 'saveSchedule', weekStart: '2026-11-29', days: ['Rest', 'Upper', 'Rest', 'Upper', 'Rest', 'Skate', 'Rest'] });
  assert.equal(short.ok, true, JSON.stringify(short));
});

test('schedule action returns both weeks, the default, goals and allowlist', () => {
  const s = setup();
  s.post({ token: TOKEN, today: '2026-11-14' },
    { action: 'saveDefault', days: ['Rest', 'Upper', 'Lower', 'Upper', 'Rest', 'Skate', 'Rest'] });
  s.post({ token: TOKEN, today: '2026-11-14' }, { action: 'saveAllowlist', packages: ['com.example.notes'] });
  const v = s.get({ token: TOKEN, today: '2026-11-17', action: 'schedule' });
  assert.deepEqual(v.workouts, ['Upper', 'Lower', 'Skate']);
  assert.equal(v.weeks.length, 2);
  assert.equal(v.weeks[0].weekStart, '2026-11-15');
  assert.deepEqual(v.weeks[0].days.map((d) => d.locked), [true, true, false, false, false, false, false]); // today locks per workout
  assert.equal(v.default.current.from, '2026-11-15');
  assert.deepEqual(v.goals.current, GOALS);
  assert.equal(v.goals.pending, null);
  assert.deepEqual(v.allowlist.current, ['com.example.notes']);
});

test('saveAllowlist takes effect tomorrow', () => {
  const s = setup();
  const r = s.post({ token: TOKEN, today: '2026-11-17' }, { action: 'saveAllowlist', packages: ['com.example.notes', 'bad name'] });
  assert.equal(r.ok, false);
  const ok = s.post({ token: TOKEN, today: '2026-11-17' }, { action: 'saveAllowlist', packages: ['com.example.notes'] });
  assert.equal(ok.effectiveFrom, '2026-11-18');
  assert.deepEqual(JSON.parse(s.props['ALLOWLIST_2026-11-18']), ['com.example.notes']);
  assert.deepEqual(s.get({ token: TOKEN, today: '2026-11-17' }).allowlist, []);
  assert.deepEqual(s.get({ token: TOKEN, today: '2026-11-18' }).allowlist, ['com.example.notes']);
});

test('saveGoals takes effect tomorrow and leaves earlier days judged by the old goals', () => {
  const s = setup();
  s.post({ token: TOKEN, today: '2026-11-14' },
    { action: 'saveDefault', days: ['Skate', 'Lower', 'Upper', 'Rest', 'Upper', 'Upper', 'Rest'] });
  // On Tuesday 11/17 (done: Push 2, Pull 2, Core 1), raise Upper's Push to 3.
  const harder = GOALS.map((w) => (w.name !== 'Upper' ? w : { ...w, rules: [{ type: 'section', name: 'Push', min: 3 }, ...w.rules.slice(1)] }));
  const r = s.post({ token: TOKEN, today: '2026-11-17' }, { action: 'saveGoals', workouts: harder });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.effectiveFrom, '2026-11-18');
  assert.deepEqual(r.workouts, harder);
  assert.equal(s.get({ token: TOKEN, today: '2026-11-17' }).blocked, false);
  const pending = s.get({ token: TOKEN, today: '2026-11-17', action: 'schedule' }).goals.pending;
  assert.equal(pending.from, '2026-11-18');
  // Thursday: the history keeps Tuesday done; Thursday itself needs Push 3.
  const thu = s.get({ token: TOKEN, today: '2026-11-19' });
  assert.deepEqual(thu.history.map((h) => h.event), ['done', 'done', 'done', 'pending']);
  assert.equal(thu.goalsProgress[0].min, 3);
});

test('saveGoals refuses bad goals and removing a planned workout', () => {
  const s = setup();
  s.post({ token: TOKEN, today: '2026-11-14' },
    { action: 'saveDefault', days: ['Skate', 'Lower', 'Upper', 'Rest', 'Upper', 'Upper', 'Rest'] });
  const noSkate = s.post({ token: TOKEN, today: '2026-11-17' }, { action: 'saveGoals', workouts: GOALS.slice(0, 2) });
  assert.equal(noSkate.ok, false);
  assert.match(noSkate.errors.join(), /"Skate" is still planned on 2026-11-22/);
  const bad = s.post({ token: TOKEN, today: '2026-11-17' }, { action: 'saveGoals', workouts: [...GOALS,
    { name: 'Rest', rules: [{ type: 'section', name: 'Core', min: 1 }] },
    { name: 'upper', rules: [{ type: 'section', name: 'Core', min: 1 }] },
    { name: 'Yoga', rules: [{ type: 'category', name: 'Stretch', min: 1 }] },
    { name: 'Run', rules: [{ type: 'section', name: 'Cardio', min: 0.5 }] },
    { name: 'Swim', rules: [] }] });
  assert.deepEqual(bad.errors, [
    '"Rest" is reserved for rest days',
    '"upper" is listed twice',
    'Yoga: unknown goal type "category"',
    'Run: Cardio needs a whole number of sets, 1 or more',
    'Swim: needs at least one goal',
  ]);
  assert.deepEqual(settingKeys(s), ['DEFAULT_2026-11-15', 'GOALS_2026-01-01']);
});

test('saves never touch the spreadsheet', () => {
  const s = setup();
  const before = JSON.stringify(s.ss.sheets);
  s.post({ token: TOKEN, today: '2026-11-14' }, { action: 'saveDefault', days: ['Skate', 'Lower', 'Upper', 'Rest', 'Upper', 'Upper', 'Rest'] });
  s.post({ token: TOKEN, today: '2026-11-21' }, { action: 'saveSchedule', weekStart: '2026-11-22', days: ['Rest', 'Upper', 'Lower', 'Upper', 'Rest', 'Skate', 'Rest'] });
  s.post({ token: TOKEN, today: '2026-11-21' }, { action: 'saveGoals', workouts: GOALS });
  s.post({ token: TOKEN, today: '2026-11-21' }, { action: 'saveAllowlist', packages: ['com.example.notes'] });
  assert.equal(JSON.stringify(s.ss.sheets), before);
});

test('?today is ignored unless ALLOW_FAKE_TODAY is set', () => {
  const s = setup({ ALLOW_FAKE_TODAY: '' });
  const st = s.get({ token: TOKEN, today: '2000-01-01' });
  assert.notEqual(st.today, '2000-01-01');
  assert.match(st.today, /^\d{4}-\d{2}-\d{2}$/);
});

test('skipToday: needs a token; a grant for today makes one; then today is open', () => {
  const s = setup();
  s.post({ token: TOKEN, today: '2026-11-14' },
    { action: 'saveDefault', days: ['Upper', 'Upper', 'Lower', 'Rest', 'Upper', 'Upper', 'Rest'] });
  const none = s.post({ token: TOKEN, today: '2026-11-15' }, { action: 'skipToday' });
  assert.deepEqual(none.errors, ['no freeze token to spend']);
  s.props['GRANT_2026-11-15'] = '1';
  assert.equal(s.get({ token: TOKEN, today: '2026-11-15' }).canSkip, true);
  const ok = s.post({ token: TOKEN, today: '2026-11-15' }, { action: 'skipToday' });
  assert.equal(ok.ok, true, JSON.stringify(ok));
  const st = s.get({ token: TOKEN, today: '2026-11-15' });
  assert.equal(st.blocked, false);
  assert.equal(st.skipped, true);
  assert.equal(s.post({ token: TOKEN, today: '2026-11-15' }, { action: 'skipToday' }).errors[0], 'today is already skipped');
  assert.equal(s.post({ token: TOKEN, today: '2026-11-18' }, { action: 'skipToday' }).errors[0], 'nothing is left to gate today');
});

test('status uses the time of day: &now with ALLOW_FAKE_TODAY', () => {
  const s = setup({ 'GOALS_2026-01-01': JSON.stringify([{ ...GOALS[2], window: '18:00-20:00' }, GOALS[0]]) });
  s.post({ token: TOKEN, today: '2026-11-14' }, { action: 'saveDefault', days: ['Skate', 'Skate', 'Skate', 'Upper', 'Rest', 'Skate', 'Rest'] });
  assert.equal(s.get({ token: TOKEN, today: '2026-11-16', now: '17:59' }).blocked, false);
  const st = s.get({ token: TOKEN, today: '2026-11-16', now: '18:00' });
  assert.equal(st.blocked, true);
  assert.deepEqual(st.sessions.map((x) => [x.workout, x.start, x.end]), [['Skate', '18:00', '20:00']]);
  // The schedule's goals carry the window.
  assert.equal(s.get({ token: TOKEN, today: '2026-11-16', action: 'schedule' }).goals.current[0].window, '18:00-20:00');
  // Within an hour of 18:00 the day can't drop Skate.
  const r = s.post({ token: TOKEN, today: '2026-11-16', now: '17:30' },
    { action: 'saveSchedule', weekStart: '2026-11-15', days: ['Skate', 'Rest', 'Skate', 'Upper', 'Rest', 'Skate', 'Rest'] });
  assert.match(r.errors.join(), /"Skate" starts within an hour/);
});

test('page: needs the page token; shows the code once nothing is left to gate; skips only with its own link', () => {
  const s = setup();
  assert.match(s.page({ token: 'nope' }), /Unauthorized/);
  s.post({ token: TOKEN, today: '2026-11-14' },
    { action: 'saveDefault', days: ['Upper', 'Upper', 'Lower', 'Rest', 'Upper', 'Upper', 'Rest'] });
  // 2026-11-17 (Tuesday, Lower): Legs 0 of 3, not done. No code, no skip without a token.
  const pending = s.page({ token: PAGE, today: '2026-11-17' });
  assert.match(pending, /Not done yet\./);
  assert.doesNotMatch(pending, /Unlock code/);
  assert.doesNotMatch(pending, /Skip today/);
  // With a token for the day: an "ask" link, then a confirm link naming the date.
  s.props['GRANT_2026-11-17'] = '1';
  assert.match(s.page({ token: PAGE, today: '2026-11-17' }), /skip=ask/);
  const ask = s.page({ token: PAGE, today: '2026-11-17', skip: 'ask' });
  assert.match(ask, /skip=2026-11-17/);
  assert.equal(s.props['SKIP_2026-11-17'], undefined);
  // A stale confirm link for another day does nothing.
  s.page({ token: PAGE, today: '2026-11-17', skip: '2026-11-16' });
  assert.equal(s.props['SKIP_2026-11-17'], undefined);
  const skipped = s.page({ token: PAGE, today: '2026-11-17', skip: '2026-11-17' });
  assert.equal(s.props['SKIP_2026-11-17'], 'true');
  assert.match(skipped, /Today is skipped with a token\./);
  // The code: HMAC-SHA256 of "open:<day>" keyed by the token, as the app computes it.
  const crypto = require('node:crypto');
  const C = require('../Core.js');
  const code = C.unlockCodeFrom(Array.from(crypto.createHmac('sha256', TOKEN).update('open:2026-11-17').digest()));
  assert.match(skipped, new RegExp(code.slice(0, 3) + ' ' + code.slice(3)));
  // Names are escaped.
  assert.doesNotMatch(s.page({ token: PAGE, today: '2026-11-17' }), /<script/);
});
