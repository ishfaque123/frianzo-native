#!/usr/bin/env python3
"""Tiny UI driver for the emulator test.

Finds on-screen elements through `adb shell uiautomator dump` and taps
them by centre coordinates. No Appium, no extra installs — plain adb.

Usage:
  uitap.py wait-text <text> <timeout_s>    exit 0 when text appears
  uitap.py wait-desc <desc> <timeout_s>    exit 0 when content-desc appears
  uitap.py tap-text <text>                 tap the node with this exact text
  uitap.py tap-desc <desc>                 tap the node with this content-desc
  uitap.py has-text <text>                 exit 0 if text is on screen now
  uitap.py dump [file]                     save the current UI dump locally

Notes:
- Compose Text/Button nodes expose their text in the dump, and the app's
  nav icons expose contentDescription ("Home", "Reels", "Notifications",
  "Profile") — those are the locators this script relies on. Verified
  from the app code (MainActivity.kt lines ~292-336, Screens.kt login
  texts); not yet run against the real app.
- Taps use the node's bounds centre. If several nodes match, the first
  clickable one wins.
"""

import subprocess
import sys
import time
import xml.etree.ElementTree as ET

DUMP_DEVICE = "/sdcard/window_dump.xml"
DUMP_LOCAL = "/tmp/window_dump.xml"


def adb(*args):
    return subprocess.run(["adb"] + list(args), capture_output=True, text=True, timeout=60)


def dump_tree():
    adb("shell", "uiautomator", "dump", DUMP_DEVICE)
    r = adb("pull", DUMP_DEVICE, DUMP_LOCAL)
    if r.returncode != 0:
        return None
    try:
        return ET.parse(DUMP_LOCAL).getroot()
    except Exception:
        return None


def find_node(root, text=None, desc=None):
    """Return (x, y) centre of the first matching clickable node, else None."""
    if root is None:
        return None
    best = None
    for node in root.iter("node"):
        t = node.get("text") or ""
        d = node.get("content-desc") or ""
        if text is not None and t != text:
            continue
        if desc is not None and d != desc:
            continue
        bounds = node.get("bounds") or ""
        try:
            a, b = bounds.replace("][", ",").strip("[]").split(",")
            x1, y1 = map(int, a.split(","))
            x2, y2 = map(int, b.split(","))
        except Exception:
            continue
        centre = ((x1 + x2) // 2, (y1 + y2) // 2)
        if node.get("clickable") == "true":
            return centre
        if best is None:
            best = centre
    return best


def tap(x, y):
    r = adb("shell", "input", "tap", str(x), str(y))
    return r.returncode == 0


def wait_for(text=None, desc=None, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        root = dump_tree()
        if find_node(root, text=text, desc=desc) is not None:
            return True
        time.sleep(2)
    return False


def main():
    if len(sys.argv) < 2:
        print("usage: uitap.py <wait-text|wait-desc|tap-text|tap-desc|has-text|dump> ...")
        return 2
    cmd = sys.argv[1]

    if cmd == "dump":
        out = sys.argv[2] if len(sys.argv) > 2 else DUMP_LOCAL
        adb("shell", "uiautomator", "dump", DUMP_DEVICE)
        r = adb("pull", DUMP_DEVICE, out)
        print(out if r.returncode == 0 else "dump failed")
        return 0 if r.returncode == 0 else 1

    if cmd == "wait-text":
        return 0 if wait_for(text=sys.argv[2], timeout=int(sys.argv[3])) else 1
    if cmd == "wait-desc":
        return 0 if wait_for(desc=sys.argv[2], timeout=int(sys.argv[3])) else 1
    if cmd == "has-text":
        root = dump_tree()
        return 0 if find_node(root, text=sys.argv[2]) is not None else 1
    if cmd == "tap-text":
        root = dump_tree()
        pos = find_node(root, text=sys.argv[2])
        if pos is None:
            print(f"not found on screen: text={sys.argv[2]!r}")
            return 1
        return 0 if tap(*pos) else 1
    if cmd == "tap-desc":
        root = dump_tree()
        pos = find_node(root, desc=sys.argv[2])
        if pos is None:
            print(f"not found on screen: content-desc={sys.argv[2]!r}")
            return 1
        return 0 if tap(*pos) else 1

    print(f"unknown command: {cmd}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
