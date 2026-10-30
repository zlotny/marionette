#!/usr/bin/env python3
import hashlib
import html
import os
import re
import subprocess
import sys
import time

PKG = "dev.marionette.debug"
OUT = sys.argv[1] if len(sys.argv) > 1 else "build/ui"
ONLY = sys.argv[2].split(",") if len(sys.argv) > 2 else None
ROOT = ["Model and connection", "Instructions", "Context", "Wake phrase", "Stop phrases", "Recognition", "Speech", "Audio devices",
        "Conversation", "Tools", "Permissions", "Screen control", "Keys and accounts", "Activity", "About Marionette"]
NESTED = {"Wake phrase": ["Sensitivity", "Activations", "Voice test"], "Conversation": ["While working"], "Audio devices": ["Car", "Phone"], "Tools": ["Search settings"]}
ANIMATED = {"home": 1, "chats": 1, "about_marionette": 2}
RECT = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


def dump():
    for _ in range(4):
        if "dumped" in adb("shell", "uiautomator", "dump", "/sdcard/ui.xml"):
            return adb("shell", "cat", "/sdcard/ui.xml")
        time.sleep(1)
    return ""


def nodes(xml):
    for node in re.findall(r"<node [^>]*>", xml):
        text = re.search(r' text="([^"]*)"', node)
        desc = re.search(r'content-desc="([^"]*)"', node)
        rect = RECT.search(node)
        if rect:
            yield html.unescape(text.group(1)) if text else "", html.unescape(desc.group(1)) if desc else "", tuple(map(int, rect.groups()))


def tap_label(label, attr=0, scrolls=8):
    for i in range(scrolls):
        for node in nodes(dump()):
            if node[attr] == label:
                a, b, c, d = node[2]
                adb("shell", "input", "tap", str((a + c) // 2), str((b + d) // 2))
                time.sleep(1.2)
                return True
        swipe()
    return False


def swipe(up=True):
    adb("shell", "input", "swipe", "540", "1800" if up else "700", "540", "700" if up else "1800", "250")
    time.sleep(0.7)


def to_top():
    for _ in range(6):
        swipe(False)


def shoot(name):
    pages = []
    seen = None
    for page in range(1, ANIMATED.get(name, 8) + 1):
        path = os.path.join(OUT, f"{name}-{page}.png")
        with open(path, "wb") as f:
            subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f)
        state = hashlib.md5(open(path, "rb").read()).hexdigest()
        if state == seen:
            os.remove(path)
            break
        seen = state
        pages.append(path)
        swipe()
    print(name, len(pages), flush=True)


def slug(text):
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")


def wanted(label):
    return ONLY is None or slug(label) in ONLY


def main():
    os.makedirs(OUT, exist_ok=True)
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/dev.marionette.ui.MainActivity")
    time.sleep(3)
    if wanted("home"):
        shoot("home")
    if wanted("chats"):
        tap_label("Open chats", 1, 1)
        shoot("chats")
        adb("shell", "am", "force-stop", PKG)
        adb("shell", "am", "start", "-n", f"{PKG}/dev.marionette.ui.MainActivity")
        time.sleep(3)
    if not tap_label("Settings", 1, 1):
        raise SystemExit("settings not found")
    if wanted("settings"):
        shoot("settings")
    for label in ROOT:
        if not wanted(label):
            continue
        to_top()
        if not tap_label(label):
            print("missing", label, flush=True)
            continue
        shoot(slug(label))
        for child in NESTED.get(label, []):
            to_top()
            if tap_label(child):
                shoot(f"{slug(label)}__{slug(child)}")
                adb("shell", "input", "keyevent", "4")
                time.sleep(0.8)
            else:
                print("missing", child, flush=True)
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.8)


main()
