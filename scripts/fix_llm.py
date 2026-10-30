import subprocess, time, re
import xml.etree.ElementTree as ET
ADB = r"C:\Users\Aqua\AndroidAgent\tools\platform-tools\adb.exe"
UP = r"C:\Users\Aqua\AndroidAgent\logs\ui.xml"

def sh(*a, timeout=180):
    return subprocess.run([ADB] + list(a), capture_output=True, timeout=timeout).stdout.decode("utf-8", "ignore")

def rows():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml"); sh("pull", "/sdcard/ui.xml", UP)
    out = []
    for n in ET.parse(UP).getroot().iter("node"):
        a = n.attrib
        t = (a.get("text") or "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        if not m: continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append((y1, (x1+x2)//2, (y1+y2)//2, a.get("class", ""), t))
    return sorted(out)

def mask(s):
    return s if len(s) < 45 else s[:12] + f"...<{len(s)} chars>"

def show():
    print("--- screen ---")
    for y, x, yy, c, t in rows():
        if t or c.endswith("EditText"):
            print(f"  {yy:>5} {c.split('.')[-1]:<9} {mask(t)[:70]}")

key = [l.strip() for l in open(r"C:\Users\Aqua\AndroidAgent\secrets\bailian.key", encoding="utf-8") if l.strip().startswith("sk-")][0]

# 1) fix Custom Model Id
target_y = None
cur = rows()
for i, r in enumerate(cur):
    if r[3].endswith("EditText"):
        # the one whose previous label says Custom Model Id
        labels = [x[4] for x in cur[:i] if x[4]]
        if labels and "Custom Model Id" in labels[-2:]:
            target_y = r
print("model field:", target_y[2] if target_y else None)
if target_y:
    sh("shell", "input", "tap", str(target_y[1]), str(target_y[2])); time.sleep(1)
    sh("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(200):
        sh("shell", "input", "keyevent", "KEYCODE_DEL")
    sh("shell", "input", "text", "qwen3.8-flash"); time.sleep(1)
    print("model set")

# 2) API key field: EditText under 'API Key' label group near y>1400
cur = rows()
api = None
for r in cur:
    if r[3].endswith("EditText") and r[0] > 1400:
        api = r
print("api field:", api[2] if api else None)
if api:
    sh("shell", "input", "tap", str(api[1]), str(api[2])); time.sleep(1)
    sh("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(220):
        sh("shell", "input", "keyevent", "KEYCODE_DEL")
    sh("shell", "input", "text", key); time.sleep(1)
    print("key typed, len", len(key))
show()
