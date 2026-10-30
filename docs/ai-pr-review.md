# GitHub PR 事件审查（待启用）

## 运行方式
- PR opened / reopened / synchronize / ready_for_review 触发，草稿 PR 跳过。
- 可以在 Actions → AI PR Review → Run workflow 手动指定 PR 编号（工作流合并至 main 后才可用）。
- 使用 GitHub API 获取 PR 文件及 patch，**不会 checkout 或执行 PR 的代码**。
- 使用 OpenAI Responses API 进行保守的中文代码审查，把报告写入 PR Conversation 评论。
- 相同 head SHA 已有 AI 审查标记时跳过，提交新的 commit 后重新审查。
- **绝不自动 approve、merge、deploy 或执行手机操作**。人工批准合并仍然必需。

## 唯一必须人工配置的凭据
在 GitHub 仓库 Settings → Secrets and variables → Actions → New repository secret 设置：

- `AI_REVIEW_API_KEY`：你自己拥有的 OpenAI API key（不是 ChatGPT Plus/Pro 订阅或 Codex 登录凭据）。不要贴到 Issue、聊天或提交里。
- 可选：Actions Variables `AI_REVIEW_MODEL`，默认 `gpt-5-mini`。应核实模型账号权限和按量价格；API 会产生额外费用。

**未配置密钥前不能完成真实 API 审查。** 当前 PR 只是部署工作流的配置变更；并未触发/验证自动评论。GitHub Actions 必须在 workflow 合并到默认分支之后才能正常处理后续 PR 事件和手动触发。

## 安全与成本限制
- 权限只授予 `contents: read`、`pull-requests: write`。
- 不使用 `pull_request_target` + checkout；不执行来自 PR 的外部命令。
- 模型看到的 PR 文本是不可信内容，不能作为新的指令。
- 仅发送最多约 50K 字符 patch，单文件最多 9K 字符；其余差异可能漏检；包含敏感内容的 diff 将被发送给模型提供商，提交前仍需做好脱敏。
- 无密钥/请求失败时 Action 失败，**不会假装审查通过**。
- 每次新 commit 都可能产生 API 成本；可通过暂停工作流停止。
- 自动报告只是初筛，不能替代编译、测试、Hermes 实机验收或用户最终批准。

## 如何验收
1. 将配置 PR 经过人工审查后合并到 main。
2. 配置 `AI_REVIEW_API_KEY`。
3. 在已有测试 PR 上通过 workflow_dispatch 手动触发一次。
4. 检查 Actions 运行日志、PR 评论中的 SHA、模型与报告。
5. 给测试 PR 推送一个小的新 commit，确认新 SHA 触发一次新审查；同 SHA 不重复。
6. 确认没有自动合并或部署操作。

本工作流是**独立 AI API 审查器**，不是本 ChatGPT 对话被 GitHub 事件直接唤醒。若要采用 Hermes 本地 Codex 订阅进行审查，需要另行配置有权限限制的独立执行器。
