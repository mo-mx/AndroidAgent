# PROJECT_STATUS — 当前真实状态

最后更新：2026-10-31（本次更新内容：阶段 6 第 1 步「语义闸门」代码+单测完成，待实机）
设备：Samsung SM-F731U1（Z Flip5，美版）/ Android 17（SDK 37）/ One UI 9
状态标注约定：`实机验证` = 真机跑过且有日志；`源码分析` = 只读了代码；`未验证` = 没测过。

---

## 1. 一句话结论

手机上的 Agent 主链路（在线 LLM + 无障碍操作 + 语音入口 + 离线命令 + 救援通道 + 无线部署）
**已在真机跑通并被逐项记录**；尚未完成的是**常驻唤醒（热词/声纹）**、
**锁屏语音**、**截图感知**三块，且已知它们在当前平台限制下各有硬约束。

## 2. 分阶段进度

| 阶段 | 内容 | 状态 | 依据 |
|---|---|---|---|
| 1 | 设备与环境勘察、方案选型（单 APK / ClosePaw 内核） | 完成 | `docs/phase1-device-report.md`（实机） |
| 2 | 基础 Agent：装 debug 包、接百炼、跑通 A/B/C 三个任务 | 完成 | `docs/phase2-basic-agent-report.md`（实机） |
| 3 | 语音：唤醒入口 / ASR / TTS / 播报闭环 | 部分完成 | `docs/phase3-voice-report.md`（实机 + 人工待确认项） |
| 4 | 无障碍稳定性 + 通用能力框架（闹钟/计时器/应用/设置页） | 部分完成 | `docs/phase4-report.md`（实机，含 1 条失败） |
| 4.5 | 无线开发环境（USB 拔除后全流程） | 完成 | `docs/wireless-acceptance.md`（实机） |
| 4.6 | Direct Boot 救援通道（未解锁也能被查询/拉起/播报） | 完成 | `docs/direct-boot-rescue-report.md`（实机） |
| 4.7 | scrcpy 无线投屏 | 视频链路完成，控制注入待人工确认 | `docs/scrcpy-wireless.md` |
| 5 | 唤醒词 + 声纹（sherpa-onnx KWS + SpeakerEmbedding） | **未开始** | — |
| 6 | 语义闸门 + 把自研能力包成 ClosePaw ToolSpec | **进行中（第 1 步完成，未实机）** | `docs/phase6-stage1-semantic-gate.md`（源码 + JVM 单测） |
| 7 | GitHub 协作仓库 | 完成（本次） | 本仓库 |

## 3. 已验证可用的功能（实机）

- **在线 Agent 任务**：打开设置读存储、Chrome 检索并进入指定站点、页面加载失败→刷新→根因解释；
  每步有 `inspection-trace` 轨迹，`stop_reason=GoalAchieved`。感知为**纯无障碍树**。
- **语音入口**：桌面「语音助手」Activity + 下拉磁贴；`fake_text` 注入可跑通
  「语音入口 → Agent 任务 → TTS 播报 188 字」全链路。
- **ASR 链路**：麦克风 → 系统识别服务（`onEndOfSpeech` / `NO_SPEECH_DETECTED` 均正确回报）。
  **真人口述识别率仍待人工验证。**
- **离线 ASR**：sherpa-onnx paraformer-zh-small 已接入（PC 同音频测试 CER 1.3% / 精确 93.3%，
  设备端真机识别率未系统评测）。
- **离线命令路由**：电量、手电、Wi-Fi 开关、时间日期等确定性指令，无网络可用，返回
  `ToolResult(status, verified, detail)`。
- **系统面板自动化**：Wi-Fi 面板开关 + `WifiManager` 复核（`verified=true`），锁屏时明确返回
  `SYSTEM_RESTRICTED`。
- **通用能力**：打开计算器/时钟（前台包名复核）、四分钟倒计时、查看闹钟
  （`AlarmManager.nextAlarmClock` 复核）、蓝牙/Wi-Fi 设置页跳转 —— 均 `verified`。
- **无线部署**：拔 USB 后 235 MB APK 无线安装 14 s；锁屏状态下 install/shell/logcat 均可用。
- **救援通道**：重启后**未解锁**状态下中继收到心跳与 `diagnostics`（`user_unlocked:false`、
  `direct_boot:true`），Wi-Fi 与蜂窝（cloudflared 隧道）双通道验证；解锁后能力自动恢复。
- **scrcpy 投屏**：D3D11 解码、真实画面录像可解析（440×1080 / 19.57 s）。

## 4. 未解决的问题（按当前优先级）

1. **唤醒词与声纹（阶段 5）**：`voice/KwsEngine.kt` 代码存在但**没有任何调用方**，
   即"代码存在、功能未接线"。框架级热词通道需系统签名权限，第三方只能做前台服务 + 本地 KWS，
   息屏功耗未测。
2. **锁屏语音无解**：One UI 9 上第三方应用无法成为默认助手（`cmd role add-role-holder` 返回
   `Package does not qualify`；强写 `voice_interaction_service` 会让系统进程 NPE 崩溃，已回滚）。
   桌面图标/磁贴入口在锁屏下被挡住 → 锁屏语音目前只能靠救援通道的远程 `speak`。
3. **截图感知不可用**：`AccessibilityScreenshotCapturer` 实机只产出 ~1024×136 / 2.9 KB 近空图，
   若开 `supportsVision=true` 会让多模态模型在错图上决策 → 当前强制关闭视觉。
4. **三星闹钟创建失败**：`ACTION_SET_ALARM` 后三星时钟停在编辑页，UI 兜底未找到保存按钮节点，
   如实返回 `verified=false`（未创建）。
5. **官方 release APK 无法接第三方端点**，目前依赖 debug 构建 + Intent 注入配置；
   正式化需要改 fork 的引导页（加"稍后配置"或内置百炼 provider）。
6. **本地小模型全部不达标**（详见 `docs/TEST_RESULTS.md` §5）：0.6B/1.7B/FunctionGemma 三款
   在 15 条中文任务上关键语义通过率 0–1/15，且存在反向危险计划 → 不接入执行层。
7. 投屏控制注入、电脑重启后重建连接、换 Wi-Fi 远程开发、纯移动数据远程开发 4 项未验收。

## 5. 正在执行的任务

Issue #3 阶段 6，第 1 步「语义闸门」已在 `feat/semantic-gate` 分支完成代码与 JVM 单测
（20/20 通过，全量 2110 tests 中 6 个失败经干净 main 复跑确认为既有 Windows 环境问题）。
**未构建 APK、未上机**；阶段 1 的实机回归与阶段 2（ToolSpec 适配）等用户批准。
细节与验收对照见 `docs/phase6-stage1-semantic-gate.md`。

## 6. 代码现状（与上游的关系）

- `src/closepaw` = `imoonkey/closepaw` 的浅克隆（上游提交 `75dae26`）+ 本地未合并改动。
- 本地改动量（未提交到上游）：4 个文件被修改（`AndroidManifest.xml` +85 行、`AgentService.kt`、
  `AgentServiceEventHandler.kt`、`settings.gradle.kts` 加 `:rescue` 模块），
  新增 `voice/`（34 个 Kotlin 文件）、`rescue/`（8 个）、`app/src/main/java`（sherpa-onnx JNI 包装）、
  `jniLibs`、`res/xml/cp_voice_interaction.xml`。
- 上游 `.git` 目录未纳入本仓库（仓库内保存的是源码快照），本地仍保留原始克隆以便与上游对比。
