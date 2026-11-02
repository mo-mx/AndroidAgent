"""Run one debug voice command on the device and report what the app actually decided.

Handles the Android 17 "app compatibility" dialog that appears for debuggable builds (it owns the
a11y focus for a few seconds and used to turn the first read into `launch-not-foreground`).

Usage:
    python scripts/voice_probe.py "现在有几个闹钟" [--parse-only]

Prints the app's own log lines (SemanticRunner / VoiceActivity / ActionMemory / TorchControl /
ClockControl) for that single command. Non-destructive: only injects a text command.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"C:\Users\Aqua\AndroidAgent\tools\platform-tools\adb.exe"
UI_XML = r"C:\Users\Aqua\AndroidAgent\logs\probe-ui.xml"
TAGS = "VoiceActivity:I SemanticRunner:I ActionMemory:I TorchControl:I ClockControl:I"
KEEP = re.compile(r"semantic:|parsed:|exec |merged:|undo\(")


def sh(*args, timeout=120):
    return subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)


def dump():
    sh("shell", "uiautomator", "dump", "--compressed", "/sdcard/probe-ui.xml")
    sh("pull", "/sdcard/probe-ui.xml", UI_XML)
    try:
        return ET.parse(UI_XML).getroot()
    except Exception:
        return None


def dismiss_compat_dialog(rounds=4, gap=0.7):
    """Tap 确定 on the compatibility dialog if it is on screen. Returns how many were dismissed."""
    dismissed = 0
    for _ in range(rounds):
        root = dump()
        if root is None:
            time.sleep(gap)
            continue
        texts = [(n.attrib.get("text") or "") for n in root.iter("node")]
        if "Android 应用兼容性" not in texts:
            break
        for n in root.iter("node"):
            if n.attrib.get("text") == "确定":
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.attrib["bounds"])
                x = (int(m.group(1)) + int(m.group(3))) // 2
                y = (int(m.group(2)) + int(m.group(4))) // 2
                sh("shell", "input", "tap", str(x), str(y))
                dismissed += 1
                time.sleep(gap)
                break
        else:
            break
    return dismissed


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    text = sys.argv[1]
    parse_only = "--parse-only" in sys.argv[2:]

    sh("logcat", "-c")
    args = ["shell", "am", "start", "-n", "ai.closepaw/.voice.VoiceActivity",
            "--es", "fake_text", text]
    if parse_only:
        args += ["--ez", "parse_only", "true"]
    sh(*args)

    time.sleep(1.2)
    dismissed = dismiss_compat_dialog()
    time.sleep(0.8 if parse_only else 14)

    out = sh("logcat", "-d", "-s", *TAGS.split()).stdout.decode("utf-8", "ignore")
    lines = [ln for ln in out.splitlines() if (parse_only or KEEP.search(ln) or "PARSEONLY" in ln)]
    print(f"### {text}   (dialog dismissed x{dismissed})")
    for ln in lines[-8:]:
        print(ln.strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
