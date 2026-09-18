# Cindy

Native iOS and Android rep counter for the CrossFit benchmark WOD **"Cindy"**
(AMRAP 20 min: 5 pull-ups, 10 push-ups, 15 air squats). The phone lies flat on
the floor under the athlete's face, front camera up, and counts reps and rounds
from the face signal (squats: the image brightness, so the athlete can look
wherever they like). Everything runs on device; no video is stored or
transmitted.

The two apps are separate native code bases with the same design:

```
ios/       SwiftUI app (XcodeGen project)
android/   Kotlin app: :core (pure logic, JVM tests) + :app (Compose UI, CameraX, MediaPipe)
shared/    CSV signal recordings both test suites replay
tools/     analysis and check scripts
docs/      store listing
```

Most of this README describes the design with the iOS names; the Android
section below maps it onto the Kotlin code. A change to the detection, the
workout rules or the readiness model belongs in both apps, and the shared CSV
replays hold both to the same rep counts.

## Build

### iOS

Requirements: Xcode 26, iOS 26+ device with Face ID, [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
cd ios
xcodegen generate            # creates Cindy.xcodeproj from project.yml
open Cindy.xcodeproj
```

The project file is generated and git-ignored; edit `ios/project.yml`, not the
`.xcodeproj`. Set your development team in Xcode (Signing & Capabilities) to run
on a device. The camera does not work in the simulator, but UI and unit tests do:

```bash
cd ios
xcodebuild -project Cindy.xcodeproj -scheme Cindy \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test
```

### Android

Requirements: JDK 21, Android SDK with platform 37, a device with Android 10+
and a front camera. The Gradle wrapper fetches everything else.

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest    # JVM tests, including the shared CSV replays
./gradlew :app:lintDebug                       # a string without German translation is an error
./gradlew :app:installDebug                    # onto a connected device or emulator
```

The emulator's front camera shows a synthetic scene, so nothing counts there, but
every screen and permission flow can be walked through.

## Detection chain

```
Front camera (30 fps, exposure locked after 2 s)
  → VisionProcessor      VNDetectFaceRectanglesRequest and/or VNDetectHumanBodyPoseRequest, per exercise;
                         each keeps its own orientation search (the phone lies flat)
  → FrameMetrics         mean brightness of the frame (squats without TrueDepth)
  → DepthMetrics         TrueDepth distances: valid share, percentiles, median (squats, push-ups);
                         for the depth signal the depth maps are the frames and no video frame
                         reaches the app (no Vision request runs)
  → SignalExtractor      one Float per frame for the expected exercise (+ confidence)
  → MedianFilter         5 samples, body pose and depth only (single-frame outliers)
  → EMAFilter            alpha 0.3 per 30 fps frame, scaled to the frame interval
  → RepDetector          Schmitt trigger (low / high), rep duration 0.5–5 s, arming debounce of 10 frames
                         at 30 fps (or ≥ 3 samples spanning 0.3 s),
                         thresholds relative to the rest level (scaled for sizes and distances, shifted for brightness),
                         BodyEvidence veto for brightness reps
  → WorkoutStateMachine  exercise, rep counter, round, transitions
  → WorkoutEngine        20-minute clock, audio feedback, UI state
```

| Exercise | Default source (no TrueDepth) | `.face` signal                  | `.pose` signal | `.brightness` | `.depth`                  | Rest position |
|----------|-------------------------------|---------------------------------|----------------|---------------|---------------------------|---------------|
| Push-up  | `.depth` (`.face`)            | face bounding-box area          | nose y         | mean luma     | median distance           | arms extended |
| Squat    | `.depth` (`.brightness`)      | face area (+ optional centre-y) | shoulder width | mean luma     | median distance           | standing      |
| Pull-up  | `.face`                       | face area (+ optional centre-y) | shoulder y     | mean luma     | median distance           | dead hang     |
| Plank    | none (timer after AMRAP)      | –                               | –              | –             | –                         | plank         |

On phones with a TrueDepth front camera, squats and push-ups count on the
depth map since the recordings of 2026-09-17 (`shared/fixtures/recorded_*_depth.csv`).
The signal is the median distance of the whole map: standing ~1.3 m, 0.4–0.75 m at
the bottom of every squat whichever way the athlete looks. At the bottom of a push-up
the athlete is closer than the camera measures and most of the map is holes; below
50 % valid pixels the signal is the 5th percentile instead (~0.16 m). Depth thresholds
hang off the rest distance (leave at 25 %, peak at 50 % of the calibrated swing), so a
squat shallower than the calibration one still counts, and scale with the rest distance
like face sizes. Phones without TrueDepth (the iPhone SE and the iPhone Duo, which has
Touch ID) cannot be excluded from the App Store — there is no required device capability
for it — so they keep the face for push-ups and the brightness for squats, and the intro
tells them counting is less reliable there. The Android app has no depth either.

The camera preview is off by default in calibration and workout (a button shows it);
counting needs no picture. The debug recorder always shows it.

Without TrueDepth, squats count on the brightness since the device recordings of 2026-09-14
(`shared/fixtures/recorded_squats_*`): from the floor the face is only found
while the athlete looks down, and the body pose drops out at the bottom of every
squat when looking ahead. The body darkens the picture the lower it gets, whichever
way the athlete looks (18 of 18 squats in the two recordings with a still start).
A squat overshoots past the rest brightness while standing up, so brightness
thresholds hang off the rest level (leave at 30 %, peak at 70 % of the calibrated
dip) instead of the cycle extremes. Face and pose keep running as `BodyEvidence`:
a dip only counts if the face area grew, the shoulder width grew or face/pose
vanished during the cycle, so a passing cloud does not count. Calibrations stored
for a source the config no longer uses are dropped on load, so the app asks for
just those exercises again.

Calibrated thresholds carry the calibration baseline, and the detector measures the
rest level when it arms. Face area and shoulder width are sizes in the image and
scale with the distance to the phone: their thresholds are rescaled (within 2.5×),
the rest follows lower values while at rest (walking away after arming). Brightness
thresholds are shifted by the rest difference (within ±0.1). Either way a cycle that
stays open longer than 5 s disarms, so a new standing position gets a fresh rest.

All tunables live in `ios/Cindy/Core/SignalConfig.swift` and its twin
`android/core/…/SignalConfig.kt` (EMA alpha, 25 % threshold
margin, rep duration limits, arming frames, confidence, lost-timeout, per-exercise
signal source, face-y weights, calibration timeouts).

The chain was tuned on iPhones at 30 fps, but a Galaxy A20e delivers 5–20 fps, where
ten arming frames would outlast the pause between two squats. Frame counts and EMA
factors therefore mean frames at `SignalConfig.referenceFrameRate` (30 fps), and
`FrameTiming` applies them to time: an alpha becomes 1 − (1 − alpha)^(dt · 30) for a
frame interval dt (capped at 0.5 s); `stableFrames` and `evidenceLostFrames` are met by
that many samples or by fewer (at least 3 resp. 2) spanning (N − 1) / 30 s. At 30 fps
nothing changes. The pose median stays 5 samples.

The rep direction (peak vs. trough relative to the rest position) is measured
during calibration, so the face-area assumptions per exercise only matter for
the hard-coded debug thresholds.

## Workout plan

`Workout anpassen` on the start screen edits the round: enable/disable exercises,
reps per exercise (seconds for the plank), order by drag and the AMRAP duration.
The plan is persisted in UserDefaults; "Original Cindy" restores 5/10/15 in 20 min.
The plank is not part of the round: it follows once, after the AMRAP clock has run out
(`WorkoutPlan.plankSeconds`, `WorkoutPhase.plank`). The score is final by then and the
camera is off. Once in position the athlete taps start, a 3 s countdown runs, then the
clock runs to the target (beeping every 10 s) or until they tap finish; there is no
pause. The seconds held are stored with the record (`plankSeconds`), outside the score.
Plans saved while the plank was a round exercise move it behind the AMRAP when decoded.

The pause screen opens the same editor on the running workout (`PlanEditorList`,
`WorkoutEngine.updatePlan`). Only calibrated exercises can be added, and only
durations the clock has not passed are offered. `WorkoutStateMachine.replacePlan`
keeps the completed rounds and the reps of the current exercise; an exercise whose
new target is already reached is done, a removed one hands over to the first
exercise of the new plan not yet done this round (or completes the round). The
changed plan is saved as the plan for the next workout too, and the record stores
the plan as it stood at the end.

## Progression

Every round completion is stored with its elapsed time. `ProgressionAdvisor`
rates the workout by pace reserve (mean round time of the first quarter divided
by the last quarter; 1 = steady) and suggests the next plan in a fixed order:
duration up to 20 min, then +1 rep on the exercise furthest below 5/10/15, then
+5 s plank, then a higher round goal. A rounds collapse of more than 20 % after
a harder plan steps back to the previous plan. The result screen shows the
round-time chart and the suggestion, which can be adopted with one tap.

## Apple Health

Optional, off until switched on in Settings. A saved workout is written with
`HKWorkoutBuilder` as a `.crossTraining` session, with one `HKWorkoutActivity`
segment per round and score, round count and plan as metadata.
`WorkoutHealthSample` does the mapping and is free of HealthKit so it can be
unit-tested; `HealthExporter` owns the `HKHealthStore`.

The app's clock counts active time only, so the sample spans the active time
and ends when the workout ended — a paused session shows up as having started
later than it really did, in exchange for exact round segments.

Cindy writes the workout plus its active energy and reads four types: body mass
for the MET estimate, and sleep analysis, resting heart rate and HRV for the
readiness estimate below. Without a body mass no energy is written at all. Exported records are remembered in UserDefaults
(`healthExportedWorkoutIDs`), so duplicates are avoided without read access to
the workout store; workouts saved before the switch was flipped can be
backfilled from Settings.

## Readiness

The start screen estimates how ready the body is for the next session, 0–100.
Apple publishes no such figure — iOS 26 has no readiness or sleep-score
HealthKit type — so `ReadinessEstimator` computes one from what is actually
readable and from the workout history:

| Component | Weight | Source |
| --- | --- | --- |
| Recovery since the last session | 0.35 | history (a full AMRAP needs 48 h, a short one proportionally less) |
| Sleep last night | 0.20 | Health (`sleepAnalysis`, overlapping sources merged) |
| HRV vs. personal baseline | 0.25 | Health (`heartRateVariabilitySDNN`, 60 days) |
| Resting pulse vs. baseline | 0.10 | Health (`restingHeartRate`, 60 days) |
| Weekly volume vs. 28-day average | 0.10 | history |

Every input is optional; the score is the weighted mean of the components that
exist, so the estimate degrades to history-only instead of disappearing when
there is no Apple Watch or no Health access. A baseline needs ten days of
values and some spread, and the latest value has to be younger than 36 h —
otherwise that component is dropped rather than guessed. The curves in
`ReadinessScoring` are judgement calls from the training literature, not a
validated model, and they are piecewise linear so they stay easy to argue with.

## Reminder

Optional, off until switched on in Settings. `NextSessionPlanner` picks the
moment: the recovery time the last session needs (see above), plus extra rest
when the body signals ask for it, snapped to the median start time of the last
ten workouts and clamped to 07:00–21:00.

The extra rest comes from `Readiness.signalScore` — the weighted score of
everything *except* the hours-since-the-last-session component. Using the total
would count the same fatigue twice, since the total is low right after a
workout by construction. `extraRestCurve` turns that score into hours
continuously: nothing at an average day (78) or better, about four hours at 65,
half a day at 45, a full day at the bottom. Continuous rather than stepped,
because a score built from a handful of noisy daily values cannot justify
letting one point decide half a day.

A slot up to two hours before the computed ready time still counts, so being
ready at 19:00 with a usual slot of 18:40 does not cost a whole day.

`WorkoutReminder` writes a single `UNCalendarNotificationTrigger` under one
identifier, so rescheduling always replaces the previous nudge. A pending
notification cannot re-evaluate itself, so it is rewritten on every readiness
refresh: at launch, after a workout, and from the `BGAppRefreshTask`
(`me.raddatz.cindy.refresh`, requested every 12 h, granted whenever iOS feels
like it).

## Calibration

`Kalibrieren` on the start screen: instructions → for each exercise a 3 s
countdown, then the app records until one full cycle is seen (or 15 s pass).
The rest baseline is the median of the first 0.5 s, thresholds are derived from
the observed min/max, and min / max / low / high / direction / rep duration are
stored in `Documents/calibration.json`. Without a complete profile no workout can
be started.

## Intro and exercise demos

`OnboardingView` runs once on a fresh install (`hasSeenIntro` in `AppModel`) and
is reachable again from Settings › "How Cindy works": what the workout is, how
the counting works, where the phone goes, what each rep looks like, then a
hand-off into calibration.

The demo of a single movement is `StickFigureDemoView` behind
`ExerciseDemoView`, reachable from the plan editor, each calibration step and the
workout's pause screen. It draws a looping stick figure from the keyframes in
`ExerciseDemo.swift`, together with the floor, the bar and the phone, so every
exercise shows the phone in the same spot. It needs no assets and no camera.

Camera angle follows `ExerciseDemo.perspective`: side-on for push-up, plank and
squat, head-on for the pull-up, whose bar is a dot from the side. For push-ups the
phone lies between the hands, so it is drawn behind the near arm and in front of
the far one.

## Debug / recording mode

Long-press the **Cindy** title on the start screen. The screen shows the live
raw and smoothed signal, confidence, face box, orientation, detector phase and a
sparkline with the current thresholds. "Aufnahme starten" writes one CSV row per
frame to `Documents/DebugLogs/` (numbers only, no images); files can be shared
via the share sheet and deleted in place.

The toggle "Workouts mitschneiden" in Settings (gear on the start screen)
records every real workout as well; Settings → Aufnahmen lists, shares and
deletes the files. Those rows additionally carry the state machine state in the `state`
column (`phase|round|exercise|count`, e.g. `active|r3|pushUp|7`), which is what
you need to debug transitions and miscounts. About 4.5 MB per 20 minutes; a red
REC marker on the workout screen shows that recording is on.

Analyse a recording on the computer:

```bash
python3 tools/analyze_csv.py path/to/recording.csv            # stats + detector simulation
python3 tools/analyze_csv.py recording.csv --alpha 0.2 --margin 0.3 --plot
python3 tools/analyze_csv.py recording.csv --column pose_shoulder_w --direction peak \
  --low 0.34 --high 0.51 --baseline 0.25                      # another column, relative thresholds
```

Besides the signal the rows carry the active `low`/`high`, the shoulder width and its
confidence, the pose orientation and the image brightness (`luma_mean`, `luma_center`).
The debug recorder always runs the face request, so its drop-outs stay visible
next to a body-pose signal.

Drop recordings into `shared/fixtures/` and add a case to
`PipelineReplayTests` in both apps to turn them into regression tests
(`CSVSignalReplay` parses the logger format, which is the same on Android).

## Language and appearance

Settings offer **Language** (Automatic / English / Deutsch) and **Appearance**
(Automatic / Light / Dark). Both are stored in `UserDefaults` via `AppModel` and
take effect immediately — no restart.

Source strings are English and act as their own keys. German lives in
`ios/Cindy/Resources/de.lproj/Localizable.strings`; `en.lproj` exists so that picking
English explicitly resolves a real bundle instead of falling back. Plural forms
(rounds, reps, minutes, seconds) are in `Localizable.stringsdict`, not built by
string concatenation.

Every user-facing string goes through `L("…")` (`Core/Localization.swift`) rather
than `Text`'s implicit lookup, because error descriptions and progression
advice are produced outside the view tree and must use the same
bundle. For the same reason dates, decimals, percentages and byte counts use the
`Localization.locale` helpers — `Date.formatted(date:time:)` and
`ByteCountFormatter` would silently fall back to the *system* locale.

Adding a string: write `L("English text")`, then add one line to
`de.lproj/Localizable.strings` and `en.lproj/Localizable.strings`. Exercise names
and units use semantic keys (`exercise.pullUp.plural`, `unit.reps`) because their
singular and plural forms collide as English keys.

`python3 tools/check_localization.py` diffs the `L(…)` calls against both
catalogs and reports missing or orphaned keys (exit code 1 on drift). A new kind
of interpolated value may need a type hint added to its `STRING_HINTS` /
`INT_HINTS` list.

Colors are semantic (`Core/Theme.swift`): `.brand` reads the `AccentColor` asset,
which is a darker orange in light mode for contrast on white. The workout screen
uses `.screenBackground` instead of the hardcoded black it had before, so its
timer, countdown and error overlays stay legible in both schemes.

## Android

The Android app is a port, not a rewrite: the same pipeline, thresholds, state
machine, readiness curves and screens, with the platform parts swapped.

| iOS | Android |
|---|---|
| Pure Swift in `Signal/`, `Calibration/`, `Workout/`, `Health/`, `Reminder/`, `Persistence/` | `:core`, plain Kotlin/JVM with the same type names; no Android imports |
| AVFoundation camera, exposure and white balance locked after 2 s | CameraX `ImageAnalysis`, locked through Camera2 interop where the device supports it |
| Vision face rectangles and body pose, per-request orientation search | MediaPipe Tasks face detector and pose landmarker, same orientation search, coordinates mapped to Vision's convention |
| AVAudioEngine beeps, audible on silent | `AudioTrack` on the media stream, transient ducking audio focus |
| HealthKit, `HKWorkoutBuilder` | Health Connect, `ExerciseSessionRecord` with one segment per round, workout UUID as `clientRecordId` |
| `UNCalendarNotificationTrigger` + `BGAppRefreshTask` | WorkManager: a delayed one-off worker posts the reminder, a 12 h periodic worker recomputes |
| `UserDefaults`, `Documents/` | `SharedPreferences` with the same keys, `filesDir` with the same file names |
| SwiftUI, `L("…")` with a runtime bundle | Compose + Material 3, string resources, `AppCompatDelegate.setApplicationLocales` |

Differences that come from the platform, not from choice:

- **No network, no Play services.** Detection runs on MediaPipe with its models
  bundled in `app/src/main/assets/mediapipe/` (sources and checksums in
  `MODELS.md` there). ML Kit was tried first and dropped: it hands usage data to
  Google Play services, which no manifest entry can stop. The manifest removes
  the `INTERNET` permission, and MediaPipe's usage logger links against inert
  stand-ins for Google's data-transport library, so nothing can leave the phone.
- **HRV is RMSSD.** Health Connect has no SDNN. The estimator only compares a
  value with the user's own 60-day baseline, so the curves are unchanged, but a
  baseline must never mix the two.
- **Background reads need their own permission.** Without
  `READ_HEALTH_DATA_IN_BACKGROUND` the periodic worker computes readiness from the
  history alone. Reads older than 30 days need `READ_HEALTH_DATA_HISTORY`.
- **Timing is approximate.** WorkManager and Doze may delay the reminder, like
  iOS may delay the refresh. No exact alarms, so no special permission.
- **Exposure lock is optional hardware.** Where a device cannot lock exposure the
  brightness signal for squats is less stable; calibration still measures it.

Engines (`WorkoutEngine`, `CalibrationEngine`) expose `StateFlow`s, run on
injectable dispatchers and a fake frame source in the JVM tests, and are bound to
`ProcessLifecycleOwner`, so a language or theme switch that recreates the
activity does not stop the camera.

Seeding test data on a device or emulator: files have to be written through
`run-as`, because a copy via `/data/local/tmp` keeps the shell's SELinux label and
the app silently cannot read it.

```bash
adb exec-in run-as me.raddatz.cindy sh -c 'cat > files/history.json' < history.json
```

## Layout

```
ios/
  project.yml    XcodeGen project (the .xcodeproj is generated)
  Cindy/
    App/           CindyApp, AppModel, RootView (navigation)
    Core/          Exercise, SignalConfig, Localization, AppLanguage, AppTheme, Theme (colors)
    Signal/        FrameObservation, SignalExtractor, MedianFilter, EMAFilter, RepDetector, RepThresholds,
                   BodyEvidence, SignalPipeline
    Camera/        CameraSession (AVFoundation), VisionProcessor (Vision), FrameProcessor (queue glue),
                   FrameMetricsCalculator (image brightness)
    Calibration/   CalibrationProfile (+ store), CalibrationAnalyzer (pure), CalibrationEngine
    Workout/       WorkoutStateMachine (pure), WorkoutEngine (camera + clock + audio)
    Audio/         AudioFeedback (beeps via AVAudioEngine)
    Persistence/   JSONFileStore, HistoryStore
    Demo/          ExerciseDemo (poses + cues), StickFigureDemoView, ExerciseDemoView
    Debug/         FrameLogger (CSV), DebugRecorderView
    Resources/     Assets.xcassets, en.lproj + de.lproj (strings, stringsdict, InfoPlist)
    UI/            Start, Onboarding, Settings, Calibration, Workout, Result, History, camera preview, sparkline
  CindyTests/      Swift Testing unit tests
android/
  core/            me.raddatz.cindy.core: signal, calibration, workout, health, reminder, persistence,
                   debug, demo, camera (brightness) — pure Kotlin, JUnit tests
  app/             me.raddatz.cindy: camera (CameraX + MediaPipe), audio, health (Health Connect),
                   reminder (WorkManager), workout + calibration engines, settings, app (AppModel,
                   navigation), ui (Compose screens, stick figure, theme)
shared/fixtures/   CSV recordings replayed by both test suites
tools/             analyze_csv.py, check_localization.py (iOS catalogs), check_listing.py
```

## CI and release

CI is one workflow per platform, each on pushes to `main` and pull requests that
touch its own directory. `.github/workflows/ci-ios.yml` watches `ios/`, `shared/`
and `tools/check_localization.py`: it regenerates the project from
`ios/project.yml`, checks both string catalogs, runs the unit tests on whatever
iPhone simulator the runner has, and builds Release once so `#if DEBUG`-only
breakage shows up here. `.github/workflows/ci-android.yml` watches `android/` and
`shared/` and runs on Linux: the JVM tests of both modules, lint (which fails on
a missing German string) and a Release build, so a missing R8 keep rule shows up
before a Play build. A change to the shared CSV fixtures runs both, a docs-only
change runs neither, and a workflow file change runs its own workflow. Neither
signs anything or needs secrets. Should these checks ever become required for
merging, keep in mind that a path-filtered workflow that does not run leaves its
required check pending.

The release workflows do not look at paths: they run only when started by hand.

`.github/workflows/release.yml` archives the iOS app and uploads it to
TestFlight. Start it by
hand (Actions → Release to TestFlight → Run workflow). How both release jobs
number and tag their builds is under Versioning below.

Both jobs run on GitHub's `macos-26` image. The repository is public, so a pull
request can come from anyone, and a self-hosted runner would execute a
stranger's code as a real user on a real Mac — home directory, ssh keys,
unlocked login keychain and all. Hosted runners are free for public
repositories, and secrets are never handed to a workflow triggered from a fork,
so the release job's signing material stays out of reach as well.

Seven repository secrets, all for the release job:

| Secret | What it is | Looks like |
|--------|------------|------------|
| `DIST_CERT_P12` | Apple Distribution certificate + key, base64 of a `.p12` | `MIIMi...` |
| `DIST_CERT_PASSWORD` | password of that `.p12` | |
| `DEV_CERT_P12` | Apple Development certificate + key, base64 of a `.p12` | `MIIMi...` |
| `DEV_CERT_PASSWORD` | password of that `.p12` | |
| `ASC_KEY_P8` | App Store Connect API key, base64 of the `.p8` | `LS0tLS1CRUdJTi...` |
| `ASC_KEY_ID` | id of that key, ten characters | `2X9R4HXF34` |
| `ASC_ISSUER_ID` | the team that issued it, a UUID | `57246542-96fe-1a63-e053-0824d011072a` |

The last two sit next to each other in App Store Connect and are easy to mix
up. The issuer id is the same for every key in the account; the key id belongs
to the one key, and is part of the downloaded file name
(`AuthKey_2X9R4HXF34.p8`). The release job rebuilds that name from
`ASC_KEY_ID`, so a mismatched pair fails with a key `xcodebuild` cannot find.

Both certificates and the API key come from Apple by hand, once:

```bash
base64 -i dist.p12 | gh secret set DIST_CERT_P12
base64 -i dev.p12  | gh secret set DEV_CERT_P12
base64 -i AuthKey_2X9R4HXF34.p8 | gh secret set ASC_KEY_P8
gh secret set ASC_KEY_ID        # and the rest, typed in
```

The development certificate never signs anything that ships. Automatic signing
archives with a development identity and only re-signs for distribution on
export, so a fresh runner without one makes `-allowProvisioningUpdates` mint a
new development certificate on every run, until the account reaches Apple's
limit and every later run fails.

Both certificates are imported into a throwaway keychain that is deleted even
when the archive fails.

### Google Play

`.github/workflows/release-android.yml` builds the signed App Bundle and uploads
it to Google Play. Start it by hand (Actions → Release to Google Play → Run
workflow) and pick the track — internal by default.

Before uploading, the job runs both test suites, checks the bundle is signed and
prints the upload certificate's SHA-256 (compare it with Test and release › App
integrity in the Play Console), and fails if the merged release manifest asks
for network access — the privacy policy promises none, and a library update can
quietly merge `INTERNET` back in. The R8 mapping file goes along, so crash
reports in the Play Console are readable.

