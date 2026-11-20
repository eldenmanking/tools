// Run from the repo root: node --test 'android/goals/apps-script/tests/*.test.js'
const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('../Core.js');

const GOALS = C.parseGoals([
  ['Push', 'Section', 'Push', 2],
  ['Pull', 'Section', 'Pull', 2],
  ['Upper', 'Section', 'Push', 1],
  ['Upper', 'section', 'Pull', 1],
  ['Skate', 'Exercise', 'Figure skating', ''],
]);

// One date's parseExercise entry.
function logged(sections, exercises = []) {
  return { sections, exercises: Object.fromEntries(exercises.map((e) => [e, true])) };
}

// A week of rows, Sunday first.
function week(start, days) {
  return days.map((w, i) => [C.addDays(start, i), w]);
}

// exercise map: logging Push x2 on each of <dates>.
function pushDone(dates) {
  const ex = {};
  for (const d of dates) ex[d] = logged({ push: 2 });
  return ex;
}

const SUN = '2026-11-01'; // a Sunday

test('dates: weeks start on Sunday', () => {
  assert.equal(C.dayOfWeek(SUN), 0);
  assert.equal(C.weekStartOf('2026-11-07'), SUN); // Saturday
  assert.equal(C.weekStartOf('2026-11-08'), '2026-11-08');
  assert.equal(C.addDays('2026-12-31', 1), '2027-01-01');
  assert.equal(C.weekStartOf('2027-01-01'), '2026-12-27'); // year rollover
});

test('parseExercise: sections carry down, spacers and duplicate dates ignored', () => {
  const header = [null, null, null, '2026-11-13', null, '2026-11-12', '2026-11-12'];
  const rows = [
    ['', '', '', '', '', '', '0e'],               // row 3 note, before any section
    ['', 'Push', 'Bench', 'x', '', 'y', 'y'],
    ['', '', 'Dips', ' ', '', '', 'z'],          // whitespace is not a log
    [],
    ['', 'Pull', 'Rows [lb total] (count)', 'x', 'stray', '', ''],  // spacer column ignored
  ];
  assert.deepEqual(C.parseExercise(header, rows), {
    '2026-11-13': logged({ push: 1, pull: 1 }, ['bench', 'rows']),
    '2026-11-12': logged({ push: 2 }, ['bench', 'dips']),
  });
});

test('exerciseKey: drops [unit] and (note) suffixes', () => {
  assert.equal(C.exerciseKey('Figure skating [hr.min]'), 'figure skating');
  assert.equal(C.exerciseKey('Squats [lb per side] (count) (s:17,2)'), 'squats');
  assert.equal(C.exerciseKey('Run (mi) [mph]'), 'run');
  assert.equal(C.exerciseKey('  Dips  '), 'dips');
});

test('dayProgress: every rule of the workout must be met', () => {
  assert.equal(C.dayProgress(GOALS, 'Upper', logged({ push: 1, pull: 1 })).done, true);
  const p = C.dayProgress(GOALS, 'upper', logged({ push: 3 }));
  assert.equal(p.done, false);
  assert.deepEqual(p.progress.map((r) => r.met), [true, false]);
  assert.deepEqual(p.progress[1], { type: 'section', name: 'Pull', min: 1, logged: 0, met: false });
  assert.equal(C.dayProgress(GOALS, 'Upper', undefined).done, false);
  assert.equal(C.dayProgress(GOALS, 'Cardio', logged({})).unknownWorkout, true);
});

test('dayProgress: an Exercise goal needs that exercise logged', () => {
  assert.equal(C.dayProgress(GOALS, 'Skate', logged({ free: 1 }, ['figure skating'])).done, true);
  assert.equal(C.dayProgress(GOALS, 'Skate', logged({ free: 1 }, ['rock climbing'])).done, false);
  // Mixed: section counts and a specific exercise in one workout.
  const mixed = C.parseGoals([
    ['UpperSkate', 'Section', 'Push', 2],
    ['UpperSkate', 'Exercise', 'Figure skating [hr.min]', 1],
    ['Bad', 'Category', 'Push', 2], // unknown type: ignored
  ]);
  assert.equal(mixed.bad, undefined);
  assert.equal(C.dayProgress(mixed, 'upperskate', logged({ push: 2 }, ['figure skating'])).done, true);
  assert.equal(C.dayProgress(mixed, 'upperskate', logged({ push: 1 }, ['figure skating'])).done, false);
});

