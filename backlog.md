# Backlog

Open items, roughly in order of priority.

## Fixes

- **Memory Film bleed.** Overscan is off for window labels since 1.3.1, but the default 0.6 mm
  bleed still prints onto the frame, which is thermal paper too. Check on a print whether it shows
  and whether the offset fine-tuning makes it unnecessary.
- **Force fine speed for photos.** Fast mode breaks up dithered photos (see history.md). Either
  print photos and the booth at fine regardless of the toggle and keep fast for signs, or show the
  toggle only in sign mode.

## Print tuning (needs paper)

- Measure densities 2, 3 and 4 with the calibration chart; `PrintCurve` interpolates them from 1
  and 5 for now. Fast speed has no curve at all.
- Check the tone target (gamma 1.6 in `PrintCurve`) on real photos; the brightness slider covers
  personal taste, the default should look right at 0.
- Calibrate halftone dots and scanlines the same way; they still use the old dot-gain formula.

- Print the raster looks on real rolls and check tone. Line screen, Macintosh, Amiga and fax skip
  dot-gain compensation on the assumption that coarse cells print true; they may need a weaker
  correction of their own.
- TV scanlines cannot go darker than about two thirds coverage. Check whether a 5 + 1 band (one
  white row) still shows visible scanlines after dot spread.
- The soft-edge fade width (12 % of the shorter side) is set from screen previews only.

## Ideas

- 1:1 preview zoom (tap the preview) so line screen and scanlines can be judged without moiré from
  the on-screen scaling.
- Remember the invert switch like the other image settings.
- Test chart: regenerate automatically when the label changes while the chart is loaded.
