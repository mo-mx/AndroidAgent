# PROJECT_STATUS — 当前真实状态

## 第㊶轮：取消倒计时的「唯一绑定」只在视口内成立（源码 + JVM，未实机）

本轮 PR #4 **无新审查**（最新复审仍是 `6551941593`，已由评论 `6552207011` 回复；其后 PR 无新评论）。按「无新审查则推进可离线项」取任务清单里的**计时器唯一绑定**（剩余的一半）。

**缺陷（源码分析 + JVM 实证）**：`ClockControl.cancelTimer` 的候选扫描用 `complete`（只在 800 节点预算耗尽时置 false）当作「候选全集」。`isScrollable` **从未被读过**——而同一文件里的 `closeAlarm` 早就把可滚动列表当作「不是全列表」直接 `BLOCKED`。计时器列表可滚动时，视口下方可能还有一张相同时长的卡片：手机真有两张 5 分钟倒计时，本轮之前的代码只看到一张，`matching.size == 1` 通过，于是点掉视口里的那张并播「已请求取消指定倒计时」。这就是审查反复点出的「部分扫描当成全集」。

**改法（不用「一律拒绝」换安全）**：区分两种计数——`N 个定时器正在运行` 是页面**自己声明**的总数（`timerCountFrom` 已有此契约），视口卡片数只是下界。所以：

```
statedTotal = timerCountFrom(boundTexts, truncated = !complete)
viewportComplete    = complete && !scrollable
coveredByPageTotal  = statedTotal >= 0 && candidates.size >= statedTotal
if (!viewportComplete && !coveredByPageTotal) → BLOCKED，零点击
```

即可滚动时**只要完整绑定到的卡片数 ≥ 页面声明的总数**，仍可证明「这个时长只有一张」，功能不被一刀切掉；没有声明总数或绑定数不足则拒绝，`speak` 按事实分两句（可滚动 / 读取被截断），`detail` 记 `complete/scrollable/stated`。

TDD 与实跑数据（`--offline --rerun-tasks`，JDK 17）：

| 阶段 | 结果 | 日志 |
|---|---|---|
| 新 `TimerCancelViewportCompletenessTest`(5)，生产未改 | **RED 5 tests / 2 failed / 0 errors**（Truth 断言：`expected: BLOCKED but was : SUCCESS`——旧代码在「可滚动 + 页面声明 2 个 + 视口 1 张」和「可滚动 + 无总数」两种情形下都真的点了取消）；3 条控制组本就通过 | 控制台输出；未单独留 RED 日志文件 |
| 实现后同组 | **GREEN 5 / 5**，exit=0 | 本机临时日志 |
| 定向 `ai.closepaw.voice.*` | **348 tests / 0 failed / 0 errors / 0 skipped**，exit=0（第㊵轮 343，+5） | 本机临时日志 |
| 全量 `:app:testDebugUnitTest` | **2438 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**；失败仍是既知两类 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改、未跳过，本轮未重跑旧 SHA 基线 | 本机临时日志 |

边界（必须看清）：
- 全部为**源码 + JVM 合成树**：三星时钟的定时器列表页是否真的上报 `isScrollable=true`、页头那句「N 个定时器正在运行」在真机上的实际文案与是否只统计运行中的卡片，**均未验证**。若真机既不上报 `isScrollable` 又不给总数，本改动不生效（等于旧行为）；若上报了 `isScrollable` 但从不给总数，`cancelTimer` 会**全部拒绝**——这是真实的可用性代价，必须靠真机数据确认。
- 「卡片数 ≥ 声明总数」只证明**数量**覆盖，不证明绑定到的就是用户说的那一个；对象级身份仍未解决，回读结论仍是 `UNPROVEN`。
- 本轮的 5 条测试夹具是我自己构造的树，不是真机脱敏树。
- 设备矩阵维持历史 4 通过 / 0 失败 / 3 未测；本 SHA **未构建、未安装、未实测**，实机需用户**重新授权**。
- `closeAlarm` 仍 BLOCKED（安全拒绝）；计时器创建/取消对象级终态仍 UNPROVEN；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。

## 第㊵轮：功能身份（精确标签≠业务身份）、扫码/扫一扫同义词、点击边界重解析（源码 + JVM，未实机）

本轮处理新复审 `6551941593`（针对 `8252913`）的 1 条 P1/P2 + 2 条 P2，三条全部已修，且三条都先用**真实返回值**证实在旧代码上成立，不是推断。

1. **P1/P2 精确标签会把说明卡当真入口**：`val tier = if (scan.exact.isNotEmpty()) scan.exact else scan.loose` 一旦发现精确标签就**整层丢弃**包含匹配。实测（RED）：帮助卡片子节点文字「扫一扫」+ 真按钮 `contentDescription=「扫一扫，打开相机」`，旧代码返回 `FeatureControl(node=…, label=扫一扫)`——即返回**帮助卡片**作点击目标。改法：两层都保留为候选，按「点击目标**自身**标签是否精确等于目标名」判 direct 层；direct 恰好一个才用，否则目标只有一个才用，其余一律 `ambiguous(n)` 失败关闭。不引入角色/resource-id 猜测（缺真机树证据，见边界）。
2. **P2 「扫码」与「扫一扫」未归一化**：实测旧行为说「打开微信扫码」、界面只有「扫一扫」时，回答 **`打开了微信，但界面上没有找到扫码`**。改法：新增 `AppFeatureAliases`（口语词→功能身份 + 允许的界面标签集，如 扫一扫/扫码/扫描/扫二维码 归一到「扫一扫」，付款码/收付款/收钱码 归一），`SemanticParser` 写入规范化 `feature`，`UiAutomator.featureControl` 增加标签集重载，任一标签命中即可，多候选歧义规则不变。
3. **P2 扫描与点击之间无窗口再校验**：旧代码点的是**早先那次读取**里的节点对象，改法是不再点旧节点——`featureControlOnce` 在动作边界重读活动窗口并重新解析，点的是**重解析后的新节点**；重解析失败（窗口换了 App / 标签消失 / 候选变多）则拒绝点击并说明「界面又变了」，`verified=false`。

TDD 与实跑数据（`--offline --rerun-tasks`，JDK 17）：

| 阶段 | 结果 | 日志 |
|---|---|---|
| 新 `AppFeatureIdentityTest`(10)，生产未改（仅先放 `AppFeatureAliases` 直通骨架以便编译） | **RED 10 tests / 6 failed / 0 errors**，全部 Truth 断言：实得 `FeatureControl(label=扫一扫)`（帮助卡）、实得 feature=`扫码`、实说「没有找到扫码」、三例在窗口已换后仍执行了点击 | `logs/pr4-review40-red.txt`（从测试 XML 抽的真实失败值） |
| 实现后同组 | **GREEN 10 / 10**，exit=0（同一次定向运行内，未单独留 green1 日志） | `logs/pr4-review40-targeted.txt` |
| 定向 `ai.closepaw.voice.*` | **343 tests / 0 failed / 0 errors / 0 skipped**，exit=0（第㊴轮 333，+10） | `logs/pr4-review40-targeted.txt` |
| 全量 `:app:testDebugUnitTest` | **2433 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**；失败仍是既知两类 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改、未跳过 | `logs/pr4-review40-full.txt` |

为适配新契约改了既有测试的桩：`featureControl` 变为标签集重载后，4 个测试文件里的 `any()` 需显式 `any<List<String>>()`；`AppFeatureWindowIdentityTest` / `AppFeatureReadCompletenessTest` / `SemanticExecutionTest` 补上 `featureControlOnce` 桩（真实重解析行为由 `AppFeatureIdentityTest` 覆盖）。

边界（必须看清）：
- 全部为**源码 + JVM 合成树**：真机上帮助卡片/真入口的节点角色、`className`、resource-id、无障碍树结构，以及「扫码」这类口语词在三星设备上的实际映射，**均未验证**。本轮**没有**引入按控件角色识别业务入口的规则，因为那需要真机脱敏只读树做依据；现在的规则是「证明不了就拒绝」，代价是保守。
- **可用性代价（真实）**：同页面只要有两个不同控件能对上一个标签（含同义词），就拒绝打开。微信这类页面是否频繁撞上，只能真机看。
- 点击前多一次全树解析（约一次 BFS），页面很大时会增加延迟并更容易触发 `candidates-truncated` 拒绝。
- 设备矩阵维持历史 4 通过 / 0 失败 / 3 未测；本 SHA **未构建、未安装、未实测**，实机需用户**重新授权**。
- `closeAlarm` 仍 BLOCKED（安全拒绝）；计时器创建/取消对象级终态仍 UNPROVEN；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。

## 第㊴轮：跨单位「或者」时长 + 可点击窗口根 + 功能候选歧义（源码 + JVM，未实机）

本轮处理新复审 `6551563018`（针对 `19f4a9c`）的 1 条 P1 + 1 条 P1/P2 + 1 条 P2，三条全部已修。

1. **P1 跨单位二选一仍被加成一个数**：`take()` 按单位各自消费一条短语，`RESIDUAL_DURATION` 只看**剩余**文本，所以「一小时或者十分钟」（4200 s）、「五分钟或者半分钟」（330 s）两段都被吃掉、残留为空、和值又像一个合法的复合时长（`一小时十分钟` 确实是 4200 s）。改法：消费前用 `hasChoiceBetweenDurations(text)` 找出全部时长短语的位置，**只在两个短语之间的间隙**里找 `或者/或是/还是/或/二选一`，命中即整句 `null`（追问，不猜）。复合时长间隙为空，不受影响。
2. **P1/P2 `clickableWithin` 先判可点、后判窗口根**：标签与中间父节点都不可点、而活动窗口根可点时，旧循环把**整个窗口根**当点击目标返回，`openFeatureResolved` 随后对它 `ACTION_CLICK`——既违反「不越过窗口根」的注释契约，也不能证明点到的是用户说的功能。改法：`sameNode(node, root)` 的判断移到 `isClickable` 之前，根节点永不作点击目标。
3. **P2 `featureControl` 只取 BFS 首个包含匹配**：可能在「扫一扫说明」卡片上点击，也可能因首个命中是不可点标题而直接报 `label-not-clickable`，后面的真按钮根本没被看过。改法：`collectFeatureCandidates` 枚举同包可见候选并区分**精确标签**（text/contentDescription 去空格后全等）与**包含匹配**；有精确标签时只看精确层；可点目标按 `sameNode` 去重后**恰好一个**才点，>1 返回 `ambiguous(n)` 失败关闭，0 个返回 `label-not-clickable`；节点预算/候选上限被截断时返回 `candidates-truncated`，不把部分扫描当全集。`AppNavigator` 为 `ambiguous` 与 `candidates-truncated` 各加独立措辞（「有多个都像X的地方，没有点」「界面太大没数完，没有点」），都 `verified=false`。

TDD 与实跑数据（`--offline --rerun-tasks`，JDK 17，先 `source env.sh`）：

| 阶段 | 结果 | 日志 |
|---|---|---|
| 新 `ChineseTimeChoiceUnitTest`(9) + `TimerChoiceUnitArgumentTest`(3) + `AppFeatureControlAmbiguityTest`(6)，生产未改 | **RED 18 tests / 13 failed / 0 errors**，全部 Truth 断言失败：`durationSeconds` 期望 null 实得 **4200 / 330**、`featureControl` 期望 null 实得**根节点/「扫一扫说明」卡片**、歧义路径实得 `FeatureControl` | RED 那次的日志**没有留存**（只有控制台输出与当时的 XML，后续运行已覆盖），如实标注 |
| 实现后同组 | **GREEN 18 / 18**，exit=0 | `logs/pr4-review39-green1.txt` |
| 定向 `ai.closepaw.voice.*` | **333 tests / 0 failed / 0 errors / 0 skipped**，exit=0（第㊳轮 315，+18） | `logs/pr4-review39-targeted.txt` |
| 全量 `:app:testDebugUnitTest` | **2423 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**；失败仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改、未跳过，本轮未重跑旧 SHA 基线 | `logs/pr4-review39-full.txt` |

过程中一次编译失败如实记录：三处消息串用了 `$feature的地方`，Kotlin 把 `feature的` 当标识符 → `Unresolved reference`；改成 `${feature}` 后重跑。那是编译错误，不计入 RED。

边界（必须看清）：
- 全部为**源码 + JVM 合成树**：三星真机上同一页面出现多个同名可点控件的频率、「扫一扫」这类功能的真实标签是精确还是带后缀（`扫一扫，打开相机`）、活动窗口根是否常带 `isClickable=true`，**均未验证**。
- **可用性代价（真实）**：`featureControl` 现在对大页面更保守——节点预算 600 内数不完候选即拒绝打开。微信这类大树是否经常撞上限，只能真机看。
- 设备矩阵维持历史 4 通过 / 0 失败 / 3 未测；本 SHA **未构建、未安装、未实测**，实机需用户**重新授权**。
- `closeAlarm` 仍 `BLOCKED`（安全拒绝）；计时器创建/取消对象级终态仍 `UNPROVEN`；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。

## 第㉞轮：真实回读被终态闸门丢掉——手电/Wi-Fi 从「确认」退回「没法确认」（源码 + JVM，未实机）

本轮无新审查（最新 review `6549082221` 已由第㉜轮处理、第㉝轮已回复 `6549757219`，其后 PR 无新评论），按「无新审查则推进可离线项」处理遗留清单里的 **「手电/Wi-Fi 缺对象级回读」**。查下来不是没有回读，是**回读没交给闸门**。

