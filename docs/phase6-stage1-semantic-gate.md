# 阶段 6 · 第 1 步：语义执行闸门（审查修复报告）

> 轮次索引：第㉑–㉔轮（导航候选集与选中态、引用身份/候选完整性、选中态入口矩阵、`expectedState` 终态校验）只在 PR #4 评论里留有完整记录（评论 `6545163487` / `6545988295` / `6545632590` / `6546401957`），本文件未补写，避免二次转述失真。

## 第㊳轮：复审 `6551213132`（针对 `86e9830`）3 条全部处理（离线源码 + JVM，未实机）

需要复审；审查未关闭。本轮在上一条第㊲轮评论之后，收到 ChatGPT 定时复审 `6551213132`（2026-11-01T00:19:03Z，审的是 `86e9830`），1 条 P1（时长参数完整性）+ 1 条 P1/P2（点击前窗口归属）+ 1 条 P2（截断时的「找不到」）。

### 已修 1 — [P1] 时长短语必须被完整消费，歧义不给单一数值

旧 `ChineseTime.durationSeconds` 对每种正则只 `find` 一次，移除首个匹配后只检查 `matched && !badNumeral && total > 0`，**不看剩下的文本里还有没有第二个时长**。于是：

- 「设置五分钟或者十分钟的倒计时」→ 300 s，「或者十分钟」原样留在文本里没被读（用户还没选）；
- 「设置十分钟和一分钟的倒计时」→ 600 s，第二个时长被静默丢弃；
- 「设置一个半分钟的倒计时」→ 30 s（`半分钟` 先命中，留下「一个」），本意是 90 s。

改法：新增 `($NUM)个?半(?:分钟|分)` 复合形（一个半分钟=90 s、两个半分钟=150 s），排在裸 `半分钟` 之前；新增 `RESIDUAL_DURATION` 残留检查——所有时长短语消费完后，若文本里仍存在时长形（`N+小时/钟头/分钟/分/秒`）则整句返回 `null`，调用方追问而不是猜。墙钟形（`7点30分`）会与「30分」这种时长形撞车，因此先用 `CLOCK_TIME_IN_TEXT` 剥掉再解析。注意 `IntentGate` 与 `SemanticParser` 用的是同一个函数，所以这一层修不了的问题下游也拦不住——负向断言因此落在**工具实际收到的入参**上。

### 已修 2 — [P1/P2] 点开功能前先证明窗口归属，点击不再走通用回退

`AppRegistry.launch` 的前台验证在读屏**之前**；读屏结果只检查了 `serviceAvailable`，没比对 `rootPackage == app.packageName`；随后 `UiAutomator.findNode(feature)` 在当时的活动窗口里按 `text/contentDescription.contains` 找首个节点，**没有包名或控件身份约束**，点完再查前台也撤不回副作用。

改法：新增 `UiAutomator.featureControl(pkg, text, timeout) : FeatureLookup(control, why)`——每一轮要求 `root.packageName == pkg`；命中节点自身也必须属于 `pkg` 且 `isVisibleToUser`；点击目标取「标签自身或其**同一包内**最近可点祖先」（`clickableWithin`，有跳数上限、不越过窗口根）。返回具名原因（`window-not-target(<pkg>)` / `not-found` / `label-not-clickable` / `no-window`），不再用一个 null 混三种事实。`AppNavigator.openFeatureResolved`：读屏后先比对 `before.rootPackage` 与 App 包名，不符即拒绝；命中后**只对绑定节点** `performAction(ACTION_CLICK)`，不再调用 `UiAutomator.click`（那个助手会上溯父节点、必要时按坐标点击）。

### 已修 3 — [P2] 截断读取里的「没找到」不再等于「不存在」

`findNode == null` 分支原来一律播「界面上没有找到X」，即使这次读取已撞上 600 节点/120 文本上限。现在先看 `before.textsTruncated || before.nodesTruncated`：截断则播「打开了X，但屏幕读取被截断，没法确认有没有F」（`before-truncated`），完整读取才保留原句。

### TDD 与实跑数字（`--offline --rerun-tasks`）

| 阶段 | 结果 | 日志 |
|---|---|---|
| 新 `ChineseTimeAmbiguityTest`(10) + `AppFeatureWindowIdentityTest`(6) + `TimerAmbiguousDurationArgumentTest`(3)，先写测试、生产未改 | **RED 19 tests / 14 failed / 0 errors**（Truth/MockK 断言失败，非编译错误）：时长歧义 4、复合分钟 2、墙钟混排 1、窗口归属 3、截断措辞 2、入参 3（有一部分重叠计数见日志），控制组 6 条本就通过 | `logs/pr4-review38-red.txt` |
| 实现后（含 2 条自身夹具修正） | **GREEN**，exit=0 | `logs/pr4-review38-green2.txt` |
| 定向 `ai.closepaw.voice.*` | **315 tests / 0 failed / 0 errors / 0 skipped**，exit=0（第㊲轮 296，+19） | `logs/pr4-review38-targeted2.txt` |
| 全量 `:app:testDebugUnitTest` | **2405 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**（失败仍为既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过） | `logs/pr4-review38-full.txt` |

过程中两处**我自己的夹具/断言问题**，如实记录：(a) `AppFeatureWindowIdentityTest` 的「点击直达」用例复用同一份读屏桩，第二次读与第一次相同 → 走成 `TIMEOUT`，改成第二次读有新文本；(b) 初版截断措辞里含「没有找到」四字，与同一用例的 `doesNotContain("没有找到")` 冲突，改的是**生产句子**（「屏幕读取被截断，没法确认有没有X」），没有放宽断言。`SemanticExecutionTest` 的 `feature does not succeed merely because launch succeeds` 补上了新路径需要的窗口/读屏桩（原断言 `contains("没有找到扫一扫")`、`doesNotContain("已经进入")` 一字未改）。`AppFeatureReadCompletenessTest` 的 `findNode`/`click` 桩换成 `featureControl`/`performAction`，断言语义未动。

### 边界（必须看清）

- 时长解析与窗口归属全部为**源码 + JVM**：三星真机上 `root.packageName` 在权限弹窗、悬浮窗、分屏时是否稳定等于 App 包名，`isClickable`/`isVisibleToUser` 是否如预期上报，**未验证**。
- 「一个半分钟=90 s」按字面语义实现；如果用户实际口语里这句话意为别的，本轮不会知道——它现在至少不再是 30 s。
- 完整读取下 `openFeature` 仍只播「界面有变化，但没能确认已经进入」，`verified=null`；页面身份仍未证明。
- `closeAlarm` 仍 BLOCKED；计时器创建/取消对象级终态仍 UNPROVEN；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。
- 本轮未构建/安装 APK、未操作手机、未调用付费 API、未 delegate、未合并 PR、未 push main。

## 第㊲轮：读不到＝没看到？——计时器诊断与闹钟播报里的「缺席断言」（离线源码 + JVM，未实机）

需要复审；审查未关闭。本轮**无新审查**（最新 review `6550162379` 已由 `6550446557` 回复，其后 PR 无新评论），按「无新审查则推进可离线项」继续同一类问题：**把「我没采到」说成「它不在」**。第㊱轮修的是 `AppNavigator`，本轮是剩下两个还没过这道门的出口。

### 问题 1 — `IntentActions.setTimer`：被截断的读屏被当成「页面里没有倒计时」

`setTimer` 发完 `ACTION_SET_TIMER` 后用 `readScreen(80)` 读一次时钟，`pageOk` 只验包名与 `selectedTabs["定时器"]`。`readScreen` 最多走 600 节点、80 条文本，撞上限时 `countdown` 仍被算成 `false`，于是播「我在时钟界面里没看到倒计时」——这是对时钟 UI 的断言，证据却是一次不完整的采样。

### 问题 2 — `IntentActions.showAlarms`：三种不同事实被讲成一句话

旧代码 `runCatching{...}.getOrNull()` 把「`nextAlarmClock` 返回 null」和「这次调用抛异常」合并成同一个 null，再统一播：

- 读到闹钟 → 「下一个闹钟是 X」（这条没问题）；
- 读到 null → 「现在没有设置闹钟」——观察到的只是**当前没有已启用的闹钟**，禁用中的闹钟读出来一模一样；
- 调用抛异常 → 同样是「现在没有设置闹钟」——**读失败被说成了空手机**，而且 `verified=true`。

### 改动

- `IntentActions.kt` `setTimer`：`countdown` 改为三态判定，顺序是「找到运行证据 → `true`」→「页头明确 `0 个定时器正在运行` → `false`」→「这次读取被截断 → `null`（unknown）」→「完整读取且没找到 → `false`」。新增 `explicitZeroCountdown()`：显式 0 是**关于页面的肯定证据**，截断不能把它抹掉。新增 `pageOk && countdown == null` 分支：`SUCCESS` + `verified=null` +「…但屏幕读取不完整，没法确认新倒计时是否开始，请在时钟里核对」，`detail` 记 `countdown-ui=unknown readClipped=<bool> texts=<n>`；日志同步加 `readClipped`。
- `IntentActions.kt` `showAlarms`：新增 `nextAlarmProbe(ctx): NextAlarm(info, known)`，`known=false` 表示读取失败；`nextAlarm(ctx)` 保留原签名（`= nextAlarmProbe(ctx).info`），其余调用方未改。播报改为：读不到 → 「读不到系统的闹钟状态，没法确认有没有闹钟」+ `verified=false`；读到 null → 「系统里没有下一个已启用的闹钟」（打开时钟/没找到 App 的措辞各自保留），不再出现「没有设置闹钟」；`detail` 追加 `nextKnown=<bool>` 与「nextAlarmClock is not an enumeration」。

没有放宽任何既有断言；`TimerDiagnosticsEvidenceTest` 的既有 6 条（含「0 个定时器正在运行」不算运行中、跨窗口读取为 unknown）全部保持。

### 真实数据（`--offline --rerun-tasks`，JDK 17，仓库根先 `source env.sh`）

| 阶段 | 结果 | 日志 |
|---|---|---|
| 新 `TimerReadTruncationTest`(5) + `AlarmAbsenceWordingTest`(3) 先写、生产未改 | **RED 8 tests / 4 failed / 0 errors**（Truth 断言失败，非编译错误）：文本截断 1、节点截断 1、闹钟 null 措辞 1、闹钟读取失败 1；4 条控制组（显式 0、完整读取未找到、截断中仍找到证据、有下一个闹钟）本就通过 | `logs/pr4-review37-red.txt` |
| 实现后同组 + `TimerDiagnosticsEvidenceTest` | **GREEN**，exit=0 | `logs/pr4-review37-green1.txt` |
| 定向 `ai.closepaw.voice.*` | **296 tests / 0 failed / 0 errors / 0 skipped**，exit=0（上轮 288，+8） | `logs/pr4-review37-targeted.txt` |
| 全量 `:app:testDebugUnitTest` | **2386 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**；失败仍为既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过，本轮未重跑旧 SHA 基线 | `logs/pr4-review37-full.txt` |

（过程中一次误报：给 `./gradlew` 传了错误的超时参数，前台 180 s 截断后残留 Gradle/Kotlin 守护进程占着 `R.jar`，下一次 `processDebugResources` 报 `Couldn't delete R.jar`。停掉守护进程、删掉该文件后重跑，上述数字来自干净重跑，不是那次失败运行的产物。）

### 边界（必须看清）

- **「读取完整」仍只是「没撞上 600 节点 / 80 文本预算」**，不等于看到了整屏：可滚动、虚拟化列表仍可能没枚举完，因此 `countdown-ui=false` 依然不是「这个页面确实没有倒计时」的强证明，只是比截断时更可信。
- 三星时钟在真机上到底会不会撞上限、`readScreen` 的 80 条预算对它够不够、`AlarmManager.nextAlarmClock` 在你机器上何时会抛：**全部未实机**。
- `showAlarms` 现在**不再枚举闹钟列表**，也不再宣称「没有设置闹钟」；禁用中的闹钟、多个闹钟的开关状态仍需走 `ClockControl.queryAlarms`（视图读取，受第㊱轮同一完整性闸门约束）或后续全列表枚举。
- `closeAlarm` 仍 BLOCKED（安全拒绝）；计时器创建/取消对象级终态仍 UNPROVEN；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。
- 本轮未构建/安装 APK、未操作手机、未调用付费 API、未 delegate、未合并 PR、未 push main。

## 第㊱轮：打开功能时「界面有没有变」不再从被截断的读屏里推（离线源码 + JVM，未实机）

需要复审；审查未关闭。本轮**无新审查**（最新 review `6550162379` 已由评论 `6550446557` 回复，其后无新评论，HEAD 仍是 `af8e901`），按「无新审查则推进可离线项」处理遗留清单里的「查询结果截断未当作不完整」。

**问题**（`AppNavigator.openFeatureResolved`）：判定「进了那个页面」用的是 `UiAutomator.screenTexts(120)`——它是 `readScreen` 的**投影**，`textsTruncated` / `nodesTruncated` / `serviceAvailable` 在投进去时被丢掉。于是三个不受证据支持的结论都能出口：

