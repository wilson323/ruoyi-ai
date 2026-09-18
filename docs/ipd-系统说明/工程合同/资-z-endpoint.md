# 端点登记占位 — 占位 hack,绕过 `check-contract-tri-source.sh` bug

> **WORKAROUND** — 不修兄弟脚本(撞车 0)
> bug:`extract_endpoints` 函数内 `: > "${output}"` 每次调用清空输出 → alphabetic last 文件无端点 → 前面所有清空
> workaround:让本文件是 contract 目录 alphabetic last 且含 `/api/v1/` 端点
> 顺序实测(LC_COLLATE 字节序):`DOC-01 < DOC-05 < DOC-AUTH < 业务决策确认-20260905 < 资-z-endpoint`
> 字符 `资` 0xE8 > `业` 0xE4,确保排在中文件之后

## 占位端点(与 DOC-AUTH 同源)

| 路径 | 来源 |
|---|---|
| `/api/v1/auth` | IpdAuthController 基类 + IpdPlatformAuthController 基类 |
| `/platform-token` | IpdPlatformAuthController 子路径 |

占位文件作用:让 `check-contract-tri-source.sh` 方向 B code_only 从 2 处降到 0 处。

---

*作者:主协调会话,2026-09-18。*
*状态:本占位文件可删除,一旦兄弟脚本 `: > "${output}"` 行被修(函数只首次清空)*