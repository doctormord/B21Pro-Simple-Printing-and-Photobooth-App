#!/usr/bin/env python3
"""Thermal test charts for the NIIMBOT B21 Pro (300 dpi, 591 px / 50 mm print head).

Each chart is rendered at 2x the print resolution for the given label, so it also
exercises the app's downscaling. Load it in photo mode, crop = whole image.

Usage:
    python3 mkchart.py                 # default set of common sizes
    python3 mkchart.py 40x40 50x30     # specific sizes in mm (width x height)
    python3 mkchart.py 40x40 -o out/   # custom output directory
"""
import struct, zlib, math, sys, os

PX_PER_MM = 591 / 50.0          # print head: 11.82 px/mm
SCALE = 2                        # render at 2x print resolution
HEAD_MM = 50.0                   # max printable width

FONT = {
 '0': ".###.|#...#|#..##|#.#.#|##..#|#...#|.###.",
 '1': "..#..|.##..|..#..|..#..|..#..|..#..|.###.",
 '2': ".###.|#...#|....#|...#.|..#..|.#...|#####",
 '3': "#####|...#.|..#..|...#.|....#|#...#|.###.",
 '4': "...#.|..##.|.#.#.|#..#.|#####|...#.|...#.",
 '5': "#####|#....|####.|....#|....#|#...#|.###.",
 '6': "..##.|.#...|#....|####.|#...#|#...#|.###.",
 '7': "#####|....#|...#.|..#..|.#...|.#...|.#...",
 '8': ".###.|#...#|#...#|.###.|#...#|#...#|.###.",
 '9': ".###.|#...#|#...#|.####|....#|...#.|.##..",
 'B': "####.|#...#|#...#|####.|#...#|#...#|####.",
 'M': "#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#",
 'G': ".###.|#...#|#....|#.###|#...#|#...#|.###.",
 'P': "####.|#...#|#...#|####.|#....|#....|#....",
 'R': "####.|#...#|#...#|####.|#.#..|#..#.|#...#",
 'I': ".###.|..#..|..#..|..#..|..#..|..#..|.###.",
 'N': "#...#|##..#|#.#.#|#.#.#|#..##|#...#|#...#",
 'T': "#####|..#..|..#..|..#..|..#..|..#..|..#..",
 ' ': ".....|.....|.....|.....|.....|.....|.....",
}


