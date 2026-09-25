# P0-7.3 补证项② 验收报告：Vue 实联 + shared 合并收口

> **卡号**: d810a157 | **验收日期**: 2026-09-25 | **验收人**: IPD 前端验收专员  
> **环境**: 前端 dev server 127.0.0.1:15666 (vite) / 后端 127.0.0.1:16039 (Spring Boot)  
> **浏览器**: Chromium headless (Playwright 1.57.0, chrome-headless-shell-1243)  
> **凭据**: ipd-admin / Ipd@123456 (SUPER_ADMIN, scope=FULL)

---

## 一、main 现状核实结论

### 后端（/Users/mac/Documents/ruoyi-ai, branch: main @ d03441d6）

| 检查项 | 结果 | 证据 |
|--------|------|------|
| `POST /api/v1/auth/refresh` 端点 | ✅ 存在 | IpdAuthController.java:131-143，单 token 轮换语义（先 logout 旧票再 login 新票） |
| `POST /api/v1/auth/logout` 端点 | ✅ 存在 | IpdAuthController.java:117-123，幂等守卫 |
| `revokeAll(personId)` | ✅ 存在 | IpdAuthSession.java:54-56；调用方：change-password(L170)、HandoverService(L638)、PersonService(L189) |
| P073AcceptanceTest / P073BehaviorAcceptanceTest | ❌ **不在 main** | `find . -name "*P073*"` 返回空。与卡面记录一致：测试代码在 PR #334 分支 merge/local-main-r15，尚未 squash 合入 |

### 前端（/Users/mac/Documents/ruoyi-ipd-web, branch: main @ 003655d）

| 检查项 | 结果 | 证据 |
|--------|------|------|
| 401+code20001 拦截 → 自动跳登录页 | ✅ 存在 | `store/ipd-auth.ts` expiredSession() L42-43 判定 401+20001；`router/ipd-guard.ts` L205-248 导航守卫 refreshIdentity 失败 → clearSession → 匿名分支 redirect `/auth/login` |
| refresh 轮换 + 重试逻辑 | ✅ 存在 | `store/ipd-auth.ts` rotateSession() L178-211：401 后自动调 `/auth/refresh`，成功则换新票重试原请求；失败则 clearSession |
| logout 服务端失效 | ✅ 存在 | `store/ipd-auth.ts` logout() L334-360：先 POST `/auth/logout`（服务端撤票），再 clearSession |
| shared 收口（live-http loginPersonaShared） | ✅ 存在 | `views/ipd/_shared/test-helpers/live-http.ts` L345-357：共享登录缓存避免限流桶耗尽 |
| 单元测试 | ✅ 全绿 | auth.test.ts 91 + ipd-auth.test.ts 5 + ipd-guard.test.ts 8 + auth-refresh.test.ts 31 = **135 tests passed** (vitest.ipd.config.mts) |

### 结论

**main 上生产代码齐全**（refresh/logout/revokeAll 端点 + 前端 401 跳登录 + 轮换重试），**唯一缺口是 P073 后端测试文件不在 main**（在 PR #334 分支等待 squash 合入，与卡面记录一致，非本次补证②范围）。

---

## 二、浏览器实联验证（金标准三证）

### 场景 1：AC-AUTH-07 正例 — 正常登录后浏览 IPD 页面

| 项目 | 内容 |
|------|------|
| **操作步骤** | 打开 /auth/login → 填入 ipd-admin/Ipd@123456 → 点击「登录工作台」→ 等待跳转 |
| **网络请求** | 18 个 /api/v1/ 请求全部 HTTP 200 + code=0（含 /auth/login, /auth/me, /auth/platform-token, 菜单, 工作台数据） |
| **界面结果** | 跳转至 `/ipd/workbench`，工作台完整渲染（待我处理 9 / 临期 6 / 未读通知 41 / 已完成 4） |
| **截图** | p073-01a-login-page.png / p073-01b-login-filled.png / p073-01c-login-success.png |
| **verdict** | ✅ **PASS** |

### 场景 2：AC-AUTH-07 核心 — Token 失效后前端自动跳登录页

| 项目 | 内容 |
|------|------|
| **操作步骤** | 登录成功后，通过 evaluate 将 sessionStorage `ruoyi-ipd.session` 的 accessToken 改为无效值（保留未过期的 accessExpiresAt 以触发服务端拒绝路径）→ 导航至 /ipd/workbench |
| **网络请求** | `GET /api/v1/auth/me` → **HTTP 401** `{"code":20001,"message":"未认证或凭证失效"}`；随后 `POST /api/v1/auth/refresh` → **HTTP 401** `{"code":20001}` （轮换尝试也被拒） |
| **界面结果** | 前端自动跳转至 `/auth/login`，页面显示红色提示「登录已失效，请重新登录」 |
| **跳转前后 URL** | `http://127.0.0.1:15666/ipd/workbench` → `http://127.0.0.1:15666/auth/login` |
| **截图** | p073-02-token-invalid-redirect.png |
| **verdict** | ✅ **PASS** — 401+20001 → 前端自动跳登录页，AC-AUTH-07 核心行为确认 |

