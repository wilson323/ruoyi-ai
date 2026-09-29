# Track E — MCP 与 Skill 配置中心 UIUX 任务分解（草稿 v1，2026-09-28）

> **性质**：可执行任务分解草稿，改造存量 4 页（非新建域）：`views/mcp/tool`、`views/mcp/market`、`views/agent/agent` + `api/mcp/**`、`api/agent/**`。每步真实代码、无占位符。
> **执行纪律**：Global Constraints #10（Commit→暂存+diff 摘要待批）；#19 凭据 write-only 交互红线；#20 不做绕过后端契约的配置项；#21 颜色系统。
>
> **事实源（只读，写码前必读）**：
> 1. 主计划 §0.5（存量 4 页 + 6 项 UIUX 痛点 + 21st 参照 id）/ §0.6（**后端真实契约**：LOCAL 只读 `command`+`args`、REMOTE 只读 `baseUrl`；`env`/`headers` 后端不读；`configJson`/`authConfig` write-only 三层：`@JsonIgnore`+`@ExcelIgnoreUnannotated`+`applyWriteOnlyConfigPolicy`）/ §0.7（颜色）
> 2. 后端实证（2026-09-28 复核）：`McpToolVo.java:56 @JsonIgnore configJson`、`McpMarketVo:54 authConfig` 同款；`McpToolController.export` → `ExcelUtil.exportExcel(list,"MCP工具",McpToolVo.class,response)`；`McpMarketTool` 实体含 `toolMetadata`，`McpMarketToolMapper` 已 select `t.tool_metadata`
> 3. 21st 参照（**只作设计参照，React/shadcn 代码禁止直入源码**）：MCP Tool 12382（三态）、Schema Viewer 29970、Settings Sidebar 28366、Vertical Settings Tabs 24937、Settings Tabbed Sections 28358、Agent Plan 2127
> 4. 颜色：`src/styles/ipd-tokens.css`（全局 `--ipd-*`，`views/mcp|views/agent` 当前零 token、零定制 = **从零引入 token 对齐 IPD 视觉**，§0.7）

## 0. 任务总览与依赖顺序

| Task | 内容 | 依赖 | allowedPaths（写权限） |
|---|---|---|---|
| **E-Verify** | 后端回归单测：序列化 + Excel 导出不含 `configJson`/`authConfig`（`@Tag("dev")`） | 无（可即刻并行） | `ruoyi-ai: ruoyi-modules/ruoyi-chat/src/test/**`（不改生产代码） |
| **E5** | 结构化连接表单 + 方案 a 空态卡 +「高级/JSON 视图」 | 无（与 E-Verify 并行） | `views/mcp/_shared/**`（新）、`views/mcp/tool/{data.tsx,tool-drawer.vue}`、`vitest.ipd.config.mts`（白名单扩展，串行窗口） |
| **E1** | 工具台三栏 + 连接测试面板 + 反向依赖 | E5（复用 `connection-config` 空态卡语义） | `views/mcp/tool/index.vue`、`views/mcp/_shared/**` |
| **E2** | 市场工具墙 + `tool_metadata` 呈现（Schema Viewer 等价） | E5（`views/mcp/_shared` 基座） | `views/mcp/market/index.vue`、`api/mcp/market/model.d.ts`、`views/mcp/_shared/**`；后端补透出走 `org/ruoyi/controller/mcp/**` + `domain/vo/mcp/**` |
| **E3** | Skill 分组绑定台（69 skill 分组搜索 + SKILL.md 预览 + 反向依赖） | E5 基座（可与 E1/E2 并行） | `views/agent/agent/**`、`views/mcp/_shared`（反向依赖纯函数可共置 `views/agent/agent/`） |
| **E-A1** | 带鉴权 MCP（**需 ADR**）：`env`/`headers` 读取落地 + UI 分区解锁 | E5（表单基座）；**未落地前 `env`/`headers` 不得出现在 UI**（#20） | `ruoyi-ai: LangChain4jMcpToolProviderService.java` + `ChildProcessSecretSanitizer.java` + `src/test/**`；前端 `views/mcp/_shared/mcp-connection-form.vue` 分区解锁 |

**顺序**：（E-Verify ∥ E5）→（E1 ∥ E2 ∥ E3）→ E-A1（ADR 批准 + 后端落地后解锁 E5 的 env/headers 分区）。

> ✅ 已实施（E5-⑥，2026-09-28）：`include` 已扩至 9 条（增 `views/mcp/**`、`views/agent/agent/**`、`api/mcp/**`）；下文为实施前预案存档，事实以 `vitest.ipd.config.mts` 为准。

**测试白名单说明（必读）**：`vitest.ipd.config.mts` 的 `include` 现有 6 条**不含** `views/mcp|views/agent|api/mcp`。处置（二选一，本计划采用 ①）：
1. **补白名单（采用）**：`include` 增 `apps/web-antd/src/views/mcp/**/*.test.ts`、`apps/web-antd/src/views/agent/agent/**/*.test.ts`、`apps/web-antd/src/api/mcp/**/*.test.ts`；`coverage.include` 增 `views/mcp/**`——该文件是**两轨共用门禁配置**，改动走串行窗口（见编排表），一次提交、B 轨确认零影响；
2. 单测放 `_shared`（`views/mcp/_shared/*.test.ts` 仍需 include 命中——`views/ipd/**` 白名单不含 `views/mcp`，**不成立**，仅当 ① 被否时改为把纯函数暂放 `views/ipd/_shared` 过渡，此路不推荐（跨域污染））。
> 所有 E 的纯逻辑落 `views/mcp/_shared/*.ts`（`connection-config.ts`/`tool-test.ts`/`market-metadata.ts`/`skill-group.ts`），组件薄壳，测试以纯函数为主 + `@vue/test-utils` mount 为辅。

**颜色红线落点（一句话）**：Track E 新 UI 全部取 `var(--ipd-*)`（全局 `styles/ipd-tokens.css` 已注入，三页**不再 import ipd-theme.css、不写 hex**），组件主色唯一入口 `preferences.ts theme.*` + `bootstrap.ts updatePreferences`（已落地只读），圆角 6px(控件)/8px(卡片)/4px(Tag)，禁 antd 默认蓝/`:root{--primary}`/第三套色板——目标是把三页从「裸 antd 默认外观」拉齐 IPD 视觉。

**每 Task 验证命令（仓根）**：
```bash
pnpm run check:type                                             # 0 错误（存量 4 处 TS2493 已清零，2026-09-28 p13 复测 EXIT 0）
pnpm exec vitest run --config vitest.ipd.config.mts             # EXIT 0
pnpm run build:antd                                             # EXIT 0
npx 21st review apps/web-antd/src/views/mcp apps/web-antd/src/views/agent --strict   # 仅覆盖 ts/tsx/css；vue 模板层走 chrome-devtools MCP 快照（Constraint #18）
node scripts/check-ipd-frontend-drift.sh
```

---

## E-Verify 后端回归门禁：write-only 三层完备性单测（✅ 已实施 2026-09-28，差异登记见主计划 §9）

**目标**：把 §0.6 的「读码结论」升级为**可执行回归门禁**——断言 ① Jackson 序列化不含 `configJson`/`authConfig`；② Excel 导出物不含这两个字段（列头无「配置信息/鉴权配置」、单元格无原值）；③ 字段注解面（`@JsonIgnore` 在、`@ExcelProperty` 不在、类上 `@ExcelIgnoreUnannotated` 在）。**零生产代码改动**。

### Files
| 路径 | 动作 |
|---|---|
| `ruoyi-ai/ruoyi-modules/ruoyi-chat/src/test/java/org/ruoyi/domain/vo/mcp/McpWriteOnlyConfigRegressionTest.java` | 新建（`@Tag("dev")`） |

### 步骤（真实代码，整文件落盘）
```java
package org.ruoyi.domain.vo.mcp;

import cn.idev.excel.FastExcel;
import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * write-only 三层完备性回归门禁（Track E-Verify，主计划 §0.6）：
 * ① Jackson 序列化不含 configJson / authConfig（@JsonIgnore 层）；
 * ② Excel 导出不含两字段（@ExcelIgnoreUnannotated + 无 @ExcelProperty 层）；
 * ③ 编辑留空保留原值（applyWriteOnlyConfigPolicy）依赖①②成立——响应/导出都不回显，
 *    前端「空态卡 + 留空提交保留原值」（方案 a）才有意义。
 * 任何一层被后续改动破坏，本测试立即红。
 */
@Tag("dev")
class McpWriteOnlyConfigRegressionTest {

    private static final String SECRET_MARKER = "SECRET-MARKER-8f3a1c";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private McpToolVo toolWithSecret() {
        McpToolVo vo = new McpToolVo();
        vo.setId(1L);
        vo.setName("probe-tool");
        vo.setDescription("probe");
        vo.setType("LOCAL");
        vo.setStatus("ENABLED");
        vo.setConfigJson("{\"command\":\"npx\",\"args\":[\"--token=" + SECRET_MARKER + "\"]}");
        vo.setCreateTime(new Date());
        vo.setUpdateTime(new Date());
        return vo;
    }

    private McpMarketVo marketWithSecret() {
        McpMarketVo vo = new McpMarketVo();
        vo.setId(1L);
        vo.setName("probe-market");
        vo.setUrl("https://example.invalid/mcp");
        vo.setAuthConfig("{\"apiKey\":\"" + SECRET_MARKER + "\"}");
        vo.setStatus("ENABLED");
        return vo;
    }

    @Test
    void jacksonSerializationOmitsWriteOnlyFields() throws Exception {
        String toolJson = objectMapper.writeValueAsString(toolWithSecret());
        assertThat(toolJson).doesNotContain("configJson").doesNotContain(SECRET_MARKER);

        String marketJson = objectMapper.writeValueAsString(marketWithSecret());
        assertThat(marketJson).doesNotContain("authConfig").doesNotContain(SECRET_MARKER);
    }

    @Test
    void excelExportOmitsWriteOnlyFields() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FastExcel.write(out)
            .head(McpToolVo.class)
            .sheet("MCP工具")
            .doWrite(List.of(toolWithSecret()));

        List<Map<Integer, String>> rows =
            FastExcel.read(new ByteArrayInputStream(out.toByteArray())).sheet().doReadSync();
        assertThat(rows).isNotEmpty();
        for (Map<Integer, String> row : rows) {
            for (String cell : row.values()) {
                assertThat(cell == null ? "" : cell)
                    .doesNotContain(SECRET_MARKER)
                    .doesNotContain("configJson");
            }
        }
        // 列头面：导出表头不含 write-only 字段的展示名
        String headDump = rows.stream()
            .flatMap(row -> row.values().stream())
            .reduce("", (a, b) -> a + "|" + (b == null ? "" : b));
        assertThat(headDump).doesNotContain("配置信息").doesNotContain("鉴权配置");
    }

    @Test
    void writeOnlyFieldsKeepAnnotationContract() throws Exception {
        assertThat(McpToolVo.class.getAnnotation(ExcelIgnoreUnannotated.class)).isNotNull();
        assertThat(McpMarketVo.class.getAnnotation(ExcelIgnoreUnannotated.class)).isNotNull();

        assertWriteOnlyField(McpToolVo.class, "configJson");
        assertWriteOnlyField(McpMarketVo.class, "authConfig");
    }

    private void assertWriteOnlyField(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        assertThat(field.getAnnotation(JsonIgnore.class))
            .as("%s.%s 必须保留 @JsonIgnore（响应不回显）", type.getSimpleName(), fieldName)
            .isNotNull();
        assertThat(field.getAnnotation(ExcelProperty.class))
            .as("%s.%s 不得挂 @ExcelProperty（导出不出现）", type.getSimpleName(), fieldName)
            .isNull();
    }
}
```

### 验证命令（ruoyi-ai 仓根，Global Constraints #8）
```bash
export PATH="$HOME/tools/maven/bin:$PATH"; export JAVA_HOME="$HOME/tools/jdk-17/Contents/Home"
mvn -o -pl ruoyi-modules/ruoyi-chat -Dtest=McpWriteOnlyConfigRegressionTest test
```
> 必须 `@Tag("dev")`（已含）——否则 Surefire `<groups>` 静默跳过假绿；错峰 + 单模块 + 不带 `-am` 不带 `clean`。
> 若 `cn.idev.excel.FastExcel` 入口名与实装不符（FastExcel 版本差异），先 `javap -cp $(find ~/.m2 -name 'fastexcel*.jar' | head -1) cn.idev.excel.FastExcel` 实证再改 import——**改 import 不改断言语义**。

### 失败路径与回滚
- 若测试红 = write-only 层被破坏（有人给字段加回 `@ExcelProperty` 或删 `@JsonIgnore`）→ **立即升级 🔴 阻断**（凭据回显面），修复方向是恢复注解，**绝不允许**「为了让前端预填而删 `@JsonIgnore`」（#19 明令）。
- 回滚 = 删测试文件（零生产代码，无副作用）。

---

## E5 结构化连接表单 + 方案 a 空态卡 + 高级/JSON 视图（✅ 已实施 2026-09-28，差异登记见主计划 §9）

**目标**：替换 `configJson` 裸 JSON Textarea（UIUX 痛点①②③）为：
- **字段集 = 后端真实读取键**（#20）：LOCAL = `command`（必填）+ `args`（数组行编辑器）；REMOTE = `baseUrl`（必填 URL）。**`env`/`headers` 不出现**（E-A1 落地前）。
- **编辑态方案 a 空态卡**（#19）：「连接配置已安全保存，不显示明文」+「替换配置」按钮 + 「留空提交将保留原配置」文案；点「替换配置」才展开全量填写；不替换提交 = **不带 configJson 键**（后端 `applyWriteOnlyConfigPolicy` 语义：留空保留原值）。
- **高级/JSON 视图**：Textarea 降级入口（双向同步结构化草稿），JSON 非法**显式报错并锁提交**（修痛点②静默 return）。

### Files
| 路径 | 动作 |
|---|---|
| `apps/web-antd/src/views/mcp/_shared/connection-config.ts` | 新建（纯函数：草稿校验/JSON 构造/解析） |
| `apps/web-antd/src/views/mcp/_shared/mcp-connection-form.vue` | 新建（结构化表单 + 空态卡 + 高级视图） |
| `apps/web-antd/src/views/mcp/tool/data.tsx` | 改（删 `configJson` Textarea schema） |
| `apps/web-antd/src/views/mcp/tool/tool-drawer.vue` | 改（挂表单 + 提交合成 + 显式校验提示） |
| `apps/web-antd/src/views/mcp/_shared/connection-config.test.ts` | 新建 |
| `vitest.ipd.config.mts` | 改（include/coverage 白名单扩展，**串行窗口**） |

### Interfaces
```ts
// connection-config.ts
export type McpToolType = 'BUILTIN' | 'LOCAL' | 'REMOTE';
export interface LocalConnectionDraft { args: string[]; command: string; }
export interface RemoteConnectionDraft { baseUrl: string; }
export type ConnectionDraft = LocalConnectionDraft & RemoteConnectionDraft; // 草稿统一形状，按 type 取用
export function emptyConnectionDraft(): ConnectionDraft;
export function validateConnectionDraft(type: McpToolType, draft: ConnectionDraft): string[]; // 空=通过
export function buildConfigJson(type: McpToolType, draft: ConnectionDraft): string;           // LOCAL:{command,args} / REMOTE:{baseUrl}
export function parseConfigJson(type: McpToolType, raw: string): null | ConnectionDraft;      // 高级视图→结构化
export function configJsonToDraft(type: McpToolType, raw: string): ConnectionDraft;           // 解析失败回空草稿
// mcp-connection-form.vue
// props: { type: McpToolType; mode: 'create' | 'edit' }
// defineModel<ConnectionDraft>('draft')；defineModel<boolean>('replaced')（edit 态是否已点「替换配置」）
// expose: validate(): string[]；getConfigJson(): null | string（null=不替换/留空，提交时不带键）
```

### 步骤（真实代码）

**E5-① `connection-config.ts`（新，整文件）**
```ts
/**
 * MCP 连接配置结构化模型（Track E5）。
 * 硬约束 #20：字段集 = 后端真实读取键（LOCAL: command+args / REMOTE: baseUrl，
 * LangChain4jMcpToolProviderService.createStdioClient/createRemoteClient 实证）。
 * env/headers 后端当前不读，E-A1 落地前**禁止**出现在任何 UI/序列化产物里。
 * 硬约束 #19：本模块只做「用户新输入」的序列化；服务端原值永不回显（@JsonIgnore），
 * 解析函数只用于高级视图的用户输入回同步，绝不用于预填服务端配置。
 */
export type McpToolType = 'BUILTIN' | 'LOCAL' | 'REMOTE';

export interface LocalConnectionDraft {
  args: string[];
  command: string;
}

export interface RemoteConnectionDraft {
  baseUrl: string;
}

export interface ConnectionDraft extends LocalConnectionDraft, RemoteConnectionDraft {}

export function emptyConnectionDraft(): ConnectionDraft {
  return { args: [], baseUrl: '', command: '' };
}

export function validateConnectionDraft(
  type: McpToolType,
  draft: ConnectionDraft,
): string[] {
  const errors: string[] = [];
  if (type === 'BUILTIN') {
    return errors;
  }
  if (type === 'LOCAL') {
    if (draft.command.trim() === '') {
      errors.push('command 必填（后端无 command 会拒绝启动本地工具）');
    }
    draft.args.forEach((arg, index) => {
      if (arg.trim() === '') errors.push(`args[${index}] 不可为空串`);
    });
    return errors;
  }
  const url = draft.baseUrl.trim();
  if (url === '') {
    errors.push('baseUrl 必填');
    return errors;
  }
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    errors.push('baseUrl 需为合法 URL（含协议，如 https://host/mcp）');
    return errors;
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    errors.push('baseUrl 协议仅支持 http/https');
  }
  return errors;
}

export function buildConfigJson(type: McpToolType, draft: ConnectionDraft): string {
  if (type === 'LOCAL') {
    return JSON.stringify({
      args: draft.args.map((arg) => arg.trim()).filter((arg) => arg !== ''),
      command: draft.command.trim(),
    });
  }
  return JSON.stringify({ baseUrl: draft.baseUrl.trim() });
}

export function parseConfigJson(
  type: McpToolType,
  raw: string,
): null | ConnectionDraft {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return null;
    }
    const record = parsed as Record<string, unknown>;
    if (type === 'LOCAL') {
      const args = Array.isArray(record.args)
        ? record.args.map((arg) => String(arg))
        : null;
      if (typeof record.command !== 'string' || args === null) return null;
      return { args, baseUrl: '', command: record.command };
    }
    if (typeof record.baseUrl !== 'string') return null;
    return { args: [], baseUrl: record.baseUrl, command: '' };
  } catch {
    return null;
  }
}

export function configJsonToDraft(type: McpToolType, raw: string): ConnectionDraft {
  return parseConfigJson(type, raw) ?? emptyConnectionDraft();
}
```