1. **缺陷（真实，不是措辞问题）**：`TorchControl.set` 会等 `onTorchModeChanged`，`WifiControl.enable/disable` 会前后读 `isWifiEnabled`；两者都用这份真实读回置 `verified = true`，但 `observed` 一直是 `null`。第㉔轮起的 `TerminalState` 对声明了 `expectedState`（`on:WIFI`、`off:TORCH`）的任务要求结构化证据，于是**真的开了的手电、真的切了的 Wi-Fi** 被统一改写成「请求已经发出，但没能确认结果」。过度保守也是不准确——证据存在而契约把它丢掉了。
2. **改法（最小）**：从 `TorchControl.set` 抽出纯判定 `outcome(on, cameraId, observed, unavailable)`，成功分支填 `ObservedState("torch", "on"|"off", "camera2:torch-callback")`；`WifiControl` 四条成功返回统一走 `stateResult(...)`，填 `ObservedState("wifi", "on"|"off", "wifi-manager:isWifiEnabled")`。identity 与 `SemanticParser.identityOf` 的 `target.name.lowercase()` 对齐（测试里直接解析原话并断言，不靠假设）。超时 / 相机被占用 / 反状态 / 平台拒绝这些分支**不加**证据。
3. **新测试 `SwitchReadbackEvidenceTest`(10)**：
   - Wi-Fi 三条成功分支走真实 `WifiControl` 调用（MockK 桩 `Context`+`WifiManager.isWifiEnabled`/`setWifiEnabled`），断言 `observed` 的 identity 与 state；
   - 手电四条纯判定：`on`/`off` 成功、无回调 → TIMEOUT 无证据、相机被占用 → DEVICE_UNAVAILABLE 无证据、回读为反状态 → EXECUTION_ERROR 无证据；
   - 端到端：`SemanticParser.parse("打开wifi"/"打开手电筒")` 的任务经 `TerminalState.verdict` 判 `CONFIRMED`、`enforce` 不改写句子；控制组「同一 SUCCESS 结果去掉证据」仍判 `UNPROVEN`。

TDD 与真实数据（全部 `--offline`，仓库根先 `source env.sh`）：

| 阶段 | 结果 | 日志 |
|---|---|---|
| 先写测试 + 只把手电判定抽成函数（行为未变） | **RED 10 tests / 6 failed / 0 errors**，全是 Truth 断言失败（`expected: wifi but was: null` 等），4 条控制组本来通过 | `logs/pr4-round34-red.txt` |
| 实现后同组 | **GREEN 10 / 10**，exit=0 | `logs/pr4-round34-green1.txt` |
| 定向 `ai.closepaw.voice.*` | **265 tests / 0 failed / 0 errors / 0 skipped**，exit=0（第㉝轮 255，+10 为本轮新测试） | `logs/pr4-round34-targeted.txt` |
| 全量 `:app:testDebugUnitTest` | **2355 tests / 6 failed / 0 errors / 8 skipped**，Gradle exit=1，**不是全绿**；失败仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5（本轮未改、未跳过，也未重跑旧 SHA 基线） | `logs/pr4-round34-full.txt` |

边界与遗留（如实）：
- **手电的 CameraManager 布线仍只有真机证据**：JVM 侧 `Handler(Looper.getMainLooper())` 与回调等待跑不起来（本仓无 Robolectric），本轮只覆盖「回调证据 → 结论」这一步，不覆盖回调是否按时到达。
- **Wi-Fi 的 `SYSTEM_RESTRICTED` 分支仍不可 JVM 测**：`openWifiPanel` 需要 `Intent(...)`，mockable android.jar 的构造返回 null，测试里会在断言前 NPE，故该分支未纳入（本轮也没有改它）。
- 首次 RED 用过 `returnsMany`，夹具在第三次读之后拿不到值抛 NPE——那是夹具问题，不是被测行为；改成「超出脚本值后重复最后一个值」，RED 才干净。这一条写在这里，免得把夹具报错误记成 RED。
- 设备矩阵不变：4 通过 / 0 失败 / 3 未测；本轮未构建/安装 APK、未操作手机、未调用付费 API、未 delegate、未并入 Issue #6、未改受保护规则、未 push main、未合并 PR。实机需用户**重新授权**。
- 遗留不变：`closeAlarm` 缺全列表枚举/稳定身份仍 `BLOCKED`；计时器创建/取消的对象级终态仍是 `UNPROVEN`；创建 undo 安全拒绝；在线 `ToolRouter` 同门禁（阶段 2）。

## 第㉝轮：语音时长到工具参数之间的一处 60 倍错误（源码 + JVM，未实机）

本轮无新审查（最新 review `6549082221` 已由第㉜轮回复 `6549362112` 处理，其后 PR 无新评论），按「无新审查则推进可离线项」处理遗留清单里的 **「五分钟真实工具参数」**。它不是设备项才有的问题——先补端到端参数断言时，发现解析层就有一个能直接算错的形状。

1. **发现的缺陷（真实存在，非风格问题）**：`ChineseTime.durationSeconds` 先由小时段把 `total` 置为整秒，再由分钟段把**分钟数**加进同一个累加器，然后把**整个累加器** `* 60`。于是任何「小时+分钟」的说法都被放大 60 倍：`一小时十分钟` → `(3600+10)*60 = 216600 s`（要求 4200 s），`1小时30分` → `217800 s`。只有小时、只有分钟、只有秒的说法都正确，所以既有测试没有一条命中。
2. **闸门为什么没拦住（这是本轮最该记住的一条）**：`IntentGate` 用 `ChineseTime.durationSeconds(said)` 和工具参数比对，**两侧都来自同一个解析器**，所以两边一起错、比对通过。参数正确性没有被独立验证过——这正是「解析 300 秒」曾被当成「工具参数 300 秒」的同类风险。
3. **改法（最小）**：分钟段改成 `total += 分钟数 * 60`，不再缩放整个累加器。行为影响面：`IntentPlanner` 与 `SemanticParser`/`IntentGate` 共用该函数，含小时的时长现在按和计算。
4. **新测试（TDD 先写）**：
   - `ChineseTimeDurationTest`（4 条）：分钟/秒/半小时为控制组；`一小时十分钟=4200`、`1小时30分=5400`、`两小时十五分钟=8100`、`一小时45分30秒=6330` 为本轮契约；`六十分钟=3600`（防止把「分钟」当独立项重复计）；无时长返回 null。
   - `TimerCreateArgumentTest`（3 条，真实 parser→能力表→闸门→Runner，只 mock `ClockControl`）：`开始五分钟倒计时` → `createTimer(ctx, 300)` 恰好一次且**不是** 5 / 3000；`设置一小时十分钟的倒计时` → `createTimer(ctx, 4200)` 且不是 216600；`设一个倒计时`（无时长）→ 追问「多久」且零工具调用。
5. **净效果（如实）**：第 2 条测试在修复前**真实拿到 216600**——不是假设出来的风险，是 MockK 记录的入参。第 1 条（五分钟=300）修复前后都通过，属回归锁，不冒充 RED。

TDD 与真实数据（全部 `--offline --rerun-tasks`，仓库根先 `source env.sh`）：

| 阶段 | 结果 | 日志 |
|---|---|---|
| 先写两组测试、生产代码未改 | **RED 7 tests / 2 failed / 0 errors**；失败为 `ChineseTimeDurationTest.hours plus minutes…`（expected 4200 / was 216600）与 `TimerCreateArgumentTest.hours plus minutes…`（`createTimer` 实收 216600），其余 5 条为控制组 | `logs/pr4-round33-red.txt` |
| 实现后同组 | **GREEN 7 / 7**，exit=0 | `logs/pr4-round33-green1.txt` |
| 定向 `ai.closepaw.voice.*` | **255 tests / 0 failed / 0 errors / 0 skipped**，exit=0（第㉜轮 248，+7 为本轮新测试） | `logs/pr4-round33-targeted.txt` |
| 全量 `:app:testDebugUnitTest` | **2345 tests / 6 failed / 0 errors / 8 skipped**，Gradle exit=1，**不是全绿**；失败仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5（本轮未改、未跳过） | `logs/pr4-round33-full.txt`、`logs/pr4-round33-full2.txt` |

边界与遗留（如实）：
- **仍是 JVM 契约，不是实机**：这些只证明「说法→工具入参」这一跳正确。三星时钟是否接受 4200 s、创建后能否核对，全部**未验证**；设备矩阵维持历史 4 通过 / 0 失败 / 3 未测，实机需用户**重新授权**。
- 顺带看到一个**未改**的语义问题（属建议，不是本轮任务）：`一个半小时` 现在解析成 1800 s（半小时），而口语含义是 5400 s。改它需要决定「一个半」「两个半」这类说法的处理规则，本轮没有擅自改，也未把当前值写进测试当成正确值。
- 本轮未构建/安装 APK、未操作手机、未调用付费 API、未 delegate、未并入 Issue #6、未改受保护规则、未 push main、未合并 PR。
- 遗留不变：`closeAlarm` 缺全列表枚举/稳定身份仍 `BLOCKED`；查询结果截断未当作不完整；计时器唯一绑定；创建 undo 安全拒绝；在线 `ToolRouter` 同门禁（阶段 2）。

## 第㉜轮：撤回创建/取消里最后一处「同类证据」冒充对象结论（源码 + JVM，未实机）

处理新复审（HEAD `54039d1`，2 条：1 P1 + 1 P2；https://github.com/mo-mx/AndroidAgent/pull/4#issuecomment-6549082221 ）。核心一句话：**一次视口读取不是枚举，相同时长不是同一个对象**。上一轮把「视口」当成了列表全貌，本轮把两处判定一起撤回到 `UNPROVEN`。

1. **[P1] `createTimer` 不再判 `CONFIRMED`**：`timerDraft` 只在**已加载的无障碍树**里数 `初始时长/总时长` 文本，`textsTruncated=false` 只说明遍历没超预算，**不说明可滚动/虚拟化列表已全量枚举**。因此「基线无同长度卡 + 表头 +1 + 回读恰好一张该长度卡」与「本次请求没生效 + 视口外的同长度卡滚进来 + 别人新建了另一个长度」同构。三条全过时的分支改为 `sentWithoutProof`（`verified=null`、`observed=null`），`detail` 记 `readback=count-plus-one-duration-card-unattributed`，句子为「…页面上出现了这个时长的卡片，但没法确认是这次创建的，请在时钟里核对」，不再出现「已经设好」。
2. **[P2] `cancelTimer` 不再判 `CONTRADICTED`**：`boundStillListed` 只证明**页面上还有某个同长度计时器**，不证明被点的那个还在（点它真的成功了、另一张同长度卡滚进来，也会命中）。该分支改为 `sentWithoutProof`，`detail` 记 `readback=same-duration-still-listed`，句子为「…页面上仍有一个同样时长的倒计时，无法确认是不是刚才那个」。同时把 `stillListed` 的判断移到**页面/截断门之后**（此前它抢在 `truncated` 之前，截断页也可能被判成取消失败）。
3. **净效果（如实）**：`TerminalState` 对计时器创建/取消**目前没有任何路径能判 `CONFIRMED`/`CONTRADICTED`**——`timer_running:<秒>` 与 `no_running_timer` 在真机上只能是 `UNPROVEN`。这是有意的：要判终态，先要有稳定对象 ID 或全量枚举，二者都还不存在。回读仍然做，事实仍写进 `detail`，只是不再替你下结论。

TDD 与真实数据（全部 `--offline --rerun-tasks`，仓库根先 `source env.sh`）：

| 阶段 | 结果 | 日志 |
|---|---|---|
| 先改 `TimerCreateReadbackTest`(10)、`TimerCancelReadbackTest`(8)，生产代码未改 | **RED 28 tests / 5 failed / 0 errors**，Truth 断言失败（非编译失败）；5 条正是本轮要改的契约，含两条复审给出的反例布局 | 控制台输出，未单独存盘 |
| 实现后同组 | 首跑 **1 failed**（`before=2` 用例返回 `DEVICE_UNAVAILABLE`：测试换了绑定根节点，`selectedState` 桩未跟着换）；补桩后 **28 / 28 GREEN**，exit=0 | 控制台输出，未单独存盘 |
| 定向 `ai.closepaw.voice.*` | **248 tests / 0 failed / 0 errors / 0 skipped**，exit=0（第㉛轮 245，+3 净增） | 控制台输出 + `app/build/test-results` 聚合 |
| 全量 `:app:testDebugUnitTest` | **2338 tests / 6 failed / 0 errors / 8 skipped**，Gradle exit=1，**不是全绿**；失败仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5（本轮未改、未跳过） | `logs/pr4-r33-full.txt` |

边界与遗留（如实）：
- 证据全部来自 **JVM 合成无障碍树**，不是三星真实时钟 UI；真机上计时器列表是否虚拟化、卡片是否用 `初始时长/总时长` 表达原始时长、表头刷新时序**仍未验证**。
- 待办性质变化：创建/取消的「对象级终态」现在是**已知不可达**，而不是待调参。下一步要么拿到稳定对象 ID（真机采集脱敏树后确认有无 resource-id/可跨读关联的稳定字段），要么先做列表全量枚举（滚动+合并多次读，需实机验证滚动边界），两者都需要**用户重新授权实机**。
- `closeAlarm` 仍 `BLOCKED`（缺全列表枚举/稳定身份，安全拒绝）；`cancelTimer` 未校时钟窗口的旧项本轮未动；查询结果截断未当作不完整、计时器唯一绑定、五分钟真实工具参数等遗留不变。
- 本轮未构建/安装 APK、未操作手机、未调用付费 API、未 delegate、未并入 Issue #6、未改受保护规则、未 push main、未合并 PR。

## 第㉛轮：创建倒计时首次有可归因的对象级回读（源码 + JVM，未实机）

本轮无新审查（最新 review `6548293536` 已由第㉚轮回复 `6548650998` 处理，其后 PR 无新评论），按「无新审查则推进可离线项」处理遗留清单里的**创建路径**：`createTimer` 此前完全不做回读，`expectedState = timer_running:<秒>` 在真机上永久 `UNPROVEN`。

