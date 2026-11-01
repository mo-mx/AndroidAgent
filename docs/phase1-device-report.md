# 第一阶段：设备环境报告

采集时间：2026-10-30 19:36–19:38（CST）
采集方式：Windows 11 + platform-tools 37.0.1，ADB 实机读取（只读，未安装、未修改系统）
原始日志：`logs/phase1-20261030-193658.txt`、`logs/phase1b-20261030-193723.txt`

---

## 1. 已确认真实的硬件与系统信息

| 项目 | 实测值 | 备注 |
|---|---|---|
| 连接序列号 | <device-serial-redacted> | 稳定，3 次 ping 全部 pong |
| 型号 | SM-F731U1（product `b5quew`，device `b5q`） | 美版无锁 XAA |
| 系统 | **Android 17**（`ro.build.version.release=17`，SDK **37**） | 与常见资料里的 One UI 6/7 不同，实际是最新 One UI 9 |
| One UI | `ro.build.version.oneui=90000`（=9.0），`sec_settings_version_one_ui=90000` | |
| 编译版本 | CP2A.260605.016 / F731U1UEU8HZIJ | |
| 安全补丁 | 2026-10-05 | |
| SoC | 高通 SM8550（骁龙 8 Gen 2） | |
| CPU ABI | arm64-v8a（+ armeabi-v7a, armeabi） | 全部 arm64 工具可用 |
| 内存 | MemTotal 7,345,652 kB（≈7.0 GB），MemAvailable ≈1.9 GB；zram swap 12.6 GB | 8 GB 物理 |
| 存储 | /data 462 G，已用 16 G，**可用 445 G** | 512 GB 机型 |
| 屏幕 | 1080×2640，密度 480 | 折叠状态见第 5 节 |
| 电池 | 89%，充电中，温度 33 ℃ | |
| SIM / 网络 | 中国联通，LTE，CS 域 HOME，VOICE/SMS/VIDEO 可用 | 通话与短信能力确认可用 |
| 定位 | location_mode=3（高精度），已开启 | 应急定位可用 |
| 硬件特性 | camera/front/flash/raw、microphone、telephony、gps、nfc、telephony.satellite | |
| 当前屏幕 | 锁屏中（isKeyguardShowing=true，deviceLocked=1），已设密码/生物识别 | 后续无人值守测试需要在解锁态进行 |

折叠态机状态机（`dumpsys device_state`）实测枚举：
`CLOSED(app_accessible=false)`、`TENT(false)`、`HALF_OPENED(true)`、`OPENED(true)`、`CONCURRENT_INNER_DEFAULT(true, cancel_when_requester_not_on_top=true)`。
→ **合盖时系统明确标记 app_accessible=false**，第三方应用界面无法在合盖态展示，这一点直接决定第四阶段（合盖唤醒）的现实上限。

## 2. 电脑侧工具链

| 组件 | 状态 |
|---|---|
| ADB (platform-tools) | 已装：37.0.1，路径 `C:\Users\<user>\AndroidAgent\tools\platform-tools\adb.exe` |
| JDK | 已装：Temurin **17.0.20.1**，路径 `tools\jdk-17.0.20.1+1` |
| Android SDK cmdline-tools | 已装：13114758 |
| platforms / build-tools | 正在安装（platforms;android-36、build-tools;36.0.0） |
| Gradle | 不单独安装，用项目自带 wrapper |
| 环境脚本 | `C:\Users\<user>\AndroidAgent\env.sh`（ANDROID_HOME / JAVA_HOME / PATH / ADB） |

