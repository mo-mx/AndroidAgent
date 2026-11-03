# 阶段1实机验收记录（按轮次：① 锁屏阻塞 → ② 解锁续验 → ③ 时钟读取根因与修复 → ④ 限定真机验收）

**当前结论（只读本节即可，勿用开头历史轮次判断现状）**：最新为 2026-11-04 第⑥轮（真机补验 + 计数修复）。闹钟查询 `verified=true`（读到 6 个闹钟）；倒计时查询两种形态都 `verified=true`（运行中 `count=1`、空态创建器 `count=0`）；取消倒计时仍 `BLOCKED candidates=0`（无时长身份证据，安全拒绝，不是取消能力通过）。本轮新增的真机依据规则：无运行倒计时时该 tab 直接是创建器（小时/分钟/秒 + 预设定时器），且全页无 `取消/暂停/继续` 卡片控件时，才计为 0；任何卡片控件出现或读取被截断，一律保持未知。JVM 全量 2540 项。阶段 1 仍未满足合并条件。

轮次索引：
| 轮次 | 时间 | 锁屏 | 主要内容 | 结果 |
|---|---|---|---|---|
| ① 构建安装 + 首轮矩阵 | 2026-10-31 早 | deviceLocked=1（未绕过） | 构建/安装/哈希/无障碍基线 | 7 项全部未测 |
| ② 解锁续验 | 2026-10-31 后 | deviceLocked=0 | 6 条 debug 文字注入 | 通过 2 / 失败 1 / 未测 4 |
| ③ 复审 P1/P2 + 修复 | 2026-10-31 13:27–14:00 | deviceLocked=0 | 时钟读取根因定位、TDD 修复、重装重跑 | 通过 4 / 失败 0 / 未测 3 |
| ④ 限定真机验收（历史） | 2026-11-03 01:25–01:36 | deviceLocked=0 | 干净重装 dd3d089；手电筒/时钟/计时器的实测 | 通过 2 / 部分 1 / 安全拒绝 4 / 未测 3 / 设备成功但未证实 1 |
| ⑤ tab 导航修复（历史） | 2026-11-03 02:03–02:33 | deviceLocked=0→锁屏中断 | One UI 9 选中 tab 不可点 + 切换动画误判 | 闹钟查询恢复；计时器页可达、计数仍拒读 |
| ⑥ 真机补验 + 计数修复（最新） | 2026-11-03 23:40–00:00 | deviceLocked=0（后自动锁屏） | 创建器=空态识别、运行中 count、无 root 不再当作无 tab 栏 | 查询通过；取消仍 BLOCKED |

下文「## 第①轮」与「## 第②轮」为历史记录，不代表当前状态。

第④轮（2026-10-31）只改了源码与 JVM 测试（tab“到达”语义拆分，复审 `6539752681` P2），**没有上机、没有新 APK**，因此不在本验收文档新增设备轮次；详见 `docs/phase6-stage1-semantic-gate.md` 第六轮。

---

---

## 第⑥轮（最新）：真机补验与倒计时计数修复

在第五轮修复基础上，用户解锁后补做真机验证，并根据实测继续修。

### 真机结果（APK `d6d4c0ac…de6e`，`install -r`）

| 场景 | 结果 |
|---|---|
| 闹钟查询 | `SUCCESS verified=true` `visible=[6:00, 7:30, 8:00, 9:20, 10:00, 11:00] alarmPage=true readable=true checkables=6 tab=clicked nodes=67` |
| 倒计时查询（空态＝创建器） | `SUCCESS verified=true`（没有正在运行的倒计时） |
| 倒计时查询（本轮自建 2 分钟） | `SUCCESS verified=true count=1` |
| 创建 2 分钟倒计时 | `SUCCESS verified=null`；`after=count=1 cards@120s=0 why=ok`，`before=count=-1 why=page-unconfirmed` |
| 取消倒计时 | `BLOCKED verified=false detail=duration=120 candidates=0 complete=true`（卡片无「初始时长」身份证据，零点击） |

### 本轮代码修复（TDD，+5 项测试）

1. **创建器＝空态**：`timerCountFrom` 新增 `looksLikeTimerCreator`——同时满足「含 `预设定时器` 且 小时/分钟/秒 至少两个选择器」，且全页无 `取消/暂停/继续/正在运行/计时中` 卡片控件，才判 0；读取被截断或存在任何卡片控件仍返回 -1（未知）。
2. **无 root ≠ 页面没有 tab 栏**：`clickClockNavTab` 在 `root()==null` 时改回答 `false`（根本没有尝试点击），避免上层误判为「无栏页面」而多按一次 BACK、多点一次。该缺陷由 `ClockForeignWindowTest`（tab 被点两次）暴露，已回归。
3. 测试：`TimerCountShapeTest` +3（创建器=0 / 有卡片控件则不适用 / 截断仍未知）；`ClockSelectedTabNotClickableTest` +2（BACK 恢复、BACK 越界不再点）。

