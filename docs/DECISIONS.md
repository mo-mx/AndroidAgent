# DECISIONS — 已确认的技术决策

规则：只有**用户确认过**或**已被实机结果锁定**的才算决策（状态 `已确认`）。
未经确认的方案一律放"待定"，不要因为某个 AI 觉得好就当成结论；
质疑一条已确认决策时，先给出新证据，再由用户改状态。

---

## D1 单 APK 路线：以 ClosePaw 为 Agent 内核 fork 扩展（已确认，2026-10-30）

- 不同时自建第二套 Agent，不并行维护"两个大脑"。
- 理由：ClosePaw 已解决最难的屏幕理解/多步执行/工具层/记忆/skills，原生 Kotlin、可离线，
  OpenAI 兼容端点可接百炼；它缺的正是语音、常驻、Phone/SMS/Camera、业务级验证。
- 代价：依赖上游，需自己维护 fork 差异（目前 4 改 4 增）。

## D2 不改动手机系统：不 Root、不刷机、不关锁屏、不动认证（已确认）

- 只为验证功能而改系统设置的行为一律禁止；历史上强写 `voice_interaction_service`
  导致系统进程 NPE，已回滚。

## D3 在线模型选型：百炼 `qwen3.8-flash`（已确认，实机测速依据）

- `qwen3.8-max` 111.8 s/轮不可用；`qwen3.7-flash` 7.0 s 啰嗦；`qwen3.8-flash` 3.9 s/轮。
- 走 `Other`（自定义 OpenAI 兼容端点），`https://dashscope.aliyuncs.com/compatible-mode/v1`。
- 注意：百炼返回 `reasoning_content`，正文非空，工具调用格式与 ClosePaw 兼容。

## D4 系统助手（VoiceInteractionService）不作为可依赖入口（已确认，平台限制）

- One UI 9 上 `cmd role add-role-holder` 返回 `Package does not qualify`，
  设置里第三方不出现；强写 Secure 设置会让系统崩溃。
- 因此语音入口固定为：**桌面「语音助手」图标 + 下拉磁贴**，不依赖电脑、不依赖系统角色。
- 后果：锁屏语音在该平台上无解（除非走救援通道远程播报）。

## D5 语音链路分层：离线确定性优先，在线 LLM 兜底（已确认）

```
本地命令路由(无模型, <1s) → 确定性语义解析(SemanticParser/SemanticRunner) → 在线 Agent(需网络+解锁)
```
- 理由：锁屏/断网/云端故障时基础指令必须仍可用；离线层毫秒级、可解释、可复现。

## D6 验收标准：`verified` 必须是业务状态回读（已确认）

- 优先级：业务状态回读（AlarmManager/WifiManager/CameraManager/PackageManager）
  > UI 变更检测（`UiChangeDetector`）> 都不可用则 `verified=false` 并如实播报"已执行但无法确认"。
- 反例（明确禁止）：把"界面变了""进程起来了""接口 200"当成功。三星闹钟那次就按此如实报"未创建"。

## D7 目标解析必须真实存在，不做坐标兜底（已确认）

- 应用必须能 `getLaunchIntentForPackage` 且前台包名复核；解析不到或多义时追问/拒绝。
- `SemanticRunner` 用 (action,target) → capability 表，CANCEL 类请求在代码层面没有路径能落到创建调用。

## D8 不接入本地小模型到执行层（已确认，2026-10-31 评测否定）

- Qwen3-0.6B/1.7B、FunctionGemma-270m、Google Mobile Actions 270M 中文 1/4 或 0/15，
  且存在"五分钟→5 秒""暂停→3000 秒"这类反向危险输出。
- 结论：短期继续用确定性解析；只有"多轮口语化中文 + 跨应用"确有需求时才启动中文微调项目。
- 不得以"JSON 合法"或"参数更大"作为选型依据。

## D9 感知层当前只用无障碍树，视觉关闭（已确认，缺陷未修）

- `AccessibilityScreenshotCapturer` 实机产出近空图（~1024×136 / 2.9 KB），
  因此必须 `supportsVision=false`，避免多模态模型在错图上决策。
- 若要开视觉，先修截图或改走 VirtualDisplay，需单独立项验证。

## D10 无线调试优先官方 TLS 通道，mDNS 发现，不写死端口（已确认）

- `adb tcpip 5555` 重启即失效；TLS 端口每次开机变化 → 一律 `adb mdns services` 发现。
- USB 仅用于：首次开启无线调试、无线被关、彻底连不上。

## D11 脚本禁止 `am force-stop`（已确认，根因实验）

- One UI 9 在 force-stop 时会把该包的无障碍注册从 Secure 设置删除 → 表现为"时好时坏"。
- 需要重启应用时用 `am start`；部署用 `adb install -r`（不 force-stop，自动重绑）。

## D12 救援通道设计：Direct Boot + 出站 HMAC + 无入站端口（已确认）

- `specialUse` 前台服务是 Android 15+ 里唯一允许从 BOOT 启动的类型。
- 配置只存 device-protected storage，不含密码/联系人/消息；未解锁时 CE 依赖能力明确返回 `unsupported`。
- NFC 只可能作为辅助入口，且必须 HCE 实现（标签触发在锁屏不可用）→ 本轮不做。

## D13 Git 协作模型（本次确认，2026-10-31）

- 私有仓库，默认分支 `main`；**main 只接受 PR 合并**，不直接推。
- 不配置任何自动部署到手机的 CI（真机操作必须由人/Hermes 在本机执行）。
- 仓库只存源码、脱敏文档、评测结论与可复现脚本；不含密钥、模型权重、APK、设备日志与截图。
- 文档是"持续有效的技术事实"，Issue 是"要解决什么"，PR 是"改了什么"。三者不混用。

## 待定（未确认，勿当结论）

- **P1** 唤醒词实现方式：前台服务 + 本地 KWS 常驻的功耗与唤醒率未测；是否值得常驻待实测。
- **P2** 是否把自研能力包成 ClosePaw `ToolSpec`（`docs/closepaw-reuse-review.md` 提案）——
  方案已写，未批准实施。
- **P3** 中文微调 270M 小模型是否立项（数据/工具集/评测集成本高）。
- **P4** 是否修截图感知（VirtualDisplay 路线），以及修复后是否重开视觉。
- **P5** 正式版引导页改造（内置百炼 provider，去掉 debug Intent 注入）。
