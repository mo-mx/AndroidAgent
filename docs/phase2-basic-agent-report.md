# 第二阶段：基础 Agent 部署报告

时间：2026-10-30 20:30–20:55（CST）
设备：SM-F731U1 / Android 17 (SDK 37) / One UI 9
模型：阿里云百炼 qwen3.8-flash（OpenAI 兼容端点）

---

## 1. 最终部署形态（与第一阶段选型一致，只一套系统）

- Agent 内核：**ClosePaw**（Apache-2.0，Kotlin，ReAct 循环 + 无障碍工具层 + 记忆 + skills）
- 安装包：**自行从源码构建的 debug 版**（235 MB），不是官方 release APK
  - 原因：官方 v0.1.0 release 的引导第 4 步只允许 OpenAI / OpenRouter 并做联网校验，百炼 Key 被拒
    （`That key was rejected`），而自定义端点「Other」只能进设置后改 → 死循环，无法完成引导。
  - debug 版接受 adb Intent 参数（release 版全部 no-op），可无 UI 完成全部配置。源码路径：
    `C:\Users\<user>\AndroidAgent\src\closepaw`，产物 `app/build/outputs/apk/debug/app-debug.apk`
- 模型接入：用 **Other（自定义 OpenAI 兼容端点）**
  - Base URL `https://dashscope.aliyuncs.com/compatible-mode/v1`
  - Model `qwen3.8-flash`，条目名 `other-custom`

部署命令（可复现）：
```
python scripts/deploy.py install      # 安装 + 授权无障碍/悬浮窗/电池白名单
python scripts/deploy.py launch "<任务文本>"   # 带 goal 直接起任务
```

## 2. 实测通过的测试

### 测试 B：打开设置→存储→读剩余容量 —— 通过
轨迹（`inspection-trace/4c07e4ec…`）硬数据：
6 轮 / 6 次工具调用 / 6 成功 / 0 失败 / 51.2 s / `stop_reason=GoalAchieved`
工具序列：`open_app(Settings)` → `click` 存储 → `click` 内部存储 → `shell df /data` 交叉核对 →
`remember_experience`（写入设备级记忆：三星设置页只显示"已使用/总量"，剩余需算）→ `complete_task`
最终答复：已用 34.2 GB / 512 GB，**剩余约 477.8 GB（≈94%）**，与 `df` 的 445 GiB 一致。
→ 说明它不只"点了接口"，而是交叉验证后才答复。

### 测试 A：Chrome 搜索 GitHub 并进入指定项目页 —— 通过
`outcome=GOAL_ACHIEVED`。过程真实：open_app(Chrome) → 点地址栏 → 输入搜索 URL → 在结果里
反复滚动（结果被 fork/镜像污染）→ 改用直接访问 `github.com/k2-fsa/sherpa-onnx` →
读页面核对后答复（含 15.6k stars / 1.8k forks / 559 issues 等实读数据）。

### 测试 C：页面加载失败→刷新→再识别 —— 通过
目标 `http://10.0.0.1:9/`（Chrome 受限端口，必然失败）。
过程：输入 URL → 回车 → 用 `scratchpad` 记下首屏错误 → 开菜单点「刷新」→ 再读 →
`remember_experience` 记录 Chrome 受限端口陷阱 → `complete_task`
答复准确指认 `ERR_UNSAFE_PORT`、中文错误文案、并说明"刷新无法修复，是本地端口黑名单"，同时给出根因。

附加能力顺带验证：`shell`（toybox）、`scratchpad`、`remember_experience`（长期记忆落盘）、
`ask_user`（指令含糊时会反问，实测在 goal 被截断成 "Open" 时正确追问）。

## 3. 关键参数（性能实测）

| 模型 | 单轮耗时 | 结论 |
|---|---|---|
| qwen3.8-max | **111.8 s**（5259 tokens） | 太重，屏幕 agent 不可用 |
| qwen3.7-flash | 7.0 s | 可用但啰嗦 |
| **qwen3.8-flash** | **3.9 s** | 选用；多步任务整体 50–90 s |

百炼返回 `reasoning_content`（思考型），content 正常非空，工具调用格式与 ClosePaw 兼容。

## 4. 本次踩到并已解决的问题（都会在后续阶段复用）

1. **官方 release 无法接第三方端点** → 改用源码 debug 构建 + Intent 注入配置
   （`api_key`/`other_api_key`/`other_base_url`/`other_model_id`/`main_model`/`goal`/`approval_mode`）。
2. **approval 模式致任务中断**：默认模式下跨 App 操作会让任务以 `USER_STOPPED` 结束；
   `approval_mode=AUTO_APPROVE` 后 A/B/C 全部跑通。`SMART` 待后续单独验证。
3. **`uiautomator dump` 会踢掉无障碍服务**（UiAutomation 抢占连接）→ 任务进行中禁用 dump，
   观测只能用 `screencap` + `adb pull`；这也是我最初几次"任务 5 秒就停"的真凶。
4. **`adb exec-out screencap` 在 git-bash 被污染** → 必须 `screencap` 到 /sdcard 再 `adb pull`。
5. **`adb shell input text` 空格会截断** → 用 `%s` 代替空格；`am start --es` 的值要自带双引号。
6. **force-stop 后无障碍设置会被清** → 每次重启先 `scripts/deploy.py grant`。
7. **折叠机有两个 display id**：主屏 4630946592180194435 / 外屏 4630947181303254916。

## 5. 尚未实现 / 已知缺陷（不得当成已完成）

- **无语音**：唤醒词、ASR、TTS 全部没有（第三阶段）。
- **无开机自启 / 无前台服务**：ClosePaw 全仓 0 处 `BOOT_COMPLETED`、0 处 `foregroundServiceType`；
  实测应用转入后台后台账为 `adj=900` 被缓存，长任务有被冻结风险（第四阶段）。
- **截图感知在本机不可用**：`A11yScreenshotCapturer` 产出的图是 1024×136、约 2.9 KB 的近空图，
  `screenshot_input=false`，因此当前是**纯无障碍树感知**（无图像）。若需视觉兜底，要么修这个
  bug，要么换 perception 模式，需单独立项。
- **无障碍树是唯一感知来源**：Canvas/游戏/WebView 自绘界面（部分 App）可能读不到。
- **无百炼 provider 内置项**：目前靠「Other + 手填 URL/模型」；fork 里应加一等公民入口。
- **引导页依赖 debug 后门**：正式版需要改 fork 的引导逻辑（加"稍后配置"或内置百炼）。
- 三星「账号」悬浮窗（Samsung Pass 自动填充）会遮挡界面，需要关掉或加白名单。

## 6. 结论

第二阶段的验收条件 **已达成**：手机独立运行、连百炼、文字指令、读界面、点击/滑动/输入、
多步骤、每步核对、失败纠错、文字答复，全部有轨迹与日志佐证（手机端 `inspection-trace` 可查）。

但这是"能跑"，不是"能长期用"：语音、常驻、应急三块仍然为零，且官方版无法直接接百炼。
下一阶段（第三阶段）应先做 fork 内的两件小事，再做语音：
1. 引导页加「稍后配置」+ 内置百炼 provider（摆脱 debug 意图注入）；
2. 语音链路：VoiceInteractionService（默认助手/侧键入口）+ sherpa-onnx 本地唤醒词 + 本地 ASR + TTS。