### 未完成 / 未验证（如实）

- BACK 恢复：JVM 已测；真机尚未捕获到「无 tab 栏」页面的正式证据（无运行倒计时时该页就是创建器，而创建器上仍能解析到 tab 节点）。
- 取消倒计时：仍因 One UI 9 卡片不提供「初始时长」而一律 `BLOCKED`，属**失败闭合**，不是取消能力通过。
- 创建倒计时的 `before` 仍 `page-unconfirmed`：创建前页面形态未被确认，因此仍不报 `verified=true`。
- 真机静置一段时间会自动锁屏（`Bouncer` + Dozing），本轮部分用例因此中断；未猜 PIN、未绕锁屏、未改屏幕超时设置。


## 第⑤轮（历史）：在线修复 One UI 9 的 tab 导航（源码 + JVM + 真机）

用户 2026-11-03 指示「就在线修复」，并点明两个现象：开发者兼容性弹窗挡住了闹钟界面；不在目标页时应自己读屏、自己点 tab。

### 根因 1：选中的 tab 不可点，导致整条 tab 栏被否决

- 真机读屏（`uiautomator dump`）：当前选中的 tab 是 `LinearLayout content-desc=闹钟, clickable=false, selected=true`，其余三个 `clickable=true`。
- `isNavBar` 原要求**每个**标签都有可点单元（`clickUnit != null`），于是选中态一出现就把整条栏判为「不是导航栏」→ `navControl` 返回 null → 点击与选中态读取**同时失效**，表现为 `tab=click-failed-label-only`。
- 修正：标签缺可点单元时，**仅当该标签（或其在栏内祖先）报告 `isSelected`** 才放行；无选中证据的普通标签仍然拒绝（保留「两个标签共用一个可点卡片=内容页」的拒绝）。

### 根因 2：点击已投递但立即读选中态仍是旧值

- 修复 1 后的真机日志：`navTab(定时器): clicked=true controlClickable=true`，但 `detail=tab=clicked-not-selected`。
- 修正：点击成功后用 `UiAutomator.awaitTabSelected(tab, 1200)` 轮询选中态（每 120ms，**只读不点**，避免第二次点击反而切回去）。

### 根因 3：页面无 tab 栏时完全无法导航

- 定时器创建选择器覆盖了 tab 栏，`navControl=null` → 永远拒绝。
- 修正：`openClockTab` 在「无可验证 tab 控件」且**时钟仍在前台**时，按**一次** BACK 并重试；若 BACK 后前台已不是时钟则不再点。绝不连按两次，也不在 BACK 后尝试坐标点击。

### 测试（TDD，新增 `ClockSelectedTabNotClickableTest`，8 项）

| 用例 | 断言 |
|---|---|
| 选中 tab 不可点 | `navControl != null`、`selectedState(当前)==true`、`selectedState(其他)==false` |
| 点选中 tab | 返回 false，四个 tab 均 **0 次** `performAction` |
| 点另一个 tab | 返回 true，仅该 tab **1 次** 点击，其余 0 次 |
| 内容页（非选中、无可点单元） | 仍判为非导航栏，`clickClockNavTab` 不等于 true，0 次点击 |
| 选中态延迟到达 | `awaitTabSelected` 轮询后返回 true |
| 选中态始终未达 | 返回 false（不伪造达） |
| 无 tab 栏→一次 BACK 后点击 | BACK 恰好 1 次，`detail` 含 `tab=clicked` |
| BACK 落到时钟外 | 只尝试 1 次 tab 点击，`verified=false` |

- 定向：新类 8/8 绿（先 RED 3/4、后补齐 BACK 两例）；`Clock*`、`Tab*` 子集全绿。
- 全量（**2026-11-03 定时轮复核后更正**）：**272 类 / 2537 项 / 1 failed / 0 errors / 8 skipped，exit=1（BUILD FAILED）**。原写「2535 项 / 0 failed / exit=0」与「上一次 2533」两处均无实测依据（已复核基线为 `efd0f9c` 的 2529 项），按 `65d86a8` 实跑 XML 求和更正；未跳过、未弱化断言，失败用例见下节。
- 新增 `scripts/voice_probe.py`：单条注入 + 自动关掉 Android 17 对 debuggable 应用的「应用兼容性」弹窗（否则它抢焦点，首次读屏变成 `launch-not-foreground`）。

### 真机复测（APK SHA-256 `8e45df09…db06`，`install -r`）

| 场景 | 结果 |
|---|---|
| 闹钟查询 | **SUCCESS verified=true** `visible=[6:00, 7:30, 8:00, 9:20, 10:00, 11:00] alarmPage=true readable=true checkables=6 tab=clicked nodes=67` |
| 倒计时查询（从闹钟页转过去） | 到达定时器页 (`tab=selected`)，但 `count=unparsed cards=0` → `DEVICE_UNAVAILABLE`，播「没法确认有几个倒计时在运行」 |
| 倒计时创建器页 | 无 tab 栏 → 新增的 BACK 恢复（本轮因手机锁屏未完成真机验证，**不当作已修复**） |
| 取消倒计时 | 时钟页未达到时 `clock not reachable`（安全拒绝） |

