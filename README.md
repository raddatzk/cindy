# Cindy

Native iOS rep counter for the CrossFit benchmark WOD **"Cindy"** (AMRAP 20 min:
5 pull-ups, 10 push-ups, 15 air squats). The iPhone lies flat on the floor under
the pull-up bar, front camera up, and counts reps and rounds from the face
signal (squats: the image brightness, so the athlete can look wherever they like). Everything runs on device; no video is stored or transmitted.

## Build

Requirements: Xcode 26, iOS 26+ device with Face ID, [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
xcodegen generate            # creates Cindy.xcodeproj from project.yml
open Cindy.xcodeproj
```

The project file is generated and git-ignored; edit `project.yml`, not the
`.xcodeproj`. Set your development team in Xcode (Signing & Capabilities) to run
on a device. The camera does not work in the simulator, but UI and unit tests do:

```bash
xcodebuild -project Cindy.xcodeproj -scheme Cindy \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test
```

## Detection chain

```
Front camera (30 fps, exposure locked after 2 s)
  → VisionProcessor      VNDetectFaceRectanglesRequest and/or VNDetectHumanBodyPoseRequest, per exercise;
                         each keeps its own orientation search (the phone lies flat)
  → FrameMetrics         mean brightness of the frame (squats)
  → SignalExtractor      one Float per frame for the expected exercise (+ confidence)
  → MedianFilter         5 frames, body pose only (single-frame outliers)
  → EMAFilter            alpha 0.3
  → RepDetector          Schmitt trigger (low / high), rep duration 0.5–5 s, 10-frame arming debounce,
                         thresholds relative to the rest level (scaled for sizes, shifted for brightness),
                         BodyEvidence veto for brightness reps
  → WorkoutStateMachine  exercise, rep counter, round, transitions
  → WorkoutEngine        20-minute clock, audio feedback, UI state
```

| Exercise | Default source | `.face` signal                  | `.pose` signal       | `.brightness`    | Rest position |
|----------|----------------|---------------------------------|----------------------|------------------|---------------|
| Push-up  | `.face`        | face bounding-box area          | nose y               | mean luma        | arms extended |
| Squat    | `.brightness`  | face area (+ optional centre-y) | shoulder width       | mean luma        | standing      |
| Pull-up  | `.face`        | face area (+ optional centre-y) | shoulder y           | mean luma        | dead hang     |
| Plank    | `.face`        | face area held inside a band    | nose y               | mean luma        | plank         |

Squats count on the brightness since the device recordings of 2026-09-14
(`CindyTests/Fixtures/recorded_squats_*`): from the floor the face is only found
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

All tunables live in `Cindy/Core/SignalConfig.swift` (EMA alpha, 25 % threshold
margin, rep duration limits, arming frames, confidence, lost-timeout, per-exercise
signal source, face-y weights, calibration timeouts).

The rep direction (peak vs. trough relative to the rest position) is measured
during calibration, so the face-area assumptions per exercise only matter for
the hard-coded debug thresholds.

## Workout plan

`Workout anpassen` on the start screen edits the round: enable/disable exercises,
reps per exercise (seconds for the plank), order by drag and the AMRAP duration.
The plan is persisted in UserDefaults; "Original Cindy" restores 5/10/15 in 20 min.
The plank is a hold: `HoldDetector` accumulates time while the smoothed face signal
stays inside the calibrated band and pauses (without reset) when it leaves the
band or the face is lost. A completed hold counts as one unit in the score.

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

The demo of a single movement has two interchangeable renderers behind
`ExerciseDemoView`, and is reachable from the plan editor, each calibration step
and the workout's pause screen:

- `RealityDemoView` plays an animated USDZ through RealityKit's `RealityView`
  in a *virtual* scene — no pass-through camera, so it never competes with the
  `AVCaptureSession` that counts reps, and it needs no camera permission.
  Switching that camera to `.worldTracking` is all that stands between this and
  real AR.
- `StickFigureDemoView` draws the same movement as a looping stick figure from
  the keyframes in `ExerciseDemo.swift`. It needs no assets and is the fallback
  whenever an exercise has no bundled model.

Camera angle follows `ExerciseDemo.perspective`: side-on for push-up, plank and
squat, head-on for the pull-up, whose bar is a dot from the side.

### Rebuilding the models

The USDZ files in `Cindy/Resources/` are generated, not authored by hand. They
come from Mixamo's X Bot character plus one "without skin" animation per
exercise, run through headless Blender:

```
Blender --background --factory-startup --python tools/build_exercise_usdz.py -- \
    --animation "~/Downloads/cindy-mixamo/Push Up.fbx" \
    --character "~/Downloads/cindy-mixamo/X Bot.fbx" \
    --out Cindy/Resources/demo_pushup.usdz --preview /tmp/pushup.png
```

Mixamo has no usable pull-up, so `tools/build_pullup_usdz.py` poses that one
directly on the same rig and adds the bar. Both scripts centre the movement on
the origin and scale it across its whole frame range (`--size`, default 1.7 m),
which is why `RealityDemoView` does no scaling of its own — change one and the
`modelExtent` constant has to follow.

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

Drop recordings into `CindyTests/Fixtures/` and add a case to
`PipelineReplayTests` to turn them into regression tests
(`CSVSignalReplay` parses the logger format).

## Language and appearance

Settings offer **Language** (Automatic / English / Deutsch) and **Appearance**
(Automatic / Light / Dark). Both are stored in `UserDefaults` via `AppModel` and
take effect immediately — no restart.

Source strings are English and act as their own keys. German lives in
`Cindy/Resources/de.lproj/Localizable.strings`; `en.lproj` exists so that picking
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

## Layout

```
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
  Demo/          ExerciseDemo (poses + cues), StickFigureDemoView, RealityDemoView
  Debug/         FrameLogger (CSV), DebugRecorderView
  Resources/     Assets.xcassets, en.lproj + de.lproj (strings, stringsdict, InfoPlist)
  UI/            Start, Onboarding, Settings, Calibration, Workout, Result, History, camera preview, sparkline
CindyTests/      Swift Testing unit tests + CSV fixtures
tools/           analyze_csv.py, check_localization.py, build_exercise_usdz.py, build_pullup_usdz.py
```

## CI and release

`.github/workflows/ci.yml` runs on every push to `main` and every pull request:
it regenerates the project from `project.yml`, checks both string catalogs with
`tools/check_localization.py`, runs the unit tests on whatever iPhone simulator
the runner has, and builds Release once so `#if DEBUG`-only breakage shows up
here. It signs nothing and needs no secrets.

`.github/workflows/release.yml` archives and uploads to TestFlight. Start it by
hand (Actions → Release to TestFlight → Run workflow) or push a `v*` tag; a tag
that disagrees with `MARKETING_VERSION` in `project.yml` fails the run instead
of arriving in App Store Connect as the wrong version. The build number is the
workflow run number, so the same marketing version can be uploaded repeatedly.

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

## Privacy

`Cindy/PrivacyInfo.xcprivacy` declares no tracking, no collected data and the
one required-reason API the app uses: `UserDefaults` (`CA92.1`, its own
defaults). The release workflow asserts the manifest is actually inside the
archived `.app`, because it is included implicitly by living under the sources
path and would otherwise disappear silently.

`PRIVACY.md` is the privacy policy the App Store listing links to. It has to be
reachable under a public URL — keep the published copy in step with this file.
