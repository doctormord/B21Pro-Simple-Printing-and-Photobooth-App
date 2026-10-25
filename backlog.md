# Backlog

Open items, roughly in order of priority.

## Gray and red/black (needs paper)

- **Gray tone curve per paper.** The 16 levels are used as if they printed evenly. Measure them per
  roll type (a gray version of the calibration chart, read with `tools/calib.py`), and decide how
  the app knows which paper is loaded: a choice in the UI, or the roll's RFID data.
- Which densities suit the gray mode, and does the speed byte matter there (the app sends 0)?
- **Red/black tones.** Red dots print lighter than black ones, so a red band reads lighter than the
  same tones in black. Check on paper whether red areas need more dots. Black dots in light tones
  show a red tint on two-colour rolls.
- Signs in red on two-colour rolls (text or frame).
- What command `0x59` does (seen before gray prints; the printer replies with the label size in
  dots and mm).

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
