// Web app for the gym gate. Bound to the gym log spreadsheet; all logic lives
// in Core.js, this file only reads and writes the sheet.
//
//   GET  <url>?token=T                    -> status JSON (see computeStatus)
//   GET  <url>?token=T&action=schedule    -> this week and next, with locks
//   POST <url>?token=T  body {"action":"saveSchedule","weekStart":"2026-11-22",
//                             "days":["Rest","Upper","Lower",...]}  (Sunday first)
//   POST <url>?token=T  body {"action":"saveAllowlist","packages":["com.a.b",...]}
//                             (takes effect tomorrow)
//   POST <url>?token=T  body {"action":"saveDefault","days":["Rest","Upper",...]}
//                             (Sunday first; applies from tomorrow to unsaved weeks)
//
// "Today" is always the server's date in the spreadsheet's time zone. Only when
// the script property ALLOW_FAKE_TODAY is "true" does ?today=yyyy-mm-dd
// override it (for emulator testing); leave it unset otherwise.

var EXERCISE_TAB = 'Exercise';
var GOALS_TAB = 'Gate Goals';
var SCHEDULE_TAB = 'Gate Schedule';
var ALLOWLIST_TAB = 'Gate Allowlist';
var DEFAULT_TAB = 'Gate Default';

// Run once from the editor: creates the gate tabs if missing and a secret
// token, then logs the token (View > Logs).
function setup() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss.getSheetByName(GOALS_TAB)) {
    var g = ss.insertSheet(GOALS_TAB);
    g.getRange(1, 1, 1, 4).setValues([['Workout', 'Type (Section/Exercise)', 'Name', 'Min exercises']]);
    // Examples: a Section row names an Exercise tab section (Push, Pull, Legs,
    // Core, ...); an Exercise row names one exercise without its [unit] suffix.
    // A workout is done when all its rows are met. Edit to taste.
    g.getRange(2, 1, 6, 4).setValues([
      ['Upper', 'Section', 'Push', 2], ['Upper', 'Section', 'Pull', 2], ['Upper', 'Section', 'Core', 1],
      ['Lower', 'Section', 'Legs', 3], ['Lower', 'Section', 'Core', 1],
      ['Skate', 'Exercise', 'Figure skating', '']
    ]);
    g.setFrozenRows(1);
  }
  if (!ss.getSheetByName(SCHEDULE_TAB)) {
    var s = ss.insertSheet(SCHEDULE_TAB);
    s.getRange(1, 1, 1, 2).setValues([['Date (yyyy-mm-dd)', 'Workout']]);
    s.getRange('A:A').setNumberFormat('@'); // keep dates as text, no time zone shifts
    s.setFrozenRows(1);
  }
  if (!ss.getSheetByName(DEFAULT_TAB)) {
    var d = ss.insertSheet(DEFAULT_TAB);
    d.getRange(1, 1, 1, 8).setValues([['Effective from (yyyy-mm-dd)', 'Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']]);
    d.getRange('A:A').setNumberFormat('@');
    d.setFrozenRows(1);
  }
  if (!ss.getSheetByName(ALLOWLIST_TAB)) {
    var a = ss.insertSheet(ALLOWLIST_TAB);
    a.getRange(1, 1, 1, 2).setValues([['Effective from (yyyy-mm-dd)', 'Allowed packages (comma-separated)']]);
    a.getRange('A:A').setNumberFormat('@');
    a.setFrozenRows(1);
  }
  var props = PropertiesService.getScriptProperties();
  if (!props.getProperty('TOKEN')) props.setProperty('TOKEN', Utilities.getUuid());
  Logger.log('TOKEN=' + props.getProperty('TOKEN'));
}

