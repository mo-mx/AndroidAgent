# 语音控制 Wi-Fi / 蓝牙开关 —— 现状诊断与落地方案

编写日期：2026-11-21　适用：念念（`mo-mx/niannian-fox`，Java 悬浮窗 + 服务端编排）
参考实现：本仓库 `src/closepaw/app/src/main/kotlin/ai/closepaw/voice/tools/`

---

## 1. 结论先行

念念现在"打不开 Wi-Fi"**不是 bug，也不是权限没申请**，是 Android 10 之后平台不允许普通应用
翻转 Wi-Fi/蓝牙开关。念念的代码在这条路上已经做了正确的事（打开系统面板让用户点一下），
缺的是**让它自己把那个开关点掉**的能力 —— 这需要无障碍服务，而念念当前没有无障碍服务。

要真正做到"说一句就把 Wi-Fi 打开"，唯一在普通应用权限范围内可行的路线是：
**无障碍服务 → 打开系统面板 → 从活的节点树找到开关 → 点击 → 重读真实状态确认 → 收起面板**。
本仓库的 `SystemPanelAutomator.kt` 就是这样实现的，Wi-Fi 路径在 SM-F731U1 / Android 17 / One UI 9 上
真机验证过（`verified=true`，锁屏时明确返回 `SYSTEM_RESTRICTED`）。

---

## 2. 现状诊断（基于念念当前源码）

`android/app/src/main/java/ai/nous/foxoverlay/FloatingService.java` 的 `runAction()`：

| action | 现在的行为 | 实际效果 |
|---|---|---|
| `wifi` | `openPanel(Settings.Panel.ACTION_WIFI → ACTION_INTERNET_CONNECTIVITY → ACTION_WIFI_SETTINGS → ACTION_SETTINGS)`，播报"Wi-Fi 我打不开开关，面板给你放出来了，点一下就行" | 只弹面板，用户还得手动点 |
| `bluetooth`（on） | `BluetoothAdapter.ACTION_REQUEST_ENABLE` | 系统弹窗"允许" → 用户点一次才行 |
| `bluetooth`（off） | 只打开 `ACTION_BLUETOOTH_SETTINGS` | 用户自己去找开关 |
| `mobile_data` / `airplane` | 同上，只开面板 | 同上 |

Manifest 里已有 `ACCESS_WIFI_STATE`、`BLUETOOTH_CONNECT` —— 这两个只够**读**状态，不够**写**。
并且 **Manifest 里没有 `AccessibilityService` 声明**，这是根本缺口。

## 3. 平台限制（为什么直接调用一定是失败）

| API | 限制 | 后果 |
|---|---|---|
| `WifiManager.setWifiEnabled()` | Android 10 (API 29) 起对普通应用废弃，只有系统应用 / Device Owner / 持 `NETWORK_SETTINGS` 才生效，返回 `false` | 反复重试永远不会成功；`isWifiEnabled`（读）仍然允许 |
| `BluetoothAdapter.enable()` / `disable()` | Android 13 起需要 `BLUETOOTH_CONNECT`，且平台在多数 ROM 上直接拒绝或静默失败；且 `enable()` 返回 `true` 只代表"流程已开始"，立刻读 `isEnabled` 可能还是旧值 | 不能把一次采样当成结论，要轮询等稳定 |
| `ACTION_REQUEST_ENABLE` | 只能用于**开**，且必须用户在系统弹窗点"允许"；AOSP 没有"关闭蓝牙"的等价请求 | 关蓝牙天然只能进设置页或走无障碍 |

三点推论（本仓库踩过并修过的）：

1. 失败后**不要重试同一个 API**，要立刻降级到面板路径并说清原因（`SYSTEM_RESTRICTED` ≠ "失败了"）。
2. `enable()` 之后必须等状态稳定（本仓库用 1200 ms 窗口 + 单调时钟 + 读数上限防自旋）。
3. "平台拒绝"和"权限没授予"必须分开播报，否则用户会去设置里翻一个根本不存在的问题。

---

## 4. 三条可行路线对比

| 路线 | 能否真正切换 | 用户成本 | 适用 | 本仓库状态 |
|---|---|---|---|---|
| **A. 无障碍驱动系统面板** | ✅ 能 | 一次性开启"辅助功能"授权 | 普通 APK 唯一可行 | **已实现**，`SystemPanelAutomator.kt`；Wi-Fi 真机验证，蓝牙未实机 |
| B. Shizuku / ADB 授权后执行 `svc wifi enable` | ✅ 能 | 每次重启需重新授权（Shizuku 无线调试模式开机可自恢复） | 极客用户、开发者机 | 未实现；不建议做主线 |
| C. Device Owner / 系统签名 / root | ✅ 能 | 需要刷机或企业下发 | 自有设备批量 | 不采用（项目硬约束：不 root、不越权） |

