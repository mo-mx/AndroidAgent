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
