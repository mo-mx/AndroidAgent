#!/usr/bin/env bash
# 一键无线投屏 / 电脑控制（scrcpy 5.0.1）
#
# 用法:
#   ./scripts/scrcpy_wireless.sh             # 连上并投屏
#   ./scripts/scrcpy_wireless.sh --record    # 投屏同时录到 screen-<时间>.mp4（用于验收）
#   ./scripts/scrcpy_wireless.sh --no-window # 只连接不显示窗口（配合 --record 做无人验收）
#   ./scripts/scrcpy_wireless.sh --kill      # 关掉已有投屏
#
# 说明：
# - 只使用官方无线调试通道（TLS 随机端口），重启手机后自动重新发现，不依赖 5555。
# - 不修改手机安全设置、不 root、不覆盖已有 App。
set -u

ROOT="C:/Users/Aqua/AndroidAgent"
ADB="$ROOT/tools/android-sdk/platform-tools/adb.exe"
SCRCPY_DIR="$ROOT/tools/scrcpy-win64-v5.0.1"
SCRCPY="$SCRCPY_DIR/scrcpy.exe"
TARGET_FILE="$ROOT/.wireless_target"
LOG="$ROOT/logs/scrcpy.log"
mkdir -p "$ROOT/logs"

export ADB="$ADB"
export PATH="$SCRCPY_DIR:$PATH"

die() { echo "错误: $*" >&2; exit 1; }

discover() {
  local line
  # 1) 官方无线调试 TLS 通道（重启后仍然可用）
  line=$("$ADB" mdns services 2>/dev/null | awk '$2=="_adb-tls-connect._tcp" {print $3; exit}')
  [ -n "${line:-}" ] && { echo "$line"; return 0; }
  # 2) 已记住的地址还活着就直接用
  if [ -f "$TARGET_FILE" ]; then
    local t; t=$(cat "$TARGET_FILE" | tr -d '\r\n')
    if "$ADB" -s "$t" get-state >/dev/null 2>&1; then echo "$t"; return 0; fi
  fi
  # 3) mDNS 失败时扫当前已连接设备
  "$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}'
}

ensure_connected() {
  local addr="${1:-}"
  [ -n "$addr" ] || die "没有发现设备。请确认：手机与电脑同一 Wi-Fi、开发者选项里「无线调试」已开启。"
  if ! "$ADB" -s "$addr" get-state >/dev/null 2>&1; then
    echo "连接 $addr ..."
    "$ADB" connect "$addr" >/dev/null 2>&1
    sleep 1
    "$ADB" -s "$addr" get-state >/dev/null 2>&1 || die "连接 $addr 失败"
  fi
  echo "$addr" > "$TARGET_FILE"
  echo "$addr"
}

case "${1:-}" in
  --kill)
    powershell -NoProfile -Command "Get-Process scrcpy -ErrorAction SilentlyContinue | Stop-Process -Force" 2>/dev/null
    echo "已关闭投屏进程"; exit 0;;
esac

ADDR=$(ensure_connected "$(discover)")
MODEL=$("$ADB" -s "$ADDR" shell getprop ro.product.model | tr -d '\r\n')
echo "设备: $MODEL @ $ADDR"
echo "scrcpy: $("$SCRCPY" --version | head -1)"

# 参数：键盘用 uhid 以便直接输入中文目标外的字符；不做音频转发（手机端音频还归 TTS 用）
ARGS=(
  --serial "$ADDR"
  --window-title "ZFlip5 $ADDR"
  --keyboard=uhid
  --max-size=1080
  --max-fps=60
  --no-audio
  --stay-awake
  --power-off-on-close
)

RECORD=""
for a in "$@"; do
  case "$a" in
    --record) RECORD="$ROOT/screen-$(date +%Y%m%d-%H%M%S).mp4";;
    --no-window) ARGS+=(--no-window);;
    --max-size=*) ARGS+=("$a");;
  esac
done
[ -n "$RECORD" ] && ARGS+=(--record "$RECORD" --record-format mp4)

echo "启动: scrcpy ${ARGS[*]}"
"$SCRCPY" "${ARGS[@]}" 2>&1 | tee "$LOG"
rc=${PIPESTATUS[0]}
echo "---- 退出码 $rc，日志 $LOG"
[ -n "$RECORD" ] && [ -f "$RECORD" ] && {
  sz=$(stat -c%s "$RECORD" 2>/dev/null || echo 0)
  echo "录像: $RECORD ($sz 字节)"
}
exit $rc