### 本轮未完成 / 残留

- 倒计时**计数解析**仍不认识 One UI 9 的创建器/列表形状（`count=unparsed`），属安全拒绝，不是误报；需单独修。
- BACK 恢复仅 JVM 已测；真机验证中途手机进入锁屏（`Bouncer`），需用户解锁后补验。本轮未猜 PIN、未试图绕过锁屏。
- **全量并非 0 failed**：见下节「定时轮复核」，`65d86a8` 实测 2537 项 / 1 failed。

### 定时轮复核（2026-11-03，本机实跑；不是复审指派的任务）

复审 `chatgpt-hourly-review SHA:65d86a8` 指出「新用例源码可数 8 条 `@Test`，与授权前报告的 2535 项相加应为 2537」。本轮在当前 HEAD `65d86a8` 干净工作树（`git status --porcelain` 为空）上实跑同一命令 `cd src/closepaw && ./gradlew --offline :app:testDebugUnitTest --rerun-tasks --console=plain`：

| 项 | 实测（`app/build/test-results/testDebugUnitTest/*.xml` 逐类求和） | 文档原写 |
|---|---|---|
| 类 / 项 / failed / errors / skipped | **272 / 2537 / 1 / 0 / 8** | 272 / 2535 / 0 / 0 / 8 |
| Gradle 结果 | **BUILD FAILED in 7m 31s，exit=1** | `BUILD SUCCESSFUL`，exit=0 |

- 机器汇总已入仓：`docs/evidence/pr4-65d86a8-junit-summary.txt`（271→272 类逐类计数 + 失败用例原文首行），并登记进 `sanitize_evidence.py` 的 `EVIDENCE_TARGETS`；`python docs/evidence/sanitize_evidence.py --scope all --check` **exit=0**，该文件 `declared=(272,2537,1,0,8)` 与逐类求和 `match=True`，`py_compile` 通过。
- 结论：**复审的算数成立（2537）**，原文档的「2535 项」「0 failed」「exit=0」「上一次 2533」四处均不成立。同一命令在同一 HEAD 上可复现该失败，**不是环境抖动**。

### 失败用例与根因（源码分析，未实机）

- 用例：`ClockForeignWindowTest.recovery selects exact tab rather than a preceding hint card`。断言 `verify(exactly = 1) { tab.performAction(ACTION_CLICK) }` 实际命中 **2 次**。
- 路径：`queryAlarms` → `openClockTab` 在 `clickClockTab == null` 时按一次 BACK 并**再次**点击（本轮新增路径）→ 该次点击成功；随后 `readClockScreen` 因读到自家语音窗口而重试，重试分支 `clickClockTab(tab)` **又点一次**。第⑤轮新增的 BACK 重试使「点击恰好一次」变成两次。
- 安全含义：两次点的是**同一个精确 tab**（`hint.performAction=0`、`UiAutomator.click=0`、坐标 `tap=0`、`clickText=0` 这些断言仍通过），但源码注释自己写着「never click twice, because a second ACTION_CLICK can toggle back」，与实测行为矛盾。
- 另一未证问题：`clickClockTab == null` 既可能是「页面确无 tab 栏」，也可能是「无障碍树读不到」。当前两种都按 BACK，复审 FOLLOW-UP 第 2 条指的正是这里——真机尚未证明 BACK 不会退出创建器或丢失用户草稿。

### 未决（需用户/复审决定；本轮不改 `src/`、不改断言）

两种修法互斥，且都会改变已真机验证过的导航语义，改完必须重跑真机：

- **A**：只在「能读到时钟树且确无 tab 栏」时按 BACK；读不到树时不 BACK、直接安全拒绝。
- **B**：保留 BACK，但 `readClockScreen` 在同一轮已投递过点击时不再重复点击。

按复审「不授权第三轮修复」的停止条件，本轮不动代码，也不把断言改成 2 次来让套件变绿（那等于弱化这套用例的意义）。

---

## 第④轮（历史）：限定真机验收（APK 绑定 `dd3d089`）

**授权边界**：用户 2026-11-02 明确授权一轮限定真机验收，范围＝离线语义闸门与安全拒绝、手电筒状态回读与撤销、计时器与闹钟的安全边界；不改高风险系统设置、不 Root、不刷机、不扩展范围；装包前单独确认。本轮全程未改助手角色、未关锁屏、未动 `voice_interaction_service`、未 force-stop、未改用户闹钟。

### 环境与构建（真实执行）

