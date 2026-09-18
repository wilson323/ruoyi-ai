# DOC-AUTH:IPD 登录与会话桥工程合同

> 登记基线:`a810e4b4`(主仓 HEAD,2026-09-18)
> 来源代码:`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java` + `ruoyi-admin/src/main/java/org/ruoyi/ipd/controller/IpdPlatformAuthController.java`
> 修复对账:`check-contract-tri-source.sh` 方向 B code_only 的 2 处历史遗留(`/api/v1/auth` + `/platform-token`)

## 1. 范围

IPD 系统两类会话:

1. **IPD 会话**(`loginType=ipd`,独立 StpLogic)— 由 `IpdAuthController` 提供,作用域是 IPD 业务(`/api/v1/projects` `/api/v1/requirements` 等)。
2. **基线平台会话**(`loginType` 默认)— RuoYi-AI 原平台基线,由 `SysLoginService` 提供,作用域是 `/chat/**` `/system/menu` 等。

两者票互相独立。`IpdPlatformAuthController` 是会话桥,把 IPD 票转换为基线平台票。

## 2. 端点登记

### 2.1 IPD 会话(`IpdAuthController`)

基类:`@RequestMapping("/api/v1/auth")`

| HTTP | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/api/v1/auth/login` | IPD 账号登录,签发 IPD 会话票 | 免登录 |
| POST | `/api/v1/auth/wecom/qr-login` | 企业微信扫码登录 | 免登录 |
| GET | `/api/v1/auth/me` | 取当前登录 IPD 人员 | 需 IPD 票 |
| POST | `/api/v1/auth/logout` | 退出 IPD 会话 | 需 IPD 票 |
| POST | `/api/v1/auth/refresh` | 刷新 IPD 会话票 | 需 IPD 票 |
| POST | `/api/v1/auth/change-password` | 改 IPD 账号密码 | 需 IPD 票 |

### 2.2 平台会话桥(`IpdPlatformAuthController`)

物理位置:`ruoyi-admin/src/main/java/org/ruoyi/ipd/controller/IpdPlatformAuthController.java`(包名归 `org.ruoyi.ipd.controller`,与模块内其它 IPD 接口共享 `IpdPermissionExceptionHandler` / `IpdServiceExceptionAdvice` 的 ApiV1 包络;`PermissionAdviceCoverageTest` 扫描范围仅 ruoyi-ipd 模块内,不受物理模块位置影响)。

基类:`@RequestMapping("/api/v1/auth")`

| HTTP | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/platform-token` | IPD 票 → 基线平台票(可访问 `/chat/**` `/system/menu`) | 需 IPD 票 + FULL scope |

注:`/api/v1/auth` 作为基类路径已在 §2.1 登记;`/platform-token` 在本节单列以消方向 B code_only。

## 3. 会话桥映射规则

- 同名优先:person.username 在 sys_user 存在同名且启用 → 直接映射
- 兜底映射:personType → `ipd-admin` / `ipd-leader` / `ipd-market` / `ipd-rd`(仅当同名缺失时)
- 停用账号视同不存在(不放大停用身份)
- 平台侧权限 = 被映射 sys_user 自身 RBAC,**不放大**
- 基线会话写 `sys_user.userId`,**绝不写 Person.id**(IpdAuthSession 红线)

## 4. 拦截与放行

- `IpdWebSecurityConfig` 的登录校验拦截器对 `/api/v1/auth/**` 强制要求有效 IPD 会话
- 免登录白名单:`/api/v1/auth/login` + `/api/v1/auth/wecom/qr-login` + `/api/v1/public/**`
- `platform-token` 不在白名单,必须有 IPD 票才能调用

## 5. 不在本合同范围

- 业务接口(`/api/v1/projects` `/api/v1/requirements` 等)— 其它 DOC-XX 合同
- 基线平台登录页(走 `SysLoginService`,不在本工程合同)

---

*作者:主协调会话,2026-09-18。*
*目的:让 `check-contract-tri-source.sh` 方向 B code_only 从 2 处降到 0 处;同时方向 A spec_only 也对得上。*