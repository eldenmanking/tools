// Pure logic for the gym gate: no Apps Script services, no clock. Every function takes
// "today", the Exercise tab and the stored settings as arguments, so the same file runs in Apps Script and
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

// A spreadsheet serial date (days since 1899-12-30; a fraction is the time of
// day) -> its ISO date. Serial dates carry no time zone.
function serialToIso(n) {
  return addDays('1899-12-30', Math.floor(n));
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

// Goal rows: [workout, type, name, min] (workoutRows_ makes them), where
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

// Allowlist rows: [effectiveFromIso, packages],
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

// Schedule rows: [dateIso, workout or "Rest"].
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

// Default week rows: [effectiveFromIso, Sun, Mon, ..., Sat]. Returns entries sorted by date; a later row for the same date wins.
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
// days, every gym day a known workout (except days where locked(i) is true:
// they keep whatever they had). Returns { errors, names }, the names normalized
// ("" or rest -> "Rest").
function checkWeek_(goals, days, label, locked) {
  var errors = [], names = [], gym = 0;
  for (var i = 0; i < 7; i++) {
    var w = String(days[i] || '').trim() || REST;
    if (isGymDay(w)) {
      gym++;
      w = workoutParts(w).join(' + ');
      if (!(locked && locked(i))) {
        unknownParts_(goals, w).forEach(function (p) { errors.push(label(i) + ': unknown workout "' + p + '"'); });
      }
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
// MIN_GYM_DAYS_PER_WEEK gym days, and every gym day from tomorrow on must name a
// workout in <goals> (the goals in effect from tomorrow).
//   days: 7 workout names, Sunday first ("Rest" or "" for rest)
// Returns { ok, errors: [string], rows: [[iso, workout]...] } (rows to store).
function validateSave(plan, goals, weekStart, days, today) {
  if (!isIsoDate(weekStart) || dayOfWeek(weekStart) !== 0) {
    return { ok: false, errors: ['weekStart must be a Sunday (yyyy-mm-dd)'], rows: [] };
  }
  if (!days || days.length !== 7) {
    return { ok: false, errors: ['days must list 7 entries, Sunday first'], rows: [] };
  }
  var c = checkWeek_(goals, days, function (i) { return addDays(weekStart, i); },
    function (i) { return addDays(weekStart, i) <= today; });
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

// ---- goals ---------------------------------------------------------------

// Goal rows for parseGoals from saved workouts:
//   [{ name, rules: [{ type: "section" | "exercise", name, min }] }]
function workoutRows_(workouts) {
  var rows = [];
  (workouts || []).forEach(function (w) {
    ((w && w.rules) || []).forEach(function (r) { rows.push([w.name, r.type, r.name, r.min]); });
  });
  return rows;
}

// Saved goals: [{ from, workouts }]. Returns [{ from, goals: parseGoals(...) }]
// sorted by date.
function parseGoalEntries(entries) {
  return entries.slice().sort(function (a, b) { return a.from < b.from ? -1 : 1; })
    .map(function (e) { return { from: e.from, goals: parseGoals(workoutRows_(e.workouts)) }; });
}

// The goals in effect on <iso> ({} if none are saved) and the next ones saved
// to take effect later, if any. The first entry also covers earlier dates.
function goalsFor(entries, iso) {
  var current = entries.length ? entries[0].goals : {}, pending = null;
  for (var i = 0; i < entries.length; i++) {
    if (entries[i].from <= iso) current = entries[i].goals;
    else if (!pending) pending = entries[i];
  }
  return { current: current, pending: pending };
}

function goalsOn_(goals, iso) {
  return Array.isArray(goals) ? goalsFor(goals, iso).current : goals;
}

// A parseGoals map as the app sees it: [{ name, rules: [{ type, name, min }] }].
function goalsView(goals) {
  return Object.keys(goals).map(function (k) {
    return {
      name: goals[k].name,
      rules: goals[k].rules.map(function (r) { return { type: r.type, name: r.name, min: r.min }; })
    };
  });
}

// Check new goals. They take effect tomorrow (server date), so a change never
// unblocks anything today, and a workout still planned from tomorrow on can't
// be removed (change the schedule first).
//   workouts: [{ name, rules: [{ type: "section" | "exercise", name, min }] }]
// Returns { ok, errors, from, workouts } (workouts cleaned up, to store).
function validateGoals(plan, workouts, today) {
  if (!Array.isArray(workouts) || !workouts.length) {
    return { ok: false, errors: ['workouts must list at least one workout'], from: null, workouts: [] };
  }
  var errors = [], out = [], seen = {};
  workouts.forEach(function (w, i) {
    var name = String((w && w.name) || '').trim();
    var label = name || 'workout ' + (i + 1);
    if (!name) errors.push(label + ': needs a name');
    else if (!isGymDay(name)) errors.push('"' + name + '" is reserved for rest days');
    else if (name.indexOf('+') >= 0) errors.push('"' + name + '": "+" joins workouts on one day, so names can\'t use it');
    else if (seen[norm_(name)]) errors.push('"' + name + '" is listed twice');
    seen[norm_(name)] = true;
    var rules = (w && Array.isArray(w.rules)) ? w.rules : [];
    if (!rules.length) errors.push(label + ': needs at least one goal');
    out.push({
      name: name,
      rules: rules.map(function (r) {
        var type = norm_(r && r.type);
        var rn = String((r && r.name) || '').trim();
        var min = type === 'exercise' ? 1 : Number(r && r.min);
        if (type !== 'section' && type !== 'exercise') {
          errors.push(label + ': unknown goal type "' + (r && r.type) + '"');
        } else if (!rn) {
          errors.push(label + ': a goal needs a name');
        } else if (!(min >= 1 && min === Math.floor(min))) {
          errors.push(label + ': ' + rn + ' needs a whole number of exercises, 1 or more');
        }
        return { type: type, name: rn, min: min };
      })
    });
  });
  var from = addDays(today, 1);
  var goals = parseGoals(workoutRows_(out));
  var p = plan_(plan);
  // Two weeks from tomorrow cover the default and copied weeks; saved days can
  // be further out.
  var dates = [];
  for (var i = 0; i < 14; i++) dates.push(addDays(from, i));
  Object.keys(p.days).sort().forEach(function (d) { if (d >= from) dates.push(d); });
  var reported = {};
  dates.forEach(function (d) {
    var w = workoutFor(p, d);
    if (!isGymDay(w)) return;
    unknownParts_(goals, w).forEach(function (part) {
      if (reported[norm_(part)]) return;
      reported[norm_(part)] = true;
      errors.push('"' + part + '" is still planned on ' + d + '; change the schedule first');
    });
  });
  return { ok: errors.length === 0, errors: errors, from: from, workouts: out };
}

// ---- stored settings -------------------------------------------------------

// The settings are script properties, one per dated entry, each a JSON array:
//   WEEK_<sunday>        7 workout names, Sunday first (a saved week)
//   DEFAULT_<from>       7 workout names, Sunday first (the default week)
//   GOALS_<from>         [{ name, rules }] (see validateGoals)
//   ALLOWLIST_<from>     package names
// Other properties and malformed values are ignored.
var SETTING_KEY_RE_ = /^(WEEK|DEFAULT|GOALS|ALLOWLIST)_(\d{4}-\d{2}-\d{2})$/;

function settingKey(kind, iso) {
  return kind + '_' + iso;
}

// Returns { plan: { days, defaults }, goals: parseGoalEntries(...), allowlist: parseAllowlist(...) }.
function parseSettings(props) {
  var days = [], defaults = [], goals = [], allow = [];
  Object.keys(props || {}).forEach(function (k) {
    var m = SETTING_KEY_RE_.exec(k);
    if (!m || !isIsoDate(m[2])) return;
    var v;
    try { v = JSON.parse(props[k]); } catch (e) { return; }
    if (!Array.isArray(v)) return;
    if (m[1] === 'WEEK') {
      if (dayOfWeek(m[2]) !== 0) return;
      for (var i = 0; i < 7; i++) days.push([addDays(m[2], i), v[i]]);
    } else if (m[1] === 'DEFAULT') {
      defaults.push([m[2]].concat(v));
    } else if (m[1] === 'GOALS') {
      goals.push({ from: m[2], workouts: v });
    } else {
      allow.push([m[2], v.join(',')]);
    }
  });
  return {
    plan: { days: parseSchedule(days), defaults: parseDefaults(defaults) },
    goals: parseGoalEntries(goals),
    allowlist: parseAllowlist(allow)
  };
}

// ---- goals and streak ------------------------------------------------------

// A day's plan can combine workouts with "+" ("Upper + Skate"): it is done when
// every goal of every part is met. A goal shared by parts (same section or
// exercise) counts once, with the higher minimum.
function workoutParts(name) {
  return String(name == null ? '' : name).split('+').map(function (p) { return p.trim(); })
    .filter(function (p) { return p; });
}

// The parts of <name> that aren't workouts in <goals>.
function unknownParts_(goals, name) {
  return workoutParts(name).filter(function (p) { return !goals[norm_(p)]; });
}

// The merged { name, rules } for <name> (one workout or several joined by "+"),
// or null if any part is unknown.
function workoutGoals(goals, name) {
  var parts = workoutParts(name);
  if (!parts.length || unknownParts_(goals, name).length) return null;
  var rules = [], byKey = {};
  parts.forEach(function (p) {
    goals[norm_(p)].rules.forEach(function (r) {
      var k = r.type + ':' + r.key;
      if (byKey[k]) {
        byKey[k].min = Math.max(byKey[k].min, r.min);
      } else {
        byKey[k] = { type: r.type, name: r.name, key: r.key, min: r.min };
        rules.push(byKey[k]);
      }
    });
  });
  return { name: name, rules: rules };
}

// <day> is one date's entry from parseExercise (or undefined if nothing was logged).
function dayProgress(goals, workout, day) {
  var g = workoutGoals(goals, workout);
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
//   goals: goal entries (parseGoalEntries), so each day is judged by the goals
//          in effect that day; a bare parseGoals map applies to every day.
function computeStatus(schedule, goals, exercise, today, allowlist) {
  var streak = 0, longest = 0, tokens = 0, completed = 0;
  var first = planStart_(plan_(schedule));
  var history = [];
  if (first !== null) {
    for (var d = first; d <= today; d = addDays(d, 1)) {
      var w = workoutFor(schedule, d);
      if (!isGymDay(w)) continue;
      var p = dayProgress(goalsOn_(goals, d), w, exercise[d]);
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
  var tp = scheduled ? dayProgress(goalsOn_(goals, today), todayWorkout, exercise[today]) : { done: false, progress: [] };
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
    week: weekView(schedule, weekStartOf(today), today).days,
    // While blocked, every app outside this list is blocked.
    allowlist: allowlistFor(allowlist || [], today).current,
    pendingAllowlist: allowlistFor(allowlist || [], today).pending,
    history: history.slice(-14)
  };
}

if (typeof module !== 'undefined') {
  module.exports = {
    addDays: addDays, serialToIso: serialToIso, dayOfWeek: dayOfWeek, weekStartOf: weekStartOf, isIsoDate: isIsoDate,
    exerciseKey: exerciseKey, workoutParts: workoutParts, workoutGoals: workoutGoals, parseExercise: parseExercise, parseGoals: parseGoals,
    parseSchedule: parseSchedule, parseAllowlist: parseAllowlist, allowlistFor: allowlistFor,
    parseDefaults: parseDefaults, defaultFor: defaultFor, validateDefault: validateDefault,
    validateAllowlist: validateAllowlist,
    parseGoalEntries: parseGoalEntries, goalsFor: goalsFor, goalsView: goalsView,
    validateGoals: validateGoals, parseSettings: parseSettings, settingKey: settingKey,
    isGymDay: isGymDay, workoutFor: workoutFor, weekView: weekView, validateSave: validateSave,
    dayProgress: dayProgress, computeStatus: computeStatus,
    MIN_GYM_DAYS_PER_WEEK: MIN_GYM_DAYS_PER_WEEK, MAX_TOKENS: MAX_TOKENS
  };
}
