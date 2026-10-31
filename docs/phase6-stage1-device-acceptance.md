# 阶段1授权实机验收：锁屏阻塞

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


## 解锁后续验收（2026-10-31，真实 debug 文字注入）

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
