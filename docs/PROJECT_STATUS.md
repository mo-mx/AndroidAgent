# PROJECT_STATUS — 当前真实状态

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