- 两次截断读取做集合差，`after - before` 非空就播「界面有变化」——差集里的文本可能本来就在，只是上一次没采到；
- 反过来，两次采样相同就播「界面没有明显变化」并返回 `TIMEOUT`——变化可能正好落在没走到的部分；
- 无障碍服务不可用时 `readScreen` 返回空投影，旧分支把它播成「界面上没有找到扫一扫」，这是对 App UI 的断言，而事实是看不到任何 UI。

**改动**（`AppNavigator.kt`，仅此一文件）：

- `before` / `after` 都改取完整 `ScreenRead`（`READ_LIMIT = 120` 不变），判定用 `before.texts` / `after.texts` 的集合差，但**先过完整性闸门**；
- 新增 `incompleteWhy(before, after)`，返回具名原因：`before-truncated` / `after-a11y-down` / `after-truncated`，全完整才返回 null；
- 顺序改为「跳出 App → 读取不完整 → 有变化 → 无变化」：跳出仍 `EXECUTION_ERROR`（前台包名与文本读取无关，仍可信）；读取不完整则 `SUCCESS` + `verified=null` + 「点了X的F，但屏幕读取不完整，没法确认是否进了那个页面」+ `detail` 记 `read=incomplete(<原因>)` 和两次读取的 `texts/nodes/truncated/pkg`；只有两次都完整时，「有变化」才播原句（`verified=null`）、「无变化」才是 `TIMEOUT`；
- `before.serviceAvailable == false` 时直接 `DEVICE_UNAVAILABLE` +「无障碍服务不可用，看不到X的界面，没法找F」，不再冒充「没找到控件」；
- `control-not-found` 的 `detail` 追加 `read=<事实>`，便于区分「读到但没有」与「读不到」。

没有放宽任何既有断言；控制组（完整读取下的有变化/无变化/跳出 App）行为与播报保持不变。

**真实数据**（`--offline --rerun-tasks`，JDK 17，仓库根先 `source env.sh`）：

| 阶段 | 结果 | 日志 |
|---|---|---|
| 新 `AppFeatureReadCompletenessTest`(6) 先写、生产未改 | **RED 6 tests / 3 failed / 0 errors**（Truth 断言失败，非编译错误）：截断两次 2 条、无障碍不可用 1 条；控制组 3 条本就通过 | `logs/pr4-round36-red2.txt` |
| 实现后同组 | **GREEN 6 / 6**，exit=0 | `logs/pr4-round36-green1.txt` |
| 定向 `ai.closepaw.voice.*` | **288 tests / 0 failed / 0 errors / 0 skipped**，exit=0（上轮 282，+6） | `logs/pr4-round36-targeted.txt` |
| 全量 `:app:testDebugUnitTest` | **2378 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**；失败仍为既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过，本轮未重跑旧 SHA 基线 | `logs/pr4-round36-full.txt` |

（首跑 RED 时我自己的控制组断言写错——原句「没能确认已经进入那个页面」含「已经进入」，被 `doesNotContain("已经进入")` 判失败；改的是测试断言而不是生产句子，RED 有效结论由第二跑 3 failed 给出。）

**边界（必须看清）**：

- 这只是把「读取不完整」从假结论里拆出来，**没有**新增对象级证据：`openFeature` 在完整读取下依旧只播「界面有变化，但没能确认已经进入」，`verified=null`。
- 「完整」只指走查没撞上 600 节点/120 文本预算，**不等于**看到了整屏（可滚动/虚拟化列表仍可能没枚举完）。真机上的实际截断率、三星各 App 的节点规模、无障碍服务被杀时的表现：**全部未实机**。
- 本轮未构建/安装 APK、未操作手机、未调用付费 API、未 delegate、未合并 PR、未 push main、未并入 Issue #6。


## 第㉛轮：创建倒计时的基线与回读（离线源码 + JVM，未实机）

需要复审；审查未关闭。本轮无新审查（最新 review `6548293536` 已由评论 `6548650998` 回复，其后无新评论），按「无新审查则推进可离线项」补上遗留清单里的**创建路径回读**。

**问题**：`ClockControl.createTimer` 只是把 `IntentActions.setTimer` 的结果记录进 `ActionMemory` 就返回。`setIntent` 层最多能证明「请求已投递」和「时钟页面上有某种倒计时措辞」，两者都不能证明**用户要的那个倒计时**存在；`expectedState = timer_running:<秒>` 因此永远 `UNPROVEN`。

**改动**（`ClockControl.kt`）：

- 新增 `TimerDraft` / `timerDraft(ctx, seconds)`：一次完整读，只保留两个可比事实——表头运行中计数、以及页面上**声明该原始时长**（`初始时长/总时长` + `ChineseTime.durationSeconds`）的文本数量；页面身份、截断、计数不可解析都使 `readable=false` 并带 `why`。
- `createTimer` 先读基线，再发 Intent，再读数，然后判定：
  - 基线无同长度卡 + 计数恰好 +1 + 回读恰好一张该长度卡 → `verified=true` + `ObservedState("timer:<秒>","running:<秒>")`；
  - 其余六种情形（基线/回读不可读、同长度 twin、计数未 +1、看不到该长度卡、多张同长度卡）→ `sentWithoutProof`，`verified=null`、`observed=null`，`detail` 记 `readback=…`；
  - Intent 层非 SUCCESS 原样透传，不读页面评分。
- 残留假设（写进代码注释与报告）：时长标签不是稳定对象 ID，第三方并发创建同长度倒计时仍可满足同一判据；要求计数 +1 是收窄而非消除。

**真实数据**（`--offline --rerun-tasks`，JDK 17）：

| 阶段 | 结果 |
|---|---|
| 新 `TimerCreateReadbackTest`(9) 先写，实现未改 | **RED 9 / 8 failed / 0 errors**，Truth 断言失败；`logs/pr4-round31-red.txt` |
| 实现后同组 | **GREEN 9 / 9**，exit=0；`logs/pr4-round31-green1.txt` |
| 定向 `ai.closepaw.voice.*` | **245 / 0 failed / 0 errors / 0 skipped**；`logs/pr4-round31-targeted.txt` |
| 全量 | **2335 / 6 failed / 8 skipped**，exit=1，仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5 基线失败；`logs/pr4-round31-full.txt` |

**边界**：JVM 合成树，不是三星真实 UI；表头刷新时序、卡片是否暴露原始时长标签、双标签结构均未验证，真机上很可能落到 `count-not-plus-one` → `UNPROVEN`。本轮未构建/安装 APK、未操作手机；实机验收需用户重新授权。`closeAlarm` 继续 `BLOCKED`，在线 `ToolRouter` 同门禁（阶段 2）未做。

## 第㉕轮：取消路径的点击时绑定校验（离线源码 + JVM，未实机）

需要复审；审查未关闭。本轮无新审查（最新 reviewed SHA 仍为 `2212ed6`，两条意见已在第㉓轮回复），按「无新审查则推进可离线项」推进计时器对象绑定的**点击时**一半。

**修的是什么**：`cancelTimer` 证明对象身份是在**扫描时**（唯一「同一最小子树里恰有一个 `初始时长/总时长` + 恰一个 `取消`」的卡片），随后点击的是扫描时握住的那个 `AccessibilityNodeInfo`。中间这几百毫秒里卡片可以被重建、tab 可以被切换、列表可以滚动，握住的包装对象描述的是已经不存在的画面。旧代码点前只核对两件事：节点包名是时钟（包装对象里的旧属性）、前台是某个时钟窗口（另一次 `root()` 读取）——两者都能在「节点已失效 / 属于另一个窗口」时通过。

**改动**：新增 `ClockControl.clickTargetProblem(node, freshRoot)`，在 `ACTION_CLICK` 之前按序核对，任一不成立即拒绝（`DEVICE_UNAVAILABLE`，零点击，不落 `UiAutomator.click` 兜底）：

| 顺序 | 检查 | 失败理由 |
|---|---|---|
| 1 | 前台 root 仍是时钟 | `window-not-clock` |
| 2 | `node.refresh()` 返回 true（框架自带的存活测试，抛异常同样拒绝） | `node-detached` |
| 3 | 刷新**之后**再读包名仍是时钟 | `node-owner=<pkg>` |
| 4 | `isVisibleToUser` | `node-not-visible` |
| 5 | `isClickable` | `node-not-clickable` |
| 6 | `node.windowId == freshRoot.windowId` | `node-foreign-window=<a> root-window=<b>` |

拒绝话术按失败事实分三类（不属于时钟窗口 / 已不在当前屏幕 / 无法确认），`detail` 带 `click-target=<reason>`。

**TDD（先测试后改码）**：新增 `ClockClickTimeBindingTest`(5)。在改动前的 head `2048f6d` 上先跑出有效 RED **4 / 5 failed**（Truth 断言失败，非编译错误：`refresh()=false`、`windowId` 不一致、`isVisibleToUser=false`、`refresh()` 抛异常四种情况下旧代码仍然投递了 `ACTION_CLICK`），第 5 条「仍然附着时恰好点击一次」是控制组，改动前即通过，不宣称它曾 RED。修后 **5 / 5 通过**。

**旧 fixture 只补证据、不删断言**：`ClockWindowGateTest` / `ClockAlarmPageEvidenceTest` / `ClockSelectionEntryMatrixTest` / `TimerObjectExecutionTest` 的合成节点工厂补 `refresh()=true`、`isVisibleToUser=true`（模型一个真实可见可用节点）。断言本身一条未改、未弱化；`ClockWindowGateTest > timer cancel refuses a cancel button whose node belongs to another app` 仍要求原话术「取消按钮不属于时钟窗口」。

**实跑数字（`--offline --rerun-tasks`）**：

| 阶段 | 结果 |
|---|---|
| 新测试对旧实现 | **RED 4 / 5 failed** |
| 新测试对修后实现 | **5 / 5 通过** |
| 定向 `voice.semantics.*` + `voice.tools.*` | **188 tests / 0 failed / 0 errors / 0 skipped**，exit=0 |
| 全量 `:app:testDebugUnitTest` | **2278 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿** |

全量 6 条失败仍是既知两类：`AgentSkillCatalogTest > entry filePath points to SKILL md` 1 条、`MemoryStoreTest` 5 条，本轮未改、未跳过，未重跑旧 SHA 基线（上一 head 数字 2273 / 6 / 8）。日志 `logs/pr4-r25-*.txt` 只留本机。

**顺手补的规则**：`/src/closepaw/logs/` 加入 `.gitignore`（根锚定的 `/logs/` 只匹配仓库根，嵌套项目的运行日志此前未被覆盖，存在误提交设备/测试输出的风险）。已验证 `git check-ignore -v src/closepaw/logs/` 命中新规则。

**边界（如实）**：
- 全部为 JVM 合成无障碍树契约，不是三星真实 UI。`AccessibilityNodeInfo.refresh()` 在该 ROM 上的返回语义、`windowId` 在多次 `rootInActiveWindow` 读取间是否稳定**均未验证**——若真机的 `windowId` 在两次读取间发生变化，此守卫会比旧版更保守（拒绝，不误点）。设备矩阵维持历史 4 通过 / 0 失败 / 3 未测，本 SHA 未构建、未安装、未实测。
- 目前仓内唯一真正执行业务点击的路径就是 `cancelTimer`（`closeAlarm` 在绑定后一律 `BLOCKED`，从不点开关；`deleteAlarm` 复用前者），所以本守卫的生产面只有这一条；导航 tab 点击走 `clickTabControl`，不在本次范围。
- 仍未完成：业务状态回读（创建/取消后对象存在性与计数，需实机）、在线 `ToolRouter` 同门禁（阶段 2）、正文双标签卡片身份（需实机采集脱敏 UI 树）。`closeAlarm` 仍因缺全列表枚举/业务对象稳定身份 `BLOCKED`，是安全拒绝，不是功能完成。
- 本轮唯一写者持 `O_CREAT|O_EXCL` 锁，结束仅删自有锁；未构建/安装 APK、未操作手机、未调用付费 API、未 delegate、未并入 Issue #6、不改受保护规则、不 push main、不合并。

## 第⑳轮：定时复审 `f5d877a`（节点逻辑身份 + 候选集完整性；离线源码 + JVM，未实机）

需要复审；审查未关闭。定时任务本轮唯一写者（`O_CREAT|O_EXCL` 锁），结束仅删自有锁。

