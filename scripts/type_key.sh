#!/usr/bin/env bash
# type the bailian key into the focused field without echoing it
set -u
ADB="/c/Users/Aqua/AndroidAgent/tools/platform-tools/adb.exe"
KEY=$(grep -m1 '^sk-' /c/Users/Aqua/AndroidAgent/secrets/bailian.key | tr -d '\r\n')
if [ -z "$KEY" ]; then echo "NO KEY"; exit 1; fi
"$ADB" shell input tap 400 2196
sleep 1
"$ADB" shell input text "$KEY"
sleep 2
"$ADB" shell input keyevent KEYCODE_BACK
echo "typed key of length ${#KEY}"
