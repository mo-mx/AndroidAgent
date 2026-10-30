import subprocess, time, sys, re
import xml.etree.ElementTree as ET

ADB = r"C:\Users\Aqua\AndroidAgent\tools\platform-tools\adb.exe"
UP = r"C:\Users\Aqua\AndroidAgent\logs\ui.xml"

def sh(*a, timeout=90):
    return subprocess.run([ADB] + list(a), capture_output=True, timeout=timeout).stdout.decode("utf-8", "ignore")

def ui():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    sh("pull", "/sdcard/ui.xml", UP)
    try:
        root = ET.parse(UP).getroot()
    except Exception:
        return []
    out = []
    for n in root.iter("node"):
        a = n.attrib
        t = (a.get("text") or a.get("content-desc") or "").strip()
        if not t:
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({"t": t, "x": (x1 + x2) // 2, "y": (y1 + y2) // 2,
                    "b": (x1, y1, x2, y2), "click": a.get("clickable") == "true"})
    return out

def find(els, needle, exact=False):
    for e in els:
        if (e["t"] == needle) if exact else (needle.lower() in e["t"].lower()):
            return e
    return None

def tap_text(needle, exact=False, tries=3, wait=2.0):
    for i in range(tries):
        els = ui()
        e = find(els, needle, exact)
        if e:
            sh("shell", "input", "tap", str(e["x"]), str(e["y"]))
            time.sleep(wait)
            return e
        time.sleep(1.5)
    return None

if __name__ == "__main__":
    needle = sys.argv[1]
    exact = len(sys.argv) > 2 and sys.argv[2] == "exact"
    e = tap_text(needle, exact)
    print("tapped:", e)
    time.sleep(1)
    for x in ui()[:18]:
        print(f'  [{x["x"]},{x["y"]}] {x["t"][:60]}')