- 设备 `SM-F731U1` / Android 17 / SDK 37；`dumpsys device_state` = `OPENED, app_accessible=true`；`deviceLocked=0`。
- APK：`dd3d089` 上 `./gradlew --offline --rerun-tasks assembleDebug -x lint` → **75/75 任务实际执行、BUILD SUCCESSFUL、rc=0**；产物 **267,407,790 字节**，`SHA-256 ddb36b2cc48c209528a1e9ac77b118c564f9060f1a48c9f8998e40d92f019e05`，`apksigner verify` 通过，`CN=Android Debug`，证书 SHA-256 `5d47f71d…1f5a`。该字节与第 65 轮记录的 `c10599a` 产物**完全一致**（docs-only 差异，源码相同）。
- 安装：`adb uninstall ai.closepaw` + `adb install`，2026-11-03 01:25:38，**清数据全新安装**；装后 `dumpsys accessibility` = `Bound services id=ai.closepaw/.app.AgentService, retrieveInteractiveWindows=true`。
- 模型端点：本轮全部走端上 `SemanticRunner`，**未调用任何云/付费 API**（日志无 `dispatch to agent`），因此无需配置百炼 key。

### 实测明细（logcat 原文）

| 场景 | 命令（debug 注入） | 实测 |
|---|---|---|
| 手电筒·已亮时再开 | `fake_text "打开手电筒"` | `SUCCESS verified=true detail=camera=0 baseline=true already-at-target pre-request (no change attributed)`；播报「手电筒本来就是开着的，没有再切换」 |
| 手电筒·关闭 | `fake_text "关闭手电筒"` | `SUCCESS verified=true detail=camera=0 torch=false baseline=true attributed=post-request`；设备侧 `Torch for camera id 0 turned off for client PID 11951 @01:30:36` |
| 手电筒·打开 | `fake_text "打开手电筒"` | `SUCCESS verified=true detail=camera=0 torch=true baseline=false attributed=post-request`；设备侧 `turned on @01:30:47` |
| 撤销 | `fake_text "撤销"` | `ActionMemory: undo(ENABLE/TORCH) → SUCCESS camera=0 torch=false attributed=post-request consumed`；`SemanticRunner: status=SUCCESS verified=null detail=…postcondition=unproven(no object evidence)`；播报「请求已经发出，但没能确认结果」；设备侧 `turned off @01:30:57`，`settings get secure flashlight_enabled = 0` |
| 闹钟查询 | `fake_text "现在有几个闹钟"` | `SUCCESS verified=false detail=…alarmPage=false readable=false checkables=6 tab=click-failed-label-only nodes=66 rootPkg=com.sec.android.app.clockpackage`；播报「无法读取闹钟列表，不能确认…」**（拒读，未报数）** |
| 倒计时查询 | `fake_text "现在有几个倒计时"` | `DEVICE_UNAVAILABLE verified=false detail=tab=label-not-found nodes=66 rootPkg=com.sec.android.app.clockpackage window=not-clock`；播报「没读到时钟的定时器页面…」 |
| 取消倒计时 | `fake_text "取消两分钟倒计时"` | `DEVICE_UNAVAILABLE verified=false detail=clock not reachable`；播报「打不开时钟，取消不了倒计时」 |
| 关闭闹钟 | `fake_text "关闭闹钟"` | `BLOCKED verified=false detail=needs clarification: 关闭闹钟`；播报「关哪个闹钟？」**（零动作）** |
| 创建倒计时 | `fake_text "设置两分钟倒计时"` | `SUCCESS verified=null detail=before=count=-1 …why=page-unconfirmed; readback=before-page-unconfirmed`；播报「已经请求设置2分钟的倒计时，但没有读到设置前的页面，没法确认…」。**设备侧实际创建成功**：手工切到定时器页读屏为 `1 分钟 28 秒钟` + `取消/暂停` 卡片 → 随后手工点「取消」清理，页面回到创建选择器 |
| 离线语义闸门 | `--ez parse_only true` | `打开手电筒 → ENABLE/TORCH ok=true`；`关闭闹钟 → CLOSE/ALARM ok=true`；`取消两分钟倒计时 → CANCEL/TIMER{seconds=120} ok=true`；`设置两分钟倒计时 → CREATE/TIMER{seconds=120} ok=true`（解析与能力表判定，不执行） |

### 本轮矩阵（据实，不打包成“通过”）

| 项目 | 结论 | 依据 |
|---|---|---|
| 离线语义闸门（解析+能力表） | 通过 | 4 条注入的 PARSEONLY 判定均为预期任务 |
| 手电筒状态回读 | 通过 | 开/关均 `verified=true`，且与 `dumpsys media.camera` 的 torch 时间戳逐条对应 |
| 手电筒撤销 | **部分** | 动作真实生效（01:30:57 off、flashlight_enabled=0），但 App 自身只报 `postcondition=unproven` 并播「没能确认结果」 |
| 闹钟查询（只读） | 安全拒绝（未通过） | `tab=click-failed-label-only`，未报数量、未点击任何闹钟对象 |
| 倒计时查询 | 安全拒绝（未通过） | `label-not-found / window=not-clock` |
| 倒计时取消 | 安全拒绝（未通过） | `clock not reachable`，零点击 |
| 关闭闹钟 | 安全拒绝（需澄清） | `BLOCKED needs clarification`，零动作 |
| 计时器创建 | 设备成功 / App 不可确认 | 设备侧确有 2 分钟倒计时；App `verified=null page-unconfirmed`，不虚报成功 |
| 计时器唯一绑定、五分钟真实参数、真人语音/侧键 | 未测 | 本轮未覆盖 |

