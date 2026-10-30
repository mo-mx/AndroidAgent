# 阶段四报告：无障碍稳定性 + 通用能力框架

日期：2026-10-31　设备：SM-F731U1 / Android 17 / One UI 9

## 一、无障碍服务不稳定 —— 根因与修复

### 根因（单变量实测）

| 操作 | `enabled_accessibility_services` 结果 |
|---|---|
| 服务已绑定，基线 | `ai.closepaw/.app.AgentService` |
| `adb shell am force-stop ai.closepaw` | **null（注册被系统删除）** |
| `adb install -r`（不 force-stop） | 保持不变，自动重绑 |

结论：**One UI 10 在 force-stop 时会把这个包的无障碍注册从 Secure 设置里直接删掉**，不是"绑定失败"。
之前的部署/测试脚本里带有 `am force-stop`，所以表现成"有时能用，有时提示无障碍没运行"。
系统侧无崩溃记录（`Crashed services:{}`），排除服务自身崩溃。

### 修复

1. 删除 `scripts/*.sh`、`scripts/run_watch.py` 中全部 `am force-stop`，改为 `am start` 重启界面。
2. 新增 `AccessibilityHealth`：区分 `DISABLED / ENABLED_NOT_BOUND / CONNECTED / LOST` 四态，
   记录 `connectCount`、`unbindCount`、`lastConnectedAt/lastLostAt`，并提供
   `awaitConnected(timeout)` 等待平台重绑。
3. `AgentService` 挂接 `onServiceConnected / onUnbind / onInterrupt / onDestroy`，每次状态变化落日志。
4. `SystemPanelAutomator` 改为按状态给结论：
   - DISABLED → PERMISSION_DENIED「无障碍被关掉了，请在设置里重新打开」
   - ENABLED_NOT_BOUND / LOST → 先等最多 12s 重绑，仍失败才报 DEVICE_UNAVAILABLE/TIMEOUT

### 验收实测（无 force-stop 的一轮完整循环）

```
--- start            setting=ai.closepaw/.app.AgentService  wifi=1
--- after_wifi_off   setting=ai.closepaw/.app.AgentService  wifi=0   task=WIFI_OFF SUCCESS verified=true  (true→false)
--- after_wifi_on    setting=ai.closepaw/.app.AgentService  wifi=1   task=WIFI_ON  SUCCESS verified=true  (false→true)
--- while_locked     setting=ai.closepaw/.app.AgentService  Dozing  task=BATTERY SUCCESS
--- after_app_switch setting=ai.closepaw/.app.AgentService
--- final            setting=ai.closepaw/.app.AgentService
wifi_restored=1
```

整轮（含两次 Wi-Fi 面板开关、锁屏、App 切换）绑定**零丢失**，面板自动化两次都成功且由
`WifiManager` 复核。锁屏状态下尝试面板自动化 → `SYSTEM_RESTRICTED (keyguard locked)`，如实播报。

## 二、通用能力框架

新增模块（`ai.closepaw.voice.tools`）：

| 模块 | 作用 |
|---|---|
| `AppRegistry` | PackageManager 枚举桌面应用，中文名/别名匹配，`getLaunchIntentForPackage` 启动，并用「前台包名」验证是否真的打开 |
| `IntentActions` | 闹钟/倒计时/查看闹钟/设置页/拨号/地图/搜索，全部先 `resolveActivity` |
| `UiAutomator` | 节点搜索、点击（节点→可点击祖先→手势）、输入、滑动、等待文本、前台包名 |
| `ChineseTime` | 「十分钟」「半小时」「一个半小时」「7点半」「下午三点」解析 |
| `CapabilityRegistry` 路由 | `IntentPlanner` 新增 TIMER/ALARM/SHOW_ALARMS/SETTINGS_PAGE/OPEN_APP/MAPS/WEB/APP_LIST |

### 实测结果（真机，逐条）

| 指令 | 结果 | 验证方式 |
|---|---|---|
| 打开计算器 | SUCCESS verified | 前台包名 = com.sec.android.app.popupcalculator |
| 打开时钟 | SUCCESS verified | 前台包名 = clockpackage |
| 设一个四分钟的倒计时 | SUCCESS verified | 时钟「定时器」页出现「N 个定时器正在运行 / 4 分钟」 |
| 查看闹钟 | SUCCESS verified | `AlarmManager.nextAlarmClock` = 10-31 07:30 |
| 打开蓝牙设置页 | SUCCESS verified | action=BLUETOOTH_SETTINGS，前台 = com.android.settings |
| 打开 Wi-Fi 设置页 | SUCCESS verified | action=WIFI_SETTINGS，前台 = com.android.settings |
| 设置九点二十的闹钟 | **未创建，verified=false** | AlarmManager 仍是 07:30 |

### 已知未完成项

1. **三星闹钟创建**：`ACTION_SET_ALARM` 无论 `EXTRA_SKIP_UI` 真假，三星时钟都停在编辑界面，
   需要点「保存」。已加 UI 兜底（点击 保存/完成/确定 后再用 AlarmManager 复核），
   但本轮真机上没找到该按钮节点，因此**如实报未创建**，没有虚报。
2. 倒计时验证依赖时钟界面文本；若无障碍不可用则报 `verified=false`。
3. 折叠状态、重启后首次解锁前未覆盖。

## 三、任务三（唤醒词 + 声纹）未开始

计划：sherpa-onnx KWS（`sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`）+ SpeakerEmbedding 模型，
前台服务常驻监听、注册流程录 5–10 条样本、侧键始终绕过声纹。需要先下载模型并测息屏功耗。
