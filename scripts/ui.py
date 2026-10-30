import subprocess, re, sys, os, xml.etree.ElementTree as ET, time

ADB = r"C:\Users\Aqua\AndroidAgent\tools\platform-tools\adb.exe"
DISP = "4630946592180194435"  # main display

def sh(*args, timeout=60):
    p = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", "ignore"), p.stderr.decode("utf-8", "ignore")

def dump(out=r"C:\Users\Aqua\AndroidAgent\logs\ui.xml"):
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    sh("pull", "/sdcard/ui.xml", out)
    txt = ""
    try:
        txt = ET.parse(out).getroot()
    except Exception as e:
        return None, f"parse error {e}"
    return txt, ""

def elements(root):
    res = []
    for n in root.iter("node"):
        a = n.attrib
        t = a.get("text", "").strip()
        d = a.get("content-desc", "").strip()
        if not t and not d:
            continue
        b = a.get("bounds", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        cx = cy = None
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        res.append({"text": t, "desc": d, "id": a.get("resource-id", ""),
                    "cls": a.get("class", ""), "clickable": a.get("clickable"),
                    "bounds": b, "cx": cx, "cy": cy})
    return res

if __name__ == "__main__":
    root, err = dump()
    if root is None:
        print("ERR", err); sys.exit(1)
    els = elements(root)
    print(f"{len(els)} labeled nodes")
    for e in els:
        label = e["text"] or e["desc"]
        print(f'  [{e["cx"]},{e["cy"]}] clickable={e["clickable"]} id={e["id"].split("/")[-1]:<22} {label[:70]}')
