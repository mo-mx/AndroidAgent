# 阶段 6 · 第 1 步：语义执行闸门（阶段报告）

对应 Issue：#3　分支：`feat/semantic-gate`　日期：2026-10-31
性质：**源码 + JVM 单元测试**。本阶段**未部署到手机**，下面标 `未实机` 的一律没有真机结论。

---

## 1. 审计：原来的"闸门"为什么等于没有

| 位置 | 发现 | 判定 |
|---|---|---|
| `SemanticRunner.dispatch()` | `CapabilityGuard.assertNotInverted(t.action, t.action)` —— 同一个值传两次 | **同值比较，恒为 true**，任何输入都放行 |
| `CapabilityGuard.check()` | 只校验 (action,target) 是否在能力表、必填参数是否存在 | 从不与**原始话语**、也从不与**工具实际入参**比较 |
| `SemanticRunner.dispatch()` | `t.params["seconds"]?.toIntOrNull() ?: 300` | 解析失败时静默建一个 5 分钟倒计时；`minute ?: 0` 同理 |
| `AppNavigator.open()` | 解析与启动在同一个函数内完成 | 闸门拿不到"准备打开哪个包"，无法做身份校验 |
| `LocalCommandRouter` | **全仓无任何调用方**（只有 VoiceActivity 的 KDoc 提到） | 离线路径实际是 `SemanticRunner`；但该文件内 `关闭wifi` → `WifiControl.enable()`，`取消` 关键词会吞掉「取消五分钟倒计时」并回「已取消」 |
| `SemanticParser` | 无动词时 CALL/CAMERA/SETTINGS_PAGE/MAPS/WEB 被猜成 `QUERY` | 拨号、开相机、开设置页被声明成"只读"，与"查询不得改状态"直接冲突 |

## 2. 实现

### 2.1 新增 `voice/semantics/IntentGate.kt`（纯 Kotlin，可 JVM 测试）

在**最终工具调用边界**比较四个**不同**来源：

1. 用户原话 `utterance`
2. 解析结果 `SemanticTask`（requested）
3. **即将真实传入的入参** `ToolCall`（含默认值、已解析出的具体包名）
4. 期望的终态 `expectedState`

规则（任一不满足即拒绝，且**拒绝发生在调用能力之前**）：

| # | 规则 | 拦的失败 |
|---|---|---|
| 1 | destructive（CANCEL/CLOSE/DELETE/DISABLE）不得变成 CREATE/ENABLE | 取消变创建 |
| 2 | QUERY 不得携带 mutation | 查询改状态 |
| 3 | action 必须一致（仅允许开关类 CLOSE→DISABLE / OPEN→ENABLE） | 动作漂移 |
| 4 | target 必须一致 | 目标漂移 |
| 5 | 身份一致：apps 必须给出具体 package；闹钟必须 `alarm:H:M`；话语里点名的商店（谷歌/三星）必须对上 | 谷歌商店→三星商店 |
| 6 | 解析出的每个参数必须原样保留 | 五分钟→5 秒 |
| 7 | 话语中自带的时间/百分比必须与入参相等；**话语没说时长就不许造一个** | `?: 300` 类默认值 |
| 8 | 带条件（"如果…"）的 mutation 必须二次确认 | 条件被忽略 |
| 9 | 歧义、多候选、未解析目标 → 提问，禁止取第一个候选、禁止坐标兜底 | 盲选 |

### 2.2 接线

- `SemanticRunner`：删除同值自比较；每个分支构造 `ToolCall`（**用真实入参**）→ `guarded()` → 通过才执行；`seconds`/`percent` 缺失改为提问而不是默认值。
- `AppNavigator`：拆出 `openResolved()` / `openFeatureResolved()`，让闸门在启动前就能比较具体包名；`open()`/`openFeature()` 保留原签名（行为不变）。
- `SemanticParser`：无动词时 CALL/CAMERA/SETTINGS_PAGE/MAPS/WEB → `OPEN`；VOLUME 带百分比 → `SET`。
- `LocalCommandRouter`：`关闭wifi` 改调 `WifiControl.disable()`；「取消」不再吞掉倒计时/闹钟类指令；torch/wifi 两条 mutation 走同一个 `IntentGate`。
- `ToolStatus`：新增 `BLOCKED`（拒绝执行），不再借用 `DEVICE_UNAVAILABLE` 表达"被闸门挡下"。

