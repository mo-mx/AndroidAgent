# ARCHITECTURE — 真实代码架构

更新：2026-10-31　范围：`src/closepaw`（仓库内为源码快照，上游 `imoonkey/closepaw@75dae26`）
标注：`已实现` = 有代码且被入口调用；`代码存在未接线` = 类在，但没有调用方；`计划` = 无代码。

---

## 1. 模块划分

| 模块 | 说明 | 状态 |
|---|---|---|
| `:app` | ClosePaw 主应用，`applicationId = ai.closepaw`，targetSdk 36 / minSdk 31 | 已实现（上游 + 本地改动 4 个文件） |
| `:rescue` | 自研救援应用，包名 `ai.closepaw.rescue`，独立进程 `:rescue`，Direct Boot 可用 | 已实现（本地新增） |

`:app` 内包结构（`app/src/main/kotlin/ai/closepaw/`，410 个 .kt）：

```
agent/       Turn 循环、规划/执行阶段、子代理(subagent)、历史压缩
tool/        ToolSpec/ToolRouter/ToolRegistry/PolicyEngine + action/(点击/输入/滚动/验证)
perception/  Perceptor：AccessibilityNodeInfo 树 → ScreenSnapshot（元素索引/排序/预算）
platform/    无障碍截图、Shizuku、虚拟显示等系统能力封装
llm/         多 provider 客户端（OPENAI_API / OPENAI_CODEX / OPENROUTER / OTHER / LOCAL_LFM）
memory/      分层 markdown 长期记忆；history/ 上下文压缩
session/     会话与状态；trace/ 轨迹落盘（inspection-trace）；protocol/ 协议定义
voice/       ★ 自研：语音入口、离线 ASR/KWS、TTS、确定性语义层、设备能力工具
```

## 2. 自研层（`voice/`，34 个文件）

### 2.1 入口层

| 文件 | 作用 | 状态 |
|---|---|---|
| `VoiceActivity.kt` | 桌面「语音助手」Activity；离线优先路由 → 语义解析 → 否则交在线 Agent | 已实现 |
| `VoiceTileService.kt` | 下拉面板磁贴 | 已实现（需用户手动添加磁贴） |
| `ClosepawVoiceInteractionService.kt` / `ClosepawVoiceSessionService.kt` / `ClosepawVoiceSession.kt` | 系统助手（VoiceInteractionService）会话 | 代码存在，**本机无法被选为默认助手**（平台限制，见 DECISIONS D4） |
| `ClosepawRecognitionService.kt` | 满足 VIES 校验用的 RecognitionService 占位 | 已实现 |

### 2.2 输入输出

| 文件 | 作用 |
|---|---|
| `VoiceInputController.kt` | Android `SpeechRecognizer`（zh-CN，边说边出） |
| `OfflineAsr.kt` | sherpa-onnx 离线中文 ASR，引擎按模型文件名自动识别（paraformer 默认） |
| `WavIo.kt` / `VoiceRecorder.kt` | 16 kHz 单声道录音与 WAV 读写（离线评测与识别共用） |
| `TtsSpeaker.kt` | Android TTS，队列 + 打断，清理 markdown/URL/emoji，180 字截断 |
| `VoiceFeedbackBridge.kt` | 挂在 `AgentServiceEventHandler.TaskCompleted`，任务完成后播报结果 |
| `KwsEngine.kt` | sherpa-onnx KWS 唤醒词（zipformer zh-en 3M int8） | **代码存在未接线** |
| `VoiceTuning.kt` | 端点/阈值调参 |

### 2.3 确定性语义层（离线、毫秒级）

```
文本 → SemanticParser.parse()  → List<SemanticTask>（action + target + params）
     → SemanticRunner.run()    → CapabilityGuard 闸门 → 能力表分派 → 能力自校验 → ToolResult
```

- `semantics/SemanticTask.kt`：`action` / `target` / 参数 / `describe()`
- `semantics/SemanticParser.kt`：中文时间（`ChineseTime`）、应用名、动作词解析
- `semantics/SemanticRunner.kt`：**(action,target) → capability** 的映射表；
  CANCEL 类请求在代码层面**没有任何能落到创建调用的路径**
- `semantics/ClockControl.kt`：`AlarmManager` / `CountDownTimer` 读写
- `semantics/AppNavigator.kt`：应用内二级页面（如微信扫一扫）
- `semantics/ActionMemory.kt`：动作级记忆
- `LocalCommandRouter.kt`：**完全无模型**的兜底路由（电量/手电/Wi-Fi/时间/退出），
  无网络、无解锁也能回答

### 2.4 设备能力工具（`voice/tools/`）