1. **回读契约**：`ClockControl.createTimer` 现在做**两次完整读**——发 Intent 之前一次（基线）、之后一次（回读），两次都必须满足「同一窗口门 / `rootPackage` 是时钟 / `selectedTabs["定时器"] == true` / 未被截断 / 表头计数可解析」。
2. **唯一允许的肯定结论**（三条同时成立）：
   - 基线页面上**没有任何**卡片声明被请求的原始时长（`初始时长/总时长` 解析值 ≠ 目标）；
   - 回读表头计数 **恰好 +1**；
   - 回读页面上**恰好一张**卡片声明该原始时长。
   成立时 `verified=true` + `ObservedState("timer:<秒>", "running:<秒>")`，`TerminalState` 才能对 `timer_running:<秒>` 判 `CONFIRMED`。
3. **其余全部 fail-closed**（`ToolResult.sentWithoutProof`，`verified=null`、`observed=null`）：基线/回读不可读（`readback=before-page-unconfirmed|before-truncated|before-count-unparsed` / `after-…`）、同长度倒计时已存在（`readback=same-duration-twin`）、计数没有 +1（`count-not-plus-one`）、回读页面看不到该时长（`duration-card-not-found`）、出现多张同长度卡（`duration-card-not-single`）。Intent 层被系统拒绝时原样透传，不拿页面评价（`EXECUTION_ERROR`）。
4. **残留假设照写不藏**：时长标签是卡片**声明的原始时长**，不是稳定对象 ID；两个读之间若有第三方创建了同长度倒计时，同一判据也会成立。计数必须 +1 是把这条口子收窄的措施，不是消除。

TDD 与真实数据（全部 `--offline --rerun-tasks`，JDK 17 本地，仓库根先 `source env.sh`）：

| 阶段 | 结果 | 日志 |
|---|---|---|
| 先写 `TimerCreateReadbackTest`(9)，生产代码未改 | **RED 9 tests / 8 failed / 0 errors**，Truth 断言失败（非编译失败）；唯一先绿的是「Intent 被拒绝时原样透传」 | `logs/pr4-round31-red.txt` |
| 实现两段回读后同组 | **GREEN 9 / 9**，exit=0 | `logs/pr4-round31-green1.txt` |
| 定向 `ai.closepaw.voice.*` | **245 tests / 0 failed / 0 errors / 0 skipped**，exit=0（第㉚轮 236，+9 为本轮新测试） | `logs/pr4-round31-targeted.txt` |
| 全量 `:app:testDebugUnitTest` | **2335 tests / 6 failed / 0 errors / 8 skipped**，Gradle exit=1，**不是全绿**；失败仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5（本轮未改、未跳过） | `logs/pr4-round31-full.txt` |

边界与遗留（如实）：
- 全部证据来自 **JVM 合成无障碍树**，不是三星时钟的真实 UI。真机上表头计数刷新时序、卡片是否用 `初始时长/总时长` 表达原始时长、双标签卡结构**仍全部未验证**；若表头滞后，结果会落到 `count-not-plus-one` → `UNPROVEN`（保守，不假报成功）。
- 既有副作用：创建前多了一次定时器页面读取（约 +0.4 s 与一次 tab 点击），在时钟不在前台的场景会额外拉起时钟再读一次。这是取得基线的代价，未做性能测量。
- 本轮未构建/安装 APK、未操作手机、未调用付费 API、未 delegate、不并入 Issue #6、不改受保护规则、不 push main、不合并。实机验收需用户**重新授权**。
- 仍缺对象级回读的：`closeAlarm`（缺全列表枚举/稳定身份，继续 `BLOCKED`）、手电/Wi-Fi；在线 `ToolRouter` 同门禁（阶段 2）仍未做。

## 第㉚轮：撤回「取消即成功」；计数与视口不再当对象证据（源码 + JVM，未实机）

处理新复审 `6548293536`（HEAD `4f5734e`，2 条 P1 + 1 条 P2；https://github.com/mo-mx/AndroidAgent/pull/4#issuecomment-6548293536 ）。上一轮（`4f5734e`）让 `cancelTimer` 点击后回读，方向对但判据不足：**视口缺失 + 计数下降不能归因到被点的那个对象**。本轮把结论收紧到「只能证明它还在，不能证明它没了」。

1. **[P1] 取消成功判定撤回**：`ClockControl.cancelTimer` 点击后仍回读同一页面，但**唯一允许的对象级结论是否定的**——同一形态的 `初始时长/总时长` 标签仍在 → `verified=false` + `ObservedState("timer:<秒>","running:<秒>")`（`TerminalState` 判 `CONTRADICTED`）。标签消失 + 计数下降**不再**推出 `verified=true`：一次读只覆盖当前视口、计数会被「另一个计时器自然结束」改变、时长标签也不是稳定 ID。其余全部 `sentWithoutProof`（`verified=null`、`observed=null`），`detail` 记 `readback=page-unconfirmed|truncated|count-not-lower|count-not-attributable`，句子固定「已请求取消指定倒计时，但无法确认最终状态」，不再出现「已取消」。
2. **[P1] 计数只认页面自己的陈述**：`timerCountFrom` 现在只从 `N 个定时器正在运行` 表头或明确空态句（`没有正在运行的定时器` / `暂无定时器` / `无正在运行的定时器`）取总数。`添加定时器`（创建入口）、创建 picker、可见卡片数量**一律不再当 0 或当总数**。新增 `visibleTimerCards` 作为「视口内至少几张卡」的下界，`queryTimers` 在总量未知但下界 > 0 时只说「页面上至少能看到 N 个倒计时在运行，但没法确认一共有几个」；总量与下界都没有时 `DEVICE_UNAVAILABLE` 且不播确定数字。
3. **[P2] 身份对应不再默认放行**：`TerminalState.identityMatches` 原先在 `task.targetIdentity == null` 时直接返回 `true`，等于真实语音路径从未核对对象身份。现在**双侧都必须声明身份且相等**；`SemanticParser.identityOf` 从与 `expectedState` 同一批参数派生身份（`timer:<秒>`、`alarm:H:M`、开关的 `torch|wifi|bluetooth` 小写，与工具边界既有 `target.name.lowercase()` 对齐），`SemanticRunner` 的 `ToolCall` 同步携带 `timerIdentity(secs)`，避免新身份把合法 timer 调用误判成 identity drift。

TDD 与真实数据（全部 `--offline --rerun-tasks`，JDK 17 本地）：

| 阶段 | 结果 | 日志 |
|---|---|---|
| 先写/改测试，生产代码未改 | **RED 是编译失败**：`visibleTimerCards` 未解析，断言未执行；`isEqualTo` 未解析是连带 | `logs/pr4-review29-red.txt` |
| 按新契约实现后首次定向 | **236 tests / 10 failed / 0 errors**：6 条 `IntentGateTest` 旧夹具未携带工具边界的新身份、`ClockSelectionEntryMatrixTest`（`verified` 由 null 变 false）、`TerminalUnprovenMutationTest`（身份不再可省），以及本轮我自己 2 条新测试期望写错（utterance 与 reason 串） | `logs/pr4-review29-targeted.txt` |
| 夹具对齐新契约后 | **236 tests / 0 failed / 0 errors / 0 skipped**，exit=0 | `logs/pr4-review29-targeted3.txt` |
| 全量 `:app:testDebugUnitTest` | **2326 tests / 6 failed / 0 errors / 8 skipped**，Gradle exit=1，**不是全绿**；失败仍是 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5（本轮未改、未跳过、未重跑旧 SHA 基线） | `logs/pr4-review29-full.txt` |

断言变动如实说明（没有整类删除）：
- `ClockSelectionEntryMatrixTest`「取消已绑定的卡片」：原断言 `verified == null`（旧实现根本不回读），改为 `verified != true`——回读后既可能 `null`（无法归因）也可能 `false`（卡片仍在），两者都不是成功。
- `TerminalUnprovenMutationTest`「CONFIRMED 直通」：原夹具不声明身份，正是 P2 指出的漏洞；改为声明 `timer` 后仍断言 `CONFIRMED` 且结果不被改写；同时 `TerminalExpectedStateTest` 新增用例断言「未声明身份 + 有身份证据 = UNPROVEN」。
- `IntentGateTest`：`allow()` 按 `SemanticRunner` 的真实约定补上工具侧身份；「5 秒 vs 300 秒」那条改为断言 `identity drift`（比原来的参数不一致检查更早拒绝），不是放行。
- `TimerCancelReadbackTest`：原来两条「必须 CONFIRMED」的正向用例改为断言 `verified=null` + `UNPROVEN`，并新增「另一个计时器自然结束 + 目标卡移出视口」负例。

边界与遗留（如实）：
- 三星时钟是否暴露稳定 timer 对象 ID、页头计数刷新时序、可滚动列表完整性、导航栏与正文双标签卡片结构：**仍全部未验证**；本轮未构建/安装 APK、未操作手机。取消功能在真机上仍只会说「无法确认」——这是合规结果，不是功能完成。
- 可见副作用：在总量未知的页面上，`queryTimers` 会从「现在有 N 个」退到「至少 N 个」或「没法确认」。旧 picker 空态判定来自一次真机采集，但「picker 出现」不等于「列表为空」，按审查要求撤回；真正空页面的文案需重新实机采集后才能恢复确定性播报。
- `createTimer` / `closeAlarm` / 手电 / Wi-Fi 仍无对象级回读；`closeAlarm` 因缺全列表/对象身份继续 `BLOCKED`；在线 `ToolRouter` 同门禁（阶段 2）仍未做。
- 未调用付费 API、未 delegate 给本地小模型、不并入 Issue #6、未改 `AGENTS.md` 等受保护规则、不 push main、不合并。

## 第㉙轮：取消倒计时首次给出对象级回读证据（源码 + JVM，未实机）

第㉘轮遗留的最大缺口是「`observed` 没有真实生产者」：所有声明了 `expectedState` 的变更在真机上一律 `UNPROVEN`，`CONFIRMED` / `CONTRADICTED` 只有 JVM 夹具可达。本轮先补最容易证伪的一处：**取消计时器点击之后回读同一个定时器页面**。

1. **回读契约**（`ClockControl.cancelTimer`）：`performAction(ACTION_CLICK) == true` 只证明手势投递，旧实现据此返回 `sentWithoutProof`。现在点击后 700 ms 重新走 `readClockScreen("定时器")`，四个事实分别记录、互不替代：
   - 页面身份：`rootPackage == 时钟包` 且同一次读回的 `selectedTabs["定时器"] == true`；
   - 读回是否被截断（`textsTruncated || nodesTruncated`）；
   - 回读后的运行中计数（`timerCountFrom`，同一棵树；页面被判非计时器页时不算）；
   - 被点击那张卡片的**同一形态**时长标签（`初始时长` / `总时长` + `ChineseTime.durationSeconds`）是否还在。
2. **判定**：
   - 页面无法确认 / 截断 / 计数不可解析 → `verified = null`、`observed = null`，句子仍是「无法确认最终状态」（`readback=page-unconfirmed|truncated|count-unparsed`）；
   - 时长标签仍在且回读计数 > 0 → `verified = false` + `ObservedState(identity="timer:<秒>", state="running:<秒>")`，`TerminalState` 判 `CONTRADICTED` 并降级为 `EXECUTION_ERROR`；
   - 标签消失 **且** 计数确实下降（或页面变为 0 个）→ `verified = true` + `ObservedState(identity="timer:<秒>", state="none")`，`no_running_timer` 判 `CONFIRMED`；
   - 标签消失但计数没降 → 两个观测互相矛盾，一律 `verified = null`（`readback=count-not-lower`），不拿其中任一条当完成。
3. **身份**：`identity = "timer:<绑定时的秒数>"`，与 `SemanticParser` 的 `no_running_timer` 期望配合；`task.targetIdentity` 为空时不强制身份相等（与 `TerminalState.identityMatches` 现有语义一致）。计数与标签取自同一棵树的文本，避免「截断的页面报告了一个下降」。

TDD（全部 `--offline --rerun-tasks`，JDK 17 本地）：

| 阶段 | 结果 | 日志 |
|---|---|---|
| 新 `TimerCancelReadbackTest`(5) 先写，实现未改 | **RED 5 tests / 5 failed**，全部是 Truth 断言失败（无编译错误） | `logs/pr4-review29-red.txt` |
| 实现回读后同组 | **GREEN 5 / 5**，exit=0 | `logs/pr4-review29-green1.txt` |
| 定向 `voice.semantics.*` + `voice.tools.*` | **224 tests / 0 failed / 0 errors / 0 skipped**，exit=0（上一轮 219，+5 为本轮新测试） | `logs/pr4-review29-targeted.txt` |
| 全量 `:app:testDebugUnitTest` | **2314 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**（仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，本轮未改、未跳过、未重跑旧 SHA 基线） | `logs/pr4-review29-full.txt` |

边界（如实）：
- 这是**第一次有一个真实路径会给出 `observed`**，但仍然只是 JVM 合成树。三星真机上「取消后卡片是否立刻消失」「运行计数表头是否随取消同步更新」都**未验证**；若表头滞后，真实结果会落到 `count-not-lower` → `UNPROVEN`（保守，不假报成功）。
- `createTimer` / `closeAlarm` / 手电与Wi-Fi 仍**没有**对象级回读：创建后无法从 UI 读回「原始时长」这个身份（剩余时间不是原始时长，已在第㉕轮说明），所以创建仍一律 `UNPROVEN`；`closeAlarm` 仍因缺全列表/对象身份 `BLOCKED`，是安全拒绝不是功能完成。
- 在线 `ToolRouter` 同门禁（阶段 2）、正文双标签卡片身份仍待办；后者需实机采集脱敏 UI 树。
- 本轮未构建/安装 APK、未操作手机、未调用付费 API、未 delegate、不并入 Issue #6、不改受保护规则、不 push main、不合并。实机验收需用户**重新授权**。

