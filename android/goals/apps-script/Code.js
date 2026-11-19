// Web app for the gym gate. A standalone script that only reads the gym log
// spreadsheet: appsscript.json asks for spreadsheets.readonly, so it can't
// change the sheet. It reads through the Sheets API (the "Sheets" advanced
// service), since SpreadsheetApp.openById needs the full spreadsheets scope. The gate's settings live in script properties (see
// parseSettings in Core.js); all logic lives in Core.js.
//
//   GET  <url>?token=T                    -> status JSON (see computeStatus)
//   GET  <url>?token=T&action=schedule    -> this week and next with locks, the
//                                            default week, goals and allowlist
//   POST <url>?token=T  body {"action":"saveSchedule","weekStart":"2026-11-22",
//                             "days":["Rest","Upper","Lower",...]}  (Sunday first)
//   POST <url>?token=T  body {"action":"saveDefault","days":["Rest","Upper",...]}
//                             (Sunday first; applies from tomorrow to unsaved weeks)
//   POST <url>?token=T  body {"action":"saveGoals","workouts":[{"name":"Upper",
//                             "rules":[{"type":"section","name":"Push","min":2},...]}]}
//                             (takes effect tomorrow)
//   POST <url>?token=T  body {"action":"skipToday"}  (spends a freeze token to unblock today)
//   POST <url>?token=T  body {"action":"saveAllowlist","packages":["com.a.b",...]}
//                             (takes effect tomorrow)
//
// Script properties set by hand: SHEET_ID (the gym log spreadsheet) and, for
// testing only, ALLOW_FAKE_TODAY. setup() creates TOKEN.
//
// "Today" is always the server's date in the spreadsheet's time zone. Only when
// ALLOW_FAKE_TODAY is "true" does ?today=yyyy-mm-dd override it (for emulator
// testing); leave it unset otherwise.

var EXERCISE_TAB = 'Exercise';

// Goals saved by setup() until the app saves its own.
var SEED_GOALS_ = [
  { name: 'Upper', rules: [
    { type: 'section', name: 'Push', min: 2 },
    { type: 'section', name: 'Pull', min: 2 },
    { type: 'section', name: 'Core', min: 1 }] },
  { name: 'Lower', rules: [
    { type: 'section', name: 'Legs', min: 2 },
    { type: 'section', name: 'Core', min: 1 }] },
  { name: 'Skate', rules: [{ type: 'exercise', name: 'Figure skating', min: 1 }] }
];

// Run once from the editor after setting SHEET_ID: checks the sheet, creates
// a secret token and the first goals, then logs the token (View > Logs).
function setup() {
  var props = PropertiesService.getScriptProperties();
  if (book_().tabs.indexOf(EXERCISE_TAB) < 0) throw new Error('the sheet has no ' + EXERCISE_TAB + ' tab');
  if (!props.getProperty('TOKEN')) props.setProperty('TOKEN', Utilities.getUuid());
  if (!parseSettings(props.getProperties()).goals.length) {
    props.setProperty(settingKey('GOALS', todayIso_()), JSON.stringify(SEED_GOALS_));
  }
  Logger.log('TOKEN=' + props.getProperty('TOKEN'));
}

