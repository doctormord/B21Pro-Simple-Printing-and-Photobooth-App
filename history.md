# History

How the app got to its current state, with the reasoning behind the less obvious decisions. Newest
first. Measurements come from prints on a B21 Pro and from an offline Python replica of the image
pipeline.

## 1.4.1 (2026-10-27)

### Gray chart as bands
The gray calibration chart had the 16 levels as a 4 × 4 grid. On paper the last patch of each row
came out a step too dark and the first patch of the next row barely darker than it, on two different
rolls: the right side of the head prints darker. The profile then treated some levels as equal and
others as far apart, and the mid tones of photos lost detail. The chart now prints one band per
level across the whole width ("CAL 16B"), so each level is averaged over the head. The paper white
comes from the margins left and right of each band, and the bands sit only 3 dots apart, so even a
30 mm label fits 16 bands of 0.8 mm. Charts of the old layout can no longer be read.

### Gray load test
Many dots heating at once pull the printer's supply down, and the printer makes up for it with a
longer pulse for level planes that hold many dots. In the gray mode that extra time is about as long
as the plane's own, and on the original firmware it is still too short: a tone prints lighter where
much of the row shares its levels. On a third-party roll a level-7 patch printed 52 % darker than
paper alone and 36 % next to a full-width band of the same level. Calibration cannot fix this, since
the same level prints differently depending on the rest of the row. The debug console's *Gray load
test* prints a light, a mid and a dark level as a patch alone, with the same level running on over
half the width and over the whole width, to compare printer firmware: with the load compensation
doubled (a patched firmware, released separately from the app) the mid and dark patches print alike.

### Calibration dialog
The photo buttons read *Camera* and *Gallery* with icons; "Foto aufnehmen" broke over three lines on
phones. The debug console has two buttons per row.

## 1.4.0 (2026-10-26)

### Gray mode
The B21 Pro reports a 16-level gray mode among its capabilities, and gray prints from the
manufacturer's app showed smooth tones with a fine line texture where ours were dithered dots. The
row format was worked out from the printer's Bluetooth traffic while printing on our own printer
(Android HCI snoop log; on Samsung phones it only starts after a reboot). The printer accepts the
same packets over classic Bluetooth and BLE, so our BLE connection works for the gray mode too.

- The print start carries the colour mode (3 = gray), gray rows come in a new wrapper command with
  a 16-bit length, 4 bits per dot, with a simple run-length scheme. The format was confirmed by
  rebuilding three recorded prints from the packets (a narrow photo, a 40 × 40 label and a
  full-width 992-row photo); the last one settled how positions past byte 255 are coded (the low bit
  of the run count is the ninth position bit).
- The first test prints from our app lost rows: the gray mode prints slower than BLE delivers, and
  the printer silently drops rows once its buffer (508 rows) is full, while still reporting the job
  as done. The status reply tells how many rows are free; the app now waits for room before each
  block of 100 rows.
- Gray wedge on paper: on original rolls all 16 levels show; on a cheap roll the lightest seven
  printed white. A measured curve per paper is still to come.

*Fine* now means this gray mode for photos; the old fine (1-bit, slow speed) is *Coarse*. The old
coarse, the printer's fast speed, is gone with its measured tables: it printed dots too large and
gave photos a wormy texture. Other raster looks and signs stay 1-bit in both modes.

### Red and black
On two-colour rolls the printer takes two 1-bit planes. Worked out the same way: one command per row,
plane 1 for red-only rows, 2 for black-only rows, 3 for rows with both (a bitmap of all dots plus a
mask byte per used byte that marks the red ones). The first attempt sent two commands for mixed rows;
the printer stops taking rows as soon as a row number repeats. A dot is red or black, never both.

