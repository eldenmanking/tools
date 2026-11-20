// A minimal in-memory Apps Script runtime: enough of the Sheets advanced service,
// Utilities, PropertiesService, ContentService, LockService and Logger to run
// Code.js and Core.js unchanged in Node. Tabs are 2D arrays; date cells are
// serial day numbers, as the Sheets API returns them unformatted. The fake
// Sheets service only reads, so a write from Code.js fails the test.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');

// Apps Script's limit on one property value.
const MAX_PROPERTY_BYTES = 9 * 1024;
const SHEET_ID_FOR_TESTS = 'sheet-id';

const isBlank = (v) => v === '' || v === undefined || v === null;

/** A tab's values as values.get returns them: trailing blank cells and rows dropped. */
function apiValues(data) {
  const rows = data.map((r) => {
    const row = (r || []).map((v) => (isBlank(v) ? '' : v));
    while (row.length && row[row.length - 1] === '') row.pop();
    return row;
  });
  while (rows.length && !rows[rows.length - 1].length) rows.pop();
  return rows;
}

/**
 * Load Code.js and Core.js into a fresh context.
 *   sheets: { tab title: 2D array }; timeZone: the spreadsheet's IANA zone; props: script properties.
 *   The spreadsheet's id is SHEET_ID_FOR_TESTS.
 * Returns { ctx, ss, props, get(params), post(params, body), page(params) } where get/post return
 * parsed JSON, page the phone page's HTML, and ss.sheets holds the tabs.
 */
function loadScript({ sheets = {}, timeZone = 'America/New_York', props = {} } = {}) {
  const ss = { sheets: Object.fromEntries(Object.entries(sheets).map(([k, v]) => [k, v.map((r) => (r || []).slice())])) };
  const book = (id) => {
    if (id !== SHEET_ID_FOR_TESTS) throw new Error('Requested entity was not found.');
    return ss;
  };
  const store = { ...props };
  let uuids = 0;
  const ctx = {
    console,
    Sheets: {
      Spreadsheets: {
        get(id, opts) {
          if (opts.fields !== 'properties.timeZone,sheets.properties.title') throw new Error('unexpected fields ' + opts.fields);
          return {
            properties: { timeZone },
            sheets: Object.keys(book(id).sheets).map((title) => ({ properties: { title } })),
          };
        },
        Values: {
          get(id, range, opts) {
            if (opts.valueRenderOption !== 'UNFORMATTED_VALUE' || opts.dateTimeRenderOption !== 'SERIAL_NUMBER') {
              throw new Error('expected unformatted values with serial dates');
            }
            const title = range.replace(/^'(.*)'$/, '$1').replace(/''/g, "'");
            const data = book(id).sheets[title];
            if (!data) throw new Error('Unable to parse range: ' + range);
            const values = apiValues(data);
            return values.length ? { range, values } : { range };
          },
        },
      },
    },
    Utilities: {
      formatDate(date, tz, fmt) {
        if (fmt === 'HH:mm') {
          return new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date);
        }
        if (fmt !== 'yyyy-MM-dd') throw new Error('unsupported format ' + fmt);
        return new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
      },
      // Signed bytes, as Apps Script returns them.
      computeHmacSha256Signature: (value, key) =>
        Array.from(crypto.createHmac('sha256', key).update(value).digest()).map((b) => (b > 127 ? b - 256 : b)),
      getUuid: () => 'test-token-' + uuids++,
    },
    PropertiesService: {
      getScriptProperties: () => ({
        getProperty: (k) => (k in store ? store[k] : null),
        getProperties: () => ({ ...store }),
        setProperty: (k, v) => {
          if (Buffer.byteLength(String(v)) > MAX_PROPERTY_BYTES) throw new Error('property ' + k + ' is too large');
          store[k] = String(v);
        },
      }),
    },
    ContentService: {
      MimeType: { JSON: 'application/json' },
      createTextOutput: (text) => ({ text, setMimeType() { return this; } }),
    },
    HtmlService: {
      createHtmlOutput: (html) => ({ html, setTitle() { return this; }, addMetaTag() { return this; } }),
    },
    ScriptApp: { getService: () => ({ getUrl: () => 'https://script.example/exec' }) },
    LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
    Logger: { log() {} },
  };
  vm.createContext(ctx);
  for (const f of ['Core.js', 'Code.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '..', f), 'utf8'), ctx, { filename: f });
  }
  return {
    ctx,
    ss,
    props: store,
    get: (parameter) => JSON.parse(ctx.doGet({ parameter }).text),
    page: (parameter) => ctx.doGet({ parameter: { ...parameter, action: 'page' } }).html,
    post: (parameter, body) => JSON.parse(ctx.doPost({ parameter, postData: { contents: JSON.stringify(body) } }).text),
  };
}

/**
 * A date cell's unformatted value: the serial day number of <iso> (days since 1899-12-30).
 * A date entered with a time of day has a fraction: pass it as <dayFraction>.
 */
function dateCell(iso, dayFraction = 0) {
  return (Date.parse(iso + 'T00:00:00Z') - Date.parse('1899-12-30T00:00:00Z')) / 86400000 + dayFraction;
}

module.exports = { loadScript, dateCell, SHEET_ID_FOR_TESTS };
