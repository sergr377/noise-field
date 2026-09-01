# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

`noise-field` is a single-user Android app (Kotlin + Jetpack Compose) for field
noise measurement: it records a sound level measurement with GPS coordinates and
a manual vehicle count, and exports CSV for external validation of the acoustic
model of the `noise-map` project. There is no backend, no map, no accounts.

Two documents govern the code:

- `noise-field-spec.md` — the **contract**. Written in Russian, split into stages
  §0–§8. Read it before changing measurement, storage, or export behaviour.
- `screens.html` — the UI mockups the four screens are built from.

`README.md` records decisions that were already made deliberately; check it
before "fixing" something that looks wrong (e.g. the A-weighting tolerance at
8 kHz).

## Language convention

Spec, README, UI strings, KDoc, and inline comments are **Russian**. Keep writing
them in Russian — do not translate existing comments or switch new ones to
English. Identifiers stay English, as they already are.

Comments frequently cite spec sections (`§2.3`, `§8`). Keep that habit when
adding code that implements a spec requirement.

## Build and test

JDK **17 or 21** is required — AGP 8.7 does not run on JDK 25.

The Gradle wrapper is committed and pinned to Gradle 8.9 — no separate Gradle
install is needed. `gradlew` runs on whatever `JAVA_HOME` points at, and it must
be a JDK 17 or 21. Recent Android Studio ships Java 25 as its own runtime, which
this build cannot use; Studio downloads a separate `jbr-21` into `~/.jdks` and
runs Gradle sync on that.

`android.overridePathCheck=true` in `gradle.properties` is deliberate: it lets
the checkout sit under a non-ASCII path, which AGP otherwise refuses.

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:connectedDebugAndroidTest
```

Unit tests (`app/src/test`) cover the audio tract, CSV builder, and data
encoding, and need no device. `NoiseDbTest` (`app/src/androidTest`) writes and
reads back all three entities and needs a device or emulator; it does not run in
CI. Nothing verifies schema migrations — the committed `app/schemas` makes such
a test possible, but none is written, so a bumped `NoiseDb.version` is covered
by nothing.

## Stack

Kotlin 2.0.21, AGP 8.7.3, KSP, Compose (BOM 2024.10.01) + Material3, Navigation
Compose, Room 2.6.1. `namespace`/`applicationId` = `dev.noisefield`,
`minSdk` 26, `compileSdk`/`targetSdk` 35, JVM target 17. Dependencies live in
`gradle/libs.versions.toml` — add versions there, not inline in `build.gradle.kts`.

**No third-party DSP or audio libraries.** The A-weighting filter, the octave
bank, and the level arithmetic are written by hand on purpose (§0, §2).

## Layout

| Path | Contents |
|---|---|
| `data/` | Room entities (`Calibration`, `Measurement`, `VehicleEvent`), DAOs, `Repository`, `JsonCodec`, `PointIds` |
| `audio/` | `AWeighting`, `OctaveBank`, `LevelMath`, `NoiseTract` (AudioRecord capture) |
| `session/` | `CaptureService` (foreground), `LocationWatcher` (bare `LocationManager`), `CaptureBus` (live state) |
| `export/` | `CsvBuilder` (four flat tables), `Exporter` (`ACTION_SEND_MULTIPLE` via FileProvider) |
| `ui/` | `trip` (start screen), `measure`, `point`, `calibration`, plus `common` and `theme` |

`Graph.kt` is a hand-written service locator (`database`, `repository`,
`exporter`), initialised from `NoiseFieldApp` and `MainActivity`. There is no DI
framework and none should be added. Navigation routes live in `Routes` in
`MainActivity.kt`; the start destination is `trip`.

`CaptureBus` is a global `StateFlow` shared between `CaptureService` and the
screens — deliberately, instead of service binding. ViewModels read it and write
through `Repository`.

## Invariants — do not break silently (§8)

These are places where "convenient" and "correct" diverge. Changing any of them
requires an explicit decision, not a refactor:

- **Levels are averaged by energy**, never as an arithmetic mean of decibels
  (`LevelMath.energyAverage`). The dB-mean is the classic bug: it produces
  plausible, systematically low numbers.
- **Filter state is never reset between buffers.** One `AWeightingFilter` and one
  `OctaveBank` per measurement, created in `NoiseTract.start`.
- **A-weighting poles are not tuned to pass the test.** The bilinear transform
  puts 8 kHz about 0.7 dB low at 44100 Hz; the test tolerance widens at the band
  edges instead (±0.5 dB from 63 Hz–4 kHz, ±1.5 dB at 8 kHz, ±2 dB at 31.5 Hz).
- **The per-second series is always stored**, and so are the octave bands —
  audio is never recorded, so a spectrum cannot be recovered afterwards.
- **Audio is never saved or transmitted in any form.** Only levels leave the app.
- **Flags never discard data.** `clip`, `gps_poor`, `short`, `wind` are written
  into `flagsJson` and shown; the reject decision belongs to post-processing.
- **The microphone and audio source are fixed** and recorded in the calibration;
  the offset is bound to that specific microphone.
- **Stored levels already include `offsetDb`.** `NoiseTract` emits raw levels;
  the offset is applied in the session layer (the calibration screen measures raw
  by design). The raw value is recovered by subtracting the linked calibration's
  offset.
- **Sample rate is 44100 Hz.** The A-weighting coefficients are computed for it;
  `NoiseTract.open` throws rather than substituting another rate.
- **`id` is surrogate, `point_id` is not unique.** Repeating a `point_id` across
  days is a required part of the dataset (it establishes the achievable accuracy
  floor). All cross-file joins go through the surrogate `id`; never warn about
  duplicate `point_id`.
- **Light and heavy vehicle counts stay separate.** The sum is derivable, the
  split is not.
- Coordinates: **median** of fixes better than 10 m; `gps_accuracy_m` from the
  **best** fix; `gps_poor` from the **worst** fix.
- Measurement is not allowed without an active calibration.

## CSV export contract (§6)

Four files: `measurements.csv`, `series.csv`, `vehicles.csv`, `octaves.csv`.
Headers are fixed literals in `CsvBuilder` and are consumed by an external Node
script — treat a header change as a breaking change. Comma separator, dot
decimal, UTF-8, `\n` line ending, RFC 4180 escaping. `datetime_iso` is local time
with offset; `t_sec` is zero-based and labels the block `[t_sec, t_sec+1)`;
`heavy` is written as `0`/`1`; octave levels are **unweighted** with the offset
applied; `mic_height_m` is the constant 1.5.

## Scope (§0)

Anything outside the spec is a regression. Do not add, propose, or "prepare for":
backend or sync, accounts, an in-app map, crowdsourcing or device-offset
databases, analytics beyond what the spec lists, audio storage, localisation
(Russian UI only), dark theme, tablet or landscape layouts.

The only colour carrying measurement meaning is the level scale in `Palette`
(§3). `Palette.Ok` and `Palette.Warn` exist too, for calibration state and
chips, and are not licence to colour anything else.

Fonts are system fonts on purpose (offline field use); numeric fields are
monospaced with tabular figures so the large `LAeq` does not jitter.

## Notes

- Verification that cannot be automated — a 20-minute recording with the screen
  off, calibration against a class 2 sound level meter, opening the CSV in the
  validation script — is done on a device and is out of reach of the test suite.
