// Runs Code.js + Core.js against the fake runtime in gas.js: endpoints, sheet
// reads and writes, token checks. The Exercise tab here is made up but laid out
// like the real one (dates in row 2 from column D, newest first, a spacer column,
// section labels in column B carried down, unit suffixes on exercise names).
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadScript, dateCell } = require('./gas.js');

const TOKEN = 't0k';
const TZ = 'America/New_York';

function exerciseTab() {
  const d = (iso) => dateCell(iso, TZ);
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

function setup(props = {}) {
  const s = loadScript({
    sheets: { Exercise: exerciseTab() },
    timeZone: TZ,
    props: { TOKEN, ALLOW_FAKE_TODAY: 'true', ...props },
  });
  s.ctx.setup(); // creates the Gate tabs with their example goals
  return s;
}

test('setup creates the gate tabs and keeps an existing token', () => {
  const s = setup();
  for (const t of ['Gate Goals', 'Gate Schedule', 'Gate Default', 'Gate Allowlist']) assert.ok(s.ss.sheets[t], t);
  assert.equal(s.props.TOKEN, TOKEN);
  assert.deepEqual(s.ss.sheets['Gate Goals'].data[1], ['Upper', 'Section', 'Push', 2]);
});

test('a wrong or missing token is refused', () => {
  const s = setup();
  assert.deepEqual(s.get({ token: 'nope' }), { error: 'unauthorized' });
  assert.deepEqual(s.get({}), { error: 'unauthorized' });
  assert.deepEqual(s.post({ token: 'nope' }, { action: 'saveDefault' }), { error: 'unauthorized' });
});

test('status reads the Exercise tab: sections, exercise goals, time zone', () => {
  const s = setup();
  // Default week from 2026-11-15: Sun Skate, Mon Lower, Tue Upper, the rest Upper/Rest.
  const saved = s.post({ token: TOKEN, today: '2026-11-14' },
    { action: 'saveDefault', days: ['Skate', 'Lower', 'Upper', 'Rest', 'Upper', 'Upper', 'Rest'] });
  assert.equal(saved.ok, true, JSON.stringify(saved));
  assert.equal(saved.effectiveFrom, '2026-11-15');

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

test('saveSchedule writes the week; locked days and short weeks are refused', () => {
  const s = setup();
  const week = ['Rest', 'Upper', 'Lower', 'Upper', 'Rest', 'Skate', 'Rest'];
  const ok = s.post({ token: TOKEN, today: '2026-11-21' }, { action: 'saveSchedule', weekStart: '2026-11-22', days: week });
  assert.equal(ok.ok, true, JSON.stringify(ok));
  const rows = s.ss.sheets['Gate Schedule'].data.slice(1);
  assert.deepEqual(rows.map((r) => r[1]), week);
  // Saving the same week again replaces its rows instead of appending.
  s.post({ token: TOKEN, today: '2026-11-21' }, { action: 'saveSchedule', weekStart: '2026-11-22', days: week });
  assert.equal(s.ss.sheets['Gate Schedule'].data.slice(1).filter((r) => r.length).length, 7);

  const locked = s.post({ token: TOKEN, today: '2026-11-23' },
    { action: 'saveSchedule', weekStart: '2026-11-22', days: ['Upper', 'Upper', 'Lower', 'Upper', 'Rest', 'Skate', 'Rest'] });
  assert.equal(locked.ok, false);
  assert.match(locked.errors.join(), /2026-11-22 is locked/);

  const short = s.post({ token: TOKEN, today: '2026-11-21' },
    { action: 'saveSchedule', weekStart: '2026-11-29', days: ['Rest', 'Upper', 'Rest', 'Upper', 'Rest', 'Skate', 'Rest'] });
  assert.match(short.errors.join(), /at least 4 gym days, got 3/);
});

test('schedule action returns this week and next with the default', () => {
  const s = setup();
  s.post({ token: TOKEN, today: '2026-11-14' },
    { action: 'saveDefault', days: ['Rest', 'Upper', 'Lower', 'Upper', 'Rest', 'Skate', 'Rest'] });
  const v = s.get({ token: TOKEN, today: '2026-11-17', action: 'schedule' });
  assert.deepEqual(v.workouts, ['Upper', 'Lower', 'Skate']);
  assert.equal(v.weeks.length, 2);
  assert.equal(v.weeks[0].weekStart, '2026-11-15');
  assert.deepEqual(v.weeks[0].days.map((d) => d.locked), [true, true, true, false, false, false, false]);
  assert.equal(v.default.current.from, '2026-11-15');
});

test('saveAllowlist takes effect tomorrow', () => {
  const s = setup();
  const r = s.post({ token: TOKEN, today: '2026-11-17' }, { action: 'saveAllowlist', packages: ['com.example.notes', 'bad name'] });
  assert.equal(r.ok, false);
  const ok = s.post({ token: TOKEN, today: '2026-11-17' }, { action: 'saveAllowlist', packages: ['com.example.notes'] });
  assert.equal(ok.effectiveFrom, '2026-11-18');
  assert.deepEqual(s.get({ token: TOKEN, today: '2026-11-17' }).allowlist, []);
  assert.deepEqual(s.get({ token: TOKEN, today: '2026-11-18' }).allowlist, ['com.example.notes']);
});

test('?today is ignored unless ALLOW_FAKE_TODAY is set', () => {
  const s = setup({ ALLOW_FAKE_TODAY: '' });
  const st = s.get({ token: TOKEN, today: '2000-01-01' });
  assert.notEqual(st.today, '2000-01-01');
  assert.match(st.today, /^\d{4}-\d{2}-\d{2}$/);
});
