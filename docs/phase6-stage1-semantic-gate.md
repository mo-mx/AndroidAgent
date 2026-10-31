# 阶段 6 · 第 1 步：语义执行闸门（审查修复报告）

Issue #3 / PR #4；分支 `feat/semantic-gate`；2026-10-31。
性质：源码分析 + 本机 Gradle JVM 回归，未实机。阶段 1 仍待审查/设备验收，不代表整个 Issue 完成。

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