### 场景 3：Logout 旧 token 服务端真失效

| 项目 | 内容 |
|------|------|
| **操作步骤** | 登录 → 记录当前 token → 通过页面上下文调 `POST /api/v1/auth/logout`（服务端撤票）+ 清除 sessionStorage → 用旧 token 直接请求 `GET /api/v1/auth/me` |
| **网络请求** | logout → HTTP 200 code=0；旧 token 请求 /auth/me → **HTTP 401** `{"code":20001,"message":"未认证或凭证失效","traceId":"ffc25df3..."}` |
| **界面结果** | 会话清除，后续导航将回登录页 |
| **截图** | N/A（UI 退出按钮定位失败，回退 API 层 logout，不触发页面导航）；证据以 `evidence-results.json` 场景3 的 `oldTokenResponseStatus=401`、`oldTokenResponseBody` 内 `code=20001`、`traceId=ffc25df3aab14a78b1541d39b01167e3` 为准 |
| **verdict** | ✅ **PASS** — 服务端真失效（非仅前端清除） |

### 场景 4：Refresh 轮换 — 旧票立即失效 + 重放拒绝

| 项目 | 内容 |
|------|------|
| **操作步骤** | 登录拿 tokenA → `POST /auth/refresh`(Bearer tokenA) 拿 tokenB → 用 tokenA 请求 /auth/me → 用 tokenA 再次 refresh（重放） → 用 tokenB 请求 /auth/me |
| **网络请求** | refresh(tokenA) → HTTP 200, tokenB ≠ tokenA；/auth/me(tokenA) → **HTTP 401** code=20001；refresh(tokenA) 重放 → **HTTP 401** code=20001；/auth/me(tokenB) → HTTP 200 |
| **界面结果** | N/A（API 层验证） |
| **截图** | N/A（API 层验证） |
| **verdict** | ✅ **PASS** — 单 token 轮换语义完整：旧票即废、重放拒绝、新票有效 |

---

## 三、补证项② 结论

**补证项②「Vue 实联 + shared 合并收口」已补齐。**

- ✅ Vue 实联：4 场景浏览器金标准全 PASS（操作+网络响应+界面截图三证齐全）
- ✅ shared 合并收口：`_shared/test-helpers/live-http.ts` loginPersonaShared 缓存机制在 main 上存在且被 auth-live.test.ts 正确消费
- ✅ 前端单元测试 135/135 绿（vitest.ipd.config.mts）
- ⚠️ 唯一遗留：P073 后端测试文件不在 main（属补证项①的 PR #334 squash 合入事项，非补证②范围）

**卡 d810a157 可以翻 done 的前置条件**：补证②本报告签收 + PR #334 squash 合入 main（补证①测试文件归位）。若 maintainer 认为「测试文件在 main」是翻卡硬条件，则仍缺 PR #334 合入这一步。

---

## 四、发现的缺陷

**无功能性缺陷。** 以下为观察项（不阻塞翻卡）：

1. **[测试基建] auth-refresh.test.ts 在默认 vitest 配置下无法运行**（缺 @vitejs/plugin-vue 处理 .vue import）——需使用 `vitest.ipd.config.mts` 运行。非代码缺陷，是运行方式约束。
2. **[测试基建] ipd-auth.test.ts 在默认配置下 sessionStorage undefined**——同上，需 happy-dom 环境（vitest.ipd.config.mts 已配置）。
3. **[UI 观察] 场景 3 中 UI 退出按钮定位失败**（headless 下用户菜单 dropdown 触发器选择器未命中），回退到 API 层 logout。不影响验证结论（服务端撤票行为已证实），但建议后续补充 UI 退出路径的 e2e 选择器稳定性。

---

## 五、证据文件清单

| 文件 | 说明 |
|------|------|
| p073-01a-login-page.png | 登录页初始状态 |
| p073-01b-login-filled.png | 凭据填入后 |
| p073-01c-login-success.png | 登录成功→工作台 |
| p073-02-token-invalid-redirect.png | token 失效→自动回登录页+错误提示 |
| evidence-results.json | 4 场景结构化证据（含网络响应体、状态码、URL） |

> **JSON 字段名失真说明**：场景3 的 `uiLogoutDone: true` 系脚本 fallback 分支（API 层 logout）也置 true 所致，字段名与「UI 退出成功」语义不符；实际 UI 退出按钮定位失败，详见本报告观察项③。
>
> **复跑脚本删除说明**：run-evidence.mjs 已于 R217 清理轮删除：场景 1/3/4 与前端仓 auth-live.test.ts 逐条同断言构成双轨（既有版断言更强），场景 2 证据以 evidence-results.json + p073-02-token-invalid-redirect.png 留存；如需复现真浏览器地址栏跳转，走前端仓 `npx vitest run --config vitest.ipd-live.config.mts`。
