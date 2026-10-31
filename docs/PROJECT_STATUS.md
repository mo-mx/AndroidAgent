# PROJECT_STATUS — 当前真实状态

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
