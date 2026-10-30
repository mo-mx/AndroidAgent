# ClosePaw 任务规划与 GUI 执行能力 —— 复用审查

日期：2026-10-31　范围：`src/closepaw`（410 个 Kotlin 文件 / 62,276 行）
性质：源码审查。标注"实机"的才是真机验证过的，其余是读代码得出的结论。

## 一、结论先说

ClosePaw 的"规划 + 感知 + 动作 + 审批"骨架是完整且可用的，可以直接作为统一执行层的承载框架。
它缺的不是框架，是**领域能力**（闹钟、计时器、应用商店、应用内二级页面）和**业务级验证**（目标状态有没有真的变成期望值）。
我们现在自研的 `voice/tools/*`、`voice/semantics/*` 恰好是这两块的补集。所以正确的做法不是重构，而是**把现有工具包成 ClosePaw 的 ToolSpec 挂进它的 Registry**，再把它的"界面变了就算成功"升级为"状态对了才算成功"。

## 二、可直接复用（无需改动）

1. 工具协议栈
   `tool/ToolSpec.kt`、`tool/ToolInvocation`、`tool/ToolRouter.kt`(457)、`tool/ToolRegistry.kt`
   声明式：name / description / JSON Schema / validate() / createInvocation()；执行返回 Success/Failure/Cancelled。这就是统一执行层要的接口形状，天然容纳"确定性工具"和"模型工具"两种来源。

2. 感知层
   `perception/Perceptor.kt`(393)、`PerceptorInternals`、`ScreenSummary`
   把 AccessibilityNodeInfo 树转成 `ScreenSnapshot`：元素索引、可交互优先排序、mergedText、bounds/center、多窗口多 root、截断预算。比我们 `voice/tools/UiAutomator.kt`(153) 的单层遍历完整得多。

3. 目标解析
   `tool/action/TargetResolver.kt`(129) 纯函数，优先级 element_index > text > x/y，结果分 Resolved / NotFound / Ambiguous，坐标回退会带 warning。可直接用于"找不到目标就拒绝"，不必让模型猜坐标。

4. 动作执行
   `tool/action/`：ClickExecutor、LongPressExecutor、TypeExecutor、ScrollExecutor、SwipeExecutor、NodeActionPerformer(383)、AccessibilityGestureInjector(175)。

5. 执行后验证（比我们现有的强）
   `tool/action/UiChangeDetector.kt`：a11y 元素 FNV-1a 指纹，空树回退 8×8 感知哈希；明确区分 `Changed/Unchanged/Unverifiable`，不把"没法确认"当"成功"。
   `tool/action/PostActionAnalysis.kt`：300 / +500 / +1000 ms 三段重试，总预算 1.8 s，专治 intent 跳转慢。

6. 审批与安全
   `tool/PolicyEngine.kt`(181)：ALWAYS_ASK / SMART / AUTO_APPROVE × AppTier(BLOCKED/CAUTIOUS/NORMAL)，金融与认证类硬拒（BLOCKED 连 AUTO_APPROVE 也过不去），会话级 allow-list 显式受 ALWAYS_ASK 约束。这套可以直接承接"敏感操作需确认"。

7. 规划循环
   `agent/Turn.kt`(484)、`TurnPlanningPhaseRunner`(309)、`TurnExecutionPhaseRunner`(272)、`AgentTurnRunner`(255)
   `cognition/policy/TurnToolPolicy.kt` 工具裁决、`LoopDetectionPolicy.kt` 卡屏检测（连续 5 屏相似度 ≥0.95 才告警，宁可漏报不误报）、`history/Compactor.kt` 上下文压缩、`agent/subagent/SubAgentRunner.kt` 子任务、`WriteTodos/Scratchpad/AskUser/CompleteTask` 四件套。

8. 模型接入
   `llm/LLMClientFactory.kt` + `LLMProvider`：OPENAI_API / OPENAI_CODEX(OAuth) / OPENROUTER / OTHER(任意 OpenAI 兼容 baseUrl，百炼走这条) / LOCAL_LFM。`PromptBuilder` 有 `supportsVision` 分支。

9. 其它现成能力（本轮未评估，仅登记）
   browser_script + CDP（含 Shizuku / 无线 ADB 自配对）、VirtualDisplay 虚拟显示、Termux 桥、记忆与技能库、会话录制回放。

## 三、不能直接用（必须改造）

