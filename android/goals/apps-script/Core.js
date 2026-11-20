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

// ---- time windows ----------------------------------------------------------

// A workout is gated only in its window, "HH:MM-HH:MM" in the spreadsheet's
// time zone (start before end, end at most 24:00). Without one it is gated all
// day. In code a window is { start, end } in minutes since midnight.
var ALL_DAY = { start: 0, end: 1440 };
// A workout on a day can be planned, moved or dropped until this many minutes
// before its window starts.
var LOCK_MINUTES = 60;

function parseTime_(s) {
  var m = /^(\d{1,2}):(\d{2})$/.exec(String(s).trim());
  if (!m) return null;
  var h = +m[1], min = +m[2];
  if (min > 59 || h > 24 || (h === 24 && min > 0)) return null;
  return h * 60 + min;
}

// "06:00-10:00" -> { start: 360, end: 600 }, or null if it isn't a window.
function parseWindow(s) {
  if (typeof s !== 'string') return null;
  var p = s.split('-');
  if (p.length !== 2) return null;
  var a = parseTime_(p[0]), b = parseTime_(p[1]);
  return a === null || b === null || a >= b ? null : { start: a, end: b };
}

function formatTime_(m) {
  var h = Math.floor(m / 60), min = m % 60;
  return (h < 10 ? '0' : '') + h + ':' + (min < 10 ? '0' : '') + min;
}

function formatWindow(w) {
  return formatTime_(w.start) + '-' + formatTime_(w.end);
}

// Minutes from <nowMin> on <today> until <minute> on <iso>.
function minutesUntil_(today, nowMin, iso, minute) {
  return (isoToUtc_(iso) - isoToUtc_(today)) / 60000 + minute - nowMin;
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
// exercise was logged on that date, one set per entry (see setCount).
//   headerIsos: ISO date or null for every column of row 2
//   dataRows:   rows 3.. of the tab as 2D values
// Returns { iso: { sections: { sectionKey: sets }, exercises: { exerciseKey: true } } },
// counting the sets logged per section.
// How many sets a logged cell holds: its entries are separated by "," or ";"
// outside parentheses ("1/2:45(8),45(8);50(6)" is 3), and empty entries don't
// count. A non-empty cell is at least 1 set.
function setCount(cell) {
  var s = String(cell == null ? '' : cell);
  var depth = 0, sets = 0, entry = false;
  for (var i = 0; i < s.length; i++) {
    var ch = s.charAt(i);
    if (ch === '(') depth++;
    else if (ch === ')') depth = Math.max(0, depth - 1);
    if (depth === 0 && (ch === ',' || ch === ';')) {
      if (entry) sets++;
      entry = false;
    } else if (!/\s/.test(ch)) {
      entry = true;
    }
  }
  if (entry) sets++;
  return Math.max(sets, s.trim() ? 1 : 0);
}

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
      day.sections[section] = (day.sections[section] || 0) + setCount(row[c]);
      day.exercises[ex] = true;
    }
  }
  return out;
}

// Goal rows: [workout, type, name, min] (workoutRows_ makes them), where
// type is "Section" (log at least <min> sets from that Exercise tab
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
    // Days before today are over. From today on each workout locks on its own, an hour
    // before its window (see validateSave).
    days.push({ date: iso, workout: p.workout || REST, source: p.source, locked: iso < today });
  }
  return { weekStart: weekStart, stored: !!storedWeeks_(plan_(plan).days)[weekStart], days: days };
}

