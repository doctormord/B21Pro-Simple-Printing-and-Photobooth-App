# Backlog

Open items, roughly in order of priority.

## Fixes

- **Memory Film bleed.** Overscan is off for window labels since 1.3.1, but the default 0.6 mm
  bleed still prints onto the frame, which is thermal paper too. Check on a print whether it shows
  and whether the offset fine-tuning makes it unnecessary.
- **Fast speed for photos.** Tones are corrected since 1.3.6, but the texture stays coarse. Decide
  after a print whether photos and the booth should always use fine.

## Print tuning (needs paper)

- Fast speed densities 2 and 4 are interpolated from 1, 3 and 5.
- The tone target (gamma 1.0) was chosen at density 3 with one photo; check it at densities 1 and 5.
- The shadow end of the measured curves comes from phone photos and is unreliable above ~90 %
  darkness; a flatbed scan of the calibration charts would fix that.
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