**E5-② `mcp-connection-form.vue`（新，整文件）**
```vue
<template>
  <div class="conn-form" data-testid="mcp-conn-form">
    <!-- 方案 a 空态卡（#19）：编辑态且未点「替换配置」时的唯一呈现 -->
    <div
      v-if="mode === 'edit' && !replaced"
      class="conn-sealed"
      data-testid="mcp-conn-sealed"
    >
      <div class="conn-sealed-title">连接配置已安全保存，不显示明文</div>
      <div class="conn-sealed-help">
        出于安全设计，接口不回显连接配置。留空提交将保留原配置；如需更换，点「替换配置」重新填写（原配置将被覆写）。
      </div>
      <a-button data-testid="mcp-conn-replace" @click="replaced = true">替换配置</a-button>
    </div>

    <template v-else>
      <div class="conn-mode-row">
        <a-radio-group v-model:value="view" button-style="solid" size="small">
          <a-radio-button value="structured">结构化</a-radio-button>
          <a-radio-button value="json">高级/JSON</a-radio-button>
        </a-radio-group>
        <a-button v-if="mode === 'edit'" size="small" type="link" @click="replaced = false">
          取消替换（留空保留原配置）
        </a-button>
      </div>

      <template v-if="view === 'structured'">
        <template v-if="type === 'LOCAL'">
          <label class="conn-label" for="mcp-conn-command">command（必填）</label>
          <a-input
            id="mcp-conn-command"
            v-model:value="draft.command"
            class="conn-control"
            data-testid="mcp-conn-command"
            placeholder="npx"
          />
          <label class="conn-label">args（数组，按序追加）</label>
          <div
            v-for="(arg, index) in draft.args"
            :key="index"
            class="conn-arg-row"
          >
            <a-input
              v-model:value="draft.args[index]"
              :data-testid="`mcp-conn-arg-${index}`"
              class="conn-control"
              placeholder="-y"
            />
            <a-button danger size="small" @click="draft.args.splice(index, 1)">
              移除
            </a-button>
          </div>
          <a-button data-testid="mcp-conn-arg-add" size="small" @click="draft.args.push('')">
            + 追加参数
          </a-button>
        </template>
        <template v-else-if="type === 'REMOTE'">
          <label class="conn-label" for="mcp-conn-base-url">baseUrl（必填）</label>
          <a-input
            id="mcp-conn-base-url"
            v-model:value="draft.baseUrl"
            class="conn-control"
            data-testid="mcp-conn-base-url"
            placeholder="https://host/mcp"
          />
        </template>
        <template v-else>
          <div class="conn-builtin" data-testid="mcp-conn-builtin">
            内置工具无需连接配置。
          </div>
        </template>
      </template>

      <template v-else>
        <label class="conn-label" for="mcp-conn-json">连接配置 JSON（高级视图）</label>
        <a-textarea
          id="mcp-conn-json"
          v-model:value="jsonText"
          :rows="6"
          class="conn-control"
          data-testid="mcp-conn-json"
          @change="syncFromJson"
        />
        <div v-if="jsonError" class="conn-error" data-testid="mcp-conn-json-error">
          {{ jsonError }}
        </div>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';

import { Button as AButton, Input as AInput, RadioGroup as ARadioGroup, RadioButton as ARadioButton, Textarea as ATextarea } from 'ant-design-vue';

import {
  buildConfigJson,
  configJsonToDraft,
  emptyConnectionDraft,
  parseConfigJson,
  validateConnectionDraft,
  type ConnectionDraft,
  type McpToolType,
} from './connection-config';

const props = defineProps<{ mode: 'create' | 'edit'; type: McpToolType }>();

const draft = defineModel<ConnectionDraft>('draft', {
  default: () => emptyConnectionDraft(),
});
const replaced = defineModel<boolean>('replaced', { default: false });

const view = ref<'json' | 'structured'>('structured');
const jsonText = ref('');
const jsonError = ref('');

watch(
  [draft, view],
  () => {
    if (view.value === 'json') {
      jsonText.value = buildConfigJson(props.type, draft.value);
    }
  },
  { deep: true, immediate: true },
);

function syncFromJson() {
  const parsed = parseConfigJson(props.type, jsonText.value);
  if (!parsed) {
    jsonError.value = 'JSON 非法或形状不符（LOCAL 需 command+args[]；REMOTE 需 baseUrl）';
    return;
  }
  jsonError.value = '';
  draft.value = parsed;
}

/** 校验（提交前调用）：空数组=通过；edit 未替换时直接通过（留空保留）。 */
function validate(): string[] {
  if (props.mode === 'edit' && !replaced.value) return [];
  return validateConnectionDraft(props.type, draft.value);
}

/** 提交载荷：null = 不带 configJson 键（方案 a 留空保留）。 */
function getConfigJson(): null | string {
  if (props.type === 'BUILTIN') return null;
  if (props.mode === 'edit' && !replaced.value) return null;
  if (validate().length > 0) return null;
  return buildConfigJson(props.type, draft.value);
}

defineExpose({ getConfigJson, validate });
</script>

<style scoped>
.conn-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--ipd-line);
  border-radius: 8px;
  background: var(--ipd-bg);
}
.conn-sealed {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px dashed var(--ipd-line);
  border-radius: 8px;
  background: var(--ipd-surface);
}
.conn-sealed-title {
  color: var(--ipd-text);
  font-size: 13px;
  font-weight: 600;
}
.conn-sealed-help {
  color: var(--ipd-muted);
  font-size: 12px;
  line-height: 1.8;
}
.conn-mode-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.conn-label {
  color: var(--ipd-text);
  font-size: 12px;
}
.conn-control {
  border-radius: 6px;
}
.conn-arg-row {
  display: flex;
  gap: 6px;
}
.conn-error {
  color: var(--ipd-red);
  font-size: 12px;
}
.conn-builtin {
  color: var(--ipd-muted);
  font-size: 12px;
}
</style>
```

**E5-③ `data.tsx` 补丁**：删除 `drawerSchema` 末尾的 `configJson` Textarea 整项（`fieldName: 'configJson'` 起整块），schema 中**不再存在**任何 `configJson` 输入（表单域与 write-only 契约解耦，连接配置完全由 `McpConnectionForm` 承接）。

**E5-④ `tool-drawer.vue` 补丁（真实代码段）**
```vue
<template>
  <BasicDrawer :title="title" class="w-[600px]">
    <BasicForm />
    <McpConnectionForm
      ref="connFormRef"
      v-model:draft="connDraft"
      v-model:replaced="connReplaced"
      :mode="isUpdate ? 'edit' : 'create'"
      :type="toolType"
    />
  </BasicDrawer>
</template>
```
```ts
// <script setup> 替换原 handleConfirm 的 JSON 静默校验段（痛点②：非法静默 return → 显式报错锁提交）：
import { computed, ref } from 'vue';
import { message } from 'ant-design-vue';
import McpConnectionForm from '../_shared/mcp-connection-form.vue';
import {
  emptyConnectionDraft,
  type ConnectionDraft,
  type McpToolType,
} from '../_shared/connection-config';

const connFormRef = ref<InstanceType<typeof McpConnectionForm>>();
const connDraft = ref<ConnectionDraft>(emptyConnectionDraft());
const connReplaced = ref(false);
const toolType = computed<McpToolType>(() => {
  const values = formApi.form.values as { type?: string };
  return (values.type ?? 'LOCAL') as McpToolType;
});

async function handleConfirm() {
  try {
    drawerApi.lock(true);
    const { valid } = await formApi.validate();
    if (!valid) return;
    const errors = connFormRef.value?.validate() ?? [];
    if (errors.length > 0) {
      message.error(errors.join('；'));
      return;
    }
    const data = cloneDeep(await formApi.getValues()) as Record<string, unknown>;
    const configJson = connFormRef.value?.getConfigJson() ?? null;
    if (configJson === null) {
      delete data.configJson;            // 方案 a：不带键 = 后端留空保留原值
    } else {
      data.configJson = configJson;
    }
    await (isUpdate.value ? mcpToolUpdate(data) : mcpToolAdd(data));
    resetInitialized();
    emit('reload');
    drawerApi.close();
  } catch (error) {
    console.error(error);
  } finally {
    drawerApi.lock(false);
  }
}
// handleClosed 里追加：connDraft.value = emptyConnectionDraft(); connReplaced.value = false;
```

**E5-⑤ `connection-config.test.ts`（新，整文件）**
```ts
import { describe, expect, it } from 'vitest';

import {
  buildConfigJson,
  configJsonToDraft,
  emptyConnectionDraft,
  parseConfigJson,
  validateConnectionDraft,
} from './connection-config';

describe('connection-config', () => {
  it('LOCAL：command 必填、args 空串报错；序列化只含 command+args（#20 不发明 env）', () => {
    const draft = { args: ['-y', ' pkg'], baseUrl: '', command: ' npx ' };
    expect(validateConnectionDraft('LOCAL', draft)).toEqual([]);
    expect(buildConfigJson('LOCAL', draft)).toBe(
      JSON.stringify({ args: ['-y', 'pkg'], command: 'npx' }),
    );
    const bad = { args: [''], baseUrl: '', command: '' };
    const errors = validateConnectionDraft('LOCAL', bad);
    expect(errors.some((e) => e.includes('command'))).toBe(true);
    expect(errors.some((e) => e.includes('args[0]'))).toBe(true);
  });

  it('REMOTE：baseUrl 必填 + URL 合法性 + 协议白名单', () => {
    expect(validateConnectionDraft('REMOTE', { args: [], baseUrl: 'https://host/mcp', command: '' })).toEqual([]);
    expect(validateConnectionDraft('REMOTE', { args: [], baseUrl: '', command: '' })).toContain('baseUrl 必填');
    expect(validateConnectionDraft('REMOTE', { args: [], baseUrl: 'not a url', command: '' })[0]).toContain('合法 URL');
    expect(validateConnectionDraft('REMOTE', { args: [], baseUrl: 'ftp://host', command: '' })[0]).toContain('http/https');
    expect(buildConfigJson('REMOTE', { args: [], baseUrl: ' https://host/mcp ', command: '' })).toBe(
      JSON.stringify({ baseUrl: 'https://host/mcp' }),
    );
  });

  it('高级视图回同步：合法 JSON → 草稿；非法/形状不符 → null（调用方显式报错）', () => {
    expect(parseConfigJson('LOCAL', '{"command":"npx","args":["-y"]}')).toEqual({
      args: ['-y'],
      baseUrl: '',
      command: 'npx',
    });
    expect(parseConfigJson('LOCAL', '{"command":"npx"}')).toBeNull();
    expect(parseConfigJson('REMOTE', '{"baseUrl":123}')).toBeNull();
    expect(parseConfigJson('REMOTE', '{oops')).toBeNull();
    expect(configJsonToDraft('LOCAL', '{oops')).toEqual(emptyConnectionDraft());
    // BUILTIN 无需配置：恒通过、恒不产出 configJson
    expect(validateConnectionDraft('BUILTIN', emptyConnectionDraft())).toEqual([]);
  });
});
```

**E5-⑥ `vitest.ipd.config.mts` 白名单扩展（串行窗口一次落）**
```ts
include: [
  'apps/web-antd/src/packages/workflow-designer/properties/GenericNodeProperty.test.ts',
  'apps/web-antd/src/api/ipd/**/*.test.ts',
  'apps/web-antd/src/router/ipd-guard.test.ts',
  'apps/web-antd/src/store/**/*.test.ts',
  'apps/web-antd/src/views/ipd/**/*.test.ts',
  'apps/web-antd/src/views/workflow/**/*.test.ts',
  // Track E（MCP 与 Skill 配置中心）：存量页改造补测
  'apps/web-antd/src/views/mcp/**/*.test.ts',
  'apps/web-antd/src/views/agent/agent/**/*.test.ts',
  'apps/web-antd/src/api/mcp/**/*.test.ts',
],
```
`coverage.include` 增 `'apps/web-antd/src/views/mcp/**/*.{ts,vue}'` 与 `'apps/web-antd/src/views/agent/agent/**/*.{ts,vue}'`。

### 验证命令
三件套 + `21st review`（`data.tsx`/新 css 会被审；vue 模板层走 chrome-devtools）+ 人工两态验证：新增 LOCAL/REMOTE 工具落库后重开编辑 → 空态卡出现（无明文）→ 不替换直接提交 → `curl` 详情（登录态）确认响应无 `configJson` 且库内原值未被清（只读探针 `mysql --defaults-extra-file=… ipd_dev -e "select config_json from mcp_tool_info where id=<id>"` 不打印值只看非空）。

### 失败路径与回滚
- `useVbenForm` 与并列自定义组件的值合成冲突 → 编辑器只认 `formApi.getValues()` + `getConfigJson()` 两路显式合成（代码已如此），不把连接字段混进 schema。
- 回滚 = 还原 `data.tsx`/`tool-drawer.vue` + 删 `_shared` 两文件 + 还原 `vitest.ipd.config.mts`。

---

## Task E1 — 工具台三栏（工具列表 / 详情+连接测试 / 反向依赖）（✅ 已实施 2026-09-28，差异登记见主计划 §9）

> 修 §0.5 痛点④（测试结果只有一行 message toast）与「工具与 Agent 绑定关系不可见」。三栏信息架构对齐 21st 参照 12382（MCP Tool 三态卡）：测试生命周期用 `inProgress / executing / complete` 三态（与 `copilotkit-render.ts` 的 `CardToolStatus` 字面量同构，复用同一词汇，不发明第四态）。
>
> **allowedPaths（本任务写权限面）**：`apps/web-antd/src/views/mcp/**`。不碰 `api/mcp/**`（复用既有 `mcpToolTest`/`agentList`）、不碰后端、不碰 `vitest.ipd.config.mts`（白名单已在 E5-⑥ 扩入 `views/mcp/**`）。

### E1-0 Files

| 动作 | 路径 | 说明 |
|---|---|---|
| NEW | `apps/web-antd/src/views/mcp/_shared/tool-test.ts` | 测试三态视图模型（纯函数，零 api import） |
| NEW | `apps/web-antd/src/views/mcp/_shared/use-tool-test.ts` | 测试执行 composable（runner 注入，可直测） |
| NEW | `apps/web-antd/src/views/mcp/_shared/tool-test-panel.vue` | 连接测试面板（三态 Tag + 耗时 + 结构化结果行） |
| NEW | `apps/web-antd/src/views/mcp/_shared/tool-reverse-deps.ts` | 反向依赖纯函数（AgentVO → 工具/技能被绑定索引） |
| NEW | `apps/web-antd/src/views/mcp/_shared/tool-test.test.ts` | 单测 |
| NEW | `apps/web-antd/src/views/mcp/_shared/tool-reverse-deps.test.ts` | 单测 |
| PATCH | `apps/web-antd/src/views/mcp/tool/data.tsx` | 工具名称列加 `slots: { default: 'name' }` |
| PATCH | `apps/web-antd/src/views/mcp/tool/index.vue` | 三栏改造（完整替换体见 E1-7） |

### E1-1 Interfaces

消费（全部为既有导出，只读不改）：

```ts
// #/api/mcp/tool
mcpToolTest(id: ID): Promise<McpToolTestResult>          // { success: boolean; message: string; data?: any }
// #/api/agent/agent
agentList(params?: PageQuery): Promise<PageResult<AgentVO>>
// PageResult<T> = { rows: T[]; total: number }（api/common.d.ts 实证）
// AgentVO: { id: number; agentName: string; mcpToolIds?: number[]; skillNames?: string[]; ... }
```

产出（本任务新增签名）：

```ts
// _shared/tool-test.ts
type ToolTestPhase = 'complete' | 'executing' | 'inProgress';
interface ToolTestViewState { durationMs: null | number; message: null | string; phase: ToolTestPhase; payloadRows: ToolTestPayloadRow[]; success: null | boolean; testedAt: null | string }
function idleToolTestState(): ToolTestViewState
function payloadToRows(data: unknown): ToolTestPayloadRow[]
function normalizeToolTestResult(response: ToolTestResponse, durationMs: number, testedAt: string): ToolTestViewState
function failedToolTestState(message: string, durationMs: number, testedAt: string): ToolTestViewState
function formatDuration(durationMs: null | number): string

// _shared/use-tool-test.ts
type ToolTestRunner = (toolId: number) => Promise<ToolTestResponse>;
function useToolTest(runner: ToolTestRunner, now?: () => number): {
  hasResult: Ref<boolean>; run: (toolId: number) => Promise<void>; running: Ref<boolean>; state: Ref<ToolTestViewState>;
}

// _shared/tool-test-panel.vue
props: { toolId: number | null }   // 缺省 null = 未选中，按钮禁用（约束 #5 guard-on-status 的 UI 面）
expose: { run: () => Promise<void> }

// _shared/tool-reverse-deps.ts
function buildToolReverseDeps(agents: AgentBinding[]): Map<number, ReverseDep[]>
function buildSkillReverseDeps(agents: AgentBinding[]): Map<string, ReverseDep[]>
function reverseDepsOf<K>(map: Map<K, ReverseDep[]>, key: K): ReverseDep[]
```

