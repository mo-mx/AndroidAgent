import subprocess, time, re, sys, os
import xml.etree.ElementTree as ET
ADB = r"C:\Users\Aqua\AndroidAgent\tools\platform-tools\adb.exe"
UP = r"C:\Users\Aqua\AndroidAgent\logs\ui.xml"

def sh(*a, timeout=120):
    return subprocess.run([ADB] + list(a), capture_output=True, timeout=timeout).stdout.decode("utf-8", "ignore")

def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml"); sh("pull", "/sdcard/ui.xml", UP)
    return ET.parse(UP).getroot()

def nodes():
    out = []
    for n in dump().iter("node"):
        a = n.attrib
        t = (a.get("text") or a.get("content-desc") or "").strip()
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        if not m: continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({"t": t, "x": (x1+x2)//2, "y": (y1+y2)//2, "b": (x1, y1, x2, y2),
                    "cls": a.get("class", ""), "click": a.get("clickable") == "true"})
    return out

def tap(x, y, wait=1.5):
    sh("shell", "input", "tap", str(x), str(y)); time.sleep(wait)

def settext(label):
    """tap the Nth EditText by index order on screen, clear it, type `label` text"""
    pass

def find_text(ns, needle, exact=True):
    for e in ns:
        if (e["t"] == needle) if exact else (needle in e["t"]):
            return e
    return None

def edits(ns):
    return [e for e in ns if e["cls"].endswith("EditText")]

def clear_and_type(e, text):
    tap(e["x"], e["y"], 1.0)
    sh("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    cur = e["t"]
    for _ in range(min(len(cur) + 5, 200)):
        sh("shell", "input", "keyevent", "KEYCODE_DEL")
    if text:
        sh("shell", "input", "text", text)
    time.sleep(1)

url = "https://dashscope.aliyuncs.com/compatible-mode/v1"
model = sys.argv[1] if len(sys.argv) > 1 else "qwen3.8-flash"
key = [l.strip() for l in open(r"C:\Users\Aqua\AndroidAgent\secrets\bailian.key", encoding="utf-8") if l.strip().startswith("sk-")][0]

ns = nodes()
o = find_text(ns, "Other")
if o:
    tap(o["x"], o["y"], 2.0)
ns = nodes()
ed = edits(ns)
print("edits after Other:", [(e["t"][:40], e["b"]) for e in ed])
labels = [e["t"] for e in ns if e["t"]][:14]
print("labels:", labels)

# Identify fields by nearby label text: Base URL / Custom Model Id / API Key
# take edits in vertical order
ed = sorted(ed, key=lambda e: e["b"][1])
if len(ed) >= 2:
    clear_and_type(ed[0], url)
    ns = nodes(); ed = sorted(edits(ns), key=lambda e: e["b"][1])
    clear_and_type(ed[1], model)
    ns = nodes(); ed = sorted(edits(ns), key=lambda e: e["b"][1])
    if len(ed) >= 3:
        clear_and_type(ed[2], key)
    print("done; final labels:", [e["t"][:40] for e in nodes() if e["t"]][:16])
else:
    print("not enough edits; abort")
