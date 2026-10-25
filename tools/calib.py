#!/usr/bin/env python3
"""Measure the printer's tone response from a photo of the calibration chart.

Print the chart from the debug console (long-press the title, *Calibration*) on a 40 x 40 mm label,
photograph it in even, indirect light with only that label in view, then:

    python3 calib.py photo.jpg [debug.png]

It finds the four corner squares, undoes the perspective, and reports for each of the 25 patches
how dark it printed (0 = paper, 1 = solid, linear light), using the white gutter around each patch
as the local paper reference. The last column goes into PrintCurve.kt as D<density>, made monotone.
debug.png shows the rectified chart with the sampled areas.

Needs numpy and opencv-python-headless.
"""
import sys
import cv2
import numpy as np

MM = 591 / 50.0
STEPS = 25


def geometry(w, h):
    # Mirror of TestChart.calibration (integer arithmetic as in Kotlin)
    m = int(2.5 * MM); f = int(3 * MM); gut = int(1 * MM)
    left = m + f + gut; right = w - m - f - gut
    top = m + f + gut; bottom = h - m - f - gut
    cw = (right - left - 4 * gut) // 5; ch = (bottom - top - 4 * gut) // 5
    squares = [(m, m), (w - m - f, m), (m, h - m - f), (w - m - f, h - m - f)]
    centers = [(x + f / 2, y + f / 2) for x, y in squares]
    patches = [(left + (i % 5) * (cw + gut), top + (i // 5) * (ch + gut), cw, ch) for i in range(STEPS)]
    return centers, patches, gut


def srgb_to_linear(v):
    v = v / 255.0
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def candidates(gray):
    # Solid dark, roughly square blobs
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    _, th = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    n, lab, stats, cent = cv2.connectedComponentsWithStats(th)
    total = gray.size
    out = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if area < total * 2e-5 or area > total * 0.01 or not 0.6 < w / h < 1.6:
            continue
        if area / (w * h) > 0.8:
            out.append((area, cent[i]))
    return out


def order(pts):
    s = pts.sum(1); d = pts[:, 0] - pts[:, 1]
    return np.array([pts[s.argmin()], pts[d.argmax()], pts[d.argmin()], pts[s.argmax()]], np.float32)


def measure(gray, quad, w, h, S=4):
    centers, patches, gut = geometry(w, h)
    H = cv2.getPerspectiveTransform(quad, np.array(centers, np.float32) * S)
    warp = cv2.warpPerspective(gray, H, (w * S, h * S), flags=cv2.INTER_AREA)
    lin = srgb_to_linear(warp.astype(np.float64))
    res = []
    for x, y, cw, ch in patches:
        p = lin[int((y + ch * .2) * S):int((y + ch * .8) * S), int((x + cw * .2) * S):int((x + cw * .8) * S)].mean()
        g = gut * S
        ring = np.concatenate([
            lin[int(y * S) - g + 2:int(y * S) - 2, int(x * S):int((x + cw) * S)].ravel(),
            lin[int((y + ch) * S) + 2:int((y + ch) * S) + g - 2, int(x * S):int((x + cw) * S)].ravel(),
            lin[int(y * S):int((y + ch) * S), int(x * S) - g + 2:int(x * S) - 2].ravel(),
            lin[int(y * S):int((y + ch) * S), int((x + cw) * S) + 2:int((x + cw) * S) + g - 2].ravel()])
        res.append((p, np.median(ring)))
    return warp, res


def score(res):
    # Patches must get darker along the index and the gutters must be lighter than the patches
    p = np.array([r[0] for r in res]); white = np.array([r[1] for r in res])
    if not np.all(np.isfinite(p)) or not np.all(np.isfinite(white)):
        return -1e9
    rank = np.corrcoef(np.argsort(np.argsort(-p)), np.arange(len(p)))[0, 1]
    return rank + (white.mean() - p[-1]) / max(white.mean(), 1e-6)


def analyse(path, w=473, h=473, debug=None):
    from itertools import combinations
    gray = cv2.cvtColor(cv2.imread(path), cv2.COLOR_BGR2GRAY)
    cand = candidates(gray)
    best = None
    for combo in combinations(cand, 4):
        areas = [c[0] for c in combo]
        if max(areas) > 1.6 * min(areas):
            continue
        quad = order(np.array([c[1] for c in combo], np.float32))
        tl, tr, bl, br = quad
        if np.linalg.norm(tr - tl) < 50 or np.linalg.norm(bl - tl) < 50:
            continue
        _, res = measure(cv2.resize(gray, None, fx=.25, fy=.25), quad * .25, w, h, S=1)
        sc = score(res)
        if best is None or sc > best[0]:
            best = (sc, quad)
    if best is None:
        raise SystemExit("corner squares not found")
    warp, res = measure(gray, best[1], w, h)
    black = res[-1][0]
    out = [(i, 255 * (1 - i / (STEPS - 1)), (white - p) / (white - black)) for i, (p, white) in enumerate(res)]
    if debug:
        _, patches, _ = geometry(w, h)
        dbg = cv2.cvtColor(warp, cv2.COLOR_GRAY2BGR)
        for (x, y, cw, ch) in patches:
            cv2.rectangle(dbg, (int((x + cw * .2) * 4), int((y + ch * .2) * 4)),
                          (int((x + cw * .8) * 4), int((y + ch * .8) * 4)), (0, 0, 255), 2)
        cv2.imwrite(debug, dbg)
    return out


if __name__ == "__main__":
    for i, tone, dark in analyse(sys.argv[1], debug=sys.argv[2] if len(sys.argv) > 2 else None):
        print(f"{i:2d}  tone {tone:5.1f}  printed {dark:5.3f}")
