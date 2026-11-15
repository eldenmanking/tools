// Run from the repo root: node --test 'android/goals/apps-script/tests/*.test.js'
const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('../Core.js');

const GOALS = C.parseGoals([
  ['Push', 'Push', 2],
  ['Pull', 'Pull', 2],
  ['Upper', 'Push', 1],
  ['Upper', 'Pull', 1],
]);

// A week of rows, Sunday first.
function week(start, days) {
  return days.map((w, i) => [C.addDays(start, i), w]);
}

// exercise map: logging Push x2 on each of <dates>.
function pushDone(dates) {
  const ex = {};
  for (const d of dates) ex[d] = { push: 2 };
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
    ['', 'Pull', 'Rows', 'x', 'stray', '', ''],  // spacer column ignored
  ];
  assert.deepEqual(C.parseExercise(header, rows), {
    '2026-11-13': { push: 1, pull: 1 },
    '2026-11-12': { push: 2 },
  });
});

test('dayProgress: every rule of the workout must be met', () => {
  assert.equal(C.dayProgress(GOALS, 'Upper', { push: 1, pull: 1 }).done, true);
  const p = C.dayProgress(GOALS, 'upper', { push: 3 });
  assert.equal(p.done, false);
  assert.deepEqual(p.progress.map((r) => r.met), [true, false]);
  assert.equal(C.dayProgress(GOALS, 'Cardio', {}).unknownWorkout, true);
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
  const few = C.validateSave({}, GOALS, SUN, ['Rest', 'Push', 'Pull', 'Rest', 'Push', 'Rest', 'Rest'], today);
  assert.match(few.errors.join(), /at least 4 gym days, got 3/);
  const unknown = C.validateSave({}, GOALS, SUN, ['Yoga', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest'], today);
  assert.match(unknown.errors.join(), /unknown workout "Yoga"/);
  assert.equal(C.validateSave({}, GOALS, '2026-11-02', Array(7).fill('Push'), today).ok, false);
});

test('validateSave: today and earlier are locked, tomorrow is editable', () => {
  const s = C.parseSchedule(week(SUN, ['Rest', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest']));
  const today = '2026-11-03'; // Tuesday (Pull)
  // Changing Wednesday (tomorrow) is allowed.
  const fine = C.validateSave(s, GOALS, SUN, ['Rest', 'Push', 'Pull', 'Push', 'Rest', 'Pull', 'Rest'], today);
  assert.equal(fine.ok, true, fine.errors.join());
  // Changing today is not.
  const same = C.validateSave(s, GOALS, SUN, ['Rest', 'Push', 'Rest', 'Push', 'Push', 'Pull', 'Rest'], today);
  assert.match(same.errors.join(), /2026-11-03 is locked/);
  // Changing a past day is not.
  const past = C.validateSave(s, GOALS, SUN, ['Push', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest'], today);
  assert.match(past.errors.join(), /2026-11-01 is locked/);
  // Locks compare against the copied schedule too: next-next week's Sunday is
  // editable, but this week's copy of last week is locked.
  const s3 = C.parseSchedule(week('2026-10-25', ['Rest', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest']));
  const copied = C.validateSave(s3, GOALS, SUN, ['Push', 'Push', 'Pull', 'Rest', 'Push', 'Pull', 'Rest'], '2026-11-01');
  assert.match(copied.errors.join(), /2026-11-01 is locked \(was "Rest"\)/);
});

test('computeStatus: rest day is not blocked; today unmet is pending, not a miss', () => {
  const s = C.parseSchedule(week(SUN, ['Rest', 'Push', 'Push', 'Rest', 'Push', 'Push', 'Rest']));
  const rest = C.computeStatus(s, GOALS, {}, '2026-11-01');
  assert.equal(rest.todayScheduled, false);
  assert.equal(rest.blocked, false);
  const pending = C.computeStatus(s, GOALS, pushDone(['2026-11-02']), '2026-11-03');
  assert.equal(pending.blocked, true);
  assert.equal(pending.streak, 1);
  assert.deepEqual(pending.goalsProgress, [{ section: 'Push', min: 2, logged: 0, met: false }]);
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
  for (let i = 15; i < 21; i++) ex[C.addDays(start, i)] = { push: 2 };
  const st = C.computeStatus(s, GOALS, ex, C.addDays(start, 20));
  assert.equal(st.tokens, 1); // 20 completions so far: 2 earned, 1 spent
  ex[C.addDays(start, 21)] = { push: 2 };
  assert.equal(C.computeStatus(s, GOALS, ex, C.addDays(start, 21)).tokens, 2);
});

test('computeStatus: no schedule at all means nothing is blocked', () => {
  const st = C.computeStatus({}, GOALS, {}, '2026-11-15');
  assert.equal(st.blocked, false);
  assert.equal(st.streak, 0);
});