On paper a red dot next to a black one turns black (the heat spreads), so a 1-dot red/black
checkerboard prints as a dark, almost black area, while 8-dot stripes stay clean. Photos therefore
get colour areas, not colour dots: a band of image tones (a range slider) prints red, the rest black,
decided on a slightly blurred image, with soft band edges blended in 2 × 2-dot cells, similar to
split toning. The roll itself cannot print a neutral black: its black is a dark red-brown and the
base is cream, so the preview shows those two colours (taken from a photo of a print) instead of
black on white.

### Brightness and contrast
Both sliders felt touchy. Brightness was an offset added to every tone, contrast a linear stretch,
both clipping at black and white. On a photo with a large light sky, five steps of brightness pushed
the whole sky to paper white: measured on the preview, steps −5 → 0 → +5 changed the mean tone
three times as much as −20 → −5. In red/black mode this also moved whole areas between red and
black. Both are now curves with fixed end points (brightness: a power curve on the mid-tones,
contrast: an S-curve), and the preview changes evenly across the slider. With fixed end points,
though, an off-white background could no longer be pushed to paper white and kept a sprinkle of
dots; raising brightness or contrast now also clips the lightest tones (up to 15 % of the range).

### Paper profiles and calibration
Cheap rolls print very differently from original ones (in gray, the lightest seven levels printed
white), and the red/black roll prints much darker than its preview, so one built-in curve cannot fit
all papers. Measuring used to mean printing the chart, photographing it and running a Python script
on a computer. The app now does that itself: a calibration dialog prints a chart in the current mode,
reads a photo of it (a Kotlin port of `tools/calib.py`, without OpenCV) and stores the measured curve
in a paper profile. The gray chart is new: 16 patches of the levels 0..15.

Profiles are matched to rolls by the barcode in their RFID tag, read when the printer connects; for
rolls without a tag the profile is chosen by hand. The reader was checked on a simulated photo
(perspective, turned by 90°, light falloff, blur, noise, a known curve with two equal levels): it
returned the curve to within rounding.

