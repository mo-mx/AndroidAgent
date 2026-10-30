Google AI Edge Gallery Mobile Actions 复用评估（Z Flip5 真机）

结论：官方示例可在 Z Flip5 正常运行，英文受控用例 4/4 正确；中文在官方 7 个工具范围内仅 1/4，中文 15 条业务用例 0/15（多数是"没有该工具"的拒答，另有误读）。不建议直接复用其模型做中文助手；可复用其推理与工具调用框架，但中文要么另做微调，要么继续用确定性解析。

一、获取与运行

- 源码：github.com/google-ai-edge/gallery，克隆提交 104ef47f0deba6355f039080a2c95ce265b82c0f
- 官方 APK：1.0.22（releases 资产 ai_edge_gallery_app.apk，87 MB），包名 com.google.ai.edge.gallery
- 官方模型：mobile_actions_q8_ekv1024.litertlm，288,964,608 字节
  用户下载文件 sha256 = 33e295cbd996b419bb1de8f3f85c5b6b01ee058a2c89bdb2173cf3e6ff4ce9d0
  Gallery 实际加载文件 sha256 相同 → 同一份权重
- 运行时：LiteRT-LM，com.google.ai.edge.litertlm:litertlm-android:0.18.1（Gallery 1.0.22 使用）
- 官方默认采样：topK=64, topP=0.95, temperature=0, maxTokens=1024, 加速器 CPU

原始演示实测（未改动）：
输入 "Open Wi-Fi settings" → 模型返回 The Wi-Fi settings have been opened.
日志：START act=android.settings.WIFI_SETTINGS ... from uid (com.google.ai.edge.gallery)，前台窗口变为 com.android.settings/.Settings$WifiSettingsActivity
即官方演示在真机上确实完成了真实系统操作。

二、与之前 llama.cpp 测试的区别

| 项 | 之前 | 官方 Mobile Actions |
|---|---|---|
| 权重 | functiongemma-270m-it 基座 | functiongemma-270m-ft-mobile-actions 任务微调 |
| 格式 | GGUF Q4_K_M（llama.cpp） | .litertlm（LiteRT-LM） |
| 量化 | Q4_K_M | Q8（q8_ekv1024） |
| 工具调用 | 通用 FunctionGemma 工具模板 + 我们的 JSON 协议 | 官方 7 个 @Tool 函数 + 官方 system prompt |
| 结论 | 语义错误严重 | 本报告结论 |

这是不同权重、不同运行时、不同任务适配方式，之前的失败不能代表官方微调模型。

三、中文测评（隔离执行）

方法：自建独立 APK（eval.mobileactions），调用官方 LiteRT-LM 0.18.1，直接复用官方 Actions.kt / MobileActionsTools.kt，工具回调只记录不执行；system prompt 与官方 getSystemPrompt() 一致；采样与官方默认一致。模型放 /data/local/tmp。原有 Agent、语音、无线、救援模块未改动。

结果（23 条，load 684 ms，单条中位 695 ms，PSS 中位 ~910 MiB）：

英文受控（官方工具范围内）
  Turn on the flashlight → FlashlightOnAction ✓
  Turn off the flashlight → FlashlightOffAction ✓
  Open Wi-Fi settings → OpenWifiSettingsAction ✓
  Show Beijing on the map → ShowLocationOnMap(Beijing) ✓
  4/4

中文受控（官方工具范围内）
  打开手电筒 → 无调用，"I cannot open the handlamp" ✗
  关闭手电筒 → 无调用，误判为发邮件询问主题 ✗
  打开Wi-Fi设置 → OpenWifiSettingsAction ✓
  在地图上显示北京 → 无调用，"I need the location" ✗
  1/4

中文业务用例（15 条，多数超出官方 7 工具范围）
  设置/取消/暂停/恢复计时器、创建/关闭/查询闹钟、三星商店、Google Play、微信分身、扫一扫、蓝牙条件：0/15
  其中 11 条为"没有该工具"类拒答（工具范围外，属预期边界），
  4 条属误读：查询/条件被解释成邮件主题、时间参数被要求补全等。

危险反向：0 次实际反向调用（因为中文几乎不产生函数调用）。这不等于安全，而是"不工作"。

四、判断

1. 官方示例本身是可用的成熟方案：真机加载正常、英文 4/4、真实完成系统操作、框架（Engine + tool() + @Tool 注解）可直接复用，不必自研工具调用协议。
2. 官方微调模型只覆盖 7 个英文工具，且实测中文基本不通（范围内 1/4）。这与官方文档一致：dataset 与 recipe 均为英文 Mobile Actions 场景。
3. 因此不应把该权重当作中文助手的大脑直接接入。它没有闹钟、计时器、应用启动、二级页面导航等能力，且中文意图识别不成立。

是否值得进一步微调：
- 若目标是"中文手机助手"，需要中文指令数据集（官方 google/mobile-actions 是英文）+ 我们自己定义的工具集（闹钟/计时器/应用/扫一扫等）+ 扩展后的工具 schema，再按官方 recipe 微调 270M。
- 工作量不是调参级别：需要中文语料、工具集设计、评测集、以及每次工具集变化后的重训。270M 在 15 条中文上全错，说明缺的是数据而非提示词。
- 建议：短期继续用确定性解析 + 现有本地工具（已验收的部分）；只有在"多轮口语化中文 + 跨应用"确有必要时，才启动中文微调项目。

五、产物

- mobile-actions-eval/gallery/                官方源码
- mobile-actions-eval/ai_edge_gallery_app.apk 官方 1.0.22
- mobile-actions-eval/mobile_actions_q8_ekv1024.litertlm 官方模型（sha256 已核）
- mobile-actions-eval/official-results.jsonl  23 条逐条原始输出/时延/内存
- mobile-actions-eval/official-demo.log       官方演示打开 Wi-Fi 设置的真实日志
- mobile-actions-eval/harness/                隔离测试 APK 源码（复用官方 Actions/Tools）
- mobile-actions-eval/harness-build.log       构建日志
