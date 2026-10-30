import subprocess, os, sys, json

ROOT = r"C:\Users\Aqua\AndroidAgent\src\closepaw"
LOG = r"C:\Users\Aqua\AndroidAgent\logs\gradle-rescue.log"
CONF = r"C:\Users\Aqua\AndroidAgent\tools\relay_data\relay.json"
SECRET_FILE = r"C:\Users\Aqua\AndroidAgent\secrets\rescue_secret.txt"

env = dict(os.environ)
env["JAVA_HOME"] = r"C:\Users\Aqua\AndroidAgent\tools\jdk-17.0.20.1+1"
env["ANDROID_HOME"] = r"C:\Users\Aqua\AndroidAgent\tools\android-sdk"
env["ANDROID_SDK_ROOT"] = env["ANDROID_HOME"]
env["PATH"] = env["JAVA_HOME"] + r"\bin;" + env["ANDROID_HOME"] + r"\platform-tools;" + env.get("PATH", "")
env["GRADLE_OPTS"] = "-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897"

secret = open(SECRET_FILE, encoding="utf-8").read().strip()
conf = json.load(open(CONF, encoding="utf-8"))
endpoint = f"https://10.0.0.163:{conf['port']}"

# pin = sha256(der of cert)
import base64, hashlib
pem = open(conf["cert"], "rb").read().decode()
body = "".join(l for l in pem.splitlines() if "-----" not in l)
pin = hashlib.sha256(base64.b64decode(body)).hexdigest()

args = ["cmd", "/c", r"gradlew.bat", "--no-daemon", ":rescue:assembleDebug",
        f"-Prescue.endpoint={endpoint}", f"-Prescue.secret={secret}", f"-Prescue.pin={pin}"]
print("endpoint:", endpoint, "pin:", pin[:16] + "...")
with open(LOG, "wb") as f:
    p = subprocess.run(args, cwd=ROOT, env=env, stdout=f, stderr=subprocess.STDOUT, timeout=3000)
print("rc=", p.returncode)
tail = open(LOG, "rb").read().decode("utf-8", "ignore").splitlines()[-18:]
print("\n".join(tail))
