import subprocess, time, sys, os, re
import xml.etree.ElementTree as ET

ADB = r"C:\Users\Aqua\AndroidAgent\tools\platform-tools\adb.exe"
UP = r"C:\Users\Aqua\AndroidAgent\logs\ui.xml"

def sh(*a, timeout=90):
    p = subprocess.run([ADB] + list(a), capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", "ignore")

def tap(x, y):
    sh("shell", "input", "tap", str(int(x)), str(int(y)))

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
        cx = cy = 0
        if m:
            x1, y1, x2, y2 = map(int, m.groups()); cx, cy = (x1+x2)//2, (y1+y2)//2
        out.append((t, cx, cy))
    return out

def top():
    d = sh("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ u0 ([\w./]+)", d)
    return m.group(1) if m else "?"

def focus():
    d = sh("shell", "dumpsys", "window")
    m = re.search(r"mCurrentFocus=Window\{\S+ u0 ([\w./]+)", d)
    return m.group(1) if m else "?"

task = sys.argv[1]
enc = task.replace(" ", "%s")

# make sure closepaw is in front
sh("shell", "am", "start", "-n", "ai.closepaw/.app.MainActivity")
time.sleep(3)
els = ui()
if not any("What can I help" in e[0] or "Ask ClosePaw" in e[0] for e in els):
    # maybe we are in a sub page; press back once
    sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(2); els = ui()
row = next((e for e in els if "What can I help" in e[0]), None) or next((e for e in els if "Send" == e[0]), None)
print("input row:", row)
if row is None:
    print("labels:", [e[0][:20] for e in els][:15]); sys.exit(1)
tap(540, row[2])
time.sleep(1)
sh("shell", "input", "text", enc)
time.sleep(2)
els = ui()
send = next((e for e in els if e[0] == "Send"), None)
print("send:", send)
if send is None:
    print("labels after typing:", [e[0][:22] for e in els][:15]); sys.exit(1)
tap(send[1], send[2])
print("== sent ==")

for i in range(15):
    time.sleep(5)
    els = ui()
    print(f"[{(i+1)*5:>3}s] top={top()} focus={focus()}")
    print("      ", [e[0][:34] for e in els][:9])