### E1-2 `_shared/tool-test.ts`（全码，可直接落盘）

```ts
/**
 * MCP 工具连接测试三态视图模型（Track E1）。
 *
 * <p>三态词汇与 `views/ipd/_shared/ai-cards/copilotkit-render.ts` 的 CardToolStatus
 * 同构（inProgress=待执行 / executing=请求飞行中 / complete=结果已达），跨轨共用同一
 * 状态词汇，不发明第四态。本文件为纯函数层：不 import 任何 api/组件，可被 vitest
 * 直接加载（白名单见 E5-⑥：views/mcp/** 已扩入 include）。
 *
 * <p>修 §0.5 痛点④：测试结果不再只弹一行 message toast，而是 normalize 成结构化
 * 视图状态（三态 + 成败 + 耗时 + 结果键值行），由 tool-test-panel.vue 渲染。
 *
 * <p>颜色红线（Global Constraints #21）：本层零样式；渲染层只用 `var(--ipd-*)`。
 */

/** 测试生命周期三态（= CardToolStatus 字面量联合）。 */
export type ToolTestPhase = 'complete' | 'executing' | 'inProgress';

/** 结果载荷展开行（对象逐键展开；非对象值 JSON 序列化为单行）。 */
export interface ToolTestPayloadRow {
  key: string;
  value: string;
}

/** 连接测试面板状态（纯数据，无 Vue 依赖）。 */
export interface ToolTestViewState {
  /** 耗时毫秒；未测过为 null。 */
  durationMs: null | number;
  /** 结果说明（normalize 时补齐成败缺省文案，绝不留空占位）。 */
  message: null | string;
  phase: ToolTestPhase;
  payloadRows: ToolTestPayloadRow[];
  /** 成功 true / 失败 false / 未测 null。 */
  success: null | boolean;
  /** ISO 时间戳；未测过为 null（hasResult 判据）。 */
  testedAt: null | string;
}

/**
 * 后端 McpToolTestResult 的结构等价形态（`api/mcp/tool/model.d.ts`：
 * `{ success: boolean; message: string; data?: any }`）。用结构类型而非 import，
 * 保持纯函数层零运行时依赖。
 */
export interface ToolTestResponse {
  data?: unknown;
  message?: null | string;
  success: boolean;
}

/** 结果行上限：防超大 payload 撑爆渲染（超出截断，行内不伪造补位行）。 */
export const TOOL_TEST_PAYLOAD_ROW_LIMIT = 50;

/** 未测初始态（phase=inProgress、testedAt=null）。 */
export function idleToolTestState(): ToolTestViewState {
  return {
    durationMs: null,
    message: null,
    phase: 'inProgress',
    payloadRows: [],
    success: null,
    testedAt: null,
  };
}

function stringifyValue(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value === null || value === undefined) return '';
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    // BigInt/循环引用等极端值走字面兜底，不断渲染
    return String(value);
  }
}

/**
 * 载荷 → 展示行：对象逐键展开（截断至 TOOL_TEST_PAYLOAD_ROW_LIMIT）；
 * 数组/标量折叠为单行 `result`；null/undefined 返回空数组（面板显示空态文案）。
 */
export function payloadToRows(data: unknown): ToolTestPayloadRow[] {
  const rows: ToolTestPayloadRow[] = [];
  if (data === null || data === undefined) return rows;
  if (typeof data !== 'object' || Array.isArray(data)) {
    rows.push({ key: 'result', value: stringifyValue(data) });
    return rows;
  }
  for (const [key, value] of Object.entries(data as Record<string, unknown>)) {
    if (rows.length >= TOOL_TEST_PAYLOAD_ROW_LIMIT) break;
    rows.push({ key, value: stringifyValue(value) });
  }
  return rows;
}

/** 后端结果 → 终态（phase=complete；message 缺省按成败补诚实文案）。 */
export function normalizeToolTestResult(
  response: ToolTestResponse,
  durationMs: number,
  testedAt: string,
): ToolTestViewState {
  const success = response.success === true;
  const rawMessage =
    typeof response.message === 'string' ? response.message.trim() : '';
  return {
    durationMs,
    message:
      rawMessage === ''
        ? success
          ? '连接测试通过'
          : '连接测试失败'
        : rawMessage,
    phase: 'complete',
    payloadRows: payloadToRows(response.data),
    success,
    testedAt,
  };
}

/** 请求异常 → 失败终态（网络/服务异常也走 complete+success=false，不断面板）。 */
export function failedToolTestState(
  message: string,
  durationMs: number,
  testedAt: string,
): ToolTestViewState {
  return {
    durationMs,
    message,
    phase: 'complete',
    payloadRows: [],
    success: false,
    testedAt,
  };
}

/** 耗时展示（null = 未测，输出长横线，不输出 0ms 误导）。 */
export function formatDuration(durationMs: null | number): string {
  return durationMs === null ? '—' : `${durationMs} ms`;
}
```

### E1-3 `_shared/use-tool-test.ts`（全码）

```ts
/**
 * 连接测试执行 composable（Track E1）。
 *
 * <p>runner 注入（调用方传 `mcpToolTest`），本文件同样零 api import——测试可注入
 * stub 直测并发护栏（running 期间重复调用直接忽略，对齐约束 #5 guard-on-status
 * 的「状态未变不再触发」口径）。计时器 now 注入，单测断言耗时确定性。
 */
import type { Ref } from 'vue';

import type { ToolTestResponse, ToolTestViewState } from './tool-test';

import { computed, ref } from 'vue';

import {
  failedToolTestState,
  idleToolTestState,
  normalizeToolTestResult,
} from './tool-test';

export type ToolTestRunner = (toolId: number) => Promise<ToolTestResponse>;

export interface UseToolTestResult {
  hasResult: Ref<boolean>;
  run: (toolId: number) => Promise<void>;
  running: Ref<boolean>;
  state: Ref<ToolTestViewState>;
}

export function useToolTest(
  runner: ToolTestRunner,
  now: () => number = () => Date.now(),
): UseToolTestResult {
  const state = ref<ToolTestViewState>(idleToolTestState());
  const running = ref(false);
  const hasResult = computed(() => state.value.testedAt !== null);

  async function run(toolId: number): Promise<void> {
    if (running.value) return;
    running.value = true;
    state.value = { ...idleToolTestState(), phase: 'executing' };
    const startedAt = now();
    const durationOf = () => Math.max(0, now() - startedAt);
    const testedAt = new Date(startedAt).toISOString();
    try {
      const response = await runner(toolId);
      state.value = normalizeToolTestResult(response, durationOf(), testedAt);
    } catch (error) {
      const detail = error instanceof Error ? error.message : '未知错误';
      state.value = failedToolTestState(
        `连接测试请求失败：${detail}`,
        durationOf(),
        testedAt,
      );
    } finally {
      running.value = false;
    }
  }

  return { hasResult, run, running, state };
}
```

### E1-4 `_shared/tool-test-panel.vue`（全码）

```vue
<script setup lang="ts">
/**
 * MCP 连接测试面板（Track E1，修痛点④）。
 *
 * <p>三态 Tag + 耗时 + 结构化结果键值行 + 空态诚实文案。测试按钮在未选中工具时
 * 禁用（guard-on-status UI 面）。取色全部 `var(--ipd-*)`（约束 #21），圆角
 * 6px 控件 / 8px 卡片 / 4px Tag；零新增运行时依赖（约束 #3）。
 */
import { computed } from 'vue';

import { mcpToolTest } from '#/api/mcp/tool';

import { useToolTest } from './use-tool-test';
import { formatDuration } from './tool-test';

const props = defineProps<{ toolId: null | number }>();

const { run, running, state } = useToolTest((toolId) => mcpToolTest(toolId));

const phaseLabel = computed(() => {
  if (running.value) return '测试中';
  if (state.value.testedAt === null) return '待测试';
  return state.value.success ? '通过' : '失败';
});

const phaseClass = computed(() => {
  if (running.value) return 'ipd-tt-tag ipd-tt-tag--running';
  if (state.value.testedAt === null) return 'ipd-tt-tag ipd-tt-tag--idle';
  return state.value.success
    ? 'ipd-tt-tag ipd-tt-tag--ok'
    : 'ipd-tt-tag ipd-tt-tag--err';
});

const resultClass = computed(() =>
  state.value.success
    ? 'ipd-tt-result ipd-tt-result--ok'
    : 'ipd-tt-result ipd-tt-result--err',
);

async function handleRun() {
  if (props.toolId === null || running.value) return;
  await run(props.toolId);
}

defineExpose({
  run: async () => {
    await handleRun();
  },
});
</script>

<template>
  <div class="ipd-tt-panel">
    <div class="ipd-tt-head">
      <span class="ipd-tt-title">连接测试</span>
      <span :class="phaseClass" data-testid="ipd-tt-phase">{{ phaseLabel }}</span>
      <span class="ipd-tt-meta">耗时 {{ formatDuration(state.durationMs) }}</span>
      <a-button
        :disabled="toolId === null || running"
        :loading="running"
        size="small"
        type="primary"
        data-testid="ipd-tt-run"
        @click="handleRun"
      >
        测试连接
      </a-button>
    </div>

    <div v-if="toolId === null" class="ipd-tt-empty">
      未选中工具：请在左侧列表选择工具后执行连接测试。
    </div>

    <template v-else>
      <div v-if="state.testedAt !== null" :class="resultClass" data-testid="ipd-tt-result">
        <div class="ipd-tt-result-msg">{{ state.message }}</div>
        <div class="ipd-tt-meta">测试时间 {{ state.testedAt }}</div>
      </div>

      <div v-if="state.testedAt === null && !running" class="ipd-tt-empty">
        尚未测试：点击「测试连接」验证该工具的连通性与工具列表拉取。
      </div>

      <div v-if="running" class="ipd-tt-empty">测试执行中，请稍候…</div>

      <ul
        v-if="state.payloadRows.length > 0"
        class="ipd-tt-rows"
        data-testid="ipd-tt-rows"
      >
        <li v-for="row in state.payloadRows" :key="row.key" class="ipd-tt-row">
          <span class="ipd-tt-row-key">{{ row.key }}</span>
          <span class="ipd-tt-row-value">{{ row.value }}</span>
        </li>
      </ul>
      <div
        v-else-if="state.testedAt !== null && state.success"
        class="ipd-tt-empty"
      >
        测试通过但未返回结构化结果（data 为空）。
      </div>
    </template>
  </div>
</template>

<style scoped>
.ipd-tt-panel {
  background: var(--ipd-surface);
  border: 1px solid var(--ipd-line);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
}

.ipd-tt-head {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ipd-tt-title {
  color: var(--ipd-text);
  font-size: 14px;
  font-weight: 600;
}

.ipd-tt-tag {
  border-radius: 4px;
  font-size: 12px;
  line-height: 18px;
  padding: 0 8px;
}

.ipd-tt-tag--idle {
  background: var(--ipd-bg);
  border: 1px solid var(--ipd-line);
  color: var(--ipd-muted);
}

.ipd-tt-tag--running {
  background: var(--ipd-amber);
  border: 1px solid var(--ipd-amber);
  color: var(--ipd-surface);
}

.ipd-tt-tag--ok {
  background: var(--ipd-green);
  border: 1px solid var(--ipd-green);
  color: var(--ipd-surface);
}

.ipd-tt-tag--err {
  background: var(--ipd-red);
  border: 1px solid var(--ipd-red);
  color: var(--ipd-surface);
}

.ipd-tt-meta {
  color: var(--ipd-muted);
  font-size: 12px;
}

.ipd-tt-empty {
  color: var(--ipd-muted);
  font-size: 12px;
  line-height: 20px;
}

.ipd-tt-result {
  border-left: 3px solid var(--ipd-line);
  border-radius: 6px;
  padding: 8px 10px;
}

.ipd-tt-result--ok {
  background: var(--ipd-blue-soft);
  border-left-color: var(--ipd-green);
}

.ipd-tt-result--err {
  background: var(--ipd-bg);
  border-left-color: var(--ipd-red);
}

.ipd-tt-result-msg {
  color: var(--ipd-text);
  font-size: 13px;
  line-height: 20px;
  word-break: break-all;
}

.ipd-tt-rows {
  display: flex;
  flex-direction: column;
  gap: 4px;
  list-style: none;
  margin: 0;
  max-height: 220px;
  overflow: auto;
  padding: 0;
}

.ipd-tt-row {
  border-bottom: 1px dashed var(--ipd-line);
  display: flex;
  font-size: 12px;
  gap: 8px;
  padding: 4px 0;
}

.ipd-tt-row-key {
  color: var(--ipd-muted);
  flex: 0 0 120px;
  word-break: break-all;
}

.ipd-tt-row-value {
  color: var(--ipd-text);
  flex: 1;
  word-break: break-all;
}
</style>
```

### E1-5 `_shared/tool-reverse-deps.ts`（全码）

```ts
/**
 * 反向依赖索引（Track E1/E3 共用纯函数）。
 *
 * <p>数据源 = 既有 `agentList`（AgentVO 携带 mcpToolIds / skillNames），
 * **客户端计算反向索引，零后端改动**（约束 #20：不绕过后端契约发明读接口）。
 * 结果按 agentName 字典序 + id 升序排序，保证渲染与断言确定性。
 */

/** AgentVO 的最小结构依赖（只取反向索引需要的键，不 import model 保持纯函数）。 */
export interface AgentBinding {
  agentName: string;
  id: number;
  mcpToolIds?: null | number[];
  skillNames?: null | string[];
}

/** 反向依赖条目（哪个 Agent 绑了这个工具/技能）。 */
export interface ReverseDep {
  agentId: number;
  agentName: string;
}

export type ToolReverseDepMap = Map<number, ReverseDep[]>;
export type SkillReverseDepMap = Map<string, ReverseDep[]>;

function pushDep<K>(map: Map<K, ReverseDep[]>, key: K, dep: ReverseDep): void {
  const list = map.get(key);
  if (list) {
    list.push(dep);
  } else {
    map.set(key, [dep]);
  }
}

function sortDeps(deps: ReverseDep[]): ReverseDep[] {
  return [...deps].sort(
    (a, b) => a.agentName.localeCompare(b.agentName) || a.agentId - b.agentId,
  );
}

/** 工具 id → 绑定该工具的 Agent 列表（mcpToolIds 为 null/空则该 Agent 不入任何键）。 */
export function buildToolReverseDeps(agents: AgentBinding[]): ToolReverseDepMap {
  const map: ToolReverseDepMap = new Map();
  for (const agent of agents) {
    const ids = agent.mcpToolIds ?? [];
    for (const toolId of ids) {
      if (typeof toolId !== 'number' || !Number.isFinite(toolId)) continue;
      pushDep(map, toolId, { agentId: agent.id, agentName: agent.agentName });
    }
  }
  return map;
}

/** 技能名 → 绑定该技能的 Agent 列表（skillNames 为 null/空则该 Agent 不入任何键）。 */
export function buildSkillReverseDeps(
  agents: AgentBinding[],
): SkillReverseDepMap {
  const map: SkillReverseDepMap = new Map();
  for (const agent of agents) {
    const names = agent.skillNames ?? [];
    for (const skillName of names) {
      if (typeof skillName !== 'string' || skillName === '') continue;
      pushDep(map, skillName, { agentId: agent.id, agentName: agent.agentName });
    }
  }
  return map;
}

/** 取反向依赖（未知键返回空数组；返回排序副本，调用方不可变体被污染）。 */
export function reverseDepsOf<K>(map: Map<K, ReverseDep[]>, key: K): ReverseDep[] {
  return sortDeps(map.get(key) ?? []);
}
```

### E1-6 `views/mcp/tool/data.tsx` 补丁（工具名称列加插槽）

把现有名称列对象：

```tsx
  {
    title: '工具名称',
    field: 'name',
    showOverflow: true,
    width: 200,
  },
```

替换为：

```tsx
  {
    title: '工具名称',
    field: 'name',
    showOverflow: true,
    // E1 三栏：名称列渲染为选中链接（写回 index.vue 的 activeTool），高亮当前详情列
    slots: { default: 'name' },
    width: 200,
  },
```

其余列、`querySchema`、`drawerSchema` 不动（`drawerSchema` 的连接配置字段改造见 E5-④）。

### E1-7 `views/mcp/tool/index.vue` 全量替换（三栏布局）

