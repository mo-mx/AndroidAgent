# Direct Boot 无屏救援实验报告

时间：2026-10-30 21:45–21:56　设备：SM-F731U1 / Android 17 (SDK 37) / One UI 9
全部实测，无 Root、未关闭锁屏、未保存 PIN、未绕过任何认证。

## 1. 实现要点

| 要求 | 落地方式 | 文件 |
|---|---|---|
| `directBootAware` + `LOCKED_BOOT_COMPLETED` | application / service / receiver 全部标 `android:directBootAware="true"`，接收 `LOCKED_BOOT_COMPLETED` + `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` | `rescue/src/main/AndroidManifest.xml`、`BootReceiver.kt` |
| 设备加密存储（DP） | `createDeviceProtectedStorageContext().filesDir` 存 endpoint / device_id；不含任何密码、联系人、消息 | `RescueConfig.kt` |
| 最小救援组件 | 独立进程 `:rescue` 的前台服务（`specialUse` 类型，这是 Android 15+ 允许从 BOOT 启动的唯一类型） | `RescueService.kt` |
| 认证与审计 | 出站 TLS + HMAC-SHA256(ts/path/body) + 120 s 时间窗；无入站端口；操作白名单 | `RescueClient.kt`、`tools/rescue_relay.py` |
| 未解锁时的能力降级 | 所有 CE 依赖（Settings.Secure、TTS、启动他人 Activity）都做了检测与明确 `unsupported` 返回 | `RescueOps.kt` |

## 2. 核心验收：重启后不解锁，电脑收到心跳 + 真实诊断 ✅

```
时间线 21:48:53 reboot  →  21:49:36 中继收到心跳（screen_on=False）
锁屏状态证明：isKeyguardShowing=true，sys.boot_completed=1

diagnostics 返回：
  user_unlocked: false        ← 从未输入过锁屏密码
  direct_boot:   true
  battery: 98, screen_on: false, uptime_s: 61
  network: wifi validated: true
  agent: {installed: true, version: "0.1.0"}
  capabilities: {launch_installed_app: false, speak: false, read_own_status: true, read_network: true}
  config: dpDir=/data/user_de/0/ai.closepaw.rescue/files, dp_files=device_id.txt,endpoint.txt
  relay_roundtrip_ms: 83, relay_http: 200
```

即：**未解锁状态下，救援客户端自己启动、自己出站、通过 HMAC 认证拿到了至少一项真实诊断**。
这一条不是用 adb 连接成功代替的 —— 是通过中继的 `/poll` + `/result` 往返证明的。

## 3. 网络双通道验证 ✅

| 网络 | 结果 | 证据 |
|---|---|---|
| Wi-Fi（局域网直连，TLS + 证书固定） | ✅ | 心跳 + status/diagnostics/start_agent/speak 全部成功，`relay_roundtrip_ms: 83` |
| 蜂窝数据（Wi-Fi 关闭，公网隧道 cloudflared） | ✅ | `network: cellular, validated: true`，status 往返成功；start_agent 返回 `launched: ai.closepaw`；speak 播报成功 |

## 4. 解锁后恢复（第 7 条）✅

- `isKeyguardShowing=false`
- 无障碍服务自动重绑：`id=ai.closepaw/.app.AgentService`
- 救援能力从降级态恢复：`launch_installed_app: true, speak: true, accessibility_enabled: true`
- 远程 `start_agent` 成功拉起主 Agent；无线 ADB 通道（TLS 端口每次开机变化，本轮 46743）也随之恢复

## 5. NFC 研究结论（第 6 条，未作为唯一方式）

- **标签触发（`ACTION_NDEF_DISCOVERED` / ReaderMode）在锁屏下不可用**：Android 的标签派发只在设备已解锁时进行；
  息屏时 NFC 轮询通常还会被关闭（NFC service 会 disable discovery）。
- **HCE（HostApduService）可以在锁屏下响应**，条件是 `android:requireDeviceUnlock="false"` /
  `requireDeviceScreenOn="false"`，且需要外部读卡器主动下发 APDU，并且部分机型/AID 需要被选为默认支付应用。
  这不是"贴一下标签"，而是"手机当卡被读"，硬件与操作成本都更高。
- 结论：**NFC 只能作为辅助入口，且必须由 HCE 实现，不能作为唯一触发**。本轮未实现（工程量与收益不匹配），
  记录为可选后续项。

## 6. 发现的新限制（比功能本身更重要）

1. **未解锁时无法修改救援配置**：`run-as` 在 Direct Boot 期间不可用（CE 数据目录尚未解密），
   所以 endpoint 之类的文件只能在解锁后写入。→ 生产环境应把"改中继地址"做成带 HMAC 签名的受控指令，
   而不是依赖文件写入。
2. **未解锁时主 Agent 完全不可用**：不是权限问题，是它自己的数据在 CE 存储里；
   救援客户端只能做"状态 + 拉起 + 播报"这类 DE 范围内的事。
3. **`specialUse` 前台服务是这个场景唯一可行的 FGS 类型**；`dataSync`/`microphone` 从 BOOT 启动会被拒。
4. 移动数据必须是用户开启状态；移动数据关闭时，Wi-Fi 一断设备就彻底离线（本轮实际踩到）。

## 7. 复现步骤

```bash
# 1) 构建（endpoint/secret/pin 在构建时注入）
python scripts/build_rescue.py
# 2) 无线安装 + 打开一次救援面板（写入 DP 配置）
adb -s <ip:port> install -r src/closepaw/rescue/build/outputs/apk/debug/rescue-debug.apk
adb -s <ip:port> shell am start -n ai.closepaw.rescue/.RescueActivity
# 3) 重启手机，不解锁
adb -s <ip:port> reboot
# 4) 中继侧观察
python tools/rescue_relay.py devices
python tools/rescue_relay.py send diagnostics
python tools/rescue_relay.py results --n 1
```
