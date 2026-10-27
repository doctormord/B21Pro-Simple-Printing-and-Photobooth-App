# CLAUDE.md

Android app (Kotlin, no Compose, Material 3 views) for the NIIMBOT B21 Pro thermal label printer.
See README.md for features, history.md for why the image pipeline looks the way it does, and
backlog.md for open work.

## Build

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (not in git) points to the SDK: `sdk.dir=/opt/homebrew/share/android-commandlinetools`.
`./gradlew testDebugUnitTest` runs the few unit tests (`NiimbotTest`: packet and row encoding; the
gray and red/black encoders round-trip through independent decoders of the row format).
Everything else is checked in the emulator and on paper.

## Layout

- `app/src/main/java/de/b21print/`: all code, one package. `ImageProcessor.kt` holds the whole
  photo pipeline, `MainActivity.kt` the main screen including the debug console.
- `res/layout/activity_main.xml` and `res/layout-w840dp/activity_main.xml` are two copies of the
  main screen (single column / tablet landscape). UI changes go into both.
- Strings exist in `values/` (English) and `values-de/` (German). Keep both in step.
- The debug console UI is built in code with hard-coded English strings; that is intentional.
- `tools/mkchart.py`: test chart generator; `TestChart.kt` is its Kotlin port. Keep them in sync.
- `Dither.kt`: blue-noise map (built on first use) and Riemersma; the calibratable 1-bit looks are
  `CAL_LOOKS` in MainActivity, each with its own profile key. `ImageProcessor.MEASURED` are the ones
  corrected even without their own curve (photo curve); coarse Amiga only with one (`measured()`).
- `DepthMap.kt` reads the depth map of Samsung portrait photos; `ImageStore` turns and crops it with
  the photo, `ImageProcessor.subjectMask` lays it over the label. Options.background decides what
  happens to the background (lighter/darker in `prepare`, another raster in `render`, red in `redMask`).
- Paper profiles (`PaperProfile.kt`) hold measured curves per mode and density ("gray3", "mono3"),
  as JSON in the prefs. `ChartReader.kt` reads the calibration charts (`TestChart.calChart`: a patch
  grid, gray as full-width bands) from a photo in the app; `tools/calib.py` mirrors the 5 × 5 layout
  and must stay in sync with it.

## Image pipeline (ImageProcessor.render)

1. Scale the photo into the photo area at print resolution (300 dpi, 591-dot head):
   area-average downscale with a small pre-blur.
2. `prepare`: auto levels (1 % black point, 99.9 % white point), sharpening, brightness/contrast,
   tone correction (gray mode: the gamma target only; 1-bit photo look: measured table from
   `PrintCurve`; halftone/scanlines: dot-gain formula; coarse looks: none), invert, then the
   soft-edge fade. The fade must come after the levels step, or the added white darkens the photo.
3. Rasterise at print resolution, by `Options.ink` (see `inkFor`): GRAY → `toLevels` (16 levels,
   photo look only), otherwise `toBits` (the raster look); RED_BLACK adds `redMask` (which dots are
   red). Nothing is scaled after this point.
4. `composePreview`: preview bitmap with simulated dot spread / gray levels / red; only this is
   scaled on screen.

`B21Printer.printResult` sends a `Result` in its ink. Gray and red/black use their own row commands,
a 576-dot width and block-wise flow control on the status reply; see the protocol notes in README.md.

Things that look like bugs but are deliberate:
- The gray calibration chart is full-width bands, not a grid: the right side of the head prints
  darker, and the levels' heat depends on how many dots share the row (see history.md, 1.4.1).
- Atkinson passes on the full error for light tones (`atkinson`). Plain Atkinson cannot put a dot
  into any flat tone above ~223. Do not reintroduce a white-point headroom to fix highlights.
- The photo look's tone correction is a measured table, not a formula: the printer has a sharp
  knee at ~25 % coverage. Without it photos print far too dark (tried in 1.4.0-beta23). To
  re-measure, print the calibration chart (debug console), photograph it and run `tools/calib.py`;
  the result goes into `PrintCurve.kt` (one table per density).
- Round labels and Memory Film print past the edge (bleed), round labels are not masked.
- Red/black: the colour is chosen per area (blurred tone, 2 × 2-dot cells), not per dot. A red dot
  next to a black one prints black.
- The colour modes must wait for room in the printer buffer before each block of rows; without it
  the printer drops rows and still reports the job as done.

## Conventions

- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for every APK handed out.
- Release APKs come from `./gradlew assembleRelease` (`B21Print-<version>.apk`, attached to a
  GitHub release). Signing is configured outside the repo in `~/.gradle/gradle.properties`
  (`B21_KEYSTORE`, `B21_KEYSTORE_PASSWORD`, `B21_KEY_ALIAS`, `B21_KEY_PASSWORD`); without it the
  debug key is used. Never commit a keystore or passwords, and check that no personal names end up
  in commits, files or certificates.
- Comments explain why, not what; match the existing density.
- Settings that should survive a restart go into the `prefs` SharedPreferences.

## Checking changes in the emulator

AVDs `b21test` (phone) and `b21tablet` (tablet, natural orientation landscape: user rotation 0 =
landscape). `adb shell uiautomator dump` plus `input tap/swipe` drives the app; a photo pushed to
`/sdcard/Pictures` and a media scan make it available in the gallery picker. Without a printer the
debug console still offers the test chart, which is the quickest way to see a change.

Bluetooth traffic of a real phone: enable the HCI snoop log in
the developer options (Samsung: then reboot), print, `adb bugreport`, and read
`FS/data/log/bt/btsnoop_hci.log` from the zip.

The preview is a downscaled copy of the print, so judge fine patterns from a zoomed crop, not from
the whole screen.