```vue
<script setup lang="ts">
import type { VbenFormProps } from '@vben/common-ui';

import type { VxeGridProps } from '#/adapter/vxe-table';
import type { McpTool } from '#/api/mcp/tool/model';

import type { ReverseDep } from '../_shared/tool-reverse-deps';

import { computed, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { getVxePopupContainer } from '@vben/utils';

import { Modal, Popconfirm, Space } from 'ant-design-vue';

import { useVbenVxeGrid, vxeCheckboxChecked } from '#/adapter/vxe-table';
import {
  mcpToolChangeStatus,
  mcpToolExport,
  mcpToolList,
  mcpToolRemove,
} from '#/api/mcp/tool';
import { agentList } from '#/api/agent/agent';
import { TableSwitch } from '#/components/table';
import { commonDownloadExcel } from '#/utils/file/download';

import ToolTestPanel from '../_shared/tool-test-panel.vue';
import {
  buildToolReverseDeps,
  reverseDepsOf,
} from '../_shared/tool-reverse-deps';
import toolDrawer from './tool-drawer.vue';
import { columns, querySchema } from './data';

const formOptions: VbenFormProps = {
  commonConfig: {
    labelWidth: 80,
    componentProps: {
      allowClear: true,
    },
  },
  schema: querySchema(),
  wrapperClass: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4',
};

const gridOptions: VxeGridProps = {
  checkboxConfig: {
    highlight: true,
    reserve: true,
  },
  columns,
  height: 'auto',
  keepSource: true,
  pagerConfig: {},
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues = {}) => {
        return await mcpToolList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        });
      },
    },
  },
  rowConfig: {
    keyField: 'id',
  },
  id: 'mcp-tool-index',
  showOverflow: false,
};

const [BasicTable, tableApi] = useVbenVxeGrid({
  formOptions,
  gridOptions,
});

const [ToolDrawer, drawerApi] = useVbenDrawer({
  connectedComponent: toolDrawer,
});

const { hasAccessByCodes } = useAccess();

/** E1 三栏：当前选中工具（详情列 + 连接测试面板 + 反向依赖列共用）。 */
const activeTool = ref<McpTool | null>(null);
const testPanelRef = ref<InstanceType<typeof ToolTestPanel> | null>(null);
const reverseDepMap = ref(new Map<number, ReverseDep[]>());
const depsLoadError = ref(false);
const agentsScanned = ref(0);

const activeToolDeps = computed(() =>
  activeTool.value ? reverseDepsOf(reverseDepMap.value, activeTool.value.id) : [],
);

function selectTool(row: McpTool) {
  activeTool.value = row;
}

/**
 * 反向依赖数据装载：agentList 分页拉全（后端 PageResult 一次一页），
 * 最多 20 页护栏；失败/截断走诚实文案，不伪造数据（约束 #7）。
 */
async function loadReverseDeps() {
  depsLoadError.value = false;
  try {
    const collected: Awaited<ReturnType<typeof agentList>>['rows'] = [];
    let total = Number.POSITIVE_INFINITY;
    for (let pageNum = 1; pageNum <= 20; pageNum += 1) {
      const result = await agentList({ pageNum, pageSize: 100 });
      collected.push(...result.rows);
      total = result.total;
      if (collected.length >= total) break;
    }
    agentsScanned.value = collected.length;
    reverseDepMap.value = buildToolReverseDeps(collected);
  } catch {
    depsLoadError.value = true;
    reverseDepMap.value = new Map();
    agentsScanned.value = 0;
  }
}

onMounted(() => {
  void loadReverseDeps();
});

function handleAdd() {
  drawerApi.setData({});
  drawerApi.open();
}

async function handleEdit(record: McpTool) {
  drawerApi.setData({ id: record.id });
  drawerApi.open();
}

async function handleDelete(row: McpTool) {
  await mcpToolRemove([row.id]);
  if (activeTool.value?.id === row.id) activeTool.value = null;
  await tableApi.query();
  await loadReverseDeps();
}

function handleMultiDelete() {
  const rows = tableApi.grid.getCheckboxRecords();
  const ids = rows.map((row: McpTool) => row.id);
  Modal.confirm({
    title: '提示',
    okType: 'danger',
    content: `确认删除选中的${ids.length}条记录吗？`,
    onOk: async () => {
      await mcpToolRemove(ids);
      if (activeTool.value && ids.includes(activeTool.value.id)) {
        activeTool.value = null;
      }
      await tableApi.query();
      await loadReverseDeps();
    },
  });
}

/** E1：测试入口 = 选中行 + 面板执行（原一行 toast 痛点④废除）。 */
async function handleTest(row: McpTool) {
  selectTool(row);
  await testPanelRef.value?.run();
}

async function handleReload() {
  await tableApi.query();
  await loadReverseDeps();
}

function handleDownloadExcel() {
  commonDownloadExcel(
    mcpToolExport,
    'MCP工具数据',
    tableApi.formApi.form.values,
  );
}
</script>

<template>
  <Page :auto-content-height="true">
    <div class="ipd-tool-bench">
      <div class="ipd-tool-bench__list">
        <BasicTable table-title="MCP工具列表">
          <template #toolbar-tools>
            <Space>
              <a-button
                v-access:code="['mcp:tool:export']"
                @click="handleDownloadExcel"
              >
                {{ $t('pages.common.export') }}
              </a-button>
              <a-button
                :disabled="!vxeCheckboxChecked(tableApi)"
                danger
                type="primary"
                v-access:code="['mcp:tool:remove']"
                @click="handleMultiDelete"
              >
                {{ $t('pages.common.delete') }}
              </a-button>
              <a-button
                type="primary"
                v-access:code="['mcp:tool:add']"
                @click="handleAdd"
              >
                {{ $t('pages.common.add') }}
              </a-button>
            </Space>
          </template>
          <template #name="{ row }">
            <a
              class="ipd-tool-link"
              :class="{ 'is-active': activeTool?.id === row.id }"
              data-testid="ipd-tool-select"
              @click.stop="selectTool(row)"
            >
              {{ row.name }}
            </a>
          </template>
          <template #status="{ row }">
            <TableSwitch
              v-model:value="row.status"
              :api="() => mcpToolChangeStatus(row)"
              :disabled="
                row.type === 'BUILTIN' || !hasAccessByCodes(['mcp:tool:edit'])
              "
              :checked-value="'ENABLED'"
              :unchecked-value="'DISABLED'"
              @reload="tableApi.query()"
            />
          </template>
          <template #action="{ row }">
            <Space>
              <ghost-button
                v-access:code="['mcp:tool:test']"
                @click.stop="handleTest(row)"
              >
                测试
              </ghost-button>
              <ghost-button
                v-access:code="['mcp:tool:edit']"
                @click.stop="handleEdit(row)"
              >
                {{ $t('pages.common.edit') }}
              </ghost-button>
              <Popconfirm
                :get-popup-container="getVxePopupContainer"
                placement="left"
                title="确认删除？"
                @confirm="handleDelete(row)"
              >
                <ghost-button
                  danger
                  v-access:code="['mcp:tool:remove']"
                  @click.stop=""
                >
                  {{ $t('pages.common.delete') }}
                </ghost-button>
              </Popconfirm>
            </Space>
          </template>
        </BasicTable>
      </div>

      <div class="ipd-tool-bench__detail">
        <a-card
          :bordered="false"
          class="ipd-tool-bench__card"
          size="small"
          title="工具详情"
        >
          <template v-if="activeTool">
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="名称">
                {{ activeTool.name }}
              </a-descriptions-item>
              <a-descriptions-item label="类型">
                {{ activeTool.type }}
              </a-descriptions-item>
              <a-descriptions-item label="状态">
                {{ activeTool.status }}
              </a-descriptions-item>
              <a-descriptions-item label="描述">
                {{ activeTool.description }}
              </a-descriptions-item>
              <a-descriptions-item label="创建时间">
                {{ activeTool.createTime }}
              </a-descriptions-item>
            </a-descriptions>
          </template>
          <div v-else class="ipd-tool-bench__empty">
            未选中工具：点击左侧工具名称查看详情并执行连接测试。
          </div>
        </a-card>
        <ToolTestPanel
          ref="testPanelRef"
          :tool-id="activeTool ? activeTool.id : null"
        />
      </div>

      <div class="ipd-tool-bench__deps">
        <a-card
          :bordered="false"
          class="ipd-tool-bench__card"
          size="small"
          title="反向依赖（Agent 绑定）"
        >
          <div v-if="depsLoadError" class="ipd-tool-bench__empty">
            Agent 绑定数据加载失败：反向依赖暂不可用（不显示推测数据）。
          </div>
          <template v-else-if="activeTool">
            <div class="ipd-tool-bench__meta">
              扫描 Agent {{ agentsScanned }} 个
            </div>
            <a-list
              v-if="activeToolDeps.length > 0"
              :data-source="activeToolDeps"
              size="small"
            >
              <template #renderItem="{ item }">
                <a-list-item>
                  <a-list-item-meta
                    :description="`Agent ID ${item.agentId}`"
                    :title="item.agentName"
                  />
                </a-list-item>
              </template>
            </a-list>
            <div v-else class="ipd-tool-bench__empty">
              暂无 Agent 绑定该工具（基于 AgentVO.mcpToolIds 实时计算）。
            </div>
          </template>
          <div v-else class="ipd-tool-bench__empty">
            未选中工具：选择工具后显示绑定该工具的 Agent。
          </div>
        </a-card>
      </div>
    </div>
    <ToolDrawer @reload="handleReload" />
  </Page>
</template>

<style scoped>
.ipd-tool-bench {
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(0, 1fr) 340px 300px;
  height: 100%;
  overflow: hidden;
}

.ipd-tool-bench__list {
  min-width: 0;
  overflow: auto;
}

.ipd-tool-bench__detail,
.ipd-tool-bench__deps {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: auto;
}

.ipd-tool-bench__card {
  background: var(--ipd-surface);
  border: 1px solid var(--ipd-line);
  border-radius: 8px;
  flex: 0 0 auto;
}

.ipd-tool-bench__empty,
.ipd-tool-bench__meta {
  color: var(--ipd-muted);
  font-size: 12px;
  line-height: 20px;
}

.ipd-tool-link {
  border-radius: 4px;
  color: var(--ipd-blue);
  cursor: pointer;
  padding: 0 4px;
}

.ipd-tool-link:hover {
  background: var(--ipd-blue-soft);
  color: var(--ipd-blue-dark);
}

.ipd-tool-link.is-active {
  background: var(--ipd-blue-soft);
  color: var(--ipd-blue-dark);
  font-weight: 600;
}
</style>
```

### E1-8 测试代码

`_shared/tool-test.test.ts`：

```ts
import { describe, expect, it } from 'vitest';

import {
  TOOL_TEST_PAYLOAD_ROW_LIMIT,
  failedToolTestState,
  formatDuration,
  idleToolTestState,
  normalizeToolTestResult,
  payloadToRows,
} from './tool-test';

describe('payloadToRows', () => {
  it('对象逐键展开并保持插入顺序', () => {
    expect(
      payloadToRows({ tools: 3, name: 'fs', ok: true, missing: null }),
    ).toEqual([
      { key: 'tools', value: '3' },
      { key: 'name', value: 'fs' },
      { key: 'ok', value: 'true' },
      { key: 'missing', value: '' },
    ]);
  });

  it('数组/标量折叠为单行 result', () => {
    expect(payloadToRows([1, 2])).toEqual([
      { key: 'result', value: '[1,2]' },
    ]);
    expect(payloadToRows('hello')).toEqual([
      { key: 'result', value: 'hello' },
    ]);
  });

  it('null/undefined 返回空数组', () => {
    expect(payloadToRows(null)).toEqual([]);
    expect(payloadToRows(undefined)).toEqual([]);
  });

  it('超出上限截断', () => {
    const big: Record<string, string> = {};
    for (let i = 0; i < TOOL_TEST_PAYLOAD_ROW_LIMIT + 10; i += 1) {
      big[`k${i}`] = `v${i}`;
    }
    expect(payloadToRows(big)).toHaveLength(TOOL_TEST_PAYLOAD_ROW_LIMIT);
  });
});

describe('normalizeToolTestResult', () => {
  it('成功且 message 为空时补诚实成功文案', () => {
    const state = normalizeToolTestResult(
      { data: { tools: 2 }, message: '', success: true },
      42,
      '2026-09-28T00:00:00.000Z',
    );
    expect(state.phase).toBe('complete');
    expect(state.success).toBe(true);
    expect(state.message).toBe('连接测试通过');
    expect(state.durationMs).toBe(42);
    expect(state.payloadRows).toEqual([{ key: 'tools', value: '2' }]);
    expect(state.testedAt).toBe('2026-09-28T00:00:00.000Z');
  });

  it('失败且 message 为空时补诚实失败文案；有 message 原样保留', () => {
    expect(
      normalizeToolTestResult(
        { success: false },
        7,
        '2026-09-28T00:00:00.000Z',
      ).message,
    ).toBe('连接测试失败');
    expect(
      normalizeToolTestResult(
        { message: 'command not found', success: false },
        7,
        '2026-09-28T00:00:00.000Z',
      ).message,
    ).toBe('command not found');
  });
});

describe('状态构造器', () => {
  it('idle 初始态三键为空、phase=inProgress', () => {
    const idle = idleToolTestState();
    expect(idle.phase).toBe('inProgress');
    expect(idle.testedAt).toBeNull();
    expect(idle.success).toBeNull();
    expect(idle.payloadRows).toEqual([]);
  });

  it('failedToolTestState 恒 success=false 且 phase=complete', () => {
    const failed = failedToolTestState(
      '连接测试请求失败：网络异常',
      5,
      '2026-09-28T00:00:00.000Z',
    );
    expect(failed.success).toBe(false);
    expect(failed.phase).toBe('complete');
    expect(failed.payloadRows).toEqual([]);
  });

  it('formatDuration：null 输出长横线', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(0)).toBe('0 ms');
    expect(formatDuration(128)).toBe('128 ms');
  });
});
```

`_shared/tool-reverse-deps.test.ts`：

```ts
import { describe, expect, it } from 'vitest';

import {
  buildSkillReverseDeps,
  buildToolReverseDeps,
  reverseDepsOf,
} from './tool-reverse-deps';

const agents = [
  {
    agentName: 'Beta',
    id: 2,
    mcpToolIds: [7, 9],
    skillNames: ['pdf:extract'],
  },
  {
    agentName: 'Alpha',
    id: 1,
    mcpToolIds: [7],
    skillNames: ['pdf:extract', 'docx:write'],
  },
  { agentName: 'Gamma', id: 3, mcpToolIds: null, skillNames: null },
];

describe('buildToolReverseDeps', () => {
  it('按工具 id 建索引并按 agentName 排序', () => {
    const map = buildToolReverseDeps(agents);
    expect(reverseDepsOf(map, 7)).toEqual([
      { agentId: 1, agentName: 'Alpha' },
      { agentId: 2, agentName: 'Beta' },
    ]);
    expect(reverseDepsOf(map, 9)).toEqual([{ agentId: 2, agentName: 'Beta' }]);
  });

  it('未知工具返回空数组；null 字段 Agent 不入索引', () => {
    const map = buildToolReverseDeps(agents);
    expect(reverseDepsOf(map, 404)).toEqual([]);
    expect([...map.keys()].sort((a, b) => a - b)).toEqual([7, 9]);
  });
});

describe('buildSkillReverseDeps', () => {
  it('按技能名建索引并排序', () => {
    const map = buildSkillReverseDeps(agents);
    expect(reverseDepsOf(map, 'pdf:extract')).toEqual([
      { agentId: 1, agentName: 'Alpha' },
      { agentId: 2, agentName: 'Beta' },
    ]);
    expect(reverseDepsOf(map, 'docx:write')).toEqual([
      { agentId: 1, agentName: 'Alpha' },
    ]);
    expect(reverseDepsOf(map, 'ghost:skill')).toEqual([]);
  });
});
```

### E1-9 验证命令

```bash
pnpm run check:type
pnpm exec vitest run --config vitest.ipd.config.mts apps/web-antd/src/views/mcp
pnpm run build:antd
```

人工两态验证（chrome-devtools）：① 未选中 → 中/右两栏出空态文案、测试按钮禁用；② 点击工具名 → 详情列填充、行高亮（`--ipd-blue-soft`）；③ 测试按钮 → 三态流转「测试中→通过/失败」，耗时与结果行出现，`data` 为空时显示「测试通过但未返回结构化结果」；④ 反向依赖列显示绑定 Agent（或诚实空态）。`npx 21st review` 覆盖 `data.tsx` 与新增 ts（vue 模板层走 chrome-devtools 复核）。

### E1-10 失败路径与回滚

- **agentList 分页截断**（>20 页）：`agentsScanned` 显示实际扫描数，反向依赖按已扫描数据出（不猜全量）；若后端 `pageSize` 上限 <100 报 400，把 `loadReverseDeps` 里 `pageSize: 100` 降为 `pageSize: 50` 即可（单点修改，无结构变化）。
- **测试接口返回体变化**：`ToolTestResponse` 是结构类型，字段缺失走 `payloadToRows` 空数组 + 诚实文案，不会崩面板。
- **回滚**：删 `_shared/tool-test.ts`、`use-tool-test.ts`、`tool-test-panel.vue`、`tool-reverse-deps.ts` 及两个测试文件；`data.tsx` 去掉 name 列 `slots`；`index.vue` 还原为改造前 198 行版本（git 单文件 checkout）。三栏是纯增量 UI，回滚不触碰任何 api/后端。

---

## Task E2 — 市场工具墙 + `tool_metadata` 呈现（✅ 已实施 2026-09-28，差异登记见主计划 §9）