1. **[P1] 引用身份判等**：`navControl` 的 `seen.container !== nav.container`、`isNavBar` 的 `node === root`、`depthBelow` 的 `cur === bar`、`clickUnit` 的 `node === bar`、`insideScrollable` 的 `n === root`、`clickTabControl` 的 `node === nav.container`、`selectedState` 的 `node === nav.container` 全部改为 `sameNode()`（先 `===`，再 `runCatching { a.equals(b) }`）；`IdentityHashMap<AccessibilityNodeInfo, String>` 改为按逻辑等价对去重（不依赖 `hashCode`）。理由：`getParent()/getChild()` 返回的是节点信息包装对象，同一逻辑节点不保证同一实例；引用身份会让「同一条被当成两条 → 误判歧义」「容器/窗口根边界失效」「点击单元去重错配」。**语义仍是失败关闭**：拿不到等价关系时退回引用身份，最坏等于旧行为。
2. **[P1] 深度哨兵显式拒绝**：`depthBelow` 返回 `UNREACHABLE_DEPTH`（1000）时，旧代码只比「深度极差 ≤1」，两个都不可达时极差为 0 因而通过。现在任一标签不可达即拒绝该候选容器——没有父链证明的归属不算归属。
3. **[P2] 候选上限静默截断**：`collectExactLabels` 返回 `CandidateScan(nodes, complete)`；`matched > NAV_MAX_CANDIDATES`（第 9 个同名节点）或 `MAX_NODES` 中途截断（队列未清空）都置 `complete = false`，`navControl` 直接返回 null。旧的「预算内第一条合格 tab 条」不再被当成唯一容器。

TDD：新 `ClockNodeIdentityTest`(5) 针对上一 head `2212ed6` 的有效 RED **5 / 5 failed**（Truth 断言失败，非编译错误；`logs/pr4-review23-identity-red.txt`），修后 **5 / 5 通过**（`logs/pr4-review23-identity-green.txt`）。5 项分别钉：同一条两个包装对象不判歧义且点击只发一次、容器边界经包装对象仍兜住（0 点击）、窗口根经包装对象不得当导航栏、两侧标签都不可达时拒绝、第 9 个候选导致整轮拒绝。

最终：定向（`voice.semantics.*` + `voice.tools.*`）**172 tests / 0 failed / 0 errors / 0 skipped**，exit=0（`logs/pr4-review23-targeted.txt`）；全量 **2262 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**。失败仍是 `AgentSkillCatalogTest` 1 项、`MemoryStoreTest` 5 项，未改、未跳过；本轮未重跑旧 SHA 基线。

边界（如实）：全部是 JVM 合成无障碍树契约。三星真机在一次遍历内是否真的返回「等价但不同实例」的包装节点**未验证**；若该 ROM 的 `AccessibilityNodeInfo.equals` 不按逻辑身份实现，本轮收益不成立（但不会比旧行为更差）。`equals` 语义按 AOSP（windowId + sourceNodeId 一类逻辑身份）推断，`recycled`/`detached` 节点可能抛异常，已用 `runCatching` 退回引用身份。小型正文双标签卡片仍未被结构规则排除，需用户**重新授权**实机采集脱敏 resource-id/role/层级。设备矩阵不变（历史 4 通过 / 0 失败 / 3 未测），本 SHA 未构建、未安装、未实测。未调用付费 API、未 delegate、未并入 Issue #6、不改受保护规则、不 push main、不合并。

## 第⑲轮：审查第十八轮（导航栏容器边界 + 容器本身不可点；离线源码 + JVM，未实机）

针对第十八轮审查（`chatgpt-review SHA:56fd90b`）的两条 P2：

1. **导航栏识别无边界**：旧 `UiAutomator.navControl` 从精确标签向上爬，只要某祖先子树里出现两个时钟 tab 标签就认定为导航栏——页面正文同时有 `闹钟` 标题和 `定时器` 行时，**整个窗口根**也满足，于是 `clickTabControl` 可能点到正文或整页。现在 `navControl` 从 control 的**父节点**开始爬，并用 `isNavBar` 做有界判定：(a) 窗口根不是导航栏；(b) 各 peer 标签在容器内的深度极差 ≤1（真实 tab 条同层）；(c) 容器子树节点数 ≤40（吞下正文的容器不是 tab 条）；(d) 至少两个不同 peer 标签。仍是启发式，但缺证据返回 `null`（不可确认），不猜。
2. **容器本身可被点击**：旧 `clickTabControl` 在 `cur === container` 的那一轮**先**试 `ACTION_CLICK` 再返回 false，实际允许点整个导航条。现在进入循环先判容器即拒绝，容器永不作为点击目标。
3. 附带收紧：`selectedState` 只读 control → container **之前**的祖先，导航条自身的 `isSelected` 不再算 tab 选中证据。

TDD：先写 `ClockNavBarBoundaryTest`（6 项）在旧实现上跑出有效 RED `6 tests / 5 failed`（`logs/pr4-review19-red1.txt`，Truth 断言失败，非编译错误），再改生产代码转绿 `6 / 0`（`logs/pr4-review19-green1.txt`）。其中「真实 tab 条仍可解析并选中」是控制组，修复前即通过，不宣称它曾 RED。

旧 fixture 补真实结构（只补证据，不删弱断言）：`TabSelectedStateTest`、`UiAutomatorReadTest`、`ClockTabReachTest`、`ClockForeignWindowTest`、`ClockWindowGateTest`、`ClockAlarmPageEvidenceTest` 的合成树都加了「窗口根在 tab 条之上」的一层——真实 `rootInActiveWindow` 是窗口，不是 tab 条本身。中间态曾出现定向 144 / 12 failed（`logs/pr4-review19-targeted.txt`），全部是旧 fixture 把 tab 条本身当窗口根，补上窗口层后收敛，断言本身未删。

最终：定向（semantics.* + tools.*）**144 tests / 0 failed / 0 errors / 0 skipped**，exit=0（`logs/pr4-review19-targeted2.txt`）；全量 **2234 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**。失败仍是 `AgentSkillCatalogTest` 1 项、`MemoryStoreTest` 5 项，未改、未跳过；本轮未重跑旧 SHA 基线。

边界：全部为 JVM 合成无障碍树契约，不是三星真实 UI。真实三星时钟的 tab 条层级、是否暴露 `isSelected`、抢焦点恢复后同树证据仍需用户重新授权的实机验证；设备矩阵不变（历史 4 通过 / 0 失败 / 3 未测）。未构建或安装 APK、未操作手机、未调用付费 API、未 delegate、未并入 Issue #6、不合并、不 push main。

## 第⑱轮：审查 6543364579（导航控件身份 + 取消手势点击；离线源码 + JVM，未实机）

需要复审；审查未关闭。定时任务本轮唯一写者，O_CREAT|O_EXCL 取锁，结束仅删自有锁。

处理该审查第 2、3 条（第 1 条包名门禁已在第⑰轮完成）：

1. **导航点击不再走通用 click**：`ClockControl.clickClockTab` 原先调用 `UiAutomator.click(n)`，而该通用 helper 会向上找可点击父节点、最后用屏幕坐标 `tap()` 兜底。现在改用新增的 `UiAutomator.clickTabControl`：只对 tab 控件或**仍在其导航容器内**的可点击祖先做 `ACTION_CLICK`，永不进入父级无限上溯或坐标手势。
2. **选中证据限定到导航控件/容器**：`UiAutomator.selectedState` 现在要求精确标签节点属于一个可识别的导航栏（同一祖先子树内出现 ≥2 个不同时钟 tab 标签：闹钟/定时器/世界时钟/秒表，向上最多 5 层），并从该控件向上读到容器为止；否则返回 `null`（不可确认）。同包但非导航的同名节点（分区标题、设置行、提示卡）不再能当页面身份。`ScreenRead.selectedTabs` 同步改为按四个 tab 标签计算。

TDD：先写 `ClockTabControlIdentityTest`（7 项）在旧实现上跑出有效 RED `6 tests / 5 failed`（`logs/pr4-review18-red1.txt`，断言失败，非编译错误），再改生产代码转绿。其中"同包非导航精确标签不作为 tab"与"无导航容器时 selectedState 为 null"是行为 RED；新增的第 7 项 `readTree 不把非导航标签当闹钟页` 是在修复后补的防回归（其生产路径与已 RED 的 selectedState 用例相同），不宣称它本身曾 RED。

收紧后必须同步补 fixture 证据（不改断言、不跳过）：`TabSelectedStateTest`、`UiAutomatorReadTest`、`ClockTabReachTest`、`ClockForeignWindowTest`、`ClockWindowGateTest`、`ClockAlarmPageEvidenceTest` 的合成树补上父链与第二个 tab 标签；原先 `verify { UiAutomator.click(tab) }` 的正向断言收紧为 `tab.performAction(ACTION_CLICK)` = 1 且 `UiAutomator.click/tap` = 0。

最终：定向（semantics.* + tools.*）137 tests / 0 failed / 0 errors / 0 skipped，exit=0（`logs/pr4-review18-targeted.txt`）；全量 **2228 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**。失败仍为 AgentSkillCatalogTest 1 项、MemoryStoreTest 5 项，未改、未跳过；本轮未重跑旧 SHA 基线。

边界：全部为 JVM 合成无障碍树契约，不是三星真实 UI；导航栏"≥2 个 tab 标签同容器"是结构假设，三星是否暴露 `isSelected`、抢焦点恢复、真实布局与计时器唯一对象绑定仍需用户重新授权的实机验证。设备矩阵不变（历史 4 通过 / 0 失败 / 3 未测）。未构建或安装 APK、未操作手机、未调用付费 API、未 delegate、未并入 Issue #6、不合并、不 push main。

## 第⑮轮：审查 6542779543（针对 d543cd3；离线源码 + JVM）

需要复审，尚无独立 reviewer 批准。定时任务由用户暂停，本轮唯一写者；进入时 O_CREAT|O_EXCL 原子创建 .git/semantic-gate-writer.lock（owner/pid/started），完成时仅删除自有锁并检查不存在。

1. P2 模糊点击：openClockTab 移除 clickClockTab(tab)==null 时的 clickText(tab) 包含匹配兜底；精确缺失/点击失败均不授权模糊点击。新增无精确 tab、只有“对关联闹钟进行分组”提示卡的 fixture，断言 verified=false、tab=click-failed-label-only、通用文字点击/节点点击/提示卡 performAction 均0。真实行为 RED 1 tests / 1 failed / 0 errors → GREEN 1 / 0；不是编译错误。
2. P2 身份一致性：queryAlarms.alarmPage 统一调用 onTargetPage(read, "闹钟")，包名与 selected=true 必须来自同一 ScreenRead；checkableCount 和空态不能建立页面身份。旧实现已有 pageOk 阻止 verified=true，因此不虚构“旧版成功绕过”：新增 switches>0 + selected=false 在旧实现 verified=false，但错误诊断 alarmPage=true；有效 RED 2 tests / 1 failed / 0 errors（alarmPage 断言失败），修后 GREEN 2 / 0。empty + selected=null 在旧实现已安全拒绝，本轮增加防回归，不宣称曾 RED。两条均断言无法确认、不播确定可见列表/确定空态、verified=false。
3. selected 在三星真实 UI 是否稳定暴露仍待重新授权实机验证；JVM 合成树不证明可用性或跨调用原子快照。本轮未构建 APK、未上机；历史设备矩阵仍4通过/0失败/3未测，当前 SHA 未实测。

测试全部带 --offline --rerun-tasks，仓库根先 source env.sh，再 cd src/closepaw：

    ./gradlew --offline :app:testDebugUnitTest --tests '*ClockTabReachTest.missing exact*' --rerun-tasks --console=plain
    ./gradlew --offline :app:testDebugUnitTest --tests '*ClockAlarmPageEvidenceTest.*without selected tab*' --rerun-tasks --console=plain
    ./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.voice.semantics.*' --tests 'ai.closepaw.voice.tools.*' --rerun-tasks --console=plain
    ./gradlew --offline :app:testDebugUnitTest --rerun-tasks --console=plain

最终定向129 tests / 0 failed / 0 errors / 0 skipped，exit=0；全量2219 tests / 6 failed / 0 errors / 8 skipped，exit=1，不是全绿。AgentSkillCatalogTest 1项、MemoryStoreTest 5项原失败未改、未跳过，未重跑旧SHA基线。全量失败名称见本机 logs/pr4-review15-full-summary.json；日志 logs/pr4-review15-{click-red,click-green,page-red,page-green,targeted,full}.txt，XML与汇总只留本机，不提交日志。

移除模糊点击后首次定向129/11 failed，补全旧 fixture 的导航选中或真实精确节点证据；中间129/3 failed因新导航点击违反既有零点击断言，改fixture为已选中+无点击且显式控制等待，保留原断言，最终129/0。恢复精确tab=1/提示卡=0断言保留，旧 clickText=1 收紧为 clickText=0，未删弱既有安全断言。Android/UI边界 MockK，真实 ClockControl/Runner 执行。

剩余：实机 selected 暴露、抢焦点恢复、真实布局及计时器唯一绑定仍需用户新授权；closeAlarm 因缺全列表/业务对象身份继续 BLOCKED，关闭功能未完成；对象身份适配、在线ToolRouter同门禁（阶段2）和通用业务终态校验（阶段3）属于后续源码工作，不是只能等待实机。未delegate、未调用付费API、未并入Issue6、未改受保护规则、不push main、不合并。回滚用git revert。

## 第⑭轮：接管 cron 未完成修改，审查 6542191769（离线源码 + JVM）

