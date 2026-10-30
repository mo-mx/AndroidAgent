@echo off
chcp 65001 >nul
title scrcpy wireless - Z Flip5
set "PATH=C:\Users\Aqua\AndroidAgent\tools\scrcpy-win64-v5.0.1;C:\Users\Aqua\AndroidAgent\tools\android-sdk\platform-tools;%SystemRoot%\system32;%SystemRoot%;%PATH%"
echo Discovering device...
"C:\Program Files\Git\bin\bash.exe" -lc "/c/Users/Aqua/AndroidAgent/scripts/scrcpy_wireless.sh %*"
if errorlevel 1 pause