**推荐 A**，B 作为"进阶选项"以后可选，C 排除。

---

## 5. 从本仓库搬什么过来

| 文件 | 搬什么 | 为什么要用它 |
|---|---|---|
| `voice/tools/SystemPanelAutomator.kt`（369 行） | 整个文件是核心：`setEnabled(ctx, target, on)` 打开面板 → 从活的节点树定位开关（`class` / `text` / `contentDescription` / `isCheckable`）→ `ACTION_CLICK` → 最近的可点击祖先 → 节点自身 bounds 的合成手势；完成后重读 `WifiManager`/`BluetoothAdapter` 确认；最后收起面板 | 不写死坐标，One UI 与 AOSP 都能用；成功判定基于真实状态而非"点了一下" |
| `voice/tools/WifiControl.kt`（111 行） | `isEnabled()` 读回、`openWifiPanel()` 兜底 | 状态证据来源；面板兜底保证最差情况也有反馈 |
| `voice/tools/AccessibilityHealth.kt` | `awaitConnected(12s)` 与 DISABLED/ENABLED_NOT_BOUND/CONNECTED/LOST 状态机 | 无障碍被系统杀掉后会"静默失效"，必须区分"没授权"与"授权了但没绑上" |
| `voice/semantics/TerminalState.kt` | 判定规则：没有 `observed` 证据就不能报成功 | 防止"面板弹出来了"被当成"Wi-Fi 开了" |
| `voice/tools/LocalCapabilities.kt` 蓝牙段 | `btSet` + `awaitState`（1200 ms 稳定窗口、单调时钟、读数上限） | 蓝牙 `enable()` 的返回值不可信，必须轮询确认 |

Java 调 Kotlin `object` 没有障碍（`SystemPanelAutomator.INSTANCE` 或加 `@JvmStatic`；
本仓库的 `object` 成员通过 `SystemPanelAutomator.INSTANCE.setEnabled(...)` 调用）。
若不想引入 Kotlin，可把 `SystemPanelAutomator` 的核心逻辑照写成 Java 工具类——
它只依赖 `AccessibilityService`、`AccessibilityNodeInfo`、`GestureDescription`，没有第三方库。

---

## 6. 在念念里落地的具体步骤

### 步骤 1：新增无障碍服务（这是唯一的硬前提）

`AndroidManifest.xml`：

```xml
<service
    android:name=".FoxA11yService"
    android:exported="false"
    android:label="念念（语音控制开关）"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data android:name="android.accessibilityservice"
        android:resource="@xml/fox_a11y" />
</service>
```

`res/xml/fox_a11y.xml`（够用就行的最小面）：

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:description="@string/a11y_desc"
    android:notificationTimeout="100" />
```

`FoxA11yService` 里只需要一行桥接：

```java
public class FoxA11yService extends AccessibilityService {
    static volatile FoxA11yService instance;
    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { /* 不需要，保持空 */ }
    @Override public void onInterrupt() { }
}
```

### 步骤 2：权限引导（不能偷偷摸摸）

`MainActivity` 里加一条引导项：`Settings.ACTION_ACCESSIBILITY_SETTINGS` 跳转 + 说明文案
（"用于代替你点系统面板里的开关"）。**必须允许用户不开**——不开时其余功能照旧，
只是开关类指令退回现在的"面板给你放出来"。

判定是否已授权可以用 `AccessibilityManager.getEnabledAccessibilityServiceList()` 或
`Settings.Secure.getString(ENABLED_ACCESSIBILITY_SERVICES)` 里是否含 `ai.nous.foxoverlay/.FoxA11yService`。

### 步骤 3：改 `runAction` 的 wifi / bluetooth 分支

```java
} else if ("wifi".equals(type)) {
    boolean want = a.optBoolean("on", true);
    Result r = PanelToggle.setEnabled(this, PanelToggle.Target.WIFI, want);
    // r.status: SUCCESS / SYSTEM_RESTRICTED / PERMISSION_DENIED / TIMEOUT
    if (!r.ok && r.status != STATUS_PERMISSION_DENIED) {
        openWifiPanelFallback();          // 沿用现在的 openPanel 逻辑
    }
    speakResult(r);                       // 见步骤 4
}
```

蓝牙同理，`Target.BLUETOOTH`。注意：**先读当前状态**，已经是目标状态就直接说"已经是开着的"，
不要去点开关（点了会翻转成相反状态——这是本仓库 `ClockForeignWindowTest` 那类重复点击问题的同源坑）。

### 步骤 4：话术按"证据"分档，不要一律说成功

| 结果 | 播报 | 依据 |
|---|---|---|
| 读回状态 = 目标 | "Wi-Fi 打开了" | `observed = {identity:"wifi", state:"on"}` |
| 点了但没读到变化 | "我点了开关，但没看到状态变化" | `verified=false, dispatched=true` |
| 无障碍没授权 | "要开开关得先给我辅助功能权限，面板我已经打开了" | `PERMISSION_DENIED` + 面板兜底 |
| 锁屏状态 | "锁着屏我改不了开关，先解锁吧" | 本仓库真机结论：`SYSTEM_RESTRICTED (keyguard locked)` |

### 步骤 5：`runAction` 要能回传结果

念念现在是 fire-and-forget（`void runAction(JSONObject)`）。要让语音层说对的话，必须把它改成
返回结果对象，并回传给服务端/前端：

```json
{ "tool":"wifi", "status":"SUCCESS", "verified":true,
  "observed":{"identity":"wifi","state":"on","source":"wifi-manager:isWifiEnabled"},
  "detail":"panel click + readback" }