项目目录：`C:\Users\<user>\AndroidAgent\`（tools / scripts / logs / docs / src）
ClosePaw 源码已克隆：`src\closepaw`（37 MB，Apache-2.0）

## 3. 助手与权限现状（实测）

- 默认助手角色 `android.app.role.ASSISTANT` 持有者：`com.google.android.googlequicksearchbox`，`fallback_enabled=true` → **角色可被第三方替换**（不是被三星锁死）。
- `settings secure voice_interaction_service` = Google 的 `GsaVoiceInteractionService`；`assistant` 同值。
- `voice_recognition_service` = Google 语音识别服务。
- 系统内 VoiceInteractionService 候选只有 1 个（Google）；Bixby 不在该列表，Bixby 走自己的 `com.samsung.android.bixby.wakeup` 热词通道。
- 无障碍：`accessibility_enabled=0`，当前 **未启用任何无障碍服务**（ClosePaw 需要用户手动开启）。
- 侧键：`global power_button_long_press=103`、`side_button=1`、`sip_voice_input_use_side_key=1`，`app_shortcuts_command_a=android.app.role.ASSISTANT`（说明长按/快捷键会拉起"默认助手角色"）。
- 热词相关系统组件已存在：`com.samsung.android.bixby.wakeup`、`bixby.ondevice.enus/esus`（三星自带离线唤醒）。
- 电源策略：AOD 开启（`aod_mode=1`）、`wake_gesture_enabled=1`、Doze 全套开启、`app_standby_enabled=1`、`cached_apps_freezer=1`、`low_power=0`、`protect_battery=0`、休眠 30 s、Doze 白名单 172 条（三星自家应用为主）。

## 4. 关键系统限制（有据可查）

1. **官方"真·热词"通道第三方不可用**：`AlwaysOnHotwordDetector` / `HotwordDetectionService` 需要 `@SystemApi` 权限 `MANAGE_HOTWORD_DETECTION`，`BIND_HOTWORD_DETECTION_SERVICE` 为 signature 级，且框架层有"仅预装应用可配置热词服务"的限制（AOSP frameworks/base 提交）。→ 原生 DSP 常驻热词只有系统应用能做。
2. **Android 14+ 禁止从 BOOT_COMPLETED 启动 microphone 类型前台服务**（Android 15 文档明确）。开机后不能立刻开始监听麦克风，只能先起"非麦克风"服务，等用户首次解锁/交互后再转入麦克风前景服务。
3. **Android 17 后台音频收紧**：应用在后台做音频播放/申请音频焦点/改音量，必须有可见 Activity 或非 `SHORT_SERVICE` 类型的前台服务；若 targetSdk=37，还必须持有"使用时（WIU）"类型前台服务。→ TTS 播报必须挂在正确类型的前台服务下，否则**静默失败且不报错**。ClosePaw 的 targetSdk=36，只受前半条约束。
4. **合盖态 `app_accessible=false`**：合盖时无法给用户展示交互界面；合盖唤醒即便能听到，也只能靠语音反馈（正好符合无屏应急思路），但不能显示 Agent 执行过程。
5. **ADB 只用于部署**：`ro.adb.secure=1`，正式运行不依赖 USB。

## 5. 可用的部署方式

| 方式 | 可行性 | 说明 |
|---|---|---|
| 安装现成 APK（ClosePaw v0.1.0 release） | ✅ 立即可行 | 147 MB 签名包，1,620 次下载，minSdk 31 / targetSdk 36，Android 17 兼容 |
| 源码构建并改造（Kotlin + AGP） | ✅ 可行 | Apache-2.0，单 app 模块，JDK 17 就绪 |
| 设为默认助手（VoiceInteractionService） | ⚠️ 待实测 | 角色可换、入口在"设置→应用→默认应用→数字助理应用"，需装自有 VIES 后验证三星是否放行 |
| Shizuku 提升权限 | ⚠️ 可选 | 用于虚拟屏/后台操作 Chrome，需手动启动一次，非必需 |
| Root / 刷机 | ❌ 不做 | 违背约束 |

预计需要用户手动授权的权限：无障碍服务、显示在其他应用上层、电池优化白名单、麦克风、相机、通讯录、电话、短信、位置（后台）、通知使用权（可选）。

## 6. 开源项目核实结果（不是看 README，是看代码与产物）

**ClosePaw**（https://github.com/imoonkey/closepaw）
- 仓库真实活跃：1,557 次提交，v0.1.0 release + 可下载 APK（2026-05-28，1620 下载），Apache-2.0，Kotlin，minSdk31/targetSdk36。
- 源码实测包含：ReAct 循环、`mobile_action`/`open_app`/`system_button` 工具、`todo`/`scratchpad`、子代理 `delegate_task`、长期记忆（markdown 分层）、skills（agent-skills + per-app SKILL.md）、shell/termux_shell/browser_script、Shizuku 虚拟屏、tracing。
- 无障碍相关代码覆盖 25 个文件 → 屏幕感知与点击的能力是真的。
- **缺失（grep 全仓 0 命中）**：`VoiceInteractionService`、`BOOT_COMPLETED`、`WakeWord`、`TextToSpeech`、`foregroundServiceType`。有 `SpeechRecognizer`（2 处，仅语音输入框）。
- 结论：**Agent 层强、语音层与常驻层几乎为空**。

**sherpa-onnx**（https://github.com/k2-fsa/sherpa-onnx）
- 最新 v2.0.1（2026-10-26 发布，4 天前），提供 `sherpa-onnx-v2.0.1-android.tar.bz2`（AAR + JNI）与 aarch64 termux 包。
- 支持离线 KWS（自定义热词）、离线 ASR（Zipformer/Paraformer/Whisper）、离线 TTS，纯本地、arm64 可用。→ 作为唤醒层与离线 ASR 层是现实选择。

## 7. 选型结论（明确一条路线）

**单 APK 路线：以 ClosePaw 为 Agent 内核（fork 后扩展），语音/常驻/应急作为自有模块并入同一个应用。不同时搭第二套 Agent。**

理由：
1. ClosePaw 已经解决了最难的部分（屏幕理解、多步执行、工具层、记忆、skills），且是 Android 原生 Kotlin、离线运行、OpenAI 兼容可自定义 base URL → 直接可接阿里云百炼。
2. 它恰好缺的正是语音（唤醒/ASR/TTS）、开机自恢复、原生电话/短信/相机工具 —— 这些正是要自己写、且必须写进同一进程的部分（应急能力要在云端 AI 故障时仍可用）。
3. 分开做两个 App 会出现"两个大脑"、双份权限、两套前台服务抢麦克风，违背"不要同时搭建多套重复系统"。

分阶段落地顺序（每阶段都要真机实测）：
- 阶段 2：先装官方 release APK（不写代码），配百炼自定义端点，跑通测试 A/B/C → 验证"Android 17 上 ClosePaw 内核 + 百炼"是否真的成立。
- 阶段 3+：fork 源码，加 `VoiceInteractionService`（注册为默认助手、侧键长按入口）、sherpa-onnx KWS + 离线 ASR、TTS 反馈；再加原生 Phone/SMS/Location/Camera 工具与 Emergency 模块，与云端 Agent 隔离。
- 阶段 4：BootRecovery（BOOT_COMPLETED → 非麦克风服务 → 解锁后转麦克风前景服务）、三星电池白名单、按实测记录 8 种场景能否唤醒。

## 8. 待用户确认/提供
1. 阿里云百炼 API Key（建议用子空间专属域名 + 专用 Key），不要贴在公开仓库或日志里。
2. 是否同意在阶段 2 先安装 ClosePaw 官方 APK 做基线验证（会在手机上新增一个应用）。
3. 应急联系人（如"妈妈"）的真实号码，仅在设备本地配置，不上云。
