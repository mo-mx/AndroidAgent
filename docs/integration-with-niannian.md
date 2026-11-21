# 与「念念」语音助手的复用与整合说明

编写日期：2026-11-21　对象仓库：`mo-mx/niannian-fox`（念念 · 桌面小狐狸）

本文回答两件事：本仓库（AndroidAgent）里哪些东西可以直接搬进念念、哪些不能照搬，
以及两个项目最终怎么合成一个。所有"已验证"字样只用于真机跑出过结果的项目，其余明确标注为源码分析或未验证。

---

## 1. 两个项目各自是什么

| | AndroidAgent（本仓库） | 念念（mo-mx/niannian-fox） |
|---|---|---|
| 形态 | 单 APK，ClosePaw（Kotlin）内核 fork + 自研语音层 + 独立 `:rescue` 进程 | 单 APK，Java 悬浮窗（`TYPE_APPLICATION_OVERLAY` + WebView）+ Python 后端 |
| 编排位置 | 端上 ReAct 循环 + 离线确定性路由（`voice/LocalCommandRouter.kt`） | 服务端 `server.py` 编排，动作以 action JSON 下发到端上 `runAction()` |
| 语音 | 系统助手入口/磁贴 + 离线 sherpa-onnx ASR + 本地 TTS | 悬浮窗按住说话 + 本地 whisper（faster-whisper）+ 云端克隆音色 TTS |
| 能做什么 | 无障碍驱动的屏幕操作、设置页跳转、手电、Wi-Fi 面板自动切换、Direct Boot 救援 | 聊天、定时/闹钟、手电、勿扰、Wi-Fi/蓝牙"只开面板不掉开关"、拍照 |
| 权限面 | 无障碍服务（`ai.closepaw/.app.AgentService`）是核心 | **没有 AccessibilityService**（Manifest 中无此声明），这是它开不了 Wi-Fi 的根因 |

一句话：念念强在人设/语音体验和悬浮交互，AndroidAgent 强在"端上真能动手 + 敢说没做到"。
两者重叠的是闹钟/手电/勿扰这类 Intent 级动作，不重叠的是**需要无障碍才能完成的那一半**。

---

## 2. 可直接复用的模块（按价值排序）

### 2.1 设备能力层 `src/closepaw/app/src/main/kotlin/ai/closepaw/voice/tools/`

| 文件 | 行数 | 作用 | 现状 |
|---|---|---|---|
| `SystemPanelAutomator.kt` | 369 | 无障碍驱动系统面板真正翻转 Wi-Fi/蓝牙开关：从**活的节点树**定位开关（class/text/contentDescription/checkable），`ACTION_CLICK` → 最近可点击祖先 → 节点自身 bounds 的合成手势；翻转后**重读真实系统状态**才报成功；完事后收起面板；锁屏时明确拒绝 | 源码 + 单测；Wi-Fi 面板开关 + `WifiManager` 复核在真机验证过（`docs/PROJECT_STATUS.md` 第 3 节），锁屏返回 `SYSTEM_RESTRICTED` 为真机结论；**蓝牙面板切换未经真机验证** |
| `WifiControl.kt` | 111 | 诚实的 Wi-Fi 控制：`setWifiEnabled` 只试一次（Android 10+ 普通应用必然失败，重试无意义）→ 失败就 `SYSTEM_RESTRICTED` + 打开 `Settings.Panel.ACTION_WIFI`；`isWifiEnabled` 读回作为 `observed` 证据 | 单测；`SYSTEM_RESTRICTED` 分支的 Intent 跳转在 JVM 测不了，真机以面板自动化路径覆盖 |
| `LocalCapabilities.kt` | 442 | 蓝牙段：`btState`（读状态 + 配对数）、`btSet`（`enable/disable` + 1200 ms 内轮询 `isEnabled` 稳定，单调时钟、读数上限防自旋；区分"平台拒绝"与"权限未授予"）；网络段：判断当前是否 Wi-Fi/蜂窝 + 是否真能上网；手电段同构 | 单测覆盖（含校时倒跳用的注入时钟）；真机未验证蓝牙 `enable()` 到底返回 false 还是抛异常 |
| `TorchControl.kt` | 383 | `CameraManager.setTorchMode` + `onTorchModeChanged` 回调做基线/事后判定，避免把迟到回调当基线 | 单测；真机时序未验证 |
| `AccessibilityHealth.kt` | — | 无障碍状态机 DISABLED / ENABLED_NOT_BOUND / CONNECTED / LOST，`awaitConnected(12s)` 等待重绑而不是第一次 null 就失败 | 真机：`adb install -r` 不 force-stop 时绑定零丢失；`force-stop` 会掉绑定需重新开启 |
| `IntentActions.kt` | 432 | 设置页/系统 UI 的 Intent 级动作（含"打开 Wi-Fi 设置页""打开蓝牙设置页"） | 真机 ✅ `SUCCESS verified`（前台包名 `com.android.settings`） |

