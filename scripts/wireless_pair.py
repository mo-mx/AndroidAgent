import subprocess, time, re, sys
import os
import xml.etree.ElementTree as ET

ADB = r"C:\Users\Aqua\AndroidAgent\tools\platform-tools\adb.exe"
# Device serial intentionally not hardcoded in the repo (it identifies a physical
# phone). Set ADB_SERIAL in your shell, or pass a wireless address like 10.0.0.x:5555.
DEV = os.environ.get("ADB_SERIAL", "")
UP = r"C:\Users\Aqua\AndroidAgent\logs\ui.xml"


def sh(*a, t=90):
    return subprocess.run([ADB, "-s", DEV] + list(a), capture_output=True, timeout=t).stdout.decode("utf-8", "ignore")


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    subprocess.run([ADB, "-s", DEV, "pull", "/sdcard/ui.xml", UP], capture_output=True)
    try:
        return ET.parse(UP).getroot()
    except Exception:
        return None


def nodes(root):
    out = []
    if root is None:
        return out
    for n in root.iter("node"):
        a = n.attrib
        t = (a.get("text") or "").strip()
        d = (a.get("content-desc") or "").strip()
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({"t": t, "d": d, "x": (x1 + x2) // 2, "y": (y1 + y2) // 2,
                    "cls": a.get("class", ""), "click": a.get("clickable") == "true",
                    "checked": a.get("checked")})
    return out


def find(ns, needle):
    for e in ns:
        if needle in e["t"] or needle in e["d"]:
            return e
    return None


def go_dev_options():
    sh("shell", "am", "start", "-a", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
    time.sleep(2.5)


def locate(ns, label, scrolls=8):
    e = find(ns, label)
    if e:
        return e
    for _ in range(scrolls):
        sh("shell", "input", "swipe", "540", "1900", "540", "900", "300")
        time.sleep(1.2)
        ns = nodes(dump())
        e = find(ns, label)
        if e:
            return e
    return None


def main():
    go_dev_options()
    # make sure we start from the top of the list
    for _ in range(8):
        sh("shell", "input", "swipe", "540", "900", "540", "1900", "200")
        time.sleep(0.5)
    time.sleep(1)
    ns = nodes(dump())
    row = locate(ns, "无线调试")
    if not row:
        print("找不到「无线调试」行"); sys.exit(1)
    print("无线调试行:", row["x"], row["y"], row["checked"])
    # the toggle sits at the right edge of the same row
    sh("shell", "input", "tap", "936", str(row["y"]))
    time.sleep(2.5)
    ns = nodes(dump())
    row = find(ns, "无线调试")
    print("切换后 checked:", row["checked"] if row else "?")
    # open the sub screen
    sh("shell", "input", "tap", str(row["x"]), str(row["y"]))
    time.sleep(2.5)
    ns = nodes(dump())
    labels = [e["t"] or e["d"] for e in ns if (e["t"] or e["d"])]
    print("子页文本:", " | ".join(labels[:18]))
    pair = find(ns, "配对码") or find(ns, "配对")
    print("配对入口:", pair)
    if pair:
        sh("shell", "input", "tap", str(pair["x"]), str(pair["y"]))
        time.sleep(3)
        ns = nodes(dump())
        for e in ns:
            if e["t"] or e["d"]:
                print("  ", e["t"] or e["d"], f"@{e['x']},{e['y']}")


if __name__ == "__main__":
    main()
