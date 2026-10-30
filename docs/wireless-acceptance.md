# 无线开发环境（第一阶段）验收报告

时间：2026-10-30 21:27–21:44　设备：SM-F731U1 / Android 17 / One UI 9
电脑：10.0.0.163（Win11）　手机：10.0.0.233（同一 Wi-Fi）
**全程 USB 已拔除**（21:36 起）

## 1. 采用的两条通道

| 通道 | 地址 | 特性 |
|---|---|---|
| 官方「无线调试」TLS | 10.0.0.233:35927 → 重启后 **45563** | 走系统配对凭据，**重启后自动恢复**，端口每次启动会变，靠 mDNS `_adb-tls-connect._tcp` 自动发现 |
| 传统 `adb tcpip 5555` | 10.0.0.233:5555 | 需要 USB 一次性开启，**重启即失效**（`service.adb.tcp.port` 不持久） |

已把 SSH/脚本的发现逻辑改成**优先 TLS 通道，回退 5555**（`scripts/wireless.sh`）。

## 2. 验收矩阵（全部真机实测）

| 验收要求 | 结果 | 证据 |
|---|---|---|
| 拔掉 USB 后，同 Wi-Fi 连接并部署 APK | ✅ | 235 MB `app-debug.apk` 无线安装 **14 秒** 成功；之后 `logcat`、`am start`、`settings put secure` 全部走 Wi-Fi |
| 无线授权无障碍并生效 | ✅ | `dumpsys accessibility` 显示 `Bound services: Service[label=ClosePaw ... id=ai.closepaw/.app.AgentService]` |
| 纯无线跑通完整 Agent 任务 | ✅ | 语音入口注入指令 → `Task ... completed (outcome=GOAL_ACHIEVED)`，播报 188 字 |
| 锁屏状态下管理能力 | ✅ | 锁屏 SQL：`dumpsys window isKeyguardShowing=true` 时，`install`、`shell`、`logcat` 均成功 |
| 手机重启后服务恢复 | ✅ | 重启后 TLS 端口变为 45563（mDNS 自动发现）；无障碍服务**自动重绑**；重启后再次跑任务 `GOAL_ACHIEVED` |
| 重启后**未首次解锁**时的能力 | ✅ 有惊喜 | 见 §4 |
| 电脑重启后重建连接 | ⏳ 部分验证 | adb server 重启（`kill-server`/`start-server`）后自动恢复；整机重启会杀掉我自己的会话进程，需改天单独做 |
| 换一个 Wi-Fi 后远程开发 | ⏳ 未做 | 需要另一个 AP/热点，你提供热点名和密码我就能测 |
| 关闭 Wi-Fi 仅用移动数据 | ⏳ 阶段二 | 需要公网中继（cloudflared 已下载好），按你的要求等第一阶段收尾后再做 |

## 3. 重启前后的关键数据

```
重启前: TLS 端口 35927, adb_wifi_enabled=1, rescue pid 20751
重启后: TLS 端口 45563（mDNS 广播）, 5555 端口 拒绝连接（符合预期）
        手机未解锁时 rescue 服务已自动启动, uptime=121s 就回连中继
        解锁后: 无障碍自动重绑, Agent 任务成功
```

## 4. 「重启但尚未首次解锁」实测结论

这轮最意外的发现：**未解锁时无线 ADB 就能用**（因为 adb 密钥在设备加密存储里，adbd 在开机早期就起）。

| 能力 | 未解锁 | 已解锁 |
|---|---|---|
| 无线 ADB `shell` / `install` / `logcat` | ✅ 可用 | ✅ |
| 救援客户端 `status`（电量/屏幕/Agent 状态/能力清单） | ✅ 可用 | ✅ |
| 救援客户端 `start_agent`（后台拉起 Agent） | ✅ 返回 `launched: ai.closepaw` | ✅ |
| 救援客户端 `speak`（TTS 播报） | ✅ 已播报 | ✅ |
| 直接用 Agent 执行屏幕任务 | ❌ 需解锁（锁屏挡住界面，无障碍虽绑定但无法操作） | ✅ |

## 5. 真实限制（写进规范，别再踩）

1. `adb tcpip 5555` **不持久**，重启后必须插 USB 或改用官方无线调试。
2. 官方无线调试的**端口每次开机都会变**（35927 → 45563），脚本必须用 mDNS 发现，不能写死。
3. 关 Wi-Fi / 关开发者选项 / 重启 → 现有连接立即失效；Wi-Fi 恢复且 IP 不变时，重新 `adb connect` 即恢复。
4. 手机装了 Clash Meta（VPN 开启时），无线调试界面显示的地址可能是 `172.19.0.1:端口`（VPN 网段），
   **实际连 LAN IP 才对**；这一点容易误判。
5. 局域网无线调试 = 能配对就能控机；只在可信 Wi-Fi 开。
6. 仍然**不能静默安装**：adb install 是 shell 权限，不是系统签名。

## 6. 日常用法（USB 变备用）

```bash
bash scripts/wireless.sh connect        # mDNS 自动发现并连上（优先 TLS）
bash scripts/wireless.sh doctor         # 体检：设备/系统/电池/锁屏/能力
bash scripts/wireless.sh deploy <apk>   # 无线安装 + 授权无障碍
bash scripts/wireless.sh logs 120       # 无线读日志
bash scripts/wireless.sh start ai.closepaw/.app.MainActivity
```

USB 只在三种情况用：首次开启无线调试、无线调试被关掉、彻底连不上时兜底。
