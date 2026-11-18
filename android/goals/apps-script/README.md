# Apps Script backend

A standalone web app that decides whether today's gym goals are met. It only reads the gym log
spreadsheet: `appsscript.json` asks for read-only spreadsheet access, so it can't change the sheet.

- `Core.js`: pure logic (dates, schedule, goals, streak, tokens, stored settings). Tested in Node.
- `Code.js`: web app endpoints, sheet reads and settings storage.
- `appsscript.json`: the manifest (read-only scope, web app settings).

## What it reads
`Exercise`: row 2 holds dates from column D; column B labels a section (Push, Pull, Legs, Core,
Cardio, Free, Stretch, ...) on its first row; any non-empty cell means that exercise was logged
that date. Spacer columns with no date are skipped. Nothing else in the sheet is used.

## What it stores
The app sets these through the endpoints. They are script properties (Project Settings > Script
properties), one per dated entry, each a JSON array:

- `WEEK_<sunday>`: a saved week, 7 workout names from Sunday. A week that isn't saved follows the
  default week, or repeats the week before it when no default is set.
- `DEFAULT_<date>`: the default week from that date.
- `GOALS_<date>`: the workouts from that date. Each workout is done when every one of its goals
  is met. There are two goal types, and one workout can mix both:
  - `section`: log at least *min* exercises from that section of `Exercise` that day.
    Example: Upper = Push 2, Pull 2, Core 1.
  - `exercise`: log that one exercise. The name leaves out its `[unit]` and `(...)` suffixes, and
    *min* is ignored. Example: Skate = Figure skating.
- `ALLOWLIST_<date>`: the packages allowed from that date. While today is blocked, every app
  outside the list in effect is blocked.

Each day is judged by the goals in effect that day, so changing goals never rewrites the streak.

## Rules
- Weeks start Sunday. A saved week needs at least 4 gym days.
- A day can be changed until midnight the night before it; "today" is the server's date in
  the spreadsheet's time zone, never the device clock.
- Each completed gym day adds 1 to the streak. A missed one spends a freeze token, if there is one,
  or resets the streak. Rest days and today (while still unmet) change nothing.
- One token per 7 completed gym days, capped at 2. Workouts on rest days don't count.
- A saved default week takes effect the next day and only fills weeks that aren't saved, so
  changing it never changes today or an earlier day. It needs the same 4 gym days.
- Saved goals and allowlists take effect the next day (server date), so changing them never
  unblocks anything today. A workout that is still planned from tomorrow on can't be removed.

## Deploy
1. Go to script.google.com > New project. Create files `Core.gs` and `Code.gs` and paste in the
   contents of `Core.js` and `Code.js`.
2. Project Settings: tick "Show appsscript.json manifest file in editor". Paste the contents of
   `appsscript.json` over that file.
3. Project Settings > Script properties: add `SHEET_ID`, the id from the gym log's URL
   (`docs.google.com/spreadsheets/d/<SHEET_ID>/edit`).
4. Pick `setup` in the function menu, then Run. Grant access; the consent screen should ask only
   to *see* your spreadsheets. Copy the `TOKEN=...` line from the execution log. `setup` also saves
   starting goals (Upper, Lower, Skate) for the app to change.
5. Deploy > New deployment > Web app. "Execute as" Me and "Who has access" Anyone come from the
   manifest. Copy the `/exec` URL.
6. Test it: open `<url>?token=<TOKEN>` in a browser; you should get JSON.

The URL and token together grant access, so keep them out of public places. To rotate the token,
delete `TOKEN` under Script properties and run `setup` again.

For emulator testing only, set the script property `ALLOW_FAKE_TODAY` to `true`. With it set,
`&today=2026-11-20` pretends that date is today. Remove the property afterwards.

## Tests
    node --test 'android/goals/apps-script/tests/*.test.js'   (from the repo root)

- `core.test.js`: the rules in `Core.js`, with fake dates.
- `endpoints.test.js`: `Code.js` and `Core.js` run unchanged on `gas.js`, a small in-memory stand-in
  for the Apps Script services (SpreadsheetApp, Utilities, PropertiesService, ContentService,
  LockService). Its spreadsheet has no write methods, so any write fails a test. It covers
  `setup`, the token check, every endpoint, the sheet reads and the stored settings, using a
  made-up Exercise tab laid out like the real one.