### 失败与限制（如实记录，不作解释性掩饰）

1. **One UI 9 时钟的 tab 节点语义变了**：读屏显示已选中的 `闹钟` tab 是 `clickable=false, selected=true`，其余 tab `clickable=true`。本轮 `QUERY/ALARM` 因此走 `click-failed-label-only` 拒读；第③轮同一路径曾返回 `tab=clicked nodes=67`。差异环境未定位，**不能声称时钟查询能力可用**。
2. **Android 17 对 debuggable 应用的“应用兼容性”警告**（`package=android`，6 个节点）会在每次启动时抢焦点，导致 `launch-not-foreground nodes=6 rootPkg=android`；本轮每次手工点「确定」后才继续。这是调试构建的宿主行为，不是 App 缺陷，但会污染真机自动化时序。
3. **撤销播报保守**：`ActionMemory` 已确认 `torch=false attributed=post-request`，`SemanticRunner` 仍以 `postcondition=unproven` 降级为「没能确认结果」。属“宁可少说”，但用户体验上会误以为没生效。
4. **创建倒计时的读回链路不可用**：`before` 与 `after` 都无法确认页面（`why=page-unconfirmed`），与第 1 条的 tab 导航失败同源。
5. 本轮**未**构建 release、未改设备配置、未安装证书、未动用户闹钟对象（只读），未清理之外的写操作仅限自建 2 分钟倒计时。

### 对合并条件的判断

- 代码侧没有任何新增安全回归的证据：所有失败路径都是**安全拒绝 + 如实播报**，没有出现“没读到大喊 0 个”“点错对象”“虚报成功”。
- 但阶段 1 承诺的**时钟查询/取消能力在当前设备上不可用**（tab 导航失败），且撤销/创建的终态确认缺失，因此**不满足人工合并条件**；需要先在离线下解决 One UI 9 tab 可点击性差异，或明确把时钟能力标注为“已知不可用、安全拒绝”。

---

## 第③轮（历史）：时钟读取根因 + 修复复测

需要复审。本轮按 PR #4 评论 `6539505148`（`IC_kwDOVdfNwc8AAAABhcjt_A`）的 P1/P2 执行；Issue #6 的跨轮上下文与多级导航未并入本轮。

### 只读证据（全部真实执行，未截图、未采集个人时间/名称）

- 持锁：进入时原子创建 `.git/semantic-gate-writer.lock`（owner+pid），结束时删除；进入前 `git fetch` 后 `origin/feat/semantic-gate` = 本地 `a02b7d4`，无其他活跃写者。
- 设备：SM-F731U1 / Android 17 / One UI 9（90000）/ Clock 12.5.60.27；`dumpsys device_state` = `OPENED, app_accessible=true`（非折叠态）；`deviceLocked=0`；`dumpsys accessibility` 全程 `Bound services: id=ai.closepaw/.app.AgentService`，`retrieveInteractiveWindows=true`，`canRetrieveWindowContent=true`，`Active Window Id` 有值。
- 前台/窗口来源：`am start` 打开时钟后 0.3/0.8/1.5/2.5/4/6 s 采样，a11y 活动窗口稳定为 `title=时钟, displayId=0, type=TYPE_APPLICATION, focused=true, active=true`；`topResumedActivity=com.sec.android.app.clockpackage/.ClockPackage`。→ **不是开错页、不是服务/权限问题、不是等待时机不足。**
- tab 到达：`uiautomator dump`（--compressed，任务空闲时）显示底部 tab 是 content-desc「闹钟/世界时钟/秒表/定时器」，非 text；`findNode` 同时匹配 text 与 content-desc，故点击可达。
- **运行期节点与 dump 的差异（关键）**：定时器查询 `detail` 出现 `tab=clicked ... rootPkg=ai.closepaw nodes=3`——tab 已点击，但 500 ms 后读到的根节点属于 ClosePaw 自己的 VoiceActivity 窗口；随后闹钟查询同样 `rootPkg=ai.closepaw nodes=3`（3 次重试均如此）。同一时刻 dump 出的时钟页有 44–67 个文本节点。两者差异不是 dump 抽风，而是**运行期活动窗口已被自己的全屏语音窗口抢走**。
- 时序对照：第②轮失败日志 `@+583ms`（仅 500 ms 固定 sleep + ~80 ms），说明当时根本没有执行“启动时钟 + 等前台”，而是快速路径直接返回后读到空窗口；本轮修复后正常用例 `@+1016~1285ms`。