## 第㉖轮：`expectedState` 真正参与判定 + `OPEN` 不再绕过闸门（源码 + JVM，未实机）

针对定时复审 `6546660320`（针对 `2048f6d`，https://github.com/mo-mx/AndroidAgent/pull/4#issuecomment-6546660320 ）的 P1 与 P2 第 2 条。上一轮（`b1922dc`）评论误称「最新已审 SHA 是 `2212ed6`」，漏掉了这条复审，本轮补上。

1. **[P1] 终态闸门不再只信一个布尔值**：新增 `tools/ObservedState(identity, state, source)`，`ToolResult` 增加 `observed` 字段。`TerminalState.verdict` 现在的规则是：
   - 任务声明了 `expectedState`（`timer_running:300` / `no_running_timer` / `alarm_exists:HHMM` / `on|off:TARGET` / `feature_visible`）时，**`verified=true` 本身不再算证据**；
   - `observed == null` → `UNPROVEN`（请求已发出，但没有对象级回读）；
   - `observed.identity` 与 `task.targetIdentity` 不符或缺一 → `UNPROVEN`（读到的是别的对象，不能升级成"完成"，也不谎报"没变化"）；
   - 同一对象但 `observed.state` 与 `expectedState` 不符 → `CONTRADICTED`，`enforce()` 降级为 `EXECUTION_ERROR`；
   - 声明了闸门没有判定谓词的 `expectedState` → 一律 `UNPROVEN`（拒绝默认放行）。
   - 未声明 `expectedState` 的任务保持旧的 `verified` 三态分级（没有可比对的东西，不假装做了对象级校验）。
2. **[P2] `OPEN` 不再豁免**：`promisesState` 纳入 `Action.OPEN`。「打开微信扫一扫」和「开始五分钟倒计时」一样是对终态的承诺。`AppNavigator.openFeatureResolved` 的「同包内新增 ≥2 条文字就算进入」改为 `verified=null` + 「点了…界面有变化，但没能确认已经进入」，`TerminalState.enforce` 再兜一层：`OPEN` + `verified=true` + 无页面身份证据 → 句子改写、`verified=null`、`detail` 追加 `postcondition=unproven(no page identity)`。

TDD（全部 `--offline --rerun-tasks`）：

| 阶段 | 结果 |
|---|---|
| 新 `TerminalExpectedStateTest`(14) 先写，`ObservedState`/`observed` 仅做类型铺垫后跑 | **RED 14 tests / 9 failed**（Truth 断言失败，非编译错误；`logs/pr4-review26-red.txt`） |
| 实现 `TerminalState` 谓词 + `AppNavigator` 降级后同组 | **GREEN**：`TerminalExpectedStateTest` + `TerminalStateGateTest` + `SemanticExecutionTest` 定向全绿，exit=0 |
| 定向 `voice.semantics.*` + `voice.tools.*` | **202 tests / 0 failed / 0 errors / 0 skipped**，exit=0 |
| 全量 `:app:testDebugUnitTest` | **2292 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**（仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，本轮未改、未跳过、未重跑旧 SHA 基线） |

边界（如实）：**目前没有任何生产者会填 `observed`**，所以声明了 `expectedState` 的任务在真机上一律落到 `UNPROVEN` —— `CONFIRMED` 现在只有 JVM 夹具可达。这是刻意的保守：先前那条「`verified=true` 即 CONFIRMED」的路径本来就是误报来源。真正要恢复 `CONFIRMED`，得让 `cancelTimer`/`closeAlarm`/`setTimer` 回读时给出对象身份与状态 token，属后续离线工作。本轮仍未实机、未构建安装 APK。

**未做（如实）**：同一复审的 P2 第 3 条（`IntentActions.kt:58-77` 计时器创建后仍用 `fg.contains("clock")` + `UiAutomator.clickText("定时器")` 模糊点击，绕开 `ClockControl.clickClockTab` 的精确标签/窗口归属/禁坐标回退）本轮未修改，留待下一轮；它需要把 `ClockControl` 的安全 tab 导航暴露给 `tools` 层并补 MockK 零点击断言。

## 第㉔轮：`expectedState` 通用终态校验（离线源码 + JVM，未实机）

第⑳轮复审（`6545877923`）与更早的复审都把「`expectedState` 通用业务终态校验」列为未完成项。本轮把它落成一个**证据分级契约**，并清掉 `verified` 的 `false`/`null` 混用：

1. **新增 `voice/semantics/TerminalState.kt`**（纯 Kotlin，无 Android import，可 JVM 测）。执行前由 `IntentGate` 判「这是不是用户要做的那件事」，执行后由它判「工具带回来的证据支不支持我们承诺的终态」：
   - `CONFIRMED`：变更类任务 + `SUCCESS` + `verified=true`（回读一致）。
   - `UNPROVEN`：`SUCCESS` + `verified=null`（请求已发出，什么都没回读）——仍是成功、仍可播报，但不得说成「做好了」。
   - `CONTRADICTED`：`SUCCESS` + `verified=false`（回读和期望不一致）——`enforce()` 降级为 `EXECUTION_ERROR`，句子明说「设备状态没有变化」，原 `detail` 保留并追加 `postcondition=contradicted`。
   - 查询任务、以及 BLOCKED / PERMISSION_DENIED / DEVICE_UNAVAILABLE 等拒绝结果一律 `NOT_APPLICABLE`，原句不改写；`enforce()` 幂等。
2. **接线点唯一**：`SemanticRunner.run` 里每个任务的 `dispatch` 结果统一过 `TerminalState.enforce`，日志照实打 `status/verified/detail`（降级后的值）。
3. **`false` 与 `null` 不再混用**：原代码有 11 处「只投递了 Intent / 只点了控件、没有业务回读」却返回 `SUCCESS + verified=false`，而 `ToolResult` 的文档语义里 `false` 是「回读了、状态没变」。新增 `ToolResult.sentWithoutProof(...)`（`SUCCESS + verified=null`）并替换：timer 创建 3、alarm 创建 3、`cancelTimer` 1、`dial` / `maps` / `web`×2 / `camera` 共 6。这是本轮唯一改变既有播报语义的地方：这些路径从「被当成已确认失败」改为「未知」，播报文本不变。

TDD（真实执行，`--offline --rerun-tasks`）：

| 阶段 | 结果 |
|---|---|
| 新 `TerminalStateGateTest`(11) 先写、实现未写 | **RED = 编译失败**（`Unresolved reference 'TerminalState' / 'sentWithoutProof'`），不是断言失败；如实记为弱 RED |
| 实现 + 接线 + 11 处替换后 | 定向首次 **183 tests / 6 failed**：6 条全部是旧断言 `verified isFalse` 遇到新语义 `null`（`ClockAlarmPageEvidenceTest` 1、`ClockSelectionEntryMatrixTest` 1、`ClockUndoSafetyTest` 1、`IntentCreationVerificationTest` 3） |
| 更新为 `isNull()` 并补 `status=SUCCESS` 断言后 | 定向 **183 tests / 0 failed / 0 errors / 0 skipped**，exit=0 |
| 全量 `:app:testDebugUnitTest` | **2273 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**（仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，与基线同类，未改、未跳过） |

6 条旧断言是**语义纠正**而非弱化：`isFalse` → `isNull()`，同时新增 `status == SUCCESS` 断言，没有删除任何断言。基线（本轮改动前，同一套定向过滤）：172 tests / 0 failed。

边界（如实）：这一轮只是**把「有没有证据」分级并强制在返回边界执行**，不是业务状态回读本身。仍然是 `源码分析 + JVM 合成结果`，不是实机。还没有任何生产者会在回读后给出 `verified=false`，所以 `CONTRADICTED → EXECUTION_ERROR` 这条分支目前只有 JVM 夹具覆盖，真机未出现过。真正缺的仍是：创建/取消后回读对象存在性与计数、在线 ToolRouter 同门禁（阶段 2）、正文双标签卡片身份（需实机采集）。

## 第㉓轮局部修复：第⑳轮定时复审的节点身份与候选完整性（源码 + JVM，未实机）

针对 PR #4 定时复审 `chatgpt-hourly-review SHA:f5d877a`（评论 15:32Z，https://github.com/mo-mx/AndroidAgent/pull/4#issuecomment-6544851243 ）的两条离线项，先写回归再改代码：

1. **[P1] 引用身份判等**：`navControl`/`isNavBar`/`depthBelow`/`clickUnit`/`insideScrollable`/`clickTabControl`/`selectedState` 原来用 `===` / `!==` / `IdentityHashMap` 判断「同一个 tab 条」「是否到容器边界」「两个标签是否共用点击单元」。平台的 `getParent()/getChild()` 不保证同一逻辑节点返回同一包装对象，后果：①同一条通过两个包装对象上报时被判成两条 → 误判歧义、导航不可用；②`node === root` / `node === nav.container` 失效 → 容器/窗口根的点击边界不再兜住；③`depthBelow` 返回 1000 哨兵。现统一改为 `sameNode()`：先 `===`，再 `runCatching { a.equals(b) }`（AOSP 的 `equals` 比较 windowId + sourceNodeId 这类逻辑身份），拿不到等价时退回引用身份。点击单元去重从 `IdentityHashMap` 改为按逻辑等价比较（每栏最多 4 个标签，线性比较即可，也不依赖 `hashCode` 一致性）。
2. **[P1 附带] 深度哨兵显式拒绝**：两个标签都「走不回本栏」时深度同为 1000、极差为 0，旧的「同深度」规则会在没有任何归属证明的情况下通过。现在 `depthBelow` 结果为 `UNREACHABLE_DEPTH` 直接拒绝该候选。
3. **[P2] 候选上限 8 静默截断**：旧 `collectExactLabels` 到 8 个后继续遍历却丢弃匹配，调用方以为拿到了全集。现返回 `CandidateScan(nodes, complete)`；候选溢出或遍历未走完（`MAX_NODES` 截断）都标记为不完整，`navControl` 直接返回 null——部分证据不再当作全集，也不会拿预算内的第一条 tab 条当唯一。

TDD（全部 `--offline --rerun-tasks`）：新 `ClockNodeIdentityTest`(5) 在上一 head `2212ed6` 上 **RED 5 / 5 failed**（断言失败，非编译错误，`logs/pr4-review23-identity-red.txt`），修后 **5 / 5 通过**（`logs/pr4-review23-identity-green.txt`）。定向 `voice.semantics.*` + `voice.tools.*` **172 tests / 0 failed / 0 errors / 0 skipped**，exit=0；全量 `:app:testDebugUnitTest` **2262 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**（仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，与前轮同类，本轮未改、未跳过、未重跑旧 SHA 基线）。日志只留本机。

边界（如实）：这仍是 JVM 合成树契约。**三星真机在一次遍历里是否真的返回等价但不同实例的包装对象未验证**；如果 `AccessibilityNodeInfo` 在该 ROM 上不按逻辑身份实现 `equals`，行为会退回旧的引用身份语义（不比旧版更差，但收益也不成立）。P2 提到的「小型正文双标签卡片」仍需实机采集脱敏 resource-id/role/层级后才能定策略，本轮未做、也不冒充已关闭。

## 第㉒轮局部修复：选中态判定在查询/绑定入口的联动矩阵（源码 + JVM，未实机）

第㉑轮把 `selectedState` 收窄到「只在识别出的 tab 条内判选中」后，留下的是**这条改动在出口处是否放宽准入**的疑问（复审 `6544694121` 第 3 条）。本轮把它按入口逐条钉住，并修掉一处诊断混淆：

1. **新回归 `ClockSelectionEntryMatrixTest`(11)**，覆盖 `readTree.selectedTabs` → `onTargetPage`（`queryTimers`/`queryAlarms`/`closeAlarm`）→ 绑定门 `verifiedClockWindow`（`cancelTimer`）三处出口的双向矩阵：`null`（栏内没有任何选中上报）在每一处都**拒绝**且零点击；`false`（栏内另有 tab 选中）同样拒绝；`true` 仍放行（控制组，防止「全靠拒绝通过」）。同时钉住：正文卡片 `isSelected=true` 在栏内无上报时读数必须是 `null` 而不是 `false`。
2. **诊断区分（代码改动）**：`verifiedClockWindow` 里 `null`/`false` 原本都落到同一个 `binding-tab-unproven`，且其后 `when(selected)` 分支与两个调用方的 `window.selected == false` 检查已不可达。现改为 `binding-tab-unreported` / `binding-tab-not-selected` 两值，并把 `why=<reason>` 加进 `cancelTimer` / `closeAlarm` 的 `detail`（仍不含任何屏幕文本）。安全行为未变：两者都拒绝、都不点击。

TDD：新测试对上一 head `2cda031` **RED 3 / 11 failed**（`selectedState` 期望 null 实得 false；两处 `why=` 期望 unreported/not-selected 实得 `binding-tab-unproven`），修后 11/11 绿。定向 `voice.semantics.*`+`voice.tools.*` **167 tests / 0 failed / 0 errors / 0 skipped**；全量 **2257 tests / 6 failed / 0 errors / 8 skipped**，exit=1，**不是全绿**（仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，与前轮同类，未改未跳过）。全部 `--offline --rerun-tasks`，日志 `logs/cron-full-r21b.txt` 只留本机。

仍未完成（可离线）：`expectedState` 通用终态校验、在线 ToolRouter 同门禁（阶段 2）、对象身份适配；小型正文双标签卡片的区分与三星真实选中态暴露仍需用户**重新授权**的实机采集。未构建/安装 APK、未操作手机、未调用付费 API、未 push main、不合并。

## 第㉑轮局部修复：第十九轮审查（源码 + JVM，未实机）

按 PR #4 定时复审 `6544694121` / `6544641476`（第⑲轮，针对 `3b7cc66`）的头部 P2 修两处，第三处如实未做：