需要复审；A–D 离线项已完成，尚无独立 reviewer 批准，不合并。接管时保留 cron worker 未提交的 ClockControl.clickClockTab Boolean? 与 ClockTabReachTest 新用例；先临时还原生产代码至 HEAD 验证 RED，再恢复并验证 GREEN，未丢弃这两处来源修改。

- A：精确控件点击返回 false 后不再进入 clickText；仅未找到精确控件（null）允许原有兼容回退。ClockTabReachTest 有效 RED 7 tests / 1 failed，GREEN 7 / 0。保留 hint card 不点击、精确节点仅点1次断言。
- B：ScreenRead.selectedTabs 在 readTree 中从同一个 r 计算；onTargetPage 只接受该 read 的包名与目标 selected=true，不再读取恢复前 reach.selected。null/false/缺 key 一律不可确认。查询恢复三条独立负向，以及新读 alarm=false：有效 RED 5 / 4，GREEN 5 / 0。初次修复将 verified 降为 null 未满足明确 false 断言，补显式 false 而非弱化断言。
- 同源采集回归 UiAutomatorReadTest：拿掉采集逻辑的旧实现有效 RED 5 / 1（期望 true 实际 null）；恢复采集后最终定向 GREEN，检验同树 alarm=true/timer=false 与无 tab 时 null。
- C：verifiedClockWindow 所有 attempt 都只读绑定 root 的 selectedState，缺失/false 返回不可确认；删除 reach 参数与旧回退。新增初始绑定 null、初始 false、恢复 false 独立调用回归。有效 RED 8 / 1（应 DEVICE_UNAVAILABLE 实 BLOCKED），GREEN 8 / 0；false 及点击失败本身是已有安全拒绝，不伪称这些用例曾 RED。
- D：独立覆盖查询 false、绑定 false（初始/恢复）、点击失败（query 精确失败、cancel 失败）、closeAlarm（新读 false、恢复 null/false）；保持 verified=false、不报确定数量、不进入绑定/业务点击断言。closeAlarm 恢复两条单独在 HEAD 旧实现跑出 RED 2 / 2（错用旧证据进入“当前可见区域未找到”），修后最终定向全部 GREEN。追加 closeAlarm 绑定阶段 null/false 独立回归：在 HEAD 有效 RED 2 / 1（null 错入 BLOCKED），修后全部 GREEN；保留同树读取1次、节点操作0次断言。

最终测试（每次 --offline --rerun-tasks，无 APK 构建任务）：

    source env.sh
    cd src/closepaw
    ./gradlew --offline :app:testDebugUnitTest --tests 'ai.closepaw.voice.semantics.*' --tests 'ai.closepaw.voice.tools.*' --rerun-tasks --console=plain
    ./gradlew --offline :app:testDebugUnitTest --rerun-tasks --console=plain

定向 126 tests / 0 failed / 0 errors / 0 skipped，exit=0。全量 2216 tests / 6 failed / 0 errors / 8 skipped，exit=1，不是全绿。AgentSkillCatalogTest 1 项与 MemoryStoreTest 5 项原失败未改、未跳过，本轮未重跑旧 SHA 基线。首次小范围定向 94 / 25 failed、首次全量 2214 / 8 failed，均因旧正向 fixture 未携带新同树证据；只补 fixture 证据、保留既有断言（正向 fixture 名称同步），最终收敛为以上数字。日志及 XML/JSON 留本机 logs/pr4-review14-*，不提交日志或个人设备数据。

风险与剩余：selected 不暴露时会比旧实现更保守地拒读，不以 checkableCount/空态或导航成功覆盖缺失选中证据。JVM 同源树证据并非 Android 跨调用原子快照或终态验证。三星是否稳定暴露 selected、窗口抢焦点恢复、真实布局与计时器唯一对象绑定仍需另行授权实机；历史矩阵仍 4 通过 / 0 失败 / 3 未测。closeAlarm 缺全列表/业务对象身份继续 BLOCKED，是不可用的安全拒绝，不是实机验收通过；补对象身份和阶段2/3是后续源码工作，不能说所有项目剩余均只能等实机。

未 delegate、未调用付费 API、未并入 Issue6、未改受保护规则、未构建安装 APK、未操作手机、不 push main、不合并。回滚用 git revert，不改写历史。



Issue #3 / PR #4；分支 `feat/semantic-gate`；2026-10-31。
性质：源码分析 + 本机 Gradle JVM 回归 + 第五轮真机复测。阶段 1 仍待审查/设备验收，不代表整个 Issue 完成。

## 第七轮：同一窗口校验门 + 截断不当完整 + tab 精确匹配（源码 + JVM + 真机只读）

依据：PR #4 复审 `6539848799`（针对 head `3253319`）的三项遗留；本轮 HEAD 起点 `03443b4`，本轮提交 `54e4b77` / `f122920` / `525ca49`（`feat/semantic-gate`）。

### 1) P1：取消/关闭路径改用同一窗口校验门

旧路径：`openClockTab` →（`foregroundPackage()` 另读一次）→ `UiAutomator.root()` 扫描 → 绑定节点 → 点击。检查与使用是两次读，中间窗口可以被 `VoiceActivity` 抢走；即使不被抢走，绑定的树也可能不是被检查的那一棵。

现路径：`verifiedClockWindow(ctx, tab, reach)` —— 取 `UiAutomator.root()` 并**就地**要求该对象 `packageName == com.sec.android.app.clockpackage`，故“检查的”和“使用的”是同一个对象；拿不到时复用 `readClockScreen` 的恢复策略（重开时钟 + 重选 tab，最多 `CLOCK_READ_ATTEMPTS` 次）。`cancelTimer` / `closeAlarm` 都只从这个根绑定节点，并在点击前再查两条：被点节点自身属于时钟包、当前活动窗口仍属时钟。任一不满足：

- `cancelTimer` → `DEVICE_UNAVAILABLE`「无法确认当前是时钟窗口 / 取消前时钟窗口变了」，不点击；
- `closeAlarm` → `DEVICE_UNAVAILABLE`「无法确认当前是时钟窗口 / 关闭前时钟窗口变了」，**不会**据此说「没有X点的闹钟」。

没有坐标点击、没有盲点兜底、没有为了通过率放宽拒绝分支。

### 2) P2：截断不再被当作完整

- `timerCountFrom(texts, truncated)`：截断时只有「N 个定时器正在运行」这类**陈述式**文本可用；「添加定时器」空态标记与卡片计数都必须基于完整读取。判定顺序修正（旧代码先看空态再看截断，截断树仍可能报 0）。
- `queryAlarms`：`readable` 要求 `pageOk && alarmPage && !truncated && (listed.isNotEmpty() || empty)`，其中 `alarmPage = reach.selected == true || read.checkableCount > 0 || empty`。新增 `UiAutomator.ScreenRead.checkableCount`（读取时统计 `isCheckable` 节点）作为**结构证据**：世界时钟页同样有 `H:mm` 文本但没有闹钟开关，因此不再可能被枚举成闹钟列表。`detail` 增加 `alarmPage=`/`checkables=`。
- `closeAlarm`：截断且未找到目标 → 「闹钟列表读取被截断，没法确认…」；未截断但页面不是闹钟列表（无选中态、无开关）→ 「无法确认当前是闹钟列表…」，都不说「没有这个闹钟」。

### 3) tab 到达语义复核（第六轮之后）

第六轮的三态 `selectedState` 已消除“标签可见即到达”：点击失败且只有标签可见 → `click-failed-label-only`，`reached=false`。本轮复核发现**匹配方式**仍有缺陷（真机证据）：`clickText("闹钟")` 与选中态读取都用 `contains`，先命中了闹钟页的提示卡「对关联闹钟进行分组」——既可能点不到 tab，也会把已选中的 tab 读成“未选中”。

- `UiAutomator.selectedState`：**精确标签优先**（`text`/`contentDescription` 去空格小写后相等），包含匹配只作回退。
- `ClockControl.clickClockTab`：先点标签**精确等于** tab 名的节点，找不到才回退旧的 `clickText`。

### 测试（真实执行）

| 测试 | 断言重点 | 结果 |
|---|---|---|
| `ClockWindowGateTest`(8) | 外来窗口不绑定/不点击计时器与闹钟；节点属他包不点击；截断下「添加定时器」不给 0；无开关证据的时钟页不报数量；tab 点击落在标签节点而非提示卡 | 上一提交上 **4 项红** → 修后全绿 |
| `ClockAlarmPageEvidenceTest`(6) | `checkableCount>0` 才枚举；明确空态可判空；窗口被抢后重开+重选 tab 再点；无法确认窗口时零点击 | 全绿 |
| `TabSelectedStateTest`(+2) | 包含标签不决定选中；无精确标签时回退 | 先红后绿 |
| `ClockWindowGateTest`(+1) | tab 精确点击 | 先红后绿 |

- 定向：`ai.closepaw.voice.semantics.*` + `ai.closepaw.voice.tools.*` = **101 tests / 0 failed**。
- 全量 `./gradlew --offline :app:testDebugUnitTest` = **2194 tests / 6 failed / 8 skipped / 0 errors**；失败仍为既知 `AgentSkillCatalogTest`(1) + `MemoryStoreTest`(5)，与基线一致，**不是全绿**。
- fixture 补全（未削弱断言）：`AlarmQuerySafetyTest` / `ClockForeignWindowTest` 的闹钟页补 `checkableCount`（真实闹钟行有开关）；`TimerObjectExecutionTest` 的节点补时钟包名；`SemanticExecutionTest` 的“取消不存在的闹钟”改用真实空态页，另一例改为“两个时钟自己的取消按钮、无时长证据”。

### 真机只读复测（本轮，非破坏性）

设备 Samsung SM-F731U1 / Clock 12.5.60.27；APK SHA256 `c42d72227152f9689cfffcfcfe772e2e76a6301c219f78ff8e8949e34d00347a`；`install -r` Success，安装后 `dumpsys accessibility` 仍 `id=ai.closepaw/.app.AgentService, isDefault=true`，`voice_interaction_service` 保持 `ai.closepaw/.voice.ClosepawVoiceInteractionService`（未改助手角色），未 force-stop、未清数据。

| 场景 | 命令 | 实测 detail |
|---|---|---|
| 闹钟查询 ×3 | `am start -n ai.closepaw/.voice.VoiceActivity --es fake_text "现在有几个闹钟"` | 2 次 `verified=true visible=[6:00, 7:30, 8:00, 9:20, 10:00, 11:00] alarmPage=true checkables=6 tab=clicked rootPkg=com.sec.android.app.clockpackage nodes=67`；1 次 `verified=false visible=[] tab=clicked-not-selected`（拒读，不报数） |
| 计时器查询（空态） | `fake_text "现在有几个倒计时"` | `verified=true tab=clicked nodes=44`（count=0，未给数字即 0 分支）；另有 1 次 `tab=label-not-found window=not-clock` 拒绝 |
| 自建对象：2 分钟倒计时 | `fake_text "设置两分钟倒计时"` | `CREATE/TIMER{seconds=120} status=SUCCESS verified=false detail=clockFg=true countdown-ui=true; no newly created object identity` |
| 自建对象查询 | `fake_text "现在有几个倒计时"` | `verified=true detail=count=1 tab=clicked nodes=27 rootPkg=clock` |
| 自建对象取消 | `fake_text "取消两分钟倒计时"` | `CANCEL/TIMER{seconds=120} status=BLOCKED verified=false detail=duration=120 candidates=0 complete=true`（窗口门通过、无「初始时长」证据 → 不点击） |
| 清理 | 计时器自然到期，`am broadcast -a android.intent.action.DISMISS_TIMER` 结束提示音 | 终态查询 `verified=true nodes=44`（空态，count=0） |

### 明确未解决 / 残余风险

- **到达判定仍非 100% 确定**：本机 2/3 次读为 `tab=clicked`（选中态未观测到 `true`）、1 次 `clicked-not-selected`。`selected` 在本机是否稳定暴露仍未定论；因此“页面正确性”靠 `alarmPage` 结构证据兜底，而非仅靠选中态。
- **取消/关闭仍不可用（失败闭合）**：本机计时器卡片没有「初始时长/总时长」标签，`cancelTimer` 永远 `BLOCKED`；`closeAlarm` 在真机未执行（本轮授权范围只含只读查询、tab 切换与自建可清理对象）。
- 矩阵不变：通过 4 / 失败 0 / 未测 3（计时器唯一绑定、五分钟真实参数、取消不存在目标的实机证据）。阶段 1 不因本轮通过；PR #4 不合并。

## 第六轮：tab“到达”语义拆分（源码 + JVM，未实机）

依据 PR #4 复审 `6539752681` 的 P2：`openClockTab` 用 `clickText(tab) || waitForText(tab, 800)`，点击失败但只要标签仍在屏上就返回 true。
时钟底部四个标签在**每一页**都存在，因此“标签可见”不能证明切到了目标页；世界时钟页的时间文本同样匹配 `^[0-9]{1,2}:[0-9]{2}$`，可能被当成闹钟列表。