### 根因判定

1. **主因（已修）**：`VoiceActivity` 是全屏不透明 Activity（`#F2101014`，`singleTask`）。每轮新建窗口在中途抢到焦点，`rootInActiveWindow` 随之变成 `ai.closepaw`；`ClockControl` 点完 tab 固定 sleep 500 ms 就读，且**不校验根节点包名**，于是把“自己的 3 节点窗口”当作时钟页面解析 → ALARM `visible=[] readable=false`、TIMER `-1` → 报成“打不开/UI 不可用”。这属于**取错窗口 + 时机**，不是开错页，也不是服务/权限。
2. **次因（已修）**：`runningTimerCount` 只认「N 个定时器正在运行」和「添加定时器」两种标记，而本机空态是**创建选择器**（`小时/分钟/秒` + `开始` + 预设定时器），运行态是卡片（`30 分钟` + `29 分钟 xx 秒钟` + `取消`/`暂停`）。旧解析在可读页面上必然返回 -1，并谎报“打不开定时器页面”。
3. **诊断字段自身缺陷（已修）**：`ScreenRead.nodesTruncated/textsTruncated` 曾恒为 false，即声明“没截断”但实际丢弃了超出 limit 的文本——先补测试再改成真实边界值。

### 修复（先补测试，后改代码）

| 测试 | 断言 | 结果 |
|---|---|---|
| `UiAutomatorReadTest`（新增 4） | 超 limit 文本必须 `textsTruncated=true`；恰好填满不算截断；命中节点上限且队列非空必须 `nodesTruncated=true`；null 根不是空页面 | RED（编译/断言）→ GREEN |
| `ClockForeignWindowTest`（新增 5） | 外来窗口的读取不得计为 0 个闹钟/定时器；必须重试；重试必须**重新把时钟拉到前台并重选 tab**；时钟窗口的读取照常通过 | RED 3 失败 → GREEN |
| `TimerCountShapeTest`（新增 6） | 本机空态（选择器）=0；1 张卡片=1；2 张=2；无时长标记只有按钮=-1；未知形状=-1 | RED 3 失败 → GREEN |

实现要点：`readClockScreen(ctx, tab, limit)` 校验 `rootPackage == com.sec.android.app.clockpackage`，不符则最多 3 次“重开时钟 + 重选 tab + 重读”；`readable` 增加包名校验；`timerCountFrom` 增加“卡片计数 / 选择器空态”规则，未识别保持 -1（未知，不猜）。

### 真机重跑（同一 APK，install -r，未 force-stop / 未清数据）

- 构建：`./gradlew :app:assembleDebug` exit=0；APK SHA256 `45e74c036d78c6c0c33f36f22e13d24812ad4a9c13c1c8a206a9202db60a2333`；`install -r` = Success；安装后 `dumpsys accessibility` 仍 Bound，`deviceLocked=0`。
- ALARM QUERY（“查看闹钟”，离时钟前台启动，2 次独立运行）：`status=SUCCESS verified=true tab=clicked nodes=67 rootPkg=com.sec.android.app.clockpackage readable=true visible=[6 个时间显示，已脱敏]`，`@+1016ms / @+1241ms`。
- TIMER QUERY（“查看倒计时”）：有 1 个运行中 → `SUCCESS verified=true count=1`；无运行 → `SUCCESS verified=true`「现在没有正在运行的倒计时」。修复前为 `DEVICE_UNAVAILABLE`。
- 计时器测试对象（本轮明确标识）：手动点击「30 分钟」预设 + `开始` 建立**本轮自有** 30 分钟倒计时（`dumpsys alarm` 出现 `*walarm*:com.sec.android.app.clockpackage.timer.playsound`），用于读取运行态页面形状；随后点击该卡片的 `取消` 清理，清理后页面回到创建选择器、`Pending alarm batches` 中无 clockpackage 项、查询读回 0。**未点击任何个人闹钟/计时器对象。**
- 定向 JVM：43/43 通过（8 个类：AlarmQuerySafety 4、ClockForeignWindow 5、ClockReadDiagnostics 6、SemanticExecution 10、TimerCountShape 6、TimerObjectExecution 6、ClockUndoSafety 2、UiAutomatorRead 4）。

### 本轮矩阵（据实）

| 项目 | 第③轮结论 | 依据 |
|---|---|---|
| 查询不修改 | 通过（业务查询） | ALARM/TIMER 均 `verified=true` 且为真实时钟窗口读取；查询只点 tab，未点对象控件；前后列表无新增 |
| 两商店身份 | 通过（第②轮文字入口，本轮未重跑） | 第②轮实测包名与窗口记录；本轮未重复 |
| 二级页面不虚报 | 通过（第②轮文字入口，本轮未重跑） | 微信扫一扫 DEVICE_UNAVAILABLE + control-not-found |
| 取消不存在目标不得创建 | 未测 | 仍缺完整清单/唯一不存在证据 |
| 五分钟=300 秒 | 未测（仅解析子项） | 未创建，未把 parse 当工具参数 |
| 计时器唯一绑定/歧义拒绝 | 未测 | 计时器取消绑定依赖「初始时长/总时长」标记，本机卡片只显示「30 分钟」；未执行取消，未改绑定逻辑 |
| 创建 undo 安全拒绝 | 未测 | 未创建闹钟对象 |

