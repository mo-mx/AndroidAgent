import os, subprocess, sys

SM = r"C:\Users\Aqua\AndroidAgent\tools\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat"
LOG = r"C:\Users\Aqua\AndroidAgent\logs"

env = dict(os.environ)
env["JAVA_HOME"] = r"C:\Users\Aqua\AndroidAgent\tools\jdk-17.0.20.1+1"
env["ANDROID_HOME"] = r"C:\Users\Aqua\AndroidAgent\tools\android-sdk"
env["ANDROID_SDK_ROOT"] = env["ANDROID_HOME"]
env["PATH"] = env["JAVA_HOME"] + r"\bin;" + env.get("PATH", "")

def run(args, inp=None, logname="sdk.log"):
    p = subprocess.run(["cmd", "/c", SM] + args, input=inp, capture_output=True,
                       env=env, timeout=1800)
    out = (p.stdout or b"") + (p.stderr or b"")
    with open(os.path.join(LOG, logname), "wb") as f:
        f.write(out)
    print(args, "rc=", p.returncode)
    tail = out.decode("utf-8", "ignore").strip().splitlines()[-4:]
    for t in tail:
        print("   ", t)

run(["--licenses"], inp=b"y\n" * 30, logname="sdk-licenses.log")
run(["platform-tools", "platforms;android-36", "build-tools;36.0.0"], logname="sdk-install.log")
print("--- installed dirs ---")
print(os.listdir(env["ANDROID_HOME"]))
