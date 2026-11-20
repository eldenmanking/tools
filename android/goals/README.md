# goals: gym-gated app blocker

An Android app for an e-ink tablet (a Viwoods AI Paper, Android 13, build 1.5.6). On scheduled gym
days it blocks every app outside an allowlist until the day's goals are met in the gym log Google
Sheet. It also tracks a schedule-based streak with freeze tokens. It isn't a launcher; the device
keeps its own.

```
goals
├── README.md              --- this file
├── apps-script            --- backend that reads the gym log sheet and keeps the settings; see its README
│   ├── .claspignore       --- what clasp uploads: the scripts and the manifest only
│   ├── appsscript.json    --- manifest: read-only spreadsheet access, web app settings
│   ├── deploy.sh          --- tests, pushes with clasp and moves the web app to the new version
│   ├── Code.js            --- web app endpoints (status, schedule, save*, the phone page), sheet reads, settings
│   ├── Core.js            --- pure rules: schedule, windows and locks, goals, streak, tokens, allowlist, unlock code
│   ├── README.md          --- what it reads and stores, rules, deploy steps
│   └── tests              --- Node tests: rules with fake dates, endpoints on a fake Apps Script runtime
├── app                    --- the Android app (Compose foundation, no Material)
│   ├── src/main/kotlin/goals/app
│   │   ├── GateActivity.kt    --- full-screen gate over a gated app; back goes to the launcher
│   │   ├── GoalsApp.kt        --- picks the gate or home screen, runs "Check again"
│   │   ├── MainActivity.kt    --- the app's own screen: setup checklist, then home or gate
│   │   ├── Permissions.kt     --- the permissions the gate needs and their settings screens
│   │   ├── data               --- status and unlock files (both processes read them) and status sources
│   │   ├── ui                 --- e-ink kit (Ink.kt) and the screens from the mocks
│   │   └── watch              --- WatchService (the :watch process) and BootReceiver
│   └── src/test               --- Paparazzi screenshot tests; snapshots/ holds the recorded PNGs
├── core                   --- plain Kotlin, no Android SDK: status JSON, gate decision with windows,
│                              allowance and unlock code, week strip, foreground app, refresh timing,
│                              notification text
├── build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew, gradle/
└── .gitignore
```

## Architecture
1. **Apps Script** (`apps-script/`). It only reads the sheet: the `Exercise` log. The schedule,
   default week, goals and allowlist are set in the app and kept in the script's properties. It
   uses the server's date for today.
   - Each workout (e.g. Upper, Lower, Skate) needs a minimum number of exercises from chosen
     sections (Push, Pull, Core, ...), a specific exercise, or both.
   - Each workout gates only in its window (e.g. 06:00–10:00; all day without one), and a day's
     plan can give a workout its own window. The day is done when every workout is.
   - It returns `{todayScheduled, workout, done, open, blocked, sessions, streak, longest, tokens,
     goalsProgress, allowlist, week, history, timeZone}`; `sessions` are today's workouts with
     their windows and goals.
   - It saves weekly schedules, where a workout can change until an hour before its window, and a
     default week (at least 4 gym days) that fills unsaved weeks from the next day on.
   - It serves a page for a phone's browser: today's status, an unlock code once nothing is left to
     gate, and skipping today with a token.
   - It saves goals and allowlists, which take effect the next day.
   - Access needs a secret token in the URL, so the app needs no Google sign-in.
