# TEST_RESULTS — 实机验收记录

更新：2026-10-31　维护方式：每次真机验证后**追加**一条，写清"测什么/怎么测/结果/证据在哪"。
规则：没有真机日志的项目只能写"未验证"，不得写"通过"。

---

## 2026-10-31 阶段1授权部署：锁屏阻塞（功能未测）

测什么：PR4 head 2ff514ff0b333f9dafe6947685c059fff91cf475 的debug构建、保留配置覆盖安装、设备基线及非破坏性回归。
怎么测：mDNS发现TLS目标后显式-s连接；getprop/dumpsys trust/accessibility；Gradle :app:assembleDebug；备份原APK与shared_prefs后install -r；重新pull已安装APK计算SHA256。
实际结果：构建exit=0、安装Success，已安装APK哈希与构建一致（98f06f77df538244db3c57e1a8a65a928b76196969d89d5d2ce273a5dbdf49e3）；shared_prefs归档前后字节相同；无障碍服务安装前后均Bound。当前用户deviceLocked=1，未绕过。
七项功能全部未测：取消不存在目标、查询不修改、300秒单位、计时器对象绑定/歧义、商店身份、二级页面反馈、创建undo拒绝。debug文字注入与真人语音均未测，不能以部署成功替代。未创建/删除/修改任何个人时钟对象。
证据：本地gitignored logs/stage1-device-build.txt、logs/stage1-device/及baseline日志；脱敏详情与逐项安全设计见 docs/phase6-stage1-device-acceptance.md。需要用户解锁后继续。没有重跑JVM全量，历史6失败不改为通过。

## 0. 测试环境（所有条目共用）

| 项 | 值 |
|---|---|
| 设备 | Samsung SM-F731U1（Z Flip5 美版，XAA），序列号不入库 |
| 系统 | Android 17（SDK 37）/ One UI 9，安全补丁 2026-10-05 |
| SoC / 内存 | SM8550（8 Gen 2）/ 8 GB 物理（MemAvailable ≈1.9 GB） |
| 屏幕 | 1080×2640 @480dpi；折叠态 `app_accessible=false` |
| 系统角色 | 默认助手 = Google `googlequicksearchbox`；无障碍由用户手动授权 |
| 电脑 | Windows 11 + git-bash，adb 37.0.1；无线调试走官方 TLS + mDNS |
| 网络 | 手机与电脑同 Wi-Fi（局域网 IP 见各报告）；另有 cloudflared 公网隧道用于蜂窝测试 |
| 模型 | 在线：阿里云百炼 `qwen3.8-flash`（OpenAI 兼容端点）；离线：sherpa-onnx paraformer-zh-small |

