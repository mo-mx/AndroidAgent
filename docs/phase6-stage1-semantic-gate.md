# 阶段 6 · 第 1 步：语义执行闸门（审查修复报告）

Issue #3 / PR #4；分支 `feat/semantic-gate`；2026-10-31。
性质：源码分析 + 本机 Gradle JVM 回归，未实机。阶段 1 仍待审查/设备验收，不代表整个 Issue 完成。

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
