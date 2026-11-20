# Apps Script backend

A standalone web app that decides whether today's gym goals are met. It only reads the gym log
spreadsheet: `appsscript.json` asks for read-only spreadsheet access, so it can't change the sheet.
It reads through the Sheets API (the "Sheets" advanced service, enabled by the manifest), because
`SpreadsheetApp.openById` needs full edit access even to read.

- `Core.js`: pure logic (dates, schedule, goals, streak, tokens, stored settings). Tested in Node.
- `Code.js`: web app endpoints, sheet reads and settings storage.
- `appsscript.json`: the manifest (read-only scope, the Sheets service, web app settings).

## What it reads
`Exercise`: row 2 holds dates from column D; column B labels a section (Push, Pull, Legs, Core,
Cardio, Free, Stretch, ...) on its first row; any non-empty cell means that exercise was logged
that date. Spacer columns with no date are skipped. Nothing else in the sheet is used.

## What it stores
The app sets these through the endpoints. They are script properties (Project Settings > Script
properties), one per dated entry, each a JSON array:

- `WEEK_<sunday>`: a saved week, 7 plans from Sunday. A plan joins workouts with `+`, and a workout
  can carry its own window for that day: `Upper@07:00-10:00 + Stretch`. A week that isn't saved
  follows the default week, or repeats the week before it when no default is set.
- `DEFAULT_<date>`: the default week from that date.
- `GOALS_<date>`: the workouts from that date. Each workout is done when every one of its goals
  is met, and gates apps only in its `window` (`"06:00-10:00"`; all day without one). There are
  two goal types, and one workout can mix both:
  - `section`: log at least *min* sets from that section of `Exercise` that day. A cell holds one
    entry per set, separated by `,` or `;` outside parentheses, so `45(8),45(8);50(6)` is 3 sets.
    Example: Upper = Push 2, Pull 2, Core 1.
  - `exercise`: log that one exercise. The name leaves out its `[unit]` and `(...)` suffixes, and
    *min* is ignored. Example: Skate = Figure skating.
- `ALLOWLIST_<date>`: the packages allowed from that date. While today is blocked, every app
  outside the list in effect is blocked.

- `SKIP_<date>`: `true` when that day was skipped with a freeze token (the app's "Skip today").
- `GRANT_<date>`: a whole number of extra freeze tokens usable on that date only, spent before
  earned ones. There is no endpoint for it: add it by hand under Script properties, e.g.
  `GRANT_2026-11-19` = `1`.

Each day is judged by the goals in effect that day, so changing goals never rewrites the streak.

## Rules
- Weeks start Sunday. A saved week can have any number of gym days: the night before is trusted
  to plan the next day. The default week needs at least 4.
- Apps are gated only while a workout that isn't done is in its window. The day counts done when
  every workout is, whenever it is logged before midnight; a missed window doesn't lock the rest of
  the day.
- A workout on a day can be planned, moved or dropped until an hour before its window starts (an
  all-day one: 23:00 the night before). Days before today can't change. "Today" and the time are the
  server's, in the spreadsheet's time zone, never the device clock.
- Each completed gym day adds 1 to the streak. A missed one spends a freeze token, if there is one,
  or resets the streak. Rest days and today (while still unmet) change nothing.
- A blocked gym day can be skipped, but only with a token: it counts like a missed day covered by a
  token (the streak is kept) and unblocks the rest of the day.
- One token per 7 completed gym days, capped at 2. Workouts on rest days don't count.
- A saved default week takes effect the next day (saved after 23:00, the day after) and only fills
  weeks that aren't saved, so changing it never changes today or an earlier day.
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
   to *see* your spreadsheets. Copy the `TOKEN=...` and `PAGE_TOKEN=...` lines from the execution log. `setup` also saves
   starting goals (Upper, Lower, Skate) for the app to change.
5. Deploy > New deployment > Web app. "Execute as" Me and "Who has access" Anyone come from the
   manifest. Copy the `/exec` URL.
6. Test it: open `<url>?token=<TOKEN>` in a browser; you should get JSON.

To update the code later, use `deploy.sh` (below), or paste the new files by hand, then Deploy >
Manage deployments > edit (pencil) > Version: New version > Deploy. Either keeps the same `/exec` URL.

The URL and `TOKEN` together grant every action and are the key for unlock codes, so keep them on
the tablet and out of public places. `PAGE_TOKEN` opens only the phone page (below), so a leaked
bookmark shows today's status and code and can skip today, but can't change anything else or work
out other days' codes. To rotate either, delete it under Script properties and run `setup` again
(a new `TOKEN` also needs a rebuilt app).

For emulator testing only, set the script property `ALLOW_FAKE_TODAY` to `true`. With it set,
`&today=2026-11-20` pretends that date is today (from midnight) and `&now=18:30` that time. Remove
the property afterwards.

## The phone page
`<url>?token=<PAGE_TOKEN>&action=page` is a page for a phone's browser (bookmark it): today's workouts
with their windows and progress, the streak and tokens, and:
- once nothing is left to gate today (done, skipped or a rest day), a 6-digit unlock code. The
  tablet's gate takes it without a connection: it is the HMAC-SHA256 of `open:<date>` keyed by
  `TOKEN`, truncated as in HOTP, so the app works it out itself. It opens that day only.
- while today can be skipped, "Skip today with a token", then a confirm link carrying today's date;
  only that link spends the token.

## Deploy with clasp
[clasp](https://github.com/google/clasp) uploads the files and updates the deployment from the
command line. Once:

1. Turn on "Google Apps Script API" at script.google.com/home/usersettings.
2. `npm install -g @google/clasp`, then `clasp login`. The login token in `~/.clasprc.json` can edit
   all your Apps Script projects; keep it private.
3. In this folder, create `.clasp.json` (gitignored) with the project's script id (Project
   Settings > IDs): `{"scriptId": "<id>", "rootDir": "."}`.

Then `./deploy.sh` runs the tests, pushes `Core.js`, `Code.js` and `appsscript.json` (see
`.claspignore`), and moves the web app's deployment to a new version, so the URL stays the same. It
finds the deployment by itself when there is only one; otherwise set `GATE_DEPLOYMENT_ID`. A push
replaces every file in the project, so keep the code here rather than editing it in the browser.

## Tests
    node --test 'android/goals/apps-script/tests/*.test.js'   (from the repo root)

- `core.test.js`: the rules in `Core.js`, with fake dates.
- `endpoints.test.js`: `Code.js` and `Core.js` run unchanged on `gas.js`, a small in-memory stand-in
  for the Apps Script services (the Sheets service, Utilities, PropertiesService, ContentService,
  LockService). Its Sheets service only reads, so any write fails a test. It covers
  `setup`, the token check, every endpoint, the sheet reads and the stored settings, using a
  made-up Exercise tab laid out like the real one.