function doGet(e) {
  return handle_(e, function (data, today) {
    var action = (e.parameter.action || 'status');
    if (action === 'status') {
      return computeStatus(data.plan, data.goals, data.exercise, today, data.allowlist);
    }
    if (action === 'schedule') {
      var ws = weekStartOf(today);
      return {
        today: today,
        workouts: Object.keys(data.goals).map(function (k) { return data.goals[k].name; }),
        minGymDays: MIN_GYM_DAYS_PER_WEEK,
        weeks: [weekView(data.plan, ws, today), weekView(data.plan, addDays(ws, 7), today)],
        default: defaultFor(data.plan.defaults, today)
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
      var body = JSON.parse((e.postData && e.postData.contents) || '{}');
      if (body.action === 'saveSchedule') {
        var v = validateSave(data.plan, data.goals, body.weekStart, body.days, today);
        if (!v.ok) return { ok: false, errors: v.errors };
        writeDatedRows_(SCHEDULE_TAB, v.rows);
        return { ok: true, week: weekView(readData_().plan, body.weekStart, today) };
      }
      if (body.action === 'saveDefault') {
        var dv = validateDefault(data.goals, body.days, today);
        if (!dv.ok) return { ok: false, errors: dv.errors };
        writeDatedRows_(DEFAULT_TAB, [dv.row]);
        return { ok: true, effectiveFrom: dv.row[0], default: defaultFor(readData_().plan.defaults, today) };
      }
      if (body.action === 'saveAllowlist') {
        var a = validateAllowlist(body.packages, today);
        if (!a.ok) return { ok: false, errors: a.errors };
        writeDatedRows_(ALLOWLIST_TAB, [a.row]);
        return { ok: true, effectiveFrom: a.row[0], allowlist: allowlistFor(readData_().allowlist, today) };
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

function tz_() {
  return SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
}

function todayIso_() {
  return Utilities.formatDate(new Date(), tz_(), 'yyyy-MM-dd');
}

function cellIso_(v, tz) {
  if (v instanceof Date) return Utilities.formatDate(v, tz, 'yyyy-MM-dd');
  var s = String(v || '').trim();
  return isIsoDate(s) ? s : null;
}

function readData_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var tz = tz_();
  var ex = ss.getSheetByName(EXERCISE_TAB).getDataRange().getValues();
  var header = (ex[1] || []).map(function (v) { return cellIso_(v, tz); });
  return {
    exercise: parseExercise(header, ex.slice(2)),
    goals: parseGoals(rowsAfterHeader_(ss.getSheetByName(GOALS_TAB))),
    plan: {
      days: parseSchedule(datedRows_(ss.getSheetByName(SCHEDULE_TAB), tz)),
      defaults: parseDefaults(datedRows_(ss.getSheetByName(DEFAULT_TAB), tz))
    },
    allowlist: parseAllowlist(datedRows_(ss.getSheetByName(ALLOWLIST_TAB), tz))
  };
}

// Rows after the header with column A as an ISO date ('' if it isn't one).
function datedRows_(sheet, tz) {
  return rowsAfterHeader_(sheet).map(function (r) { return [cellIso_(r[0], tz) || ''].concat(r.slice(1)); });
}

function rowsAfterHeader_(sheet) {
  if (!sheet) throw new Error('missing tab; run setup() first');
  var n = sheet.getLastRow();
  return n < 2 ? [] : sheet.getRange(2, 1, n - 1, sheet.getLastColumn()).getValues();
}

// Replace the rows of tab <name> whose column A holds one of these dates.
function writeDatedRows_(name, rows) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(name);
  var dates = {};
  rows.forEach(function (r) { dates[r[0]] = true; });
  var tz = tz_();
  var last = sheet.getLastRow();
  var col = last < 2 ? [] : sheet.getRange(2, 1, last - 1, 1).getValues();
  for (var i = col.length - 1; i >= 0; i--) {
    if (dates[cellIso_(col[i][0], tz)]) sheet.deleteRow(i + 2);
  }
  var start = sheet.getLastRow() + 1;
  sheet.getRange(start, 1, rows.length, 1).setNumberFormat('@');
  sheet.getRange(start, 1, rows.length, rows[0].length).setValues(rows);
}