1. **`navControl` 只取全树第一个精确同名节点**（`findFirst`）→ 正文里先出现的「闹钟」段标题/设置行会抢占，真 tab 条即使合格也无法识别，导航被错误拒绝。改为收集全部精确同名候选（上限 8），逐个用 `barFor` 向上验证导航身份；**两条不同容器都合格时判歧义返回 null**，不取第一个。
2. **`selectedState` 在栏内无 `selected=true` 时扫描整棵窗口树**找任意 `isSelected` → 正文里无关的选中元素会把「无法确认」误诊为「目标未选中」。改为只在识别出的 tab 条子树内判断（含容器自身，兼容容器级上报）；栏内没有任何选中上报时返回 null。安全方向不变：任何情况都不会返回 true。
3. **未做**：小型正文双标签卡片（≤40 节点、同深度、各自可点）仍能通过结构规则。离线只有合成树，没有真实资源 ID/角色证据；再叠结构启发式只会让真 tab 条更容易被拒，不能证明区分能力。需要实机采集 tab 条与正文卡片的 resource-id/role/层级，属**需授权实机**项。

TDD：新 `ClockNavCandidateTest`(5) 在旧实现上 **RED 4 / 5 failed**（`navControl` 返回 null 而非 tab 条、两条 tab 条未判歧义、两处 `selectedState` 期望 null 实得 false），修后 5/5。定向 `voice.semantics.*`+`voice.tools.*` **156 tests / 0 failed / 0 errors / 0 skipped**，exit=0；全量 **2246 tests / 6 failed / 0 errors / 8 skipped**，exit=1，不是全绿（仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过，本轮未重跑旧 SHA 基线）。全部 `--offline --rerun-tasks`，日志 `logs/pr4-review20-*` 只留本机。

仍未完成（可离线）：`selectedState` 在查询/绑定入口的联动负向矩阵、`expectedState` 通用终态校验、在线 ToolRouter 同门禁（阶段 2）、对象身份适配。实机（selected 暴露、真实 tab 条布局、抢焦点恢复、计时器唯一对象绑定）与上面第 3 条的证据采集需用户**重新授权**。未构建/安装 APK、未操作手机、未调用付费 API、未 delegate、不改受保护规则、不 push main、不合并。

## 第⑲轮局部修复：第十八轮审查（导航栏容器边界 + 容器不可点；源码 + JVM，未实机）

`UiAutomator.navControl` 不再把窗口根/正文容器当导航栏：从 control 父节点起爬，`isNavBar` 要求非窗口根、peer 标签深度极差 ≤1、容器子树 ≤40 节点、≥2 个不同 peer 标签；缺证据返回 null。`clickTabControl` 先判 `cur === container` 即拒绝，容器永不点击；`selectedState` 只读容器之前的祖先，容器自身 `isSelected` 不作证据。新 `ClockNavBarBoundaryTest` 6 项，旧实现有效 RED 6 tests / 5 failed（断言失败），修后 6/0，其中「真实 tab 条仍可点击选中」为控制组、修前已过。旧 fixture 补「窗口根在 tab 条之上」结构，断言未删弱。

定向 semantics.* + tools.*：144 tests / 0 failed / 0 errors / 0 skipped，exit=0。全量 2234 tests / 6 failed / 0 errors / 8 skipped，exit=1，不是全绿；失败仍是 AgentSkillCatalogTest 1 项、MemoryStoreTest 5 项，未改未跳过，本轮未重跑旧 SHA 基线。日志 logs/pr4-review19-{red1,green1,targeted,targeted2,full}.txt 只留本机。

仍可离线推进：真实三星 tab 条布局下的层级假设（本条启发式仍非已证明的导航身份）、查询/绑定端 selectedState 联动负向矩阵、对象身份适配、阶段2 在线 ToolRouter 同门禁。实机（selected 暴露、抢焦点恢复、计时器唯一绑定）需用户重新授权。未构建安装 APK、未操作手机、未调用付费 API、未 delegate、不改受保护规则、不 push main、不合并。需要复审。

## 第⑰轮局部修复：审查 6543364579（源码分析 + JVM，未实机）

clickClockTab 在遍历前检查 root 包名、点击前检查目标节点包名，非时钟包或包名缺失安全拒绝；初始/查询恢复/绑定恢复共用该入口。新增私有导航入口的 root跨包与node跨包零点击回归（反射调用真实方法，Android边界MockK）；有效 RED 1 test / 1 failed，修后两种组合均通过。该测试不是完整查询/绑定集成负向矩阵。

定向 --offline --rerun-tasks：131 tests / 0 failed / 0 errors / 0 skipped，exit=0。全量2221 tests / 6 failed / 0 errors / 8 skipped，exit=1，不是全绿；AgentSkillCatalogTest 1、MemoryStoreTest 5，本轮未重跑旧SHA。初次定向131/3 failed为旧fixture未提供包名，仅补root/node包名后原断言全保留。日志logs/pr4-review17-{red,targeted,full}.txt及JSON本机保留。

仅完成包名门禁切片：通用click的父级/坐标回退仍待移除，精确标签仍缺导航控件身份、selectedState同名非导航节点拒绝仍待修；审查未关闭，仍可离线推进。源码/JVM不等于实机，历史设备矩阵不变；新一轮实机需用户重新授权。本轮未构建安装APK、未操作手机、未调用付费API、未delegate、不合并、不push main、不动Issue6。需要复审。


## 第⑯轮局部修复：审查 6543146968（源码 + JVM，未实机）

移除 selectedState 的包含匹配回退；仅提示卡且父 selected=true 时返回 null。旧兼容用例收紧为拒绝，新增提示卡父选中回归。有效 RED：9 tests / 2 failed / 0 errors；最终定向130 tests / 0 failed / 0 errors / 0 skipped；全量2220 tests / 6 failed / 0 errors / 8 skipped，不是全绿。全部 --offline --rerun-tasks；日志 logs/pr4-review16-{red,targeted,full}.txt，失败名称 JSON 留本机。失败仍为 AgentSkillCatalogTest 1项、MemoryStoreTest 5项，本轮未重跑旧SHA。

仅完成模糊选中证据切片，不代表审查关闭：精确标签仍缺导航容器身份，外来窗口导航点击、通用父节点/坐标回退仍待离线修复；查询/绑定端真实 selectedState 联动负向矩阵尚未补齐。仍有离线任务，不是只能等待实机。未构建安装APK、未操作手机、未调用付费API、不合并、不push main、不动Issue6。新实机需用户重新授权。需要复审。


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



## 第⑬轮局部更新（源码分析 + JVM；未实机）

处理新复审 `6541929671` 的绑定恢复切片：`verifiedClockWindow` 在发生窗口恢复后，只使用最终绑定树的 selected，null 不再回退窗口切换前的 true，而是安全拒绝。新增真实 cancelTimer 调用回归：旧 selected=true、外来窗口抢焦点、恢复树 selected=null，断言 DEVICE_UNAVAILABLE / verified=false / 取消节点点击0次。正向恢复 fixture 显式提供同树 selected=true，保留既有调用/点击断言，不弱化断言。

TDD 有效 RED：12 tests / 1 failed；最终定向（`*Clock*Test`、TimerObjectExecutionTest、IntentGateTest、SemanticExecutionTest，`--rerun-tasks`）85 tests / 0 failed / 0 errors / 0 skipped。全量2201 tests / 6 failed / 0 errors / 8 skipped，Gradle exit=1，不是全绿；失败AgentSkillCatalogTest 1项、MemoryStoreTest 5项，未重跑旧SHA基线。首次定向85项有2项旧恢复fixture缺少新同树证据而失败，补证据后重跑全绿。日志 `logs/pr4-review13-{red,targeted,full}.txt`、汇总JSON本地保留。

**仅局部修复，复审未关闭**：查询恢复仍使用旧 reach.selected；未发生恢复的绑定仍保留旧 selected 回退；初始模糊 tab 点击兜底仍待移除；查询/绑定恢复 false、点击失败及 closeAlarm 独立负向矩阵仍待补。仍有离线工作，不是只能等待实机。未构建安装APK、未操作手机、未调用付费API、未动Issue #6、不合并、不push main。新实机需重新授权。需要复审。


## 第⑫轮更新（源码分析 + JVM；未实机）

继续处理复审 `6541088857` P1：关闭闹钟不再以 `isScrollable=false` 或完整遍历已加载树证明全局唯一。当前适配器没有完整列表枚举及业务对象身份，故即使唯一可见开关也返回 BLOCKED / verified=false，不点击、不声称目标已关闭；删除复用该安全拒绝。关闭能力当前不可用，不是对象身份能力已实现。

TDD 新增 OEM 不暴露 scrollable、视口外重复对象无节点的回归：有效 RED 11 tests / 1 failed（预期 BLOCKED 实为 SUCCESS）。修后定向 `--rerun-tasks` 110 tests / 0 failed / 0 errors / 0 skipped；全量 2200 tests / 6 failed / 0 errors / 8 skipped，Gradle exit=1，不是全绿。失败为 AgentSkillCatalogTest 1项、MemoryStoreTest 5项，未重跑旧 SHA 基线。旧两条关闭成功测试改为更严格的拒绝及点击零次，保留窗口恢复调用和错误对象不点击断言。日志 `logs/pr4-review12-{red,targeted,full}.txt` 和汇总 JSON 本地保留。首次未加载 env.sh 的 JAVA_HOME 启动失败不作为 RED。

P1 的可见对象误操作路径已失败闭合，待复审；**P2 恢复后同树页面证据、旧 selected 回退、绑定恢复独立负向回归仍待修**，继续存在离线任务。未构建安装 APK、未操作手机、未调用付费 API、未动 Issue #6、不合并、不 push main。新一轮实机需用户重新授权，既有实机矩阵不变。需要复审。


## 第⑪轮局部更新（源码分析 + JVM；未实机）

继续处理复审 `6541088857` 的 P1：绑定树中出现 `isScrollable` 时，当前唯一可见开关不再作为全列表唯一性证明，返回 BLOCKED / verified=false，且不点击开关。新增仅加载一个 08:00、另一重复对象在视口外无节点的合成树回归，保留关闭点击零次断言。TDD RED 10 tests / 1 failed；定向 `--rerun-tasks` 109 tests / 0 failed / 0 errors / 0 skipped；全量 2199 tests / 6 failed / 0 errors / 8 skipped，exit=1，不是全绿（AgentSkillCatalogTest 1项、MemoryStoreTest 5项；本轮未重跑旧 SHA 基线）。日志 `logs/pr4-review11-{red,targeted,full}.txt` 本地保留。

这是已暴露滚动结构的失败闭合切片，**不是 P1 全部关闭**：平台不暴露 scrollable 时仍缺全列表/业务对象身份证明；应继续离线处理，不把 scrollable=false 当完整性证据。P2 恢复后同树页面证据、旧 selected 回退与绑定恢复独立负向回归尚待修复。源码/JVM 不等于实机，未构建安装 APK、未操作手机、未调用付费 API、未动 Issue #6、不合并、不 push main。新实机需用户重新授权；需要复审。


## 第⑩轮局部更新（源码分析 + JVM；未实机）

处理复审 `6541088857` 的 P1 否定结论切片：当前可见区域未找到目标，不再声称“没有这个闹钟/不用关闭”，改为“不能确认不存在，没有执行关闭”。新增未加载滚动区域目标回归：有效 RED 7 tests / 1 failed。既有 Runner 空态回归改为更保守的范围反馈断言，保留读取一次、创建零次、通用点击零次断言。最终定向 `--rerun-tasks` 108 tests / 0 failed / 0 errors / 0 skipped；全量 2198 tests / 6 failed / 0 errors / 8 skipped，exit=1，不是全绿（AgentSkillCatalogTest 1项、MemoryStoreTest 5项；本轮未重跑旧SHA基线）。日志 `logs/pr4-review10-red.txt`、`pr4-review10-targeted.txt`、`pr4-review10-full.txt` 本地保留。

**仅修复不可证明不存在的播报，不关闭 P1**：可见目标仍缺全局唯一身份，关闭路径的全局唯一性防护、视口外重复目标回归仍待处理。P2 恢复后同树页面证据、移除旧 selected 回退、绑定恢复独立负向回归仍待处理。仍有离线任务，不是只能等待实机。未构建/安装 APK、未操作手机、未调用付费 API、未动 Issue #6；新实机需重新授权。需要复审，阶段1未整体通过、不合并。


## 第⑨轮局部更新（源码分析 + JVM；未实机）

继续处理审查 `6540570445`：两条错窗口恢复路径 `readClockScreen` / `verifiedClockWindow` 改为 `clickClockTab(tab)`，恢复时不再退回 contains 模糊点击。新增 VoiceActivity 窗口→重开时钟→提示卡位于真正 tab 之前的查询回归，要求点精确 tab 且不点提示卡；有效 RED 6 tests / 1 failed，修后定向 107 tests / 0 failed / 0 errors / 0 skipped（`--rerun-tasks`）。全量 2197 tests / 6 failed / 0 errors / 8 skipped，Gradle exit=1，失败仍为 AgentSkillCatalogTest 1项、MemoryStoreTest 5项；未重跑旧SHA基线，不称全绿。日志 `logs/pr4-review9-tab-red.txt`、`pr4-review9-targeted.txt`、`pr4-review9-full.txt` 本地保留。

**仅完成精确重试点击切片，审查未关闭**：P1 全列表对象唯一性仍待修；P2 恢复后同树页面证据与移除旧 selected 回退仍待修，当前不能把重试点击修复等同页面身份已证明。绑定路径这次只同步同一精确点击实现，未新增该路径独立回归。未构建/安装 APK、未操作手机、未调用付费 API；设备验收仍需用户重新授权。阶段1未整体通过、不合并。

## 第⑧轮局部更新（2026-10-31，源码分析 + JVM；未实机）

