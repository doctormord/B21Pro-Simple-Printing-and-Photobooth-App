# History

How the app got to its current state, with the reasoning behind the less obvious decisions. Newest
first. Measurements come from prints on a B21 Pro and from an offline Python replica of the image
pipeline.

## 1.3.9 (2026-10-25)

- The photo booth button moved from below the preview into the mode bar, between *Photo* and *Sign*,
  in amber. It opens the booth and leaves the current mode selected. Saves a full row on phones.
- *Fast* is now called *Coarse*, which describes what it does to photos.
- The version number is shown next to the title.
- All README screenshots retaken, including the nine raster looks with the measured tone curve.

## 1.3.8 (2026-10-25)

Tone sweep at density 3, brightness and contrast at 0: gamma 1.0 looked best, 1.3–2.0 increasingly
grey. The default tone target is now linear.

## 1.3.7 (2026-10-25)

The first print with the measured curve (1.3.5) came out flat: blacks were a dense grey texture
instead of solid, faces mid-grey. Two reasons. The phone photos used for measuring crush the
shadows, so patches from tone ~100 down all read as solid and the table never used darker dither
inputs; the table is now trusted up to 90 % darkness and continues in a straight line to solid at
input 0. And the gamma-1.6 target was chosen on paper-free reasoning. Rather than guess again, the
debug console got a tone sweep that prints the photo in four stripes with gamma 1.0, 1.3, 1.6 and
2.0.

## 1.3.6 (2026-10-25)

Calibration charts for all fine densities (1–5) and for fast at densities 1, 3 and 5 replace the
interpolated tables. Fine is one smooth family, darker with each density step (1 and 2 nearly
equal). Fast is different in kind: single dots print two to three times darker (tone 234: 0.15–0.20
instead of 0.065), and around 50 % coverage the curve turns back (density 1: tone 149 → 0.72, tone
127 → 0.41). The 50 % pattern alternates dots, and at fast speed those apparently get too little
energy. That reversal is what made photos at fast speed look blotchy. The correction uses the
monotone envelope of each curve, so it never aims into the dip; at fast density 3 it jumps from
dither input 156 straight to 110.

## 1.3.5 (2026-10-25)

### Measured tone curve instead of a dot-gain formula
Mid-tones from about tone 180 down still ran together (test chart steps 7–10 merged). A calibration
chart was added: 25 flat tones, pre-dithered with the photo dither and printed dot for dot, read
back from a phone photo by `tools/calib.py` (corner marks for perspective, white gutters as local
paper reference, sRGB decoded to linear light). Printed darkness on original rolls, fine speed:

| Dither input tone | 255 | 223 | 191 | 170 | 149 | 128 | 106 | 85 |
|-------------------|----:|----:|----:|----:|----:|----:|----:|---:|
| Density 1         | 0   | .08 | .16 | .42 | .68 | .76 | .95 | .99 |
| Density 5         | 0   | .12 | .25 | .59 | .81 | .86 | .98 | 1.0 |

Up to ~25 % coverage single dots print true; then they merge and the curve shoots up, saturating
around tone 110. The old formula (`d − 0.55·d·(1 − d)`) is a gentle parabola and cannot follow the
knee, so it put most mid-tones into the saturated range. The photo look now inverts the measured
table (densities 2–4 interpolated between 1 and 5), with a target of `1 − (v/255)^1.6`, between
linear and sRGB. At density 5 the whole range from mid-grey to black now maps to dither inputs
96–162, at density 1 to 21–150. The preview shades each spot by the measured darkness of its local
dot coverage, so it still matches the paper.

## 1.3.3 (2026-10-25)

### Light tones printed grey
Prints from 1.3.x looked muddier than 1.0.x: faces and white fabric came out grey. Removing the
white-point headroom was not the cause; it made mid-tones lighter (tone 140: 31.8 % → 27.5 % ink).
The difference was in the light tones. Plain Atkinson left everything above ~210 blank, so skin
and highlights printed as bare paper. The hybrid dither puts dots there, but the dot-gain
compensation was still tapered out in the highlights (the taper existed only because plain
Atkinson had no dots there to correct). Modelled printed coverage at tone 235: ideal 7.8 %, old 0 %,
1.3.2 11 %. With the compensation applied across the whole range it is 6.1 %.

### Debug console
Rebuilt: test chart, diagnostics and firmware flash at the top, command field with an inline send
button and preset chips, and a fixed log area with copy and clear buttons. App log lines (e.g.
diagnostics) are mirrored into it while it is open. Without a printer the same console opens with
the printer functions disabled.

## 1.3.2 (2026-10-25)

- Turning on the soft edge made the whole photo darker. The fade was blended into the image before
  the automatic levels step, so the added white pushed the measured white point to 255 and the photo
  was no longer brightened. The fade is now applied after the tone curve.
- Soft edge width reduced from 16 % to 12 % of the shorter side.
- Second scanline look with a 4-row gap. The 2-row gap of the first one prints as about one row
  after dot spread, which is hard to see on a 40 mm label.

## 1.3.1 (2026-10-25)

- Overscan only applies to round labels. On Memory Film the frame is thermal paper as well, so
  printing 2 mm past the window greyed it.

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
  *Overscan*, because rolls can sit up to about ±2 mm off. 1.3 also applied overscan to the Memory
  Film window, which turned out to be a mistake (see 1.3.1).

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
