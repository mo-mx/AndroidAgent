#!/system/bin/sh
# Execution regression: the dangerous direction is CREATE-on-CANCEL.
OUT=/sdcard/Android/data/ai.closepaw/files/exec_regress.txt
: > "$OUT"
logcat -c
say() {
  echo "CMD: $1" >> "$OUT"
  am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "$1" >/dev/null 2>&1
  sleep "$2"
}

say "查询倒计时" 18
say "取消五分钟倒计时" 20
say "查询倒计时" 18
say "设置五分钟倒计时" 20
say "查询倒计时" 18
say "取消五分钟倒计时" 22
say "查询倒计时" 18
say "关闭十一点闹钟" 20
say "查询闹钟" 18

logcat -d -s SemanticRunner:I SemanticParser:I ClockControl:I AppNavigator:I VoiceActivity:I > /tmp/er.txt 2>/dev/null
cat /tmp/er.txt >> "$OUT"
echo "done=$(date)" >> "$OUT"