处理最新复审 `6540570445` 的隐私日志切片：`closeAlarm` 不再向 Logcat 写目标时间/整页文本，`IntentActions.setTimer` 不再写整页文本，只保留计数与非敏感状态。新增两条实际能力调用测试，用私密标记断言 Log.i 不泄漏；分别先 RED（1/4、1/7 失败）后 GREEN。定向 106 tests / 0 failed / 0 errors / 0 skipped；全量 2196 tests / 6 failed / 0 errors / 8 skipped，Gradle exit=1，不是全绿。失败仍为 AgentSkillCatalogTest 1项 + MemoryStoreTest 5项，本轮未重跑旧SHA基线。

**复审尚未全部处理**：同评论 P1 可滚动闹钟列表的全局唯一性、P2 恢复路径精确 tab 点击/新树证据仍待修复；本轮仅完成隐私切片，不能据此批准合并或声称关闭闹钟安全验收通过。未构建/安装 APK、未操作手机；新一轮实机仍需用户授权。第⑦轮设备矩阵不变。日志 `logs/pr4-review8-*-red.txt`、`logs/pr4-review8-targeted.txt`、`logs/pr4-review8-full.txt`（本地，不提交）。

最后更新：2026-10-31（第⑦轮：同一窗口校验门 + 截断不再当完整 + tab 精确匹配；JVM 全量 + 真机只读复测）

本轮最新状态（第⑦轮，源码分析 + JVM 单测 + 真机只读复测）：处理复审 `6539848799` 对 head `3253319` 的三项遗留。

1. **P1 同一窗口校验门（已修，JVM）**：`cancelTimer` / `closeAlarm` 原来在 `openClockTab` 之后各自 `UiAutomator.root()` 扫描并绑定节点，`foregroundPackage()` 是另一次读——检查能通过，但绑定/点击用的是别的窗口的树。现两处都走 `verifiedClockWindow(ctx, tab, reach)`：返回的根**就是**通过 `packageName == com.sec.android.app.clockpackage` 校验的那个对象，并复用 `readClockScreen` 的“重开时钟 + 重选 tab”有界重试；无法确认即 `DEVICE_UNAVAILABLE`（“无法确认当前是时钟窗口”），绝不说“目标不存在”、绝不点击。点击前再加两条：被点的节点自身 `packageName` 必须是时钟；当前活动窗口仍属时钟。无坐标、无盲点。
2. **P2 截断不再变确定结论（已修，JVM）**：`timerCountFrom` 的「添加定时器」空态标记移到截断闸门之后（截断时只有「N 个定时器正在运行」这种陈述式文本可用）；`queryAlarms` 除“截断即不可读”外，还要求页面**结构上**是闹钟列表（tab 报告选中 / 页面存在闹钟开关，新增 `ScreenRead.checkableCount` / 明确空态），世界时钟的 `H:mm` 行不再被枚举成闹钟；`closeAlarm` 在回答“没有X点的闹钟”前同样要求该证据。
3. **tab 到达语义复核（已修，含真机证据）**：第六轮后已无“标签可见即到达”的路径（点击失败仍算 `click-failed-label-only`）。但真机复测暴露新问题：`clickText("闹钟")` 与选中态读取都用了“包含”匹配，命中了闹钟页的提示卡「对关联闹钟进行分组」——于是 tab 有时没切、且已选中的 tab 被报成未选中。现改为**精确标签优先**（`selectedState` 与新增 `clickClockTab`），包含匹配仅作回退。

测试（真实执行，先 RED 后 GREEN）：`ClockWindowGateTest`(8) 在上一提交上 4 项红（外来窗口绑定计时器/闹钟、节点属于别的应用、截断下的「添加定时器」、无闹钟结构证据的时钟页），修后全绿；`ClockAlarmPageEvidenceTest`(6) 钉住新证据契约与窗口恢复；`TabSelectedStateTest` +2、`ClockWindowGateTest` +1 先红后绿（提示卡被当成 tab、tab 未被点击）。定向 101/101 通过；全量 `:app:testDebugUnitTest` **2194 tests / 6 failed / 8 skipped / 0 errors**，失败仍是既知 `AgentSkillCatalogTest`(1) + `MemoryStoreTest`(5)，**不是全绿**。

真机复测（SM-F731U1 / Clock 12.5.60.27，APK SHA256 `c42d7222…d00347a`，`install -r` 后无障碍仍 Bound、`voice_interaction_service` 未改）：ALARM QUERY 3 次 → 2 次 `verified=true visible=[6:00, 7:30, 8:00, 9:20, 10:00, 11:00] tab=clicked checkables=6 rootPkg=clock`，1 次 `verified=false tab=clicked-not-selected visible=[]`（宁可拒读也不报数）；TIMER QUERY 空态 `verified=true`（`nodes=44`），自建 2 分钟倒计时后 `count=1 verified=true`（`nodes=27`）；对自建对象执行 `取消` → `BLOCKED candidates=0 complete=true`（未点击、失败闭合）；测试对象已跑完并用 `ACTION_DISMISS_TIMER` 结束，终态查询回到空态。

残余：`selected` 在本机是否稳定暴露仍未确定（2/3 次读为 `tab=clicked`，1 次 `clicked-not-selected`），因此**到达判定仍非 100% 确定性**，已如实写进 `detail`；取消/关闭路径在真机仍为 BLOCKED（无「初始时长」证据），矩阵维持 通过 4 / 失败 0 / 未测 3。

上一轮状态（第④~⑥轮，源码分析 + JVM）：按复审 `6539752681` 的 P2 修 `openClockTab` 的假到达——旧逻辑 `clickText(tab) || waitForText(tab, 800)` 在点击失败时只要标签还可见就算到达，而时钟四个底部标签每页都在。现拆成三个事实（导航控件存在 / 点击已投递 / 目标 tab 报告选中）：`UiAutomator.selectedState` 返回 `true / false / null`，`null`（平台不暴露选中态）与 `false`（明确未选中）不再混同；读取前用 `onTargetPage` 复核“时钟包名 + 该 tab 未报告未选中”。`closeAlarm` 不再读原始活动窗口，外来窗口一律 `DEVICE_UNAVAILABLE`，不把“读不到”说成“没有这个闹钟”。同轮按复审 `6539848799` 补齐：`closeAlarm`/`cancelTimer` 在 scan 前与 click 前各复核一次前台包名（窗口变了就拒绝、不点击），截断读取不再产生确定计数/确定空态。新增 `ClockTabReachTest`(6) + `TabSelectedStateTest`(6) + `ClockWindowBindingTest`(6)，先 RED 后 GREEN；定向 74/74；全量 2177 tests / 6 failed / 8 skipped / 0 errors（仍是既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，与基线一致，不是全绿）。第六轮同样未上机；`selected` 是否暴露当时未验证（第⑦轮已部分实测）。
上一轮状态（第③轮，实机验证）：按 PR #4 复审 `6539505148` 先采只读证据再修。根因是**读到了 ClosePaw 自己的窗口**：`VoiceActivity` 全屏窗口中途抢焦点，`rootInActiveWindow` 变成 `ai.closepaw nodes=3`，旧代码点完 tab 固定 sleep 500ms 就读且不校验根节点包名，于是把空窗当“时钟页不可读”；次因是 `runningTimerCount` 只认「添加定时器/正在运行」，而本机空态是 `小时/分钟/秒 + 开始` 选择器、运行态是 `取消/暂停` 卡片。已按 TDD 修复并复测：ALARM QUERY `verified=true readable=true`，TIMER QUERY 1 个运行→`count=1`、清理后→0（verified=true）。构建 APK SHA256 `45e74c03…b60a2333`，install -r 后无障碍仍 Bound。矩阵：通过 4 / 失败 0 / 未测 3；计时器取消绑定、五分钟工具参数、取消不存在目标、undo 仍缺安全对象证据。

更早（第②轮）：按复审 P1 做了源码级可观测性改造（`readScreen` 报告 `nodesVisited/nodesTruncated/textsTruncated/rootPkg`，`ClockControl` 报告 tab 到达方式），当时未实机、根因未定位。
上一轮状态（第②轮）：解锁已读回 deviceLocked=0，无障碍仍Bound，安装APK哈希与2ff514f构建记录一致。两商店身份及二级页不虚报通过；时钟查询业务失败，其他4项安全条件不足未测。未重建/改源码/触碰个人时钟对象。详见 docs/phase6-stage1-device-acceptance.md 第②轮。

上一轮部署记录：源码SHA 2ff514ff0b333f9dafe6947685c059fff91cf475；原APK/配置已本地备份，安装前后shared_prefs归档相同，无障碍均已绑定。未绕过锁屏、未改高风险设置、未调用付费API。详见 docs/phase6-stage1-device-acceptance.md。阶段1不是已通过，阶段2/3未推进。
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
| 6 | 语义闸门 + 把自研能力包成 ClosePaw ToolSpec | **进行中（阶段 1：第③轮时钟读取已实机修好；第⑦轮同一窗口门/截断/tab 精确匹配已修，JVM 全绿定向 + 真机只读复测；取消/关闭仍 BLOCKED 未测）** | `docs/phase6-stage1-semantic-gate.md`（源码 + JVM 单测）、`docs/phase6-stage1-device-acceptance.md`（实机） |
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

（第㊷轮，离线，源码 + JVM，未实机）本轮处理 **PR #4 新复审 `6552469328`**（针对 HEAD `4d23e5c`，2026-11-01T02:43:31Z）的 3 条意见。上一轮（㊶）把它误判成「无新审查」，实际是复审与㊶评论几乎同时到达后漏读——本轮更正并补上。**P1/P2 点击身份**：`featureControlOnce` 仍保留 `direct.size == 1` 优先返回，即「自身标签精确」仍能直接胜出；可点击帮助卡片自身 `text="扫一扫"`、真入口 `contentDescription="扫一扫，打开相机"` 时，前者被点。这和第㊵轮修的是同一个错误的下一层：精确文本不是业务身份，而 resource-id/类名/角色在没有真机证据前都不带业务语义。改为**只有唯一可点击目标才点**，两个不同可点击控件都答得上名字即 `ambiguous` 并零点击（`direct` 层删除）。真实代价：入口旁只要有可点帮助卡片就一律拒点，这条可用性损失如实记录，不做调参。**P2 动作边界的身份绑定**：`featureControlOnce` 只重查包名与候选数，同包同窗口换页后 B 页若有唯一同名控件仍会被点。新增 `UiAutomator.clickIdentityMiss`，按序核对 target 的 `viewIdResourceName`、`className`（角色）、label、两次 root 的 `windowId`，以及**页面结构指纹** `pageSignature`（按遍历序拼接每节点 class+viewId，**不含任何文本**，因此计时器跳动不会破坏它；撞 600 节点预算则返回 null 视为不可证）；任一不成立即 `DEVICE_UNAVAILABLE`、`detail` 记 `beyond-scan-identity=<原因>`、零点击。`AppNavigator` 在点击前插入此闸。边界写清：结构指纹是**反换页证据而非页身份证明**，同骨架同 id 的两页会碰撞；无 view-id 的界面（Compose/WebView）只剩 class+label 一层，弱于资源 id；真实 App 是否暴露这些信号**未验证**。**P2 测试有效性**：`SemanticExecutionTest` 桩住的是 `featureControl(String…)` 重载，生产走 `List<String>` 重载，所以「not-found」从未注入、测试以错误原因通过。改为 `capture` 桩 `List<String>` 重载并 `verify(exactly = 1)` + 断言捕获到「扫一扫」，证明别名集真的到达了真实重载。TDD 与实跑：新 `AppFeatureClickBindingTest`(7) 先写、生产未改 → **RED 17 tests / 5 failed / 0 errors**（Truth 断言失败，非编译错误：帮助卡片自身精确 1、`扫一扫设置` 对照 1、换页 1、资源 id 变化 1、windowId 变化 1；2 条控制组本就通过）；实现后同组 **GREEN**，随后 `ai.closepaw.voice.semantics.*` **252 tests / 0 failed / 0 errors / 0 skipped**（exit=0）；定向 `ai.closepaw.voice.*` **355 tests / 0 failed / 0 errors / 0 skipped**（exit=0，第㊶轮 348，+7）；全量 `:app:testDebugUnitTest` **2445 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**（失败仍是既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过，未重跑旧 SHA 基线）。旧断言按新规则收紧而非放宽：`AppFeatureIdentityTest`、`AppFeatureControlAmbiguityTest` 里两条「精确标签胜出」改为断言 ambiguous + 双方零点击；两条只给桩控制、未给身份信号的夹具补上 viewId/className 与 window 快照（否则新闸会一律拒绝，测试将以错误原因变红）。边界：全部为源码 + JVM 合成树；三星微信/时钟真实资源 id、可点击帮助卡片是否存在、`windowId` 在同 Activity 换 Fragment 时是否变化、结构指纹在真实大页面上的截断率**均未实机**；本 SHA **未构建、未安装、未实测**，实机需用户**重新授权**。`closeAlarm` 仍 BLOCKED；计时器创建/取消对象级终态仍 UNPROVEN；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。本轮持 `O_CREAT|O_EXCL` 写者锁，结束仅删自有锁；未 push main、未合并。