The build signs from the environment (`CINDY_UPLOAD_KEYSTORE`,
`CINDY_UPLOAD_STORE_PASSWORD`, optional `CINDY_UPLOAD_KEY_ALIAS` defaulting to
`upload` and `CINDY_UPLOAD_KEY_PASSWORD` defaulting to the store password,
`CINDY_VERSION_CODE`); without a keystore the release build stays unsigned, which
is what the CI job builds. A bundle for a manual upload is built the same way
locally, with the password read so it stays out of the shell history:

```bash
cd android
read -s "CINDY_UPLOAD_STORE_PASSWORD?Keystore password: " && export CINDY_UPLOAD_STORE_PASSWORD
CINDY_UPLOAD_KEYSTORE=~/cindy-upload.jks ./gradlew :app:bundleRelease
```

Three repository secrets, plus two optional ones:

| Secret | What it is |
|--------|------------|
| `ANDROID_UPLOAD_KEYSTORE` | the upload keystore, base64 of the `.jks` |
| `ANDROID_UPLOAD_STORE_PASSWORD` | its password |
| `PLAY_SERVICE_ACCOUNT_JSON` | JSON key of a Google Cloud service account with "Google Play Android Developer API" enabled, invited in Play Console › Users and permissions with release rights for Cindy only |
| `ANDROID_UPLOAD_KEY_ALIAS` | optional, if the alias is not `upload` |
| `ANDROID_UPLOAD_KEY_PASSWORD` | optional, if the key has its own password |

