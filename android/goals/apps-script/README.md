# Apps Script backend

Runs inside the gym log spreadsheet (bound script) and decides whether today's gym goals are met.

- `Core.js`: pure logic (dates, schedule, goals, streak, tokens). Tested in Node.
- `Code.js`: web app endpoints and sheet I/O.

## Sheet layout it reads
- `Exercise` (existing, unchanged): row 2 holds dates from column D; column B labels a section
  (Push, Pull, Legs, Core, Cardio, Free, Stretch, ...) on its first row; any non-empty cell means
  that exercise was logged that date. Spacer columns with no date are skipped.
- `Gate Goals` (created by `setup()`): `Workout | Section | Min exercises`. A workout is done when
  every one of its rows is met, for example `Upper | Push | 2` plus `Upper | Pull | 2`.
- `Gate Schedule` (created by `setup()`, written by the app): `yyyy-mm-dd | Workout or Rest`.
  A week with no rows repeats the week before it.

## Rules
- Weeks start Sunday. A saved week needs at least 4 gym days.
- A day can be changed until midnight the night before it; "today" is the server's date in
  the spreadsheet's time zone, never the device clock.
- Each completed gym day adds 1 to the streak. A missed one spends a freeze token, if there is one,
  or resets the streak. Rest days and today (while still unmet) change nothing.
- One token per 7 completed gym days, capped at 2. Workouts on rest days don't count.

## Deploy
1. In the sheet: Extensions > Apps Script. Create files `Core.gs` and `Code.gs` and paste in
   the contents of `Core.js` and `Code.js`.
2. Pick `setup` in the function menu, then Run. Grant access. Copy the `TOKEN=...` line
   from the execution log.
3. Edit the `Gate Goals` tab so that it matches the workouts you do.
4. Deploy > New deployment > Web app. Set "Execute as" to Me and "Who has access" to Anyone.
   Copy the `/exec` URL.
5. Test it: open `<url>?token=<TOKEN>` in a browser; you should get JSON.

The URL and token together grant access, so keep them out of public places. To rotate the token,
delete `TOKEN` under Project Settings > Script properties and run `setup` again.

For emulator testing only, set the script property `ALLOW_FAKE_TODAY` to `true`. With it set,
`&today=2026-11-20` pretends that date is today. Remove the property afterwards.

## Tests
    node --test 'android/goals/apps-script/tests/*.test.js'   (from the repo root)
