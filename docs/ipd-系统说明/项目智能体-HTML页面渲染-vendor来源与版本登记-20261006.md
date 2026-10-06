# 项目智能体 HTML 页面渲染：vendor 来源与版本登记（2026-10-06）

> 本文件是 `render_html_page` 受治理工具与 `answer-me-with-html-ipd` 技能的第三方来源
> 登记页。升级、替换、下线该渲染引擎前必须先读本页。

## 一、上游来源（钉死版本）

| 项 | 值 |
| --- | --- |
| 仓库 | https://github.com/QingYunA/answer-me-with-html |
| commit | `f3082c912c1637d7eff38e7a4c356545b756c75f`（main） |
| 上游版本 | 0.4.12 |
| 许可证 | MIT（原文随包 vendor 于 `ipd-am/LICENSE`） |
| 拉取日期 | 2026-10-06 |
| 引擎形态 | `am.mjs` 单文件 bundle（368 241 字节，Node 20+，零安装，render 不联网） |

## 二、vendor 文件与完整性锁定

classpath 位置：`ruoyi-modules/ruoyi-ipd/src/main/resources/ipd-am/`

| 文件 | 上游路径 | sha256 |
| --- | --- | --- |
| `am.mjs` | `skills/answer-me-with-html/scripts/am.mjs` | `f926b37c5ecb550731fcf1500578053bfcf9aa4436e04c57ac8a7e303eed4f91` |
| `LICENSE` | `LICENSE` | `752a1a34b40ae8e64e86f546ec699a691aff573179e1f96997e82757b2ee5d3f` |

- `ORIGIN.json` 与上表同源；`AnswerMeHtmlRenderer` 每次加载从 classpath 提取 am.mjs 到
  引擎缓存目录时重算 sha256 并与 ORIGIN.json 比对，不一致 fail-closed（对齐 SKILL.md
  原始字节锁定哲学）。
- **升级纪律**：上游迭代快。升级必须替换 am.mjs → 重算 sha256 → 更新 ORIGIN.json
  （commit/upstreamVersion/fetchedAt）→ 同步本页表格 → 全量回归渲染测试。**禁止任何
  运行期自动更新/联网拉取**（引擎 config 预写 `open=off`、`update_check=off`，每次渲染
  独立临时 HOME）。

## 三、本项目接入面（改了哪里）

| 层 | 文件/条目 | 说明 |
| --- | --- | --- |
| 渲染服务 | `agent/kernel/AnswerMeHtmlRenderer.java` | 宿主 node 子进程执行 `am render`，超时 60s/输出 2MB/草稿 64KB 上限，node 缺失/超时/超限 fail-loud 中文报错 |
| 受治理工具 | `agent/kernel/HtmlPageRenderTool.java` | ID `render_html_page`，入参 draft+fileName(.html)；成功经 `ArtifactDeliveryTarget.deliver` 落 DRAFT 产物版本；失败以 text 透传 CLI 行号+组件+正确示例供模型自修 |
| 执行声明绑定 | `agent/kernel/ExecutionClaimBoundTool.java` | `Mono.using(claims::openApproved)` 包装：渲染工具执行期持 Claim，交付链 `ProjectAgentExecutionClaims.requireAuthorized/requireDelivery` 放行（ fileName 相等、filePath/description null、force false 逐字段比对） |
| 装配 | `agent/kernel/AgentScopeProjectAgentKernel.java` | 与 PROJECT_KNOWLEDGE_SEARCH 同位同链（KernelGovernedTool.wrap + OwnershipGuarded），条件：`spec.toolIds()` 含该 ID |
| 目录 | `agent/catalog/ProjectAgentToolCatalog.java` | `HTML_PAGE_RENDER` 常量 + DESCRIPTORS 条目（WRITE 语义=仅产物草稿写入）；node 不可用时该工具 status 不可用且**不拖垮整包**（CapabilityService 按需可选逻辑） |
| 技能 | `resources/ipd-skills/answer-me-with-html-ipd/SKILL.md` | 中文改编版（判定规则/草稿速查/九组件选型/STE 规则忠实上游 §1/§3/§4/§5；工作流改为调 `render_html_page` 工具）；sha256 实算入清单 |
| 清单 | `resources/ipd-skills/capability-packs.json` | 7 包 skills[] 追加 `answer-me-with-html-ipd@1.0.0`、tools[] 追加 `render_html_page`（清单 44→45 技能） |
| DB 种子 | `docs/script/sql/update/2026-10-06-ipd-answer-me-html-pack-sync.sql` | 幂等 INSERT IGNORE，7 包各加 SKILL 行+TOOL 行；已落库幂等重放验证 |
| 配置 | `ipd.project-agent.html-render.*` | `ProjectAgentConfiguration` @Value 默认值（enabled=true/node-bin=node/timeout-seconds=60/max-output-bytes=2MiB/draft-max-chars=65536）；本地覆盖走 .codex 配置目录（gitignored），密钥不入库 |

## 四、边界（非功能降级，是安全边界）

- **不支持 `am video`/TTS/mp4**：依赖外网与 Chrome，违反沙箱边界（沙箱镜像
  `python:3.13-alpine`、network=none 未动）。渲染引擎与九种页面组件完整保留。
- 渲染在**宿主机**执行（非沙箱）：每次渲染独立临时目录用后清理、超时强杀进程。
- 技能注入方式：登记进全部 7 个能力包、创建运行时按需勾选；未改 `ipd_action_skill_map`。
  未勾选该工具/技能的运行：工具不装配（结构性保证）、系统提示零变化。

## 五、运行态验收锚点（2026-10-06，16039）

- run `2107462234526482433`：`render_html_page` 真实调用成功（渲染→Claim 交付→DRAFT 落库
  75 070 字节，sha256 `dd4eb5ab…1477a4`，4 panels/tree×1/flow×1/callout×2，STE 警告 1 条
  如实回报模型）；产物 `<!doctype html>` 前缀命中前端隔离框判定，无外部资源依赖。
- 已知 PARTIAL：勾选 competitor-analysis-ipd 同跑时完成门禁连续拒绝（SKILL_CONTRACT_MISMATCH/
  UNSUPPORTED_MEASUREMENT）——两技能对正文形态要求互斥（详尽引用正文 vs 2-3 行收尾），
  属产品层技能合同冲突，待 owner 拍板（改门禁或改技能不在本次授权内）。详见
  `log.md` 2026-10-06 同日条目。