// Shared checks for a week of plans: at least <minGym> gym days, every part a
// known workout in goalsAt(i) (except parts keep(i, name) says stay as they
// were), and every day's own window well formed. Returns { errors, names }, the
// plans normalized ("" or rest -> "Rest", "Upper @ 7:00-10:00+Skate" ->
// "Upper@07:00-10:00 + Skate").
function checkWeek_(goalsAt, days, label, keep, minGym) {
  var errors = [], names = [], gym = 0;
  for (var i = 0; i < 7; i++) {
    var w = String(days[i] || '').trim() || REST;
    if (isGymDay(w)) {
      gym++;
      var parts = planParts(w);
      parts.forEach(function (p) {
        if (p.bad) errors.push(label(i) + ': "' + p.name + '" has a bad time window (use HH:MM-HH:MM)');
        else if (!(keep && keep(i, p.name)) && !goalsAt(i)[norm_(p.name)]) {
          errors.push(label(i) + ': unknown workout "' + p.name + '"');
        }
      });
      w = parts.map(function (p) { return p.name + (p.window ? '@' + formatWindow(p.window) : ''); }).join(' + ');
    } else {
      w = REST;
    }
    names.push(w);
  }
  if (gym < minGym) {
    errors.push('a week needs at least ' + minGym + ' gym days, got ' + gym);
  }
  return { errors: errors, names: names };
}

var WEEKDAYS_ = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

// Check a new default week. It applies from tomorrow (server date) to every
// week that isn't saved, so it can't change today or any earlier day; saved in
// the last LOCK_MINUTES of the day (<nowMin>), from the day after, so it can't
// change a workout about to start at midnight either.
//   days: 7 plans, Sunday first
// Returns { ok, errors, row: [fromIso, Sun..Sat] }.
function validateDefault(goals, days, today, nowMin) {
  if (!days || days.length !== 7) {
    return { ok: false, errors: ['days must list 7 entries, Sunday first'], row: [] };
  }
  var c = checkWeek_(function () { return goals; }, days, function (i) { return WEEKDAYS_[i]; }, null, MIN_GYM_DAYS_PER_WEEK);
  var from = addDays(today, (nowMin || 0) >= 1440 - LOCK_MINUTES ? 2 : 1);
  return { ok: c.errors.length === 0, errors: c.errors, row: [from].concat(c.names) };
}

