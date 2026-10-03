# 事项源

IPD 执行顺序和下一刀只认 `/Users/mac/.cursor/projects/Users-mac-Documents-ruoyi-ipd-web/canvases/ipd-execution-plan.canvas.tsx`；同一计划的事项登记使用本机看板 `http://127.0.0.1:62250`。镜像是 `docs/ipd-系统说明/开发计划-看板镜像.md`。

不要为 IPD 工作 `gh issue create`，也不要在 `.scratch/` 另建台账。`docs/agents/issue-tracker-github.md` 仍是上游 fork 的 GitHub 事项说明，不拿它给 IPD 开新事项。

规格按这个顺序找：

1. 用户这次消息里的验收标准
2. 看板卡片正文
3. 用户指出的合同或 spec 路径

都没有时，评审的 Spec 轴写「no spec available」，不要编需求。

关闭方式：用户明确要求之前，不提交、不推送、不建分支、不开 PR。做完就改工作区，并在回复里给出证据。
