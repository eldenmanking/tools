// Web app for the gym gate. Bound to the gym log spreadsheet; all logic lives
// in Core.js, this file only reads and writes the sheet.
//
//   GET  <url>?token=T                    -> status JSON (see computeStatus)
//   GET  <url>?token=T&action=schedule    -> this week and next, with locks
//   POST <url>?token=T  body {"action":"saveSchedule","weekStart":"2026-11-22",
//                             "days":["Rest","Push","Pull",...]}  (Sunday first)
//
// "Today" is always the server's date in the spreadsheet's time zone. Only when
// the script property ALLOW_FAKE_TODAY is "true" does ?today=yyyy-mm-dd
// override it (for emulator testing); leave it unset otherwise.

var EXERCISE_TAB = 'Exercise';
var GOALS_TAB = 'Gate Goals';
var SCHEDULE_TAB = 'Gate Schedule';

// Run once from the editor: creates the two gate tabs if missing and a secret
// token, then logs the token (View > Logs).
function setup() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss.getSheetByName(GOALS_TAB)) {
    var g = ss.insertSheet(GOALS_TAB);
    g.getRange(1, 1, 1, 3).setValues([['Workout', 'Section', 'Min exercises']]);
    // Examples matching the Exercise tab's sections (Push, Pull, Legs, Core,
    // Cardio, Free, Stretch); edit to taste. All rows of a workout must be met.
    g.getRange(2, 1, 5, 3).setValues([
      ['Upper', 'Push', 2], ['Upper', 'Pull', 2], ['Upper', 'Core', 1],
      ['Lower', 'Legs', 3], ['Lower', 'Pull', 1]
    ]);
    g.setFrozenRows(1);
  }
  if (!ss.getSheetByName(SCHEDULE_TAB)) {
    var s = ss.insertSheet(SCHEDULE_TAB);
    s.getRange(1, 1, 1, 2).setValues([['Date (yyyy-mm-dd)', 'Workout']]);
    s.getRange('A:A').setNumberFormat('@'); // keep dates as text, no time zone shifts
    s.setFrozenRows(1);
  }
  var props = PropertiesService.getScriptProperties();
  if (!props.getProperty('TOKEN')) props.setProperty('TOKEN', Utilities.getUuid());
  Logger.log('TOKEN=' + props.getProperty('TOKEN'));
}

function doGet(e) {
  return handle_(e, function (data, today) {
    var action = (e.parameter.action || 'status');
    if (action === 'status') {
      return computeStatus(data.schedule, data.goals, data.exercise, today);
    }
    if (action === 'schedule') {
      var ws = weekStartOf(today);
      return {
        today: today,
        workouts: Object.keys(data.goals).map(function (k) { return data.goals[k].name; }),
        minGymDays: MIN_GYM_DAYS_PER_WEEK,
        weeks: [weekView(data.schedule, ws, today), weekView(data.schedule, addDays(ws, 7), today)]
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
      if (body.action !== 'saveSchedule') return { error: 'unknown action ' + body.action };
      var v = validateSave(data.schedule, data.goals, body.weekStart, body.days, today);
      if (!v.ok) return { ok: false, errors: v.errors };
      writeWeek_(v.rows);
      return { ok: true, week: weekView(readData_().schedule, body.weekStart, today) };
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
    schedule: parseSchedule(rowsAfterHeader_(ss.getSheetByName(SCHEDULE_TAB)).map(function (r) {
      return [cellIso_(r[0], tz) || '', r[1]];
    }))
  };
}

function rowsAfterHeader_(sheet) {
  if (!sheet) throw new Error('missing tab; run setup() first');
  var n = sheet.getLastRow();
  return n < 2 ? [] : sheet.getRange(2, 1, n - 1, sheet.getLastColumn()).getValues();
}

// Replace the stored rows for these dates.
function writeWeek_(rows) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SCHEDULE_TAB);
  var dates = {};
  rows.forEach(function (r) { dates[r[0]] = true; });
  var tz = tz_();
  for (var i = sheet.getLastRow(); i >= 2; i--) {
    if (dates[cellIso_(sheet.getRange(i, 1).getValue(), tz)]) sheet.deleteRow(i);
  }
  var start = sheet.getLastRow() + 1;
  sheet.getRange(start, 1, rows.length, 1).setNumberFormat('@');
  sheet.getRange(start, 1, rows.length, 2).setValues(rows);
}