> 修 §0.5 痛点（市场工具只有表格、元数据不可见）。「工具墙」卡片网格对齐 21st 参照 12382（三态卡）+ 29970（Schema Viewer 的树形呈现）。**关键事实（实证）**：`McpMarketTool` 实体有 `toolMetadata`（JSON 串），但出参投影 `McpMarketToolListItem` 的注释明写 "Provider metadata is deliberately excluded"，`/{marketId}/tools` **今天不透出任何元数据**。因此本任务分两条腿：
> - **E2-BE-1（后端安全投影）**：不透出原始 `toolMetadata`（可能含 provider 内部信息/凭据，违背 #19 精神），而是**白名单脱敏投影** `metadataView`；落地前前端走诚实空态。
> - **E2 前端（墙 + 树）**：`metadataView` 在场 → Schema 树呈现；缺席 → 「元数据未透出（E2-BE-1）」空态，不伪造字段（约束 #7）。
>
> **allowedPaths（前端）**：`apps/web-antd/src/views/mcp/**` + `apps/web-antd/src/api/mcp/market/model.d.ts`（仅加一个可选字段，无方法/端点变化）。**allowedPaths（E2-BE-1 后端）**：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/domain/dto/mcp/**` + 同模块 `src/test/java/**`。

### E2-0 Files

| 动作 | 路径 | 说明 |
|---|---|---|
| NEW（BE） | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/domain/dto/mcp/McpMarketMetadataRedactor.java` | 白名单脱敏投影器 |
| PATCH（BE） | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/domain/dto/mcp/McpMarketToolListItem.java` | record 增 `metadataView`，`from` 内接 redactor |
| NEW（BE 测） | `ruoyi-modules/ruoyi-chat/src/test/java/org/ruoyi/domain/dto/mcp/McpMarketMetadataRedactorTest.java` | `@Tag("dev")` 回归单测 |
| PATCH | `apps/web-antd/src/api/mcp/market/model.d.ts` | `McpMarketTool` 增 `metadataView?: Record<string, unknown> \| null` |
| NEW | `apps/web-antd/src/views/mcp/_shared/market-metadata.ts` | metadataView → 树节点（纯函数） |
| NEW | `apps/web-antd/src/views/mcp/_shared/metadata-tree.vue` | 递归 Schema 树（AntD 等价 29970） |
| NEW | `apps/web-antd/src/views/mcp/_shared/market-tool-wall.vue` | 工具墙（卡片网格 + 单个/批量加载 + 分页 + 刷新） |
| NEW | `apps/web-antd/src/views/mcp/_shared/market-wall-drawer.vue` | 工具墙抽屉壳（useVbenDrawer） |
| NEW | `apps/web-antd/src/views/mcp/_shared/market-metadata.test.ts` | 单测 |
| PATCH | `apps/web-antd/src/views/mcp/market/index.vue` | 操作列加「工具墙」入口 + 抽屉接线 |

### E2-BE-1 后端元数据安全投影（先探针、后白名单、再投影）

**第 1 步（只读探针，决定白名单内容）**：实证库内 `tool_metadata` 真实键集（不打印值）：

```bash
mysql --defaults-extra-file=~/ipd-dev.cnf ipd_dev -e \
  "select JSON_KEYS(tool_metadata) from mcp_market_tool where tool_metadata is not null limit 5;"
```

**第 2 步（白名单脱敏器全码）**——白名单初值取「展示安全」的通用元数据键；探针实证后可在 `DISPLAY_KEYS` 增删，但**任何 `(?i)(key|token|secret|password|credential|auth)` 命名键一律先拒绝再谈白名单**（#19 write-only 精神延伸到市场元数据）：

```java
package org.ruoyi.domain.dto.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 市场工具元数据脱敏投影器（Track E2-BE-1）。
 *
 * <p>原始 {@code mcp_market_tool.tool_metadata} 是市场源透传的 JSON，可能携带
 * provider 内部信息甚至凭据（对照 Global Constraints #19 write-only 精神）。投影
 * 规则保守三层：
 * <ol>
 *   <li>命名否决：键名命中 {@code (key|token|secret|password|credential|auth)}
 *       （忽略大小写）一律丢弃，即使出现在白名单里也丢弃；</li>
 *   <li>白名单：只保留 {@link #DISPLAY_KEYS}；</li>
 *   <li>形态收窄：仅保留标量与「标量数组」；对象/嵌套结构整体丢弃（不递归展开，
 *       防止攻击者把密值藏进第二层）。</li>
 * </ol>
 *
 * <p>解析失败/空输入返回空 Map（不断列表接口，前端对应空态「元数据未透出」）。
 */
public final class McpMarketMetadataRedactor {

    /** 展示安全键白名单（探针实证后可增删；新增键必须过第 1 层命名否决）。 */
    private static final Set<String> DISPLAY_KEYS = Set.of(
        "description", "homepage", "repository", "license",
        "tags", "categories", "author", "version", "icon", "readme"
    );

    private static final Pattern SENSITIVE_KEY =
        Pattern.compile("(?i).*(key|token|secret|password|credential|auth).*");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpMarketMetadataRedactor() {
    }

