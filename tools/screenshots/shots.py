#!/usr/bin/env python3
"""Screenshots for the README, taken in the emulator and cut to the parts that show a feature.

    python3 tools/screenshots/shots.py                    # all scenes
    python3 tools/screenshots/shots.py --only booth,sign  # some scenes
    python3 tools/screenshots/shots.py --list

What it does, so a UI change costs no manual work: the emulator is set to the display of the Galaxy S25
(1080 x 2340, 480 dpi), the app gets its settings from a preferences file, is driven through the
accessibility tree (views are found by resource id or text, never by pixel position) and every
screenshot is cut to the union of the views named in the scene. A moved or resized view moves the
crop with it. A new feature is a new scene function (see SCENES below) and, if it needs a view the
layout has no id for, an `android:id` in the layout.

Needs: adb, ffmpeg, a running emulator (any API 30+ image) and the debug APK (run-as writes the
preferences). Photos come from the emulator's camera through the photo booth, so no picture is
stored in the repository. No printer is needed; what only a printer can show (battery, prints) is
left out or faked with files in the app's storage.
"""
import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import gzip
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PKG = "de.b21print"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
OUT = ROOT / "docs"
SIZE, DENSITY = "1080x2340", "480"   # Galaxy S25
CUR = {"size": SIZE}

