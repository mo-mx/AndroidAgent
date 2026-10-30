# 无线开发环境（第一阶段）实测报告

时间：2026-10-30 21:27–21:40
设备：SM-F731U1 / Android 17 (SDK 37) / One UI 9，IP 10.0.0.233；电脑 10.0.0.163，同 /24

## 1. 支持情况（实测）

| 项 | 结果 |
|---|---|
| 系统版本 | Android 17 / SDK 37 / One UI 9 |
| 官方无线调试（API 30+） | 支持；`adb_wifi_enabled` 可置 1，设置里「无线调试」入口存在 |
| 传统 TCP 通道（`adb tcpip 5555`） | ✅ 已启用并跑通全部开发操作 |
| mDNS 自动发现 | ✅ `adb mdns services` 能发现 `adb-<device-serial-redacted>-... _adb._tcp 10.0.0.233:5555` |
| 官方 TLS 配对通道 | 支持，但需要手机屏上的一次性 6 位配对码（见 §4 待办） |

## 2. 已实测通过（全部走 Wi-Fi，命令只指定 TCP 传输 `-s 10.0.0.233:5555`）

1. **无线安装 APK**：`adb -s 10.0.0.233:5555 install -r rescue-debug.apk` → `Success`
2. **无线读日志**：`logcat -d -t 40` 正常取到日志行
3. **无线启动应用**：`am start -n ai.closepaw.rescue/.RescueActivity` 正常拉起
4. **无线取状态**：`dumpsys battery`、`wm size`、`getprop` 均正常
5. **断线重连**：`adb disconnect` 后 `wireless.sh connect` 秒级恢复（mDNS 自动发现地址）
6. **锁屏/息屏下**：`mWakefulness=Dozing` 状态下 shell 与 APK 安装仍然成功
7. **Wi-Fi 切断后**：TCP 传输立刻掉线（`device offline`）；Wi-Fi 恢复且 IP 不变后，
   重新 `adb connect` 即恢复，无需重新授权

一键脚本：`scripts/wireless.sh {status|connect|install|logs|start|deploy|doctor}`
（`doctor` 会打印设备/系统/电池/锁屏状态与能力边界）

## 3. 真实限制（不粉饰）

| 限制 | 原因 | 影响 |
|---|---|---|
| **重启后 5555 通道失效** | `adb tcpip` 只改运行时 `service.adb.tcp.port`，不持久；持久化需要 root 改 `persist.adb.tcp.port` | 重启后必须插 USB 重新执行一次，或用官方「无线调试」配对通道 |
| 切 Wi-Fi / 改 IP 会掉线 | TCP 传输绑定原 IP:端口 | 需重新 `adb connect`；IP 若变化要重新发现 |
| 开关/关闭「开发者选项」会失效 | 系统重置 adb 状态 | 同上 |
| 局域网内任何能配对的主机都可控机 | ADB 无线调试的安全模型就是"配对即可控" | 只在可信 Wi-Fi 使用；不要在企业/公共网络开启 |
| 无法静默安装 | ADB `install` 是 shell 权限，不是系统签名 | 与"静默安装"无关的需求别指望 adb |
| 首次配对必须人操作一次 | Android 11+ 设计如此（配对码/二维码） | 属于"一次性授权"，可接受 |

## 4. 待你配合的两步（我可以立刻验收）

1. **官方无线调试配对（让连接在重启后也能重建）**
   手机：设置 → 开发者选项 → 无线调试 → **使用配对码配对设备**
   把屏上的 **6 位配对码**告诉我（端口我用 mDNS 自动发现）。
   我会执行 `adb pair`，然后 `adb connect`，并验证重启后是否自动可用。
2. **拔掉 USB**（物理动作，我做不了），然后我跑一遍 `wireless.sh deploy` 做真·无线部署验收。

## 5. 第二阶段（跨网络救援）进度快照 —— 已冻结，等你确认

在第一阶段之前已经写了客户端与中继，并已跑通：
- 手机端 APK `ai.closepaw.rescue`（独立进程 `:rescue`，不依赖主 Agent）
- 出站 TLS + HMAC-SHA256 签名 + 时间戳窗（120 s），证书固定（自签中继）
- 中继 `tools/rescue_relay.py`（PC 侧，HTTPS 8765）
- 实测：手机每 3 s 轮询中继，`status` 指令返回完整 JSON（电量、充电、屏幕、Agent 是否安装/版本/无障碍是否启用、能力清单）
- 明确标注不支持：静默安装、改无障碍开关、读别的应用日志

尚未做的：移动数据（需要公网中继，cloudflared 隧道已下载）、APK 下发与签名校验、审计日志、重启恢复验证。
按你的要求，这些等第一阶段验收完再继续。
