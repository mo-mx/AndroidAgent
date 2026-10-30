# AndroidAgent

私有协作仓库：Windows 侧 Android Agent 研发工程（真机 Samsung Z Flip5 / Android 17）。

本仓库只用于 **共享代码、项目状态、测试结果和技术决策**，不含手机个人数据、密钥或编译产物。
两份互相可见的协作方：Hermes（本地执行、真机验证）与 ChatGPT（代码阅读、评审、方案讨论）。

## 这个项目是什么

在真实手机上跑一个可长期使用的手机助手，路径是：

- Agent 内核：fork 自 [ClosePaw](https://github.com/imoonkey/closepaw)（Apache-2.0，Kotlin），
  单 APK 路线，不自建第二套 Agent。
- 语音层：系统助手入口 / 桌面语音入口 / 下拉磁贴 + 本地离线 ASR（sherpa-onnx）+ TTS 播报。
- 离线层：确定性命令路由（无网络、无解锁也能回答电量、手电、Wi-Fi 一类问题）。
- 救援层：独立进程 `:rescue`，Direct Boot + 出站 HMAC 中继，重启未解锁也能被查询状态。
- 在线层：在线 LLM（阿里云百炼 OpenAI 兼容端点）跑 ClosePaw 的 ReAct 循环执行屏幕任务。

## 仓库结构

```
AndroidAgent/
├── README.md                     ← 你在这里
├── AGENTS.md                     ← AI 协作行为约定（任何 Agent 修改前必读）
├── docs/
│   ├── PROJECT_STATUS.md         ← 当前真实进度（首要入口）
│   ├── ARCHITECTURE.md           ← 代码架构与工具调用关系
│   ├── TEST_RESULTS.md           ← 实机验收结果
│   ├── DECISIONS.md              ← 已确认的技术决策
│   └── *.md                      ← 各阶段原始报告（脱敏后保留）
├── src/closepaw/                 ← Android 工程（:app + :rescue 两个模块）
│   ├── app/src/main/kotlin/ai/closepaw/
│   │   ├── agent/  tool/  perception/  llm/  memory/ ...   ← 上游内核
│   │   └── voice/                                          ← 自研语音/离线/能力层
│   └── rescue/                   ← 自研救援模块（独立进程）
├── scripts/                      ← 部署、无线调试、构建、评测脚本
├── asr_eval/  llm_eval/  mobile-actions-eval/  ← 评测数据与可复现脚本（不含权重）
└── docs/bailian.*                ← 百炼接口文档留档
```

## 共享工作流

1. 读 `docs/PROJECT_STATUS.md` 与相关 Issue。
2. 建独立分支（`feat/xxx`、`fix/xxx`、`docs/xxx`），不要直接推 main。
3. 改完在 `docs/TEST_RESULTS.md` 记录**实测**结果（没测就写没测）。
4. 开 PR，写清改了什么、影响范围、测试结果、未解决问题。
5. 等审查/用户批准后合并。

Issue 讨论"要解决什么"，PR 讨论"具体改了什么"，文档只存**持续有效的技术事实**。

## 不含在仓库里的东西（有意为之）

`tools/`（SDK/JDK/adb/scrcpy 二进制，4.2 GB）、`.env` 与 `secrets/`、`logs/`（含设备截图与原始日志）、
`build/`、`*.gguf` / `*.litertlm` / `*.apk` 等模型与产物、手机录音与投屏视频。
需要时按 `docs/TEST_RESULTS.md` 里的路径在本机复现，仓库只保留结论与可复现命令。

## 环境前提

- Windows 11 + git-bash，`env.sh` 设置 `ANDROID_HOME` / `JAVA_HOME` / `PATH` / `ADB`。
- JDK 17（Temurin）、Android SDK cmdline-tools、platform-tools 37.x。
- 真机通过官方无线调试（TLS + mDNS 自动发现）连接，USB 仅作兜底。

## 协作入口

| 用途 | 位置 |
|---|---|
| 仓库（Private） | https://github.com/mo-mx/AndroidAgent |
| 待办 / 技术讨论 | Issues（每个 Issue 写清：要解决什么 / 为什么 / 如何验收） |
| 代码修改与审查 | Pull Requests（用 `.github/PULL_REQUEST_TEMPLATE.md`） |
| 当前真实进度 | `docs/PROJECT_STATUS.md`（首要入口） |
| 已确认决策（勿反复推翻） | `docs/DECISIONS.md` |
| 实机验收结果 | `docs/TEST_RESULTS.md` |

协作通道初始化验收记录：Issue #1。
