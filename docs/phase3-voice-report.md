# 第三阶段（进行中）：语音交互系统

时间：2026-10-30 21:05–21:20
代码：`src/closepaw/app/src/main/kotlin/ai/closepaw/voice/`（新增 7 个文件）+ manifest

## 1. 已实现

| 模块 | 文件 | 状态 |
|---|---|---|
| 系统助手入口 | ClosepawVoiceInteractionService / ClosepawVoiceSessionService / ClosepawVoiceSession | 代码完成，**本机无法被选为默认助手**（见 §3） |
| 独立语音入口 | VoiceActivity（桌面图标「语音助手」） | ✅ 可用 |
| 快捷开关 | VoiceTileService（下拉面板磁贴） | ✅ 已注册，需用户在编辑面板里添加 |
| 语音识别 | VoiceInputController（Android SpeechRecognizer，zh-CN，边说边出结果） | ✅ 链路已验证 |
| 语音播报 | TtsSpeaker（Android TTS，中文，队列+打断，markdown/URL/emoji 清洗，180 字截断） | ✅ 已验证触发 |
| 任务完成播报 | VoiceFeedbackBridge（挂在 AgentServiceEventHandler.TaskCompleted 上） | ✅ 日志确认朗读了 188 字答复 |
| 目标下发 | VoiceGoalDispatcher（复用 MainActivity 的 goal 通道，不另建调度器） | ✅ |

## 2. 已通过的真实验证

1. **端到端闭环（绕过麦克风，验证其余全部环节）**
   `am start .../.voice.VoiceActivity --es fake_text "open settings and read the battery level"`
   → `VoiceActivity: dispatch=true` → `AgentSession: Task started` → 工具执行（open_app/click）
   → `VoiceFeedbackBridge: speaking outcome=GOAL_ACHIEVED chars=188` → `Task ... completed (outcome=GOAL_ACHIEVED)`
2. **ASR 机制验证**（无人说话时）
   `RecognitionServiceImpl: onEndOfSpeech` → `RecognitionClient: #onResults empty` →
   `onError NO_SPEECH_DETECTED` → 我方 `VoiceInput: ASR error 7` → 界面提示「没有听到说话」并语音播报。
   说明麦克风→系统识别服务的链路是通的，只是没人说话。
3. **审批模式修复**：默认 `SMART` 会让跨 App 任务以 `USER_STOPPED` 结束；在
   设置→Agent Behavior→Approval 选 **Auto-Approve** 后同一任务 `GOAL_ACHIEVED`。
   （该选项持久化在 `AppSettingsStore.approvalMode`，非 debug 专用。）

## 3. 硬限制：本机无法把第三方 App 设为默认助手（有证据）

- `cmd role add-role-holder android.app.role.ASSISTANT ai.closepaw`
  → `RoleControllerServiceImpl: Package does not qualify for the role`
- 设置→应用→默认应用→数字助理应用：列表里「盖乐世推荐」只有 Google，「其他」为空，
  **ClosePaw 根本不出现**（尽管 `cmd package query-services -a android.service.voice.VoiceInteractionService`
  已能查到我们的服务，2 个候选中第 2 个就是我们）。
- 直接写 `settings put secure voice_interaction_service/assistant` 指向我们：
  按 KEYCODE_ASSIST 时系统 `VoiceInteractionManagerService$getDefaultAssistantFlagsFromInfo()`
  **空指针崩溃**（系统进程，不是我们的代码），已回滚到 Google 以免系统不稳。

结论：One UI 9 上「侧键长按 / 系统助手」入口对非预装应用不可用。这条属于平台限制，不是实现问题。
替代触发（已实现）：桌面图标「语音助手」、下拉面板磁贴；两者都不依赖电脑。

## 4. 尚未实现

- **本地唤醒词（sherpa-onnx KWS）**：未接入。且框架级常驻热词（HotwordDetectionService）需系统签名权限，
  第三方最多做「前台服务+本地KWS」常驻监听，耗电与后台限制需第三阶段后续实测。
- **离线 ASR**：未接入（当前是 Google 在线识别；离线应急指令要等 sherpa-onnx 模型落地）。
- **锁屏/息屏可用性**：VoiceActivity 是普通 Activity，锁屏时无法显示（实测被锁屏挡住）；
  真正锁屏可用需要系统助手 session，而系统助手不可用 → 锁屏语音目前无解，只能靠解锁后触发。
- 语音打断（barge-in）、连续对话、说话人确认：未做。

## 5. 待人工确认的一项

真人口述验证：解锁 → 点桌面「语音助手」→ 说一句中文（如「现在手机还有多少电」），
确认 ① 识别正确 ② 能听到「好的，开始执行」 ③ 任务完成后能听到结果播报。
机器侧只能验证到「麦克风→识别服务」链路通 + 「文字→Agent→TTS」链路通，中间的真实语音识别率必须真人测。