## 3. 测试清单与结果

`app/src/test/kotlin/ai/closepaw/voice/semantics/IntentGateTest.kt` —— 20 个用例，
Gradle：`:app:testDebugUnitTest --tests "*IntentGateTest"` → **20 passed / 0 failed（0.07 s）**。

| 验收项（来自 Issue #3） | 用例 | 结果 |
|---|---|---|
| 取消不存在的 8 点闹钟不得创建 | `cancelling a non-existent 8 o'clock alarm never creates one` | PASS（inversion） |
| 取消计时器不得建计时器 | `cancelling a timer never creates a timer` | PASS |
| 关闭/暂停/恢复不得建 | `closing and resuming a timer never create one` | PASS |
| 查询不得修改 | `a query may never mutate` | PASS |
| 五分钟不能误成五秒 | `five minutes is never five seconds` | PASS |
| 未说时长不得默认 300 秒 | `a timer without any stated duration is refused` | PASS（invented） |
| 闹钟时分必须与话语一致 | `alarm hour must match the utterance` | PASS |
| 两个商店不可混淆 | `google play and galaxy store are not interchangeable` / `samsung store request must not land on google play` | PASS |
| 未解析应用不得猜 | `an unresolved app identity is refused, never guessed` | PASS |
| 二级页面不得降级为只启动首页 | `in-app feature request may not degrade to a plain launch` | PASS |
| 动作/目标漂移 | `action drift is refused` / `target drift is refused` | PASS |
| 开关类 CLOSE→DISABLE 是唯一允许的重写 | `switch close-to-disable normalisation is the only accepted rewrite` | PASS |
| 歧义 → 提问 | `ambiguity is a question, not an execution` | PASS |
| 多候选 → 不取第一个 | `multiple candidates are never resolved to the first one` | PASS |
| 条件 mutation → 需确认 | `conditional mutation needs confirmation` | PASS |
| 音量百分比一致 | `volume percent must match the utterance` | PASS |
| 回归：闸门不是自我比较 | `the gate is not a self-comparison regression`（action/target/param/identity 单字段漂移逐条必须被拦） | PASS |

全量单测：`:app:testDebugUnitTest` → **2110 tests, 6 failed, 8 skipped**。
6 个失败经 `git stash` 在干净 main 上复跑**同样失败**（55 tests / 6 failed），与本次改动无关：

- `AgentSkillCatalogTest.entry filePath points to SKILL md` —— Windows 路径分隔符（`\` vs `/`）
- `MemoryStoreTest` 5 条 —— markdown 分节插入顺序在 Windows/CRLF 下不一致

## 4. 未做 / 风险

- **未实机验证**：本步只到 JVM 单测。真机验收（阶段 1 的实机部分）需要重新构建 debug APK 并跑
  Issue 里的 6 个场景，本轮按"测试先于实机部署"的要求**没有**部署。
- **行为变化（可能影响已验收路径）**：
  1. 计时器/闹钟解析不到时长改为**提问**，不再默认 300 秒 / 0 分；
  2. 带"如果…"的 mutation 现在会先确认；
  3. 说话未点名商店却说"打开商店"时仍按 `AppNavigator.preferred` 的偏好（三星商店），闸门只在
     话语**点名**时强制身份；
  4. `LocalCommandRouter` 的 `关闭wifi` 不再误调 enable（该文件当前无调用方，属预防性修复）。
- **仍未覆盖**：Thread 时序以外的多任务依赖、在线 LLM 路径（ClosePaw `ToolRouter`）尚未接这道闸门
  —— 那属于第 2 步（ToolSpec 适配）的工作，闸门届时在 `ToolRouter` 前复用同一 `IntentGate`。
- **未接线的死代码**：`KwsEngine`、`LocalCommandRouter` 仍无调用方，本步只加固未接线，未删除。

## 5. 下一步（待批准后继续）

1. 阶段 1 实机回归：构建 debug APK → 跑 6 个验收场景（**需要用户授权装包/操作手机**）。
2. 阶段 2：把 `IntentActions`/`ClockControl`/`LocalCapabilities`/`SystemPanelAutomator` 包成
   ClosePaw `ToolSpec`，并在 `ToolRouter` 前复用 `IntentGate`（届时在线路径也受同一契约约束）。