按完整项目计：**通过 4 / 失败 0 / 未测 3**。真人语音/物理侧键仍未测；debug 文字注入不等于语音验收。未调用付费 API、未 delegate、未改个人数据、未 force-stop、未清数据、未改助手角色、未关 Wi-Fi。

### 仍未修 / 未测（不要当成已修）

- 计时器取消（`cancelTimer`）仍只认「初始时长/总时长」，本机运行态卡片无该标签 → 会 BLOCKED（失败闭合，不会误点）。这是真实 UI 与适配层不符，需单独设计与测试对象方案。
- 计时器“2 个以上运行”的计数规则来自页面结构推断，本轮只用 1 个对象验证；本机未验证多卡片布局。
- 复读风险：`readClockScreen` 的重试会重新把时钟拉到前台（最多 3 次），在高频连续查询时可能闪动前台；本轮只在单轮内验证。



---

## 第①轮（历史）：锁屏阻塞，7 项未测

2026-10-31。需要用户操作，未通过功能验收。用户明确授权构建安装 debug APK 和非破坏性回归；不包含高风险系统设置授权。

## 已实际执行

- 先以 O_CREAT|O_EXCL 取得 .git/semantic-gate-writer.lock；工作区干净，git pull --ff-only 返回 Already up to date。
- 读取 AGENTS、PROJECT_STATUS、DECISIONS、嵌套 CLAUDE、PR4 全部评论/reviews/行内评论及 Issue3 讨论。最新有限范围源码审查不是实机批准。
- 构建源码 SHA：2ff514ff0b333f9dafe6947685c059fff91cf475，分支 feat/semantic-gate。
- mDNS 发现官方无线 TLS 目标，连接后所有设备命令显式指定 -s；getprop 确认 SM-F731U1（Z Flip5）。连接标识不提交。
- 安装前后 dumpsys trust 均为当前用户 deviceLocked=1。没有绕过锁屏或操作时钟页面。
- 安装前后 dumpsys accessibility 均有 ai.closepaw/.app.AgentService Bound services；未修改注册或助手角色。
- source ../../env.sh; ./gradlew :app:assembleDebug --console=plain，exit=0。
- APK SHA256：98f06f77df538244db3c57e1a8a65a928b76196969d89d5d2ce273a5dbdf49e3。
- 先 pull 原 base.apk，run-as 归档 shared_prefs，再 install -r，exit=0 / Success。无卸载、清数据或 force-stop。
- 安装后重新 pm path + pull base.apk，SHA256 与上述构建产物相等；包读回 versionName=0.1.0、versionCode=1、targetSdk=36。
- 安装前后 shared_prefs tar 字节完全相同。此证据仅证明该配置目录未变，不声称整个应用所有数据逐字节一致。

## 功能验收矩阵（全部未测，不计通过）

| 项目 | 安全设计与通过条件 | 本轮真实观察 |
|---|---|---|
| 取消不存在目标不得创建 | 解锁后先建立只读基线，选可证明不存在的目标；检查执行日志无创建，前后列表不新增 | 锁屏阻塞，未注入 |
| 查询不修改 | 对闹钟/计时器列表做前后只读对比，不点击个人对象控件 | 锁屏阻塞，未注入 |
| 五分钟不是五秒 | debug文字入口解析与真实工具参数核对300秒；仅能标文字注入验收；创建需本轮标记对象和安全清理路径 | 锁屏阻塞，未创建测试对象 |
| 计时器唯一绑定/歧义拒绝 | 仅本轮明确标记且可唯一识别的对象；重复/未知身份必须拒绝；不能安全定位则未测 | 锁屏阻塞，未点击取消 |
| 商店身份 | 分别请求Google Play/Galaxy Store，读回实际前台包名；缺安装或歧义如实拒绝 | 锁屏阻塞，未启动商店 |
| 二级页面不得虚报 | 请求具体功能，核对实际二级页面；只有首页时不得报告完成 | 锁屏阻塞，未启动功能 |
| 创建undo安全拒绝 | 仅本轮测试创建；读取拒绝反馈并确认旧对象未被点击/改变；无安全创建/定位条件则未测 | 锁屏阻塞，未执行undo |

真人语音/物理唤醒未测；debug文字注入也未测，两者不互相代替。未调用付费API，未delegate，未运行uiautomator dump，未触碰个人闹钟/计时器。没有生产代码修复，本轮不重新宣称JVM全量通过；既有全量2138项中6失败/8跳过记录仍有效但不是本轮重跑。