**日志位置说明**：原始日志、截图、投屏视频都在本机 `C:\Users\Aqua\AndroidAgent\logs\`
与手机端 `inspection-trace/`，**不随仓库分发**（含设备画面与个人信息）。
仓库只保存结论与可复现命令；需要原始证据时在真机上按命令重跑。

---

## 1. 阶段一：环境勘察

| 项 | 结果 | 证据 |
|---|---|---|
| ADB 连接/型号/系统/内存/存储读取 | ✅ | `logs/phase1-20261030-193658.txt`、`logs/phase1b-20261030-193723.txt`；报告 `docs/phase1-device-report.md` |
| 折叠态枚举（`dumpsys device_state`） | ✅ 合盖 `app_accessible=false` | 同上 |
| 默认助手角色可替换性 | ✅ 可替换（`fallback_enabled=true`），但后续实测角色授予被拒（见 §3） | 同上 |

## 2. 阶段二：基础 Agent（在线，纯无障碍感知）

| 用例 | 结果 | 硬数据 |
|---|---|---|
| B：设置→存储→读剩余容量 | ✅ 通过 | 6 轮 / 6 次工具调用 / 0 失败 / 51.2 s / `GoalAchieved`；答复 34.2 GB 已用、剩余约 477.8 GB，与 `df` 的 445 GiB 一致 |
| A：Chrome 搜索 GitHub 并进入指定项目页 | ✅ 通过 | `outcome=GOAL_ACHIEVED`，读回 stars/forks/issues 实值 |
| C：页面加载失败→刷新→再识别 | ✅ 通过 | 准确指认 `ERR_UNSAFE_PORT` 并说明刷新无法修复 |
| 顺带验证 | ✅ | `shell`(toybox)、`scratchpad`、`remember_experience`、`ask_user`（goal 被截断时正确追问） |

延迟实测（同端点）：`qwen3.8-max` 111.8 s/轮（不可用）；`qwen3.7-flash` 7.0 s；
**`qwen3.8-flash` 3.9 s/轮**（选用，多步任务整体 50–90 s）。

## 3. 阶段三：语音

| 用例 | 结果 | 说明 |
|---|---|---|
| `fake_text` 注入全链路（语音入口→Agent→播报） | ✅ | `VoiceActivity dispatch=true` → `Task started` → `speaking outcome=GOAL_ACHIEVED chars=188` |
| ASR 机制（无人说话） | ✅ 链路通 | `onEndOfSpeech` → `#onResults empty` → `onError NO_SPEECH_DETECTED` → 提示"没有听到说话" |
| 真人口述识别率 | ⏳ **未验证** | 需本人在设备前说一句中文，机器侧无法代替 |
| 设为默认助手（`cmd role add-role-holder`） | ❌ 失败 | `RoleControllerServiceImpl: Package does not qualify for the role` |
| 设置列表里出现 ClosePaw | ❌ 不出现 | 「数字助理应用」列表中「其他」为空 |
| 强写 `settings put secure voice_interaction_service` | ❌ 系统崩溃，已回滚 | `VoiceInteractionManagerService$getDefaultAssistantFlagsFromInfo()` NPE（系统进程） |
| 锁屏下打开语音 Activity | ❌ 被锁屏挡住 | 普通 Activity 无法在锁屏上显示 |

## 4. 阶段四：无障碍稳定性与通用能力

### 4.1 无障碍掉绑根因（单变量实验）

| 操作 | `enabled_accessibility_services` |
|---|---|
| 基线（已绑定） | `ai.closepaw/.app.AgentService` |
| `am force-stop ai.closepaw` | **null（注册被系统删除）** |
| `adb install -r`（不 force-stop） | 保持，自动重绑 |

修复后完整循环实测（`scripts/a11y_cycle_test.sh`）：Wi-Fi 关/开各一次、锁屏、App 切换，
全程 `setting=` 不变，`wifi_restored=1`，绑定**零丢失**。

### 4.2 通用能力（逐条真机）

| 指令 | 结果 | 复核方式 |
|---|---|---|
| 打开计算器 | ✅ SUCCESS verified | 前台包名 `com.sec.android.app.popupcalculator` |
| 打开时钟 | ✅ SUCCESS verified | 前台包名 `clockpackage` |
| 设一个四分钟的倒计时 | ✅ SUCCESS verified | 时钟页出现「N 个定时器正在运行 / 4 分钟」 |
| 查看闹钟 | ✅ SUCCESS verified | `AlarmManager.nextAlarmClock` = 10-31 07:30 |
| 打开蓝牙设置页 / Wi-Fi 设置页 | ✅ SUCCESS verified | action + 前台 `com.android.settings` |
| **设置九点二十的闹钟** | ❌ **未创建，verified=false** | `AlarmManager` 仍为 07:30（三星时钟停在编辑页，保存按钮节点未找到） |
| 锁屏下系统面板自动化 | ⚠️ 明确拒绝 | `SYSTEM_RESTRICTED (keyguard locked)`，如实播报 |

## 5. 离线 ASR / 本地小模型评测（PC 与真机隔离测试）

### 5.1 离线中文 ASR（30 条中文命令，PC 同音频，同一套 16 kHz WAV）

| 模型 | CER | 精确匹配 | 备注 |
|---|---|---|---|
| streaming-zipformer-zh-14M | 5.3% | 80.0% | 旧链路 |
| **paraformer-zh-small** | **1.3%** | **93.3%** | 采用（体积/延迟最优） |
| zipformer-ctc-zh-int8-2025 | 0.9% | 93.3% | 351 MB，过重 |
| sensevoice-small | 3.1% | 83.3% | 63 MB |

