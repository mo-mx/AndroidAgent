import subprocess, time, sys, os, re

ADB = r"C:\Users\Aqua\AndroidAgent\tools\platform-tools\adb.exe"
BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
APK = r"C:\Users\Aqua\AndroidAgent\src\closepaw\app\build\outputs\apk\debug\app-debug.apk"

def sh(*a, timeout=600):
    p = subprocess.run([ADB] + list(a), capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", "ignore") + p.stderr.decode("utf-8", "ignore")

key = [l.strip() for l in open(r"C:\Users\Aqua\AndroidAgent\secrets\bailian.key", encoding="utf-8") if l.strip().startswith("sk-")][0]

def grant():
    sh("shell", "settings", "put", "secure", "enabled_accessibility_services", "ai.closepaw/.app.AgentService")
    sh("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    sh("shell", "cmd", "appops", "set", "ai.closepaw", "SYSTEM_ALERT_WINDOW", "allow")
    sh("shell", "cmd", "deviceidle", "whitelist", "+ai.closepaw")

def launch(goal=None, extra=()):
    args = ["shell", "am", "start", "-n", "ai.closepaw/.app.MainActivity",
            "--es", "api_key", "sk-dummy-for-onboarding",
            "--es", "other_api_key", key,
            "--es", "other_base_url", BASE,
            "--es", "other_model_id", "qwen3.8-flash",
            "--es", "main_model", "other-custom",
            "--ez", "trace_enabled", "true",
            "--ez", "debug_mode", "true"]
    if goal:
        args += ["--es", "goal", '"' + goal + '"']
    args += list(extra)
    return sh(*args)

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "install"
    if cmd == "install":
        print(sh("install", "-r", "-g", APK))
        grant()
    elif cmd == "grant":
        grant(); print("granted")
    elif cmd == "launch":
        goal = sys.argv[2] if len(sys.argv) > 2 else None
        print(launch(goal))
    elif cmd == "apkinfo":
        print(sh("shell", "dumpsys", "package", "ai.closepaw").split("versionName")[1][:40])