| 文件 | 能力 | 验证方式 |
|---|---|---|
| `ToolResult.kt` | `ToolStatus` + `verified` + `detail` 统一结果 | — |
| `WifiControl.kt` / `TorchControl.kt` | Wi-Fi / 手电开关 | `WifiManager` / `CameraManager` 回读 |
| `SystemPanelAutomator.kt` | 快捷面板操作 + 状态机（DISABLED/ENABLED_NOT_BOUND/CONNECTED/LOST） | 面板 + 系统状态复核 |
| `AccessibilityHealth.kt` | 无障碍四态、`connectCount`/`unbindCount`、`awaitConnected()` | 日志 |
| `AppRegistry.kt` | 桌面应用枚举、中文名匹配、前台包名校验 | `getLaunchIntentForPackage` + 前台包名 |
| `IntentActions.kt` | 闹钟/倒计时/设置页/拨号/地图/搜索，全部先 `resolveActivity` | 各项回读 |
| `UiAutomator.kt` | 节点搜索/点击(节点→可点击祖先→手势)/输入/滑动/等文本/前台包名 | — |
| `ChineseTime.kt` | 「十分钟」「半小时」「7点半」等中文时间解析 | 单测/实机指令 |
| `LocalCapabilities.kt` | 能力清单与准入判断 | — |

## 3. 在线 Agent 链路（上游内核，未改动调用协议）

```
VoiceActivity / MainActivity(--es goal) / 磁贴
   └─ VoiceGoalDispatcher.dispatch()
        └─ MainActivity（goal 通道，唯一调度入口，不另建调度器）
             └─ AgentService（无障碍服务 + 前台任务执行）
                  └─ AgentTurnRunner → Turn.kt（ReAct）
                       ├─ Perception: Perceptor → ScreenSnapshot（纯无障碍树）
                       ├─ LLM: LLMClientFactory → OTHER(百炼 OpenAI 兼容) / OPENROUTER / ...
                       ├─ 工具裁决: TurnToolPolicy → PolicyEngine(ALWAYS_ASK/SMART/AUTO_APPROVE × AppTier)
                       └─ ToolRouter → ToolRegistry(ToolSpec.validate → createInvocation)
                            └─ action/ 执行器 → UiChangeDetector(指纹) / PostActionAnalysis(300/500/1000ms)
                  └─ TaskCompleted → AgentServiceEventHandler → VoiceFeedbackBridge → TtsSpeaker
```

- 工具集（上游 14 个）：`mobile_action`、`open_app`、`system_button`、`wait`、`complete_task`、
  `write_todos`、`scratchpad`、`delegate_task`、`remember_experience`、`ask_user`、`shell`、
  `termux_shell`、`activate_skill`、`browser_script`。
  **没有闹钟/计时器/音量/Wi-Fi 开关/应用内二级页面** —— 这正是自研 `voice/tools` 要补的部分。
- 轨迹：`inspection-trace/<id>` 落盘，含每轮工具调用与结果。

## 4. 救援链路（`:rescue`）

```
BootReceiver (LOCKED_BOOT_COMPLETED / BOOT_COMPLETED / MY_PACKAGE_REPLACED, directBootAware)
  └─ RescueService（独立进程 :rescue，specialUse 前台服务）
       ├─ RescueConfig：endpoint/device_id 存 device-protected storage（无密码/联系人/消息）
       ├─ RescueClient：出站 TLS + HMAC-SHA256(ts/path/body) + 120 s 时间窗，无入站端口
       ├─ PinnedTls：证书固定
       ├─ RescueOps：白名单操作；CE 依赖项在未解锁时返回 unsupported
       └─ RescueSpeaker / RescueActivity：解锁后可播报、可打开配置面板
PC 侧：tools/rescue_relay.py（HTTPS 8765，可用 cloudflared 暴露到公网）
```

## 5. 已实现 vs 计划

| 项 | 状态 |
|---|---|
| 语音入口、TTS 播报、离线命令、离线 ASR | 已实现 |
| 唤醒词（KWS 常驻监听） | 代码存在未接线；前台服务与功耗未测 |
| 声纹（SpeakerEmbedding） | 计划 |
| 把 `voice/tools` 包成 ClosePaw `ToolSpec` 并注册 | 计划（方案见 `docs/closepaw-reuse-review.md`） |
| 语义闸门（ToolRouter 之前的一致性/目标存在性校验） | 计划 |
| 业务级 `verified` 回读并入 ToolResult | 部分（自研能力已做，ClosePaw 侧仍以 UI 变化为准） |
| 截图/视觉感知 | 计划（当前强制 `supportsVision=false`） |
| 百炼 provider 内置（摆脱 debug Intent 注入） | 计划 |
| CI（构建/单测） | 未启用（本仓库明确不配自动部署到手机的流水线） |
