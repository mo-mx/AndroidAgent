#!/system/bin/sh
OUT=/sdcard/Android/data/ai.closepaw/files/parse_only.txt
: > "$OUT"
logcat -c
run() {
  am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "$1" --ez parse_only true >/dev/null 2>&1
  sleep 2
}
run "设置五分钟倒计时"
run "取消五分钟倒计时"
run "再次取消这个五分钟倒计时"
run "设置十点闹钟"
run "关闭十点闹钟"
run "关闭十一点闹钟"
run "查询闹钟"
run "打开三星应用商店"
run "打开Google Play"
run "打开微信扫一扫"
run "打开手电筒"
run "打开应用商店"
run "打开相机后录像五秒"
run "打开手电筒然后关闭"
run "如果蓝牙已经打开就不要修改"
run "取消刚才的操作"
sleep 1
logcat -d -s VoiceActivity:I > /tmp/po.txt 2>/dev/null
grep PARSEONLY /tmp/po.txt >> "$OUT"
echo "done=$(date)" >> "$OUT"