- **三个事实分开记录**：`UiAutomator.selectedState(root, text): Boolean?` —— `true` 选中；`false` 树里暴露了选中状态且该 tab 未选中；`null` tab 不存在或该树根本没暴露选中状态。`ClockControl.openClockTab` 只在 `clicked && selected != false` 或 `selected == true` 时算到达，`reason ∈ {clicked, clicked-not-selected, selected, click-failed-label-only, launch-not-foreground, label-not-found, app-not-found}`。
- **读取前复核目标页**：`onTargetPage(read, tab)` 要求 `rootPackage == com.sec.android.app.clockpackage` **且** 该 tab 未被报告为未选中；`queryAlarms` / `queryTimers` / `closeAlarm` 统一走这条门。`selected == null`（平台不暴露选中态）不阻止读取，但会在 `detail` 的 `tab=` 里如实写出。
- **`closeAlarm` 不再读原始活动窗口**：改用 `readClockScreen`，外来窗口（例如 ClosePaw 自己的 `VoiceActivity`）返回 `DEVICE_UNAVAILABLE`，不会把“读不到”说成“没有这个闹钟”。
- **未使用坐标或盲点兜底**；没有为了通过率放宽任何拒绝分支。
- **TDD**：新增 `ClockTabReachTest`(6) 与 `TabSelectedStateTest`(6)。RED 为行为失败（6 项 `ClockTabReachTest` 全失败，`TabSelectedStateTest` 的祖先选中项失败），随后 GREEN。
- `ClockReadDiagnosticsTest` 的 `tab=label-only` 断言改为新的 `tab=click-failed-label-only`（原因名变化，拒绝语义更强，未放宽）；`SemanticExecutionTest` 的 fixture 补上时钟包名，否则“取消不存在的闹钟”会先被外来窗口门拦下——这是 fixture 补全，不是断言削弱。
- **本轮未实机**：`selected` 在本机三星时钟上是否真的暴露未验证；`null` 时仍按“点击成功”判定，属已知残余风险，已在 `detail` 中可见。

同轮补丁（复审 `6539848799` 对 head `3253319` 的遗留项）：

- **绑定与点击前的窗口复核**：`closeAlarm` / `cancelTimer` 原先在 `openClockTab` 之后直接 `UiAutomator.root()` 扫描并点击节点，只证明了“读到的页面”，没证明“点击时仍是时钟窗口”。现两处在 scan 前与 click 前各查一次 `foregroundPackage == com.sec.android.app.clockpackage`，不符即 `DEVICE_UNAVAILABLE`（“无法确认窗口/窗口变了”），**不会**据此说“闹钟不存在”，也不点击。
- **截断不再变成确定结论**：`timerCountFrom(texts, truncated)` 在截断时返回 -1（未知），只有页面上明确写着「N 个定时器正在运行」这种陈述式文本才照常采用；`queryAlarms` 的 `readable` 要求未被截断，闹钟播报新增“读取被截断”分支；`closeAlarm` 在截断且未找到目标时答“没法确认”，不再答“没有这个闹钟”。`detail` 增加 `truncated=`。
- **TDD**：`ClockWindowBindingTest`(6) 先失败 5 项（窗口切换后仍点击、外来窗口扫描、计时器截断计数、闹钟截断可读、窗口切换未拦截），随后 GREEN。定向 74/74；全量 **2177 tests / 6 failed / 8 skipped / 0 errors**，失败仍为 `AgentSkillCatalogTest`(1) + `MemoryStoreTest`(5)。
- 仍未实机：窗口切换拦截只经 JVM fixture 验证；三星时钟是否会在读取与点击之间把前台交回 `VoiceActivity` 未实测。

## 第五轮：P1 根因定位 + 修复复测（实机验证）

依据 PR #4 复审 `6539505148`。第四轮只做了可观测性；本轮采集真机证据后定位并修复。

- **根因（实机验证）**：不是开错页、不是服务/权限、不是等待不足。`am start` 时钟后 0.3–6 s 活动窗口稳定为 `title=时钟 type=TYPE_APPLICATION focused=true active=true`，`dumpsys accessibility` 全程 `Bound services: id=ai.closepaw/.app.AgentService`、`retrieveInteractiveWindows=true`、`canRetrieveWindowContent=true`。失败发生在**读屏那一刻根节点属于 `ai.closepaw` 自己的 VoiceActivity 窗口**（`rootPkg=ai.closepaw nodes=3`），而同一时刻 dump 的时钟页有 44–67 个文本节点。全屏不透明 `VoiceActivity`（`#F2101014`, `singleTask`）中途抢焦点；旧代码点完 tab 固定 `sleep(500)` 后直接读且不校验包名。
- **次因（实机验证）**：本机（Clock 12.5.60.27）定时器空态是创建选择器（`小时/分钟/秒` + `开始` + 预设定时器），运行态是卡片（`30 分钟` + `29 分钟 xx 秒钟` + `取消`/`暂停`）。旧 `runningTimerCount` 只认「N 个定时器正在运行」「添加定时器」，在**可读页面**上必然 -1，并谎报“打不开定时器页面”。
- **修复**：`UiAutomator.readTree` 真实计算 `nodesTruncated/textsTruncated`；`ClockControl.readClockScreen(ctx, tab, limit)` 校验 `rootPackage == com.sec.android.app.clockpackage`，不符则最多 3 次“重开时钟 + 重选 tab + 重读”；`readable` 增加包名校验；`timerCountFrom` 增加“卡片计数/选择器空态”，未识别保持 -1。安全语义未放宽：外来窗口的文本永远不计为列表证据。
- **TDD**：`UiAutomatorReadTest`(4) / `ClockForeignWindowTest`(5) / `TimerCountShapeTest`(6) 先失败后通过；定向 43/43 通过（`logs/round5-test-all-clock.txt`）。
- **设备复测**：APK SHA256 `45e74c036d78c6c0c33f36f22e13d24812ad4a9c13c1c8a206a9202db60a2333`，`install -r` 后无障碍仍 Bound。ALARM QUERY `verified=true readable=true visible=[…6 项…]`（2 次独立运行）；TIMER QUERY 有 1 个运行→`count=1`、清理后→0，均 `verified=true`。本轮自建并清理的测试对象：30 分钟倒计时（`dumpsys alarm` 可见 `timer.playsound`，取消后 `Pending alarm batches` 无 clockpackage 项）。
- **仍未修**：`cancelTimer` 只认「初始时长/总时长」，本机卡片无该标签 → BLOCKED（失败闭合、不误点）；多卡片计数布局未实机验证。

## 第四轮：PR 复审 P1/P2（可观测性 + 文档，未实机）——已被第五轮取代

依据 PR #4 复审评论 `IC_kwDOVdfNwc8AAAABhcjt_A`（2026-10-31T05:21:21Z）：
P1 要求先定位时钟 UI 读取失败（前台包、服务根节点、窗口来源、点击后状态的最小必要只读证据）；
P2 要求设备验收文档区分历史轮次与最新结论。Issue #6 的会话上下文与多级导航不在本 PR 扩项。

**本轮边界**：不猜根因、不改匹配规则、不改等待策略、不动 tab 文案、不实机、不装包。
只做两件事：P2 文档订正；把"读不到"与"确实没有"在源码层分开报告（诊断），使下一次设备只读运行能给出可归因证据。

### 源码分析（`源码分析`，未实机）

设备记录为 ALARM `verified=false readable=false visible=[]`、TIMER `DEVICE_UNAVAILABLE`。
下列位置都会让"没读到"和"页面确实没有"落进同一个结论，属候选解释，**均未验证、不作为根因宣称**：

1. `ClockControl.kt` 旧 `openClockTab` 返回 `clickText(tab) || waitForText(tab, 800)`：只要标签仍在屏幕上就算"已到达"，即使点击失败或根本没切页。
2. 旧 `queryTimers` 完全忽略 `openClockTab` 的返回值，把任何读不到统一写成 `ui unavailable`，无法区分"没切到定时器页"与"页面对了但没内容"。
3. `UiAutomator.screenTexts` 旧实现同时受 600 节点与 `limit` 文本两个上限约束，命中即静默返回短列表；调用方拿到的空列表既可能是"树被截断"也可能是"页面真没有"。列表行位于树较深层，BFS 先扫浅层。
4. 闹钟行识别用 `^[0-9]{1,2}:[0-9]{2}$`（`queryAlarms` / `closeAlarm`）：若真实行文本带"上午/下午"或与 contentDescription 合并，即使读到行也不会被当作时间。
5. `root()` 取 `rootInActiveWindow`，且前台包只在点击前读一次；若读到的是别的窗口（弹窗、编辑页、多窗口），内容属于别的界面。
6. 固定 `Thread.sleep(500/700)`，没有条件等待；冷启动或切页慢时可能读得太早。

### 本轮改动（诊断，不改变"什么算读到"）

- 新增 `UiAutomator.readScreen(limit): ScreenRead`（`texts / nodesVisited / nodesTruncated / textsTruncated / rootPackage / serviceAvailable`）；`screenTexts` 退化为它的投影，返回值与旧实现一致。
- `ClockControl.openClockTab` 返回 `TabReach(reached, reason)`，`reason ∈ {clicked, label-only, launch-not-foreground, label-not-found, app-not-found}`；`reached` 与旧布尔语义等价，运行逻辑不变。
- `queryAlarms` / `queryTimers` / `closeAlarm` 的 `detail` 追加同一时刻的 `tab=… nodes=… nodesTruncated=… textsTruncated=… rootPkg=… a11y=…`；`queryTimers` 不再把原因写成 `ui unavailable`。
- 未通过条件、`verified` 判定、拒绝策略一条都没放宽；没有新增"看起来通过"的分支。

### TDD 记录

- RED：`logs/pr4-review4-targeted-1.txt` 三处行为断言失败（timer 原因、label-only 区分、读取边界字段），随后 GREEN `logs/pr4-review4-targeted-2.txt`（51 tests / 0 failed）。
- RED（截断检测独立一轮）：`logs/pr4-review4-trunc-red.txt` 2 项失败（文本上限、节点上限）；GREEN `logs/pr4-review4-trunc-green.txt`。
- `AlarmQuerySafetyTest` 原"不可达 tab 不读屏"断言改为"仍读一次、仅用于报告边界，其文本不得成为列表证据"，播报与 `verified=false` 断言未动。

### 仍未做 / 需要设备证据

根因未定位。上述 4/5/6 三条若要定论，需要一次**用户授权的只读运行**采集：点击前后前台包、`readScreen` 的 `rootPkg`/`nodesVisited`/`nodesTruncated`、tab 节点是否存在与 `isSelected`、以及脱敏后的行文本形状（不含具体时间与名称）。本轮没有该授权，因此不改 UI 匹配与等待逻辑。

以下为历史轮次记录。

## 第三轮审查修复（评论 6538790664）

- P1：统一为 `ActionMemory.recordResult` 一个结果准入入口。时钟创建由 ClockControl 调用一次，Runner 不再重复记录；AppNavigator 也不再嵌套记忆。只有 SUCCESS 且 verified=true 的现有固定手电逆操作可保留；所有时钟 CREATE 即使 verified=true 也不接受时长/时分作为对象身份。
- 当前时钟 Intent 没有本轮新增对象 ID，也没有按该 ID 撤销的 API，故本轮选择安全拒绝全部时钟创建 undo，不虚构 receipt/对象 ID。删除 ActionMemory 中按 seconds/hour/minute 调用取消的逆操作，缺少证明回答“不能安全撤销”。明确时间/时长的用户取消命令仍保留原有唯一 UI 定位路径，不是 undo。
- 新的时钟创建尝试会清除旧 undo 状态，防止“刚才”退回更旧的手电等操作。入口调用次数有精确断言。旧 undo 时长传递测试改为更严格的“即使 verified=true，只有时长也不能自动撤销”，不是放宽断言。
- P2：nextAlarm=null 仅说明系统没有下一个已启用闹钟；当前可见列表有时间时明确列表仍有闹钟、可能含关闭项、不推定全部开关状态；只有明确空态文字可报告当前页面列表为空；UI 不可读或 tab 打不开时 verified=false。可见列表不冒充完整枚举。
- 有效 RED：`logs/pr4-review3-undo-red.txt` 2 项行为断言失败；`logs/pr4-review3-query-red.txt` 4 项行为断言失败；追加旧记忆污染用例 `logs/pr4-review3-stale-red.txt` 1 项行为断言失败。中途 Boolean? 生产编译错误和测试 Target 导入错误均已修复，不作为 RED。
- 最终定向 GREEN：48 tests / 0 failed / 0 errors / 0 skipped。类数量 23+10+6+3+2+4。日志 `logs/pr4-review3-targeted.txt`，程序汇总 `logs/pr4-review3-targeted-summary.json`。
- 全量：2138 tests / 6 failed / 0 errors / 8 skipped，Gradle exit=1，不是全绿。失败仍为 AgentSkillCatalogTest 1 项、MemoryStoreTest 5 项；本轮未重跑旧 SHA 基线。日志 `logs/pr4-review3-full.txt`，失败名称完整保存在 `logs/pr4-review3-full-summary.json`。
- 真实运行边界：parser / guard / runner / gate / ClockControl / IntentActions / ActionMemory 未替换；Context、Intent、系统 AlarmManager、无障碍树用 MockK。旧同参数对象设置为可点击、可关闭，OEM startActivity 无状态改变；断言旧对象 ACTION_CLICK=0、创建请求=1、记忆入口=1、旧手电调用=0。倒计时覆盖完整 Runner 创建→Runner undo；闹钟覆盖 ClockControl 创建→ActionMemory undo。
- 无独立 reviewer 结论：依用户要求未使用 delegate；不冒称复审通过。未构建可安装 APK、未实机、未推进阶段 2/3，受保护规则未改。

