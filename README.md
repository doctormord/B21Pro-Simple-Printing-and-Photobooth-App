# B21 Print

An Android app for the NIIMBOT B21 Pro label printer. It prints photos and text signs over
Bluetooth, with no account, no cloud catalog and no RFID roll check, and it has a photo booth mode
for tablets.

<p>
  <img src="docs/tablet-landscape.png" width="64%" alt="Tablet, landscape">
  <img src="docs/tablet-portrait.png" width="30%" alt="Tablet, portrait">
</p>

## Features

**Photos**
- Camera, gallery, or *Share* from any other app; crop to the label shape or freely
- Nine raster looks (see below), brightness, contrast, invert
- Soft edge: the photo fades to white towards its border, round on circle labels
- Instant frame with an optional caption, printed by the app on plain labels
- NB835 *Memory Film* labels with a pre-printed frame: only the window is printed, with a
  per-label offset that is remembered
- Round labels and Memory Film print 0.6 mm past the edge so a slightly shifted roll leaves no
  white gap; on round labels *Overscan* raises this to 2 mm

**Signs**
- Short text, centered and sized automatically, 1–3 lines
- Three fonts, bold/italic/underline, five frame styles
- Voice input
- History of the last 50 printed signs, and saved templates

**Photo booth** (the amber *Booth* button between *Photo* and *Sign*)
- Full-screen front camera with the printable area marked
- Countdown (off/3/5/10 s) and screen flash
- Review before printing: delete or print, then print again or take the next photo
- Uses the label, frame, caption, raster and density settings from the main screen
- Volume keys and Bluetooth selfie remotes work as a shutter

**Labels and printing**
- Presets for common B21 sizes (rectangular, square, round, instant style) and a custom size
  (print width up to 50 mm, length up to 200 mm)