（第㊶轮，离线，源码 + JVM，未实机）本轮 **PR #4 无新审查**（最新复审 `6551941593` 已由评论 `6552207011` 回复，其后无新评论），按「无新审查则推进可离线项」推进任务清单里的**计时器唯一绑定**剩余一半。**缺陷**：`ClockControl.cancelTimer` 把 `complete`（只在 800 节点预算耗尽时才为 false）当作候选全集，`isScrollable` **从未读过**——同文件的 `closeAlarm` 早已把可滚动列表判为「不是全列表」直接 BLOCKED。可滚动计时器列表里视口下方可能还有一张同时长卡片：手机真有两张 5 分钟倒计时时，旧代码只看见一张，`matching.size == 1` 通过，点掉视口那张并播「已请求取消指定倒计时」。**改法**：区分「页面声明的总数」与「视口卡片数」——`statedTotal = timerCountFrom(boundTexts, truncated = !complete)`；`viewportComplete = complete && !scrollable`；`coveredByPageTotal = statedTotal >= 0 && candidates.size >= statedTotal`；两者都不成立才 `BLOCKED` + 零点击，措辞按事实分「列表可以滚动」/「读取被截断」两种，`detail` 记 `complete/scrollable/stated`。这样可滚动但页面给了总数且绑定数覆盖时仍能取消，不是一刀切拒绝。TDD：新 `TimerCancelViewportCompletenessTest`(5) 先写、生产未改 → **RED 5 tests / 2 failed / 0 errors**（`expected: BLOCKED but was : SUCCESS`，旧代码在两种情形下真的点了取消；3 条控制组本就通过）；实现后 **GREEN 5/5**，exit=0。定向 `ai.closepaw.voice.*` **348 tests / 0 failed / 0 errors / 0 skipped**（exit=0，第㊵轮 343，+5）；全量 `:app:testDebugUnitTest` **2438 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**（失败仍是既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过，未重跑旧 SHA 基线）。边界：真机三星定时器页是否上报 `isScrollable`、页头「N 个定时器正在运行」实际文案是否只统计运行中，**均未验证**；若上报可滚动却从不给总数，`cancelTimer` 会全部拒绝（真实可用性代价）；「卡片数 ≥ 总数」只证数量覆盖、不证绑定对象就是用户说的那个，回读仍是 `UNPROVEN`；夹具是我自建的合成树。`closeAlarm` 仍 BLOCKED（安全拒绝）；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。本轮未构建/安装 APK、未操作手机、未调用付费 API、未合并 PR、未 push main。

（第㊳轮，离线，源码 + JVM，未实机）按新复审 `6551213132`（针对 HEAD `86e9830`，2026-11-01T00:19:03Z）处理 1 条 P1 + 1 条 P1/P2 + 1 条 P2。**P1 时长参数完整性**：`ChineseTime.durationSeconds` 每类正则只 `find` 一次、消费后不检查剩余文本，于是「五分钟或者十分钟」→300 s（「或者十分钟」没读）、「十分钟和一分钟」→600 s（第二个被丢）、「一个半分钟」→30 s（`半分钟` 抢先命中，留下「一个」，本意 90 s）。现在新增 `($NUM)个?半(?:分钟|分)` 复合形（一个半分钟=90、两个半分钟=150），排在裸 `半分钟` 前；并把墙钟形 `N点(M)` 先剥掉，再用 `RESIDUAL_DURATION`（`N+小时/钟头/分钟/分/秒`）检查残留——只要还剩第二个时长就返回 `null`，调用方追问。`IntentGate` 用同一函数，所以下游拦不住，负向断言落在**工具入参**上。**P1/P2 点击前窗口归属**：`launch` 的前台验证在读屏之前，读屏只查 `serviceAvailable`，随后 `findNode(feature)` 在活动窗口里按包含匹配取首个节点、点完才查前台。新增 `UiAutomator.featureControl(pkg,text,timeout): FeatureLookup(control, why)`——root 包名必须等于目标包，命中节点自身也必须同包且 `isVisibleToUser`，点击目标取标签自身或**同包内**最近可点祖先（跳数有上限、不越窗口根），失败返回具名原因 `window-not-target(pkg)`/`not-found`/`label-not-clickable`/`no-window`；`openFeatureResolved` 读屏后先比对 `before.rootPackage`，不符即拒绝，命中后只对绑定节点 `performAction(ACTION_CLICK)`，不再用会父级上溯/坐标兜底的 `UiAutomator.click`。**P2 截断时的「找不到」**：`control == null` 且 `before` 被截断时改播「屏幕读取被截断，没法确认有没有X」，完整读取才保留「界面上没有找到X」。TDD：新 `ChineseTimeAmbiguityTest`(10)+`AppFeatureWindowIdentityTest`(6)+`TimerAmbiguousDurationArgumentTest`(3) 先写、生产未改 → **RED 19 tests / 14 failed / 0 errors**（断言失败非编译错误，6 条控制组本就通过）；实现后 GREEN，exit=0。定向 `ai.closepaw.voice.*` **315 tests / 0 failed / 0 errors / 0 skipped**（exit=0，第㊲轮 296，+19）；全量 **2405 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**（仍为既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过）。两处自身问题如实记录：窗口用例复用同一份读屏桩导致走成 TIMEOUT（改为第二次读有新文本）；截断措辞初版含「没有找到」与 `doesNotContain("没有找到")` 冲突，改的是生产句子而非断言。`SemanticExecutionTest` 与 `AppFeatureReadCompletenessTest` 的原断言未改，只补新路径所需桩。边界：真机上 `root.packageName`/`isClickable`/`isVisibleToUser` 的表现、权限弹窗与分屏场景全部**未实机**；`openFeature` 完整读取下仍只播「界面有变化，但没能确认已经进入」、`verified=null`；`closeAlarm` 仍 BLOCKED（安全拒绝）；计时器创建/取消对象级终态仍 UNPROVEN；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。本轮未构建/安装 APK、未操作手机、未调用付费 API、未合并 PR、未 push main。

（第㊲轮，离线，源码 + JVM，未实机）本轮**无新审查**（最新复审仍是 `6550162379`，已由评论 `6550446557` 回复；其后 PR 无新评论），按「无新审查则推进可离线项」继续第㊱轮同一类问题——**把「我没采到」讲成「它不在」**，本轮落到剩下两个出口。**其一 `IntentActions.setTimer`**：发完 `ACTION_SET_TIMER` 后只读一次屏（`readScreen(80)`，上限 600 节点 / 80 文本），撞上限时 `countdown` 仍算 `false`，于是播「我在时钟界面里没看到倒计时」——用不完整采样断言时钟 UI。改为三态：找到运行证据→`true`；页头明确「0 个定时器正在运行」→`false`（显式 0 是关于页面的肯定证据，截断不能抹掉）；读取被截断→`null`；完整读取且没找到→`false`。新增 `pageOk && countdown==null` 分支：「…但屏幕读取不完整，没法确认新倒计时是否开始」，`verified=null`，`detail` 记 `countdown-ui=unknown readClipped=…`。**其二 `IntentActions.showAlarms`**：旧的 `runCatching{}.getOrNull()` 把「`nextAlarmClock` 返回 null」和「调用抛异常」合成同一个 null，两者都播「现在没有设置闹钟」且 `verified=true`——读到 null 只证明**当前没有已启用的闹钟**（禁用中的闹钟读出来一样），抛异常则是**读失败被说成空手机**。新增 `nextAlarmProbe(ctx): NextAlarm(info, known)`，`nextAlarm(ctx)` 保留原签名（`= probe.info`）不动其他调用方；播报改为读不到→「读不到系统的闹钟状态，没法确认有没有闹钟」+`verified=false`，读到 null→「系统里没有下一个已启用的闹钟」，`detail` 加 `nextKnown=`。TDD：新 `TimerReadTruncationTest`(5) + `AlarmAbsenceWordingTest`(3) 先写、生产未改 → **RED 8 tests / 4 failed / 0 errors**（Truth 断言失败，非编译错误；文本截断、节点截断、闹钟 null 措辞、闹钟读取失败各 1；4 条控制组本就通过）；实现后同组 + `TimerDiagnosticsEvidenceTest` **GREEN，exit=0**；定向 `ai.closepaw.voice.*` **296 tests / 0 failed / 0 errors / 0 skipped**（exit=0，上轮 288，+8）；全量 `:app:testDebugUnitTest` **2386 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**（失败仍是既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过，未重跑旧 SHA 基线）。过程中一次假失败：前台命令被 180 s 截断后残留 Gradle/Kotlin 守护进程占住 `R.jar`，`processDebugResources` 报 `Couldn't delete R.jar`；停守护进程并删文件后干净重跑，上面的数字来自重跑。边界：「完整」仍只指没撞上 600 节点/80 文本预算，不等于看到整屏（可滚动/虚拟化列表仍可能没枚举完）；`showAlarms` 现在**不再枚举闹钟列表**、也不再宣称「没有设置闹钟」，禁用中的闹钟仍需 `ClockControl.queryAlarms` 或后续全列表枚举；真机截断率、三星时钟节点规模、`nextAlarmClock` 何时抛全部**未实机**。`closeAlarm` 仍 BLOCKED（安全拒绝）；计时器创建/取消对象级终态仍 UNPROVEN；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。本轮未构建/安装 APK、未操作手机、未调用付费 API、未合并 PR、未 push main。

（第㊱轮，离线，源码 + JVM，未实机）本轮**无新审查**（最新复审仍是 `6550162379`，HEAD `1a77501`，已由评论 `6550446557` 回复；其后无新评论，HEAD 维持 `af8e901`），按「无新审查则推进可离线项」处理遗留清单里的**「查询结果截断未当作不完整」**，落点是 `AppNavigator.openFeatureResolved`（打开 App 内功能）：它判定「进了那个页面」用的是 `UiAutomator.screenTexts(120)`——`readScreen` 的投影，`textsTruncated`/`nodesTruncated`/`serviceAvailable` 在投进去时被丢掉。于是三个无证据结论都能出口：(a) 两次截断样本做集合差非空就播「界面有变化」（差集里的文本可能本来就在，只是上次没采到）；(b) 两次采样相同就播「界面没有明显变化」并返回 `TIMEOUT`（变化可能落在没走到的部分）；(c) 无障碍服务不可用时 `readScreen` 的空投影被播成「界面上没有找到X」，这是对 App UI 的断言，而事实是看不到 UI。改法：`before`/`after` 改取完整 `ScreenRead`，新增 `incompleteWhy()` 给出具名原因（`before-truncated`/`after-a11y-down`/`after-truncated`，全完整才 null），判定顺序改为「跳出 App（`EXECUTION_ERROR`，前台包名证据不受文本读取影响）→ 读取不完整（`SUCCESS` + `verified=null` + 「点了X的F，但屏幕读取不完整，没法确认是否进了那个页面」，`detail` 记两次读取的 `texts/nodes/truncated/pkg`）→ 有变化（原句，`verified=null`）→ 无变化（`TIMEOUT`）」；`serviceAvailable=false` 直接 `DEVICE_UNAVAILABLE` +「无障碍服务不可用，看不到X的界面」。既有断言一条未放宽，控制组播报不变。TDD：新 `AppFeatureReadCompletenessTest`(6) 先写、生产未改 → **RED 6 tests / 3 failed / 0 errors**（Truth 断言失败，非编译错误；3 条控制组本就通过；首跑还有 1 条失败是我自己控制组断言写错——原句含「已经进入」而断言用的是 `doesNotContain("已经进入")`，改的是测试断言不是生产句子，如实记录）；实现后 **GREEN 6 / 6**，exit=0。定向 `ai.closepaw.voice.*` **288 tests / 0 failed / 0 errors / 0 skipped**（exit=0，上轮 282，+6）；全量 `:app:testDebugUnitTest` **2378 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**（失败仍是既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过，未重跑旧 SHA 基线）。边界：「完整」只指没撞上 600 节点/120 文本预算，**不等于看到整屏**（可滚动/虚拟化列表仍可能没枚举完）；真机截断率、各 App 节点规模、无障碍被杀表现全部**未实机**；完整读取下 `openFeature` 仍只播「界面有变化，但没能确认已经进入」、`verified=null`，没有新增对象级证据。`closeAlarm` 仍 BLOCKED（安全拒绝）；计时器创建/取消对象级终态仍 UNPROVEN；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。本轮未构建/安装 APK、未操作手机、未调用付费 API、未合并 PR、未 push main。

（第㉟轮，离线，源码 + JVM，未实机）按 PR #4 新复审 `6550162379`（针对 HEAD `1a77501`，2026-10-31T22:46:52Z）处理 2 条 P1，**没有合并、没有构建或安装 APK、没有操作手机**。**P1-1 手电证据无法归因**：`onTorchModeChanged` 把**每个**事件（包括注册回调时 CameraService 推送的初始态）写进 `observed`，`set()` 也把 `confirmed` 丢掉、`outcome` 只看 `observed == on` 就返回 `verified=true`；于是「本来是开着的」能说成「我打开了」，请求后又收到 `onTorchModeUnavailable` 也照样报成功，新结构化 `observed` 还把假成功升格成 `TerminalState` 的 `CONFIRMED`。改法：新增 `TorchControl.RequestTracker` 明确归属契约——`markRequested()` 之前的事件只作 baseline、绝不当结果；`onTorchModeUnavailable` 只在请求后才有意义；`set()` 先有界等待 baseline（`BASELINE_TIMEOUT_MS=1200`）再 `setTorchMode`，把 `confirmed && tracker.confirmed` 交给 `outcome`；`outcome` 现在 `unavailable` 一律优先（成功之后又 unavailable 也不报成功），无 post-request 确认即 `TIMEOUT` + `verified=null` + 无 `observed`。**P1-2 时长解析**：`半分钟`→1800 s、`一个半小时`→1800 s、`1.5小时`→18000 s 三个口语仍在（`IntentGate` 用同一解析器交叉比较，两侧一起错所以拦不住）。`ChineseTime.durationSeconds` 改为**按整段短语消费**：优先复合形（`N个半小时`、`N小时半`、`半分钟`、`N分半`），数字支持小数，消费过的片段从待匹配串里移除，无法读出的数词整句返回 `null`（追问答话，不给后缀读数）。TDD：新 `ChineseTimePhraseUnitTest`(5) + `TimerCompoundDurationArgumentTest`(3，真实 `parse → 能力表 → IntentGate → SemanticRunner → ClockControl.createTimer` 入参) + `TorchAttributionTest`(9)，先写测试、只加 `confirmed/baseline` 参数与 `RequestTracker` 骨架（行为仍旧）：**RED 31 tests / 12 failed / 0 errors**，全部 Truth/MockK 断言失败，无编译错误（`ChineseTimePhraseUnitTest` 4/5、`TimerCompoundDurationArgumentTest` 3/3、`TorchAttributionTest` 5/9；控制组 `ChineseTimeDurationTest` 4/4、`SwitchReadbackEvidenceTest` 10/10 本就应绿）；实现后同组 **34 / 34 GREEN，exit=0**。定向 `ai.closepaw.voice.*` **282 tests / 0 failed / 0 errors / 0 skipped**（exit=0，上轮 265，+17）；全量 `:app:testDebugUnitTest` **2372 tests / 6 failed / 0 errors / 8 skipped**，Gradle **exit=1，不是全绿**（失败仍是既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过，本轮未重跑旧 SHA 基线）。边界：手电回调真实时序（JVM 无 Looper）、Wi-Fi 既有回读、三星时钟是否接受这些秒数全部**未实机**；`closeAlarm` 仍 BLOCKED（安全拒绝）；计时器创建/取消对象级终态仍 UNPROVEN；在线 `ToolRouter` 同门禁（阶段 2）未开始；Issue #6 未并入。

