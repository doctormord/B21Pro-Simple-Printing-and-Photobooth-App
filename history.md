# History

How the app got to its current state, with the reasoning behind the less obvious decisions. Newest
first. Measurements come from prints on a B21 Pro and from an offline Python replica of the image
pipeline.

## 1.3 (2026-10-25)

### Raster looks
The three-way raster toggle (photo / dots / text) became a picker with eight looks: photo, halftone
dots, line screen, TV scanlines, Macintosh, Amiga, fax and text/logo. All of them run on the image
after it has been scaled to 300 dpi, so the printer receives exactly the pattern that was computed.

- **Line screen**: one line per 7 dot rows; at each column the line is as thick as the local
  darkness times the pitch.
- **TV scanlines**: four picture rows, two white rows. The first version dithered the full image
  and blanked the gap rows afterwards; the error that diffused into the gaps was lost and the result
  was far too light. The bands are now dithered as one contiguous image and spread out afterwards.
  Tones darker than about one third cannot be reached, since a third of the area is always white.
- **Macintosh, Amiga, fax**: dithered on a coarse grid (4 × 4, 2 × 2 and 2 × 3 dots per cell) and
  scaled back up by repetition. The Macintosh look first used plain Atkinson, which is authentic but
  burns out light tones (see below); it now uses the same highlight fix as the photo look.
- Dot-gain compensation is only applied to looks made of single dots (photo, halftone, scanlines).
  On 4 × 4 cells or thick lines only the edges spread, and full compensation made them too light.

### Soft edge and overscan
- Soft edge fades the photo to white over the outer sixth of its area. Rectangles fade per side and
  the two ramps are multiplied, which gives softly rounded corners; circles fade radially. The fade
  ends at the visible edge (window or rim), not at the bleed.
- Round labels used to be clipped hard at the circle. They now print past the rim like bleed in
  offset printing, so a shifted roll does not leave a white crescent. 0.6 mm by default, 2 mm with
  *Overscan*, because rolls can sit up to about ±2 mm off.

### UI
- Paper type became a choice dialog. The dropdown popup had no room to open above the print bar.
- The print bar is hidden while the keyboard is open, so the text field keeps its space.
- In tablet landscape the preview is refitted when the keyboard closes; before, it stayed at the
  size it had while the keyboard was up.
- The debug console has a *Load test chart* button that works without a printer.

## 1.2 (internal test build)

### Highlights: the real cause was the dither, not the tone curve
After 1.0 the app mapped the brightest tone to 240 instead of 255 ("highlight headroom"),
because light areas printed as blank paper. Prints still burned out, so the headroom was lowered
step by step. A sweep from 255 down to 190 on a shaded sphere showed that the sphere only gets
highlight dots at around 210, and by then every white area, including the paper around the photo,
turns grey.

The cause is Atkinson dithering. It passes on only 6/8 of the quantisation error. For a flat tone
*v*, a run of white dots settles at an effective value of 4·*v* − 765, which stays above the 50 %
threshold for any *v* above about 223. Those tones never get a dot, whatever the tone curve does.

Measured ink coverage on flat patches:

| Tone | Atkinson | Hybrid | Ideal |
|-----:|---------:|-------:|------:|
| 223  | 1.6 %    | 9.5 %  | 12.5 % |
| 230  | 0 %      | 8.5 %  | 9.8 %  |
| 240  | 0 %      | 5.8 %  | 5.9 %  |
| 250  | 0 %      | 2.1 %  | 2.0 %  |
| 255  | 0 %      | 0 %    | 0 %    |

The fix blends from Atkinson (6/8) to full error diffusion (6/6) for source tones between 200 and
235. Mid-tones keep Atkinson's clean look, highlights get the right number of dots, paper white stays
blank. The headroom constant was removed.

### Auto levels
The automatic levels step mapped the 99th percentile to white. On the test image that was the light
grey background (tone 238), which then printed as blank paper, indistinguishable from the white end
of the gradient next to it. The white point is now the 99.9th percentile: only specular glints clip.

### Fine vs. fast
A print series (fine/fast at density 1 and 5) showed that fast mode turns dithered photos into
mottled noise, while text is unaffected at either speed. The app sends identical data in both modes;
only one byte in the print start differs, so this is the printer's behaviour. Use fine for photos.

## After 1.0 (2026-10-25)

- **Dot gain.** At full heat each dot bleeds into its neighbours, so mid-tones printed much darker
  than the screen. Tones are lightened by the inverse of that spread (strength 0.55, confirmed on
  printed charts), and the preview simulates the spread so it matches the paper.
- **Sharpening** was reduced; strong unsharp masking made scaled edges bloom at full heat.
- **Print speed** toggle via the speed byte of the V4 print start.
- **Density** default lowered from 3 to 2; 1–2 suits photos on original rolls.
- **Downscaling.** Bilinear scaling left moiré on fine and diagonal structures. It was replaced by an
  area-average downscaler, then by: halve until within 2× of the target, a small Gaussian blur
  (σ = 0.6 × remaining factor), then an area average. Residual moiré on a near-Nyquist grating:
  bilinear 3.56, area 0.86, blur + area 0.26 (standard deviation on a field that should be flat).
  Microtext stays as legible as before.
- **Test chart generator** `tools/mkchart.py`.
- "Polaroid" was replaced by "Instant" throughout.
- The photo booth flash button became icon-only so the flip-camera button stays on screen on phones.

## 1.0 (2026-10-24)

First release: photos and signs, photo booth, tablet layouts, English and German UI, BLE firmware
flasher. The protocol is based on niimbluelib; the B21 Pro needs the V4 print sequence and a
heartbeat before each job because it drops the first packet after a pause.