// Check a save of one whole week. Days before today are over and can't change.
// From today on, each workout can be planned, moved or dropped until
// LOCK_MINUTES before its window starts (<nowMin>: minutes since midnight
// today), and a newly planned one must be a workout in the goals in effect that
// day. A saved week has no minimum of gym days: the night before is trusted to
// plan the next day; the minimum applies to the default week.
//   goals: goal entries (parseGoalEntries) or one parseGoals map for every day
//   days: 7 plans, Sunday first ("Rest" or "" for rest; see planParts)
// Returns { ok, errors: [string], rows: [[iso, workout]...] } (rows to store).
function validateSave(plan, goals, weekStart, days, today, nowMin) {
  if (!isIsoDate(weekStart) || dayOfWeek(weekStart) !== 0) {
    return { ok: false, errors: ['weekStart must be a Sunday (yyyy-mm-dd)'], rows: [] };
  }
  if (!days || days.length !== 7) {
    return { ok: false, errors: ['days must list 7 entries, Sunday first'], rows: [] };
  }
  nowMin = nowMin || 0;
  var isoAt = function (i) { return addDays(weekStart, i); };
  var current = function (i) { return workoutFor(plan, isoAt(i)) || REST; };
  // A workout already planned that day keeps passing, even if its goals were removed since.
  var keep = function (i, name) {
    return workoutParts(current(i)).some(function (n) { return norm_(n) === norm_(name); });
  };
  var c = checkWeek_(function (i) { return goalsOn_(goals, isoAt(i)); }, days, isoAt, keep, 0);
  var errors = c.errors, rows = [];
  for (var i = 0; i < 7; i++) {
    var iso = isoAt(i), was = current(i);
    if (iso < today) {
      if (norm_(c.names[i]) !== norm_(was)) errors.push(iso + ' is over (was "' + was + '")');
    } else {
      var g = goalsOn_(goals, iso);
      var before = sessionsFor_(g, was), after = sessionsFor_(g, c.names[i]);
      var locked = function (s) { return minutesUntil_(today, nowMin, iso, s.window.start) <= LOCK_MINUTES; };
      var has = function (list, s) { return list.some(function (o) { return sessionKey_(o) === sessionKey_(s); }); };
      before.forEach(function (s) {
        if (locked(s) && !has(after, s)) errors.push(iso + ': "' + s.workout + '" starts within an hour, so it can\'t change');
      });
      after.forEach(function (s) {
        if (locked(s) && !has(before, s)) errors.push(iso + ': "' + s.workout + '" would start within an hour; plan it at least an hour ahead');
      });
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

// parseGoals for saved workouts, each with its window (see parseWindow) when it has one.
function goalsMap_(workouts) {
  var goals = parseGoals(workoutRows_(workouts));
  (workouts || []).forEach(function (w) {
    var g = w && goals[norm_(w.name)];
    var win = w && parseWindow(w.window);
    if (g && win) g.window = win;
  });
  return goals;
}

// Saved goals: [{ from, workouts }]. Returns [{ from, goals: parseGoals(...) }]
// sorted by date.
function parseGoalEntries(entries) {
  return entries.slice().sort(function (a, b) { return a.from < b.from ? -1 : 1; })
    .map(function (e) { return { from: e.from, goals: goalsMap_(e.workouts) }; });
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

// A parseGoals map as the app sees it: [{ name, rules: [{ type, name, min }], window? }].
function goalsView(goals) {
  return Object.keys(goals).map(function (k) {
    var v = {
      name: goals[k].name,
      rules: goals[k].rules.map(function (r) { return { type: r.type, name: r.name, min: r.min }; })
    };
    if (goals[k].window) v.window = formatWindow(goals[k].window);
    return v;
  });
}

// Check new goals. They take effect tomorrow (server date), so a change never
// unblocks anything today, and a workout still planned from tomorrow on can't
// be removed (change the schedule first).
//   workouts: [{ name, rules: [{ type: "section" | "exercise", name, min }], window? }]
//   (window: "HH:MM-HH:MM", gated all day when left out)
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
    else if (name.indexOf('@') >= 0) errors.push('"' + name + '": "@" starts a time window, so names can\'t use it');
    else if (seen[norm_(name)]) errors.push('"' + name + '" is listed twice');
    seen[norm_(name)] = true;
    var rules = (w && Array.isArray(w.rules)) ? w.rules : [];
    if (!rules.length) errors.push(label + ': needs at least one goal');
    var win = w && w.window != null && w.window !== '' ? parseWindow(w.window) : null;
    if (w && w.window != null && w.window !== '' && !win) {
      errors.push(label + ': bad time window "' + w.window + '" (use HH:MM-HH:MM)');
    }
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
          errors.push(label + ': ' + rn + ' needs a whole number of sets, 1 or more');
        }
        return { type: type, name: rn, min: min };
      }),
      window: win ? formatWindow(win) : undefined
    });
  });
  var from = addDays(today, 1);
  out.forEach(function (w) { if (!w.window) delete w.window; });
  var goals = goalsMap_(out);
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

// The settings are script properties, one per dated entry, each JSON:
//   WEEK_<sunday>        7 workout names, Sunday first (a saved week)
//   DEFAULT_<from>       7 workout names, Sunday first (the default week)
//   GOALS_<from>         [{ name, rules }] (see validateGoals)
//   ALLOWLIST_<from>     package names
//   SKIP_<date>          true: that day was skipped with a freeze token (saveSkip)
//   GRANT_<date>         a whole number of extra freeze tokens usable on that date
//                        only, spent before earned ones. Set by hand; no endpoint.
// Other properties and malformed values are ignored.
var SETTING_KEY_RE_ = /^(WEEK|DEFAULT|GOALS|ALLOWLIST|SKIP|GRANT)_(\d{4}-\d{2}-\d{2})$/;

function settingKey(kind, iso) {
  return kind + '_' + iso;
}