ADB = shutil.which("adb") or str(Path(os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")) / "platform-tools/adb")
SERIAL: list = []
TMP = Path(tempfile.mkdtemp(prefix="b21shots-"))
NIGHT = False
THUMBS: dict = {}       # label images cropped out of earlier scenes, for the "recent prints" fixtures
FULL: dict = {}         # full screens, for the overview montage


# ---------- adb ----------

def adb(*args, input=None, check=True) -> bytes:
    r = subprocess.run([ADB, *SERIAL, *args], input=input, capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace')}")
    return r.stdout


def sh(cmd: str, **kw) -> str:
    return adb("shell", cmd, **kw).decode(errors="replace")


# ---------- views ----------

class Node:
    def __init__(self, rid, text, desc, b):
        self.rid, self.text, self.desc, self.b = rid, text, desc, b

    @property
    def center(self):
        return (self.b[0] + self.b[2]) // 2, (self.b[1] + self.b[3]) // 2


def dump() -> list:
    for _ in range(5):
        sh("uiautomator dump /sdcard/ui.xml")
        data = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
        try:
            root = ET.fromstring(data)
            break
        except ET.ParseError:
            time.sleep(1)
    else:
        raise RuntimeError("no UI dump")
    out = []
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m:
            continue
        rid = n.get("resource-id", "").split("/")[-1]
        out.append(Node(rid, n.get("text", ""), n.get("content-desc", ""), tuple(map(int, m.groups()))))
    return out


def find(nodes, sel):
    """sel: a resource id, or 'text:Some text' (substring, case-insensitive)."""
    for n in nodes:
        if sel.startswith("text:"):
            if sel[5:].lower() in n.text.lower() and n.text:
                return n
        elif n.rid == sel:
            return n
    return None


def wait_for(sel, timeout=40):
    end = time.time() + timeout
    while time.time() < end:
        n = find(dump(), sel)
        if n:
            return n
        time.sleep(1)
    raise RuntimeError(f"{sel} did not show up")


def tap(sel, timeout=20):
    n = wait_for(sel, timeout)
    sh(f"input tap {n.center[0]} {n.center[1]}")
    time.sleep(0.8)


def tap_slider(sel, frac):
    n = scroll_to(sel)
    x = n.b[0] + int((n.b[2] - n.b[0]) * (0.08 + 0.84 * frac))
    sh(f"input tap {x} {n.center[1]}")
    time.sleep(0.5)


def long_press(sel):
    n = wait_for(sel)
    x, y = n.center
    sh(f"input swipe {x} {y} {x} {y} 1500")
    time.sleep(1.5)


def scroll_to(sel, area="scroll"):
    """Scrolls the main screen until the view is inside the scrolling area."""
    for step in range(14):
        nodes = dump()
        a, n = find(nodes, area), find(nodes, sel)
        if a and n and n.b[1] >= a.b[1] and n.b[3] <= a.b[3] + 4:
            return n
        if not a:
            raise RuntimeError("no scroll area")
        x = (a.b[0] + a.b[2]) // 2
        up = step < 8   # the view may be above: after eight tries go back up
        y0, y1 = (a.b[3] - 90, a.b[1] + 90) if up else (a.b[1] + 90, a.b[3] - 90)
        sh(f"input swipe {x} {y0} {x} {y1} 350")
        time.sleep(0.6)
    raise RuntimeError(f"{sel} not reachable")


def scroll_top():
    for _ in range(4):
        a = find(dump(), "scroll")
        if not a:
            return
        x = (a.b[0] + a.b[2]) // 2
        sh(f"input swipe {x} {a.b[1] + 90} {x} {a.b[3] - 90} 300")
        time.sleep(0.4)


def type_text(sel, text):
    tap(sel)
    sh("input text " + text.replace(" ", "%s"))
    time.sleep(0.5)
    sh("input keyevent 4")   # closes the keyboard
    time.sleep(0.8)


# ---------- screenshots ----------

def capture(path: Path):
    path.write_bytes(adb("exec-out", "screencap", "-p"))


def ffmpeg(*args):
    r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *args], capture_output=True)
    if r.returncode:
        raise RuntimeError(r.stderr.decode(errors="replace"))


def shot(name, ids=None, pad=24, width=720, out_dir=None, tag=None):
    """Screenshot cut to the union of the views `ids` (None = the whole screen). Names ending in .png
    go to docs/, others only to the temp folder."""
    nodes = dump()
    src = TMP / "raw.png"
    capture(src)
    W, H = (int(v) for v in CUR["size"].split("x"))
    if ids:
        found = [find(nodes, i) for i in ids]
        missing = [i for i, n in zip(ids, found) if n is None]
        if missing:
            print(f"  ! {name}: views not on screen: {missing}")
        found = [n for n in found if n]
        if not found:
            return None
        x0 = max(0, min(n.b[0] for n in found) - pad)
        y0 = max(0, min(n.b[1] for n in found) - pad)
        x1 = min(W, max(n.b[2] for n in found) + pad)
        y1 = min(H, max(n.b[3] for n in found) + pad)
    else:
        x0, y0, x1, y1 = 0, 0, W, H
    vf = f"crop={x1 - x0}:{y1 - y0}:{x0}:{y0}"
    if width and x1 - x0 > width:
        vf += f",scale={width}:-1:flags=lanczos"
    dest = (out_dir or OUT) / name
    dest.parent.mkdir(parents=True, exist_ok=True)
    ffmpeg("-i", str(src), "-vf", vf, str(dest))
    print(f"  {dest.name}")
    return dest


# ---------- app state ----------

def prefs_xml(d: dict) -> str:
    out = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
    for k, v in d.items():
        if isinstance(v, bool):
            out.append(f'    <boolean name="{k}" value="{str(v).lower()}" />')
        elif isinstance(v, int):
            out.append(f'    <int name="{k}" value="{v}" />')
        elif isinstance(v, float):
            out.append(f'    <float name="{k}" value="{v}" />')
        else:
            out.append(f'    <string name="{k}">{v}</string>')
    out.append("</map>")
    return "\n".join(out) + "\n"


def run_as_write(path: str, data: bytes):
    adb("shell", f"run-as {PKG} sh -c 'mkdir -p $(dirname {path}) && cat > {path}'", input=data)


def start(prefs: dict, extra=None):
    """Fresh app data with these settings, then the app."""
    sh(f"am force-stop {PKG}")
    sh(f"pm clear {PKG}")
    sh(f"pm grant {PKG} android.permission.CAMERA")
    base = {"boothTimer": 0, "boothFlash": False, "keepHistory": True}
    run_as_write("shared_prefs/b21.xml", prefs_xml({**base, **prefs}).encode())
    for path, data in (extra or {}).items():
        run_as_write(path, data)
    sh(f"am start -n {PKG}/.MainActivity")
    wait_for("toggleAppMode", 60)
    time.sleep(2.5)


def photo_from_booth():
    """The emulator's camera picture as the photo: one booth shot, then 'edit in the main screen'."""
    tap("modeAppBooth")
    wait_for("btnShutter", 30)
    time.sleep(3)
    tap("btnShutter")
    tap("btnBoothEdit", 60)
    wait_for("btnCrop", 30)
    time.sleep(2.5)


OLD_FONT = "1.0"


def set_size(size):
    """A taller screen shows a whole card of the settings at once (the stage keeps its height)."""
    sh(f"wm size {size}")
    CUR["size"] = size
    time.sleep(3)


def setup_device():
    global OLD_FONT
    OLD_FONT = sh("settings get system font_scale").strip() or "1.0"
    sh("settings put system font_scale 1.0")     # an emulator image may come with a larger font
    sh(f"wm size {SIZE}")
    sh(f"wm density {DENSITY}")
    sh(f"cmd uimode night {'yes' if NIGHT else 'no'}")
    for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {k} 0")
    sh("settings put global sysui_demo_allowed 1")
    demo = "am broadcast -a com.android.systemui.demo -e command "
    sh(demo + "enter")
    sh(demo + "clock -e hhmm 1000")
    sh(demo + "battery -e level 100 -e plugged false")
    sh(demo + "network -e wifi show -e level 4 -e fully true -e mobile show -e level 4 -e fully true -e datatype none")
    sh(demo + "notifications -e visible false")
    adb("install", "-r", "-g", str(APK))
    sh(f"cmd locale set-app-locales {PKG} --locales en")


def reset_device():
    sh("am broadcast -a com.android.systemui.demo -e command exit")
    sh("wm size reset")
    sh("wm density reset")
    sh(f"settings put system font_scale {OLD_FONT if OLD_FONT != 'null' else '1.0'}")


# ---------- scenes ----------

SCENES = {}


def scene(fn):
    SCENES[fn.__name__] = fn
    return fn


def thumb(name):
    """The label image of the main screen or the booth review, kept for the 'recent prints' fixtures."""
    nodes = dump()
    n = find(nodes, "boothResultCard") or find(nodes, "preview")
    if n:
        capture(TMP / "raw.png")
        x0, y0, x1, y1 = n.b
        dest = TMP / f"thumb-{name}.png"
        ffmpeg("-i", str(TMP / "raw.png"), "-vf", f"crop={x1 - x0}:{y1 - y0}:{x0}:{y0},scale='min(480,iw)':-1", str(dest))
        THUMBS[name] = dest


@scene
def photo():
    """Main screen with a photo, the label settings, the stamp and the panorama."""
    start({"label": "Tall M", "stampMode": 1, "stampWhite": True})
    photo_from_booth()
    FULL["photo"] = shot("main-photo.png", width=540)
    thumb("photo")
    scroll_to("btnOrient")
    tap_slider("sliderPano", 0.4)
    # the picture over three labels: crop it to the label format of the panorama
    tap("btnCrop"); wait_for("cropView", 20); tap("cropSticker"); tap("btnCropDone")
    wait_for("stage", 20); time.sleep(2.5)
    shot("panorama.png", ["stage"])
    tap_slider("sliderPano", 0.0)
    # whole cards: the screen made tall enough for them
    set_size("1080x3700")
    wait_for("cardLabel", 20)
    scroll_top()
    shot("label-settings.png", ["cardLabel"], pad=0)
    shot("stamp.png", ["txtStampTitle", "toggleStamp", "toggleStampColor"])
    shot("image-settings.png", ["cardImage"], pad=0)
    scroll_to("cardPrint")
    shot("print-settings.png", ["cardPrint"], pad=0)
    set_size(SIZE)


@scene
def instant():
    """Instant frame in black, with a caption and a text stamp."""
    start({"label": "Instant", "stampMode": 2, "stampText": "SUMMER 2026", "instantBlack": True})
    photo_from_booth()
    shot("instant-black.png", ["stage"], pad=8)


@scene
def sign():
    """Signs: a QR code, and red text and frame on a two-colour roll."""
    start({"label": "Square M", "textMode": True})
    type_text("editText", "example.org/b21")
    tap("switchQr")
    shot("sign-qr.png", ["stage", "switchQr"], pad=12)
    FULL["sign"] = None
    start({"label": "Square M", "textMode": True, "redRoll": True, "frame": 2, "textColor": 1})
    type_text("editText", "Fresh paint")
    FULL["sign"] = shot("sign-red-full.png", width=540, out_dir=TMP)
    thumb("sign")
    shot("sign-red.png", ["stage", "textColorBox"], pad=12)


@scene
def booth():
    """The photo booth: viewfinder with the controls, strip (stacked and side by side), grid, black frame."""
    start({"label": "Tall M", "boothMode": 1})
    tap("modeAppBooth")
    wait_for("btnShutter", 30)
    time.sleep(3.5)
    shot("booth-viewfinder.png", None, width=540)
    shot("booth-controls.png", ["toggleBoothMode", "toggleBoothFrame", "btnBoothOrient", "btnBoothPortrait"], pad=16)
    tap("btnShutter")
    wait_for("btnBoothEdit", 60)
    time.sleep(1.5)
    FULL["booth"] = shot("booth-review-full.png", width=540, out_dir=TMP)
    shot("booth-review.png", None, width=540)
    shot("booth-strip.png", ["boothResultCard"], pad=0, width=420)
    thumb("booth")
    # the same label turned: three tall pictures side by side
    start({"label": "Tall M", "boothMode": 1, "orient": 1})
    tap("modeAppBooth"); wait_for("btnShutter", 30); time.sleep(3.5)
    tap("btnShutter"); wait_for("btnBoothEdit", 60); time.sleep(1.5)
    shot("booth-strip-side.png", ["boothResultCard"], pad=0, width=560)
    # grid with a black frame
    start({"label": "Square M", "boothMode": 2, "boothBlack": True})
    tap("modeAppBooth"); wait_for("btnShutter", 30); time.sleep(3.5)
    tap("btnShutter"); wait_for("btnBoothEdit", 60); time.sleep(1.5)
    shot("booth-grid.png", ["boothResultCard"], pad=0, width=420)


@scene
def recent():
    """Recent prints. Without a printer nothing is printed, so three prints are written as files."""
    jobs, base = {}, int(time.time() * 1000)
    for i, (key, title, gray, copies) in enumerate([
        ("photo", "Square M · 40 × 40 mm", True, 1),
        ("sign", "Fresh paint", False, 2),
        ("booth", "Tall M · Booth", True, 1),
    ]):
        t = THUMBS.get(key)
        if t is None:
            continue
        ident = base - (i + 1) * 3_600_000 * 7
        w, h = 472, 472
        d = struct.pack(">iq", 0x42323150, ident) + struct.pack(">H", len(title.encode())) + title.encode()
        d += bytes([0]) + struct.pack(">iii", 2, 1, copies) + struct.pack(">i", 1) + bytes([1 if gray else 0])
        d += struct.pack(">iii", w, h, 0) + bytes([1 if gray else 0, 0]) + bytes(w * h) + (bytes(w * h) if gray else b"")
        jobs[f"files/prints/{ident}.job"] = gzip.compress(d)
        jobs[f"files/prints/{ident}.png"] = t.read_bytes()
    start({"label": "Square M"}, extra=jobs)
    tap("btnPrints")
    time.sleep(2.5)
    shot("recent-prints.png", None, width=540)


@scene
def calibration():
    """The calibration dialog."""
    start({"label": "Square M", "raster": "PHOTO"})
    scroll_to("dropProfile")
    tap("dropProfile")
    tap("text:Calibrate")
    wait_for("alertTitle", 20)
    time.sleep(1)
    shot("calibration.png", ["parentPanel"], pad=8)


@scene
def console():
    """The debug console."""
    start({"label": "Square M"})
    long_press("title")
    wait_for("text:Debug console", 20)
    time.sleep(1)
    shot("debug-console.png", ["parentPanel"], pad=8)


@scene
def overview():
    """The hero image: photo, red sign, booth review side by side (needs those scenes to have run)."""
    parts = [FULL.get("photo"), FULL.get("sign"), FULL.get("booth")]
    parts = [p for p in parts if p]
    if len(parts) < 2:
        print("  overview needs the scenes photo, sign and booth in the same run")
        return
    args = []
    for p in parts:
        args += ["-i", str(p)]
    flt = "".join(f"[{i}:v]pad=iw+40:ih:20:0:color=white[p{i}];" for i in range(len(parts)))
    flt += "".join(f"[p{i}]" for i in range(len(parts))) + f"hstack=inputs={len(parts)}"
    dest = OUT / "overview.png"
    ffmpeg(*args, "-filter_complex", flt, str(dest))
    print(f"  {dest.name}")


ORDER = ["photo", "instant", "sign", "booth", "recent", "calibration", "console", "overview"]


def main():
    global SERIAL, NIGHT, OUT, APK
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", help="adb serial of the emulator (default: the only device)")
    ap.add_argument("--only", help="comma separated scene names")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--night", action="store_true", help="dark theme")
    ap.add_argument("--apk", default=str(APK))
    ap.add_argument("--out", default=str(OUT))
    a = ap.parse_args()
    if a.list:
        for n in ORDER:
            print(f"{n:12s} {(SCENES[n].__doc__ or '').strip()}")
        return
    if a.serial:
        SERIAL = ["-s", a.serial]
    NIGHT, OUT, APK = a.night, Path(a.out), Path(a.apk)
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg is needed")
    if not APK.exists():
        sys.exit(f"{APK} not found: ./gradlew assembleDebug first (run-as needs a debuggable build)")
    names = a.only.split(",") if a.only else ORDER
    setup_device()
    try:
        for n in names:
            print(f"{n}:")
            for attempt in (1, 2):
                try:
                    SCENES[n]()
                    break
                except RuntimeError as e:   # a slow emulator: start the scene again once
                    print(f"  ! {e}" + (" (again)" if attempt == 1 else ""))
                    set_size(SIZE)
                    if attempt == 2:
                        raise
    finally:
        reset_device()
        shutil.rmtree(TMP, ignore_errors=True)


if __name__ == "__main__":
    main()
