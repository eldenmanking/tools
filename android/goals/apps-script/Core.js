// Pure logic for the gym gate: no SpreadsheetApp, no clock. Every function takes
// "today" and sheet data as arguments, so the same file runs in Apps Script and
// in Node (tests/core.test.js) with fake dates.
//
// Dates are ISO strings ("2026-11-15") in the spreadsheet's time zone.
// Weeks start on Sunday.

var MIN_GYM_DAYS_PER_WEEK = 4;
var DAYS_PER_TOKEN = 7;
var MAX_TOKENS = 2;
var REST = 'Rest';

// ---- dates -----------------------------------------------------------------

function isoToUtc_(iso) {
  var p = iso.split('-');
  return Date.UTC(+p[0], +p[1] - 1, +p[2]);
}

function utcToIso_(ms) {
  return new Date(ms).toISOString().slice(0, 10);
}

function addDays(iso, n) {
  return utcToIso_(isoToUtc_(iso) + n * 86400000);
}

// 0 = Sunday ... 6 = Saturday
function dayOfWeek(iso) {
  return new Date(isoToUtc_(iso)).getUTCDay();
}

function weekStartOf(iso) {
  return addDays(iso, -dayOfWeek(iso));
}

function isIsoDate(s) {
  return typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && utcToIso_(isoToUtc_(s)) === s;
}

// ---- parsing sheet data ----------------------------------------------------

function norm_(s) {
  return String(s == null ? '' : s).trim().toLowerCase();
}

// The Exercise tab: row 2 holds dates from column D on (newest first, blank
// spacer columns allowed); column B labels a section on its first row and the
// label carries down; column C names the exercise; a non-empty cell means that
// exercise was logged on that date.
//   headerIsos: ISO date or null for every column of row 2
//   dataRows:   rows 3.. of the tab as 2D values
// Returns { iso: { sectionKey: count } }, counting distinct exercise rows.
function parseExercise(headerIsos, dataRows) {
  var out = {};
  var section = '';
  for (var r = 0; r < dataRows.length; r++) {
    var row = dataRows[r];
    var label = norm_(row[1]);
    if (label) section = label;
    if (!section || !norm_(row[2])) continue;
    var seen = {};
    for (var c = 3; c < row.length; c++) {
      var iso = headerIsos[c];
      if (!iso || seen[iso] || norm_(row[c]) === '') continue;
      seen[iso] = true; // two columns with the same date count an exercise once
      var day = out[iso] || (out[iso] = {});
      day[section] = (day[section] || 0) + 1;
    }
  }
  return out;
}

// Gate Goals tab rows (after the header): [workout, section, min exercises].
// Returns { workoutKey: { name, rules: [{ section, sectionKey, min }] } }.
function parseGoals(rows) {
  var out = {};
  for (var i = 0; i < rows.length; i++) {
    var name = String(rows[i][0] || '').trim();
    var section = String(rows[i][1] || '').trim();
    var min = Number(rows[i][2]);
    if (!name || !section || !(min > 0)) continue;
    var key = norm_(name);
    var g = out[key] || (out[key] = { name: name, rules: [] });
    g.rules.push({ section: section, sectionKey: norm_(section), min: min });
  }
  return out;
}

// Gate Schedule tab rows (after the header): [dateIso, workout or "Rest"].
// Returns { iso: workout }. Later rows win.
function parseSchedule(rows) {
  var out = {};
  for (var i = 0; i < rows.length; i++) {
    var iso = String(rows[i][0] || '').trim();
    var w = String(rows[i][1] || '').trim();
    if (isIsoDate(iso)) out[iso] = w || REST;
  }
  return out;
}

// ---- schedule --------------------------------------------------------------

function isGymDay(workout) {
  return !!workout && norm_(workout) !== 'rest';
}

function storedWeeks_(schedule) {
  var weeks = {};
  for (var iso in schedule) weeks[weekStartOf(iso)] = true;
  return weeks;
}

function firstWeek_(schedule) {
  var first = null;
  for (var iso in schedule) {
    var w = weekStartOf(iso);
    if (first === null || w < first) first = w;
  }
  return first;
}

// The workout planned for <iso>: a stored day wins; a stored week's missing
// days are rest; a week with nothing stored repeats the week before it. Before
// the first stored week there is no schedule (null).
function workoutFor(schedule, iso) {
  var first = firstWeek_(schedule);
  if (first === null) return null;
  var weeks = storedWeeks_(schedule);
  var d = iso;
  while (weekStartOf(d) >= first) {
    if (weeks[weekStartOf(d)]) return schedule[d] || REST;
    d = addDays(d, -7);
  }
  return null;
}

function weekView(schedule, weekStart, today) {
  var days = [];
  for (var i = 0; i < 7; i++) {
    var iso = addDays(weekStart, i);
    days.push({ date: iso, workout: workoutFor(schedule, iso) || REST, locked: iso <= today });
  }
  return { weekStart: weekStart, stored: !!storedWeeks_(schedule)[weekStart], days: days };
}

