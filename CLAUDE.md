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
There are no unit tests; changes are checked in the emulator and on paper.

## Layout

- `app/src/main/java/de/b21print/`: all code, one package. `ImageProcessor.kt` holds the whole
  photo pipeline, `MainActivity.kt` the main screen including the debug console.
- `res/layout/activity_main.xml` and `res/layout-w840dp/activity_main.xml` are two copies of the
  main screen (single column / tablet landscape). UI changes go into both.
- Strings exist in `values/` (English) and `values-de/` (German). Keep both in step.
- The debug console UI is built in code with hard-coded English strings; that is intentional.
- `tools/mkchart.py`: test chart generator; `TestChart.kt` is its Kotlin port. Keep them in sync.

## Image pipeline (ImageProcessor.render)

1. Scale the photo into the photo area at print resolution (300 dpi, 591-dot head):
   area-average downscale with a small pre-blur.
2. Soft edge, if enabled.
3. `prepare`: auto levels (1 % black point, 99.9 % white point), sharpening, brightness/contrast,
   dot-gain compensation (only for looks made of single dots).
4. `toBits`: the raster look, at print resolution. Nothing is scaled after this point.
5. `composePreview`: preview bitmap with simulated dot spread; only this is scaled on screen.

Things that look like bugs but are deliberate:
- Atkinson passes on the full error for light tones (`atkinson`). Plain Atkinson cannot put a dot
  into any flat tone above ~223. Do not reintroduce a white-point headroom to fix highlights.
- Round labels and Memory Film print past the edge (bleed), round labels are not masked.

## Conventions

- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for every APK handed out.
- Release APKs are debug-signed (`B21Print-<version>.apk`, attached to a GitHub release).
- Comments explain why, not what; match the existing density.
- Settings that should survive a restart go into the `prefs` SharedPreferences.

## Checking changes in the emulator

AVDs `b21test` (phone) and `b21tablet` (tablet, natural orientation landscape: user rotation 0 =
landscape). `adb shell uiautomator dump` plus `input tap/swipe` drives the app; a photo pushed to
`/sdcard/Pictures` and a media scan make it available in the gallery picker. Without a printer the
debug console still offers the test chart, which is the quickest way to see a change.

The preview is a downscaled copy of the print, so judge fine patterns from a zoomed crop, not from
the whole screen.