复现命令（`src/closepaw`，先 `source ../../env.sh`）：

    ./gradlew :app:testDebugUnitTest --tests '*IntentGateTest' --tests '*SemanticExecutionTest' --tests '*TimerObjectExecutionTest' --tests '*IntentCreationVerificationTest' --tests '*ClockUndoSafetyTest' --tests '*AlarmQuerySafetyTest' --console=plain
    ./gradlew :app:testDebugUnitTest --console=plain

以下为历史轮次记录；历史“undo 保留时长即可撤销”的说明已被本轮身份准入和安全拒绝策略取代。

## 第二轮审查修复（评论 6538288842）

- 修复新建计时器假验证：旧计时器文字只能说明页面有倒计时，不证明本次请求创建了指定对象。`setTimer` 不再因此返回 `verified=true` 或播报“已经开始”。
- 修复新建闹钟假验证：`nextAlarmClock` 时间匹配不能证明本次新增/更新；匹配也返回 `verified=false`。更早的其他闹钟不代表目标不存在，不再推测停在确认界面。这里只降级不可靠的验证，不引入未经验证的对象回读器。
- 删除闹钟保留 `closeAlarm` 的真实反馈，明确“未删除”；未确认关闭不播报“已经关闭”。
- 新增真实 `IntentActions.setTimer/setAlarm` 调用测试，Android Intent/Context/AlarmManager 与无障碍边界用 MockK；覆盖旧十分钟倒计时、既存同时间闹钟、更早其他闹钟。删除反馈测试隔离 closeAlarm 返回值，不冒称该测试覆盖了真实 UI。
- TDD 有效 RED：`logs/pr4-creation-red.txt`（1 行为断言失败）、`pr4-alarm-create-red.txt`（2 行为断言失败）、`pr4-delete-feedback-red.txt`（1 行为断言失败）。首次 Intent 链 mock 不全的 NullPointerException 已修复，不作为 RED 证据。
- GREEN：四类定向 JVM 测试 **42 tests / 0 failed / 0 skipped**（23+10+6+3），`logs/pr4-review2-targeted.txt`。命令为上文定向命令加 `--tests '*IntentCreationVerificationTest'`。
- 全量实跑 **2132 tests / 6 failed / 8 skipped / 0 errors**，命令退出失败，`logs/pr4-review2-full.txt`；失败仍是 AgentSkillCatalogTest 1 项、MemoryStoreTest 5 项。本轮未重新运行旧 SHA 基线，不把上轮基线复跑写成本轮动作。
- 未构建 APK、未操作手机、未验证 expectedState，阶段 1 待复审，阶段 2/3 未推进。

## 已修复的审查问题

1. ALARM 能力表补上 CANCEL。回归经过真实 `SemanticParser → CapabilityGuard → SemanticRunner → IntentGate → ClockControl`，8 点闹钟不存在时进入查询并拒绝，不会调用创建。
2. 所有已解析参数在 ToolCall 缺失时拒绝，不再 `?: continue`。取消五分钟的 seconds=300 一直传至 `ClockControl.cancelTimer(ctx, seconds)`；即使规划丢失参数也从原话复核时长。撤销路径同样保留记忆时长，记忆缺失就拒绝。
3. 取消计时器移除全局 `clickText("取消")` 和记忆失败后选第一个的兜底。只允许在非全屏根节点的最小子树中把明确的初始/总时长与唯一取消按钮绑定；所有可见取消按钮都必须可解释，目标时长匹配唯一。零匹配、重复时长、另一张卡片时长未知、树缺失/截断、全局时长文字无法绑定等均拒绝/追问。点击仅用绑定节点的 ACTION_CLICK，不用父节点或坐标兜底。
4. 闹钟关闭同样拒绝重复时间，只绑定同一最小子树中的唯一时间与唯一开关；不再点时间节点打开编辑器，不再跨祖先拿第一个开关。
5. 一般 APP / APP_FEATURE 不再以包名非空作为身份证明。闸门从原话重取应用名/功能，用独立安装清单中的 label/alias/完整包名精确匹配；必须唯一且与实际启动包一致。谷歌/三星商店保留明确别名映射。错误解析“微信→QQ”、缺少安装清单、同名候选、规划与调用一起把微信改成QQ或把扫一扫改成付款码，均拒绝。
6. 补上原话动作/目标与规划动作/目标的独立比较，避免规划与调用同时把 CANCEL 改成 CREATE 后互相比对仍放行。闹钟原话时间优先于规划 targetIdentity，不能把取消八点改为七点。

## 真实验证边界（纠正旧文案）

- IntentGate 比较的是原话、结构化规划、实际调用描述，以及安装清单/对象定位证据。
- `SemanticTask.expectedState` 仅是描述性元数据。没有通用终态评估器，没有执行后验证 expectedState；不能声称“四方验证”或业务终态已验收。CapabilityGuard 仅对 QUERY 的 `mutate:` 元数据有特殊拒绝规则。
- 本次计时器/闹钟 mutation 的点击成功返回 `verified=false`，明确“已请求，但无法确认最终状态”；不把计时器总数减少或旧开关引用当成功。已关闭闹钟仅依据当前唯一绑定开关状态报告。
- 计时器 UI 适配只使用明确 `初始时长` / `总时长` 文字证据。这是 JVM 合成无障碍树 fixture 的契约，不是采集自三星时钟的真实 UI；真实设备若无此证据将拒绝，不能声称三星取消功能已可用。剩余时间、裸“5分钟”或记忆时长不是对象身份证明。
- APP 身份核对的信任来源是 AppRegistry 安装清单及本地别名；不是密码学证明。模糊子串结果即使 resolver 给出一个包，也不能作为精确身份通过闸门，可能比旧行为多追问。
- 原先补回设备能力层的 `577e926` 实际新增 10 个 Kotlin 文件，不是 13 个（另有 .gitignore）。以 `git diff-tree --no-commit-id --name-only -r 577e926` 为依据。
- 在线 ClosePaw ToolRouter 尚未接入同一闸门，属阶段 2；`LocalCommandRouter` / `KwsEngine` 仍无调用方。AppNavigator 的既有 feature UI-change 检查不是业务终态验证，阶段 3 尚未完成。

## 测试与证据

在 `src/closepaw`，先加载仓库根目录 `env.sh`，再执行：

    ./gradlew :app:testDebugUnitTest --tests '*IntentGateTest' --tests '*SemanticExecutionTest' --tests '*TimerObjectExecutionTest' --console=plain

结果：38 tests / 0 failed / 0 skipped。IntentGateTest 23，SemanticExecutionTest 9，TimerObjectExecutionTest 6。生产 parser/能力表/runner/gate/clock/app resolver 实际运行；Android Context、无障碍树、安装清单与系统执行边界用 MockK，不模拟整个执行器来宣称集成通过。

回归覆盖：取消不存在闹钟、重复闹钟、五分钟参数缺失/漂移、原话取消被规划反转、多个计时器中只点击指定卡片、重复/不存在/未知时长对象拒绝、全局文字不可绑定、一般应用错包/同名/无证据、真实 resolver 正向启动、二级功能控件缺失不报成功、undo 时长保留。保留已有反向/查询/时长/商店/条件等强断言；旧“取消可丢参数”的允许断言改为拒绝，并增加完整参数正向通过断言。

TDD：每个修复切片先观察对应行为断言失败，再修改生产代码转绿。初次测试代码 Log.w 重载类型编译错误经明确 String 类型修正，该编译错误不作为 RED 证据。有效 RED 日志：

- `logs/pr4-alarm-red.txt`：取消路径能力表不可达。
- `logs/pr4-timer-red.txt` / `pr4-timer-object-red.txt`：丢时长、全局取消/多对象。
- `logs/pr4-app-red.txt`：微信错误解析为 QQ 仍启动。
- `logs/pr4-alarm-duplicate-red.txt`：同时间多个闹钟盲选。
- `logs/pr4-raw-intent-red.txt`：规划和工具一起反转原意。
- `logs/pr4-undo-red.txt`：undo 丢时长。
- `logs/pr4-unbound-red.txt` / `pr4-global-scope-red.txt`：未绑定对象/全屏容器兜底。
- `logs/pr4-alarm-identity-red.txt`：规划身份伪造闹钟时间。

全量命令：`./gradlew :app:testDebugUnitTest --console=plain`。最终结果：2128 tests / 6 failed / 8 skipped；不是全量通过。完整本机输出在 `logs/pr4-final-full.txt`。已知失败为 AgentSkillCatalogTest 1 项、MemoryStoreTest 5 项；未弱化或跳过。隔离 worktree 在修复前 head `4d1e78d58e9767974144c8d442fce8fbe012cdd7` 复跑这两个类：55 tests / 6 failed，失败名称集合与本轮全量完全一致。隔离构建缺少已忽略的 launcher PNG 与 bridge raw，复制主工作区既有真实构建资源后复跑，未改基线 Kotlin 源码。基线日志 `logs/pr4-baseline.txt`。

日志和 XML 留在本机，不提交个人设备数据。XML 路径：`src/closepaw/app/build/test-results/testDebugUnitTest/`。仍有 Android isChecked 弃用、JVM instrumentation 和 Gradle configuration cache 警告，不称“无警告通过”。没有 Actions 独立验收结论。

## 协作与剩余事项

写入前以 O_CREAT|O_EXCL 取得 `.git/semantic-gate-writer.lock`，整个修复/测试/提交/评论周期只保留一个写者；cron 已改为同锁契约，锁存在退出、不抢锁，结束释放自己的锁。未覆盖他人未提交修改，未修改 AGENTS（写入审批超时，保留原文件）。

没有 assembleDebug、安装 APK 或操作手机；没有 push main、force push、合并 PR。JVM 测试相关 Gradle 资源任务不等于产出可安装 APK。
阶段 1 设备回归仍需另行授权；阶段 2 ToolSpec 和阶段 3 业务状态验证均未在本次冒称完成。已有 DELETE 闹钟实现只是关闭并说明，不是真正删除，仍是已知能力限制。

## 第㉗轮：计时器创建验证路径改用精确 tab 导航（2026-11-01，离线源码 + JVM，未实机）

对应复审 `6546660320` P2 第 3 条——上一轮在文末明确列为「未做」的那一条。

### 问题

`IntentActions.setTimer` 发出 `ACTION_SET_TIMER` 后，用 `UiAutomator.clickText("定时器")` 切到定时器页。`clickText` 是**包含匹配**（`findNode`），而 `click` 会向上找可点祖先（最多 4 跳），最后退化成坐标 `tap`。这条路径绕开了 `ClockControl.clickClockTab` 已有的全部约束（精确标签、时钟窗口归属、必须是导航栏内控件、禁止坐标兜底），因此可能点到「定时器设置」这类内容行或提示卡，而读回的文本仍来自时钟包 → 把无关页面当成这次创建的证据。

### 改动

- `tools/UiAutomator.kt`：新增 `CLOCK_PKG` 与 `clickClockNavTab(tab)`：前台 root 必须是时钟包 → `navControl` 找到**精确标签**所在的导航栏控件 → 控件与容器都属时钟包 → 只走 `clickTabControl`（ACTION_CLICK，绝不 `click`/`tap`）。返回 `null` = 无已证实控件，`false` = 控件存在但拒绝点击。
- `semantics/ClockControl.kt`：`clickClockTab` 改为委托 `UiAutomator.clickClockNavTab`（行为不变，外层 `runCatching` 把 UI 层异常收敛为「无已证实控件」，fail closed），删除重复实现，保证只有一份 tab 点击语义。
- `tools/IntentActions.kt`：`setTimer` 的切 tab 改走 `clickClockNavTab`，并把到达结果记为 `tab=clicked|refused|no-verified-tab-control|not-attempted`；只有 `tab=clicked` 时，屏幕上的「定时器正在运行 / 剩余 / 倒计时 / N 分钟 N 秒」才被当作证据。未到达时屏幕文本一律不作证据，detail 写明 `timer tab not reached`。
- 生产代码中 `UiAutomator.clickText` 现已**无任何调用方**（只剩旧测试引用它）；本轮不删，留待与那些夹具一起清理，避免顺手改动无关测试。

### TDD 与实跑数字（`--offline --rerun-tasks`）