    public static Map<String, Object> redact(String toolMetadataJson) {
        if (toolMetadataJson == null || toolMetadataJson.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw;
        try {
            raw = MAPPER.readValue(toolMetadataJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (SENSITIVE_KEY.matcher(key).matches()) {
                continue;
            }
            if (!DISPLAY_KEYS.contains(key)) {
                continue;
            }
            if (value == null) {
                continue;
            }
            if (value instanceof List<?> list && isScalarList(list)) {
                view.put(key, list);
            } else if (value instanceof Map<?, ?>) {
                continue;
            } else {
                view.put(key, value);
            }
        }
        return view;
    }

    private static boolean isScalarList(List<?> list) {
        for (Object item : list) {
            if (item instanceof Map<?, ?> || item instanceof List<?>) {
                return false;
            }
        }
        return true;
    }
}
```

**第 3 步（`McpMarketToolListItem.java` 补丁）**——record 增一个字段、`from` 内接 redactor（调用方 `McpMarketToolListResult.of` 零改动）：

```java
package org.ruoyi.domain.dto.mcp;

import org.ruoyi.domain.entity.mcp.McpMarketTool;

import java.util.Date;
import java.util.Map;

/**
 * Public market-tool projection. Raw provider metadata is deliberately excluded;
 * only the redacted {@link #metadataView} is exposed (Track E2-BE-1).
 */
public record McpMarketToolListItem(
    Long id,
    Long marketId,
    String toolName,
    String toolDescription,
    String toolVersion,
    Boolean isLoaded,
    Long localToolId,
    Date createTime,
    Date updateTime,
    Map<String, Object> metadataView
) {

    public static McpMarketToolListItem from(McpMarketTool tool) {
        return new McpMarketToolListItem(tool.getId(), tool.getMarketId(), tool.getToolName(),
            tool.getToolDescription(), tool.getToolVersion(), tool.getIsLoaded(),
            tool.getLocalToolId(), tool.getCreateTime(), tool.getUpdateTime(),
            McpMarketMetadataRedactor.redact(tool.getToolMetadata()));
    }
}
```

**第 4 步（回归单测，`@Tag("dev")`）** `McpMarketMetadataRedactorTest.java`：

```java
package org.ruoyi.domain.dto.mcp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class McpMarketMetadataRedactorTest {

    @Test
    void whitelistedScalarsAndScalarArraysSurvive() {
        Map<String, Object> view = McpMarketMetadataRedactor.redact(
            "{\"description\":\"文件工具\",\"license\":\"MIT\",\"tags\":[\"fs\",\"doc\"],\"installCount\":42}");
        assertEquals("文件工具", view.get("description"));
        assertEquals("MIT", view.get("license"));
        assertEquals(List.of("fs", "doc"), view.get("tags"));
        assertFalse(view.containsKey("installCount"), "白名单外的键必须丢弃");
    }

    @Test
    void sensitiveKeysAreDroppedEvenWhenWhitelistedByNameCollision() {
        Map<String, Object> view = McpMarketMetadataRedactor.redact(
            "{\"apikey\":\"sk-live\",\"Authorization\":\"Bearer x\",\"token\":\"t\",\"description\":\"ok\"}");
        assertTrue(view.isEmpty() || view.keySet().equals(java.util.Set.of("description")),
            "命名否决层必须先于白名单生效");
        assertEquals("ok", view.get("description"));
    }

    @Test
    void nestedObjectsAndInvalidJsonYieldEmptyView() {
        assertTrue(McpMarketMetadataRedactor.redact("{\"config\":{\"password\":\"p\"}}").isEmpty());
        assertTrue(McpMarketMetadataRedactor.redact("{oops").isEmpty());
        assertTrue(McpMarketMetadataRedactor.redact(null).isEmpty());
    }
}
```

**后端验证命令**（E-Verify 同款 mvn 口径，沿用主计划约束 #8）：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
cd /Users/mac/Documents/ruoyi-ai
mvn -o -pl ruoyi-modules/ruoyi-chat "-Dtest=McpMarketMetadataRedactorTest" test
```

**后端失败路径**：若 record 加字段导致既有 FastExcel/序列化测试形态断言失败（`McpMarketToolListItem` 不参与 Excel 导出，实证无 `@ExcelProperty`，风险低）→ 保留 `from(McpMarketTool)` 单方法签名不变即可回退（字段删除 + redactor 文件删除）。**红线自查**：本补丁零改动 `McpToolVo` 的 `@JsonIgnore` / `applyWriteOnlyConfigPolicy`（约束 #19）。

### E2-1 `api/mcp/market/model.d.ts` 补丁

`McpMarketTool` 接口替换为（其余接口不动）：

```ts
export interface McpMarketTool {
  id: number;
  marketId: number;
  toolName: string;
  toolDescription: string;
  toolVersion: string;
  isLoaded: boolean;
  localToolId: number;
  /**
   * E2-BE-1 白名单脱敏元数据（description/homepage/license/tags…）。
   * 原始 tool_metadata 后端刻意不出参（"Provider metadata is deliberately
   * excluded"）；E2-BE-1 未落地时该键缺席，前端走诚实空态。
   */
  metadataView?: Record<string, unknown> | null;
}
```

### E2-2 `_shared/market-metadata.ts`（全码）

```ts
/**
 * 市场工具元数据 → Schema 树（Track E2，AntD 等价 21st 参照 29970 Schema Viewer）。
 *
 * <p>纯函数层：不 import api/组件。metadataView 缺席（E2-BE-1 未落地）时返回
 * available=false，渲染层出诚实空态，不伪造字段（约束 #7）。树深度/节点数双护栏，
 * 超限截断为「…」占位节点（标注真实截断事实，非 TODO 占位符）。
 */

/** 树节点（scalar=叶子；object/array=容器带 children）。 */
export interface MetadataNode {
  children: MetadataNode[];
  key: string;
  kind: 'array' | 'object' | 'scalar';
  value: string;
}

export interface MetadataView {
  available: boolean;
  nodes: MetadataNode[];
}

export const METADATA_MAX_DEPTH = 6;
export const METADATA_MAX_NODES = 200;

function scalarText(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value === null || value === undefined) return '';
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}

interface BuildBudget {
  emitted: number;
}

function buildNode(key: string, value: unknown, depth: number, budget: BuildBudget): MetadataNode {
  if (budget.emitted >= METADATA_MAX_NODES) {
    return { children: [], key: `${key}（已达 ${METADATA_MAX_NODES} 节点上限，余下截断）`, kind: 'scalar', value: '…' };
  }
  budget.emitted += 1;
  if (value !== null && typeof value === 'object' && depth >= METADATA_MAX_DEPTH) {
    return { children: [], key: `${key}（已达 ${METADATA_MAX_DEPTH} 层深度上限，深层截断）`, kind: 'scalar', value: '…' };
  }
  if (Array.isArray(value)) {
    return {
      children: value.map((item, index) => buildNode(String(index), item, depth + 1, budget)),
      key,
      kind: 'array',
      value: `Array(${value.length})`,
    };
  }
  if (value !== null && typeof value === 'object') {
    const children = Object.entries(value as Record<string, unknown>).map(
      ([childKey, childValue]) => buildNode(childKey, childValue, depth + 1, budget),
    );
    return { children, key, kind: 'object', value: `Object(${children.length})` };
  }
  return { children: [], key, kind: 'scalar', value: scalarText(value) };
}

/**
 * metadataView → 树视图。raw 非对象（null/undefined/数组/字符串）一律 available=false
 * （该键由后端白名单投影保证是对象；异常形态不带病渲染）。
 */
export function parseMetadataView(raw: unknown): MetadataView {
  if (raw === null || raw === undefined || typeof raw !== 'object' || Array.isArray(raw)) {
    return { available: false, nodes: [] };
  }
  const budget: BuildBudget = { emitted: 0 };
  const nodes = Object.entries(raw as Record<string, unknown>).map(
    ([key, value]) => buildNode(key, value, 0, budget),
  );
  return { available: nodes.length > 0, nodes };
}
```

### E2-3 `_shared/metadata-tree.vue`（全码，自递归）

```vue
<script setup lang="ts">
/**
 * 元数据 Schema 树（Track E2）。自递归组件（metadata-tree.vue 文件名自引用）。
 * 取色 `var(--ipd-*)`、Tag 圆角 4px（约束 #21）。kind 三态 Tag 呼应 29970
 * Schema Viewer 的类型着色语义，色板只用 ipd token。
 */
import type { MetadataNode } from './market-metadata';

defineProps<{ nodes: MetadataNode[] }>();

const kindLabel: Record<MetadataNode['kind'], string> = {
  array: '数组',
  object: '对象',
  scalar: '值',
};
</script>

<template>
  <ul class="ipd-md-tree">
    <li v-for="node in nodes" :key="node.key + node.value" class="ipd-md-node">
      <div class="ipd-md-line">
        <span :class="`ipd-md-kind ipd-md-kind--${node.kind}`">
          {{ kindLabel[node.kind] }}
        </span>
        <span class="ipd-md-key">{{ node.key }}</span>
        <span class="ipd-md-value">{{ node.value }}</span>
      </div>
      <MetadataTree
        v-if="node.children.length > 0"
        :nodes="node.children"
        class="ipd-md-children"
      />
    </li>
  </ul>
</template>

<style scoped>
.ipd-md-tree {
  display: flex;
  flex-direction: column;
  gap: 2px;
  list-style: none;
  margin: 0;
  padding: 0;
}

.ipd-md-children {
  border-left: 1px solid var(--ipd-line);
  margin-left: 10px;
  padding-left: 10px;
}

.ipd-md-line {
  align-items: baseline;
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 6px;
  line-height: 20px;
}

.ipd-md-kind {
  border: 1px solid var(--ipd-line);
  border-radius: 4px;
  color: var(--ipd-muted);
  flex: 0 0 auto;
  font-size: 11px;
  padding: 0 6px;
}

.ipd-md-kind--object {
  background: var(--ipd-blue-soft);
  border-color: var(--ipd-blue);
  color: var(--ipd-blue-dark);
}

.ipd-md-kind--array {
  background: var(--ipd-bg);
  border-color: var(--ipd-amber);
  color: var(--ipd-amber);
}

.ipd-md-key {
  color: var(--ipd-text);
  font-weight: 600;
  word-break: break-all;
}

.ipd-md-value {
  color: var(--ipd-muted);
  word-break: break-all;
}
</style>
```

### E2-4 `_shared/market-tool-wall.vue`（全码）

```vue
<script setup lang="ts">
/**
 * 市场工具墙（Track E2）：卡片网格 + 单个/批量「加载到本地」+ 分页 + 刷新市场。
 *
 * <p>数据源全部为既有 api（mcpMarketToolList/mcpMarketLoadTool/
 * mcpMarketBatchLoadTools/mcpMarketRefresh），零新增端点（约束 #20）。
 * 元数据呈现：metadataView 在场 → Schema 树；缺席 → 「元数据未透出」诚实空态。
 */
import type { McpMarketTool } from '#/api/mcp/market/model';

import { onMounted, ref, watch } from 'vue';

import {
  mcpMarketBatchLoadTools,
  mcpMarketLoadTool,
  mcpMarketRefresh,
  mcpMarketToolList,
} from '#/api/mcp/market';

import MetadataTree from './metadata-tree.vue';
import { parseMetadataView } from './market-metadata';

const props = defineProps<{ marketId: number }>();

const PAGE_SIZE = 12;

const tools = ref<McpMarketTool[]>([]);
const page = ref(1);
const total = ref(0);
const loading = ref(false);
const loadError = ref(false);
const checkedIds = ref<number[]>([]);
const actionPending = ref(false);

async function loadPage(nextPage: number) {
  loading.value = true;
  loadError.value = false;
  try {
    const result = await mcpMarketToolList(props.marketId, {
      page: nextPage,
      size: PAGE_SIZE,
    });
    tools.value = result.data ?? [];
    total.value = result.total;
    page.value = nextPage;
    checkedIds.value = [];
  } catch {
    loadError.value = true;
    tools.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void loadPage(1);
});

watch(
  () => props.marketId,
  (nextId, prevId) => {
    if (nextId !== prevId) void loadPage(1);
  },
);

function toggleChecked(toolId: number, checked: boolean) {
  if (checked) {
    if (!checkedIds.value.includes(toolId)) checkedIds.value.push(toolId);
  } else {
    checkedIds.value = checkedIds.value.filter((id) => id !== toolId);
  }
}

async function handleLoadOne(tool: McpMarketTool) {
  actionPending.value = true;
  try {
    await mcpMarketLoadTool(tool.id);
    await loadPage(page.value);
  } finally {
    actionPending.value = false;
  }
}

async function handleBatchLoad() {
  if (checkedIds.value.length === 0) return;
  actionPending.value = true;
  try {
    await mcpMarketBatchLoadTools(checkedIds.value);
    await loadPage(page.value);
  } finally {
    actionPending.value = false;
  }
}

async function handleRefresh() {
  actionPending.value = true;
  try {
    await mcpMarketRefresh(props.marketId);
    await loadPage(1);
  } finally {
    actionPending.value = false;
  }
}
</script>

<template>
  <div class="ipd-wall">
    <div class="ipd-wall__bar">
      <a-button
        :disabled="checkedIds.length === 0 || actionPending"
        :loading="actionPending"
        size="small"
        type="primary"
        data-testid="ipd-wall-batch"
        @click="handleBatchLoad"
      >
        批量加载（{{ checkedIds.length }}）
      </a-button>
      <a-button
        :disabled="actionPending"
        size="small"
        data-testid="ipd-wall-refresh"
        @click="handleRefresh"
      >
        刷新市场
      </a-button>
      <span class="ipd-wall__meta">共 {{ total }} 个工具</span>
    </div>

    <div v-if="loadError" class="ipd-wall__empty">
      市场工具加载失败：请检查市场地址后重试（不显示缓存假数据）。
    </div>
    <a-spin v-else :spinning="loading">
      <div class="ipd-wall__grid">
        <div v-for="tool in tools" :key="tool.id" class="ipd-wall__card">
          <div class="ipd-wall__card-head">
            <a-checkbox
              :checked="checkedIds.includes(tool.id)"
              @change="(e: Event) => toggleChecked(tool.id, (e.target as HTMLInputElement).checked)"
            />
            <span class="ipd-wall__name">{{ tool.toolName }}</span>
            <a-tag v-if="tool.toolVersion" class="ipd-wall__tag" color="blue">
              {{ tool.toolVersion }}
            </a-tag>
            <a-tag
              :class="
                tool.isLoaded
                  ? 'ipd-wall__tag ipd-wall__tag--loaded'
                  : 'ipd-wall__tag'
              "
            >
              {{ tool.isLoaded ? '已加载' : '未加载' }}
            </a-tag>
          </div>
          <div class="ipd-wall__desc">{{ tool.toolDescription || '（无描述）' }}</div>

          <template v-if="parseMetadataView(tool.metadataView).available">
            <div class="ipd-wall__sub">工具元数据</div>
            <MetadataTree :nodes="parseMetadataView(tool.metadataView).nodes" />
          </template>
          <div v-else class="ipd-wall__empty-inline">
            元数据未透出：待后端 E2-BE-1 白名单投影落地后自动显示。
          </div>

          <div class="ipd-wall__actions">
            <a-button
              :disabled="tool.isLoaded || actionPending"
              size="small"
              type="link"
              @click="handleLoadOne(tool)"
            >
              加载到本地
            </a-button>
            <a-button
              v-if="tool.isLoaded && tool.localToolId"
              disabled
              size="small"
              type="link"
            >
              本地工具 ID {{ tool.localToolId }}
            </a-button>
          </div>
        </div>
      </div>
      <div v-if="!loadError && tools.length === 0" class="ipd-wall__empty">
        该市场暂无工具：可先点击「刷新市场」拉取上游清单。
      </div>
    </a-spin>

    <a-pagination
      v-if="total > PAGE_SIZE"
      :current="page"
      :page-size="PAGE_SIZE"
      :total="total"
      class="ipd-wall__pager"
      size="small"
      @change="(p: number) => loadPage(p)"
    />
  </div>
</template>

<style scoped>
.ipd-wall {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ipd-wall__bar {
  align-items: center;
  display: flex;
  gap: 8px;
}

.ipd-wall__meta {
  color: var(--ipd-muted);
  font-size: 12px;
}

.ipd-wall__grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
}

.ipd-wall__card {
  background: var(--ipd-surface);
  border: 1px solid var(--ipd-line);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
}

.ipd-wall__card-head {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.ipd-wall__name {
  color: var(--ipd-text);
  font-size: 14px;
  font-weight: 600;
  word-break: break-all;
}

.ipd-wall__tag {
  border-radius: 4px;
}

.ipd-wall__tag--loaded {
  background: var(--ipd-green);
  border-color: var(--ipd-green);
  color: var(--ipd-surface);
}

.ipd-wall__desc {
  color: var(--ipd-muted);
  font-size: 12px;
  line-height: 20px;
  min-height: 40px;
  word-break: break-all;
}

.ipd-wall__sub {
  color: var(--ipd-text);
  font-size: 12px;
  font-weight: 600;
}

.ipd-wall__empty,
.ipd-wall__empty-inline {
  color: var(--ipd-muted);
  font-size: 12px;
  line-height: 20px;
}

.ipd-wall__actions {
  display: flex;
  gap: 4px;
  margin-top: auto;
}

.ipd-wall__pager {
  align-self: flex-end;
}
</style>
```

> 颜色红线自查：`a-tag color="blue"` 是 antd 语义色 token（组件层主题由 `preferences.ts theme.*` 统一注入，非第三套色板、非硬编码 hex）；其余取色全部 `var(--ipd-*)`。

### E2-5 `_shared/market-wall-drawer.vue`（全码）

```vue
<script setup lang="ts">
/**
 * 工具墙抽屉壳（Track E2）。Vben drawer 连接组件（与 market-drawer.vue 同款模式），
 * data 协议：{ marketId: number }。
 */
import { ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import MarketToolWall from './market-tool-wall.vue';

const marketId = ref<null | number>(null);

const [BasicDrawer, drawerApi] = useVbenDrawer({
  onOpenChange(isOpen) {
    if (!isOpen) {
      return null;
    }
    const data = drawerApi.getData() as { marketId: number };
    marketId.value = data.marketId;
    return null;
  },
});
</script>

<template>
  <BasicDrawer class="w-[1000px]" title="市场工具墙">
    <MarketToolWall v-if="marketId !== null" :market-id="marketId" />
    <div v-else class="ipd-wall__empty">缺少市场标识：请从市场列表重新打开。</div>
  </BasicDrawer>
</template>

<style scoped>
.ipd-wall__empty {
  color: var(--ipd-muted);
  font-size: 12px;
}
</style>
```

### E2-6 `views/mcp/market/index.vue` 补丁（三段增量）

① script 增 import（放在 `import marketDrawer from './market-drawer.vue';` 之后）：

```ts
import marketWallDrawer from '../_shared/market-wall-drawer.vue';
```

② script 增抽屉接线与入口（放在既有 `const [MarketDrawer, …] = useVbenDrawer(…)` 之后）：

```ts
const [MarketWallDrawer, wallDrawerApi] = useVbenDrawer({
  connectedComponent: marketWallDrawer,
});

function handleOpenWall(row: McpMarket) {
  wallDrawerApi.setData({ marketId: row.id });
  wallDrawerApi.open();
}
```

③ template 操作列 `<Space>` 内、`刷新` 按钮之前插入：

```vue
          <ghost-button
            v-access:code="['mcp:market:query']"
            @click.stop="handleOpenWall(row)"
          >
            工具墙
          </ghost-button>
```

并在 `<MarketDrawer @reload="tableApi.query()" />` 之后追加一行：

```vue
    <MarketWallDrawer />
```

### E2-7 测试代码 `_shared/market-metadata.test.ts`

```ts
import { describe, expect, it } from 'vitest';

import {
  METADATA_MAX_DEPTH,
  METADATA_MAX_NODES,
  parseMetadataView,
} from './market-metadata';

describe('parseMetadataView', () => {
  it('E2-BE-1 未透出（undefined/null/非对象）→ available=false', () => {
    for (const raw of [undefined, null, 'str', 42, ['a']]) {
      expect(parseMetadataView(raw)).toEqual({ available: false, nodes: [] });
    }
  });

  it('标量叶子 + 对象/数组容器结构', () => {
    const view = parseMetadataView({
      description: '文件工具',
      tags: ['fs', 'doc'],
      extra: { a: 1 },
    });
    expect(view.available).toBe(true);
    const byKey = new Map(view.nodes.map((n) => [n.key, n]));
    expect(byKey.get('description')).toMatchObject({
      kind: 'scalar',
      value: '文件工具',
    });
    expect(byKey.get('tags')).toMatchObject({ kind: 'array', value: 'Array(2)' });
    expect(byKey.get('tags')?.children).toHaveLength(2);
    expect(byKey.get('extra')).toMatchObject({ kind: 'object', value: 'Object(1)' });
    expect(byKey.get('extra')?.children[0]).toMatchObject({
      key: 'a',
      value: '1',
    });
  });

  it('深度超限截断为诚实截断节点', () => {
    let deep: unknown = 'leaf';
    for (let i = 0; i < METADATA_MAX_DEPTH + 2; i += 1) deep = { nested: deep };
    const view = parseMetadataView({ root: deep });
    const text = JSON.stringify(view.nodes);
    expect(text).toContain('深层截断');
  });

  it('节点数超限截断', () => {
    const wide: Record<string, string> = {};
    for (let i = 0; i < METADATA_MAX_NODES + 20; i += 1) wide[`k${i}`] = 'v';
    const view = parseMetadataView(wide);
    expect(view.nodes.length).toBeLessThanOrEqual(METADATA_MAX_NODES + 1);
    expect(JSON.stringify(view.nodes)).toContain('节点上限');
  });
});
```

### E2-8 验证命令与人工两态

```bash
pnpm run check:type
pnpm exec vitest run --config vitest.ipd.config.mts apps/web-antd/src/views/mcp
pnpm run build:antd
```

后端腿（E2-BE-1）：`mvn -o -pl ruoyi-modules/ruoyi-chat "-Dtest=McpMarketMetadataRedactorTest" test`（PATH/JAVA_HOME 口径见 E2-BE-1 第 4 步）。

人工两态验证（chrome-devtools）：① 市场列表 → 「工具墙」→ 卡片网格出现、分页可用；② E2-BE-1 未部署时每卡显示「元数据未透出」空态、部署后同一数据自动长出 Schema 树；③ 单个/批量加载后 `isLoaded` 翻转、批量计数归零；④ `npx 21st review` 覆盖新增 ts/tsx 与 css（vue 模板走 chrome-devtools）。

### E2-9 失败路径与回滚

- **metadataView 形态漂移**：`parseMetadataView` 对非对象一律 available=false（有单测钉死），不会带病渲染。
- **大市场分页性能**：PAGE_SIZE=12 卡/页 + 树 200 节点/6 层护栏，退化路径为截断节点（有单测）。
- **回滚**：前端删 `_shared/market-*.ts|vue`、`metadata-tree.vue`、`market-metadata.test.ts`，还原 `model.d.ts` 与 `market/index.vue`；后端还原 `McpMarketToolListItem.java` + 删 redactor 与其测试。前后腿可独立回滚（前端空态文案天然兼容无 `metadataView` 的旧后端）。

---

## Task E3 — Skill 分组绑定台（分组搜索 + SKILL.md 预览 + 反向依赖）（✅ 已实施 2026-09-28，差异登记见主计划 §9）

> 修 §0.5 痛点（skill 只是一个裸多选下拉、69 个 skill 难检索、绑定关系不可见）。参照 21st 28366（Settings Sidebar）/ 24937（Vertical Settings Tabs）的信息架构，用 AntD + Vben 表达「左侧分组列表 + 右侧预览与反向依赖」双栏台。
>
> **契约事实（实证）**：技能唯一读接口是 `GET /agent/skillOptions` → `SkillOptionVo { name, description }`（`AgentController L125-128`）；description 即 SKILL.md front-matter 的描述。**后端今天没有 SKILL.md 正文读接口**，故「SKILL.md 预览」= front-matter 预览 + 诚实标注；正文预览列为跨轨请求 **E3-BE-1**（allowedPaths：`org/ruoyi/controller/agent/**` + `domain/vo/agent/**`），未落地前**不发任何幽灵 HTTP**（约束 #20）。
>
> **allowedPaths（本任务前端写权限面）**：`apps/web-antd/src/views/agent/agent/**` + 只读复用 `views/mcp/_shared/tool-reverse-deps.ts`（跨页 import，不复制逻辑）。不碰 `api/agent/**`（`agentSkillOptions`/`agentList` 既有导出足够）。

### E3-0 Files

| 动作 | 路径 | 说明 |
|---|---|---|
| NEW | `apps/web-antd/src/views/agent/agent/_shared/skill-group.ts` | 分组 + 搜索纯函数 |
| NEW | `apps/web-antd/src/views/agent/agent/_shared/skill-binding-bench.vue` | Skill 分组绑定台（双栏） |
| NEW | `apps/web-antd/src/views/agent/agent/_shared/skill-group.test.ts` | 单测 |
| PATCH | `apps/web-antd/src/views/agent/agent/data.tsx` | `skillNames` 表单项隐藏（deps show:false），值面移交绑定台 |
| PATCH | `apps/web-antd/src/views/agent/agent/agent-drawer.vue` | 挂载绑定台 + 值合成（完整替换体见 E3-4） |

### E3-1 Interfaces

消费：

```ts
// #/api/agent/agent（既有，只读）
agentSkillOptions(): Promise<SkillOption[]>        // SkillOption = { name: string; description?: string }
agentList(params?: PageQuery): Promise<PageResult<AgentVO>>
// ../../../mcp/_shared/tool-reverse-deps（E1 产物，只读复用）
buildSkillReverseDeps(agents: AgentBinding[]): Map<string, ReverseDep[]>
reverseDepsOf<K>(map: Map<K, ReverseDep[]>, key: K): ReverseDep[]
```

产出：

```ts
// _shared/skill-group.ts
interface SkillEntry { name: string; description?: null | string }
interface SkillGroup { key: string; label: string; skills: SkillEntry[] }
function skillGroupKey(name: string): string
function groupSkills(skills: SkillEntry[]): SkillGroup[]
function searchSkills(skills: SkillEntry[], keyword: string): SkillEntry[]

// _shared/skill-binding-bench.vue
props: { modelValue: string[] }                    // = form 的 skillNames 值面
emits: (e: 'update:modelValue', value: string[]): void
```

### E3-2 `_shared/skill-group.ts`（全码）

```ts
/**
 * Skill 分组与搜索（Track E3，纯函数）。
 *
 * <p>分组键是**前端派生约定**（name 冒号/斜杠前缀），非后端契约字段——后端
 * SkillOptionVo 只有 name/description（AgentController L125 实证）。派生规则确定性：
 * ① name 含 `:` 取冒号前缀；② 否则含 `/` 取斜杠前缀；③ 都没有归「通用」组。
 * 「通用」组固定排在最后，其余组按 label 字典序（渲染与断言确定性）。
 */

export interface SkillEntry {
  description?: null | string;
  name: string;
}

export interface SkillGroup {
  key: string;
  label: string;
  skills: SkillEntry[];
}

export const SKILL_FALLBACK_GROUP = '通用';

/** 分组键派生（空/非字符串防御归「通用」，不断列表）。 */
export function skillGroupKey(name: string): string {
  if (typeof name !== 'string' || name.trim() === '') return SKILL_FALLBACK_GROUP;
  const colon = name.indexOf(':');
  if (colon > 0) return name.slice(0, colon);
  const slash = name.indexOf('/');
  if (slash > 0) return name.slice(0, slash);
  return SKILL_FALLBACK_GROUP;
}

/** 分组：同名 skill 去重（name 唯一键，后到覆盖前到 description）；组内按 name 字典序。 */
export function groupSkills(skills: SkillEntry[]): SkillGroup[] {
  const byName = new Map<string, SkillEntry>();
  for (const skill of skills) {
    if (typeof skill?.name !== 'string' || skill.name === '') continue;
    byName.set(skill.name, { description: skill.description ?? '', name: skill.name });
  }
  const groups = new Map<string, SkillEntry[]>();
  for (const skill of byName.values()) {
    const key = skillGroupKey(skill.name);
    const list = groups.get(key);
    if (list) {
      list.push(skill);
    } else {
      groups.set(key, [skill]);
    }
  }
  const result: SkillGroup[] = [];
  for (const [label, list] of groups) {
    list.sort((a, b) => a.name.localeCompare(b.name));
    result.push({ key: label, label, skills: list });
  }
  result.sort((a, b) => {
    if (a.label === SKILL_FALLBACK_GROUP) return 1;
    if (b.label === SKILL_FALLBACK_GROUP) return -1;
    return a.label.localeCompare(b.label);
  });
  return result;
}

/**
 * 搜索：name/description 大小写不敏感包含匹配；keyword 空白（trim 后空）返回全量。
 * 匹配在分组前执行（调用方 searchSkills → groupSkills 组合）。
 */
export function searchSkills(skills: SkillEntry[], keyword: string): SkillEntry[] {
  const needle = keyword.trim().toLowerCase();
  if (needle === '') return skills;
  return skills.filter((skill) => {
    const name = skill.name.toLowerCase();
    const description = (skill.description ?? '').toLowerCase();
    return name.includes(needle) || description.includes(needle);
  });
}
```

### E3-3 `_shared/skill-binding-bench.vue`（全码）

```vue
<script setup lang="ts">
/**
 * Skill 分组绑定台（Track E3）：左栏分组勾选（搜索过滤）+ 右栏 SKILL.md
 * front-matter 预览与反向依赖。
 *
 * <p>值面协议：v-model = string[]（= AgentVO.skillNames），与 useVbenForm 隐藏
 * 字段做显式合成（E5 同款模式，见 E3-4）。SKILL.md **正文**预览依赖后端读接口
 * E3-BE-1（未提供）：预览区如实标注只展示 front-matter，不发幽灵请求（#20）。
 * 反向依赖 = E1 的 buildSkillReverseDeps（agentList 客户端计算，零后端改动）。
 */
import type { SkillOption } from '#/api/agent/agent/model';

import type { ReverseDep } from '../../../mcp/_shared/tool-reverse-deps';

import { computed, onMounted, ref } from 'vue';

import { agentList, agentSkillOptions } from '#/api/agent/agent';

import {
  buildSkillReverseDeps,
  reverseDepsOf,
} from '../../../mcp/_shared/tool-reverse-deps';
import { groupSkills, searchSkills, skillGroupKey } from './skill-group';

const props = defineProps<{ modelValue: string[] }>();
const emit = defineEmits<{
  (e: 'update:modelValue', value: string[]): void;
}>();

const keyword = ref('');
const skills = ref<SkillOption[]>([]);
const loadError = ref(false);
const previewName = ref<null | string>(null);
const reverseDepMap = ref(new Map<string, ReverseDep[]>());

const groups = computed(() =>
  groupSkills(searchSkills(skills.value, keyword.value)),
);

const totalSkills = computed(() => skills.value.length);

const previewSkill = computed(
  () => skills.value.find((skill) => skill.name === previewName.value) ?? null,
);

const previewGroup = computed(() =>
  previewSkill.value ? skillGroupKey(previewSkill.value.name) : '',
);

const previewDeps = computed(() =>
  previewName.value ? reverseDepsOf(reverseDepMap.value, previewName.value) : [],
);

const selectedSet = computed(() => new Set(props.modelValue));

function isChecked(name: string): boolean {
  return selectedSet.value.has(name);
}

function toggleSkill(name: string, checked: boolean) {
  const next = new Set(selectedSet.value);
  if (checked) {
    next.add(name);
  } else {
    next.delete(name);
  }
  emit('update:modelValue', [...next]);
}

function handlePreview(name: string) {
  previewName.value = name;
}

onMounted(async () => {
  try {
    skills.value = await agentSkillOptions();
  } catch {
    loadError.value = true;
    skills.value = [];
  }
  try {
    const collected: Awaited<ReturnType<typeof agentList>>['rows'] = [];
    for (let pageNum = 1; pageNum <= 20; pageNum += 1) {
      const result = await agentList({ pageNum, pageSize: 100 });
      collected.push(...result.rows);
      if (collected.length >= result.total) break;
    }
    reverseDepMap.value = buildSkillReverseDeps(collected);
  } catch {
    reverseDepMap.value = new Map();
  }
});
</script>

<template>
  <div class="ipd-skill-bench">
    <div class="ipd-skill-bench__left">
      <div class="ipd-skill-bench__bar">
        <a-input-search
          v-model:value="keyword"
          allow-clear
          placeholder="搜索技能（名称或描述）"
          size="small"
        />
        <span class="ipd-skill-bench__meta">
          共 {{ totalSkills }} 个技能 / 已绑定 {{ modelValue.length }} 个
        </span>
      </div>

      <div v-if="loadError" class="ipd-skill-bench__empty">
        技能清单加载失败：可稍后重试（不显示缓存假数据）。
      </div>
      <div v-else-if="groups.length === 0" class="ipd-skill-bench__empty">
        没有匹配「{{ keyword }}」的技能。
      </div>

      <div v-for="group in groups" :key="group.key" class="ipd-skill-bench__group">
        <div class="ipd-skill-bench__group-head">
          <span class="ipd-skill-bench__group-label">{{ group.label }}</span>
          <span class="ipd-skill-bench__meta">{{ group.skills.length }} 个</span>
        </div>
        <div
          v-for="skill in group.skills"
          :key="skill.name"
          class="ipd-skill-bench__row"
          :class="{ 'is-preview': previewName === skill.name }"
          @click="handlePreview(skill.name)"
        >
          <a-checkbox
            :checked="isChecked(skill.name)"
            @click.stop
            @change="(e: Event) => toggleSkill(skill.name, (e.target as HTMLInputElement).checked)"
          />
          <span class="ipd-skill-bench__name">{{ skill.name }}</span>
          <span class="ipd-skill-bench__desc">
            {{ skill.description || '（无描述）' }}
          </span>
        </div>
      </div>
    </div>

    <div class="ipd-skill-bench__right">
      <template v-if="previewSkill">
        <div class="ipd-skill-bench__panel">
          <div class="ipd-skill-bench__panel-title">SKILL.md 预览（front-matter）</div>
          <div class="ipd-skill-bench__field">
            <span class="ipd-skill-bench__field-key">name</span>
            <span class="ipd-skill-bench__field-value">{{ previewSkill.name }}</span>
          </div>
          <div class="ipd-skill-bench__field">
            <span class="ipd-skill-bench__field-key">group</span>
            <span class="ipd-skill-bench__field-value">{{ previewGroup }}</span>
          </div>
          <div class="ipd-skill-bench__field">
            <span class="ipd-skill-bench__field-key">description</span>
            <span class="ipd-skill-bench__field-value">
              {{ previewSkill.description || '（无描述）' }}
            </span>
          </div>
          <div class="ipd-skill-bench__notice">
            SKILL.md 正文预览依赖后端读接口（E3-BE-1，未提供）：当前仅展示
            front-matter（skillOptions 契约只含 name/description），不发起正文请求。
          </div>
        </div>

        <div class="ipd-skill-bench__panel">
          <div class="ipd-skill-bench__panel-title">
            反向依赖（绑定该技能的 Agent）
          </div>
          <div v-if="previewDeps.length > 0" class="ipd-skill-bench__deps">
            <div
              v-for="dep in previewDeps"
              :key="dep.agentId"
              class="ipd-skill-bench__field"
            >
              <span class="ipd-skill-bench__field-key">Agent</span>
              <span class="ipd-skill-bench__field-value">
                {{ dep.agentName }}（ID {{ dep.agentId }}）
              </span>
            </div>
          </div>
          <div v-else class="ipd-skill-bench__empty">
            暂无 Agent 绑定该技能（基于 AgentVO.skillNames 实时计算）。
          </div>
        </div>
      </template>
      <div v-else class="ipd-skill-bench__empty">
        未选中技能：点击左侧技能行查看 SKILL.md 预览与反向依赖。
      </div>
    </div>
  </div>
</template>

<style scoped>
.ipd-skill-bench {
  border: 1px solid var(--ipd-line);
  border-radius: 8px;
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(0, 1fr) 320px;
  padding: 12px;
}

.ipd-skill-bench__left {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 420px;
  overflow: auto;
}

.ipd-skill-bench__right {
  border-left: 1px solid var(--ipd-line);
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: auto;
  padding-left: 12px;
}

.ipd-skill-bench__bar {
  align-items: center;
  display: flex;
  gap: 8px;
}

.ipd-skill-bench__meta,
.ipd-skill-bench__empty,
.ipd-skill-bench__notice {
  color: var(--ipd-muted);
  font-size: 12px;
  line-height: 20px;
}

.ipd-skill-bench__group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ipd-skill-bench__group-head {
  align-items: baseline;
  display: flex;
  gap: 8px;
  padding: 4px 0;
}

.ipd-skill-bench__group-label {
  color: var(--ipd-text);
  font-size: 13px;
  font-weight: 600;
}

.ipd-skill-bench__row {
  align-items: baseline;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  gap: 8px;
  padding: 4px 6px;
}

.ipd-skill-bench__row:hover,
.ipd-skill-bench__row.is-preview {
  background: var(--ipd-blue-soft);
}

.ipd-skill-bench__name {
  color: var(--ipd-text);
  flex: 0 0 auto;
  font-size: 12px;
  font-weight: 600;
}

.ipd-skill-bench__desc {
  color: var(--ipd-muted);
  flex: 1;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ipd-skill-bench__panel {
  background: var(--ipd-surface);
  border: 1px solid var(--ipd-line);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
}

.ipd-skill-bench__panel-title {
  color: var(--ipd-text);
  font-size: 13px;
  font-weight: 600;
}

.ipd-skill-bench__field {
  display: flex;
  font-size: 12px;
  gap: 8px;
  line-height: 20px;
}

.ipd-skill-bench__field-key {
  color: var(--ipd-muted);
  flex: 0 0 84px;
}

.ipd-skill-bench__field-value {
  color: var(--ipd-text);
  flex: 1;
  word-break: break-all;
}
</style>
```

### E3-4 `data.tsx` 与 `agent-drawer.vue` 补丁

**① `data.tsx`**：`skillNames` 表单项整项替换为「隐藏值面」（字段仍在 form 值树里占位，提交前由绑定台值覆盖）：

```tsx
  {
    component: 'Input',
    dependencies: {
      show: () => false,
      triggerFields: [''],
    },
    // E3：skillNames 值面移交 SkillBindingBench（drawer 内挂载并显式合成），
    // 本项仅占位保持 form 值树完整，不再渲染裸多选下拉
    fieldName: 'skillNames',
    label: '技能',
  },
```

（删除该项原有 `api: agentSkillOptions` 等渲染配置；`data.tsx` 顶部 `agentSkillOptions` import 随之删除，避免 unused 报错。）

**② `agent-drawer.vue` 完整替换体**：

```vue
<script setup lang="ts">
import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { $t } from '@vben/locales';
import { cloneDeep } from '@vben/utils';

import { useVbenForm } from '#/adapter/form';
import { agentAdd, agentInfo, agentUpdate } from '#/api/agent/agent';
import {
  defaultFormValueGetter,
  useBeforeCloseDiff,
} from '#/utils/popup';

import SkillBindingBench from './_shared/skill-binding-bench.vue';
import { drawerSchema } from './data';

const emit = defineEmits<{ reload: [] }>();

const isUpdate = ref(false);
const title = computed(() => {
  return isUpdate.value ? $t('pages.common.edit') : $t('pages.common.add');
});

/** E3：技能绑定台值面（= AgentVO.skillNames），提交前与 form 值显式合成。 */
const skillNames = ref<string[]>([]);

const [BasicForm, formApi] = useVbenForm({
  commonConfig: {
    formItemClass: 'col-span-2',
    componentProps: {
      class: 'w-full',
    },
  },
  layout: 'vertical',
  schema: drawerSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-2 gap-x-4',
});

const { onBeforeClose, markInitialized, resetInitialized } = useBeforeCloseDiff(
  {
    initializedGetter: defaultFormValueGetter(formApi),
    currentGetter: defaultFormValueGetter(formApi),
  },
);

const [BasicDrawer, drawerApi] = useVbenDrawer({
  onBeforeClose,
  onClosed: handleClosed,
  onConfirm: handleConfirm,
  async onOpenChange(isOpen) {
    if (!isOpen) {
      return null;
    }
    drawerApi.drawerLoading(true);

    const { id } = drawerApi.getData() as { id?: number | string };
    isUpdate.value = !!id;
    if (isUpdate.value && id) {
      const record = await agentInfo(id);
      await formApi.setValues(record);
      skillNames.value = Array.isArray(record.skillNames)
        ? [...record.skillNames]
        : [];
    } else {
      skillNames.value = [];
    }
    await markInitialized();

    drawerApi.drawerLoading(false);
  },
});

async function handleConfirm() {
  try {
    drawerApi.lock(true);
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    const data = cloneDeep(await formApi.getValues());
    // E3 合成：技能值面以绑定台为准（表单 skillNames 项为隐藏占位）
    data.skillNames = [...skillNames.value];
    await (isUpdate.value ? agentUpdate(data) : agentAdd(data));
    resetInitialized();
    emit('reload');
    drawerApi.close();
  } catch (error) {
    console.error(error);
  } finally {
    drawerApi.lock(false);
  }
}

async function handleClosed() {
  await formApi.resetForm();
  skillNames.value = [];
  resetInitialized();
}
</script>

<template>
  <BasicDrawer :title="title" class="w-[720px]">
    <BasicForm />
    <SkillBindingBench v-model="skillNames" class="ipd-agent-bench" />
  </BasicDrawer>
</template>

<style scoped>
.ipd-agent-bench {
  margin-top: 12px;
}
</style>
```

### E3-5 测试代码 `_shared/skill-group.test.ts`

```ts
import { describe, expect, it } from 'vitest';

import {
  SKILL_FALLBACK_GROUP,
  groupSkills,
  searchSkills,
  skillGroupKey,
} from './skill-group';

const skills = [
  { description: '读 PDF', name: 'pdf:extract' },
  { description: '写 Word', name: 'docx:write' },
  { description: '读 PDF 页', name: 'pdf:pages' },
  { description: '通用助手', name: 'helper' },
  { description: '斜杠命名', name: 'fs/read' },
];

describe('skillGroupKey', () => {
  it('冒号前缀 > 斜杠前缀 > 通用', () => {
    expect(skillGroupKey('pdf:extract')).toBe('pdf');
    expect(skillGroupKey('fs/read')).toBe('fs');
    expect(skillGroupKey('helper')).toBe(SKILL_FALLBACK_GROUP);
    expect(skillGroupKey('')).toBe(SKILL_FALLBACK_GROUP);
    expect(skillGroupKey(':weird')).toBe(SKILL_FALLBACK_GROUP);
  });
});

describe('groupSkills', () => {
  it('按派生键分组、组内字典序、通用组固定最后', () => {
    const groups = groupSkills(skills);
    expect(groups.map((g) => g.label)).toEqual(['docx', 'fs', 'pdf', SKILL_FALLBACK_GROUP]);
    expect(groups[2].skills.map((s) => s.name)).toEqual(['pdf:extract', 'pdf:pages']);
  });

  it('同名去重（后到覆盖）、脏数据跳过', () => {
    const groups = groupSkills([
      { description: 'v1', name: 'pdf:extract' },
      { description: 'v2', name: 'pdf:extract' },
      { description: 'x', name: '' },
      { description: 'y', name: undefined as unknown as string },
    ]);
    const pdf = groups.find((g) => g.label === 'pdf');
    expect(pdf?.skills).toEqual([{ description: 'v2', name: 'pdf:extract' }]);
    expect(groups).toHaveLength(1);
  });
});

describe('searchSkills', () => {
  it('name/description 大小写不敏感包含匹配', () => {
    expect(searchSkills(skills, 'PDF').map((s) => s.name)).toEqual([
      'pdf:extract',
      'pdf:pages',
    ]);
    expect(searchSkills(skills, 'word').map((s) => s.name)).toEqual(['docx:write']);
  });

  it('空白关键字返回全量；无命中返回空数组', () => {
    expect(searchSkills(skills, '   ')).toHaveLength(5);
    expect(searchSkills(skills, 'ghost')).toEqual([]);
  });
});
```

### E3-6 验证命令

```bash
pnpm run check:type
pnpm exec vitest run --config vitest.ipd.config.mts apps/web-antd/src/views/agent
pnpm run build:antd
```

人工两态验证（chrome-devtools）：① Agent 新增抽屉 → 绑定台出现在表单下方，分组渲染（`pdf`/`docx`/`fs`/`通用`）；② 搜索「pdf」只剩 pdf 组两行；③ 勾选/取消勾选 → 头部「已绑定 N 个」实时变化；④ 点击技能行 → 右栏 front-matter 预览 + 「正文预览依赖后端读接口（E3-BE-1）」标注 + 反向依赖（或诚实空态）；⑤ 编辑态保存 → `skillNames` 以绑定台为准回库（curl 详情核对）。

### E3-7 失败路径与回滚

- **skillOptions 数量增长（>200）**：左栏整体滚动 + 搜索过滤，不做虚拟滚动（零新依赖）；如渲染压力实测超阈，再立项虚拟列表（不预先加依赖）。
- **分组键派生与 skill 命名规范冲突**（如全冒号前缀爆炸）：`skillGroupKey` 单点可调（如改为双冒号段），单测随之更新，UI 零改动。
- **回滚**：删 `_shared/skill-group.ts|skill-binding-bench.vue|skill-group.test.ts`；`data.tsx` 恢复 skillNames 渲染项（含 `agentSkillOptions` import）；`agent-drawer.vue` 还原 93 行版本。值面合成是显式两行（`data.skillNames = …`），回滚无残留耦合。

---

## Task E-A1 — 带鉴权 MCP（REMOTE headers / LOCAL env）🔒 **需 ADR，未落地前 env/headers 不得出现在 UI**

> **红线（主计划约束 #20 + #19）**：今天后端 LOCAL 只读 `command`+`args`（`createStdioClient` L424-450）、REMOTE 只读 `baseUrl`（`createRemoteClient` L488-505）；`env`/`headers` 后端不读 → **E-A1 落地前 UI 一律不出现这两个输入面**（E5 表单字段集严格 = 后端读取键）。`configJson` 整体仍是 write-only（`@JsonIgnore` 不动、编辑态空态卡不变）；`env`/`headers` 若启用，也是**写进 configJson 内层**的 write-only 数据，绝不因「想预填」而触碰 `@JsonIgnore`（约束 #19）。
>
> **前置门槛**：本任务必须先过 ADR（骨架见 E-A1-0），评审通过才进入实施；前端解锁用代码常量门（B6 `generative-ui-gate` 同款模式），**默认 false**，不做任何用户可配开关（约束 #20：不造绕过后端契约的配置项）。

### E-A1-0 ADR 骨架（待评审，评审通过前本任务其余步骤冻结）

```markdown
# ADR-E-A1：带鉴权 MCP（REMOTE headers / LOCAL env）写入面

- 状态：⏳ proposed（评审通过后置 accepted，实施步骤 E-A1-BE-1..FE-1 解冻）
- 背景：REMOTE MCP 市场源逐步启用 Bearer/API-Key 鉴权；LOCAL 子进程工具需要少量
  非敏感环境变量。今天 configJson 仅 command/args/baseUrl 被消费，headers/env
  写了也不生效（幽灵配置，违背契约透明）。
- 决策（选项 B，推荐）：
  1. REMOTE：configJson 内层新增 `headers`（对象，字符串键值），经
     StreamableHttpMcpTransport.Builder.customHeaders(Map) 注入
     （langchain4j-mcp 1.17.2-beta27 javap 实证该方法存在）；
  2. LOCAL：configJson 内层新增 `env`（对象，字符串键值），经
     StdioMcpTransport.Builder.environment(Map) 叠加注入；
  3. 安全红线：`ChildProcessSecretSanitizer.isProtectedKey`（DEEPSEEK_API_KEY
     及其大小写变体）在 env 面**先拒绝后注入**（配置层直接报错，不做静默清洗）；
     emptyProviderSecretOverride() 恒最后合并（保护键空白化不可被覆盖）；
  4. write-only 语义不变：headers/env 值随 configJson 整体 write-only
     （@JsonIgnore + 导出不含 + 留空保留），UI 永不回显明文；
  5. UI 解锁用编译期常量门 AUTH_MCP_GATE.envHeaders（默认 false），无用户开关。
- 否决项 A（不启用）：维持现状，headers/env 永远不读 —— 满足安全但市场鉴权源不可用。
- 否决项 C（后端读 headers 但不读 env）：LOCAL 场景不完整，且两套字段集易漂移。
- 后果：多一个内层写入键（严格 write-only）；需回归单测钉死「保护键拒绝 +
  导出/序列化不含」两条红线。
```

### E-A1-BE-1 `ChildProcessSecretSanitizer` 增 `isProtectedKey`（补丁）

文件：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/common/process/ChildProcessSecretSanitizer.java`
（在 `emptyProviderSecretOverride()` 方法后新增，其余不动）：

```java
    /**
     * Protected-key predicate for config-supplied values (Track E-A1): user config
     * must never override provider-owned credentials, in any case variant.
     */
    public static boolean isProtectedKey(String name) {
        return name != null && DEEPSEEK_API_KEY.equalsIgnoreCase(name);
    }
```

### E-A1-BE-2 新增 `McpToolAuthConfigParser`（全码，纯静态、可直测）

文件：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/domain/dto/mcp/McpToolAuthConfigParser.java`

```java
package org.ruoyi.domain.dto.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.ruoyi.common.process.ChildProcessSecretSanitizer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MCP 连接配置内层 auth 键解析器（Track E-A1，需 ADR-E-A1 通过）。
 *
 * <p>只解析 configJson 内层的 {@code env}（LOCAL）与 {@code headers}（REMOTE）
 * 两个对象键，形态收窄为「字符串键 → 字符串标量值」。安全红线：
 * <ul>
 *   <li>{@code env} 键命中 {@link ChildProcessSecretSanitizer#isProtectedKey}
 *       直接抛错（配置层拒绝，不静默清洗、不带病运行）；</li>
 *   <li>非对象 / 非标量值 / 空键一律抛错（配置错误必须显式暴露，约束 #7）。</li>
 * </ul>
 */
public final class McpToolAuthConfigParser {

    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");

    private McpToolAuthConfigParser() {
    }

    public static Map<String, String> parseEnv(JsonNode configNode) {
        return parseObject(configNode, "env", true);
    }

    public static Map<String, String> parseHeaders(JsonNode configNode) {
        Map<String, String> headers = parseObject(configNode, "headers", false);
        for (String name : headers.keySet()) {
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid header name in config JSON: " + name);
            }
        }
        return headers;
    }

    private static Map<String, String> parseObject(JsonNode configNode, String field, boolean envSide) {
        Map<String, String> result = new LinkedHashMap<>();
        if (configNode == null || !configNode.has(field)) {
            return result;
        }
        JsonNode node = configNode.get(field);
        if (!node.isObject()) {
            throw new IllegalArgumentException("'" + field + "' must be a JSON object in config JSON");
        }
        var iterator = node.fields();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("'" + field + "' keys must be non-blank strings");
            }
            if (envSide && ChildProcessSecretSanitizer.isProtectedKey(key)) {
                throw new IllegalArgumentException(
                    "'" + field + "' key '" + key + "' is reserved and cannot be set by tool config");
            }
            if (!value.isValueNode()) {
                throw new IllegalArgumentException(
                    "'" + field + "' value for key '" + key + "' must be a scalar string");
            }
            result.put(key, value.asText());
        }
        return result;
    }
}
```

### E-A1-BE-3 `LangChain4jMcpToolProviderService` 两处消费补丁

文件：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/mcp/service/core/LangChain4jMcpToolProviderService.java`（只改两个方法内部，签名与其余逻辑不动）。

① `createStdioClient` 中「创建传输层」段替换为（import 增 `org.ruoyi.domain.dto.mcp.McpToolAuthConfigParser` 与 `java.util.LinkedHashMap`）：

```java
        // E-A1：内层 env 叠加（保护键在解析层已拒绝；保护键空白化恒最后合并）
        Map<String, String> envOverlay = new LinkedHashMap<>(McpToolAuthConfigParser.parseEnv(configNode));
        envOverlay.putAll(ChildProcessSecretSanitizer.emptyProviderSecretOverride());

        log.info("mcp_client transport=STDIO status=CREATING argumentCount={} envCount={}",
            fullCommand.size(), envOverlay.size());

        McpTransport transport = StdioMcpTransport.builder()
            .command(fullCommand)
            .environment(envOverlay)
            .logEvents(TRAFFIC_LOGGING_ENABLED)
            .build();
```

② `createRemoteClient` 中「创建 HTTP/SSE 传输层」段替换为：

```java
        // E-A1：内层 headers 注入（langchain4j-mcp 1.17.2-beta27 Builder.customHeaders(Map) 实证）
        Map<String, String> customHeaders = McpToolAuthConfigParser.parseHeaders(configNode);
        log.info("mcp_client transport=HTTP status=CREATING headerCount={}", customHeaders.size());

        McpTransport transport = StreamableHttpMcpTransport.builder()
            .url(baseUrl)
            .customHeaders(customHeaders)
            .logRequests(TRAFFIC_LOGGING_ENABLED)
            .build();
```

> 实证依据：`javap -cp ~/.m2/repository/dev/langchain4j/langchain4j-mcp/1.17.2-beta27/langchain4j-mcp-1.17.2-beta27.jar 'dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport$Builder'` 显示 `customHeaders(java.util.Map<String,String>)` 等三重载；`StdioMcpTransport$Builder.environment(Map)` 与现状一致。实施时若 pom 实际解析版本不同，先 `mvn -o -pl ruoyi-modules/ruoyi-chat dependency:tree | grep langchain4j-mcp` 复核版本；方法缺失即 🔴 停线回 ADR，不做替代猜测。

### E-A1-BE-4 回归单测 `McpToolAuthConfigParserTest.java`（`@Tag("dev")`）

文件：`ruoyi-modules/ruoyi-chat/src/test/java/org/ruoyi/domain/dto/mcp/McpToolAuthConfigParserTest.java`

```java
package org.ruoyi.domain.dto.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class McpToolAuthConfigParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void envParsesScalarsAndRejectsProtectedKeys() throws Exception {
        var node = mapper.readTree("{\"env\":{\"FOO\":\"bar\",\"N\":3}}");
        assertEquals(Map.of("FOO", "bar", "N", "3"), McpToolAuthConfigParser.parseEnv(node));

        var blocked = mapper.readTree("{\"env\":{\"deePSeeK_API_KEY\":\"sk-x\"}}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> McpToolAuthConfigParser.parseEnv(blocked));
        assertTrue(e.getMessage().contains("reserved"));
    }

    @Test
    void headersParseAndValidateNames() throws Exception {
        var node = mapper.readTree("{\"headers\":{\"Authorization\":\"Bearer t\"}}");
        assertEquals(Map.of("Authorization", "Bearer t"), McpToolAuthConfigParser.parseHeaders(node));

        var badName = mapper.readTree("{\"headers\":{\"Bad Header\":\"x\"}}");
        assertThrows(IllegalArgumentException.class, () -> McpToolAuthConfigParser.parseHeaders(badName));
    }

    @Test
    void malformedFieldsThrowInsteadOfSilentlySkipping() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> McpToolAuthConfigParser.parseEnv(mapper.readTree("{\"env\":[1]}")));
        assertThrows(IllegalArgumentException.class,
            () -> McpToolAuthConfigParser.parseHeaders(mapper.readTree("{\"headers\":{\"k\":{\"nested\":1}}}")));
        assertTrue(McpToolAuthConfigParser.parseEnv(mapper.readTree("{}")).isEmpty());
    }
}
```

后端验证：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
cd /Users/mac/Documents/ruoyi-ai
mvn -o -pl ruoyi-modules/ruoyi-chat "-Dtest=McpToolAuthConfigParserTest" test
# 与 E-Verify、E2-BE-1 同批回归（write-only 红线不因 E-A1 松动）：
mvn -o -pl ruoyi-modules/ruoyi-chat "-Dtest=McpWriteOnlyConfigRegressionTest,McpMarketMetadataRedactorTest" test
```

