# Backlog

Open items, roughly in order of priority.

## Gray and red/black (needs paper)

- **Low gray levels print white.** Levels 1–9 leave no mark (densities 3–5, original and coloured
  rolls); neither the rest of the row, hot neighbours nor command `0x59` change that. The
  manufacturer's app still uses levels 4–9 heavily in photos: print the same photo from both apps
  and compare the light tones.
- **Calibrate the 1-bit looks on paper.** *Amiga*, *Amiga fine*, *Blue noise* and *Riemersma* each
  have a chart; print and read them, then print a photo per look: does the tone fit, are there
  stripes?
- **Cross stripes in 1-bit photos.** Open, paused. Facts: not the transfer, the row header, the
  source photo or our row data (stripes on paper do not correlate with coverage, lone dots, pairs or
  row load of the sent rows). The manufacturer's bitmap prints clean through our sending, and in a
  label split between its dither and ours the stripes stay in our half, with nothing visible
  crossing the middle. Likely a small row-wise variation in the printer that only shows in large,
  smooth mid-tone areas, which the calibrated photo look produces and the manufacturer's harder,
  darker dither does not. Debug tools: *Save print bitmap*, *Print bitmap file*.
- **Calibrate on paper.** The in-app calibration (paper profiles) has only been checked on a
  simulated photo. Measure gray and 1-bit on an original and a cheap roll and compare with the
  `tools/calib.py` results.
- **Calibrate red/black.** A chart with separate black and red patches and a curve per ink; the
  profile is already there.
- Which densities suit the gray mode, and does the speed byte matter there (the app sends 0)?
- **Red/black tones.** Red dots print lighter than black ones, so a red band reads lighter than the
  same tones in black. Check on paper whether red areas need more dots. The roll's black is a dark
  red-brown and prints much darker than the preview on photos; it needs its own tone curve.
- Signs in red on two-colour rolls (text or frame).
- What command `0x59` does (seen before gray prints; the printer replies with the label size in
  dots and mm).

- **Depth split on paper.** Print a portrait photo with background *other raster* (Amiga fine /
  Blue noise) and with *red*: does the seam show, does the boundary fit?

## Fixes

- **Memory Film bleed.** Overscan is off for window labels since 1.3.1, but the default 0.6 mm
  bleed still prints onto the frame, which is thermal paper too. Check on a print whether it shows
  and whether the offset fine-tuning makes it unnecessary.

## Print tuning (needs paper)

- The tone target (gamma 1.0) was chosen at density 3 with one photo in 1-bit; check it at
  densities 1 and 5 and in the gray mode.
- The shadow end of the measured curves comes from phone photos and is unreliable above ~90 %
  darkness; a flatbed scan of the calibration charts would fix that.
- Calibrate halftone dots and scanlines the same way; they still use the old dot-gain formula.
- Print the raster looks on real rolls and check tone. Line screen, Macintosh, Amiga and fax skip
  dot-gain compensation on the assumption that blocks and thick lines print true; they may need a
  correction of their own.
- TV scanlines cannot go darker than about two thirds coverage. Check whether a 5 + 1 band (one
  white row) still shows visible scanlines after dot spread.
- The soft-edge fade width (12 % of the shorter side) is set from screen previews only.

## Ideas

- 1:1 preview zoom (tap the preview) so line screen and scanlines can be judged without moiré from
  the on-screen scaling.
- Remember the invert switch like the other image settings.
- Test chart: regenerate automatically when the label changes while the chart is loaded.
