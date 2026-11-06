#!/usr/bin/env python3
"""Best-effort black-screen detector for reels screenshots.

For each screenshot it measures the fraction of near-black pixels in the
middle of the screen (the video area, away from the top bar and the
bottom comment pill). It also checks the top strip: if the WHOLE screen
is black (app crashed / screen off) the verdict is INCONCLUSIVE, not a
player bug.

Usage: check-black.py shot1.png shot2.png ...
Writes result.json next to the script's working directory (or $OUT_DIR).

CAVEAT (read this): a SurfaceView/VideoView layer does not always show
up in `screencap` on real hardware — on the swiftshader emulator it
normally does, because everything is composited in software. So this
check is a helper, not proof. The screenrecord video in test-output/
is the source of truth; a human watches it and decides.
"""

import json
import os
import sys

BLACK_MAX = 25          # a pixel is "black" when R,G,B are all below this
SUSPECT_FRACTION = 0.85  # >85% black video area = black screen suspected


def analyse(path):
    from PIL import Image
    img = Image.open(path).convert("RGB")
    w, h = img.size
    px = img.load()

    def black_fraction(x0, y0, x1, y1, step=4):
        black = total = 0
        for y in range(y0, y1, step):
            for x in range(x0, x1, step):
                r, g, b = px[x, y]
                total += 1
                if r < BLACK_MAX and g < BLACK_MAX and b < BLACK_MAX:
                    black += 1
        return black / total if total else 0.0

    # Video area: middle of the screen (top bar and bottom pill excluded).
    video = black_fraction(int(w * 0.15), int(h * 0.18), int(w * 0.85), int(h * 0.82))
    # Top strip: the white nav bar. If this is black too, the whole screen
    # is dead — that is a different failure, not the reels player.
    top = black_fraction(0, 0, w, int(h * 0.08))
    return {"file": os.path.basename(path), "black_fraction": round(video, 3),
            "top_strip_black_fraction": round(top, 3)}


def main():
    files = sys.argv[1:]
    if not files:
        print("usage: check-black.py <png> [png ...]")
        return 2
    results = []
    for f in files:
        try:
            results.append(analyse(f))
        except Exception as e:
            results.append({"file": os.path.basename(f), "error": str(e)})
    usable = [r for r in results if "black_fraction" in r]
    if not usable:
        verdict = "INCONCLUSIVE"
    elif any(r["top_strip_black_fraction"] > 0.85 for r in usable):
        verdict = "INCONCLUSIVE"
    elif any(r["black_fraction"] > SUSPECT_FRACTION for r in usable):
        verdict = "BLACK_SCREEN_SUSPECTED"
    else:
        verdict = "OK"
    out = {"verdict": verdict, "images": results,
           "note": "Best-effort screencap check only. The screenrecord video is the source of truth."}
    out_dir = os.environ.get("OUT_DIR", ".")
    with open(os.path.join(out_dir, "result.json"), "w") as fh:
        json.dump(out, fh, indent=2)
    print(json.dumps(out, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