Play App Signing holds the key users' installs are signed with; the keystore is
only the upload key. Keep the `.jks` and its password in the password manager —
a secret cannot be read back, and a lost upload key takes a support request to
replace. Never commit it: the repository is public, and `.gitignore` excludes
`*.jks`, `*.keystore`, `*.p12` and `*.p8`.

The Play Developer API cannot create an app's very first release, so version
code 1 was uploaded by hand. While the app has never been published in
production, the API may only accept releases as drafts; if an upload fails with
"Only releases with status draft may be created on draft app", run the workflow
with status `draft` and roll the release out in the Play Console.

### Versioning

Nobody bumps a version by hand for a build. Both release jobs compute it:

| | iOS | Android |
|---|---|---|
| Version users see | `CFBundleShortVersionString` = `<major>.<commits on main>` | `versionName` = `<major>.<commits on main>` |
| Build for the store | `CFBundleVersion` = 10 × run number + attempt | `versionCode` = 10 × run number + attempt |
| Major number | `MARKETING_VERSION` in `ios/project.yml` (`1.0`) | `majorVersion` in `android/app/build.gradle.kts` (`1`) |

The commit count grows with every commit on `main`, so each new state is a
higher version on its own — App Store Connect demands that for every App Store
release and closes a version to further builds once it is out — and the same
commit carries the same version on both platforms. The build number only has to
be unique: Apple within a version, Play across all uploads. Ten per run keeps a
re-run of a run (same run number, next attempt) from repeating the build of an
upload that already went through. Both jobs check out the full history and fail
on a shallow clone, which would count a single commit. Local builds are `1.0`
with build 1.

Two consequences. Rewriting `main`'s history (a force-push after a rebase or
squash) can lower the count and get uploads rejected as older versions. And
since every build is a new version, every TestFlight build for external testers
goes through a Beta App Review first; internal testers and Play's internal track
are not affected. Raise the major number only to say something to users — it is
never needed to get past a store check.

Tags are set by the workflows, not by hand: after a successful upload a small
follow-up job tags the built commit `ios-v1.57` or `android-v1.57`, so git
shows which state went to which store. Uploading the same state again keeps the
first tag. That job is the only one with write access to the repository; the
build and upload jobs, which run the build tools and a third-party upload
action, can only read. The version a commit would get:

```bash
echo "1.$(git rev-list --count HEAD)"
```

## Privacy

`ios/Cindy/PrivacyInfo.xcprivacy` declares no tracking, no collected data and the
one required-reason API the app uses: `UserDefaults` (`CA92.1`, its own
defaults). The release workflow asserts the manifest is actually inside the
archived `.app`, because it is included implicitly by living under the sources
path and would otherwise disappear silently.

`PRIVACY.md` is the privacy policy the App Store listing links to. It has to be
reachable under a public URL — keep the published copy in step with this file.