| 阶段 | 结果 |
|---|---|
| 新 `TimerTabNavigationSafetyTest`(4) 在旧实现上 | **RED 4 / 4 failed**：两条是 `clickText(定时器)`「应当 0 次却被调用」的 MockK 断言失败（栈显示 `IntentActions.setTimer:74`），一条缺 `tab=` 诊断，一条缺 `countdown-ui=false`；**均为断言失败，非编译错误** |
| 修后同组 | **4 / 4 通过** |
| 定向 `voice.tools.*` + `voice.semantics.*` | **206 tests / 0 failed / 0 errors / 0 skipped**，exit=0 |
| 全量 `:app:testDebugUnitTest` | **2296 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿** |

全量 6 条失败仍是既知两类（`AgentSkillCatalogTest > entry filePath points to SKILL md` 1、`MemoryStoreTest` 5），本轮未改、未跳过；基线为 2292 / 6 / 8，多出的 4 条即本轮测试。日志 `logs/pr4-r26-*.txt` 只留本机。

**夹具说明（不算弱化断言）**：旧 `IntentCreationVerificationTest` 原先 stub `clickText("定时器")=true`，改为 stub `clickClockNavTab("定时器")=true`——补的是新路径的证据，断言一条未删。新测试里 `["定时器", "0 个定时器正在运行"]` 曾被自己的证据规则判为「有倒计时」，按真实语义换成空态页 `添加定时器`。

### 边界（如实）

- 全部为 JVM 合成无障碍树。三星时钟真实 tab 条是否满足 `navControl` 的「≥2 个同深度标签 + 容器 ≤40 节点 + 不在滚动容器内」**未验证**。不满足时结果是 `tab=no-verified-tab-control`，退化为「已发送但无法确认」——不误点、不假成功，但切页可能失效。
- 未构建、未安装、未实测；设备矩阵仍 4 通过 / 0 失败 / 3 未测，实机需用户**重新授权**。
- 未完成：业务状态回读（创建/取消后的对象存在性与计数，需实机）、在线 `ToolRouter` 同门禁（阶段 2）、正文双标签卡片身份（需实机采集脱敏 UI 树）、`closeAlarm` 仍为安全拒绝。

## 第㉖轮：`expectedState` 参与判定、`OPEN` 纳入闸门（2026-11-01，离线源码 + JVM）

对应复审 `6546660320`（P1 + P2 第 2 条）。上一轮 PR 评论误判「最新已审 SHA = `2212ed6`」，漏掉这条，本轮补做并如实更正。

### 改动

- `tools/ToolResult.kt`：新增 `ObservedState(identity, state, source)`；`ToolResult` 增加 `observed: ObservedState? = null`（尾部默认参数，既有构造点不变），`ToolResult.ok(...)` 增加同名可选参数。
- `semantics/TerminalState.kt`：
  - `verdict` 先判 `promisesState`（`action.mutates || action == OPEN`），再判 `declaresState`（`expectedState` 非空）。未声明终态的任务保留旧的三态分级，不假装做了对象级校验。
  - 声明了终态时：`observed == null` → `UNPROVEN`；身份不符/缺一 → `UNPROVEN`；同一对象状态 token 不符 → `CONTRADICTED`；谓词未覆盖的 `expectedState` → `UNPROVEN`。只有「身份吻合 + 状态吻合 + `verified=true`」才是 `CONFIRMED`。
  - `enforce` 新增 `UNPROVEN` 分支：仅当 `action == OPEN && verified == true` 时改写句子（「点了X，但没能确认页面已经打开，请你看一眼」）、`verified = null`、`detail += "; postcondition=unproven(no page identity)"`。`CONTRADICTED` 分支追加 `postcondition=contradicted` 并保留原 `observed`。幂等。
- `semantics/AppNavigator.kt`：`openFeatureResolved` 的「同包内新增 ≥2 条文字」不再返回 `verified = true` / 「已经进入」，改为 `verified = null` + 明确的「没能确认已经进入」。屏幕文字变化不是页面身份。

### 测试

- `TerminalExpectedStateTest`(14)：先写、只加类型不加逻辑时 **RED 14 / 9 failed**（Truth 断言失败）；实现后 GREEN。
- 定向 `voice.semantics.*` + `voice.tools.*`：**202 / 0 failed / 0 errors / 0 skipped**，exit=0（`--offline --rerun-tasks`）。
- 全量 `:app:testDebugUnitTest`：**2292 / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**（`AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过）。
- 日志 `logs/pr4-review26-{red,targeted,full}.txt` 只留本机，不提交。

### 边界与未完成

- **尚无生产者填 `observed`**：真机上声明了 `expectedState` 的任务一律 `UNPROVEN`，`CONFIRMED` 目前只有 JVM 夹具可达。这是刻意保守（旧路径本身是误报源），但意味着「已确认完成」的能力暂时为零，必须如实如此表述。
- 未实机、未构建安装 APK、未操作手机；历史设备矩阵仍 4 通过 / 0 失败 / 3 未测。
- 未做：复审 P2 第 3 条 `IntentActions.setTimer` 的模糊 `clickText("定时器")`（需把 `ClockControl` 的安全 tab 导航暴露给 `tools` 层）；`closeAlarm` 仍因缺全列表/业务对象身份安全拒绝；在线 `ToolRouter` 同门禁（阶段 2）未开始。


## 第㉘轮：UNPROVEN 对所有动作生效 + 计时器诊断的同读页面证据与计数解析（2026-11-01，离线源码 + JVM）

对应复审 `6547564219`（HEAD `cdf52b1`）：1 条 P1 + 2 条 P2。

### 改动

- `semantics/TerminalState.kt`：`enforce` 的 UNPROVEN 分支从「仅 `Action.OPEN`」放宽到「任何 `promisesState` 的动作」，条件改为 `result.verified == true`。非 OPEN 动作改写为「请求已经发出，但没能确认结果，请你看一眼」、`verified = null`、`detail += "; postcondition=unproven(no object evidence)"`；OPEN 保留原有页面专用措辞。仍幂等（第二遍 `verified` 已为 null，不再改写）。
- `tools/IntentActions.kt` `setTimer`：
  - 读屏从 `screenTexts(80)` 改为 `readScreen(80)`，并新增 `pageOk = tab == "clicked" && read.rootPackage == UiAutomator.CLOCK_PKG && read.selectedTabs["定时器"] == true`。未满足时 `countdown-ui=unknown`、`page=unconfirmed`，句子仍是「无法确认是否真的开始」。
  - 新增 `countdownWording(text)`：`(\d+)\s*个\s*定时器正在运行` 命中时要求 `N > 0`；无数字的「定时器正在运行」仍算运行证据；「剩余 / 倒计时 / N 分钟 M 秒」同前。显式「0 个定时器正在运行」现在给出 `countdown-ui=false`。
  - 日志只写 `tab/page/countdown/textCount`，不写屏文本。

### 测试（`--offline --rerun-tasks`）

| 阶段 | 结果 |
|---|---|
| 新 `TerminalUnprovenMutationTest`(7) + `TimerDiagnosticsEvidenceTest`(6)，在 `cdf52b1` 上、仅加测试 | **RED 13 tests / 6 failed**（Truth 断言失败：`TerminalUnprovenMutationTest.kt:57/72/88`、`TimerDiagnosticsEvidenceTest.kt:87/102/112`；另 7 条为控制组/回归组，本来就应通过） |
| 实现 `TerminalState.enforce` + `IntentActions.setTimer` 后同组 | **13 / 13 通过** |
| 定向 `voice.semantics.*` + `voice.tools.*` | **219 tests / 0 failed / 0 errors / 0 skipped**，exit=0 |
| 全量 `:app:testDebugUnitTest` | **2309 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿** |

首次实现后 1 条失败：新句「没能确认已经完成」里的「已经完成」撞上测试的 `doesNotContain("完成")`。未放宽断言，改生产句子为「没能确认结果」（语义更准，也不含「完成」）。

旧夹具 `IntentCreationVerificationTest` / `TimerTabNavigationSafetyTest` 的 `screenTexts` 桩改为 `readScreen` 桩并补 `rootPackage`/`selectedTabs` 页面身份（生产路径已改，断言未删未弱化；`TimerTabNavigationSafetyTest` 的「countdown-ui=false」现在只在页面身份成立时可达）。

### 边界（如实）

- **P1 的后果**：真机上所有声明 `expectedState` 的变更现在都会被改成「没能确认结果」，因为仍没有生产者填 `observed`。误报消失，但「已确认完成」的播报能力仍为零——不是新回归，是上一轮已存在的状态被真正执行到输出层。
- 页面身份依赖 `readTree` 的 `selectedTabs`，其对三星真机的可靠性**未验证**：不暴露 `selected` 时结果为 `unknown`（更保守），不会假报 `true`。
- 未构建、未安装、未实测；设备矩阵维持历史 4 通过 / 0 失败 / 3 未测，实机需用户**重新授权**。
- 未做：`observed` 的真实生产者（取消/关闭/创建的对象身份回读）、在线 `ToolRouter` 同门禁（阶段 2）、正文双标签卡片身份、`closeAlarm` 的全列表枚举。

## 第㉝轮：把「说法 → 工具入参」这一跳真正测出来（离线源码 + JVM，未实机）

需要复审；审查未关闭。本轮无新审查（最新 review `6549082221` 已由第㉜轮回复，其后无新评论），按「无新审查则推进可离线项」处理遗留里的 **「五分钟真实工具参数」**。

**为什么这一项不该只等实机**：设备验收表把它记为「未测（解析子项通过）」，指的是没有在真机上核对过工具真的收到 300 秒。但「解析器算出多少」与「工具实际收到多少」之间隔着一层被测代码，可以先用 JVM 固定下来。补这层断言时发现了真实缺陷，而不是发现测试缺口。

**缺陷**：`ChineseTime.durationSeconds` 的小时段把 `total` 设成整秒，分钟段却把**分钟数**加进同一个累加器后再把**整个累加器**乘 60。凡「小时 + 分钟」的说法都被放大 60 倍：

| 说法 | 应有 | 修复前 |
|---|---|---|
| `一小时十分钟` | 4200 s | **216600 s** |
| `1小时30分` | 5400 s | **217800 s** |

小时单说、分钟单说、秒单说都正确，所以既有 2400+ 条测试没有一条命中。**闸门也没拦住**：`IntentGate` 拿 utterance 的 `durationSeconds` 和工具参数比，两侧来自同一个函数，一起错就一起通过。参数正确性此前没有任何独立证据。

**改动**：分钟段改为 `total += 分钟数 * 60`（不再缩放累加器），并在代码里写明原因。`IntentPlanner` / `SemanticParser` / `IntentGate` 共用该函数，含小时的时长现在按和计算。

**测试（先写，后改）**：

- `ChineseTimeDurationTest`(4)：控制组 分钟/秒/半小时/一小时；契约 `一小时十分钟=4200`、`设置一小时十分钟的倒计时=4200`、`1小时30分=5400`、`两小时十五分钟=8100`、`一小时45分30秒=6330`；`六十分钟=3600`（防重复计分钟）；无时长 → null。
- `TimerCreateArgumentTest`(3)：真实 parser → `CapabilityGuard` → `IntentGate` → `SemanticRunner`，只 mock `ClockControl`，断言**入参**：`开始五分钟倒计时` → `createTimer(ctx, 300)` 恰一次、且不是 5/3000；`设置一小时十分钟的倒计时` → `createTimer(ctx, 4200)`、不是 216600；`设一个倒计时` → 追问「多久」、零调用。

| 阶段 | 结果 |
|---|---|
| 仅加测试 | **RED 7 / 2 failed / 0 errors**：`expected 4200 but was 216600`；MockK 记录 `createTimer` 实收 216600。另 5 条为控制组，本来就应通过（`logs/pr4-round33-red.txt`） |
| 修 `ChineseTime` 后同组 | **GREEN 7 / 7**，exit=0（`logs/pr4-round33-green1.txt`） |
| 定向 `ai.closepaw.voice.*` | **255 / 0 failed / 0 errors / 0 skipped**，exit=0（`logs/pr4-round33-targeted.txt`） |
| 全量 | **2345 / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**，失败仍是 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5（`logs/pr4-round33-full.txt`、`-full2.txt`） |

**边界**：这只证明说法到入参正确，不证明三星时钟接受 4200 s，也不证明创建后能核对；不是一个设备验收项被完成。`一个半小时` 目前算成 1800 s（应为 5400 s）是**另一个**语义问题，本轮未改、也未把它写进测试当正确值——需要先定「一个半/两个半」的规则。实机仍须用户重新授权。


## 第㉞轮：手电/Wi-Fi 的真实回读没有交给终态闸门（源码 + JVM，未实机）

无新审查（最新 review `6549082221` 已处理），推进遗留清单的「手电/Wi-Fi 缺对象级回读」。

**问题**：`TorchControl.set` 等 `onTorchModeChanged`、`WifiControl.enable/disable` 读 `isWifiEnabled`，都据此置 `verified=true`，但都不填 `observed`。`TerminalState` 对声明 `expectedState` 的任务要求结构化证据，于是有真实证据的成功被降级播报成「请求已经发出，但没能确认结果」。

**改动**：`TorchControl.outcome(on, cameraId, observed, unavailable)` 抽成纯判定并在成功分支带 `ObservedState("torch","on"/"off")`；`WifiControl.stateResult(...)` 供四条成功返回共用，带 `ObservedState("wifi","on"/"off")`。identity 与 `SemanticParser.identityOf` 的 `target.name.lowercase()` 一致，测试内直接解析原话验证。

**测试** `SwitchReadbackEvidenceTest`(10)：Wi-Fi 三条成功分支真实 MockK（Context+WifiManager）；手电四条纯判定（成功 on/off、无回调 TIMEOUT、相机占用 DEVICE_UNAVAILABLE、回读反状态 EXECUTION_ERROR）；端到端 `SemanticParser.parse → TerminalState.verdict == CONFIRMED`；控制组（去掉证据的同一 SUCCESS 仍 UNPROVEN）。

| 阶段 | 结果 |
|---|---|
| 测试先写 + 仅抽函数（行为不变） | **RED 10 / 6 failed / 0 errors**，Truth 断言失败（`logs/pr4-round34-red.txt`） |
| 实现后同组 | **GREEN 10 / 10**，exit=0（`logs/pr4-round34-green1.txt`） |
| 定向 `ai.closepaw.voice.*` | **265 / 0 failed / 0 errors / 0 skipped**，exit=0（`logs/pr4-round34-targeted.txt`） |
| 全量 | **2355 / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**，失败仍是 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5（`logs/pr4-round34-full.txt`） |

**边界**：手电的 CameraManager 回调/等待仍只有真机证据（JVM 无 Looper，本仓无 Robolectric）；Wi-Fi 的 `SYSTEM_RESTRICTED` 分支因 `Intent(...)` 在 mockable android.jar 返回 null 而无法在 JVM 跑（本轮未改该分支）。设备矩阵不变（4 通过 / 0 失败 / 3 未测），实机需用户重新授权；未构建/安装 APK、未操作手机、未 push main、未合并。

## 第㉟轮：手电回读的归属、时长短语的整段解析（源码 + JVM，未实机）

来源：PR #4 复审 `6550162379`（针对 `1a77501`）两条 P1。第㉞轮把手电回调状态接进 `TerminalState`，方向对，但证据没有归属。

### P1-1 手电：请求前的回调被当成本次结果

`set()` 里 `onTorchModeChanged` 无条件 `observed.set(enabled)`；`latch.await` 得到的 `confirmed` **没有传给** `outcome`；`outcome` 只判断 `observed == on`。可复现：初始回调 `on=true`，请求后 `onTorchModeUnavailable`（或请求后没有匹配回调）→ 仍然「手电筒已打开」+ `verified=true` + `ObservedState`，`TerminalState` 进一步判 `CONFIRMED`。

改法（归属契约写成可测代码）：

- `RequestTracker(cameraId, on)`，字段 `baseline / postState / unavailable / requested / confirmed`；
- `markRequested()` 之前到达的事件只写 `baseline`，一律不确认；`onTorchModeUnavailable` 也只在请求后生效；
- `set()` 先有界等 baseline（`BASELINE_TIMEOUT_MS = 1200`）再 `markRequested()` + `setTorchMode`，`outcome(..., confirmed = latch && tracker.confirmed, baseline = tracker.baseline)`；
- `outcome`：`unavailable` 优先（成功之后才 unavailable 同样不报成功）→ 成功仅当 `confirmed && observed == on` → 反状态 `EXECUTION_ERROR` → 其余 `TIMEOUT` + `verified=null` + 无 `observed`。

残留（写进代码注释）：baseline 窗口内若一个事件都没到，请求照发，此时结果按「未确认」处理——fail closed，不假设。

### P1-2 时长：三个口语被读成别的时长

| 说法 | 应有 | 修复前 |
|---|---|---|
| `半分钟` | 30 s | 1800 s |
| `一个半小时` | 5400 s | 1800 s |
| `1.5小时` | 5400 s | 18000 s |

`IntentGate` 用 `ChineseTime.durationSeconds` 复核工具参数，两侧同错 → 闸门放行；值最终进 `AlarmClock.EXTRA_LENGTH`。改为按整段短语消费（复合形优先、小数支持、消费后从待匹配串移除、不可读数词整句 `null`）。

### 测试

| 阶段 | 结果 |
|---|---|
| 测试先写 + 只加 `confirmed/baseline` 参数与 `RequestTracker` 骨架 | **RED 31 / 12 failed / 0 errors**（断言失败，非编译错误） |
| 实现后同组 | **34 / 34 GREEN，exit=0** |
| 定向 `ai.closepaw.voice.*` | **282 / 0 failed / 0 errors / 0 skipped**，exit=0 |
| 全量 | **2372 / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**（`AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5） |

