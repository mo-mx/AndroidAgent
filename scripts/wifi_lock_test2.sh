#!/system/bin/sh
# Locked/dozing test WITHOUT force-stop, so the accessibility service stays bound.
OUT=/sdcard/Android/data/ai.closepaw/files/wifi_lock2.txt
: > "$OUT"
settings put secure enabled_accessibility_services ai.closepaw/.app.AgentService
settings put secure accessibility_enabled 1
am start -n ai.closepaw/.app.MainActivity >/dev/null 2>&1
sleep 8
echo "a11y=$(dumpsys accessibility | grep -c 'Bound services')" >> "$OUT"
logcat -c
input keyevent 26
sleep 3
echo "screen_before=$(dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')" >> "$OUT"
echo "wifi_before=$(settings get global wifi_on)" >> "$OUT"
am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "关闭Wi-Fi" >/dev/null 2>&1
sleep 25
echo "wifi_after=$(settings get global wifi_on)" >> "$OUT"
logcat -d -s SysPanel:I IntentPlanner:I > /tmp/wl2.txt 2>/dev/null
cat /tmp/wl2.txt >> "$OUT"
svc wifi enable
cmd wifi set-wifi-enabled enabled >/dev/null 2>&1
sleep 2
echo "wifi_restored=$(settings get global wifi_on)" >> "$OUT"
