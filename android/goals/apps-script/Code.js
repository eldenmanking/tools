// Web app for the gym gate. A standalone script that only reads the gym log
// spreadsheet: appsscript.json asks for spreadsheets.readonly, so it can't
// change the sheet. It reads through the Sheets API (the "Sheets" advanced
// service), since SpreadsheetApp.openById needs the full spreadsheets scope. The gate's settings live in script properties (see
// parseSettings in Core.js); all logic lives in Core.js.
//
//   GET  <url>?token=T                    -> status JSON (see computeStatus)
//   GET  <url>?token=T&action=schedule    -> this week and next with locks, the
//                                            default week, goals and allowlist
//   GET  <url>?token=T&action=page        -> a page for a phone's browser: today's
//                                            status, the unlock code once nothing
//                                            is left to gate today, and skipping
//                                            today with a token (&skip=ask, then
//                                            &skip=<today>)
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
// "Today" and the time of day are always the server's, in the spreadsheet's time
// zone. Only when ALLOW_FAKE_TODAY is "true" do ?today=yyyy-mm-dd and ?now=HH:MM
// override them (for testing); leave it unset otherwise.

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
  if (e && e.parameter && e.parameter.action === 'page') return page_(e);
  return handle_(e, function (data, today, nowMin) {
    var action = (e.parameter.action || 'status');
    if (action === 'status') {
      return computeStatus(data.plan, data.goals, data.exercise, today, data.allowlist, data.tokens, nowMin);
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
    return handle_(e, function (data, today, nowMin) {
      var props = PropertiesService.getScriptProperties();
      var body = JSON.parse((e.postData && e.postData.contents) || '{}');
      var tomorrow = addDays(today, 1);
      if (body.action === 'saveSchedule') {
        var v = validateSave(data.plan, data.goals, body.weekStart, body.days, today, nowMin);
        if (!v.ok) return { ok: false, errors: v.errors };
        save_(props, 'WEEK', body.weekStart, v.rows.map(function (r) { return r[1]; }));
        return { ok: true, week: weekView(settings_().plan, body.weekStart, today) };
      }
      if (body.action === 'saveDefault') {
        var dv = validateDefault(goalsFor(data.goals, tomorrow).current, body.days, today, nowMin);
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
      if (body.action === 'skipToday') return skipToday_(props, data, today);
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

// Spends a freeze token on today, if it is still gated and one is left.
function skipToday_(props, data, today) {
  var st = computeStatus(data.plan, data.goals, data.exercise, today, data.allowlist, data.tokens);
  if (!st.canSkip) {
    return { ok: false, errors: [st.skipped ? 'today is already skipped'
      : st.open ? 'nothing is left to gate today' : 'no freeze token to spend'] };
  }
  save_(props, 'SKIP', today, true);
  return { ok: true, effectiveFrom: today };
}

function authorized_(e) {
  var token = PropertiesService.getScriptProperties().getProperty('TOKEN');
  return !!token && !!e && !!e.parameter && e.parameter.token === token;
}

// { today, nowMin }: the server's date and minutes since midnight, or the
// test overrides (see ALLOW_FAKE_TODAY).
function clock_(e) {
  var props = PropertiesService.getScriptProperties();
  var now = new Date();
  var today = Utilities.formatDate(now, tz_(), 'yyyy-MM-dd');
  var hm = Utilities.formatDate(now, tz_(), 'HH:mm').split(':');
  var nowMin = +hm[0] * 60 + +hm[1];
  if (props.getProperty('ALLOW_FAKE_TODAY') === 'true') {
    if (isIsoDate(e.parameter.today)) {
      today = e.parameter.today;
      nowMin = 0; // a faked day starts at midnight unless &now says otherwise
    }
    var m = /^(\d{2}):(\d{2})$/.exec(e.parameter.now || '');
    if (m) nowMin = +m[1] * 60 + +m[2];
  }
  return { today: today, nowMin: nowMin };
}

function handle_(e, fn) {
  var out;
  if (!authorized_(e)) {
    out = { error: 'unauthorized' };
  } else {
    try {
      var c = clock_(e);
      out = fn(readData_(), c.today, c.nowMin);
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

// ---- the phone page --------------------------------------------------------

var WEEKDAY_NAMES_ = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
var MONTH_NAMES_ = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function esc_(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// The page a phone opens (action=page): status, the unlock code and skipping.
// It changes nothing unless &skip names today's date, which only its own
// confirm link does.
function page_(e) {
  if (!authorized_(e)) return HtmlService.createHtmlOutput('<p>Unauthorized.</p>');
  var body;
  try {
    var c = clock_(e), props = PropertiesService.getScriptProperties();
    var note = '';
    if (e.parameter.skip === c.today) {
      var lock = LockService.getScriptLock();
      lock.waitLock(10000);
      try {
        var r = skipToday_(props, readData_(), c.today);
        note = r.ok ? 'Today is skipped with a token.' : 'Not skipped: ' + r.errors.join(' ');
      } finally {
        lock.releaseLock();
      }
    }
    var data = readData_();
    var st = computeStatus(data.plan, data.goals, data.exercise, c.today, data.allowlist, data.tokens, c.nowMin);
    var link = ScriptApp.getService().getUrl() + '?token=' + encodeURIComponent(e.parameter.token) + '&action=page';
    var fake = props.getProperty('ALLOW_FAKE_TODAY') !== 'true' ? '' : ['today', 'now'].map(function (k) {
      return e.parameter[k] ? '&' + k + '=' + encodeURIComponent(e.parameter[k]) : '';
    }).join('');
    body = pageBody_(st, link + fake, e.parameter.skip === 'ask', note,
      st.open ? unlockCodeFrom(Utilities.computeHmacSha256Signature(unlockMessage(c.today), props.getProperty('TOKEN'))) : null);
  } catch (err) {
    body = '<p>Error: ' + esc_(err) + '</p>';
  }
  return HtmlService.createHtmlOutput('<!doctype html><style>' +
    'body{font:18px/1.4 -apple-system,sans-serif;margin:24px;color:#000;background:#fff}' +
    'h1{font-size:28px;margin:0 0 4px}.day{text-transform:uppercase;letter-spacing:.05em;font-size:14px}' +
    '.code{font:700 44px ui-monospace,monospace;letter-spacing:.15em;margin:4px 0}' +
    'a.b{display:block;border:2px solid #000;padding:12px;margin:12px 0;text-align:center;color:#000;text-decoration:none;font-weight:700}' +
    'ul{padding-left:20px}.done{color:#555}</style>' + body)
    .setTitle('Gym gate').addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

function pageBody_(st, link, asking, note, code) {
  var p = st.today.split('-');
  var date = WEEKDAY_NAMES_[dayOfWeek(st.today)] + ', ' + MONTH_NAMES_[+p[1] - 1] + ' ' + (+p[2]);
  var title = !st.todayScheduled ? 'Rest day.' : st.done ? 'Done for today.' : st.skipped ? 'Skipped.' : 'Not done yet.';
  var h = '<div class="day">' + esc_(date) + '</div><h1>' + title + '</h1>';
  if (note) h += '<p><b>' + esc_(note) + '</b></p>';
  if (st.sessions.length) {
    h += '<ul>' + st.sessions.map(function (s) {
      var when = s.start === '00:00' && s.end === '24:00' ? 'all day' : s.start + '–' + s.end;
      var goals = s.goalsProgress.map(function (g) {
        return esc_(g.name) + (g.type === 'exercise' ? (g.met ? ' ✓' : '') : ' ' + g.logged + '/' + g.min);
      }).join(', ');
      return '<li class="' + (s.done ? 'done' : '') + '"><b>' + esc_(s.workout) + '</b> · ' + when +
        (s.done ? ' · done' : '') + '<br>' + goals + '</li>';
    }).join('') + '</ul>';
  }
  h += '<p>Streak ' + st.streak + ' · Tokens ' + st.tokens + ' of ' + MAX_TOKENS +
    (st.todayTokens ? ' (+' + st.todayTokens + ' today)' : '') + '</p>';
  if (code) {
    h += '<p>Unlock code for the tablet when it can\'t connect:</p><div class="code">' +
      code.slice(0, 3) + ' ' + code.slice(3) + '</div>';
  }
  if (st.canSkip) {
    h += asking
      ? '<a class="b" target="_top" href="' + esc_(link + '&skip=' + st.today) + '">Tap to spend a token on today</a>' +
        '<a class="b" target="_top" href="' + esc_(link) + '">Cancel</a>'
      : '<a class="b" target="_top" href="' + esc_(link + '&skip=ask') + '">Skip today with a token</a>';
  }
  return h + '<a class="b" target="_top" href="' + esc_(link) + '">Refresh</a>';
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