（第㉘轮，离线，源码 + JVM）按新复审 `6547564219`（HEAD `cdf52b1`）修 1 条 P1 + 2 条 P2。先更正：上一轮文末写「最新已审 SHA 为 `2048f6d`」是错的，`6547520665` 已审 `67fa299`、`6547564219` 已审 `cdf52b1`，本轮按后者推进。**P1**：`TerminalState.enforce` 此前只改写 `Action.OPEN` 的 UNPROVEN，`CREATE/CANCEL/SET` 的 UNPROVEN 仍原样返回 `SUCCESS + verified=true` 和「已完成」句；现在所有承诺终态的动作在 UNPROVEN 且 `verified=true` 时统一改写为「请求已经发出，但没能确认结果，请你看一眼」、`verified=null`、`detail += "; postcondition=unproven(no object evidence)"`，幂等。**P2-1**：`IntentActions.setTimer` 不再用 `screenTexts` 把任意屏文本当证据，改为 `readScreen(80)` 并只在「同一次读回 `rootPackage == com.sec.android.app.clockpackage` 且 `selectedTabs["定时器"] == true`」时给出 `countdown-ui=true|false`，否则 `countdown-ui=unknown`（`page=unconfirmed`）——`ACTION_CLICK` 返回 true 只是动作投递，VoiceActivity 抢焦点是历史实证。**P2-2**：`contains("定时器正在运行")` 曾把「0 个定时器正在运行」判成运行中；改为解析 `(\d+)\s*个\s*定时器正在运行` 并要求 `N > 0`，显式 0 为 `countdown-ui=false`。TDD：新 `TerminalUnprovenMutationTest`(7) + `TimerDiagnosticsEvidenceTest`(6) 对旧实现 **RED 13 tests / 6 failed**（均为 Truth 断言失败，非编译错误），修后同组 13/13；定向 `voice.semantics.*` + `voice.tools.*` **219 tests / 0 failed / 0 errors / 0 skipped**（exit=0）；全量 **2309 tests / 6 failed / 0 errors / 8 skipped**（exit=1，**不是全绿**，失败仍为既知 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，未改未跳过）。未实机、未构建安装 APK；三星真实 tab 条身份与 `selected` 暴露仍待用户重新授权；`closeAlarm` 仍安全拒绝；`observed` 生产者、在线 `ToolRouter` 同门禁（阶段 2）仍未做。
（第㉗轮，离线，源码 + JVM）无新审查（最新已审 SHA 为 `2048f6d`，其 P1、P2 第 2 条已在第㉖轮修完），本轮推进上轮文末明确列为「未做」的复审 `6546660320` P2 第 3 条：`IntentActions.setTimer` 切定时器页用的是 `UiAutomator.clickText("定时器")`——包含匹配 + 可点祖先上溯 + 坐标兜底，绕开了 `ClockControl.clickClockTab` 的全部约束。新增 `UiAutomator.clickClockNavTab(tab)`（root 必须时钟包 → 精确标签的导航栏控件 → 控件/容器同属时钟包 → 只 ACTION_CLICK），`ClockControl.clickClockTab` 改为委托它（单一实现），`setTimer` 只在 `tab=clicked` 时把屏幕文本当证据，否则写明 `tab=no-verified-tab-control|refused` 且不误点。TDD 新 `TimerTabNavigationSafetyTest`(4) 对旧实现 **RED 4/4**（断言失败，非编译错误），修后 4/4；定向 206 tests / 0 failed / 0 errors / 0 skipped（exit=0）；全量 2296 / 6 failed / 0 errors / 8 skipped（exit=1，不是全绿，失败仍为既知两类，未改未跳过）。未实机：三星真实 tab 条是否满足导航栏启发式未验证；业务回读、在线 `ToolRouter` 同门禁（阶段 2）、正文双标签卡片身份仍未完成。
（第㉕轮，离线，源码 + JVM）无新审查（最新 reviewed SHA 仍是 `2212ed6`），本轮推进「计时器唯一绑定」的**点击时**一半：`cancelTimer` 的绑定只在扫描时证明过，点击的却是扫描时握住的 `AccessibilityNodeInfo`。新增 `ClockControl.clickTargetProblem`，`ACTION_CLICK` 前按序核对前台 root 是时钟、`node.refresh()` 存活、刷新后包名仍是时钟、`isVisibleToUser`、`isClickable`、`node.windowId == freshRoot.windowId`，任一不成立即 `DEVICE_UNAVAILABLE` + 零点击（不走 `UiAutomator.click` 兜底）。TDD 新 `ClockClickTimeBindingTest`(5) 对旧 head 有效 RED 4/5（另 1 条为控制组），修后 5/5；定向 188 tests / 0 failed / 0 errors / 0 skipped（exit=0）；全量 2278 / 6 failed / 8 skipped（exit=1，不是全绿，失败仍为既知两类，未改未跳过）。同时把 `/src/closepaw/logs/` 加入 `.gitignore`。未实机：`refresh()` 与 `windowId` 在该 ROM 的真实语义未验证；业务回读、在线 `ToolRouter` 同门禁（阶段 2）、正文双标签卡片身份仍未完成。
（第㉔轮，离线，源码 + JVM）把复审长期挂着的「`expectedState` 通用终态校验」落成 `TerminalState` 证据分级契约（CONFIRMED / UNPROVEN / CONTRADICTED），在 `SemanticRunner` 的返回边界统一执行：变更类任务回读与期望不一致的 SUCCESS 一律降级为 `EXECUTION_ERROR`，查询与拒绝类不改写；同时用 `ToolResult.sentWithoutProof`（`verified=null`）替换 11 处「只投递 Intent / 只点控件」却标 `verified=false` 的假「已确认未变」。定向 183/0/0/0，全量 2273 / 6 failed / 8 skipped（exit=1，不是全绿，失败仍为既知两类，未改未跳过）。未实机；真正的业务回读（对象存在性/计数）、在线 ToolRouter 同门禁（阶段 2）、正文双标签卡片身份仍未完成。
（第⑳轮，离线，源码 + JVM）按 PR 评论 `6544037799`（第⑱轮复审）要求补的负向夹具，并修掉夹具暴露出的真实误点击：内容页（`闹钟` 段标题 + 可点 `定时器` 设置行）此前被当成导航栏，`clickClockTab("定时器")` **真的返回 true 并投递了 ACTION_CLICK**（RED 实测 `expected null but was true`，detail `tab=clicked`）。`UiAutomator.isNavBar` 现再加两条有界规则：导航栏不得位于滚动容器内；每个 peer 标签必须各自拥有栏内点击目标且不得共用（同一可点卡片里的两个标签不成 tab）。`labelDepths` 改为 `labelNodes`+`depthBelow`（父链证明标签真在该栏内）+`clickUnit`+`insideScrollable`。新增 `ClockContentLabelGateTest`(7)：内容页两个标签的零点击、滚动列表内 tab 形容器、真导航条控制组、`queryTimers`/`queryAlarms`/`cancelTimer`/`closeAlarm` 四个入口的拒绝与零动作。TDD：新测试在旧实现上 RED 4/7（其中 2 条是 `navControl`/`clickClockTab` 误判，2 条是入口层误报 `tab=clicked`）；修后 7/7 绿。旧夹具把 tab 标签写成不可点，与新规则冲突（16 项失败），按真实 UI 补 `isClickable=true`（断言未删未弱化）。定向 `voice.semantics.*`+`voice.tools.*` **151 tests / 0 failed / 0 errors / 0 skipped**；全量 **2241 tests / 6 failed / 8 skipped / 0 errors**，exit=1，不是全绿（失败仍为 `AgentSkillCatalogTest` 1 + `MemoryStoreTest` 5，与基线同类，未改未跳过）。仍是 JVM 合成树，`isNavBar` 依旧是**启发式**；三星真实 tab 条是否满足「栏内每标签有可点单元」「栏不在滚动容器内」需用户**重新授权**的实机验证。未构建/安装 APK、未操作手机、未调用付费 API、不合并。
（第⑲轮，离线）按 PR 评论 `6544037799` 前一轮（`56fd90b` 复审）的两条 P2：(1) `navControl` 的向上爬改为有界 `isNavBar`（非窗口根、peer 标签深度极差 ≤1、容器 ≤40 节点、≥2 peer 标签，缺证据返回 null）；(2) `clickTabControl` 进入循环先判容器即拒绝，容器永不作为点击目标。TDD RED 6/5 → 绿；定向 144/0，全量 2234 / 6 failed / 8 skipped。源码 + JVM，未实机。
（第⑱轮，离线）按 PR 评论 6543364579 第 2/3 条：导航点击改为 `UiAutomator.clickTabControl`（只对导航栏内的 tab 控件/可点击祖先做 ACTION_CLICK，取消通用 click 的父级上溯与坐标手势兜底）；`selectedState` 现在要求精确标签位于可识别导航栏（同祖先子树 ≥2 个时钟 tab 标签）内，并把选中读取限定在控件→容器区间，同包非导航同名节点不再能当页面身份。TDD 有效 RED 6/5（旧实现），最终定向 137/0，全量 2228 tests / 6 failed / 8 skipped（exit=1，不是全绿，既知两类失败）。源码 + JVM，未实机。
（第②轮，历史）按 PR 复审 `IC_kwDOVdfNwc8AAAABhcjt_A`（P1 先定位时钟 UI 读取失败 / P2 文档区分轮次）做了两件事：P2 订正 `docs/phase6-stage1-device-acceptance.md`（标题与轮次索引，历史第一轮不再冒充当前）；P1 只做源码级诊断，把 tab 到达方式与读屏边界暴露到 `detail`，定向 JVM 54/54 通过（含 6 条新增诊断用例，RED→GREEN 分段记录）。时钟 UI 读取失败**未定位根因**，需一次用户授权的只读运行采集前台包/根节点/是否截断/行文本形状。未实机、未装包、未推进阶段 2/3。
上一轮按 PR 评论 6538790664 修复 undo 误取消既存对象：统一结果准入，时钟创建无本轮对象身份及身份绑定逆操作时不能安全撤销，移除时长/时分 undo；新尝试清除旧记忆。闹钟查询区分无下个已启用、可见非空（可能含关闭项）、明确空态、UI不可读。定向 JVM 48/48；全量 2138 tests / 6 failed / 8 skipped / 0 errors，未实机、未推进阶段2/3。
上一轮按 PR 评论 6538288842 修复新建倒计时/闹钟的假验证：既存对象不作为本次创建证明，缺少唯一目标回读则 verified=false；删除闹钟反馈不再把点击说成关闭完成。定向 JVM 42/42；全量 2132 tests / 6 failed / 8 skipped / 0 errors，失败类仍为 AgentSkillCatalogTest / MemoryStoreTest。未实机、未推进阶段 2/3。
上一轮：Issue #3 / PR #4 在 `feat/semantic-gate` 修复审查：ALARM CANCEL 可达、计时器取消保留时长并绑定唯一对象、一般 APP 必须用安装清单核对原话与实际包名。相关 JVM 回归 38/38；全量 2128 tests / 6 failed / 8 skipped。隔离修复前 head `4d1e78d` 复跑两个失败类 55 tests / 同样 6 failed，失败名称集合一致，未弱化断言。
`expectedState` 未被通用终态验证器执行；取消 mutation 无最终业务回读时 `verified=false`。计时器适配仅 JVM fixture 验证，未知三星真实 UI 会拒绝，不能宣称已可用。阶段 1 待审查/设备验收。
（该句属第②轮范围：当时未构建 APK、未上机；第③轮已构建并实机复测，第④轮又回到未实机。）普通代码修复已获自动推进授权，阶段 1 实机回归仍需单独授权，阶段 2（ToolSpec 适配）未在本次完成。
细节与验收对照见 `docs/phase6-stage1-semantic-gate.md`。

## 6. 代码现状（与上游的关系）

- `src/closepaw` = `imoonkey/closepaw` 的浅克隆（上游提交 `75dae26`）+ 本地未合并改动。
- 本地改动量（未提交到上游）：4 个文件被修改（`AndroidManifest.xml` +85 行、`AgentService.kt`、
  `AgentServiceEventHandler.kt`、`settings.gradle.kts` 加 `:rescue` 模块），
  新增 `voice/`（34 个 Kotlin 文件）、`rescue/`（8 个）、`app/src/main/java`（sherpa-onnx JNI 包装）、
  `jniLibs`、`res/xml/cp_voice_interaction.xml`。
- 上游 `.git` 目录未纳入本仓库（仓库内保存的是源码快照），本地仍保留原始克隆以便与上游对比。