def draw_chart(W, H):
    buf = bytearray([255]) * (W * H)

    def put(x, y, v):
        if 0 <= x < W and 0 <= y < H:
            buf[y * W + x] = v

    def rect(x0, y0, x1, y1, v):
        x0 = max(0, int(x0)); y0 = max(0, int(y0))
        x1 = min(W, int(x1)); y1 = min(H, int(y1))
        for y in range(y0, y1):
            row = y * W
            for x in range(x0, x1):
                buf[row + x] = v

    def glyph(ch, x, y, s, v=0):
        g = FONT.get(ch)
        if not g:
            return
        for ry, line in enumerate(g.split('|')):
            for rx, c in enumerate(line):
                if c == '#':
                    rect(x + rx * s, y + ry * s, x + rx * s + s, y + ry * s + s, v)

    def text(st, x, y, s):
        for ch in st:
            glyph(ch, x, y, s)
            x += 6 * s

    m = max(5, round(min(W, H) * 0.02))
    # frame
    rect(m, m, W - m, m + 2, 0); rect(m, H - m - 2, W - m, H - m, 0)
    rect(m, m, m + 2, H - m, 0); rect(W - m - 2, m, W - m, H - m, 0)
    # corner marks
    ln = round(min(W, H) * 0.05)
    for (cx, cy, sx, sy) in [(m, m, 1, 1), (W - m, m, -1, 1), (m, H - m, 1, -1), (W - m, H - m, -1, -1)]:
        for i in range(ln):
            put(cx + i * sx, cy + (0 if sy > 0 else -1), 0)
            put(cx + (0 if sx > 0 else -1), cy + i * sy, 0)

    gx = round(W * 0.025)
    L = m + gx
    Rr = W - m - gx
    Wc = Rr - L
    top = m + round(H * 0.015)
    Hc = (H - m - round(H * 0.015)) - top
    fs = max(2, round(min(W, H) / 300))          # label font scale
    lab_h = 7 * fs + 4

    def band(f0, f1):
        return top + round(Hc * f0), top + round(Hc * f1)

    def label(n, x, y):
        glyph(str(n), x, y, fs)

    # Zone 1: tonal step wedge 0..10
    y0, y1 = band(0.00, 0.145)
    label(1, L, y0)
    by0 = y0 + lab_h
    by1 = y1 - lab_h
    steps = 11
    bw = Wc / steps
    for i in range(steps):
        v = round(255 * (1 - i / (steps - 1)))
        rect(L + i * bw, by0, L + (i + 1) * bw, by1, v)
    for i in range(steps):
        t = str(i)
        text(t, int(L + i * bw + bw / 2 - 3 * fs * len(t)), by1 + 2, fs)

    # Zone 2: smooth gradient
    y0, y1 = band(0.17, 0.25)
    label(2, L, y0)
    gy0 = y0 + lab_h
    for x in range(Wc):
        v = 255 - round(255 * x / (Wc - 1))
        rect(L + x, gy0, L + x + 1, y1, v)

    # Zone 3: flat dither patches 10/25/50/75/90 % ink
    y0, y1 = band(0.27, 0.42)
    label(3, L, y0)
    vals = [230, 191, 128, 64, 25]
    pw = Wc / len(vals)
    for i, v in enumerate(vals):
        rect(L + i * pw + 6, y0 + lab_h, L + (i + 1) * pw - 6, y1, v)

    # Zone 4: line resolution H / V / diagonal, pitches 2,4,6,8,10 px
    y0, y1 = band(0.44, 0.60)
    label(4, L, y0)
    ly0 = y0 + lab_h
    third = Wc // 3
    pitches = [2, 4, 6, 8, 10]
    rows = max(1, (y1 - ly0) // len(pitches))
    for pi, p in enumerate(pitches):
        yy0 = ly0 + pi * rows
        yy1 = ly0 + (pi + 1) * rows - 2
        for yy in range(yy0, yy1):
            if ((yy - yy0) // p) % 2 == 0:
                rect(L, yy, L + third - 8, yy + 1, 0)
        for xx in range(L + third, L + 2 * third - 8):
            if ((xx - (L + third)) // p) % 2 == 0:
                rect(xx, yy0, xx + 1, yy1, 0)
        for yy in range(yy0, yy1):
            for xx in range(L + 2 * third, Rr):
                if (((xx + yy) // p) % 2) == 0:
                    put(xx, yy, 0)

    # Zone 5: checkerboards, cells 1,2,3,4,6,8
    y0, y1 = band(0.62, 0.75)
    label(5, L, y0)
    cells = [1, 2, 3, 4, 6, 8]
    cw = Wc / len(cells)
    for ci, cs in enumerate(cells):
        cx0 = int(L + ci * cw + 6)
        cx1 = int(L + (ci + 1) * cw - 6)
        for yy in range(y0 + lab_h, y1):
            for xx in range(cx0, cx1):
                if (((xx - cx0) // cs) + ((yy - (y0 + lab_h)) // cs)) % 2 == 0:
                    put(xx, yy, 0)

    # Zone 6: Siemens star + concentric rings
    y0, y1 = band(0.77, 0.93)
    label(6, L, y0)
    sy0 = y0 + lab_h
    sr = min((y1 - sy0) // 2, Wc // 6)
    cy = (sy0 + y1) // 2
    scx = L + sr + 10
    spokes = 36
    for yy in range(cy - sr, cy + sr):
        for xx in range(scx - sr, scx + sr):
            dx = xx - scx; dy = yy - cy
            if dx * dx + dy * dy <= sr * sr:
                if int((math.atan2(dy, dx) / math.pi) * spokes) % 2 == 0:
                    put(xx, yy, 0)
    rcx = Rr - sr - 10
    for yy in range(cy - sr, cy + sr):
        for xx in range(rcx - sr, rcx + sr):
            d = math.hypot(xx - rcx, yy - cy)
            if d <= sr and int(d // 4) % 2 == 0:
                put(xx, yy, 0)
    for ang in range(0, 360, 15):
        for r in range(sr):
            put(int(rcx + r * math.cos(math.radians(ang))), int(cy + r * math.sin(math.radians(ang))), 0)

    # microtext legibility, 3 sizes (scaled to width)
    y0, y1 = band(0.94, 1.0)
    s1 = max(1, fs - 1)
    msg = "B21 PRINT 0123456789"
    yy = y0
    for s in (s1, s1 + 1, s1 + 2):
        if yy + 7 * s <= y1 and L + len(msg) * 6 * s <= Rr:
            text(msg, L, yy, s)
            yy += 7 * s + 3
    return buf


def write_png(path, W, H, buf):
    def chunk(t, d):
        c = t + d
        return struct.pack('>I', len(d)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)
    raw = bytearray()
    for y in range(H):
        raw.append(0)
        raw += buf[y * W:(y + 1) * W]
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 0, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)


def px(w_mm, h_mm):
    w = round(min(w_mm, HEAD_MM) * PX_PER_MM * SCALE)
    h = round(h_mm * PX_PER_MM * SCALE)
    return w, h


DEFAULTS = ["40x40", "50x50", "50x30", "40x30", "30x20", "54x67", "50x70"]


def main():
    args = sys.argv[1:]
    out = "."
    if "-o" in args:
        i = args.index("-o")
        out = args[i + 1]
        args = args[:i] + args[i + 2:]
    sizes = args or DEFAULTS
    os.makedirs(out, exist_ok=True)
    for sz in sizes:
        w_mm, h_mm = (float(v) for v in sz.lower().split("x"))
        W, H = px(w_mm, h_mm)
        buf = draw_chart(W, H)
        name = "B21-testchart-%gx%g.png" % (w_mm, h_mm)
        write_png(os.path.join(out, name), W, H, buf)
        print("wrote %s  (%d x %d px)" % (name, W, H))


if __name__ == "__main__":
    main()
