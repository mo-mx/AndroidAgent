#!/system/bin/sh
# Execution regression v2: wake the screen before each command (UI automation needs a live window).
OUT=/sdcard/Android/data/ai.closepaw/files/exec2.txt
: > "$OUT"
logcat -c
say() {
  echo "CMD: $1" >> "$OUT"
  input keyevent 224 >/dev/null 2>&1
  sleep 1
  am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "$1" >/dev/null 2>&1
  sleep "$2"
}

say "查询倒计时" 18
say "设置五分钟倒计时" 22
say "查询倒计时" 18
say "取消五分钟倒计时" 24
say "查询倒计时" 18
say "关闭十一点闹钟" 22

logcat -d > /tmp/e2.txt 2>/dev/null
grep -E "SemanticRunner|ClockControl|heard\(" /tmp/e2.txt >> "$OUT"
echo "done=$(date)" >> "$OUT"
