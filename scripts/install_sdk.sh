#!/usr/bin/env bash
set -u
export JAVA_HOME="C:/Users/Aqua/AndroidAgent/tools/jdk-17.0.20.1+1"
export ANDROID_HOME="C:\\Users\\Aqua\\AndroidAgent\\tools\\android-sdk"
cd /c/Users/Aqua/AndroidAgent || exit 1
SM='C:\Users\Aqua\AndroidAgent\tools\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat'
printf 'y\ny\ny\ny\ny\ny\ny\ny\ny\ny\n' | cmd //c "$SM --licenses" > logs/sdk-licenses2.log 2>&1
echo "licenses rc=$?"
cmd //c "$SM platform-tools platforms;android-36 build-tools;36.0.0" > logs/sdk-install.log 2>&1
echo "install rc=$?"
tail -6 logs/sdk-install.log
ls tools/android-sdk
