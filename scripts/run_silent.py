import subprocess, time, sys, re
import xml.etree.ElementTree as ET

ADB = r"C:\Users\Aqua\AndroidAgent\tools\platform-tools\adb.exe"
UP = r"C:\Users\Aqua\AndroidAgent\logs\ui.xml"
SHOT = r"C:\Users\Aqua\AndroidAgent\logs"
DISP = "4630946592180194435"

def sh(*a, timeout=120):
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
        out.append((t, (x1 + x2) // 2, (y1 + y2) // 2))
    return out

def top():
    d = sh("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ u0 ([\w./]+)", d)
    return m.group(1) if m else "?"

def shot(name):
    p = f"/sdcard/{name}.png"
    sh("shell", "screencap", "-p", "-d", DISP, p)
    sh("pull", p, f"{SHOT}\\{name}.png")
    return f"{SHOT}\\{name}.png"

task = sys.argv[1]
secs = int(sys.argv[2]) if len(sys.argv) > 2 else 120
enc = task.replace(" ", "%s")

sh("shell", "settings", "put", "secure", "enabled_accessibility_services", "ai.closepaw/.app.AgentService")
sh("shell", "settings", "put", "secure", "accessibility_enabled", "1")
time.sleep(2)
sh("shell", "am", "start", "-n", "ai.closepaw/.app.MainActivity")
time.sleep(3)

# prepare input (uiautomator used only BEFORE the task starts)
els = ui()
row = next((e for e in els if "What can I help" in e[0]), None)
if not row:
    print("no input row:", [e[0][:25] for e in els][:12]); sys.exit(1)
sh("shell", "input", "tap", "540", str(row[2]))
time.sleep(1)
sh("shell", "input", "text", enc)
time.sleep(2)
els = ui()
send = next((e for e in els if e[0] == "Send"), None)
if not send:
    print("no send:", [e[0][:25] for e in els][:12]); sys.exit(1)
sh("shell", "input", "tap", str(send[1]), str(send[2]))
print("== sent; observing with screenshots only (no uiautomator) ==")

n = secs // 8
for i in range(n):
    time.sleep(8)
    p = shot(f"s{i:02d}")
    print(f"[{(i+1)*8:>3}s] top={top()}  {p}")