数据：`asr_eval/*.json`。设备端真机识别率**未系统评测**（仅验证链路通）。

### 5.2 本地小模型（15 条中文任务，llama.cpp b11960 / aarch64 / 4 线程 / temp=0 / max_tokens=240）

| 模型 | action+target | 关键语义通过 | JSON 解析 | 加载 s | 完成中位 s | RSS MiB |
|---|---|---|---|---|---|---|
| Qwen3-0.6B Q4_K_M | 6/15 | 1/15 | 14/15 | 4.78 | 1.13 | 1262 |
| Qwen3-1.7B Q4_K_M | 2/15 | 0/15 | 15/15 | 4.47 | 2.78 | 2623 |
| FunctionGemma-270m-it | 0/15 | 0/15 | 0/15 | 1.33 | 3.30 | 580 |

典型错误：0.6B「五分钟」输出 `seconds=5`、「暂停三分钟」`seconds=3000`；1.7B「恢复计时器」输出
`CREATE`；FunctionGemma 工具格式崩塌（重复 parameters、action 嵌进 parameters）。
**结论：三款均不得接入执行层**（`llm_eval/verification-report.txt`、`results.json`）。

### 5.3 Google AI Edge Mobile Actions（官方 270M 微调 + LiteRT-LM 0.18.1，隔离 APK）

| 组 | 结果 |
|---|---|
| 英文受控（官方 7 工具内） | 4/4 ✓（手电开/关、Wi-Fi 设置、地图显示北京） |
| 中文受控（官方 7 工具内） | 1/4（仅 Wi-Fi 设置 ✓） |
| 中文业务用例 15 条 | 0/15（11 条"无该工具"属预期，4 条误读） |
| 危险反向 | 0 次实际反向调用 —— 但这是"不工作"，不等于安全 |

数据：`mobile-actions-eval/official-results.jsonl`、`docs/mobile-actions-official-eval.md`。

## 6. 无线开发与救援

| 用例 | 结果 | 证据 |
|---|---|---|
| 拔 USB 后无线安装 235 MB APK | ✅ 14 s | `docs/wireless-acceptance.md` |
| 无线授权无障碍并生效 | ✅ | `dumpsys accessibility` Bound services |
| 纯无线跑完整 Agent 任务 | ✅ | `GOAL_ACHIEVED` + 播报 188 字 |
| 锁屏状态下 install/shell/logcat | ✅ | — |
| 重启后服务自动恢复 | ✅ | TLS 端口 45563（mDNS 发现），无障碍自动重绑 |
| 重启**未解锁**时中继心跳 + diagnostics | ✅ | `user_unlocked:false`、`direct_boot:true`、`relay_roundtrip_ms:83` |
| 蜂窝数据 + cloudflared 公网隧道 | ✅ | `network: cellular, validated: true`，status/start_agent/speak 均成功 |
| 解锁后能力恢复 | ✅ | `launch_installed_app/speak/accessibility_enabled` 全部转 true |
| 电脑整机重启后重建连接 | ⏳ 未做 | 会杀掉当前会话进程 |
| 换 Wi-Fi / 纯移动数据远程开发 | ⏳ 未做 | 需另一个 AP 与热点凭据 |

## 7. 投屏（scrcpy 5.0.1）

| 用例 | 结果 |
|---|---|
| 无线发现与连接 | ✅ `[samsung] Samsung SM-F731U1 (Android 17)` |
| 视频解码与真实画面 | ✅ D3D11VA，448×1088；录像 440×1080 / 458 帧 / 19.57 s，ffprobe 可解析 |
| 控制通道注册 | ✅ `dumpsys input` 出现 UHID 设备 `scrcpy` |
| 合成事件端到端注入 | ⏳ **未验证**（前台化被系统拒绝，需人工在投屏窗口点一下确认） |

## 8. 下次要补的验证

1. 真人口述：解锁 → 桌面「语音助手」→ 说中文，确认识别/回执/播报。
2. 唤醒词：前台服务常驻 + 息屏功耗与唤醒率（阶段 5）。
3. 三星闹钟创建的 UI 兜底按钮定位。
4. 投屏控制注入的人工确认（10 秒）。


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