// Returns { plan: { days, defaults }, goals: parseGoalEntries(...), allowlist: parseAllowlist(...) }.
function parseSettings(props) {
  var days = [], defaults = [], goals = [], allow = [], skips = {}, grants = {};
  Object.keys(props || {}).forEach(function (k) {
    var m = SETTING_KEY_RE_.exec(k);
    if (!m || !isIsoDate(m[2])) return;
    var v;
    try { v = JSON.parse(props[k]); } catch (e) { return; }
    if (m[1] === 'SKIP') {
      if (v === true) skips[m[2]] = true;
      return;
    }
    if (m[1] === 'GRANT') {
      if (typeof v === 'number' && v >= 1 && v === Math.floor(v)) grants[m[2]] = v;
      return;
    }
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
    allowlist: parseAllowlist(allow),
    tokens: { skips: skips, grants: grants }
  };
}

// ---- goals and streak ------------------------------------------------------

// A day's plan can combine workouts with "+" ("Upper + Skate"): it is done when
// every goal of every part is met. A goal shared by parts (same section or
// exercise) counts once, with the higher minimum. A part can carry its own
// window for that day: "Upper@07:00-10:00".
// Returns [{ name, window (null: the workout's own), bad (the window didn't parse) }].
function planParts(plan) {
  return String(plan == null ? '' : plan).split('+').map(function (p) { return p.trim(); })
    .filter(function (p) { return p; }).map(function (p) {
      var at = p.indexOf('@');
      if (at < 0) return { name: p, window: null, bad: false };
      var w = parseWindow(p.slice(at + 1).replace(/\s+/g, ''));
      return { name: p.slice(0, at).trim(), window: w, bad: !w };
    });
}

// The workout names of a day's plan: "Upper@07:00-10:00 + Skate" -> ["Upper", "Skate"].
function workoutParts(name) {
  return planParts(name).map(function (p) { return p.name; }).filter(function (p) { return p; });
}

// A day's gated sessions: [{ workout, window }], each part with its own window
// that day, else its workout's, else all day.
function sessionsFor_(goals, plan) {
  if (!isGymDay(plan)) return [];
  return planParts(plan).filter(function (p) { return p.name; }).map(function (p) {
    var g = goals[norm_(p.name)];
    return { workout: p.name, window: p.window || (g && g.window) || ALL_DAY };
  });
}

function sessionKey_(s) {
  return norm_(s.workout) + '@' + formatWindow(s.window);
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
// A gym day skipped with a token (tokenDays.skips) counts as frozen, today
// included, and unblocks it. Tokens granted for a day (tokenDays.grants) exist
// on that day only and are spent before earned ones.
//   goals: goal entries (parseGoalEntries), so each day is judged by the goals
//          in effect that day; a bare parseGoals map applies to every day.
//   nowMin: minutes since midnight today. Apps are gated only in the window of a
//           workout that isn't done; without nowMin, in every such window.
// Whatever the windows, the day counts done only when every workout is.
function computeStatus(schedule, goals, exercise, today, allowlist, tokenDays, nowMin) {
  var skips = (tokenDays && tokenDays.skips) || {}, grants = (tokenDays && tokenDays.grants) || {};
  var streak = 0, longest = 0, tokens = 0, completed = 0;
  var todayGrant = grants[today] || 0, skippedToday = false;
  var first = planStart_(plan_(schedule));
  var history = [];
  if (first !== null) {
    for (var d = first; d <= today; d = addDays(d, 1)) {
      var w = workoutFor(schedule, d);
      if (!isGymDay(w)) continue;
      var p = dayProgress(goalsOn_(goals, d), w, exercise[d]);
      var grant = grants[d] || 0;
      // Spends a token, the day's own grant first. False if there is none.
      var spend = function () {
        if (grant > 0) grant--;
        else if (tokens > 0) tokens--;
        else return false;
        return true;
      };
      var event;
      if (p.done) {
        streak++;
        completed++;
        if (completed % DAYS_PER_TOKEN === 0 && tokens < MAX_TOKENS) tokens++;
        event = 'done';
      } else if (skips[d] && spend()) {
        event = 'skipped';
        if (d === today) skippedToday = true;
      } else if (d === today) {
        event = 'pending';
      } else if (spend()) {
        event = 'frozen';
      } else {
        streak = 0;
        event = 'missed';
      }
      if (streak > longest) longest = streak;
      if (d === today) todayGrant = grant;
      history.push({ date: d, workout: w, event: event });
    }
  }
  var todayWorkout = workoutFor(schedule, today) || REST;
  var scheduled = isGymDay(todayWorkout);
  var tg = goalsOn_(goals, today);
  var tp = scheduled ? dayProgress(tg, todayWorkout, exercise[today]) : { done: false, progress: [] };
  var sessions = sessionsFor_(tg, todayWorkout).map(function (s) {
    var sp = dayProgress(tg, s.workout, exercise[today]);
    return { workout: s.workout, start: formatTime_(s.window.start), end: formatTime_(s.window.end),
      done: sp.done, goalsProgress: sp.progress, _w: s.window };
  });
  var gatedNow = sessions.some(function (s) {
    return !s.done && (nowMin == null || (s._w.start <= nowMin && nowMin < s._w.end));
  });
  sessions.forEach(function (s) { delete s._w; });
  var open = !scheduled || tp.done || skippedToday;
  return {
    today: today,
    todayScheduled: scheduled,
    workout: todayWorkout,
    done: scheduled ? tp.done : false,
    unknownWorkout: !!tp.unknownWorkout,
    // Today was skipped with a freeze token.
    skipped: skippedToday,
    // Gated right now (at nowMin). The app judges the time itself from sessions.
    blocked: !open && gatedNow,
    // Nothing is left to gate today: a rest day, done, or skipped.
    open: open,
    // Today's workouts, each with its window and goals, in plan order.
    sessions: sessions,
    // Today can still be skipped: blocked, with a token to spend.
    canSkip: scheduled && !tp.done && !skippedToday && tokens + todayGrant > 0,
    streak: streak,
    longest: longest,
    // Earned tokens, and tokens usable today only (granted for today).
    tokens: tokens,
    todayTokens: todayGrant,
    goalsProgress: tp.progress,
    week: weekView(schedule, weekStartOf(today), today).days,
    // While blocked, every app outside this list is blocked.
    allowlist: allowlistFor(allowlist || [], today).current,
    pendingAllowlist: allowlistFor(allowlist || [], today).pending,
    history: history.slice(-14)
  };
}

// ---- unlock code -----------------------------------------------------------

// What the unlock code for <today> signs: the tablet opens its gate for that
// day when given the code, without a connection.
function unlockMessage(today) {
  return 'open:' + today;
}

// The 6-digit unlock code from the HMAC-SHA256 of unlockMessage(today), keyed by
// the token, as bytes (signed, as Apps Script gives them, or not). Truncated as
// in HOTP (RFC 4226), so the app computes the same code.
function unlockCodeFrom(hmac) {
  var b = hmac.map(function (x) { return x & 0xff; });
  var o = b[b.length - 1] & 0x0f;
  var n = ((b[o] & 0x7f) * 16777216) + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3];
  var code = String(n % 1000000);
  while (code.length < 6) code = '0' + code;
  return code;
}

if (typeof module !== 'undefined') {
  module.exports = {
    addDays: addDays, serialToIso: serialToIso, dayOfWeek: dayOfWeek, weekStartOf: weekStartOf, isIsoDate: isIsoDate,
    exerciseKey: exerciseKey, setCount: setCount, workoutParts: workoutParts, workoutGoals: workoutGoals, parseExercise: parseExercise, parseGoals: parseGoals,
    parseSchedule: parseSchedule, parseAllowlist: parseAllowlist, allowlistFor: allowlistFor,
    parseDefaults: parseDefaults, defaultFor: defaultFor, validateDefault: validateDefault,
    validateAllowlist: validateAllowlist,
    parseGoalEntries: parseGoalEntries, goalsFor: goalsFor, goalsView: goalsView,
    validateGoals: validateGoals, parseSettings: parseSettings, settingKey: settingKey,
    isGymDay: isGymDay, workoutFor: workoutFor, weekView: weekView, validateSave: validateSave,
    dayProgress: dayProgress, computeStatus: computeStatus,
    parseWindow: parseWindow, formatWindow: formatWindow, planParts: planParts,
    unlockMessage: unlockMessage, unlockCodeFrom: unlockCodeFrom, LOCK_MINUTES: LOCK_MINUTES,
    MIN_GYM_DAYS_PER_WEEK: MIN_GYM_DAYS_PER_WEEK, MAX_TOKENS: MAX_TOKENS
  };
}
