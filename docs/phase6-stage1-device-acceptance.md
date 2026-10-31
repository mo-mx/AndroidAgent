# 阶段1实机验收记录（按轮次：① 锁屏阻塞 → ② 解锁续验 → ③ 时钟读取根因与修复）

**当前结论（只读本节即可，勿用开头历史轮次判断现状）**：最新为 2026-10-31 第③轮。时钟 UI 读取失败已定位为**读到了自己的窗口**（`rootPkg=ai.closepaw`，3 个节点）并已修复验证：ALARM QUERY 现为 `verified=true readable=true visible=[6 个时间显示，已脱敏]`；TIMER QUERY 在“1 个运行中”时为 `verified=true count=1`、在清理后为 `verified=true`（0）。矩阵仍是 **通过 4 / 失败 0 / 未测 3**（第②轮的“失败 1”已修并复测通过；新增的计时器查询读取不再虚报，但计时器取消绑定等 3 项仍缺安全对象证据）。阶段 1 **仍未整体通过**，PR #4 不合并。

轮次索引：
| 轮次 | 时间 | 锁屏 | 主要内容 | 结果 |
|---|---|---|---|---|
| ① 构建安装 + 首轮矩阵 | 2026-10-31 早 | deviceLocked=1（未绕过） | 构建/安装/哈希/无障碍基线 | 7 项全部未测 |
| ② 解锁续验 | 2026-10-31 后 | deviceLocked=0 | 6 条 debug 文字注入 | 通过 2 / 失败 1 / 未测 4 |
| ③ 复审 P1/P2 + 修复（最新） | 2026-10-31 13:27–14:00 | deviceLocked=0 | 时钟读取根因定位、TDD 修复、重装重跑 | 通过 4 / 失败 0 / 未测 3 |

下文「## 第①轮」与「## 第②轮」为历史记录，不代表当前状态。

---

## 第③轮（最新）：时钟读取根因 + 修复复测

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
