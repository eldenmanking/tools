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

// An exercise's key: its name without the unit and notes suffixes, so
// "Figure skating [hr.min]" and "Squats [lb per side] (count) (s:17,2)" become
// "figure skating" and "squats".
function exerciseKey(name) {
  return norm_(String(name == null ? '' : name).split(/\s*[\[(]/)[0]);
}

// The Exercise tab: row 2 holds dates from column D on (newest first, blank
// spacer columns allowed); column B labels a section on its first row and the
// label carries down; column C names the exercise; a non-empty cell means that
// exercise was logged on that date.
//   headerIsos: ISO date or null for every column of row 2
//   dataRows:   rows 3.. of the tab as 2D values
// Returns { iso: { sections: { sectionKey: count }, exercises: { exerciseKey: true } } },
// counting distinct exercise rows per section.
function parseExercise(headerIsos, dataRows) {
  var out = {};
  var section = '';
  for (var r = 0; r < dataRows.length; r++) {
    var row = dataRows[r];
    var label = norm_(row[1]);
    if (label) section = label;
    var ex = exerciseKey(row[2]);
    if (!section || !ex) continue;
    var seen = {};
    for (var c = 3; c < row.length; c++) {
      var iso = headerIsos[c];
      if (!iso || seen[iso] || norm_(row[c]) === '') continue;
      seen[iso] = true; // two columns with the same date count an exercise once
      var day = out[iso] || (out[iso] = { sections: {}, exercises: {} });
      day.sections[section] = (day.sections[section] || 0) + 1;
      day.exercises[ex] = true;
    }
  }
  return out;
}

// Gate Goals tab rows (after the header): [workout, type, name, min], where
// type is "Section" (log at least <min> exercises from that Exercise tab
// section) or "Exercise" (log that exercise; <min> is ignored). A workout is
// done when all its rows are met.
// Returns { workoutKey: { name, rules: [{ type, name, key, min }] } }.
function parseGoals(rows) {
  var out = {};
  for (var i = 0; i < rows.length; i++) {
    var workout = String(rows[i][0] || '').trim();
    var type = norm_(rows[i][1]);
    var name = String(rows[i][2] || '').trim();
    if (!workout || !name || (type !== 'section' && type !== 'exercise')) continue;
    var min = type === 'exercise' ? 1 : Number(rows[i][3]);
    if (!(min > 0)) continue;
    var wk = norm_(workout);
    var g = out[wk] || (out[wk] = { name: workout, rules: [] });
    g.rules.push({
      type: type,
      name: name,
      key: type === 'exercise' ? exerciseKey(name) : norm_(name),
      min: min
    });
  }
  return out;
}

// ---- allowlist -------------------------------------------------------------

// Gate Allowlist tab rows (after the header): [effectiveFromIso, packages],
// packages comma-separated. Returns entries sorted by date.
function parseAllowlist(rows) {
  var byDate = {};
  for (var i = 0; i < rows.length; i++) {
    var iso = String(rows[i][0] || '').trim();
    if (!isIsoDate(iso)) continue;
    byDate[iso] = splitPackages_(rows[i][1]); // later rows win
  }
  return Object.keys(byDate).sort().map(function (d) { return { from: d, packages: byDate[d] }; });
}

function splitPackages_(s) {
  var seen = {};
  return String(s == null ? '' : s).split(/[\s,]+/).filter(function (p) {
    if (!p || seen[p]) return false;
    seen[p] = true;
    return true;
  }).sort();
}

// The allowlist in effect on <iso> ([] before the first entry) and the next
// one already saved to take effect later, if any.
function allowlistFor(entries, iso) {
  var current = [], pending = null;
  for (var i = 0; i < entries.length; i++) {
    if (entries[i].from <= iso) current = entries[i].packages;
    else if (!pending) pending = entries[i];
  }
  return { current: current, pending: pending };
}

var PACKAGE_RE_ = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;

// Check a new allowlist. It takes effect tomorrow (server date), so a change
// never unblocks anything today.
// Returns { ok, errors, row: [fromIso, packages] }.
function validateAllowlist(packages, today) {
  var list = splitPackages_((packages || []).join(','));
  var errors = list.filter(function (p) { return !PACKAGE_RE_.test(p); })
    .map(function (p) { return 'not a package name: ' + p; });
  return { ok: errors.length === 0, errors: errors, row: [addDays(today, 1), list.join(',')] };
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

// A plan is { days: parseSchedule(...), defaults: parseDefaults(...) }. The
// functions below also take a bare parseSchedule map (no defaults).
function plan_(p) {
  if (p && p.days && p.defaults) return p;
  return { days: (p && p.days) || p || {}, defaults: (p && p.defaults) || [] };
}

function storedWeeks_(days) {
  var weeks = {};
  for (var iso in days) weeks[weekStartOf(iso)] = true;
  return weeks;
}

// The earliest date the plan says anything about, or null.
function planStart_(plan) {
  var first = null;
  for (var iso in plan.days) {
    var w = weekStartOf(iso);
    if (first === null || w < first) first = w;
  }
  if (plan.defaults.length && (first === null || plan.defaults[0].from < first)) {
    first = plan.defaults[0].from;
  }
  return first;
}

// Gate Default tab rows (after the header): [effectiveFromIso, Sun, Mon, ...,
// Sat]. Returns entries sorted by date; a later row for the same date wins.
function parseDefaults(rows) {
  var byDate = {};
  for (var i = 0; i < rows.length; i++) {
    var iso = String(rows[i][0] || '').trim();
    if (!isIsoDate(iso)) continue;
    var days = [];
    for (var j = 1; j <= 7; j++) {
      var w = String(rows[i][j] == null ? '' : rows[i][j]).trim();
      days.push(isGymDay(w) ? w : REST);
    }
    byDate[iso] = days;
  }
  return Object.keys(byDate).sort().map(function (d) { return { from: d, days: byDate[d] }; });
}

// The default week in effect on <iso> (7 names, Sunday first) and the next
// one saved to take effect later, if any.
function defaultFor(defaults, iso) {
  var current = null, pending = null;
  for (var i = 0; i < defaults.length; i++) {
    if (defaults[i].from <= iso) current = defaults[i];
    else if (!pending) pending = defaults[i];
  }
  return { current: current, pending: pending };
}

// The workout planned for <iso> and where it came from:
//   saved   - the week was saved (its missing days are rest)
//   default - the week wasn't saved; the default in effect that day applies
//   copied  - neither; the same weekday of the week before is repeated
// Before the plan's first saved week or default there is no schedule (null).
function planFor_(p, iso) {
  var plan = plan_(p);
  var first = planStart_(plan);
  if (first === null) return { workout: null, source: null };
  var weeks = storedWeeks_(plan.days);
  var d = iso;
  var copied = false;
  while (d >= first) {
    if (weeks[weekStartOf(d)]) return { workout: plan.days[d] || REST, source: copied ? 'copied' : 'saved' };
    var def = defaultFor(plan.defaults, d).current;
    if (def) return { workout: def.days[dayOfWeek(d)], source: copied ? 'copied' : 'default' };
    d = addDays(d, -7);
    copied = true;
  }
  return { workout: null, source: null };
}

function workoutFor(plan, iso) {
  return planFor_(plan, iso).workout;
}

function weekView(plan, weekStart, today) {
  var days = [];
  for (var i = 0; i < 7; i++) {
    var iso = addDays(weekStart, i);
    var p = planFor_(plan, iso);
    days.push({ date: iso, workout: p.workout || REST, source: p.source, locked: iso <= today });
  }
  return { weekStart: weekStart, stored: !!storedWeeks_(plan_(plan).days)[weekStart], days: days };
}

// Shared checks for a week of workouts: at least MIN_GYM_DAYS_PER_WEEK gym
// days, every gym day a workout in Gate Goals. Returns { errors, names }, the
// names normalized ("" or rest -> "Rest").
function checkWeek_(goals, days, label) {
  var errors = [], names = [], gym = 0;
  for (var i = 0; i < 7; i++) {
    var w = String(days[i] || '').trim() || REST;
    if (isGymDay(w)) {
      gym++;
      if (!goals[norm_(w)]) errors.push(label(i) + ': unknown workout "' + w + '"');
    } else {
      w = REST;
    }
    names.push(w);
  }
  if (gym < MIN_GYM_DAYS_PER_WEEK) {
    errors.push('a week needs at least ' + MIN_GYM_DAYS_PER_WEEK + ' gym days, got ' + gym);
  }
  return { errors: errors, names: names };
}

var WEEKDAYS_ = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

// Check a new default week. It applies from tomorrow (server date) to every
// week that isn't saved, so it can't change today or any earlier day.
//   days: 7 workout names, Sunday first
// Returns { ok, errors, row: [fromIso, Sun..Sat] }.
function validateDefault(goals, days, today) {
  if (!days || days.length !== 7) {
    return { ok: false, errors: ['days must list 7 entries, Sunday first'], row: [] };
  }
  var c = checkWeek_(goals, days, function (i) { return WEEKDAYS_[i]; });
  return { ok: c.errors.length === 0, errors: c.errors, row: [addDays(today, 1)].concat(c.names) };
}

// Check a save of one whole week. A day can change only until midnight the
// night before it (so today and earlier are locked), the week needs at least
// MIN_GYM_DAYS_PER_WEEK gym days, and every gym day must name a workout in the
// Gate Goals tab.
//   days: 7 workout names, Sunday first ("Rest" or "" for rest)
// Returns { ok, errors: [string], rows: [[iso, workout]...] } (rows to store).
function validateSave(plan, goals, weekStart, days, today) {
  if (!isIsoDate(weekStart) || dayOfWeek(weekStart) !== 0) {
    return { ok: false, errors: ['weekStart must be a Sunday (yyyy-mm-dd)'], rows: [] };
  }
  if (!days || days.length !== 7) {
    return { ok: false, errors: ['days must list 7 entries, Sunday first'], rows: [] };
  }
  var c = checkWeek_(goals, days, function (i) { return addDays(weekStart, i); });
  var errors = c.errors, rows = [];
  for (var i = 0; i < 7; i++) {
    var iso = addDays(weekStart, i);
    var current = workoutFor(plan, iso) || REST;
    if (iso <= today && norm_(c.names[i]) !== norm_(current)) {
      errors.push(iso + ' is locked (was "' + current + '")');
    }
    rows.push([iso, c.names[i]]);
  }
  return { ok: errors.length === 0, errors: errors, rows: rows };
}

// ---- goals and streak ------------------------------------------------------

// <day> is one date's entry from parseExercise (or undefined if nothing was logged).
function dayProgress(goals, workout, day) {
  var g = goals[norm_(workout)];
  if (!g) return { done: false, unknownWorkout: true, progress: [] };
  var sections = (day && day.sections) || {};
  var exercises = (day && day.exercises) || {};
  var done = true;
  var progress = g.rules.map(function (r) {
    var logged = r.type === 'exercise' ? (exercises[r.key] ? 1 : 0) : (sections[r.key] || 0);
    if (logged < r.min) done = false;
    return { type: r.type, name: r.name, min: r.min, logged: logged, met: logged >= r.min };
  });
  return { done: done, progress: progress };
}

// Walk every scheduled day from the first stored week to <today>.
// Completed gym day: streak +1, and every DAYS_PER_TOKEN completions earns a
// freeze token (cap MAX_TOKENS). Missed gym day before today: spend a token if
// there is one, else the streak resets. Rest days change nothing; neither does
// today while it is still unmet. Logging on a rest day is a bonus.
function computeStatus(schedule, goals, exercise, today, allowlist) {
  var streak = 0, longest = 0, tokens = 0, completed = 0;
  var first = planStart_(plan_(schedule));
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
    // While blocked, every app outside this list is blocked.
    allowlist: allowlistFor(allowlist || [], today).current,
    pendingAllowlist: allowlistFor(allowlist || [], today).pending,
    history: history.slice(-14)
  };
}

if (typeof module !== 'undefined') {
  module.exports = {
    addDays: addDays, dayOfWeek: dayOfWeek, weekStartOf: weekStartOf, isIsoDate: isIsoDate,
    exerciseKey: exerciseKey, parseExercise: parseExercise, parseGoals: parseGoals,
    parseSchedule: parseSchedule, parseAllowlist: parseAllowlist, allowlistFor: allowlistFor,
    parseDefaults: parseDefaults, defaultFor: defaultFor, validateDefault: validateDefault,
    validateAllowlist: validateAllowlist,
    isGymDay: isGymDay, workoutFor: workoutFor, weekView: weekView, validateSave: validateSave,
    dayProgress: dayProgress, computeStatus: computeStatus,
    MIN_GYM_DAYS_PER_WEEK: MIN_GYM_DAYS_PER_WEEK, MAX_TOKENS: MAX_TOKENS
  };
}
