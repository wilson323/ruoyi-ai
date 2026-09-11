---
name: ipd-guard-vite-startup
description: IPD 前端 vite dev server 启动防御。macOS 下 pnpm 包装层与 nohup 后台启动两个独立根因都会把 vite 主线程锁在 read syscall，端口 LISTEN 但 HTTP 全部超时。本技能沉淀两条根因级证据 + 判别手段 + 修复模板。
---

# ipd-guard-vite-startup

Vite dev server 启动卡死的两类根因防御。与"代理配置错"完全无关，改 vite.config 没用。

## 何时使用

- 在本机 macOS 启动 vite dev server（端口 15666）做前后端联调
- 端口 LISTEN 正常但 curl / 浏览器 / HTTP 客户端全部超时
- 重启 vite 后疑似 .env*.local 改动未生效
- 排查"代理不转发 / 静态资源 404 / API 全 hang"

## 路由进来的两个根因（必须都排查）

### 根因 A：pnpm 包装层卡 stdin read syscall

- **症状**：`pnpm vite` / `pnpm run dev` 起的进程 LISTEN 正常，但 HTTP 请求全部 hang（curl 5-8s timeout / `curl: (52) Empty reply`）
- **原因**：pnpm 进程包装层会留 stdin/stdout pipe 给父 shell，node v8 主线程 libuv 把 stdin 当成活跃 fd poll，macOS 下 `uv__stream_io` 偏向 stdin read → event loop 被锁在 read syscall，不进 accept
- **定位**：`sample <pid> 1 -file /tmp/s.txt`，看主线程栈是否停在 `uv__stream_io → read (libsystem_kernel)`；日志同样输出 `VITE v7.x.x ready in ...` 但 HTTP 不通
- **修复**：直接 `node node_modules/vite/bin/vite.js` 起，跳过 pnpm 包装层
- **验证**：HTTP 立即通，curl 200，代理正常转发

### 根因 B：后台启动必须带活 pty（nohup 也踩）

- **症状**：`nohup node vite.js ... &` + 显式 `< /dev/null`，进程状态 SN、CPU 时间近零、端口 LISTEN 正常但所有 HTTP 超时
- **原因**：与根因 A 不同根因——libuv event loop 在无 pty 环境下同样会卡在 `uv_run` 等待某个永不就绪的 fd；**node 直起 + nohup 也踩**，不只是 pnpm 问题
- **定位**：`lsof -i :端口` 见 LISTEN + `curl` 长期 000 超时 + `sample` 显示主线程停在 `uv_run`
- **修复**：把 vite 挂到**有活 pty 的终端**上——agent 后台 terminal / `tmux new -s vite` / `screen -S vite` / 真实交互 shell 前台运行
- **杀进程**：卡死进程 `kill -15` 可能无效（信号被堵），需 `kill -9`；杀掉后端口才真正释放（新实例会因端口占用静默失败，先 `lsof -i :端口` 确认 LISTEN 归属再启动）

## 不受影响的场景

- `pnpm run check:type` / `pnpm exec vitest run` / `pnpm run build:antd` —— 不长跑 dev server，无此坑
- `java -jar` 后端服务 —— 不读 stdin，nohup 后台启动不受此坑影响

## .env*.local 修改后的铁律

- `import.meta.env` 是 vite **启动时**注入，改 .env*.local 后**必须重启 vite** 才生效
- 验证 env 是否生效：看页面渲染 / 控制台 warn，别信"文件已存在"

## 必做检查清单

1. 起动前：`lsof -i :15666` 确认端口归属（防静默绑定失败）
2. 起动命令：`node node_modules/vite/bin/vite.js` 直起，不要 `pnpm vite` / `pnpm run dev`
3. 后台运行：挂在 agent background terminal / tmux / screen，不要裸 `nohup ... &`
4. 起动后：`curl http://127.0.0.1:15666/` 探活一次，不要只看 `VITE ready` 日志
5. 不通时：`sample <pid> 1` 看主线程栈（uv__stream_io / uv_run），定位是根因 A 还是 B
6. 杀卡死进程：`kill -9`，再 `lsof -i :15666` 确认释放

## 失败归因三分类

| 归因 | 典型表现 | 修法 |
|---|---|---|
| 知识错 | 以为是代理/vite.config 错，改配置半天没用 | 走本技能两个根因排查，先 sample 看栈 |
| 环境错 | vite 起在无 pty 的 nohup 后台 | 挂到 tmux / agent background terminal |
| 检查错 | 只看 LISTEN 不 curl 探活 | 每次起动后必 curl 一次根路径 |

## 禁止清单

- ❌ 不要 `pnpm vite` / `pnpm run dev` 启动（macOS 卡 stdin）
- ❌ 不要裸 `nohup node vite.js &` 后台（无 pty 同样卡）
- ❌ 不要只看 `VITE ready` 日志就认为通了（两根因下日志都正常）
- ❌ 不要在 vite 卡死时反复重启而不 `sample` 定位根因
- ❌ 不要 `kill -15` 后立刻起新实例（信号被堵，端口未释放）

## 版本指纹

- 验证时 vite 版本：v7.2.7（`VITE v7.2.7 ready in 2143 ms` 实测日志）
- 验证时 node：v8 主线程 libuv（uv__stream_io / uv_run）
- 验证时 OS：macOS（darwin 26.x）
- 最近验证：2026-09-11（本 SKILL 由 2 条根因级 memory 蒸馏，原 ID `1bdcb5d4` + `1e1172bb`）
- 过期触发：vite 升级 major 版本 / node 升级到不同 libuv 行为 / macOS 大版本升级

## 上游入口

`ipd-guard/SKILL.md` 路由表 → 本技能对应"起 vite dev server / 排查端口 LISTEN 但 HTTP 超时"场景。