这套层的设计前提值得念念直接继承：**工具返回值带 `status / verified / detail / observed / dispatched`**，
`verified` 只在实际读回目标状态时才为 true，否则说"请求已发出，但没能确认结果"。这条纪律在
`voice/semantics/TerminalState.kt` 里有对应的闸门实现和 60 多轮复审记录。

### 2.2 语义闸门 `voice/semantics/`（SemanticParser / IntentGate / SemanticTask / TerminalState）

把一句中文分解成 `target + 期望状态`（例如"打开蓝牙" → `on:BLUETOOTH`），执行后必须拿到
`ObservedState(identity, state, source)` 才能判成功。价值不在代码量，在于它踩过的坑：
Wi-Fi/蓝牙/手电三处都曾因为"读了状态但没把证据交给闸门"，把真翻了的开关播成"没能确认结果"。
念念若自己写状态判定，会重复这些坑。

### 2.3 离线确定性路由 `LocalCommandRouter.kt` + `ChineseTime.kt`

断网也能答的指令集合（电量/时间/日期/手电/Wi-Fi 开关）。念念目前所有意图都走服务端，
断网即哑；把这层嵌进去就能"没网也能开手电、问时间"，且是毫秒级、零 token。

### 2.4 离线 ASR 结论（可省一次选型）

`asr_eval/` 用 30 条中文命令、同一套 16 kHz WAV 在 PC 上做过横向评测：

| 模型 | CER | 精确匹配 |
|---|---|---|
| streaming-zipformer-zh-14M | 5.3% | 80.0% |
| **paraformer-zh-small（采用）** | **1.3%** | **93.3%** |
| zipformer-ctc-zh-int8-2025 | 0.9% | 93.3%（351 MB，过重） |
| sensevoice-small | 3.1% | 83.3% |

念念当前用 faster-whisper（服务端）。若做端上离线兜底，直接上 paraformer-zh-small，不必重新选型。
注意：**设备端真机识别率仍未系统评测**，只有链路通的证据。

### 2.5 `:rescue` 独立进程（Direct Boot 救援）

`src/closepaw/rescue/` 4.1 MB 独立 APK/模块：`directBootAware` + `LOCKED_BOOT_COMPLETED`，
前台服务（`specialUse`，Android 15+ 唯一允许从 BOOT 启动的类型），出站 TLS + HMAC-SHA256
（ts/path/body 签名、120 s 时间窗）、**不开入站端口**、操作白名单，未解锁时对 CE 依赖项
明确返回 `unsupported`。真机验证：重启不解锁 → 43 s 内中继收到心跳，`diagnostics` 返回
`user_unlocked=false / direct_boot=true`。

对念念的意义：手机重启后她能在你不解锁的情况下被查询/播报"我还在"，这比"开 Wi-Fi"更接近
"救援"两个字的本意。

### 2.6 工程资产

- `scripts/`：无线 ADB 部署（拔 USB 后 235 MB APK 14 s 装完）、`a11y_cycle_test.sh`（无障碍掉绑回归）
- `AGENTS.md`：AI 协作纪律（不宣称未验证的测试、区分实机/源码分析）
- `docs/`：项目状态、决策记录、逐条真机验收表 —— 整合时可直接沿用同一套证据格式

---

## 3. 不建议照搬的东西

| 项 | 原因 |
|---|---|
| ClosePaw 的 ReAct 循环 + ToolSpec 注册 | 念念已经有服务端编排（`server.py`），两套编排并存只会让"谁决定动作"变得不可预测。选边，不要叠加。 |
| 系统 `RecognizerIntent` 语音入口 | 与念念的按住说话/KWS 冲突；且 One UI 9 上第三方应用无法成为默认助手（`cmd role add-role-holder` 返回 `Package does not qualify`，强写 `voice_interaction_service` 会让系统进程 NPE 崩溃，已回滚） |
| 端上大模型/在线 LLM 客户端 | 念念的后端已经在做，端上再放一份只增加体积与密钥泄露面 |
| `KwsEngine.kt`（唤醒词） | 代码存在但**没有任何调用方**，属"代码存在、功能未接线"，别当现成能力用 |

