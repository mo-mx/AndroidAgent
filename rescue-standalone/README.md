# rescue-standalone —— 手机救援服务的独立工程

这个目录是 **`:rescue` 模块的可独立构建副本**，用来把救援服务单独分发（另一个仓库、另一个 APK、
另一台机器上构建），不改动、也不依赖主工程里的 `src/closepaw/`。

> **正本仍在 `src/closepaw/rescue/`。** 本目录是从它复制出来的快照，主工程原文件没有被移动或修改。
> 两边都改会导致代码分叉，所以约定：功能改动先落在 `src/closepaw/rescue/`，需要分发时再同步到这里，
> 并在提交信息里写明同步的源提交。

构建验证（2026-11-21，Windows 11 + JDK 17 + 仓库内 android-sdk）：

```
JAVA_HOME=<repo>/tools/jdk-17.0.20.1+1  ANDROID_HOME=<repo>/tools/android-sdk
./gradlew --offline :rescue:assembleDebug
→ BUILD SUCCESSFUL in 42s，35 tasks executed
→ rescue/build/outputs/apk/debug/rescue-debug.apk
```

---

## 它是什么

安卓上一条**重启后不解锁也能用**的出站通道。核心约束是"不做任何越权的事"：

| 能力 | 做法 |
|---|---|
| 未解锁即可启动 | `directBootAware` + 接收 `LOCKED_BOOT_COMPLETED` / `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` |
| 能常驻 | 独立进程 `:rescue` 的前台服务（`specialUse` 类型，Android 15+ 允许从 BOOT 启动的唯一类型） |
| 不泄露状态 | 配置只放 Device Protected Storage（`createDeviceProtectedStorageContext()`），不含密码/短信/联系人 |
| 不做端口暴露 | **只有出站**，不开入站端口；NAT 后面、Wi-Fi 或移动数据都能通 |
| 认证 | TLS + HMAC-SHA256(`ts` / `path` / `body`) + 120 秒时间窗 + 操作白名单 |
| 未解锁时的降级 | 所有依赖 CE 存储的能力（`Settings.Secure`、TTS、拉起他人 Activity）先检测，明确返回 `unsupported` |

操作白名单见 `rescue/src/main/kotlin/ai/closepaw/rescue/RescueOps.kt`：`status`（含电量/屏幕/uptime/`user_unlocked`）、
`speak`、`start_agent` 等。加操作要同时改手机端白名单和 PC 端 `tools/rescue_relay.py`。

## 组成

```
rescue-standalone/
├── rescue/                        Android 模块（applicationId ai.closepaw.rescue）
│   ├── build.gradle.kts           端点/密钥/证书 pin 从 Gradle 属性或环境变量注入，默认空 → 拒绝启动并写日志
│   └── src/main/kotlin/ai/closepaw/rescue/
│       ├── BootReceiver.kt        LOCKED_BOOT_COMPLETED / BOOT_COMPLETED / MY_PACKAGE_REPLACED
│       ├── RescueService.kt       :rescue 进程前台服务，轮询中继
│       ├── RescueClient.kt        出站 HTTPS + HMAC 签名
│       ├── RescueOps.kt           操作白名单 + 未解锁降级
│       ├── RescueConfig.kt        DP 存储里的 endpoint / device_id
│       ├── RescueSpeaker.kt       TTS 播报（解锁后才可用）
│       ├── RescueActivity.kt      需要界面时的兜底入口
│       └── PinnedTls.kt           证书 pin
├── tools/rescue_relay.py          PC 端中继（TLS 服务 + 命令队列 + 结果日志）
├── gradle/ gradlew gradlew.bat    Gradle 8.11.1 wrapper
└── settings.gradle.kts            只有 :rescue 一个模块
```

## 构建

```bash
# 1. 准备凭据（不要写进仓库；三项都可以用环境变量或 -P 属性传）
export RESCUE_ENDPOINT="https://你的中继:8765"   # 或 https 隧道地址
export RESCUE_SECRET="<与中继共享的密钥>"
export RESCUE_PIN="<可选：证书 SPKI pin>"

# 2. 构建
JAVA_HOME=<JDK17> ANDROID_HOME=<AndroidSDK> ./gradlew :rescue:assembleDebug
# 产物：rescue/build/outputs/apk/debug/rescue-debug.apk

# 3. 安装（ADB 只用于部署，正式运行不依赖 ADB）
adb install -r rescue/build/outputs/apk/debug/rescue-debug.apk
```

## 跑起来

```bash
# PC 端：初始化并启动中继
python tools/rescue_relay.py init --secret "<同一个密钥>" --port 8765
python tools/rescue_relay.py serve

# 查看/下发
python tools/rescue_relay.py devices
python tools/rescue_relay.py send status
python tools/rescue_relay.py send speak --text "机主，我在线"
python tools/rescue_relay.py results --n 20
```

中继数据写在脚本旁边的 `./relay_data/`（`relay.json` 密钥、`queue.json` 待办、`results.jsonl` 结果、
`devices.jsonl` 最后在线）。这四个文件都已在 `.gitignore` 之外，别手动提交。

## 已验证 / 未验证（照实写）

**实机验证（SM-F731U1 / Android 17 / One UI 9，2026-10-30）**
- 重启后**不解锁**：43 秒内中继收到心跳，`diagnostics` 返回 `user_unlocked=false`、`direct_boot=true`、
  电量、`screen_on=false`、`uptime_s=61`
- Wi-Fi 与蜂窝（cloudflared 隧道）双通道都通；解锁后 CE 能力自动恢复
- 详细时间线与日志原文：`../docs/direct-boot-rescue-report.md`

**未验证**
- 长时间驻留（跨天）后 `:rescue` 进程是否被国产 ROM / 省电策略杀掉，未做整机 soak
- 证书 pin 变更时的轮换流程只跑过一次手工流程
- 多设备共用一个中继时的隔离只按 `device_id` 区分，未做压力测试

## 与主工程的关系

- 主工程把它作为 `:rescue` 模块编进同一个 Gradle 构建（`src/closepaw/settings.gradle.kts` 里 `include(":rescue")`）。
- 本目录是**同一个模块的独立工程副本**，两者都产出 `ai.closepaw.rescue`。**不要同时把两个 APK 装到同一台手机上**
  （同 applicationId 会互相覆盖），选其中一个。
- 主工程里的 Agent App 与救援 APK 是协作关系，不是依赖关系：Agent 负责日常操作，救援负责"人不在、机锁着"时的最小通道。
