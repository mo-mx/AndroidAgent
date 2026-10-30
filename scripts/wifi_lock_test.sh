#!/system/bin/sh
OUT=/sdcard/Android/data/ai.closepaw/files/wifi_lock_test.txt
: > "$OUT"
# NOTE: force-stop would make One UI delete the accessibility registration.
# Use "am start" instead: the activity is recreated without touching the a11y binding.
settings put secure enabled_accessibility_services ai.closepaw/.app.AgentService
settings put secure accessibility_enabled 1
am start -n ai.closepaw/.app.MainActivity >/dev/null 2>&1
sleep 7
logcat -c
input keyevent 26
sleep 3
echo "screen_before=$(dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')" >> "$OUT"
echo "wifi_before=$(settings get global wifi_on)" >> "$OUT"
am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "关闭Wi-Fi" >/dev/null 2>&1
sleep 25
echo "wifi_after=$(settings get global wifi_on)" >> "$OUT"
echo "screen_after=$(dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')" >> "$OUT"
logcat -d -s SysPanel:I IntentPlanner:I VoiceActivity:I > /tmp/wl.txt 2>/dev/null
cat /tmp/wl.txt >> "$OUT"
svc wifi enable
cmd wifi set-wifi-enabled enabled >/dev/null 2>&1
sleep 2
echo "wifi_restored=$(settings get global wifi_on)" >> "$OUT"