```

服务端 `server.py` 在拿到这个结果后再决定话术补不补一句，否则会出现"她自己说打开了、系统其实没开"。

---

## 7. 验收清单（每条都要有证据，不能只看代码）

| # | 用例 | 期望 | 证据 |
|---|---|---|---|
| 1 | Wi-Fi 关着时说"打开 Wi-Fi" | 真的打开 | `WifiManager.isWifiEnabled == true` + 播报"Wi-Fi 打开了" |
| 2 | Wi-Fi 开着时说"关掉 Wi-Fi" | 真的关闭 | 读回 `false`；面板收起后回到念念界面 |
| 3 | Wi-Fi 已开时再说"打开 Wi-Fi" | 不重复点击 | 日志无第二次 `ACTION_CLICK`，播报"已经是开着的" |
| 4 | 蓝牙关着时说"打开蓝牙" | 真的打开 | `BluetoothAdapter.isEnabled == true` |
| 5 | 蓝牙关着时说"关闭蓝牙"（先开着） | 真的关闭 | 读回 `false` |
| 6 | 未授予无障碍时说"打开 Wi-Fi" | 面板兜底 + 明确说需要授权 | `status=PERMISSION_DENIED`，无异常崩溃 |
| 7 | 锁屏时说"打开 Wi-Fi" | 明确拒绝，不假装成功 | `SYSTEM_RESTRICTED (keyguard locked)` |
| 8 | 飞行模式开着时说"打开 Wi-Fi" | 说明要先关飞行模式 | 面板或明确播报 |

---

## 8. 坑与风险（本仓库踩过的）

1. **重复点击会翻转**：One UI 上同一个 tab/开关被点两次会切回去。必须"先读状态 → 已是目标就不点 → 点完只验证不补点"。
2. **`ACTION_INTERNET_CONNECTIVITY` 面板里 Wi-Fi 和移动数据都在**：定位开关必须按 `contentDescription`/`text` 精确匹配，不能用"第一个可勾选节点"。
3. **无障碍会被系统杀掉**：`adb install -r` 不会掉绑定，但 `force-stop` 会（实测）。服务重启后要能自恢复，`awaitConnected` 不要一次 null 就判失败。
4. **锁屏下不可用**：这是平台行为，不是代码问题，如实播报。
5. **三星时钟/设置页停留**：点完开关后要主动 `performGlobalAction(GLOBAL_ACTION_BACK)` 或结束面板，否则用户停留在系统设置页——本仓库的实现里有点了保存按钮找不到、停留在编辑页的失败先例（`docs/TEST_RESULTS.md` 第 4.2 节"设置九点二十的闹钟 ❌ 未创建"）。
6. **国产 ROM 差异**：节点结构、面板顺序、后台限制都不同，第 6 节的实现换机型必须重跑第 7 节全部用例。

---

## 9. 明确未验证的部分

- 蓝牙：`SystemPanelAutomator` 的蓝牙路径在本仓库**未做真机验证**（只有 Wi-Fi 真机通过、蓝牙设置页跳转 verified）。搬到念念后必须单独跑第 7 节 #4/#5。
- `BluetoothAdapter.enable()` 在目标机上是返回 `false` 还是抛异常，未实测。
- 无障碍常驻后的功耗（`typeWindowContentChanged` 事件频率）未测；若功耗高，把事件订阅压到"只在执行开关指令时临时启用"。
- 连续高频开关（例如一句话连开 Wi-Fi + 蓝牙 + 移动数据）的时序未测。