// Check a save of one whole week. A day can change only until midnight the
// night before it (so today and earlier are locked), the week needs at least
// MIN_GYM_DAYS_PER_WEEK gym days, and every gym day must name a workout in the
// Gate Goals tab.
//   days: 7 workout names, Sunday first ("Rest" or "" for rest)
// Returns { ok, errors: [string], rows: [[iso, workout]...] } (rows to store).
function validateSave(schedule, goals, weekStart, days, today) {
  var errors = [];
  if (!isIsoDate(weekStart) || dayOfWeek(weekStart) !== 0) {
    return { ok: false, errors: ['weekStart must be a Sunday (yyyy-mm-dd)'], rows: [] };
  }
  if (!days || days.length !== 7) {
    return { ok: false, errors: ['days must list 7 entries, Sunday first'], rows: [] };
  }
  var rows = [];
  var gym = 0;
  for (var i = 0; i < 7; i++) {
    var iso = addDays(weekStart, i);
    var w = String(days[i] || '').trim() || REST;
    if (isGymDay(w)) {
      gym++;
      if (!goals[norm_(w)]) errors.push(iso + ': unknown workout "' + w + '"');
    } else {
      w = REST;
    }
    var current = workoutFor(schedule, iso) || REST;
    if (iso <= today && norm_(w) !== norm_(current)) {
      errors.push(iso + ' is locked (was "' + current + '")');
    }
    rows.push([iso, w]);
  }
  if (gym < MIN_GYM_DAYS_PER_WEEK) {
    errors.push('a week needs at least ' + MIN_GYM_DAYS_PER_WEEK + ' gym days, got ' + gym);
  }
  return { ok: errors.length === 0, errors: errors, rows: rows };
}

// ---- goals and streak ------------------------------------------------------

function dayProgress(goals, workout, counts) {
  var g = goals[norm_(workout)];
  if (!g) return { done: false, unknownWorkout: true, progress: [] };
  counts = counts || {};
  var done = true;
  var progress = g.rules.map(function (r) {
    var logged = counts[r.sectionKey] || 0;
    if (logged < r.min) done = false;
    return { section: r.section, min: r.min, logged: logged, met: logged >= r.min };
  });
  return { done: done, progress: progress };
}

// Walk every scheduled day from the first stored week to <today>.
// Completed gym day: streak +1, and every DAYS_PER_TOKEN completions earns a
// freeze token (cap MAX_TOKENS). Missed gym day before today: spend a token if
// there is one, else the streak resets. Rest days change nothing; neither does
// today while it is still unmet. Logging on a rest day is a bonus.
function computeStatus(schedule, goals, exercise, today) {
  var streak = 0, longest = 0, tokens = 0, completed = 0;
  var first = firstWeek_(schedule);
  var history = [];
  if (first !== null) {
    for (var d = first; d <= today; d = addDays(d, 1)) {
      var w = workoutFor(schedule, d);
      if (!isGymDay(w)) continue;
      var p = dayProgress(goals, w, exercise[d]);
      var event;
      if (p.done) {
        streak++;
        completed++;
        if (completed % DAYS_PER_TOKEN === 0 && tokens < MAX_TOKENS) tokens++;
        event = 'done';
      } else if (d === today) {
        event = 'pending';
      } else if (tokens > 0) {
        tokens--;
        event = 'frozen';
      } else {
        streak = 0;
        event = 'missed';
      }
      if (streak > longest) longest = streak;
      history.push({ date: d, workout: w, event: event });
    }
  }
  var todayWorkout = workoutFor(schedule, today) || REST;
  var scheduled = isGymDay(todayWorkout);
  var tp = scheduled ? dayProgress(goals, todayWorkout, exercise[today]) : { done: false, progress: [] };
  return {
    today: today,
    todayScheduled: scheduled,
    workout: todayWorkout,
    done: scheduled ? tp.done : false,
    unknownWorkout: !!tp.unknownWorkout,
    // The app blocks only while this is true.
    blocked: scheduled && !tp.done,
    streak: streak,
    longest: longest,
    tokens: tokens,
    goalsProgress: tp.progress,
    history: history.slice(-14)
  };
}

if (typeof module !== 'undefined') {
  module.exports = {
    addDays: addDays, dayOfWeek: dayOfWeek, weekStartOf: weekStartOf, isIsoDate: isIsoDate,
    parseExercise: parseExercise, parseGoals: parseGoals, parseSchedule: parseSchedule,
    isGymDay: isGymDay, workoutFor: workoutFor, weekView: weekView, validateSave: validateSave,
    dayProgress: dayProgress, computeStatus: computeStatus,
    MIN_GYM_DAYS_PER_WEEK: MIN_GYM_DAYS_PER_WEEK, MAX_TOKENS: MAX_TOKENS
  };
}
