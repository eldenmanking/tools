// A minimal in-memory Apps Script runtime: enough of SpreadsheetApp, Utilities,
// PropertiesService, ContentService, LockService and Logger to run Code.js and
// Core.js unchanged in Node. Sheets are 2D arrays; dates are JS Date objects, as
// getValues() returns them. The spreadsheet is read-only: it has no write
// methods, so a write from Code.js fails the test.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class Range {
  constructor(sheet, row, col, rows, cols) {
    Object.assign(this, { sheet, row, col, rows, cols });
  }
  getValues() {
    const out = [];
    for (let r = 0; r < this.rows; r++) {
      const src = this.sheet.data[this.row - 1 + r] || [];
      const row = [];
      for (let c = 0; c < this.cols; c++) {
        const v = src[this.col - 1 + c];
        row.push(v === undefined || v === null ? '' : v);
      }
      out.push(row);
    }
    return out;
  }
  getValue() {
    return this.getValues()[0][0];
  }
}

class Sheet {
  constructor(name, data = []) {
    this.name = name;
    this.data = data.map((r) => r.slice());
  }
  getName() { return this.name; }
  getLastRow() {
    for (let r = this.data.length; r > 0; r--) {
      if ((this.data[r - 1] || []).some((v) => v !== '' && v !== undefined && v !== null)) return r;
    }
    return 0;
  }
  getLastColumn() {
    return this.data.reduce((m, r) => Math.max(m, (r || []).length), 0);
  }
  getDataRange() {
    return new Range(this, 1, 1, Math.max(this.getLastRow(), 1), Math.max(this.getLastColumn(), 1));
  }
  getRange(a, b, c, d) {
    return new Range(this, a, b, c || 1, d || 1);
  }
}

// Apps Script's limit on one property value.
const MAX_PROPERTY_BYTES = 9 * 1024;
const SHEET_ID_FOR_TESTS = 'sheet-id';

/**
 * Load Code.js and Core.js into a fresh context.
 *   sheets: { name: 2D array }; timeZone: the spreadsheet's IANA zone; props: script properties.
 *   The spreadsheet opens by the id SHEET_ID_FOR_TESTS.
 * Returns { ctx, ss, props, get(params), post(params, body) } where get/post return parsed JSON.
 */
function loadScript({ sheets = {}, timeZone = 'America/New_York', props = {} } = {}) {
  const book = {};
  const ss = {
    getSheetByName: (n) => book[n] || null,
    getSpreadsheetTimeZone: () => timeZone,
    sheets: book,
  };
  const store = { ...props };
  const ctx = {
    console,
    SpreadsheetApp: {
      openById: (id) => {
        if (id !== SHEET_ID_FOR_TESTS) throw new Error('no spreadsheet with id ' + id);
        return ss;
      },
    },
    Utilities: {
      formatDate(date, tz, fmt) {
        if (fmt !== 'yyyy-MM-dd') throw new Error('unsupported format ' + fmt);
        return new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
      },
      getUuid: () => 'test-token-0000',
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
    LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
    Logger: { log() {} },
  };
  vm.createContext(ctx);
  // Date cells must be the sandbox's own Date, or Code.js's `instanceof Date` fails
  // across realms (real Apps Script has only one).
  const SandboxDate = vm.runInContext('Date', ctx);
  for (const [name, data] of Object.entries(sheets)) {
    book[name] = new Sheet(name, data.map((row) => (row || []).map((v) => (v instanceof Date ? new SandboxDate(v.getTime()) : v))));
  }
  for (const f of ['Core.js', 'Code.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '..', f), 'utf8'), ctx, { filename: f });
  }
  return {
    ctx,
    ss,
    props: store,
    get: (parameter) => JSON.parse(ctx.doGet({ parameter }).text),
    post: (parameter, body) => JSON.parse(ctx.doPost({ parameter, postData: { contents: JSON.stringify(body) } }).text),
  };
}

/** A Date at local midnight of <iso> in <tz>, like a date cell's value in that zone. */
function dateCell(iso, tz = 'America/New_York') {
  // Find the UTC instant whose wall-clock date in tz is iso at 00:00.
  const guess = new Date(iso + 'T12:00:00Z');
  const hour = Number(new Intl.DateTimeFormat('en-US', { timeZone: tz, hour: 'numeric', hourCycle: 'h23' }).format(guess));
  return new Date(guess.getTime() - hour * 3600000);
}

module.exports = { loadScript, dateCell, Sheet, SHEET_ID_FOR_TESTS };