---

## 4. 整合成一个应用的推荐路线

### 4.1 目标形态

**一个 APK，两个 Gradle module**：
`app`（念念的 Java 悬浮窗/UI/角色）依赖 `agent-core`（AndroidAgent 的 Kotlin 能力层 + 语义闸门 + 离线路由）。
Java 调 Kotlin `object` 无门槛；反向不成立，所以把能力层放 Kotlin 侧不动即可。

### 4.2 分三步，每步都能独立验收

| 步 | 内容 | 验收标准 |
|---|---|---|
| 1 | 把 `voice/tools/` + `voice/semantics/` + `LocalCommandRouter` 抽成 `agent-core` module，去掉对 ClosePaw 内核的依赖（只依赖一个 `A11yBridge` 接口，由念念侧实现成自己的 AccessibilityService） | 单测全部迁移后仍绿；念念能调 `LocalCommandRouter.route("打开手电")` |
| 2 | 念念新增 `FoxA11yService`（无障碍服务）+ 权限引导页；`runAction("wifi"/"bluetooth")` 分支改为调用 `SystemPanelAutomator.setEnabled`，失败时回落现在的 `openPanel` | 真机四条用例：开 Wi-Fi / 关 Wi-Fi / 开蓝牙 / 关蓝牙，每条都要有"重读系统状态"的证据 |
| 3 | 决策权归一：谁的编排说了算 —— 建议保留念念的服务端编排（它有克隆音色、历史、静默机制），端上只做"执行 + 状态回读 + 诚实回报"；`classify → tool_result` 的 JSON 契约与现在的 `runAction` action JSON 合并成一份 schema | 断网时手电/时间/电量仍可用；联网时多步任务仍能跑 |

### 4.3 数据/接口约定（建议直接沿用）

```
action:  { "type": "wifi", "on": true }              // 下发
result:  { "tool": "wifi", "status": "SYSTEM_RESTRICTED|SUCCESS|PERMISSION_DENIED",
           "verified": false, "dispatched": true,
           "detail": "a11y not bound", "observed": null }
```
`verified=true` 必须伴随 `observed={"identity":"wifi","state":"on","source":"wifi-manager:isWifiEnabled"}`。
念念现在的 `runAction` 是无返回值的 fire-and-forget，第二步改造要把它变成"返回结果对象"，
否则语音层永远不知道成功没成功，只能说"我打开了面板"。

---

## 5. 风险与未决

1. **无障碍权限是硬门槛**：整合后念念会多一个"辅助功能"授权步骤，用户不开就没有开关能力。引导页要写清用途，且必须允许"不开也能用其余功能"。
2. **两套 TTS 冲突**：云端克隆音色（念念）vs 本地 TTS（AndroidAgent）要定优先级与降级顺序，否则同一句话会念两遍。
3. **服务端仍是单点**：念念的编排在服务器上，离线能力只能靠第 2.3 节的本地路由兜底，不会因为整合而变成全离线。
4. **本仓库当前阻塞**（与本整合无关但需知悉）：全量 JVM 272 类 / 2537 项 / 1 failed（`ClockForeignWindowTest` 重复点击）；复审已声明不授权第三轮自动修复，等用户决定；阶段 1 实机验收需重新授权。
5. **目标机差异**：AndroidAgent 的全部真机证据来自 SM-F731U1 / Android 17 / One UI 9。换机型（尤其国产 ROM）面板节点结构不同，第 2.1 节的节点定位需要重新验证。

---

## 6. 附：本仓库相关入口

- 架构与工具调用关系：`docs/ARCHITECTURE.md`
- 当前真实进度：`docs/PROJECT_STATUS.md`
- 逐条真机验收：`docs/TEST_RESULTS.md`（第 4 节"通用能力"、第 3 节"已验证可用的功能"）
- 救援通道实测：`docs/direct-boot-rescue-report.md`
- 语音控制 Wi-Fi/蓝牙 的落地方案：`docs/voice-toggle-wifi-bluetooth.md`