test('workoutFor: unset weeks copy the previous week; before the first week is unscheduled', () => {
  const s = C.parseSchedule(week(SUN, ['Rest', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest']));
  assert.equal(C.workoutFor(s, '2026-10-31'), null);
  assert.equal(C.workoutFor(s, '2026-11-02'), 'Push');
  assert.equal(C.workoutFor(s, '2026-11-16'), 'Push'); // two weeks later, copied
  assert.equal(C.workoutFor(s, '2026-11-17'), 'Pull');
  // A stored week's missing days are rest, not copied.
  const s2 = C.parseSchedule([...week(SUN, ['Rest', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest']), ['2026-11-09', 'Pull']]);
  assert.equal(C.workoutFor(s2, '2026-11-09'), 'Pull');
  assert.equal(C.workoutFor(s2, '2026-11-10'), 'Rest');
});

test('validateSave: minimum gym days, known workouts, Sunday start', () => {
  const today = '2026-10-25';
  const ok = C.validateSave({}, GOALS, SUN, ['Rest', 'Push', 'Pull', 'Rest', 'Push', 'Pull', ''], today);
  assert.equal(ok.ok, true, ok.errors.join());
  assert.equal(ok.rows[6][1], 'Rest');
  // A saved week has no minimum of gym days, only the default week does.
  assert.equal(C.validateSave({}, GOALS, SUN, Array(7).fill('Rest'), today).ok, true);
  const unknown = C.validateSave({}, GOALS, SUN, ['Yoga', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest'], today);
  assert.match(unknown.errors.join(), /unknown workout "Yoga"/);
  assert.equal(C.validateSave({}, GOALS, '2026-11-02', Array(7).fill('Push'), today).ok, false);
});

test('validateSave: earlier days are over; today\'s all-day workouts are locked, tomorrow is editable', () => {
  const s = C.parseSchedule(week(SUN, ['Rest', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest']));
  const today = '2026-11-03'; // Tuesday (Pull)
  // Changing Wednesday (tomorrow) is allowed.
  const fine = C.validateSave(s, GOALS, SUN, ['Rest', 'Push', 'Pull', 'Push', 'Rest', 'Pull', 'Rest'], today);
  assert.equal(fine.ok, true, fine.errors.join());
  // Changing today is not.
  const same = C.validateSave(s, GOALS, SUN, ['Rest', 'Push', 'Rest', 'Push', 'Push', 'Pull', 'Rest'], today);
  assert.match(same.errors.join(), /2026-11-03: "Pull" starts within an hour/);
  // Changing a past day is not.
  const past = C.validateSave(s, GOALS, SUN, ['Push', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest'], today);
  assert.match(past.errors.join(), /2026-11-01 is over/);
  // Locks compare against the copied schedule too: this week's copy of last
  // week's Sunday (Rest) can't gain an all-day workout today.
  const s3 = C.parseSchedule(week('2026-10-25', ['Rest', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest']));
  const copied = C.validateSave(s3, GOALS, SUN, ['Push', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest'], '2026-11-01');
  assert.match(copied.errors.join(), /2026-11-01: "Push" would start within an hour/);
});

test('computeStatus: rest day is not blocked; today unmet is pending, not a miss', () => {
  const s = C.parseSchedule(week(SUN, ['Rest', 'Push', 'Push', 'Rest', 'Push', 'Push', 'Rest']));
  const rest = C.computeStatus(s, GOALS, {}, '2026-11-01');
  assert.equal(rest.todayScheduled, false);
  assert.equal(rest.blocked, false);
  const pending = C.computeStatus(s, GOALS, pushDone(['2026-11-02']), '2026-11-03');
  assert.equal(pending.blocked, true);
  assert.equal(pending.streak, 1);
  assert.deepEqual(pending.goalsProgress, [{ type: 'section', name: 'Push', min: 2, logged: 0, met: false }]);
  const done = C.computeStatus(s, GOALS, pushDone(['2026-11-02', '2026-11-03']), '2026-11-03');
  assert.equal(done.blocked, false);
  assert.equal(done.streak, 2);
});

test('computeStatus: a miss with no tokens resets the streak; bonus days do not count', () => {
  const s = C.parseSchedule(week(SUN, ['Rest', 'Push', 'Push', 'Rest', 'Push', 'Push', 'Rest']));
  // Mon done, Tue missed, Wed (rest) logged as a bonus, Thu done.
  const st = C.computeStatus(s, GOALS, pushDone(['2026-11-02', '2026-11-04', '2026-11-05']), '2026-11-05');
  assert.equal(st.streak, 1);
  assert.equal(st.longest, 1);
  assert.equal(st.tokens, 0);
});

test('computeStatus: a token every 7 completions, capped at 2, auto-spent on a miss', () => {
  // Gym every day Mon-Sat? Use 6 gym days a week from Sunday 2026-10-04.
  const start = '2026-10-04';
  const s = C.parseSchedule(week(start, ['Push', 'Push', 'Push', 'Push', 'Push', 'Push', 'Rest']));
  const days = [];
  for (let d = start; d <= '2026-11-14'; d = C.addDays(d, 1)) if (C.dayOfWeek(d) !== 6) days.push(d);
  // 36 gym days through Sat 2026-11-14 -> 5 tokens earned, capped at 2.
  const all = C.computeStatus(s, GOALS, pushDone(days), '2026-11-14');
  assert.equal(all.streak, days.length);
  assert.equal(all.tokens, 2);
  // Miss two days: both tokens spent, streak kept; a third miss resets it.
  const misses = ['2026-11-09', '2026-11-10', '2026-11-11'];
  const ex = pushDone(days.filter((d) => !misses.slice(0, 2).includes(d)));
  const two = C.computeStatus(s, GOALS, ex, '2026-11-13');
  assert.equal(two.tokens, 0);
  assert.equal(two.streak, days.indexOf('2026-11-13') + 1 - 2);
  const ex3 = pushDone(days.filter((d) => !misses.includes(d)));
  const three = C.computeStatus(s, GOALS, ex3, '2026-11-13');
  assert.equal(three.streak, 2); // Thu + Fri after the reset
  assert.equal(three.longest, days.indexOf('2026-11-08') + 1);
});

test('computeStatus: tokens are earned while at the cap only up to the cap', () => {
  const start = '2026-10-04';
  const s = C.parseSchedule(week(start, ['Push', 'Push', 'Push', 'Push', 'Push', 'Push', 'Push']));
  const days = [];
  for (let d = start; d < C.addDays(start, 14); d = C.addDays(d, 1)) days.push(d);
  // 14 completions = 2 tokens; one miss spends one; 7 more would earn it back.
  const ex = pushDone(days.filter((d) => d !== '2026-10-18'));
  for (let i = 15; i < 21; i++) ex[C.addDays(start, i)] = logged({ push: 2 });
  const st = C.computeStatus(s, GOALS, ex, C.addDays(start, 20));
  assert.equal(st.tokens, 1); // 20 completions so far: 2 earned, 1 spent
  ex[C.addDays(start, 21)] = logged({ push: 2 });
  assert.equal(C.computeStatus(s, GOALS, ex, C.addDays(start, 21)).tokens, 2);
});

test('computeStatus: no schedule at all means nothing is blocked', () => {
  const st = C.computeStatus({}, GOALS, {}, '2026-11-15');
  assert.equal(st.blocked, false);
  assert.equal(st.streak, 0);
});

test('allowlist: a saved change takes effect tomorrow, never today', () => {
  const today = '2026-11-15';
  const v = C.validateAllowlist(['com.example.reader', 'com.example.notes', 'com.example.reader'], today);
  assert.equal(v.ok, true);
  assert.deepEqual(v.row, ['2026-11-16', 'com.example.notes,com.example.reader']);
  assert.match(C.validateAllowlist(['notapackage', 'com.ok.app'], today).errors.join(), /not a package name: notapackage/);

  const entries = C.parseAllowlist([
    ['2026-11-01', 'com.example.reader'],
    v.row,
    ['bad date', 'com.x.y'],
  ]);
  const now = C.allowlistFor(entries, today);
  assert.deepEqual(now.current, ['com.example.reader']);
  assert.deepEqual(now.pending, { from: '2026-11-16', packages: ['com.example.notes', 'com.example.reader'] });
  const tomorrow = C.allowlistFor(entries, '2026-11-16');
  assert.deepEqual(tomorrow.current, ['com.example.notes', 'com.example.reader']);
  assert.equal(tomorrow.pending, null);
  assert.deepEqual(C.allowlistFor([], today).current, []);
});

test('computeStatus: reports today\'s allowlist with the block state', () => {
  const s = C.parseSchedule(week(SUN, ['Rest', 'Skate', 'Push', 'Rest', 'Push', 'Push', 'Rest']));
  const entries = C.parseAllowlist([['2026-11-01', 'com.example.reader']]);
  const st = C.computeStatus(s, GOALS, {}, '2026-11-02', entries);
  assert.equal(st.workout, 'Skate');
  assert.equal(st.blocked, true);
  assert.deepEqual(st.allowlist, ['com.example.reader']);
  const ex = { '2026-11-02': logged({ free: 1 }, ['figure skating']) };
  assert.equal(C.computeStatus(s, GOALS, ex, '2026-11-02', entries).blocked, false);
});

const DEFAULT_ROW = ['2026-11-01', 'Rest', 'Upper', 'Push', 'Rest', 'Upper', 'Pull', 'Rest'];

test('defaults: an unsaved week follows the default; a saved week overrides it', () => {
  const plan = { days: C.parseSchedule(week('2026-11-08', ['Rest', 'Pull', 'Pull', 'Pull', 'Pull', 'Rest', 'Rest'])),
    defaults: C.parseDefaults([DEFAULT_ROW]) };
  assert.equal(C.workoutFor(plan, '2026-11-02'), 'Upper');   // default week
  assert.equal(C.workoutFor(plan, '2026-11-09'), 'Pull');    // saved week
  assert.equal(C.workoutFor(plan, '2026-11-14'), 'Rest');    // saved week, Saturday
  assert.equal(C.workoutFor(plan, '2026-11-16'), 'Upper');   // back to the default
  assert.equal(C.workoutFor(plan, '2026-10-31'), null);      // before anything
  const v = C.weekView(plan, '2026-11-15', '2026-11-16');
  assert.deepEqual(v.days.map((d) => d.source), Array(7).fill('default'));
  assert.equal(v.days[0].locked, true); // over
  assert.equal(v.days[1].locked, false); // today: each workout locks on its own
});

test('defaults: a new default starts tomorrow and never changes today or earlier', () => {
  // Last week was saved; this week is unsaved, so it repeats last week.
  const days = C.parseSchedule(week('2026-11-08', ['Rest', 'Pull', 'Pull', 'Pull', 'Pull', 'Rest', 'Rest']));
  const today = '2026-11-17'; // Tuesday
  assert.equal(C.workoutFor(days, today), 'Pull');
  const v = C.validateDefault(GOALS, ['Rest', 'Rest', 'Rest', 'Upper', 'Upper', 'Upper', 'Upper'], today);
  assert.equal(v.ok, true, v.errors.join());
  assert.equal(v.row[0], '2026-11-18');
  const plan = { days, defaults: C.parseDefaults([v.row]) };
  assert.equal(C.workoutFor(plan, '2026-11-16'), 'Pull');  // locked days keep the copy
  assert.equal(C.workoutFor(plan, today), 'Pull');
  assert.equal(C.workoutFor(plan, '2026-11-18'), 'Upper'); // tomorrow on: the default
  assert.equal(C.weekView(plan, '2026-11-15', today).days[2].source, 'copied');
  assert.equal(C.weekView(plan, '2026-11-15', today).days[3].source, 'default');
  // A later default replaces an earlier one from its own date on.
  const two = C.parseDefaults([DEFAULT_ROW, v.row]);
  assert.equal(C.defaultFor(two, '2026-11-17').current.from, '2026-11-01');
  assert.equal(C.defaultFor(two, '2026-11-17').pending.from, '2026-11-18');
});

test('defaults: same checks as a week (4 gym days, known workouts)', () => {
  const few = C.validateDefault(GOALS, ['Rest', 'Upper', 'Rest', 'Upper', 'Rest', 'Upper', 'Rest'], '2026-11-17');
  assert.match(few.errors.join(), /at least 4 gym days, got 3/);
  const bad = C.validateDefault(GOALS, ['Yoga', 'Upper', 'Push', 'Upper', 'Rest', 'Upper', 'Rest'], '2026-11-17');
  assert.match(bad.errors.join(), /Sun: unknown workout "Yoga"/);
  assert.equal(C.validateDefault(GOALS, ['Upper'], '2026-11-17').ok, false);
});

test('defaults: saving a week still locks days that came from the default', () => {
  const plan = { days: {}, defaults: C.parseDefaults([DEFAULT_ROW]) };
  const r = C.validateSave(plan, GOALS, '2026-11-15', ['Rest', 'Rest', 'Upper', 'Upper', 'Upper', 'Upper', 'Rest'], '2026-11-16');
  assert.match(r.errors.join(), /2026-11-16: "Upper" starts within an hour/);
});

test('computeStatus: streak counts default-planned days', () => {
  const plan = { days: {}, defaults: C.parseDefaults([['2026-11-01', 'Push', 'Push', 'Push', 'Push', 'Rest', 'Rest', 'Rest']]) };
  const st = C.computeStatus(plan, GOALS, pushDone(['2026-11-01', '2026-11-02', '2026-11-03']), '2026-11-04');
  assert.equal(st.streak, 3);
  assert.equal(st.blocked, true);
  assert.equal(st.workout, 'Push');
});

test('computeStatus: includes this week for the home screen', () => {
  const s = C.parseSchedule(week(SUN, ['Rest', 'Push', 'Push', 'Rest', 'Push', 'Push', 'Rest']));
  const st = C.computeStatus(s, GOALS, {}, '2026-11-03');
  assert.deepEqual(st.week.map((d) => d.workout), ['Rest', 'Push', 'Push', 'Rest', 'Push', 'Push', 'Rest']);
  assert.deepEqual(st.week.map((d) => d.locked), [true, true, false, false, false, false, false]);
});

test('parseSettings: reads dated properties and ignores the rest', () => {
  const s = C.parseSettings({
    TOKEN: 'x',
    'WEEK_2026-11-01': JSON.stringify(['Rest', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest']),
    'WEEK_2026-11-03': JSON.stringify(Array(7).fill('Push')),  // not a Sunday
    'DEFAULT_2026-11-08': JSON.stringify(['Rest', 'Upper', 'Push', 'Rest', 'Upper', 'Pull', 'Rest']),
    'GOALS_2026-11-02': JSON.stringify([{ name: 'Push', rules: [{ type: 'section', name: 'Push', min: 3 }] }]),
    'ALLOWLIST_2026-11-02': JSON.stringify(['com.example.notes']),
    'ALLOWLIST_2026-11-05': '{not json',
    'GOALS_2026-13-01': '[]',                                     // not a date
  });
  assert.equal(C.workoutFor(s.plan, '2026-11-02'), 'Push');
  assert.equal(C.workoutFor(s.plan, '2026-11-09'), 'Upper');
  assert.deepEqual(s.allowlist, [{ from: '2026-11-02', packages: ['com.example.notes'] }]);
  assert.deepEqual(s.goals.map((e) => e.from), ['2026-11-02']);
  assert.equal(s.goals[0].goals.push.rules[0].min, 3);
});

test('goalsFor: each day uses the goals in effect; the first entry covers earlier days', () => {
  const entries = C.parseGoalEntries([
    { from: '2026-11-10', workouts: [{ name: 'Push', rules: [{ type: 'section', name: 'Push', min: 3 }] }] },
    { from: '2026-11-01', workouts: [{ name: 'Push', rules: [{ type: 'section', name: 'Push', min: 2 }] }] },
  ]);
  assert.equal(C.goalsFor(entries, '2026-10-01').current.push.rules[0].min, 2);
  assert.equal(C.goalsFor(entries, '2026-11-09').current.push.rules[0].min, 2);
  assert.equal(C.goalsFor(entries, '2026-11-09').pending.from, '2026-11-10');
  assert.equal(C.goalsFor(entries, '2026-11-10').current.push.rules[0].min, 3);
  assert.deepEqual(C.goalsFor([], '2026-11-10'), { current: {}, pending: null });
  // Push x2 counts on 11/09 but not on 11/10.
  const s = C.parseSchedule(week('2026-11-08', ['Rest', 'Push', 'Push', 'Push', 'Push', 'Rest', 'Rest']));
  const st = C.computeStatus(s, entries, pushDone(['2026-11-09', '2026-11-10']), '2026-11-11');
  assert.deepEqual(st.history.map((h) => h.event), ['done', 'missed', 'pending']);
});

test('validateSave: a locked day may keep a workout that was removed since', () => {
  const s = C.parseSchedule(week(SUN, ['Rest', 'Skate', 'Push', 'Rest', 'Push', 'Pull', 'Rest']));
  const noSkate = C.parseGoals([['Push', 'Section', 'Push', 2], ['Pull', 'Section', 'Pull', 2]]);
  const r = C.validateSave(s, noSkate, SUN, ['Rest', 'Skate', 'Push', 'Push', 'Push', 'Pull', 'Rest'], '2026-11-02');
  assert.equal(r.ok, true, r.errors.join());
  const later = C.validateSave(s, noSkate, SUN, ['Rest', 'Skate', 'Push', 'Skate', 'Push', 'Pull', 'Rest'], '2026-11-02');
  assert.match(later.errors.join(), /2026-11-04: unknown workout "Skate"/);
});

test('serialToIso: spreadsheet serial dates, with or without a time of day', () => {
  assert.equal(C.serialToIso(46343), '2026-11-17');
  assert.equal(C.serialToIso(46343.99), '2026-11-17');
  assert.equal(C.serialToIso(1), '1899-12-31');
});

test('combined workouts: every part must be met; shared goals count once at the higher minimum', () => {
  const goals = C.parseGoals([
    ['Upper', 'Section', 'Push', 2], ['Upper', 'Section', 'Core', 1],
    ['Lower', 'Section', 'Legs', 2], ['Lower', 'Section', 'Core', 2],
    ['Skate', 'Exercise', 'Figure skating', ''],
  ]);
  assert.deepEqual(C.workoutParts(' Upper+ Skate '), ['Upper', 'Skate']);
  const both = C.dayProgress(goals, 'Upper + Skate', logged({ push: 2, core: 1 }, ['figure skating']));
  assert.equal(both.done, true);
  assert.deepEqual(both.progress.map((r) => r.name), ['Push', 'Core', 'Figure skating']);
  assert.equal(C.dayProgress(goals, 'Upper + Skate', logged({ push: 2, core: 1 })).done, false);
  const ul = C.dayProgress(goals, 'upper + lower', logged({ push: 2, legs: 2, core: 1 }));
  assert.deepEqual(ul.progress.map((r) => [r.name, r.min]), [['Push', 2], ['Core', 2], ['Legs', 2]]);
  assert.equal(ul.done, false);
  assert.equal(C.dayProgress(goals, 'Upper + Yoga', logged({})).unknownWorkout, true);
});

test('combined workouts: weeks validate each part and store one spelling', () => {
  const goals = C.parseGoals([['Push', 'Section', 'Push', 2], ['Skate', 'Exercise', 'Figure skating', '']]);
  const ok = C.validateSave({}, goals, SUN, ['skate+push', 'Push', 'Push', 'Rest', 'Push', 'Rest', 'Rest'], '2026-10-25');
  assert.equal(ok.ok, true, ok.errors.join());
  assert.equal(ok.rows[0][1], 'skate + push');
  const bad = C.validateDefault(goals, ['Skate + Yoga', 'Push', 'Push', 'Rest', 'Push', 'Rest', 'Rest'], '2026-10-25');
  assert.match(bad.errors.join(), /Sun: unknown workout "Yoga"/);
  // A combined day counts as one gym day.
  assert.match(C.validateDefault(goals, ['Skate + Push', 'Push', 'Rest', 'Rest', 'Push', 'Rest', 'Rest'], '2026-10-25').errors.join(), /got 3/);
});

test('combined workouts: names can not use "+", and a planned part can not be removed', () => {
  const plan = { days: {}, defaults: C.parseDefaults([['2026-11-01', 'Skate + Push', 'Push', 'Push', 'Rest', 'Push', 'Rest', 'Rest']]) };
  const push = { name: 'Push', rules: [{ type: 'section', name: 'Push', min: 2 }] };
  const r = C.validateGoals(plan, [push, { name: 'A+B', rules: [{ type: 'section', name: 'Core', min: 1 }] }], '2026-11-17');
  assert.match(r.errors.join(), /"A\+B": "\+" joins workouts/);
  assert.match(r.errors.join(), /"Skate" is still planned on 2026-11-22/);
});

test('setCount: entries separated by , or ; outside parentheses', () => {
  assert.equal(C.setCount('1/2:45(8)'), 1);
  assert.equal(C.setCount('1/2:45(8),45(8);50(6)'), 3);
  assert.equal(C.setCount('45(8,9);50(6)'), 2);   // a comma inside parentheses is part of one set
  assert.equal(C.setCount('8, 8, '), 2);           // empty entries don't count
  assert.equal(C.setCount(12), 1);
  assert.equal(C.setCount(''), 0);
  const header = [null, null, null, '2026-11-13'];
  const rows = [['', 'Push', 'Bench', '45(8),45(8),50(6)'], ['', '', 'Dips', '10;10']];
  assert.equal(C.parseExercise(header, rows)['2026-11-13'].sections.push, 5);
});

test('skip: a skipped gym day spends a token, keeps the streak and unblocks today', () => {
  const ex = pushDone(['2026-11-01', '2026-11-02', '2026-11-03', '2026-11-04', '2026-11-05', '2026-11-06', '2026-11-07']);
  // 7 done -> 1 token. Skip Sunday 11/08 (today).
  const today = '2026-11-08';
  const plan = C.parseSchedule(week('2026-11-08', Array(7).fill('Push')).concat(week(SUN, Array(7).fill('Push'))));
  assert.equal(C.computeStatus(plan, GOALS, ex, today).canSkip, true);
  const st = C.computeStatus(plan, GOALS, ex, today, [], { skips: { [today]: true }, grants: {} });
  assert.equal(st.skipped, true);
  assert.equal(st.blocked, false);
  assert.equal(st.canSkip, false);
  assert.equal(st.tokens, 0);
  assert.equal(st.streak, 7);
  assert.equal(st.history.at(-1).event, 'skipped');
});

test('skip: needs a token; a recorded skip with none left is just a pending or missed day', () => {
  const plan = C.parseSchedule(week(SUN, Array(7).fill('Push')));
  const st = C.computeStatus(plan, GOALS, {}, '2026-11-01', [], { skips: { '2026-11-01': true }, grants: {} });
  assert.equal(st.canSkip, false);
  assert.equal(st.skipped, false);
  assert.equal(st.blocked, true);
});

test('grants: tokens for one day only, spent first, gone the next day', () => {
  const plan = C.parseSchedule(week(SUN, Array(7).fill('Push')));
  const grants = { '2026-11-02': 1 };
  const mon = C.computeStatus(plan, GOALS, pushDone(['2026-11-01']), '2026-11-02', [], { skips: {}, grants });
  assert.equal(mon.tokens, 0);
  assert.equal(mon.todayTokens, 1);
  assert.equal(mon.canSkip, true);
  const skipped = C.computeStatus(plan, GOALS, pushDone(['2026-11-01']), '2026-11-02', [], { skips: { '2026-11-02': true }, grants });
  assert.equal(skipped.skipped, true);
  assert.equal(skipped.todayTokens, 0);
  // Unused, Monday's grant still covers Monday's miss when the day is over, but not Tuesday's.
  const wed = C.computeStatus(plan, GOALS, pushDone(['2026-11-01']), '2026-11-04', [], { skips: {}, grants });
  assert.deepEqual(wed.history.map((h) => h.event), ['done', 'frozen', 'missed', 'pending']);
  assert.equal(wed.todayTokens, 0);
});

test('parseSettings: skips and grants', () => {
  const s = C.parseSettings({ 'SKIP_2026-11-02': 'true', 'GRANT_2026-11-02': '1', 'GRANT_2026-11-03': '0.5', 'SKIP_2026-11-04': '"yes"' });
  assert.deepEqual(s.tokens, { skips: { '2026-11-02': true }, grants: { '2026-11-02': 1 } });
});

// ---- time windows ----------------------------------------------------------

const WINDOWED = C.parseGoalEntries([{ from: '2026-01-01', workouts: [
  { name: 'Push', rules: [{ type: 'section', name: 'Push', min: 2 }], window: '06:00-10:00' },
  { name: 'Stretch', rules: [{ type: 'section', name: 'Stretch', min: 1 }], window: '21:00-23:00' },
  { name: 'Pull', rules: [{ type: 'section', name: 'Pull', min: 2 }] },
] }]);

test('windows: parse and format; start must come before end, 24:00 at most', () => {
  assert.deepEqual(C.parseWindow('06:00-10:30'), { start: 360, end: 630 });
  assert.deepEqual(C.parseWindow('6:00-24:00'), { start: 360, end: 1440 });
  for (const bad of ['10:00-06:00', '06:00-06:00', '06:00', '25:00-26:00', '06:60-07:00', '24:01-24:02', 7]) {
    assert.equal(C.parseWindow(bad), null, String(bad));
  }
  assert.equal(C.formatWindow({ start: 360, end: 1440 }), '06:00-24:00');
  assert.deepEqual(C.planParts('Upper @ 7:00-10:00 + Skate'),
    [{ name: 'Upper', window: { start: 420, end: 600 }, bad: false }, { name: 'Skate', window: null, bad: false }]);
  assert.deepEqual(C.workoutParts('Upper@07:00-10:00 + Skate'), ['Upper', 'Skate']);
  assert.equal(C.planParts('Upper@later')[0].bad, true);
});

test('windows: gated only in the window of a workout not yet done; the day needs every one', () => {
  const plan = C.parseSchedule(week(SUN, ['Push + Stretch', 'Rest', 'Rest', 'Rest', 'Rest', 'Rest', 'Rest']));
  const at = (now, ex = {}) => C.computeStatus(plan, WINDOWED, ex, SUN, [], null, now);
  assert.equal(at(5 * 60).blocked, false);
  assert.equal(at(6 * 60).blocked, true);
  assert.equal(at(10 * 60).blocked, false); // the morning window is over
  assert.equal(at(22 * 60).blocked, true);
  const s = at(7 * 60).sessions;
  assert.deepEqual(s.map((x) => [x.workout, x.start, x.end, x.done]),
    [['Push', '06:00', '10:00', false], ['Stretch', '21:00', '23:00', false]]);
  assert.deepEqual(s[0].goalsProgress.map((g) => g.name), ['Push']);
  // Push done: the morning opens, the evening still gates, the day isn't done.
  const morning = { [SUN]: logged({ push: 2 }) };
  assert.equal(at(7 * 60, morning).blocked, false);
  assert.equal(at(22 * 60, morning).blocked, true);
  assert.equal(at(22 * 60, morning).done, false);
  assert.equal(at(22 * 60, morning).open, false);
  // Without a time, every unmet window counts.
  assert.equal(C.computeStatus(plan, WINDOWED, morning, SUN, [], null).blocked, true);
  // A missed window still leaves the day to be met by midnight; missing it spends a token.
  const next = C.computeStatus(plan, WINDOWED, morning, C.addDays(SUN, 1), [], null, 0);
  assert.equal(next.history[0].event, 'missed');
});

test('windows: a day can set its own window; a workout without one gates all day', () => {
  const plan = C.parseSchedule(week(SUN, ['Push@12:00-13:00 + Pull', 'Rest', 'Rest', 'Rest', 'Rest', 'Rest', 'Rest']));
  const st = C.computeStatus(plan, WINDOWED, {}, SUN, [], null, 7 * 60);
  assert.deepEqual(st.sessions.map((x) => [x.workout, x.start, x.end]), [['Push', '12:00', '13:00'], ['Pull', '00:00', '24:00']]);
  assert.equal(st.blocked, true); // Pull, all day
  const pulled = C.computeStatus(plan, WINDOWED, { [SUN]: logged({ pull: 2 }) }, SUN, [], null, 7 * 60);
  assert.equal(pulled.blocked, false);
});

test('windows: a workout can change until an hour before its window', () => {
  const plan = C.parseSchedule(week(SUN, ['Push + Stretch', 'Push', 'Rest', 'Rest', 'Rest', 'Rest', 'Rest']));
  const save = (days, now) => C.validateSave(plan, WINDOWED, SUN, days, SUN, now);
  const rest = ['Rest', 'Rest', 'Rest', 'Rest', 'Rest', 'Rest', 'Rest'];
  // At 04:59 the 06:00 Push can still go; at 05:00 it can't.
  assert.equal(save(['Stretch', 'Push', ...rest.slice(2)], 4 * 60 + 59).ok, true);
  const late = save(['Stretch', 'Push', ...rest.slice(2)], 5 * 60);
  assert.match(late.errors.join(), /2026-11-01: "Push" starts within an hour, so it can't change/);
  // Once Push's window is over it stays as it was; Stretch (21:00) can still be dropped at 19:59.
  assert.equal(save(['Push', 'Push', ...rest.slice(2)], 19 * 60 + 59).ok, true);
  assert.equal(save(['Push', 'Push', ...rest.slice(2)], 20 * 60).ok, false);
  // Moving Stretch later counts as dropping it and planning it again.
  assert.equal(save(['Push + Stretch@22:00-23:00', 'Push', ...rest.slice(2)], 19 * 60).ok, true);
  assert.equal(save(['Push + Stretch@20:30-23:00', 'Push', ...rest.slice(2)], 19 * 60 + 45).ok, false);
  // Adding a workout today needs an hour's notice too; Pull (all day) never gets it.
  assert.equal(save(['Push + Stretch + Pull', 'Push', ...rest.slice(2)], 7 * 60).ok, false);
  // Tomorrow's 06:00 Push locks at 05:00 tomorrow, not at midnight.
  assert.equal(save(['Push + Stretch', 'Rest', ...rest.slice(2)], 23 * 60 + 30).ok, true);
  // The stored plan keeps the day's own windows, spelled one way.
  assert.equal(save(['Push + Stretch @ 22:00-23:00', 'Push', ...rest.slice(2)], 19 * 60).rows[0][1], 'Push + Stretch@22:00-23:00');
  assert.match(save(['Push + Stretch@soon', 'Push', ...rest.slice(2)], 0).errors.join(), /bad time window/);
});

test('windows: the default week saved in the last hour of a day starts the day after tomorrow', () => {
  const days = ['Pull', 'Pull', 'Pull', 'Pull', 'Rest', 'Rest', 'Rest'];
  const goals = C.goalsFor(WINDOWED, '2026-11-18').current;
  assert.equal(C.validateDefault(goals, days, '2026-11-17', 22 * 60 + 59).row[0], '2026-11-18');
  assert.equal(C.validateDefault(goals, days, '2026-11-17', 23 * 60).row[0], '2026-11-19');
});

test('windows: goals keep a valid window; a bad one or "@" in a name is refused', () => {
  const ok = C.validateGoals({}, [{ name: 'Push', rules: [{ type: 'section', name: 'Push', min: 1 }], window: '6:00-10:00' },
    { name: 'Pull', rules: [{ type: 'section', name: 'Pull', min: 1 }], window: '' }], SUN);
  assert.equal(ok.ok, true, ok.errors.join());
  assert.deepEqual(ok.workouts.map((w) => w.window), ['06:00-10:00', undefined]);
  assert.equal(C.goalsView(C.parseGoalEntries([{ from: SUN, workouts: ok.workouts }])[0].goals)[0].window, '06:00-10:00');
  const bad = C.validateGoals({}, [{ name: 'P@ss', rules: [{ type: 'section', name: 'Push', min: 1 }], window: '10:00-09:00' }], SUN);
  assert.match(bad.errors.join(), /"@" starts a time window/);
  assert.match(bad.errors.join(), /bad time window "10:00-09:00"/);
});

test('unlock code: HOTP truncation (RFC 4226 example), signed bytes too, padded to 6 digits', () => {
  const hex = '1f8698690e02ca16618550ef7f19da8e945b555a';
  const bytes = hex.match(/../g).map((h) => parseInt(h, 16));
  assert.equal(C.unlockCodeFrom(bytes), '872921');
  assert.equal(C.unlockCodeFrom(bytes.map((b) => (b > 127 ? b - 256 : b))), '872921');
  assert.equal(C.unlockMessage('2026-11-20'), 'open:2026-11-20');
  assert.equal(C.unlockCodeFrom([0, 0, 0, 5, ...Array(15).fill(0), 0]), '000005');
});