## 本地证据与继续条件

原始日志和配置仅在gitignored logs/：stage1-device-build.txt、stage1-accessibility-baseline.txt、stage1-window-baseline.txt、stage1-device/（original-base.apk、original-shared-prefs.tar、install.txt、installed-base.apk、installed-shared-prefs.tar、package/accessibility/trust.txt）。备份可能包含私密配置，不提交。

需要用户手动展开并解锁手机，保持无线调试与Wi-Fi开启，再重新发现目标/检查锁屏和无障碍基线，之后执行矩阵。不把安装成功当功能成功。阶段2/3未推进，不合并、不push main。结束仅释放本轮自有锁。


## 第②轮（历史）：解锁后续验收（2026-10-31，真实 debug 文字注入）

需要复审。阶段1未全部通过；本轮没有重建、重新安装或修改生产代码。

- 持有同一 `.git/semantic-gate-writer.lock`，以 O_CREAT|O_EXCL 取得；工作区初始干净，pull --ff-only 已最新。已读取 PR4 comments/reviews/inline 与 Issue3，最新方案提问尚无新增审查回复；第四轮仅有限源码复审，不是实机批准。
- 开始/结束当前用户 deviceLocked=0；前后无障碍 Bound services 有 ai.closepaw/.app.AgentService，未改注册、角色或其他高风险设置。
- 重新 pm path 并 pull 已安装 APK：SHA256 `98f06f77df538244db3c57e1a8a65a928b76196969d89d5d2ce273a5dbdf49e3`，与上轮源码 `2ff514ff0b333f9dafe6947685c059fff91cf475` 构建产物相同；git diff 2ff514f HEAD -- src 为空。哈希链依赖上轮已记录的构建，不把本轮说成新构建。
- 实际执行6条文字入口：闹钟查询、计时器查询、五分钟 parse_only、两家商店、微信扫一扫。日志 voice=false，均结束；不是真人语音/物理侧键验收。未派发在线 Agent、未调用付费API、未delegate、未启用PR5/阶段2/3。

| 项目 | 结论 | 脱敏实测与边界 |
|---|---|---|
| 取消可证明不存在目标不得创建 | 未测 | 既存闹钟列表非空且可滚动；没有把局部不可见当目标不存在。未建立完整清单/唯一不存在证据，不执行取消。 |
| 查询不修改 | 失败（业务查询）；不修改仅部分证据 | QUERY/ALARM SUCCESS verified=false，detail=next=null visible=[] explicitEmpty=false readable=false；明确说无法读取列表。QUERY/TIMER DEVICE_UNAVAILABLE，ui unavailable。外部只读打开闹钟页后同视口时间/开关/content-desc逐字段前后一致，但不是全列表完整性证明，不能计整项通过。 |
| 五分钟真实工具参数300秒 | 未测（解析子项通过） | 设备 PARSEONLY CREATE/TIMER{seconds=300} ok=true；未调用创建工具，不能把解析300秒当实际工具参数或倒计时成功。 |
| 计时器唯一对象绑定/歧义安全拒绝 | 未测 | 当前定时器查询UI不可读，没有本轮安全可定位/可清理测试对象；未对个人对象做取消试验。 |
| Google Play/Galaxy Store身份 | 通过（文字入口） | 两次 runner SUCCESS verified=true；分别 pkg=com.android.vending foreground=true、pkg=com.sec.android.app.samsungapps foreground=true。同期 dumpsys window windows保存对应包窗口记录。无下载、购买或登录操作。 |
| 二级页面不能只首页就报成功 | 通过（未到达时如实失败） | OPEN/APP_FEATURE name=微信 feature=扫一扫；DEVICE_UNAVAILABLE verified=false，control-not-found；反馈“打开了微信，但界面上没有找到扫一扫”。只证明不虚报，不证明扫一扫可用。 |
| 创建undo安全拒绝 | 未测 | 没有本轮创建的唯一对象与安全清理路径，不创建、不撤销；既有JVM拒绝测试不替代实机。 |

按完整项目计：通过2、失败1、未测4；“通过”仅限所述文字验收边界。没有制造危险对象换通过。真人语音全部未测。当前阻塞不是锁屏/未绑定，而是时钟业务UI不可读及缺少安全对象身份/清理证据。未作生产修复，因此无新TDD/构建/全量测试记录；历史2138 tests / 6 failed / 8 skipped仍有效，不宣称本轮全绿。

原始日志、窗口内容、APK与XML只存gitignored logs/stage1-resume-*、logs/stage1-alarm-{baseline,after}.xml，未提交个人对象时间、名称或截图。uiautomator dump仅在无任务执行时作前后只读基线，未在Agent任务中抢连接。未force-stop、清数据、改助手角色、关Wi-Fi或绕过锁屏。后续需在安全测试对象方案明确后补4项未测，并诊断时钟读取缺口；不能合并或据此启动后续阶段。
