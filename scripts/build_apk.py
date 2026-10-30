import subprocess, os, sys

ROOT = r"C:\Users\Aqua\AndroidAgent\src\closepaw"
LOG = r"C:\Users\Aqua\AndroidAgent\logs\gradle-build.log"
env = dict(os.environ)
env["JAVA_HOME"] = r"C:\Users\Aqua\AndroidAgent\tools\jdk-17.0.20.1+1"
env["ANDROID_HOME"] = r"C:\Users\Aqua\AndroidAgent\tools\android-sdk"
env["ANDROID_SDK_ROOT"] = env["ANDROID_HOME"]
env["PATH"] = env["JAVA_HOME"] + r"\bin;" + env["ANDROID_HOME"] + r"\platform-tools;" + env.get("PATH", "")
env["GRADLE_OPTS"] = "-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897"

args = ["cmd", "/c", r"gradlew.bat", "--no-daemon", "assembleDebug", "-x", "lint"]
with open(LOG, "wb") as f:
    p = subprocess.run(args, cwd=ROOT, env=env, stdout=f, stderr=subprocess.STDOUT, timeout=3000)
print("rc=", p.returncode)
tail = open(LOG, "rb").read().decode("utf-8", "ignore").splitlines()[-25:]
print("\n".join(tail))
