# Backlog

Open items, roughly in order of priority.

## Fixes

- **No overscan on Memory Film.** The frame is printed on the same thermal paper, so anything that
  lands outside the window shows as a grey fringe on it. Hide the *Overscan* switch for window
  labels and ignore the option there; check whether the default 0.6 mm bleed should go as well.
- **Force fine speed for photos.** Fast mode breaks up dithered photos (see history.md). Either
  print photos and the booth at fine regardless of the toggle and keep fast for signs, or show the
  toggle only in sign mode.

## Print tuning (needs paper)

- Print the raster looks on real rolls and check tone. Line screen, Macintosh, Amiga and fax skip
  dot-gain compensation on the assumption that coarse cells print true; they may need a weaker
  correction of their own.
- TV scanlines cannot go darker than about two thirds coverage. Check whether a 5 + 1 band (one
  white row) still shows visible scanlines after dot spread.
- The soft-edge fade width (16 % of the shorter side) is a first guess.

## Ideas

- 1:1 preview zoom (tap the preview) so line screen and scanlines can be judged without moiré from
  the on-screen scaling.
- Remember the invert switch like the other image settings.
- Test chart: regenerate automatically when the label changes while the chart is loaded.