1. 没有领域工具
   全部工具只有 14 个：mobile_action / open_app / system_button / wait / complete_task / write_todos / scratchpad / delegate_task / remember_experience / ask_user / shell / termux_shell / activate_skill / browser_script。
   **没有闹钟、计时器、音量、Wi-Fi/蓝牙开关、应用内二级页面导航。** 用户实际抱怨的"取消五分钟倒计时"这类，框架里根本没有对应能力，模型再准也无处落。
   → 我们已有的 `IntentActions`、`ClockControl`、`ChineseTime`、`LocalCapabilities`、`SystemPanelAutomator` 必须包成 ToolSpec 注册进去。

2. 验证只到"界面变了"
   `UiChangeDetector` 判断的是屏幕指纹差异，不是业务状态。闹钟建没建成、手电筒亮没亮、Wi-Fi 真的开了没，都要业务级回读。
   → 需要把 `ToolResult.verified` 与 UI 变更检测并列：业务状态可回读的以业务为准，UI 只做兜底。

3. 截图感知在本机不可用
   `platform/AccessibilityScreenshotCapturer.kt`(231) 实机拿到约 1024×136 / 2.9 KB 近空图。`PromptBuilder` 默认把图塞给模型（`supportsVision=true`），会让多模态模型在错的图上做决策。
   → 必须显式 `supportsVision=false`，或在 VirtualDisplay 路径上修好截图再开。

4. 完全没有离线路径
   整个 Turn 循环以 LLM 为中心，断网即失效，也没有"本地能力优先"的分流。
   → 离线前置路由（我们已验收的 `LocalCommandRouter` / `SemanticParser`）要先拦截，未命中再进 LLM 循环。

5. 没有"拒绝执行"语义
   模型输出什么就执行什么，`validate()` 只校验参数形状，不校验"动作与用户意图是否一致"。
   → 统一执行层要在 ToolRouter 之前加语义一致性闸门：CANCEL 不得落到 create；查询类不得产生写操作；目标必须来自真实解析（AppRegistry / ClockControl），解析不到就 ASK 或拒绝。

6. LOCAL_LFM 未验证
   代码里是 Leap SDK 的本地推理入口，本机未跑通，不能当作"本地模型已就绪"。

## 四、统一执行层设计（提案，本轮不实施）

一个接口，三个来源，同一套验证：

    Capability {
      id / action / targetKinds / requiredParams
      preconditions()            // 目标是否真实存在（AppRegistry、AlarmManager…）
      execute(params): ToolResult
      verify(params): VerifyResult
    }

    ToolResult = { status: SUCCESS|PERMISSION_DENIED|SYSTEM_RESTRICTED|
                           DEVICE_UNAVAILABLE|EXECUTION_ERROR|TIMEOUT,
                   verified: Boolean, detail: String }

来源统一收敛为 `SemanticTask`（已存在于 `voice/semantics/SemanticTask.kt`）：
- 确定性解析器（离线，毫秒级）
- 未来本地小模型（离线）
- 在线 LLM（ClosePaw 的 Turn 循环，通过 ToolSpec 调用同一批 Capability）

验证分三层，优先级从高到低：
1. 业务状态回读（AlarmManager / WifiManager / CameraManager / PackageManager）—— 能做的都做这一层
2. UI 变更检测（复用 `UiChangeDetector`）—— 业务状态不可读时的兜底
3. 都做不到 → `verified=false`，播报时明确说"已执行但无法确认"

拒绝策略（第三条写进代码而不是提示词）：
- 动作与能力不一致 → 拒绝并说明
- 目标解析不到或多义 → 追问或拒绝，禁止坐标兜底
- 权限/系统限制 → PERMISSION_DENIED / SYSTEM_RESTRICTED，不重试同一路径

## 五、建议的下一步顺序

1. 把 `IntentActions` / `ClockControl` / `LocalCapabilities` / `SystemPanelAutomator` 包成 ToolSpec，注册进 ClosePaw ToolRegistry（不动 Turn 循环）。
2. 加语义闸门（在 ToolRouter 之前），先只做"动作类型一致性 + 目标真实存在"两条。
3. 在线 LLM 走 Turn 循环验证三类难例：取消闹钟、取消计时器、应用消歧 + 应用内二级导航（微信扫一扫）。
4. 截图：要么修 VirtualDisplay 截图，要么明确 supportsVision=false 长期运行。

以上 1–4 任一步都可能影响已验收的语音链路，落地前逐项确认。