function doGet(e) {
  return handle_(e, function (data, today) {
    var action = (e.parameter.action || 'status');
    if (action === 'status') {
      return computeStatus(data.plan, data.goals, data.exercise, today, data.allowlist, data.tokens);
    }
    if (action === 'schedule') {
      var ws = weekStartOf(today);
      var goals = goalsFor(data.goals, today);
      return {
        today: today,
        // Workouts a day from tomorrow on can use.
        workouts: goalsView(goalsFor(data.goals, addDays(today, 1)).current).map(function (w) { return w.name; }),
        minGymDays: MIN_GYM_DAYS_PER_WEEK,
        weeks: [weekView(data.plan, ws, today), weekView(data.plan, addDays(ws, 7), today)],
        default: defaultFor(data.plan.defaults, today),
        goals: {
          current: goalsView(goals.current),
          pending: goals.pending && { from: goals.pending.from, workouts: goalsView(goals.pending.goals) }
        },
        allowlist: allowlistFor(data.allowlist, today)
      };
    }
    return { error: 'unknown action ' + action };
  });
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    return handle_(e, function (data, today) {
      var props = PropertiesService.getScriptProperties();
      var body = JSON.parse((e.postData && e.postData.contents) || '{}');
      var tomorrow = addDays(today, 1);
      if (body.action === 'saveSchedule') {
        var goals = goalsFor(data.goals, tomorrow).current;
        var v = validateSave(data.plan, goals, body.weekStart, body.days, today);
        if (!v.ok) return { ok: false, errors: v.errors };
        save_(props, 'WEEK', body.weekStart, v.rows.map(function (r) { return r[1]; }));
        return { ok: true, week: weekView(settings_().plan, body.weekStart, today) };
      }
      if (body.action === 'saveDefault') {
        var dv = validateDefault(goalsFor(data.goals, tomorrow).current, body.days, today);
        if (!dv.ok) return { ok: false, errors: dv.errors };
        save_(props, 'DEFAULT', dv.row[0], dv.row.slice(1));
        return { ok: true, effectiveFrom: dv.row[0], default: defaultFor(settings_().plan.defaults, today) };
      }
      if (body.action === 'saveGoals') {
        var gv = validateGoals(data.plan, body.workouts, today);
        if (!gv.ok) return { ok: false, errors: gv.errors };
        save_(props, 'GOALS', gv.from, gv.workouts);
        return { ok: true, effectiveFrom: gv.from, workouts: goalsView(goalsFor(settings_().goals, gv.from).current) };
      }
      if (body.action === 'skipToday') {
        var st = computeStatus(data.plan, data.goals, data.exercise, today, data.allowlist, data.tokens);
        if (!st.canSkip) {
          return { ok: false, errors: [st.skipped ? 'today is already skipped'
            : !st.blocked ? 'today is not blocked' : 'no freeze token to spend'] };
        }
        save_(props, 'SKIP', today, true);
        return { ok: true, effectiveFrom: today };
      }
      if (body.action === 'saveAllowlist') {
        var a = validateAllowlist(body.packages, today);
        if (!a.ok) return { ok: false, errors: a.errors };
        save_(props, 'ALLOWLIST', a.row[0], a.row[1] ? a.row[1].split(',') : []);
        return { ok: true, effectiveFrom: a.row[0], allowlist: allowlistFor(settings_().allowlist, today) };
      }
      return { error: 'unknown action ' + body.action };
    });
  } finally {
    lock.releaseLock();
  }
}

function handle_(e, fn) {
  var out;
  var props = PropertiesService.getScriptProperties();
  var token = props.getProperty('TOKEN');
  if (!token || !e || !e.parameter || e.parameter.token !== token) {
    out = { error: 'unauthorized' };
  } else {
    try {
      var today = todayIso_();
      if (props.getProperty('ALLOW_FAKE_TODAY') === 'true' && isIsoDate(e.parameter.today)) {
        today = e.parameter.today;
      }
      out = fn(readData_(), today);
      out.serverTime = new Date().toISOString();
      out.timeZone = tz_();
    } catch (err) {
      out = { error: String(err) };
    }
  }
  return ContentService.createTextOutput(JSON.stringify(out)).setMimeType(ContentService.MimeType.JSON);
}

var book_cache_ = null;

// The gym log spreadsheet named by the SHEET_ID script property:
// { id, timeZone, tabs: [tab titles] }.
function book_() {
  if (!book_cache_) {
    var id = PropertiesService.getScriptProperties().getProperty('SHEET_ID');
    if (!id) throw new Error('set the SHEET_ID script property first');
    var meta = Sheets.Spreadsheets.get(id, { fields: 'properties.timeZone,sheets.properties.title' });
    book_cache_ = {
      id: id,
      timeZone: meta.properties.timeZone,
      tabs: (meta.sheets || []).map(function (s) { return s.properties.title; })
    };
  }
  return book_cache_;
}

function tz_() {
  return book_().timeZone;
}

function todayIso_() {
  return Utilities.formatDate(new Date(), tz_(), 'yyyy-MM-dd');
}

// A date cell read unformatted is a serial day number; text dates also count.
function cellIso_(v) {
  if (typeof v === 'number') return serialToIso(v);
  var s = String(v == null ? '' : v).trim();
  return isIsoDate(s) ? s : null;
}

function settings_() {
  return parseSettings(PropertiesService.getScriptProperties().getProperties());
}

// Store one dated setting, replacing any saved for the same date.
function save_(props, kind, iso, value) {
  props.setProperty(settingKey(kind, iso), JSON.stringify(value));
}

function readData_() {
  var book = book_();
  if (book.tabs.indexOf(EXERCISE_TAB) < 0) throw new Error('the sheet has no ' + EXERCISE_TAB + ' tab');
  // Unformatted, so dates come as serial numbers (no time zone to get wrong). Rows are
  // cut after their last value.
  var ex = Sheets.Spreadsheets.Values.get(book.id, "'" + EXERCISE_TAB + "'",
    { valueRenderOption: 'UNFORMATTED_VALUE', dateTimeRenderOption: 'SERIAL_NUMBER' }).values || [];
  var header = (ex[1] || []).map(cellIso_);
  var s = settings_();
  return { exercise: parseExercise(header, ex.slice(2)), plan: s.plan, goals: s.goals, allowlist: s.allowlist, tokens: s.tokens };
}