**未实机**：手电回调时序、baseline 窗口长度、三星时钟是否接受这些秒数都未验证；设备矩阵维持 4 通过 / 0 失败 / 3 未测，实机需用户重新授权。`closeAlarm` 仍 BLOCKED（安全拒绝），计时器对象级终态仍 UNPROVEN，在线 `ToolRouter` 同门禁（阶段 2）未开始。


## 第㊴轮：复审 `6551563018`（HEAD `19f4a9c`）——跨单位二选一、可点击窗口根、功能候选歧义

**证据类型：源码 + JVM。未实机。**

### 1. [P1] `ChineseTime.durationSeconds`：跨单位「或者」被加成一个数

`take()` 每个单位各消费一条短语，`RESIDUAL_DURATION` 只检查**剩余**文本；两段都被消费后残留为空，和值又与合法的复合时长看不出区别：

| utterance | 旧结果 | 真相 |
|---|---|---|
| 设置一小时或者十分钟的倒计时 | 4200 s | 用户没选，应追问 |
| 设置五分钟或者半分钟的倒计时 | 330 s | 同上 |
| 设置一小时十分钟的倒计时 | 4200 s | 合法复合时长，必须保留 |

改法：消费前 `hasChoiceBetweenDurations(text)` 找出全部时长短语的区间，**只在相邻两段之间的间隙**里匹配 `或者|或是|还是|或|二选一`；命中即整句 `null`。复合时长间隙为空，行为不变。

### 2. [P1/P2] `UiAutomator.clickableWithin`：先判可点、后判窗口根

标签与中间父节点都不可点、而活动窗口根 `isClickable=true` 时，旧循环返回**窗口根**并对其 `ACTION_CLICK`。现在 `sameNode(node, root)` 在 `isClickable` **之前**判断，根节点永不作为点击目标。

### 3. [P2] `featureControl`：首个包含匹配，无歧义约束

改为枚举候选（`collectFeatureCandidates`）：精确标签（text/contentDescription 去空格全等）优先于包含匹配；可点目标按 `sameNode` 去重后恰好一个才返回，多个 → `ambiguous(n)`，0 个 → `label-not-clickable`，扫描被节点预算/候选上限截断 → `candidates-truncated`（部分扫描不当全集）。`AppNavigator` 为后两者各加独立措辞，均 `verified=false`，不点击。

### 测试

| 阶段 | 结果 |
|---|---|
| 新 18 条测试先写、生产未改 | **RED 18 / 13 failed / 0 errors**（Truth 断言：4200、330、根节点、「扫一扫说明」卡片）；RED 日志未留存，如实标注 |
| 实现后同组 | **GREEN 18 / 18**，exit=0 |
| 定向 `ai.closepaw.voice.*` | **333 / 0 failed / 0 errors / 0 skipped**，exit=0 |
| 全量 | **2423 / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**（`AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5） |

编译期一次失败（`$feature的地方` 被当成标识符）已修，不计 RED。

**未实机**：三星上同名可点控件的实际出现率、功能标签是精确还是带后缀、窗口根是否常可点，全部未验证。`featureControl` 对大页面更保守（600 节点内数不完即拒绝），代价只能在真机上评估。设备矩阵维持 4 通过 / 0 失败 / 3 未测，本 SHA 未构建/未安装/未实测，实机需用户重新授权。`closeAlarm` 仍 BLOCKED，计时器对象级终态仍 UNPROVEN，在线 `ToolRouter` 同门禁（阶段 2）未开始。

## 第㊵轮：复审 `6551941593`（HEAD `8252913`）——功能身份、同义词、点击边界重解析

**证据类型：源码 + JVM。未实机。**

### 1. [P1/P2] 精确标签仍可把说明/帮助卡片当成真实功能入口

`featureControl` 第㊴轮的分层是 `if (scan.exact.isNotEmpty()) scan.exact else scan.loose`——**精确层非空就整层丢弃包含层**。反向组合因此仍会误点：帮助卡片子节点文字恰好「扫一扫」（exact），真入口 `contentDescription=「扫一扫，打开相机」`（loose）；exact 层唯一，`clickableWithin` 返回帮助卡片，`ACTION_CLICK` 落在卡片上。RED 实跑确证：`FeatureControl(node=AccessibilityNodeInfo(#180), label=扫一扫)`。

改法：两层**都**是候选；对每个可点目标（`sameNode` 去重）取**目标自身**标签（`ownLabel`：自身 text 或 contentDescription，去空格小写）：

- `direct` = 自身标签精确等于任一目标标签名的目标；
- `direct.size == 1` → 用它；
- 否则 `targets.size == 1` → 用它；
- 否则 `ambiguous(n)`，不点击。

这样「扫一扫」精确按钮旁边有「扫一扫设置」卡片时仍点按钮（卡片自身标签是「扫一扫设置」，不 direct，但 direct 恰好 1 个），而「卡片内含精确文字 + 真入口描述更长」两个目标都不 direct → 拒绝。**没有**引入按 className/resource-id 识别业务入口的规则（缺真机树证据）。

### 2. [P2] 「扫码」与「扫一扫」未归一化

`appFeatureWords` 同时列「扫一扫」「扫码」，但命中词原样进 `params["feature"]`，界面匹配又是字面比较。RED 实跑：说「打开微信扫码」、树里只有「扫一扫」时，真的回答 `打开了微信，但界面上没有找到扫码`。

改法：`AppFeatureAliases`（`semantics/AppFeatureAliases.kt`）给出「口语词 → 功能身份 → 允许的界面标签集」：`扫一扫/扫码/扫描/扫二维码` → 「扫一扫」，`付款码/收付款/收钱码` → 「付款码」，其余词身份即自身。`SemanticParser` 写规范化 `feature`；`UiAutomator.featureControl` 新增 `List<String>` 重载，任一标签精确/包含命中即可，歧义与截断规则不变；`AppNavigator` 用 `AppFeatureAliases.labelsFor(feature)`。

### 3. [P2 · 静态竞态] 扫描与点击之间没有窗口再校验

旧代码点的是早先读取里拿到的 `AccessibilityNodeInfo`，中间没有任何再读；弹出对话框或页面跳转时，是否会对过时控件执行动作没有证据。改法：不点旧节点——`UiAutomator.featureControlOnce`（单次、不重试）在**动作边界**重读活动窗口并重新解析，点击落在**重解析后的新节点**；解析失败（窗口换了 App、标签消失、候选变多/被截断）→ 拒绝点击，话术「点之前…的界面又变了，没有点X」，`verified=false`，detail 记 `beyond-scan=<why>`。

### 测试

| 阶段 | 结果 |
|---|---|
| 新 `AppFeatureIdentityTest`(10)，生产未改（只放直通骨架 `AppFeatureAliases` 以便编译） | **RED 10 / 6 failed / 0 errors**，全部 Truth 断言：实得帮助卡片、实得 feature=`扫码`、实说「没有找到扫码」、三例窗口已换仍点击。失败值见 `logs/pr4-review40-red.txt` |
| 实现后 | **GREEN 10 / 10**，exit=0 |
| 定向 `ai.closepaw.voice.*` | **343 / 0 failed / 0 errors / 0 skipped**，exit=0（第㊴轮 333，+10） |
| 全量 | **2433 / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**（`AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，既知、未跳过、未改） |

既有测试为适配新契约做了桩调整：`featureControl` 变成标签集重载后，`any()` 需显式 `any<List<String>>()`（4 个文件）；`AppFeatureWindowIdentityTest`/`AppFeatureReadCompletenessTest`/`SemanticExecutionTest` 补 `featureControlOnce` 桩，真实重解析由 `AppFeatureIdentityTest` 覆盖。

**未实机**：真机上帮助卡片与真入口的节点角色、className、resource-id，以及三星设备上「扫码」的实际映射都未验证；本轮刻意不按控件角色猜业务入口，规则是「证明不了就拒绝」。代价：同页面两个控件对得上同一标签（含同义词）即拒绝打开；点击前多一次全树解析，大树页面更容易 `candidates-truncated`。设备矩阵维持 4 通过 / 0 失败 / 3 未测，本 SHA 未构建/未安装/未实测，实机需用户重新授权。`closeAlarm` 仍 BLOCKED，计时器对象级终态仍 UNPROVEN，在线 `ToolRouter` 同门禁（阶段 2）未开始。