2. **Android app** (Kotlin and Jetpack Compose; `core` holds the logic, `app` the UI).
   - A foreground service watches the foreground app with UsageStatsManager. While a workout that
     isn't done is in its window and an app outside the allowlist opens, it shows a full-screen
     gate. It judges the time itself from the cached status, so windows open and close offline. The
     app itself, the launcher and system settings are always allowed.
   - The gate offers 2 minutes open, once an hour (by the wall clock, so it runs out with the
     screen off too), and an unlock code: the phone page shows it once today is done or skipped,
     and the tablet checks it without a connection (HMAC of the date with the token; 5 wrong
     codes, then 15 minutes' wait).
   - The gate shows the streak, tokens, today's goals and progress, and a "Check again" button.
   - A persistent notification shows the streak, tokens and today's status. It is redrawn only
     when something changes.
   - The app's own screen has Status, Schedule, Goals and Allowlist tabs, for the schedule and
     default week, the goals and the allowlist.
   - The last status is cached for offline use. The app checks the status when a blocked app
     opens, and refreshes it infrequently in the background (see Memory: no WorkManager).
   - A cached status counts only for the day it is from (in the sheet's time zone) and only while
     the device clock isn't behind it. Otherwise the app gates until a check succeeds, so going
     offline or winding the clock back unlocks nothing.
   - The UI is e-ink first: black and white, large type, no animation, redraws only on change.

## Memory
The tablet has little RAM. Measured with `adb shell dumpsys meminfo` on an Android 13 emulator
(x86_64, software rendering), in KB. PSS counts shared pages proportionally; private (USS) is
memory only this app uses, which is roughly what it adds.

| State | PSS | Private | Notes |
|---|---|---|---|
| Release, app open | 24,000 | 17,700 | 12,300 native: rendering caches and fonts |
| Release, in background | 23,300 | 17,900 | kept until Android asks for memory back |
| Release, background after a `COMPLETE` trim | 19,000 | 13,600 | native drops to 8,200; cached, killable |
| Debug, app open | 55,200 | 47,000 | 20,500 code (no R8); never install debug |
| UI-less foreground service in its own process | 7,300 | 4,500 | probe; `oom_adj` 200 so it stays alive |
| `WatchService` (`:watch`), screen on, polling | 8,200 | 5,100 | the real watcher; the UI process can be killed meanwhile |
| For scale: system app processes without UI | 6,400–9,600 | | e.g. providers.calendar, keychain |

Rules that keep it there (step 3 follows them):
- Install the release build (R8), not debug.
- The always-on watcher is a foreground service in its own process (`android:process=":watch"`)
  that never touches Compose or the UI classes: about 7 MB, which is the floor for any
  Android process. In the UI process it would hold the UI's 19–24 MB all day.
- The UI process (home screen, gate) exists only while shown. Once it's backgrounded, Android
  can trim it and kill it; nothing keeps it alive.
- No WorkManager: its startup initializer runs in every process, including `:watch`, and opens
  a database. The watcher schedules its own infrequent checks (`shouldFetch` in `core`).
- On the tablet, GPU buffers show up under Graphics, which the emulator's software renderer
  reports as 0. Check with `adb shell dumpsys meminfo goals.app` and `goals.app:watch` on the
  device.

## Background survival
Checked on the Android 13 emulator with the watcher running and no battery exemption:
- Forced deep Doze for 3 minutes, screen off: `:watch` stayed alive, and a blocked app was
  gated 2 s after waking. The foreground service keeps the app in the active standby bucket
  (`am set-standby-bucket restricted` doesn't stick).
- `kill -9` of `:watch` (what the low-memory killer does): restarted in about 5 s.
- Settings > Force stop: stays stopped until Gym Gate is opened again. Android allows nothing
  else, and Settings has to stay reachable so the tablet can't be locked out. This is the
  known way around the gate, along with revoking usage access.
- Vendor task killers aren't covered by the emulator; check on the tablet.

What the tablet's own framework does (from a decompiled `services.jar` of firmware 1.5.6):
- No Viwoods code that kills or freezes background apps was found; its additions (an e-ink
  settings service, sleep and shutdown screens) don't touch other apps' processes.
- Stock firmware blocks notifications from apps outside a built-in list ("eink project, Blocked
  notification from package"). The gate still works there, but its status notification won't
  show unless that check is patched out.
- Viwoods has its own settings app, `com.viwoods.settings`, which is never gated, like
  `com.android.settings`.

## Build order
1. Apps Script and tests (done).
2. Android skeleton and the gate screen, with a manual unlock for testing (done; uses
   `SampleStatusSource`, a stand-in for the endpoint, with a debug-only "I finished" button).
3. Foreground service, app detection, and the permissions flow (usage access, overlay,
   notifications, battery). Done: `WatchService` in `:watch`, `SetupScreen`, persistent
   notification.
4. Wire up the endpoint and add the persistent notification. Done: `GateClient` reads
   `gate.url` and `gate.token` from `local.properties`; without them the app shows sample data.
5. Schedule, default week, goals and allowlist editors. Done: tabs (Status, Schedule, Goals,
   Allowlist); editing a single week (this week or next) and the default week, with days that
   combine workouts; the goals editor; the allowlist picker (launchable apps, labels only).
6. On the device: check that the background service survives (exempt the app from battery
   optimization), then polish for e-ink.
7. Gated time windows instead of whole days. Done: each workout has a window in the Goals tab
   (all day without one) and a day can set its own in the day picker; missing one window doesn't
   lock the rest of the day. A workout can change until an hour before its window. The Schedule
   tab lists a day's workouts comma separated, with their windows.
8. A 2-minute allowance while gated. Done: it runs by the wall clock (screen off too) and can be
   used again an hour after it was started. When it runs out the gate comes back over whatever is
   in front; audio playing in the background keeps playing.
9. Unlock code for when the tablet is offline. Done: bookmark `<gate.url>?token=<PAGE_TOKEN>&action=page`
   on a phone (its own token, so the app's never sits in the phone's history); it shows today's status, a 6-digit code once today is done or skipped, and a link to
   skip today with a token. Type the code on the gate ("Enter code").

## Building
Open this folder (`android/goals`) in Android Studio, or run:

    ./gradlew :app:installDebug           # build and install on the emulator or a device
    ./gradlew :core:test                  # unit tests for the logic
    ./gradlew :app:verifyPaparazziDebug   # screenshots of the screens, no emulator needed
    ./gradlew :app:recordPaparazziDebug   # re-record them after a deliberate UI change

To use the real endpoint, add the Apps Script `/exec` URL and token to `local.properties`
(gitignored), then rebuild:

    gate.url=https://script.google.com/macros/s/<id>/exec
    gate.token=<TOKEN from setup>

The token ends up inside the APK, so don't share builds made with it.

`core` builds without the Android SDK. Where Google's Maven repository can't be reached, use
`GOALS_CORE_ONLY=1 gradle :core:test`.