- Gap, black mark, continuous and transparent paper; multiple copies
- Print density 1–5 and two print modes, fine and coarse (the printer's slow and fast speed)

**Layout**
- Phone, tablet portrait and a two-column tablet landscape layout
- English and German UI

## Raster looks

The photo is scaled to the printer's 300 dpi first; the look is then applied pixel for pixel and
sent to the printer as is. The on-screen preview is a scaled copy, so fine line patterns can show
moiré there that does not appear on paper.

![Raster looks](docs/raster-looks.png)

| Look           | How it works                                                                 |
|----------------|------------------------------------------------------------------------------|
| Photo          | Atkinson error diffusion with tones corrected by the printer's measured response (see below). Light tones pass on the full error, so highlights keep a few dots instead of burning out, while paper white stays blank. |
| Halftone dots  | Round dots on a 45° screen, about 0.5 mm pitch                               |
| Line screen    | Horizontal lines, 0.6 mm apart; the line thickness carries the tone (engraving style) |
| TV scanlines   | The picture sits in bands of dot rows with a white gap: 4 + 2 rows, or 6 + 4 rows for *wide*. Dot spread closes about one row of the gap, so the wide version is the one that shows on small labels. |
| Macintosh      | Atkinson dithering at 72 dpi, one screen pixel = 4 × 4 printer dots          |
| Amiga          | 4 × 4 Bayer ordered dither on 2 × 2 dot cells, the familiar cross-hatch      |
| Fax            | 150 × 100 dpi cells, close to fax standard resolution, with Floyd–Steinberg diffusion |
| Text/logo      | Hard threshold, for line art and logos                                       |

Close-up, about twice the printed size:

![Raster detail](docs/raster-detail.png)

### Tone correction

Thermal dots spread into their neighbours, and not evenly: measured with the calibration chart,
isolated dots print about as dark as their share of the area, but from roughly 25 % coverage on they
merge, and below tone ~110 everything prints solid black. The photo look therefore corrects tones
with a table measured per print density and speed (`PrintCurve.kt`) instead of a formula: for each photo tone
it picks the dither input that prints the intended darkness, and the preview uses the same
measurement. The intended darkness is linear in the photo tone (tone 128 prints half dark); chosen
from a tone-sweep print, darker targets closer to the screen's sRGB curve looked grey and flat on
thermal paper.

Halftone dots and TV scanlines still use a simple dot-gain formula; the coarse looks print close to
their nominal tone and are not corrected.

## Settings

<p>
  <img src="docs/settings.png" width="49%" alt="Image and print settings">
  <img src="docs/label-options.png" width="49%" alt="Label options with soft edge and overscan">
</p>

- **Raster**: the look, see above.
- **Brightness / Contrast**: applied after an automatic levels step that stretches the photo to
  full range. Only the brightest 0.1 % may clip to white, so light grey stays distinguishable from
  paper white.
- **Invert**: swap black and white.
- **Soft edge**: fade to white over the outer eighth of the photo area. The fade is applied after
  the tone curve, so it does not change the brightness of the rest of the photo.
- **Overscan** (round labels): print 2 mm instead of 0.6 mm past the rim. Memory Film has no
  overscan: its frame is thermal paper too, and print outside the window would grey it.
- **Print density**: heat level sent to the printer. 1–2 suits photos on original rolls.
- **Speed**: *Fine* for photos. *Coarse* is the printer's fast mode: single dots print much larger,
  and dense alternating dot patterns come out lighter than lighter tones; the tone correction works
  around that, but photos keep a coarse, wormy texture. Text prints the same in both modes.
- **Paper type**: how the printer finds the next label (gap, black mark, continuous, transparent).

![Soft edge](docs/soft-edge.png)

Signs use the same label and print settings:

![Sign mode](docs/sign.png)

## Requirements

- Android 9 or newer with Bluetooth LE
- A NIIMBOT B21 Pro (other B21 variants may work, untested)

The app does not check the roll's RFID tag. On stock firmware, rolls without a valid NIIMBOT tag
still print very faint, because the printer firmware itself lowers the heat; the app cannot change
that. With a patched printer firmware they print normally.

## Install

Download the APK from the [releases](../../releases) and install it (allow installing from unknown
sources). Then tap *Connect*; the app scans for printers whose name starts with `B21`.

From 1.3.1 on, release APKs are signed with the project's release key. Certificate SHA-256:

```
d0:03:0d:5d:c9:1c:a5:ae:65:1e:fc:24:09:15:70:18:31:2d:6a:02:6d:2c:f7:10:bc:c4:dc:c1:5f:a8:f6:d2
```

Versions 1.0 and 1.3 were signed with a debug key. Android does not install an update signed with
a different key, so uninstall the old version once before installing 1.3.1. This resets the app's
settings, sign history, templates and Memory Film offsets.

## Build

JDK 17 and the Android SDK (API 34) are required.

```sh
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

### Signing

`./gradlew assembleRelease` signs with the key configured in `~/.gradle/gradle.properties`, or with
the local debug key if there is none. The key never goes into the repository.

To sign with your own key, create it once (keep the file and password safe; without them you
cannot publish updates):

```sh
keytool -genkeypair -keystore ~/.android/b21print-release.jks -alias b21print \
        -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=B21 Print"
```

and add to `~/.gradle/gradle.properties`:

```properties
B21_KEYSTORE=~/.android/b21print-release.jks
B21_KEYSTORE_PASSWORD=...
B21_KEY_ALIAS=b21print
B21_KEY_PASSWORD=...
```

The output is `app/build/outputs/apk/release/app-release.apk`. Android only installs an update if
it is signed with the same key as the installed app, so your own builds and the release APKs
cannot replace each other.

## Debug console

Long-press the *B21 Print* title. With a printer connected, the console sends raw text commands to
the BLE characteristic and shows every reply. It also offers:

- **Test chart**: loads a chart sized to the current photo area (window, instant frame or circle)
  into the preview, as if it were a cropped photo: tone wedge, gradient, dither patches, line and
  checkerboard resolution, Siemens star, rings and microtext. Works without a printer.
- **Printer diagnostics**: queries RFID, anti-fake and status data and writes the raw replies to
  the log at the bottom of the main screen.
- **Calibration**: a 5 × 5 chart of flat tones, already dithered and printed dot for dot without
  any image processing, with the density and speed printed on it. `tools/calib.py` reads a photo of
  it and reports how dark each patch printed; those numbers are the tables in `PrintCurve.kt`.
- **Tone sweep**: prints the current photo in four vertical stripes with different tone targets
  (gamma 1.0, 1.3, 1.6, 2.0, labelled at the top), to choose the target from one print.
- **Log** with copy and clear buttons; it also shows the app's own log lines while open.
- **Flash firmware**: sends a `.bin` firmware image over Bluetooth. The version is taken from the
  file name (e.g. `B21_Pro_3.10.bin` → 3.10). The printer verifies the image before committing it;
  a rejected or interrupted transfer leaves the old firmware running. Do not switch the printer off
  during the update.

<img src="docs/test-chart.png" width="45%" alt="Test chart on a Memory Film label">

`tools/mkchart.py` generates the same chart as a PNG for any label size.

## Protocol notes

- BLE service `e7810a71-73ae-499d-8c15-faa9aef0c3f2`, characteristic
  `bef8d6c9-9c21-4c9e-b632-bd58c1009f9f`
- Packets: `55 55 CMD LEN DATA… XOR AA AA`
- The B21 Pro uses the "V4" print sequence (print start with copies, speed and quality bytes, a
  13-byte page size, no page start). The app falls back to older sequences if the printer rejects
  it and remembers what worked.
- 300 dpi, 591-dot print head (50 mm). Rows are sent as bitmap rows with per-third dot counts;
  identical rows are merged.
- The printer often drops the first packet after a pause, so every job starts with a heartbeat.
- Firmware updates use a separate framing with a CRC-32 instead of the XOR checksum:
  `55 55 CMD chunkHi chunkLo LEN DATA… CRC32 AA AA`, 200-byte chunks.

The protocol work of [niimbluelib](https://github.com/MultiMote/niimbluelib) by MultiMote was the
main reference.

## Code

| File                             | Purpose                                                        |
|----------------------------------|----------------------------------------------------------------|
| `Niimbot.kt`                     | packet encoding and parsing, row encoding                      |
| `B21Printer.kt`                  | BLE connection, print sequence, firmware update                |
| `ImageProcessor.kt`              | scaling, tone curve, dot gain, raster looks, frame and window composition |
| `PrintCurve.kt`                  | measured tone response per density, tone correction table     |
| `TestChart.kt`                   | built-in test chart (port of `tools/mkchart.py`), calibration chart |
| `TextLabel.kt`                   | sign rendering                                                 |
| `Labels.kt`                      | label presets                                                  |
| `MainActivity.kt`                | main screen                                                    |
| `CropActivity.kt`, `CropView.kt` | crop screen                                                    |
| `BoothActivity.kt`               | photo booth                                                    |

[history.md](history.md) records how the image pipeline got where it is, [backlog.md](backlog.md)
lists what is still open.

## Disclaimer

Not affiliated with NIIMBOT, Apple or Commodore; the look names only describe the style. Use at your
own risk, especially the firmware flasher.
