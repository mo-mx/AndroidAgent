#!/system/bin/sh
# Phase-1 acceptance: accessibility lifecycle across toggles / lock / app switch.
# Deliberately NO am force-stop: that is what was destroying the binding in earlier tests.
OUT=/sdcard/Android/data/ai.closepaw/files/a11y_cycle.txt
: > "$OUT"
STATUS=/sdcard/Android/data/ai.closepaw/files/a11y_status.txt

snap() {
  echo "--- $1" >> "$OUT"
  echo "bound=$(dumpsys accessibility | grep -c 'service\[ClosePaw')" >> "$OUT"
  echo "setting=$(settings get secure enabled_accessibility_services)" >> "$OUT"
  echo "wifi=$(settings get global wifi_on)" >> "$OUT"
  echo "power=$(dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')" >> "$OUT"
}

logcat -c
snap start

# 1) Wi-Fi off through the system panel
am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "关闭Wi-Fi" >/dev/null 2>&1
sleep 22
snap after_wifi_off

# 2) Wi-Fi on through the system panel
am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "打开Wi-Fi" >/dev/null 2>&1
sleep 25
snap after_wifi_on

# 3) lock the screen and speak while locked
input keyevent 26
sleep 3
am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "还有多少电" >/dev/null 2>&1
sleep 12
snap while_locked
input keyevent 26
sleep 2

# 4) switch apps away and back
am start -a android.settings.SETTINGS >/dev/null 2>&1
sleep 4
input keyevent 4
sleep 2
snap after_app_switch

# 5) one more panel toggle to prove it still works after all that
am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "关闭Wi-Fi" >/dev/null 2>&1
sleep 22
am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "打开Wi-Fi" >/dev/null 2>&1
sleep 25
snap final

logcat -d -s A11yHealth:I SysPanel:I IntentPlanner:I AgentService:I > /tmp/a11y_cycle_log.txt 2>/dev/null
cat /tmp/a11y_cycle_log.txt >> "$OUT"
echo "--- a11y_status.txt" >> "$OUT"
cat "$STATUS" >> "$OUT" 2>/dev/null

svc wifi enable
cmd wifi set-wifi-enabled enabled >/dev/null 2>&1
sleep 2
echo "wifi_restored=$(settings get global wifi_on)" >> "$OUT"