### E-A1-FE-1 前端解锁步骤（gate 默认 false，代码先落、行为锁死）

① NEW `apps/web-antd/src/views/mcp/_shared/auth-mcp-gate.ts`：

```ts
/**
 * 带鉴权 MCP 字段集解锁门（Track E-A1，与 B6 generative-ui-gate 同款常量门模式）。
 *
 * <p>ADR-E-A1 评审通过 + 后端 parser 落地 + 真机连通验收后，才允许把 envHeaders
 * 置 true。false 时 connection-form 不渲染 env/headers 任何输入面（约束 #20）。
 * 这是编译期常量，**不是**用户可配开关，不做绕过后端契约的配置项。
 */
export const AUTH_MCP_GATE = { envHeaders: false };
```

② `connection-config.ts` 增量（E5-① 产物上扩展；`ConnectionDraft` 增两行编辑器值面）：

```ts
import { AUTH_MCP_GATE } from './auth-mcp-gate';

/** E-A1：env/headers 行编辑器值面（键值对数组；仅 gate 开启时参与 configJson 合成）。 */
export interface KeyValueDraft {
  key: string;
  value: string;
}

// ConnectionDraft 增字段：
//   envRows: KeyValueDraft[];      // LOCAL 用
//   headerRows: KeyValueDraft[];   // REMOTE 用
// emptyConnectionDraft() 对应补两个空数组；validateConnectionDraft 增补丁：
//   仅当 AUTH_MCP_GATE.envHeaders 为 true 时校验行（键非空、env 键不等于
//   'DEEPSEEK_API_KEY'（忽略大小写））；false 时两行恒空、零校验、零产出。

/** buildConfigJson 内层合成增量（替换原 LOCAL/REMOTE 分支尾部）： */
// LOCAL 分支：config = { command, args };若 gate && envRows 有值 → config.env = rowsToRecord(envRows)
// REMOTE 分支：config = { baseUrl };若 gate && headerRows 有值 → config.headers = rowsToRecord(headerRows)

function rowsToRecord(rows: { key: string; value: string }[]): Record<string, string> {
  const record: Record<string, string> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key === '') continue;
    record[key] = row.value;
  }
  return record;
}

/** parseConfigJson 增量：gate 开启时从内层 env/headers 还原行；gate 关闭时无视两键。 */
```

