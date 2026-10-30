#!/usr/bin/env bash
# 无线开发一键脚本（局域网）
#
# 用法:
#   ./scripts/wireless.sh status             # 看当前有哪些传输
#   ./scripts/wireless.sh connect            # 尝试连上（优先 mDNS 发现，其次固定 IP）
#   ./scripts/wireless.sh install <apk>      # 无线安装
#   ./scripts/wireless.sh logs [行数]        # 无线读日志
#   ./scripts/wireless.sh start <pkg/act>    # 无线启动应用
#   ./scripts/wireless.sh deploy [apk]       # 连接 + 安装 agent + 授权无障碍
#   ./scripts/wireless.sh doctor             # 体检：报告能/不能做什么
#
# 说明:
# - 走的是 Android 无线调试的 TCP 通道（端口 5555）。首次需要 USB 执行 adb tcpip 5555，
#   或使用官方「无线调试」配对后走 TLS 随机端口。
# - 重启手机后 5555 通道失效（service.adb.tcp.port 不持久），需要重新 adb tcpip 或用官方配对。
set -u

ADB="/c/Users/Aqua/AndroidAgent/tools/platform-tools/adb.exe"
FALLBACK_IP="${WIRELESS_IP:-10.0.0.233}"
PORT="${WIRELESS_PORT:-5555}"

dev() { "$ADB" -s "$TARGET" "$@"; }

discover() {
  # 优先官方「无线调试」TLS 通道（重启后依然可用），其次传统 5555 通道
  local line
  line=$("$ADB" mdns services 2>/dev/null | awk '$2=="_adb-tls-connect._tcp" {print $3; exit}')
  if [ -n "${line:-}" ]; then echo "$line"; return 0; fi
  line=$("$ADB" mdns services 2>/dev/null | awk -v p=":$PORT" '$2=="_adb._tcp" && index($3,p)>0 {print $3; exit}')
  if [ -n "${line:-}" ]; then echo "$line"; return 0; fi
  echo "$FALLBACK_IP:$PORT"
}

connect() {
  local addr
  addr=$(discover)
  printf '目标: %s ... ' "$addr"
  "$ADB" connect "$addr" >/dev/null 2>&1
  sleep 1
  if "$ADB" -s "$addr" get-state >/dev/null 2>&1; then
    echo "已连接"
    echo "$addr" > /c/Users/Aqua/AndroidAgent/.wireless_target
    return 0
  fi
  echo "失败"
  echo "排查: 手机是否与电脑同一 Wi-Fi？开发者选项里的「无线调试」是否开启？" >&2
  return 1
}

target() {
  if [ -n "${WIRELESS_TARGET:-}" ]; then echo "$WIRELESS_TARGET"; return; fi
  if [ -f /c/Users/Aqua/AndroidAgent/.wireless_target ]; then
    cat /c/Users/Aqua/AndroidAgent/.wireless_target
  else
    discover
  fi
}

TARGET="$(target)"

cmd="${1:-status}"
case "$cmd" in
  status)
    echo "== 设备列表 =="
    "$ADB" devices -l
    echo
    echo "== 当前使用目标 =="
    echo "$TARGET"
    ;;
  connect)
    connect || exit 1
    ;;
  install)
    [ $# -ge 2 ] || { echo "用法: $0 install <apk>"; exit 1; }
    dev install -r "$2" | tail -2
    ;;
  logs)
    n="${2:-80}"
    dev logcat -d -t "$n" | tail -n "$n"
    ;;
  start)
    [ $# -ge 2 ] || { echo "用法: $0 start <pkg/activity>"; exit 1; }
    dev shell am start -n "$2" | tail -2
    ;;
  deploy)
    apk="${2:-/c/Users/Aqua/AndroidAgent/src/closepaw/app/build/outputs/apk/debug/app-debug.apk}"
    connect || exit 1
    TARGET="$(cat /c/Users/Aqua/AndroidAgent/.wireless_target)"
    dev install -r "$apk" | tail -2
    dev shell settings put secure enabled_accessibility_services ai.closepaw/.app.AgentService
    dev shell settings put secure accessibility_enabled 1
    dev shell cmd appops set ai.closepaw SYSTEM_ALERT_WINDOW allow
    dev shell cmd deviceidle whitelist +ai.closepaw
    echo "部署完成（无障碍已授权）"
    ;;
  doctor)
    echo "== 无线开发体检 =="
    connect >/dev/null 2>&1 || { echo "✗ 无法连接 $TARGET"; exit 1; }
    TARGET="$(cat /c/Users/Aqua/AndroidAgent/.wireless_target)"
    echo "已连接: $TARGET"
    printf '设备: '; dev shell getprop ro.product.model | tr -d '\r'
    printf '系统: '; dev shell getprop ro.build.version.release | tr -d '\r'
    printf '电池: '; dev shell dumpsys battery | awk '/level:/{print $2; exit}'
    printf '锁屏: '; dev shell dumpsys window 2>/dev/null | grep -m1 -o 'isKeyguardShowing=[a-z]*'
    echo "-- 能力 --"
    dev shell "echo shell-ok" >/dev/null 2>&1 && echo "  ✓ shell 命令" || echo "  ✗ shell 命令"
    dev shell "pm list packages ai.closepaw" 2>/dev/null | grep -q ai.closepaw && echo "  ✓ 读取已装应用" || echo "  ✗ 读取已装应用"
    echo "  ✓ 安装 APK / 读日志 / 启动应用（见 install|logs|start 子命令）"
    echo "  ✗ 静默安装（需 Device Owner/root，ADB 无此权限）"
    ;;
  *)
    echo "未知命令: $cmd"; sed -n '3,12p' "$0"
    ;;
esac
