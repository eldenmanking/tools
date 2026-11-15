# goals: gym-gated app blocker

An Android app for an e-ink tablet (a Viwoods AI Paper, Android 13, build 1.5.6). On scheduled gym
days it blocks every app outside an allowlist until the day's goals are met in the gym log Google
Sheet. It also tracks a schedule-based streak with freeze tokens. It isn't a launcher; the device
keeps its own.

```
goals
├── README.md          --- this file
└── apps-script        --- backend bound to the gym log sheet (source of truth); see its README
    ├── Code.js        --- web app endpoints (status, schedule, saveSchedule, saveAllowlist), sheet I/O
    ├── Core.js        --- pure rules: schedule lock-in, goals, streak, tokens, allowlist
    ├── README.md      --- sheet layout, rules, deploy steps
    └── tests          --- Node tests with fake dates
```

## Architecture
1. **Apps Script** (`apps-script/`). It reads the `Exercise` log, plus the `Gate Goals`,
   `Gate Schedule` and `Gate Allowlist` tabs, and uses the server's date for today.
   - Each workout (e.g. Upper, Lower, Skate) needs a minimum number of exercises from chosen
     sections (Push, Pull, Core, ...), a specific exercise, or both.
   - It returns `{todayScheduled, workout, done, blocked, streak, longest, tokens, goalsProgress,
     allowlist}`.
   - It saves weekly schedules, rejecting changes to locked days and weeks with fewer than 4 gym
     days.
   - It saves allowlists, which take effect the next day.
   - Access needs a secret token in the URL, so the app needs no Google sign-in.
2. **Android app** (planned: Kotlin and Jetpack Compose).
   - A foreground service watches the foreground app with UsageStatsManager. When the status says
     `blocked` and an app outside the allowlist opens, it shows a full-screen gate. The app itself,
     the launcher and system settings are always allowed.
   - The gate shows the streak, tokens, today's goals and progress, and a "Check again" button.
   - A persistent notification shows the streak, tokens and today's status. It is redrawn only
     when something changes.
   - The app also has a schedule editor and an allowlist picker (changes apply the next day).
   - The last status is cached for offline use. The app checks the status when a blocked app
     opens, and WorkManager refreshes it infrequently in the background.
   - The UI is e-ink first: black and white, large type, no animation, redraws only on change.

## Build order
1. Apps Script and tests (done).
2. Android skeleton and the gate screen, with a manual unlock for testing.
3. Foreground service, app detection, and the permissions flow (usage access, overlay,
   notifications).
4. Wire up the endpoint and add the persistent notification.
5. Schedule editor.
6. On the device: check that the background service survives (exempt the app from battery
   optimization), then polish for e-ink.
