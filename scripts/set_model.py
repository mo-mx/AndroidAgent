import subprocess, time, re, sys
import xml.etree.ElementTree as ET
ADB = r"C:\Users\Aqua\AndroidAgent\tools\platform-tools\adb.exe"
UP = r"C:\Users\Aqua\AndroidAgent\logs\ui.xml"

def sh(*a, timeout=120):
    return subprocess.run([ADB] + list(a), capture_output=True, timeout=timeout).stdout.decode("utf-8", "ignore")

def ui():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml"); sh("pull", "/sdcard/ui.xml", UP)
    try: root = ET.parse(UP).getroot()
    except Exception: return []
    out = []
    for n in root.iter("node"):
        a = n.attrib
        t = (a.get("text") or a.get("content-desc") or "").strip()
        if not t: continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            out.append((t, (x1+x2)//2, (y1+y2)//2, a.get("class")))
    return out

newmodel = sys.argv[1]
els = ui()
field = next((e for e in els if e[3].endswith("EditText")), None)
print("edit fields:", [(e[0][:40], e[1], e[2]) for e in els if e[3].endswith("EditText")])
# find the edit text containing current model
cand = [e for e in els if e[3].endswith("EditText")]
target = None
for e in cand:
    if "qwen" in e[0]:
        target = e
if target is None:
    for e in els:
        if e[0].startswith("qwen"):
            target = e
print("target:", target)
if not target:
    print([e[0][:30] for e in els][:20]); sys.exit(1)
sh("shell", "input", "tap", str(target[1]), str(target[2]))
time.sleep(1)
sh("shell", "input", "keyevent", "KEYCODE_MOVE_END")
for _ in range(4):
    sh("shell", "input", "keyevent", "--longpress", "KEYCODE_DEL")
sh("shell", "keyevent", "67") if False else None
for _ in range(60):
    sh("shell", "input", "keyevent", "KEYCODE_DEL")
time.sleep(1)
sh("shell", "input", "text", newmodel)
time.sleep(1)
sh("shell", "input", "keyevent", "KEYCODE_BACK")
time.sleep(2)
print("after:", [(e[0][:40], e[1], e[2]) for e in ui() if e[0].startswith("qwen") or "Model" in e[0]][:5])