Then the first gray calibration on paper: on an original roll at density 3 the levels 1–9 printed
nothing at all, 10 was 14 % dark, and only six levels were usable. A test print showed that this
does not depend on the rest of the row, on hot neighbouring dots or on command `0x59` (which the
manufacturer's app sends before gray prints), and densities 4 and 5 moved the first visible level by
one at most. The gray mode had spread photo tones evenly over all 16 levels, so everything lighter
than about mid-grey printed white. It now uses the measured levels by default and treats 1–9 as
paper.

The same tag also sets the *Black + red roll* switch: a two-colour roll printed in gray or 1-bit came
out a faint red.

### More 1-bit looks
After https://surma.dev/things/ditherpunk/: *Blue noise*, a threshold map made once with
void-and-cluster (fixed pattern per tone like Bayer, so it calibrates as exactly, but no grid), and
*Riemersma*, error diffusion along a Hilbert curve. Both calibratable; the calibration chart from
the debug console now follows look, label and density instead of staying as first loaded (it was
scaled to a new label and kept the photo dither).

### Depth: subject and background
Samsung portrait photos carry a depth map (see README, *Depth map*). Instead of a separate "depth"
raster, the image settings get a *Background* choice that only shows for such photos: lighter,
darker, another raster, or red on two-colour rolls. Tried on the computer first with one portrait
(Otsu split of the depth histogram as the default boundary, a soft step of 8 % of the depth range
at the edge). A background in another raster is the same photo prepared and dithered again with
that raster's own tone correction; the two are mixed along the boundary with blue noise, so the
seam has no outline. No ML libraries: the map comes from the camera.

### Other
- Coarse *Amiga* is calibratable. Uncorrected, its preview looked muddy: the simulated dot spread
  filled the gaps of the 4 × 4 pattern with grey, and faces came out dark. The photo curve does not
  suit 2 × 2 blocks, so without its own curve it stays uncorrected.
- After a theme change or rotation the depth controls disappeared while the effect stayed: the
  photo was restored after the controls had been shown.
- Red/black preview: mixed the inks by plain dot coverage, so tone-corrected photos (fewer dots)
  looked grey; it now takes the darkness from the print curve like the 1-bit preview.
- Camera and gallery are icon buttons beside the preview; the position fine-tuning folds away;
  soft edge sits with invert; brightness and contrast show their values; image favourites.
- *Amiga fine*: the Amiga cross-hatch on single dots (8 × 8 Bayer, 65 tones) instead of 2 × 2 cells,
  with the measured tone correction of the photo look, because single dots merge on paper.
- Cross stripes in 1-bit photo prints that the manufacturer's app did not have. First suspected:
  the printer running dry between BLE writes, so rows went out several per write. The stripes
  stayed, and a sparse row test showed no lost rows. Then the tone correction: every built-in table
  has two steps measured equally dark, so it jumped 11 dither levels between photo tones 59 and 60
  and could turn faint banding in a photo into stripes; equal steps are now bridged (largest jump
  2.6). The photo itself turned out clean when zoomed in, and the stripes stayed exactly in place
  whatever the sending speed, also with 1-bit prints waiting for buffer room before each block of
  50 rows (the status then counted down from 1999 by 50 per block, so in 1-bit mode it is not the
  free buffer). Fixed places for any timing point to the row data itself. Switching the dot count
  in the row header between a few guessed formats did not help either. A Bluetooth capture of the
  manufacturer's app printing the same photo finally showed three differences: colour mode 2 in
  the print start (we sent 0), rows only as wide as the label (61 bytes for 484 dots; we padded
  every row to 591 dots), and three dot counts per row, one per 24 bytes. Our 74-byte rows had
  carried the total in one field instead, which the printer presumably read as three parts. It
  also sends the whole label in under a second without waiting. 1-bit prints now follow it in all
  three points; colour mode 2 printed noticeably lighter and the stripes darker. Claiming every
  row full or empty changed nothing either, so the header counts are not the cause, and 1-bit went
  back to the earlier form (colour mode 0, rows padded to the head width). A dither sweep (four 1-bit
  variants side by side on one label, since removed) tested the next suspect, the dither itself. On it, the two stripes with the measured tone correction (Atkinson and
  Floyd-Steinberg) showed the cross stripes, Floyd-Steinberg without correction and Fax did not.
  The correction lifts the mid-tones until they are fields of isolated dots, and on this paper a
  single dot sits right at the threshold (a 1-dot line nearly vanished in the row test), so small
  changes in heat from row to row switch whole rows of them on or off. Making uncorrected
  Floyd-Steinberg the photo look (beta23) printed photos far too dark and muddy, so the photo look
  went back to corrected Atkinson. The stripes are still open. Dot pairs (2 × 2 cells with 0, 2, 3
  or 4 dots) removed lone dots but showed a herringbone texture and were dropped. Next try:
  Amiga fine, whose ordered pattern gives each tone one fixed pattern, so a calibration chart in
  that pattern measures exactly what a photo prints. Each look now has its own curve. Calibrated this way, Amiga fine printed a photo with smooth tones
  and no visible stripes. Its preview showed bands that the paper does not have: the ImageView
  enlarged it by a non-integer factor with bilinear sampling. The preview is now enlarged by a whole
  factor without filtering and area-averaged to screen size.
  Last checks on the stripes: the manufacturer's bitmap, printed through our sending (*Print bitmap
  file*), came out clean; a row-by-row comparison of a striped print with the exact rows we had sent
  found no relation to anything in those rows; and in a label split between the two dithers the
  stripes stayed in our half. Paused there: the photo look stays as it is.
- NB838 photo frames (54 × 84 mm, rose, silver and pink frames) as a label preset; the window was
  measured from the product diagram.
- The tone target (gamma) is a setting in the debug console; the tone sweep centres on it.
- Unit tests decode the gray and red/black rows with independent decoders of the row format.

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
