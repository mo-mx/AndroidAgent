#!/system/bin/sh
# Offline acceptance: 20 commands with Wi-Fi + mobile data OFF.
# Runs entirely on the device; re-enables Wi-Fi at the end so the PC can pull the results.
LOG=/sdcard/Android/data/ai.closepaw/files/acc_result.txt
mkdir -p /sdcard/Android/data/ai.closepaw/files
: > "$LOG"
echo "start $(date)" >> "$LOG"

# NOTE: force-stop would make One UI delete the accessibility registration.
# Use "am start" instead: the activity is recreated without touching the a11y binding.
logcat -c
svc wifi disable
svc data disable
sleep 4
echo "wifi_on=$(settings get global wifi_on) data=$(settings get global mobile_data)" >> "$LOG"

i=0
while IFS= read -r c; do
  i=$((i+1))
  echo "CMD$i: $c" >> "$LOG"
  am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "$c" >/dev/null 2>&1
  sleep 9
done <<'EOF'
还有多少电
现在几点
打开手电筒
关闭手电筒
手电筒开着吗
Wi-Fi 开着吗
蓝牙开了没有
现在有没有网络
音量是多少
把媒体音量调到百分之三十
存储空间还剩多少
屏幕锁着吗
系统版本是多少
还有多少电，打开手电筒
关闭手电筒，告诉我现在几点
看看蓝牙有没有开，如果没开就告诉我
打开手电筒，然后关闭它
打开Wi-Fi
给妈妈打电话
打开相机，录五秒视频
EOF

logcat -d -s IntentPlanner:I VoiceSession:I LocalCaps:I TorchControl:I WifiControl:I LocalCmd:I VoiceActivity:I > /tmp/acc_logcat.txt 2>/dev/null
cat /tmp/acc_logcat.txt >> "$LOG"
echo "end $(date)" >> "$LOG"

svc wifi enable
cmd wifi set-wifi-enabled enabled >/dev/null 2>&1
sleep 2
echo "wifi_restored=$(settings get global wifi_on)" >> "$LOG"