> 说明：`rowsToRecord` 与行校验补丁按上述注释落在 E5 的 `connection-config.ts` 既有函数内（单一职责不拆文件）；gate=false 下 `buildConfigJson` 输出与 E5 版本逐字节一致（有 E5-⑤ 单测钉死，翻 gate 后再补两组行编辑断言）。

③ `mcp-connection-form.vue` 增 env/headers 分区（仅 gate 开启渲染；每行 key/value 输入 + 删除按钮 + 「添加一行」按钮，与 args 行编辑器同款交互；**编辑态沿用方案 a**：空态卡 + 「替换配置」后才可见行编辑，值永远不回显）。

### E-A1 验证命令与人工验收（gate 翻 true 后才有 ③④）

```bash
pnpm run check:type
pnpm exec vitest run --config vitest.ipd.config.mts apps/web-antd/src/views/mcp
pnpm run build:antd
```

① gate=false：表单无 env/headers 输入面（DOM 检索无对应 name 属性）；② 后端三组单测绿；③ 真机 REMOTE 工具带 `Authorization` header 连通（tcpdump/后端日志 headerCount=1）；④ 内层 `env` 配 `DEEPSEEK_API_KEY` 任意大小写变体 → 创建/测试报「reserved」错误。

### E-A1 失败路径与回滚

- **customHeaders 方法与实际解析版本不符**（dependency:tree 复核发现版本漂移）→ 🔴 停线回 ADR，不猜替代 API。
- **env 叠加语义与预期不符**（子进程未收到 env）→ 真机用 `args` 换 `env` 打印类工具实证 `StdioMcpTransport.environment` 的 overlay 语义（其 javadoc 已述 overlay，仍以真机为准）。
- **回滚**：后端还原 `createStdioClient`/`createRemoteClient` 两段 + 删 parser 与测试（`isProtectedKey` 可保留，无行为影响）；前端把 `AUTH_MCP_GATE.envHeaders` 置 false 即回到锁死态，分区代码保留不删（零行为 = E5 版本）。

---

## 多智能体并行编排表（Track E）

| 波次 | 任务 | 可并行对 | 写权限隔离边界（allowedPaths） | 冲突面 |
|---|---|---|---|---|
| W-E0 | E-Verify（后端） | ∥ E5（前端） | `ruoyi-modules/ruoyi-chat/src/test/java/**` | 无 |
| W-E0 | E5 结构化表单 | ∥ E-Verify | `views/mcp/tool/**`、`views/mcp/_shared/connection-config.ts|mcp-connection-form.vue`、`api/mcp/tool/model.d.ts`（如需）、`tool-drawer.vue`、`tool/data.tsx` | 无 |
| W-E0′ | E5-⑥ vitest 白名单 | **串行窗口** | `vitest.ipd.config.mts`（唯一共享门禁文件，先到先落、后到 rebase） | 高（唯一） |
| W-E1 | E1 工具台三栏 | ∥ E2 ∥ E3 | `views/mcp/tool/**`、`views/mcp/_shared/tool-*` | 与 E2 仅共享 `_shared/` 目录，文件名前缀 `tool-*`/`market-*` 零重叠 |
| W-E1 | E2 工具墙 + BE 投影 | ∥ E1 ∥ E3 | 前端 `views/mcp/market/**`、`_shared/market-*`、`metadata-tree.vue`、`api/mcp/market/model.d.ts`；后端 `domain/dto/mcp/**` + 同模块测试 | 与 E1 无文件重叠 |
| W-E1 | E3 Skill 绑定台 | ∥ E1 ∥ E2 | `views/agent/agent/**`（只读 import `views/mcp/_shared/tool-reverse-deps.ts`） | 无 |
| W-E2 | E-A1 带鉴权 MCP | **串行 + ADR 门** | 后端 `common/process/ChildProcessSecretSanitizer.java`、`mcp/service/core/LangChain4jMcpToolProviderService.java`、`domain/dto/mcp/**`；前端 `_shared/auth-mcp-gate.ts` + `connection-config.ts` 表单分区 | 与 E5 共写 `connection-config.ts` → 必须在 E5 合并后 rebase 再落 |
| 跨轨 | B ↔ E | 全程并行 | B：`views/ipd/**`、`api/ipd/**`；E：`views/mcp/**`、`views/agent/agent/**`、`api/mcp/**` | 零文件重叠；仅 `vitest.ipd.config.mts` 走串行窗口 |

## 里程碑门禁（Track E）

| 门禁 | 覆盖任务 | 通过标准 | 未过处置 |
|---|---|---|---|
| G-E0 | E-Verify | `McpWriteOnlyConfigRegressionTest` 绿：序列化 + FastExcel 导出双断言不含 `configJson`/`authConfig`/密值 | 🔴 全 Track E UI 改造冻结（先钉契约再动 UI） |
| G-E1 | E5 | 编辑态空态卡 + 「留空提交保留原值」文案 + 库内 `config_json` 非空探针 + 三件套绿 | 🔴 表单改造回滚（方案 a 是红线） |
| G-E2 | E1 | 三栏布局 + 测试三态流转 + 反向依赖断言（vitest 绿）+ 人工四态过 | 🟡 面板降级为只读结果行 |
| G-E3 | E2 | 工具墙双态（元数据缺席空态 / 在场树）+ redactor 单测绿 + 命名否决用例过 | 🟡 墙保留、元数据区锁空态 |
| G-E4 | E3 | 分组/搜索单测绿 + skillNames 合成回库核对一致 | 🟡 回退裸多选（保留分组纯函数） |
| G-E5 | E-A1 | ADR 评审通过 + parser 单测绿 + 真机 header 连通 + 保护键拒绝用例过 | gate 恒 false（UI 零出现） |

## 风险与未证明项（Track E）

| 编号 | 风险/未证明项 | 等级 | 处置 |
|---|---|---|---|
| R-E1 | `tool_metadata` 真实键集未探明，白名单可能漏展示项/误伤 | 🟡 | E2-BE-1 第 1 步 JSON_KEYS 探针先行；`DISPLAY_KEYS` 单点可调，命名否决层永不放松 |
| R-E2 | `StdioMcpTransport.environment` 的 overlay 语义（子进程实际收到 env）仅 javadoc 佐证 | 🟡 | E-A1 真机实证步骤④前置；不符即停线回 ADR |
| R-E3 | skill 分组键是前端派生约定，命名规范变化会退化成「大通用组」 | 🟡 | `skillGroupKey` 单点可调 + 单测钉死；UI 显示实际技能数，不写死 69 |
| R-E4 | E3/E5 共用的「隐藏表单项 + 自定义组件显式合成」模式下，`useBeforeCloseDiff` 的脏检查不含 bench 值（改技能未保存直接关抽屉可能不提示） | 🟡 | 已知偏差如实标注；缓解=提交前显式合成；后续可把 bench 值纳入 initializedGetter |
| R-E5 | E-A1 `customHeaders` 版本漂移 | 🟢 | javap 已实证 1.17.2-beta27 存在；实施时 dependency:tree 复核，缺失即停线 |
| U-E1 | `mcpToolTest` 的 `data` 字段真实形态未实证（model 标 any） | — | 面板按对象/标量通用展开（单测覆盖两种），形态实证后可加精确渲染 |
| U-E2 | 「69 skill」口径与 `skillOptions` 实际数量出入（仓内 skills 目录仅 3 个 + harness 内置） | — | UI 恒显示实际数量；数量口径列入验收问询清单 |
| U-E3 | `McpMarketToolListItem` 加字段对既有 JSON 断言的破坏面（实证低风险） | — | 以后端全量 `mvn -o -pl ruoyi-modules/ruoyi-chat test` 收口 |
| U-E4 | E3-BE-1（SKILL.md 正文读接口）是否立项未决 | — | 未决前预览只渲染 front-matter，不发幽灵请求 |
