# Track B — AI 工作界面（对话/画布/文档/双模式）前端任务分解（草稿 v1，2026-09-28）

> **性质**：可执行任务分解草稿。每步代码可直接落盘，无占位符（全仓规约禁 TBD/TODO 假码）。
> **执行纪律**：Global Constraints #10——所有 "Commit" 步骤一律替换为「暂存 + 输出 diff 摘要待批」；本文件只新增，不改既有文档。
>
> **事实源（只读，写码前必读）**：
> 1. 主计划 Global Constraints（21 条）+ §0.2/§0.3/§0.7：`ruoyi-ai/docs/ipd-系统说明/开发计划-AI工作界面六阶段小阶段化-20260928.md`
> 2. 单轨契约（5 条红线 + 四帧→AG-UI 对账表）：`ruoyi-ipd-web/docs/copilotkit单轨融合契约-20260928.md`
> 3. 现有代码：`views/ipd/_shared/ai-assistant.vue`（CopilotKitProvider + P3-02 放大工作界面）、`ai-cards/{copilotkit-render.ts,card-registry.ts,types.ts}`、`api/ipd/ai-copilot.ts`（四帧 SSE）、`src/packages/workflow-designer/**`（Vue Flow 封装）、`src/components/tinymce/src/editor.vue`
> 4. 颜色红线：`src/styles/ipd-tokens.css`（全局 `--ipd-*`）+ `preferences.ts theme.*` + `bootstrap.ts updatePreferences({theme:…})`（两处同改）
> 5. 21st 参照（**只作视觉参照，React/shadcn 代码禁止直入源码**）：`Agent Plan 2127`（步骤引导进度）、`MCP Tool 12382`（三态视觉，与 `CardToolStatus` 同构）

## 0. 任务总览与依赖顺序

| Task | 内容 | 依赖 | allowedPaths（写权限） |
|---|---|---|---|
| **B1** | 工作界面骨架 + 双模式状态中枢（同一状态双布局，零第二对话通道） | 无 | `views/ipd/_shared/ai-workspace/**`（新）+ `views/ipd/_shared/ai-assistant.vue`（P3-03 段补丁） |
| **B2** | 22 小阶段步骤导航 pane | **Track A 接口契约冻结**（A→B） | `api/ipd/stage-sub-stages.ts`（新）+ `ai-workspace/stage-step-nav*` |
| **B3** | Vue Flow 画布 pane | B1 | `ai-workspace/canvas-*` |
| **B4** | TinyMCE 文档 pane + word/html 导出 | B1 | `ai-workspace/doc-*` |
| **B5** | Threads 抽屉（会话归档 + CopilotKit Intelligence locked 态） | B1 | `ai-workspace/ai-thread-store.ts`、`threads-drawer.vue` + `ai-assistant.vue` 接线补丁 |
| **B6** | A2UI / Open Generative UI 启用决策门（**需 ADR，默认不启用**） | B1（可随时并行出 ADR 文稿） | `ai-workspace/generative-ui-gate.ts`（新）+ `ai-assistant.vue` Provider props 补丁（gate 关闭时零生效） |

**顺序**：B1 →（B2 ∥ B3 ∥ B4 ∥ B5）→ B6（决策门随时可审，启用落点在 B1 的 Provider）。
**并行性**：B3/B4/B5 互不相干可三路并行；B2 等 Track A 契约冻结（`GET /api/v1/ipd/stage/sub-stages` 形状）。

**测试白名单说明（重要）**：`vitest.ipd.config.mts` 的 `include` 已含
`apps/web-antd/src/views/ipd/**/*.test.ts` 与 `apps/web-antd/src/api/ipd/**/*.test.ts`。
Track B 所有新文件都在这两个域内 → **单测天然被门禁收录，无需改测试配置**；
凡纯逻辑一律放 `.ts`（`workspace-mode.ts` / `canvas-graph.ts` / `doc-export.ts` / `ai-thread-store.ts` / `stage-step-nav-model.ts`）配套同名 `.test.ts`，组件层再叠 `@vue/test-utils` mount 测试（devDeps 已有，`ai-assistant.test.ts` 有先例）。

**颜色红线落点（一句话）**：Track B 全部新 UI 取色只写 `var(--ipd-*)`（`src/styles/ipd-tokens.css` 全局已注入，`ai-workspace/**` **不再 import ipd-theme.css、不新造色板**），主色/语义色组件级唯一入口是 `preferences.ts theme.*` + `bootstrap.ts updatePreferences`（已落地，两轨只读不改），圆角 6px(控件)/8px(卡片)/4px(Tag)，**禁止** hex/rgb 字面量、antd 默认蓝 `#1677ff`、`:root{--primary:…}` 覆写、第三套色板。

**每 Task 验证三件套（仓根执行，Global Constraints #9）**：
```bash
pnpm run check:type
pnpm exec vitest run --config vitest.ipd.config.mts
pnpm run build:antd
```
> 基线现状（§0.2）：`check:type` 有 4 处存量 TS2493（`ai-assistant.test.ts`，commit `caeb167` 带入），**属存量红**；每个 Task 的验收口径 = 「vue-tsc 错误清单不新增行」（对照 `pnpm run check:type 2>&1 | grep -c TS` 前后一致），而不是盲目要求 EXIT 0。`vitest` / `build:antd` 必须 EXIT 0。

---

## B1 工作界面骨架 + 双模式状态中枢

**目标**：把 P3-02 的 `.is-workbench` 双栏升格为「AI 工作界面」——左对话（唯一对话通道，状态零复制）、右四 pane（卡片/步骤/画布/文档），并落双模式切换（经典抽屉 ⇄ AI 工作台）：**同一份 messages/cardView 状态、两种布局**，只换 CSS 布局与 pane 装配，不建第二对话通道（单轨红线 #1）。

### Files
| 路径 | 动作 |
|---|---|
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/workspace-mode.ts` | 新建（纯函数：模式/pane 归一 + localStorage 读写，可注入 storage 可测） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/use-ai-workspace.ts` | 新建（单例状态中枢：mode/pane/activeSubStageCode/doc/canvas 联动） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/ai-workspace.vue` | 新建（四 pane 装配容器，pane 内容由 B2/B3/B4 落位，B1 先给「卡片 pane + 空态」） |
| `apps/web-antd/src/views/ipd/_shared/ai-assistant.vue` | 补丁（P3-03 段：showcase 区改为 `<IpdAiWorkspace>` 装配 + 双模式切换按钮） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/workspace-mode.test.ts` | 新建（vitest） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/ai-workspace.test.ts` | 新建（@vue/test-utils mount） |

### Interfaces（产出签名，后续 Task 消费）
```ts
// workspace-mode.ts
export type WorkspaceMode = 'ai' | 'classic';           // classic=右抽屉单栏；ai=全屏工作界面
export type WorkspacePane = 'cards' | 'doc' | 'canvas' | 'steps';
export function normalizeMode(raw: null | string): WorkspaceMode;
export function normalizePane(raw: null | string): WorkspacePane;
export function loadWorkspaceMode(storage: Pick<Storage, 'getItem'>): WorkspaceMode;
export function saveWorkspaceMode(storage: Pick<Storage, 'setItem'>, mode: WorkspaceMode): void;
export function loadWorkspacePane(storage: Pick<Storage, 'getItem'>): WorkspacePane;
export function saveWorkspacePane(storage: Pick<Storage, 'setItem'>, pane: WorkspacePane): void;

// use-ai-workspace.ts（单例；ai-assistant.vue / 各 pane 共享一份状态——单轨红线 #1 的落点）
export interface IpdAiWorkspace {
  mode: Ref<WorkspaceMode>;
  pane: Ref<WorkspacePane>;
  activeSubStageCode: Ref<null | string>;   // B2 步骤导航选中项 ↔ B3 画布高亮
  docTitle: Ref<string>;
  docHtml: Ref<string>;                     // B4 文档 pane 内容（导出数据源，唯一一份）
  setMode(mode: WorkspaceMode): void;
  setPane(pane: WorkspacePane): void;
}
export function useIpdAiWorkspace(): IpdAiWorkspace;
```

### 步骤（真实代码）

**B1-① `workspace-mode.ts`（新，整文件落盘）**
```ts
/**
 * AI 工作界面双模式状态（P3-03）：classic=右抽屉单栏 / ai=全屏工作界面。
 * 纯函数 + 注入 storage（happy-dom 可测）；只持久化「布局偏好」，不持久化对话内容
 * （对话归档属 B5，互不耦合）。
 */
export type WorkspaceMode = 'ai' | 'classic';
export type WorkspacePane = 'cards' | 'doc' | 'canvas' | 'steps';

export const WORKSPACE_MODE_KEY = 'ipd:ai-workspace-mode';
export const WORKSPACE_PANE_KEY = 'ipd:ai-workspace-pane';
export const WORKSPACE_PANES: readonly WorkspacePane[] = [
  'cards',
  'steps',
  'canvas',
  'doc',
] as const;

export function normalizeMode(raw: null | string): WorkspaceMode {
  return raw === 'ai' ? 'ai' : 'classic';
}

export function normalizePane(raw: null | string): WorkspacePane {
  return (WORKSPACE_PANES as readonly string[]).includes(raw ?? '')
    ? (raw as WorkspacePane)
    : 'cards';
}

export function loadWorkspaceMode(
  storage: Pick<Storage, 'getItem'> = window.localStorage,
): WorkspaceMode {
  return normalizeMode(storage.getItem(WORKSPACE_MODE_KEY));
}

export function saveWorkspaceMode(
  storage: Pick<Storage, 'setItem'> = window.localStorage,
  mode: WorkspaceMode = 'classic',
): void {
  storage.setItem(WORKSPACE_MODE_KEY, normalizeMode(mode));
}

export function loadWorkspacePane(
  storage: Pick<Storage, 'getItem'> = window.localStorage,
): WorkspacePane {
  return normalizePane(storage.getItem(WORKSPACE_PANE_KEY));
}

export function saveWorkspacePane(
  storage: Pick<Storage, 'setItem'> = window.localStorage,
  pane: WorkspacePane = 'cards',
): void {
  storage.setItem(WORKSPACE_PANE_KEY, normalizePane(pane));
}
```

**B1-② `use-ai-workspace.ts`（新，整文件落盘）**
```ts
/**
 * AI 工作界面共享状态中枢（单例 refs）。
 * 单轨红线 #1 落点：messages/cardView 等对话状态仍**只**存在于 ai-assistant.vue，
 * 本中枢只承载「布局与展示 pane」状态（mode/pane/步骤选中/文档草稿），绝不同步第二份对话。
 */
import { ref, type Ref } from 'vue';

import {
  loadWorkspaceMode,
  loadWorkspacePane,
  saveWorkspaceMode,
  saveWorkspacePane,
  type WorkspaceMode,
  type WorkspacePane,
} from './workspace-mode';

export interface IpdAiWorkspace {
  activeSubStageCode: Ref<null | string>;
  docHtml: Ref<string>;
  docTitle: Ref<string>;
  mode: Ref<WorkspaceMode>;
  pane: Ref<WorkspacePane>;
  setMode: (mode: WorkspaceMode) => void;
  setPane: (pane: WorkspacePane) => void;
}

const mode = ref<WorkspaceMode>(loadWorkspaceMode());
const pane = ref<WorkspacePane>(loadWorkspacePane());
const activeSubStageCode = ref<null | string>(null);
const docTitle = ref('未命名文档');
const docHtml = ref('');

export function useIpdAiWorkspace(): IpdAiWorkspace {
  return {
    activeSubStageCode,
    docHtml,
    docTitle,
    mode,
    pane,
    setMode: (next: WorkspaceMode) => {
      mode.value = next;
      saveWorkspaceMode(window.localStorage, next);
    },
    setPane: (next: WorkspacePane) => {
      pane.value = next;
      saveWorkspacePane(window.localStorage, next);
    },
  };
}
```

**B1-③ `ai-workspace.vue`（新，整文件落盘；cards/steps/canvas/doc 四 pane 骨架 + 卡片 pane 真实装配，steps/canvas/doc 三 pane 由 B2/B3/B4 替换各自占位组件引用——此处给的是**当前即可运行**的空态卡，不是 TODO）**
```vue
<template>
  <div class="ipd-ai-workspace" data-testid="ipd-ai-workspace">
    <div class="ws-tabs" role="tablist">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :aria-selected="pane === tab.key"
        :class="['ws-tab', { 'is-active': pane === tab.key }]"
        :data-testid="`ipd-ai-ws-tab-${tab.key}`"
        role="tab"
        type="button"
        @click="setPane(tab.key)"
      >
        {{ tab.label }}
      </button>
    </div>
    <div class="ws-body">
      <div v-if="pane === 'cards'" class="ws-pane" data-testid="ipd-ai-ws-pane-cards">
        <slot name="cards">
          <div class="ws-empty">
            对话中产出的结构化建议卡（预审 / 结论 / 章程 / 需求草案）会在此展开。
          </div>
        </slot>
      </div>
      <div v-else-if="pane === 'steps'" class="ws-pane" data-testid="ipd-ai-ws-pane-steps">
        <slot name="steps">
          <div class="ws-empty">小阶段步骤导航由 B2 落位（消费 Track A 的 22 小阶段接口）。</div>
        </slot>
      </div>
      <div v-else-if="pane === 'canvas'" class="ws-pane" data-testid="ipd-ai-ws-pane-canvas">
        <slot name="canvas">
          <div class="ws-empty">流程画布由 B3 落位（Vue Flow）。</div>
        </slot>
      </div>
      <div v-else class="ws-pane" data-testid="ipd-ai-ws-pane-doc">
        <slot name="doc">
          <div class="ws-empty">文档编辑与 word/html 导出由 B4 落位（TinyMCE）。</div>
        </slot>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useIpdAiWorkspace } from './use-ai-workspace';

const { pane, setPane } = useIpdAiWorkspace();

const tabs = [
  { key: 'cards', label: '建议卡' },
  { key: 'steps', label: '步骤' },
  { key: 'canvas', label: '画布' },
  { key: 'doc', label: '文档' },
] as const;
</script>

<style scoped>
/* 色板：只用全局 --ipd-* token（Global Constraint #21），禁 hex */
.ipd-ai-workspace {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
  min-height: 0;
}
.ws-tabs {
  display: flex;
  gap: 6px;
  border-bottom: 1px solid var(--ipd-line);
}
.ws-tab {
  padding: 6px 14px;
  border: 0;
  border-radius: 6px 6px 0 0;
  background: transparent;
  color: var(--ipd-muted);
  font-size: 13px;
  cursor: pointer;
}
.ws-tab.is-active {
  color: var(--ipd-blue);
  box-shadow: inset 0 -2px 0 var(--ipd-blue);
}
.ws-tab:focus-visible {
  outline: var(--ipd-focus-ring-width) solid var(--ipd-focus-ring-color);
  outline-offset: var(--ipd-focus-ring-offset);
}
.ws-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.ws-pane {
  height: 100%;
}
.ws-empty {
  padding: 12px;
  border: 1px dashed var(--ipd-line);
  border-radius: 8px;
  background: var(--ipd-bg);
  color: var(--ipd-muted);
  font-size: 12px;
  line-height: 1.8;
}
</style>
```

**B1-④ `ai-assistant.vue` 补丁（P3-03 段；两处改动，锚点明确）**

改动 1 —— `<script setup>` 导入区（在 `import { copilotKitAuthHeaders, IpdAiCardRenderHost } from './ai-cards/copilotkit-render';` 之后插入）：
```ts
import IpdAiWorkspace from './ai-workspace/ai-workspace.vue';
import { useIpdAiWorkspace } from './ai-workspace/use-ai-workspace';
import type { WorkspaceMode } from './ai-workspace/workspace-mode';
```
并在 `const expanded = ref(false);` 之后接入（双模式 = 同一状态双布局，只改布局态）：
```ts
/** P3-03 双模式：mode 与 expanded 同源联动（ai=全屏工作界面 / classic=右抽屉），零第二对话通道。 */
const { mode: workspaceMode, setMode } = useIpdAiWorkspace();
function applyMode(next: WorkspaceMode) {
  setMode(next);
  expanded.value = next === 'ai';
}
```
把原 `toggleExpand` 升格（保留函数名，测试面零漂移）：
```ts
function toggleExpand() {
  applyMode(expanded.value ? 'classic' : 'ai');
}
```
改动 2 —— 模板中 `<aside class="showcase-col" data-testid="ipd-ai-showcase"> … </aside>` 整块替换为：
```vue
<aside class="showcase-col" data-testid="ipd-ai-showcase">
  <IpdAiWorkspace>
    <template #cards>
      <div
        v-if="cardView"
        class="ipd-ai-card-host"
        data-testid="ipd-ai-card-host"
      >
        <component
          :is="cardView.component"
          :data="cardView.data"
          @confirm="onCardConfirm"
        />
      </div>
      <div
        v-else-if="cardDegraded"
        class="ipd-ai-card-degraded"
        data-testid="ipd-ai-card-degraded"
      >
        {{ cardDegraded }}
      </div>
      <div
        v-else-if="cardNotice"
        class="ipd-ai-card-notice"
        data-testid="ipd-ai-card-notice"
      >
        {{ cardNotice }}
      </div>
      <div v-else class="showcase-empty" data-testid="ipd-ai-showcase-empty">
        右侧工作区：对话中产出的结构化建议卡（预审 / 结论 / 章程 /
        需求草案）会在此展开；「步骤 / 画布 / 文档」页签依次由 B2/B3/B4 落位。
      </div>
    </template>
  </IpdAiWorkspace>
</aside>
```
> 单轨红线自检：`messages`/`streamCopilot`/四帧处理/`enforceCardRenderRules` **零改动**；卡片三态（host/degraded/notice）只是移动到 `#cards` 插槽内，渲染分支与 testid 全保留（`ai-assistant.test.ts` 断言面不破）。

**B1-⑤ 测试（新，整文件落盘）**

`workspace-mode.test.ts`：
```ts
import { describe, expect, it } from 'vitest';

import {
  loadWorkspaceMode,
  loadWorkspacePane,
  normalizeMode,
  normalizePane,
  saveWorkspaceMode,
  saveWorkspacePane,
  WORKSPACE_MODE_KEY,
  WORKSPACE_PANE_KEY,
} from './workspace-mode';

function fakeStorage(initial: Record<string, string> = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
  };
}

describe('workspace-mode', () => {
  it('normalizeMode 只认 ai，其余一律 classic（防御脏值）', () => {
    expect(normalizeMode('ai')).toBe('ai');
    expect(normalizeMode('classic')).toBe('classic');
    expect(normalizeMode('AI')).toBe('classic');
    expect(normalizeMode(null)).toBe('classic');
    expect(normalizeMode('rm -rf')).toBe('classic');
  });

  it('normalizePane 白名单外回落 cards', () => {
    expect(normalizePane('steps')).toBe('steps');
    expect(normalizePane('canvas')).toBe('canvas');
    expect(normalizePane('doc')).toBe('doc');
    expect(normalizePane('evil')).toBe('cards');
    expect(normalizePane(null)).toBe('cards');
  });

  it('mode/pane 读写闭环且 key 锁定', () => {
    const storage = fakeStorage();
    saveWorkspaceMode(storage, 'ai');
    saveWorkspacePane(storage, 'doc');
    expect(loadWorkspaceMode(storage)).toBe('ai');
    expect(loadWorkspacePane(storage)).toBe('doc');
    expect(storage.getItem(WORKSPACE_MODE_KEY)).toBe('ai');
    expect(storage.getItem(WORKSPACE_PANE_KEY)).toBe('doc');
    // 坏值容错
    const dirty = fakeStorage({ [WORKSPACE_MODE_KEY]: 'x', [WORKSPACE_PANE_KEY]: 'y' });
    expect(loadWorkspaceMode(dirty)).toBe('classic');
    expect(loadWorkspacePane(dirty)).toBe('cards');
  });
});
```

`ai-workspace.test.ts`（组件层：四 tab 切换 + cards 插槽渲染 + 状态中枢联动）：
```ts
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import IpdAiWorkspace from './ai-workspace.vue';
import { useIpdAiWorkspace } from './use-ai-workspace';

describe('IpdAiWorkspace', () => {
  it('四 tab 齐备，点击切换 pane，cards 插槽承载既有卡片区', () => {
    const wrapper = mount(IpdAiWorkspace, {
      slots: { cards: '<div data-testid="card-slot-echo">卡片槽</div>' },
    });
    expect(wrapper.findAll('.ws-tab')).toHaveLength(4);
    expect(wrapper.find('[data-testid="card-slot-echo"]').exists()).toBe(true);
    wrapper.get('[data-testid="ipd-ai-ws-tab-canvas"]').trigger('click');
    expect(useIpdAiWorkspace().pane.value).toBe('canvas');
    expect(wrapper.find('[data-testid="ipd-ai-ws-pane-canvas"]').exists()).toBe(true);
    // 切回 cards，插槽内容仍在（状态不因切 tab 丢失）
    wrapper.get('[data-testid="ipd-ai-ws-tab-cards"]').trigger('click');
    expect(wrapper.find('[data-testid="card-slot-echo"]').exists()).toBe(true);
  });
});
```

### 验证命令
```bash
pnpm run check:type                       # 错误行数不新增（存量 4 处 TS2493 不算）
pnpm exec vitest run --config vitest.ipd.config.mts   # EXIT 0（新增 2 文件全绿）
pnpm run build:antd                       # EXIT 0
node scripts/check-ipd-frontend-drift.sh  # 漂移守卫 4 项全过
```

### 失败路径与回滚
- `check:type` 新增错误 → 先修类型再落盘；不得用 `any` 绕过（规约）。
- mount 测试因 pinia 报错 → 测试里 `createPinia` + `setActivePinia`（`ai-assistant.test.ts` 有同款边界处理先例），不得为此改生产代码。
- 整体回滚 = `git checkout -- apps/web-antd/src/views/ipd/_shared/ai-assistant.vue` + 删除 `ai-workspace/` 目录；localStorage 键 `ipd:ai-workspace-mode/pane` 残留无害（normalize 兜底 classic/cards）。
- **红线回滚触发器**：若发现任何测试为了过检而断言"第二对话实例存在"或复制 `streamCopilot` 通道 → 立即停手回退本 Task（单轨红线 #1/#5）。


---

## B2 22 小阶段步骤导航 pane（消费 Track A）

**目标**：AI 工作界面「步骤」pane = 六阶段 / 22 小阶段依次引导导航（参照 `Agent Plan 2127` 的进度呈现，但用 AntD Steps/Collapse 等价表达）：每小阶段显示含动作（真实 69 action_code）、深/轻、阻断标记、Gate 徽章；选中项写入 `useIpdAiWorkspace().activeSubStageCode` 供 B3 画布联动。
**依赖**：Track A 的 `GET /api/v1/ipd/stage/sub-stages` 契约冻结（A→B）；接口未就绪时**诚实降级**为 `backend-pending` 空态（禁演示假数据，Global Constraint #7——主计划 §2 拆解表是**契约基线**，但不得在前端硬编码冒充接口返回）。

### Files
| 路径 | 动作 |
|---|---|
| `apps/web-antd/src/api/ipd/stage-sub-stages.ts` | 新建（typed client，`ipdGet`） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/stage-step-nav-model.ts` | 新建（纯函数：DTO → 导航视图模型 + 校验不变量） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/stage-step-nav.vue` | 新建（导航组件） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/stage-step-nav-model.test.ts` | 新建 |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/stage-step-nav.test.ts` | 新建（mount） |

### Interfaces（消费 Track A / 产出给 B3）
```ts
// api/ipd/stage-sub-stages.ts —— 契约以 Track A 冻结为准，以下为 A→B 联调对齐面
export type StageCode = 'CONCEPT' | 'DEV' | 'KPI' | 'LAUNCH' | 'LIFECYCLE' | 'PLAN' | 'VALID';
export type SubStageGate = 'G1' | 'G2' | 'G3' | 'G4' | 'G5' | null;
export interface StageActionBrief {
  actionCode: string;        // 69 真动作之一（脏数据 A01/A02/A1/A2 不得出现，见不变量）
  actionName: string;
  depth: 'DEEP' | 'LIGHT';
  isBlocking: boolean;
  ownerRole: 'BOTH' | 'MARKET_PM' | 'RD_PM';
}
export interface SubStageDto {
  subStageCode: string;      // CONCEPT-S1 … KPI-S1（22 个）
  subStageName: string;
  stageCode: StageCode;
  sortOrder: number;
  gateCode: SubStageGate;
  actions: StageActionBrief[];
}
export interface SubStageBundle { stageCode: StageCode; stageName: string; subStages: SubStageDto[]; }
export function fetchSubStages(): Promise<SubStageBundle[]>;

// stage-step-nav-model.ts（产出，B3 canvas-graph.ts 消费同一 DTO）
export interface StepNavGroup { stageCode: StageCode; stageName: string; items: StepNavItem[]; }
export interface StepNavItem {
  subStageCode: string; subStageName: string; sortOrder: number; gateCode: SubStageGate;
  blockingCount: number; deepCount: number; lightCount: number; actionCodes: string[];
}
export function buildStepNavModel(bundles: SubStageBundle[]): StepNavGroup[];
export function verifySubStageInvariants(bundles: SubStageBundle[]): string[]; // 违规清单，空=平
```

### 步骤（真实代码）

**B2-① `api/ipd/stage-sub-stages.ts`（新，整文件）**
```ts
/**
 * 22 小阶段导航数据（Track A 契约面；A→B 联调点：路径与 DTO 形状以 Track A 冻结为准）。
 * 错峰说明：本 client 只读（GET），不新增任何写入端点（单轨红线 #4 同精神）。
 */
import { ipdGet } from './http';

export type StageCode =
  | 'CONCEPT'
  | 'DEV'
  | 'KPI'
  | 'LAUNCH'
  | 'LIFECYCLE'
  | 'PLAN'
  | 'VALID';
export type SubStageGate = 'G1' | 'G2' | 'G3' | 'G4' | 'G5' | null;

export interface StageActionBrief {
  actionCode: string;
  actionName: string;
  depth: 'DEEP' | 'LIGHT';
  isBlocking: boolean;
  ownerRole: 'BOTH' | 'MARKET_PM' | 'RD_PM';
}

export interface SubStageDto {
  actions: StageActionBrief[];
  gateCode: SubStageGate;
  sortOrder: number;
  stageCode: StageCode;
  subStageCode: string;
  subStageName: string;
}

export interface SubStageBundle {
  stageCode: StageCode;
  stageName: string;
  subStages: SubStageDto[];
}

export function fetchSubStages(): Promise<SubStageBundle[]> {
  return ipdGet<SubStageBundle[]>('/stage/sub-stages');
}
```

**B2-② `stage-step-nav-model.ts`（新，整文件）**
```ts
/**
 * 步骤导航视图模型（纯函数）：SubStageBundle[] → 22 小阶段导航分组 + 校验不变量
 * （主计划 §2.8：每 action_code 唯一归属 / sort 连续 / Gate 小阶段 sort 最大 / 脏数据排除）。
 * 不变量断言独立成 verifySubStageInvariants——数据不平只报违规清单，不阻塞渲染（诚实降级）。
 */
import type {
  StageActionBrief,
  StageCode,
  SubStageBundle,
  SubStageDto,
  SubStageGate,
} from '../../../api/ipd/stage-sub-stages';

export interface StepNavItem {
  actionCodes: string[];
  blockingCount: number;
  deepCount: number;
  gateCode: SubStageGate;
  lightCount: number;
  sortOrder: number;
  subStageCode: string;
  subStageName: string;
}

export interface StepNavGroup {
  items: StepNavItem[];
  stageCode: StageCode;
  stageName: string;
}

const DIRTY_CODES = new Set(['A01', 'A02', 'A1', 'A2']);

function toItem(dto: SubStageDto): StepNavItem {
  let blockingCount = 0;
  let deepCount = 0;
  let lightCount = 0;
  for (const action of dto.actions as StageActionBrief[]) {
    if (action.isBlocking) blockingCount += 1;
    if (action.depth === 'DEEP') deepCount += 1;
    else lightCount += 1;
  }
  return {
    actionCodes: dto.actions.map((action) => action.actionCode),
    blockingCount,
    deepCount,
    gateCode: dto.gateCode,
    lightCount,
    sortOrder: dto.sortOrder,
    subStageCode: dto.subStageCode,
    subStageName: dto.subStageName,
  };
}

export function buildStepNavModel(bundles: SubStageBundle[]): StepNavGroup[] {
  return bundles.map((bundle) => ({
    items: bundle.subStages
      .map(toItem)
      .sort((a, b) => a.sortOrder - b.sortOrder),
    stageCode: bundle.stageCode,
    stageName: bundle.stageName,
  }));
}

export function verifySubStageInvariants(bundles: SubStageBundle[]): string[] {
  const violations: string[] = [];
  const seenCodes = new Map<string, string>();
  for (const bundle of bundles) {
    const sorted = [...bundle.subStages].sort((a, b) => a.sortOrder - b.sortOrder);
    sorted.forEach((sub, index) => {
      if (sub.sortOrder !== index + 1 && sub.subStageCode !== 'KPI-S1') {
        violations.push(`${sub.subStageCode} sortOrder 不连续（${sub.sortOrder}≠${index + 1}）`);
      }
      const gateItems = bundle.subStages.filter((s) => s.gateCode !== null);
      for (const gate of gateItems) {
        const maxSort = Math.max(...bundle.subStages.map((s) => s.sortOrder));
        if (gate.sortOrder !== maxSort) {
          violations.push(`${gate.subStageCode} 承载 ${String(gate.gateCode)} 但 sort=${gate.sortOrder} 非最大`);
        }
      }
      for (const action of sub.actions) {
        if (DIRTY_CODES.has(action.actionCode)) {
          violations.push(`脏数据 ${action.actionCode} 混入 ${sub.subStageCode}`);
        }
        const owner = seenCodes.get(action.actionCode);
        if (owner) {
          violations.push(`${action.actionCode} 重复归属（${owner} 与 ${sub.subStageCode}）`);
        } else {
          seenCodes.set(action.actionCode, sub.subStageCode);
        }
      }
    });
  }
  return violations;
}
```

**B2-③ `stage-step-nav.vue`（新，整文件）**
```vue
<template>
  <div class="stage-step-nav" data-testid="ipd-ai-step-nav">
    <a-alert
      v-if="violations.length > 0"
      :message="`小阶段数据不变量违规 ${violations.length} 项：${violations[0]}`"
      data-testid="ipd-ai-step-nav-violation"
      show-icon
      type="warning"
    />
    <a-collapse v-model:activeKey="activeKeys" ghost>
      <a-collapse-panel
        v-for="group in model"
        :key="group.stageCode"
        :header="`${group.stageName}（${group.items.length} 小阶段）`"
      >
        <ol class="step-list">
          <li
            v-for="item in group.items"
            :key="item.subStageCode"
            :class="['step-item', { 'is-active': item.subStageCode === activeSubStageCode }]"
            :data-testid="`ipd-ai-step-${item.subStageCode}`"
            role="button"
            tabindex="0"
            @click="select(item.subStageCode)"
            @keyup.enter="select(item.subStageCode)"
          >
            <div class="step-head">
              <span class="step-name">{{ item.subStageName }}</span>
              <a-tag v-if="item.gateCode" color="blue" class="step-tag">{{ item.gateCode }} 门禁</a-tag>
              <a-tag v-if="item.blockingCount > 0" color="red" class="step-tag">
                阻断 {{ item.blockingCount }}
              </a-tag>
              <a-tag class="step-tag">深 {{ item.deepCount }} / 轻 {{ item.lightCount }}</a-tag>
            </div>
            <div class="step-actions">
              <a-tag v-for="code in item.actionCodes" :key="code" class="step-tag">{{ code }}</a-tag>
            </div>
          </li>
        </ol>
      </a-collapse-panel>
    </a-collapse>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';

import { Alert as AAlert, Collapse as ACollapse, CollapsePanel as ACollapsePanel, Tag as ATag } from 'ant-design-vue';

import type { SubStageBundle } from '../../../../api/ipd/stage-sub-stages';

import { buildStepNavModel, verifySubStageInvariants } from './stage-step-nav-model';
import { useIpdAiWorkspace } from './use-ai-workspace';

const props = defineProps<{ bundles: SubStageBundle[] }>();
const { activeSubStageCode, setPane } = useIpdAiWorkspace();

const model = computed(() => buildStepNavModel(props.bundles));
const violations = computed(() => verifySubStageInvariants(props.bundles));
const activeKeys = ref<string[]>(props.bundles.map((bundle) => bundle.stageCode));

function select(subStageCode: string) {
  activeSubStageCode.value = subStageCode;
  // 选中即联动画布高亮（B3 消费 activeSubStageCode），不切通道只切展示
  setPane('canvas');
}
</script>

<style scoped>
.stage-step-nav {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.step-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.step-item {
  padding: 10px 12px;
  border: 1px solid var(--ipd-line);
  border-radius: 8px;
  background: var(--ipd-surface);
  cursor: pointer;
}
.step-item.is-active {
  border-color: var(--ipd-blue);
  background: var(--ipd-blue-soft);
}
.step-item:focus-visible {
  outline: var(--ipd-focus-ring-width) solid var(--ipd-focus-ring-color);
  outline-offset: var(--ipd-focus-ring-offset);
}
.step-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.step-name {
  color: var(--ipd-text);
  font-size: 13px;
  font-weight: 600;
}
.step-tag {
  border-radius: 4px;
}
.step-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
</style>
```

**B2-④ 数据装载与降级（`ai-workspace.vue` 的 `#steps` 插槽接线，B2 落地时在 ai-assistant.vue 的 `<IpdAiWorkspace>` 内补；接口未就绪 → 诚实空态）**
```ts
// ai-assistant.vue <script setup> 追加（B2 落位步骤）：
import { fetchSubStages, type SubStageBundle } from '../../../api/ipd/stage-sub-stages';
import StageStepNav from './ai-workspace/stage-step-nav.vue';

const subStageBundles = ref<SubStageBundle[]>([]);
const subStageError = ref('');
async function loadSubStages() {
  try {
    subStageBundles.value = await fetchSubStages();
    subStageError.value = '';
  } catch (error) {
    // 诚实降级：接口未就绪/失败只出空态，不落硬编码假数据（Global Constraint #7）
    subStageBundles.value = [];
    subStageError.value = error instanceof Error ? error.message : '小阶段数据暂不可用';
  }
}
```
```vue
<template #steps>
  <StageStepNav v-if="subStageBundles.length > 0" :bundles="subStageBundles" />
  <div v-else class="showcase-empty" data-testid="ipd-ai-step-nav-empty">
    {{ subStageError || '小阶段数据加载中…' }}
  </div>
</template>
```

**B2-⑤ 测试**

`stage-step-nav-model.test.ts`（不变量 + 视图模型，夹具用主计划 §2 真实 code 面的最小两组）：
```ts
import { describe, expect, it } from 'vitest';

import type { SubStageBundle } from '../../../../api/ipd/stage-sub-stages';

import { buildStepNavModel, verifySubStageInvariants } from './stage-step-nav-model';

const bundles: SubStageBundle[] = [
  {
    stageCode: 'CONCEPT',
    stageName: '概念',
    subStages: [
      {
        subStageCode: 'CONCEPT-S1',
        subStageName: '市场洞察',
        stageCode: 'CONCEPT',
        sortOrder: 1,
        gateCode: null,
        actions: [
          { actionCode: 'C01', actionName: '市场机会与痛点调研', depth: 'DEEP', isBlocking: true, ownerRole: 'MARKET_PM' },
          { actionCode: 'C04', actionName: '区域市场准入与需求差异调研', depth: 'DEEP', isBlocking: true, ownerRole: 'MARKET_PM' },
        ],
      },
      {
        subStageCode: 'CONCEPT-S4',
        subStageName: '合规与立项',
        stageCode: 'CONCEPT',
        sortOrder: 2,
        gateCode: 'G1',
        actions: [
          { actionCode: 'C05', actionName: '技术可行性预研', depth: 'LIGHT', isBlocking: false, ownerRole: 'RD_PM' },
          { actionCode: 'C11', actionName: 'Charter立项评审会', depth: 'DEEP', isBlocking: true, ownerRole: 'BOTH' },
        ],
      },
    ],
  },
];

describe('stage-step-nav-model', () => {
  it('buildStepNavModel 排序 + 深/轻/阻断计数正确', () => {
    const model = buildStepNavModel(bundles);
    expect(model).toHaveLength(1);
    expect(model[0].items.map((item) => item.subStageCode)).toEqual(['CONCEPT-S1', 'CONCEPT-S4']);
    expect(model[0].items[0]).toMatchObject({ blockingCount: 2, deepCount: 2, lightCount: 0 });
    expect(model[0].items[1]).toMatchObject({ blockingCount: 1, deepCount: 1, lightCount: 1, gateCode: 'G1' });
  });

  it('verifySubStageInvariants：合规数据零违规；脏数据/重复归属/Gate 非末位各报违规', () => {
    expect(verifySubStageInvariants(bundles)).toEqual([]);
    const dirty = structuredClone(bundles);
    dirty[0].subStages[0].actions.push({ actionCode: 'A01', actionName: '脏', depth: 'LIGHT', isBlocking: false, ownerRole: 'RD_PM' });
    dirty[0].subStages[1].actions.push({ actionCode: 'C01', actionName: '重复', depth: 'DEEP', isBlocking: false, ownerRole: 'RD_PM' });
    dirty[0].subStages[1].gateCode = null;
    dirty[0].subStages[0].gateCode = 'G1';
    const violations = verifySubStageInvariants(dirty);
    expect(violations.some((v) => v.includes('A01'))).toBe(true);
    expect(violations.some((v) => v.includes('C01 重复归属'))).toBe(true);
    expect(violations.some((v) => v.includes('G1'))).toBe(true);
  });
});
```

`stage-step-nav.test.ts`（mount：选中联动 activeSubStageCode + 自动跳画布 pane）：
```ts
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import StageStepNav from './stage-step-nav.vue';
import { useIpdAiWorkspace } from './use-ai-workspace';

const bundles = [
  {
    stageCode: 'CONCEPT',
    stageName: '概念',
    subStages: [
      {
        subStageCode: 'CONCEPT-S1',
        subStageName: '市场洞察',
        stageCode: 'CONCEPT',
        sortOrder: 1,
        gateCode: null,
        actions: [
          { actionCode: 'C01', actionName: '市场机会与痛点调研', depth: 'DEEP', isBlocking: true, ownerRole: 'MARKET_PM' },
        ],
      },
    ],
  },
] as never;

describe('StageStepNav', () => {
  it('点击小阶段写入 activeSubStageCode 并切到画布 pane', () => {
    const wrapper = mount(StageStepNav, { props: { bundles } });
    wrapper.get('[data-testid="ipd-ai-step-CONCEPT-S1"]').trigger('click');
    const ws = useIpdAiWorkspace();
    expect(ws.activeSubStageCode.value).toBe('CONCEPT-S1');
    expect(ws.pane.value).toBe('canvas');
  });
});
```

### 验证命令
同三件套 + `pnpm exec vitest run --config vitest.ipd.config.mts -t "stage-step"` 快验；联调期与 Track A 错峰跑 G0（G0 E2E 错峰铁律：只占 15666/16039 一次，B2 独立窗口）。

### 失败路径与回滚
- Track A 接口形状与 Interfaces 不符 → **只改 `stage-sub-stages.ts` 的 DTO + model 映射两处**，组件零改；禁止在组件里散写 `any` 适配。
- 不变量报违规 → 面板顶部 warning（代码已含），同时把违规清单回传 Track A（证据由 D 轨验收），**不静默吞**。
- 回滚 = 删 `stage-step-nav*`/`stage-sub-stages.ts` + 移除 `ai-assistant.vue` 的 B2 追加段；画布 pane 回落 B1 空态。
- 风险口径：若 Track A 延期，B2 全部代码仍可落盘合并（空态可验），只是功能不可用 → 里程碑门禁标 PARTIAL 不标 done（Global Constraints #11）。

---

## B3 Vue Flow 画布 pane

**目标**：「画布」pane = 六阶段 22 小阶段流程图（节点=小阶段、边=先后依赖、Gate 节点高亮、阻断计数角标），点节点 ↔ B2 步骤导航双向联动（同一 `activeSubStageCode`，不复制状态）。技术面：`@vue-flow/core` + `@vue-flow/background`（已装，`workflow-designer` 有同款装配先例），**不复用 workflow-designer 的节点组件**（那是流程设计器语义，耦合会带进 property 面板依赖），本 pane 自带轻节点（默认节点 + label slot 由 `data` 驱动）。

### Files
| 路径 | 动作 |
|---|---|
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/canvas-graph.ts` | 新建（纯函数：StepNavGroup[] → Vue Flow Node[]/Edge[]） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/canvas-pane.vue` | 新建 |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/canvas-graph.test.ts` | 新建 |

### Interfaces
```ts
import type { Edge, Node } from '@vue-flow/core';
export interface CanvasNodeData { label: string; subStageCode: string; gateCode: string | null; blockingCount: number; }
export function buildCanvasGraph(groups: StepNavGroup[]): { edges: Edge[]; nodes: Node<CanvasNodeData>[] };
// node.id = subStageCode；node.selected 由 canvas-pane 根据 activeSubStageCode 计算（不进纯函数）
```

### 步骤（真实代码）

**B3-① `canvas-graph.ts`（新，整文件）**
```ts
/**
 * 小阶段流程图布局（纯函数）：22 小阶段 → Vue Flow nodes/edges。
 * 布局规则：阶段横向分列（x = 列号×320），小阶段纵向等距（y = 序号×110）；
 * 边 = 同阶段 sort 链 + 阶段间末位→首位链；KPI-S1（sort=99 常驻）挂右侧独立列不入链。
 */
import type { Edge, Node } from '@vue-flow/core';

import type { StepNavGroup } from './stage-step-nav-model';

export interface CanvasNodeData {
  blockingCount: number;
  gateCode: null | string;
  label: string;
  subStageCode: string;
}

const COLUMN_GAP = 320;
const ROW_GAP = 110;

export function buildCanvasGraph(groups: StepNavGroup[]): {
  edges: Edge[];
  nodes: Node<CanvasNodeData>[];
} {
  const nodes: Node<CanvasNodeData>[] = [];
  const edges: Edge[] = [];
  const chainTails: { code: string; x: number }[] = [];
  let column = 0;
  for (const group of groups) {
    if (group.stageCode === 'KPI') {
      const item = group.items[0];
      if (item) {
        nodes.push({
          id: item.subStageCode,
          position: { x: column * COLUMN_GAP, y: 0 },
          data: {
            blockingCount: item.blockingCount,
            gateCode: item.gateCode,
            label: `${group.stageName}·${item.subStageName}`,
            subStageCode: item.subStageCode,
          },
          type: 'default',
        });
      }
      continue;
    }
    const x = column * COLUMN_GAP;
    let previous: null | string = null;
    group.items.forEach((item, index) => {
      nodes.push({
        id: item.subStageCode,
        position: { x, y: index * ROW_GAP },
        data: {
          blockingCount: item.blockingCount,
          gateCode: item.gateCode,
          label: `${group.stageName}·${item.subStageName}`,
          subStageCode: item.subStageCode,
        },
        type: 'default',
      });
      if (previous) {
        edges.push({ id: `${previous}->${item.subStageCode}`, source: previous, target: item.subStageCode });
      }
      previous = item.subStageCode;
    });
    const tail = group.items.at(-1);
    if (tail) chainTails.push({ code: tail.subStageCode, x });
    column += 1;
  }
  // 阶段间跨列链：上一阶段末位 → 下一阶段首位（按 x 顺序）
  const sortedTails = chainTails.sort((a, b) => a.x - b.x);
  for (let i = 1; i < sortedTails.length; i += 1) {
    const sourceTail = sortedTails[i - 1];
    const targetHead = groups
      .filter((group) => group.stageCode !== 'KPI')
      .flatMap((group) => group.items)
      .find((item) => {
        const node = nodes.find((n) => n.id === item.subStageCode);
        return node && node.position.x === sortedTails[i].x && node.position.y === 0;
      });
    if (targetHead) {
      edges.push({
        id: `${sourceTail.code}->${targetHead.subStageCode}`,
        source: sourceTail.code,
        target: targetHead.subStageCode,
        animated: true,
      });
    }
  }
  return { edges, nodes };
}
```

**B3-② `canvas-pane.vue`（新，整文件）**
```vue
<template>
  <div class="canvas-wrap" data-testid="ipd-ai-canvas-pane">
    <VueFlow
      :edges="edges"
      :nodes="nodes"
      fit-view-on-init
      @node-click="onNodeClick"
    >
      <Background :gap="16" />
    </VueFlow>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

import { Background } from '@vue-flow/background';
import { VueFlow, type NodeMouseEvent } from '@vue-flow/core';

import type { SubStageBundle } from '../../../../api/ipd/stage-sub-stages';

import { buildCanvasGraph } from './canvas-graph';
import { buildStepNavModel } from './stage-step-nav-model';
import { useIpdAiWorkspace } from './use-ai-workspace';

const props = defineProps<{ bundles: SubStageBundle[] }>();
const { activeSubStageCode, setPane } = useIpdAiWorkspace();

const graph = computed(() => buildCanvasGraph(buildStepNavModel(props.bundles)));
const nodes = computed(() =>
  graph.value.nodes.map((node) => ({
    ...node,
    class:
      node.id === activeSubStageCode.value
        ? 'ipd-canvas-node is-active'
        : node.data.gateCode
          ? 'ipd-canvas-node is-gate'
          : 'ipd-canvas-node',
  })),
);
const edges = computed(() => graph.value.edges);

function onNodeClick(event: NodeMouseEvent) {
  activeSubStageCode.value = String(event.node.id);
  setPane('steps');
}
</script>

<style scoped>
.canvas-wrap {
  height: 100%;
  min-height: 360px;
  border: 1px solid var(--ipd-line);
  border-radius: 8px;
  background: var(--ipd-bg);
}
:deep(.ipd-canvas-node) {
  padding: 6px 10px;
  border: 1px solid var(--ipd-line);
  border-radius: 8px;
  background: var(--ipd-surface);
  color: var(--ipd-text);
  font-size: 12px;
  box-shadow: none;
}
:deep(.ipd-canvas-node.is-gate) {
  border-color: var(--ipd-blue);
}
:deep(.ipd-canvas-node.is-active) {
  border-color: var(--ipd-blue);
  background: var(--ipd-blue-soft);
}
</style>
```
> 注意：Vue Flow 的基础样式在 `workflow-designer/StandaloneWorkflowDesigner.vue` 里是文件内 `@import '@vue-flow/core/dist/style.css'`；`canvas-pane.vue` 同样在 `<style>` 段顶部补两行 `@import '@vue-flow/core/dist/style.css';` + `@import '@vue-flow/core/dist/theme-default.css';`（默认主题仅提供布局骨架，颜色被上方 `:deep` 覆盖为 `--ipd-*`，不引入 shadcn 色板）。
> 装配：`ai-assistant.vue` `<IpdAiWorkspace>` 内补 `<template #canvas><CanvasPane :bundles="subStageBundles" /></template>`（与 B2 的 bundles 同源同份）。

**B3-③ `canvas-graph.test.ts`（新，整文件）**
```ts
import { describe, expect, it } from 'vitest';

import { buildCanvasGraph } from './canvas-graph';
import type { StepNavGroup } from './stage-step-nav-model';

const groups: StepNavGroup[] = [
  {
    stageCode: 'CONCEPT',
    stageName: '概念',
    items: [
      { subStageCode: 'CONCEPT-S1', subStageName: '市场洞察', sortOrder: 1, gateCode: null, blockingCount: 1, deepCount: 1, lightCount: 0, actionCodes: ['C01'] },
      { subStageCode: 'CONCEPT-S4', subStageName: '合规与立项', sortOrder: 4, gateCode: 'G1', blockingCount: 2, deepCount: 3, lightCount: 1, actionCodes: ['C11'] },
    ],
  },
  {
    stageCode: 'PLAN',
    stageName: '计划',
    items: [
      { subStageCode: 'PLAN-S1', subStageName: '需求定义', sortOrder: 1, gateCode: null, blockingCount: 2, deepCount: 2, lightCount: 0, actionCodes: ['P01'] },
    ],
  },
  {
    stageCode: 'KPI',
    stageName: 'KPI',
    items: [
      { subStageCode: 'KPI-S1', subStageName: '共担KPI归集', sortOrder: 99, gateCode: null, blockingCount: 0, deepCount: 4, lightCount: 0, actionCodes: ['K01'] },
    ],
  },
];

describe('buildCanvasGraph', () => {
  it('节点数=小阶段数；同阶段链 + 跨阶段链齐；KPI 不入链', () => {
    const { edges, nodes } = buildCanvasGraph(groups);
    expect(nodes.map((node) => node.id)).toEqual(['CONCEPT-S1', 'CONCEPT-S4', 'PLAN-S1', 'KPI-S1']);
    const edgeIds = edges.map((edge) => edge.id);
    expect(edgeIds).toContain('CONCEPT-S1->CONCEPT-S4');
    expect(edgeIds).toContain('CONCEPT-S4->PLAN-S1');
    expect(edgeIds.filter((id) => id.includes('KPI-S1'))).toHaveLength(0);
  });

  it('Gate 数据进 node.data（供样式层渲染门禁高亮）', () => {
    const { nodes } = buildCanvasGraph(groups);
    const gate = nodes.find((node) => node.id === 'CONCEPT-S4');
    expect(gate?.data).toMatchObject({ gateCode: 'G1', blockingCount: 2, subStageCode: 'CONCEPT-S4' });
  });
});
```

### 验证命令
三件套 + 人工：`node node_modules/vite/bin/vite.js`（cwd=`apps/web-antd`，127.0.0.1:15666）打开工作界面 → 画布 22 节点可点、点节点步骤 pane 同步高亮（chrome-devtools MCP `take_snapshot` 留证）。

### 失败路径与回滚
- Vue Flow 与 happy-dom 不合（canvas 测不到）→ 保持纯函数测试为主（已含），组件层不强测渲染像素；不得为此把图逻辑塞进组件。
- 节点过多渲染卡顿 → 降级 `fitView` + `nodesDraggable=false`（一行 prop），仍不动 workflow-designer。
- 回滚 = 删 `canvas-*` 两文件 + 移除 `#canvas` 装配行。


---

## B4 TinyMCE 文档 pane + word/html 导出

**目标**：「文档」pane = TinyMCE 富文本（复用 `src/components/tinymce/src/editor.vue` 八处在用的统一封装），内容存 `useIpdAiWorkspace().docHtml`（唯一一份，导出数据源同源）；导出 **word(.doc)** 与 **html** 两个文件，**零新增依赖**（word 走 mso-HTML 包装协议，见风险 R-B2）。

### Files
| 路径 | 动作 |
|---|---|
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/doc-export.ts` | 新建（纯函数：word/html 文档构造 + 下载触发） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/doc-pane.vue` | 新建 |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/doc-export.test.ts` | 新建 |

### Interfaces
```ts
export function buildWordDocumentHtml(title: string, bodyHtml: string): string; // mso-HTML（.doc 兼容）
export function buildStandaloneHtml(title: string, bodyHtml: string): string;
export function exportWordDoc(title: string, bodyHtml: string, filename?: string): void;
export function exportHtmlDoc(title: string, bodyHtml: string, filename?: string): void;
export function downloadBlob(blob: Blob, filename: string): void; // a[download] + URL.revokeObjectURL
```

### 步骤（真实代码）

**B4-① `doc-export.ts`（新，整文件）**
```ts
/**
 * 文档导出（零新增依赖，Global Constraint #3）：
 * - html：独立完整文档原样下载；
 * - word：mso-HTML 包装协议（application/msword + Word 命名空间 + mso 条件注释），
 *   产出 .doc 可被 Word/WPS 直接打开。**不是 OOXML(.docx)**：复杂样式/图片以
 *   Base64 内嵌呈现，保真度风险见任务风险 R-B2，落盘前须 WPS/Word 实机验证。
 */
const BOM = String.fromCharCode(0xfeff);

export function buildWordDocumentHtml(title: string, bodyHtml: string): string {
  return [
    '<!DOCTYPE html>',
    '<html xmlns:o="urn:schemas-microsoft-com:office:office"',
    ' xmlns:w="urn:schemas-microsoft-com:office:word"',
    ' xmlns="http://www.w3.org/TR/REC-html40">',
    '<head><meta charset="utf-8">',
    `<title>${escapeHtml(title)}</title>`,
    '<!--[if gte mso 9]><xml><w:WordDocument><w:View>Print</w:View>',
    '<w:Zoom>100</w:Zoom></w:WordDocument></xml><![endif]-->',
    '<style>@page { size: A4; margin: 2cm; }',
    'body { font-family: "Noto Sans SC", "Microsoft YaHei", sans-serif; font-size: 12pt; }',
    'table { border-collapse: collapse; } td, th { border: 1px solid #999; padding: 4px 8px; }',
    '</style></head><body>',
    `<h1>${escapeHtml(title)}</h1>`,
    bodyHtml,
    '</body></html>',
  ].join('');
}

export function buildStandaloneHtml(title: string, bodyHtml: string): string {
  return [
    '<!DOCTYPE html>',
    '<html lang="zh-CN"><head><meta charset="utf-8">',
    `<title>${escapeHtml(title)}</title>`,
    '<style>body { max-width: 860px; margin: 2rem auto; font-family: "Noto Sans SC", sans-serif; }',
    'img { max-width: 100%; }</style></head><body>',
    `<h1>${escapeHtml(title)}</h1>`,
    bodyHtml,
    '</body></html>',
  ].join('');
}

export function escapeHtml(raw: string): string {
  return raw
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function safeFilename(title: string, fallback: string): string {
  const trimmed = title.trim().replaceAll(/[\\/:*?"<>|]/g, '-');
  return trimmed === '' ? fallback : trimmed.slice(0, 80);
}

export function exportWordDoc(title: string, bodyHtml: string, filename?: string): void {
  const html = buildWordDocumentHtml(title, bodyHtml);
  const blob = new Blob([BOM, html], { type: 'application/msword;charset=utf-8' });
  downloadBlob(blob, `${filename ?? safeFilename(title, 'IPD文档')}.doc`);
}

export function exportHtmlDoc(title: string, bodyHtml: string, filename?: string): void {
  const html = buildStandaloneHtml(title, bodyHtml);
  const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
  downloadBlob(blob, `${filename ?? safeFilename(title, 'IPD文档')}.html`);
}
```

**B4-② `doc-pane.vue`（新，整文件）**
```vue
<template>
  <div class="doc-pane" data-testid="ipd-ai-doc-pane">
    <div class="doc-toolbar">
      <a-input
        v-model:value="docTitle"
        class="doc-title"
        data-testid="ipd-ai-doc-title"
        placeholder="文档标题"
      />
      <a-button data-testid="ipd-ai-doc-export-word" @click="onExportWord">
        导出 word
      </a-button>
      <a-button data-testid="ipd-ai-doc-export-html" @click="onExportHtml">
        导出 html
      </a-button>
    </div>
    <div class="doc-editor">
      <Tinymce v-model="docHtml" :height="520" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { Button as AButton, Input as AInput, message } from 'ant-design-vue';

import { Tinymce } from '#/components/tinymce';

import { exportHtmlDoc, exportWordDoc } from './doc-export';
import { useIpdAiWorkspace } from './use-ai-workspace';

const { docHtml, docTitle } = useIpdAiWorkspace();

function onExportWord() {
  exportWordDoc(docTitle.value, docHtml.value);
  message.success('word 文档已导出');
}

function onExportHtml() {
  exportHtmlDoc(docTitle.value, docHtml.value);
  message.success('html 文档已导出');
}
</script>

<style scoped>
.doc-pane {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
}
.doc-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}
.doc-title {
  max-width: 320px;
  border-radius: 6px;
}
.doc-editor {
  flex: 1;
  min-height: 0;
}
</style>
```
> 装配：`ai-assistant.vue` `<IpdAiWorkspace>` 内补 `<template #doc><DocPane /></template>`。
> `Tinymce` 的 `modelValue` 即 `defineModel<string>('modelValue')`（`editor.vue` 实证），`v-model` 直连 `docHtml`；上传图片走封装内既有 `uploadApi`（OSS），导出时 TinyMCE 输出的 `<img src>` 是 http(s) URL——word 导出实机验证时确认 Word 能拉到图（拉不到则在导出前用 canvas 转 Base64，属 R-B2 的缓解步骤，代码位置：`doc-export.ts` 增 `inlineImages(html)`，**触发条件=实机验证失败**，不预写空壳）。

**B4-③ `doc-export.test.ts`（新，整文件）**
```ts
import { describe, expect, it } from 'vitest';

import {
  buildStandaloneHtml,
  buildWordDocumentHtml,
  escapeHtml,
} from './doc-export';

describe('doc-export', () => {
  it('word 文档含 mso 命名空间与 WordDocument 条件注释', () => {
    const html = buildWordDocumentHtml('立项报告', '<p>正文</p>');
    expect(html).toContain('xmlns:w="urn:schemas-microsoft-com:office:word"');
    expect(html).toContain('<w:WordDocument>');
    expect(html).toContain('<p>正文</p>');
    expect(html).toContain('<h1>立项报告</h1>');
  });

  it('标题经 escapeHtml 防注入（含 <script> 的标题不产生活脚本）', () => {
    const html = buildWordDocumentHtml('<script>alert(1)</script>', '<p>x</p>');
    expect(html).not.toContain('<script>alert(1)</script>');
    expect(html).toContain('&lt;script&gt;');
  });

  it('html 导出是独立完整文档', () => {
    const html = buildStandaloneHtml('文档', '<ul><li>项</li></ul>');
    expect(html.startsWith('<!DOCTYPE html>')).toBe(true);
    expect(html).toContain('<ul><li>项</li></ul>');
    expect(html.trimEnd().endsWith('</html>')).toBe(true);
    expect(escapeHtml('a&b')).toBe('a&amp;b');
  });
});
```

### 验证命令
三件套 + **实机验证（必做，R-B2 证据）**：导出 .doc 用 WPS 与 Microsoft Word 各打开一次截图存 `ZK-IPD/开发说明/UI原型-文档导出验证/`；导出 .html 浏览器打开截图。
```bash
pnpm run check:type && pnpm exec vitest run --config vitest.ipd.config.mts && pnpm run build:antd
```

### 失败路径与回滚
- Word 打开乱码 → 编码问题：确认 BOM + `charset=utf-8`（代码已含）；再不行在 `<meta>` 加 `http-equiv`，仍不行才评估 `docx` 生成（**需 ADR + 新依赖，违反 #3，除非 owner 拍板**）。
- 图片在 Word 中丢失 → 触发 `inlineImages` 缓解（B4-② 注记），不改导出协议。
- 回滚 = 删 `doc-*` 两文件 + 移除 `#doc` 装配行；`docHtml` 是内存态无残留。

---

## B5 Threads 抽屉（会话归档 + CopilotKit Intelligence locked 态）

**目标**：AI 工作界面的会话列表抽屉——新建/切换/重命名/删除/归档会话。**单轨红线自检**：会话切换只是**同一份 `messages` 状态换数据**（`ai-assistant.vue` 持有唯一状态），不是第二对话通道；`streamCopilot` 四帧通道、CopilotKit AG-UI 通道都零改动。
**分级**：
- **L1（本 Task，零后端改动）**：本地会话归档（`localStorage` 快照 + 上限裁剪），任何环境可用；
- **L2（挂起，见未证明项 U-B1）**：CopilotKit Intelligence 远端 Threads（`useThreads`/`CopilotThreadsDrawer`，license feature），无 license 时渲染 locked 态——本 Task 已把 locked 态 UI 落位，远端接线待 license/契约核实。

### Files
| 路径 | 动作 |
|---|---|
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/ai-thread-store.ts` | 新建（纯函数：快照读写/裁剪/归档键） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/threads-drawer.vue` | 新建（会话列表 + locked 态卡） |
| `apps/web-antd/src/views/ipd/_shared/ai-assistant.vue` | 补丁（归档/切换接线，标题栏加「会话」按钮） |
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/ai-thread-store.test.ts` | 新建 |

### Interfaces
```ts
export interface AiChatMessage { content: string; intent: null | string; role: 'assistant' | 'user'; sources: null | string[]; streaming: boolean; }
export interface AiThreadSummary { id: string; messageCount: number; title: string; updatedAt: number; }
export interface AiThreadSnapshot extends AiThreadSummary { messages: AiChatMessage[]; }
export const AI_THREAD_STORE_KEY = 'ipd:ai-threads';
export const AI_THREAD_LIMIT = 50;                     // 归档上限（超出丢最旧）
export function loadThreadSnapshots(storage: Pick<Storage,'getItem'>): AiThreadSnapshot[];
export function saveThreadSnapshot(storage: Pick<Storage,'getItem'|'setItem'>, snapshot: AiThreadSnapshot): AiThreadSnapshot[];
export function removeThreadSnapshot(storage: Pick<Storage,'getItem'|'setItem'>, id: string): AiThreadSnapshot[];
export function summarize(snapshot: AiThreadSnapshot): AiThreadSummary;
```

### 步骤（真实代码）

**B5-① `ai-thread-store.ts`（新，整文件）**
```ts
/**
 * 会话归档（L1 本地快照）：messages 的持久化副本，**不是第二通道**——
 * 切换会话 = ai-assistant.vue 用快照替换同一份 messages ref（单轨红线 #1）。
 * 防御：坏 JSON / 超限 / 脏字段一律安全回落，不抛错不断对话。
 */
export interface AiChatMessage {
  content: string;
  intent: null | string;
  role: 'assistant' | 'user';
  sources: null | string[];
  streaming: boolean;
}

export interface AiThreadSummary {
  id: string;
  messageCount: number;
  title: string;
  updatedAt: number;
}

export interface AiThreadSnapshot extends AiThreadSummary {
  messages: AiChatMessage[];
}

export const AI_THREAD_STORE_KEY = 'ipd:ai-threads';
export const AI_THREAD_LIMIT = 50;

function isMessage(value: unknown): value is AiChatMessage {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.content === 'string' &&
    (record.role === 'assistant' || record.role === 'user')
  );
}

function normalizeSnapshot(raw: unknown): null | AiThreadSnapshot {
  if (raw === null || typeof raw !== 'object') return null;
  const record = raw as Record<string, unknown>;
  if (typeof record.id !== 'string' || record.id === '') return null;
  const messages = Array.isArray(record.messages)
    ? record.messages.filter(isMessage).map((message) => ({
        content: message.content,
        intent: message.intent ?? null,
        role: message.role,
        sources: message.sources ?? null,
        streaming: false,
      }))
    : [];
  return {
    id: record.id,
    messageCount: messages.length,
    messages,
    title:
      typeof record.title === 'string' && record.title !== ''
        ? record.title
        : '未命名会话',
    updatedAt: typeof record.updatedAt === 'number' ? record.updatedAt : 0,
  };
}

export function loadThreadSnapshots(
  storage: Pick<Storage, 'getItem'> = window.localStorage,
): AiThreadSnapshot[] {
  const raw = storage.getItem(AI_THREAD_STORE_KEY);
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .map((item) => normalizeSnapshot(item))
      .filter((item): item is AiThreadSnapshot => item !== null)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, AI_THREAD_LIMIT);
  } catch {
    return [];
  }
}

function persist(
  storage: Pick<Storage, 'getItem' | 'setItem'>,
  snapshots: AiThreadSnapshot[],
): AiThreadSnapshot[] {
  const trimmed = [...snapshots]
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .slice(0, AI_THREAD_LIMIT);
  storage.setItem(AI_THREAD_STORE_KEY, JSON.stringify(trimmed));
  return trimmed;
}

export function saveThreadSnapshot(
  storage: Pick<Storage, 'getItem' | 'setItem'> = window.localStorage,
  snapshot: AiThreadSnapshot,
): AiThreadSnapshot[] {
  const normalized = normalizeSnapshot(snapshot);
  if (!normalized) return loadThreadSnapshots(storage);
  const rest = loadThreadSnapshots(storage).filter((item) => item.id !== normalized.id);
  return persist(storage, [normalized, ...rest]);
}

export function removeThreadSnapshot(
  storage: Pick<Storage, 'getItem' | 'setItem'> = window.localStorage,
  id: string,
): AiThreadSnapshot[] {
  return persist(
    storage,
    loadThreadSnapshots(storage).filter((item) => item.id !== id),
  );
}

export function summarize(snapshot: AiThreadSnapshot): AiThreadSummary {
  const { id, messageCount, title, updatedAt } = snapshot;
  return { id, messageCount, title, updatedAt };
}

export function createThreadId(): string {
  return `thread-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}
```

**B5-② `threads-drawer.vue`（新，整文件；locked 态 = CopilotKit Intelligence 远端 Threads 未启用）**
```vue
<template>
  <Drawer
    :open="open"
    data-testid="ipd-ai-threads-drawer"
    title="会话"
    width="380"
    @close="emit('update:open', false)"
  >
    <div class="thread-locked" data-testid="ipd-ai-threads-locked">
      云端会话（CopilotKit Intelligence）未启用：当前会话列表为本机归档，
      换设备不同步。远端启用需 license + ADR 记录（B5-L2）。
    </div>
    <a-button block data-testid="ipd-ai-thread-new" type="primary" @click="emit('new')">
      新建会话
    </a-button>
    <ul class="thread-list">
      <li
        v-for="item in items"
        :key="item.id"
        :class="['thread-item', { 'is-active': item.id === activeId }]"
        :data-testid="`ipd-ai-thread-${item.id}`"
      >
        <div class="thread-main" role="button" tabindex="0" @click="emit('select', item.id)" @keyup.enter="emit('select', item.id)">
          <div class="thread-title">{{ item.title }}</div>
          <div class="thread-meta">{{ item.messageCount }} 条 · {{ formatTime(item.updatedAt) }}</div>
        </div>
        <Popconfirm title="确认删除该会话？" @confirm="emit('remove', item.id)">
          <a-button danger size="small" type="text">删除</a-button>
        </Popconfirm>
      </li>
    </ul>
  </Drawer>
</template>

<script setup lang="ts">
import { Button as AButton, Drawer, Popconfirm } from 'ant-design-vue';

import type { AiThreadSummary } from './ai-thread-store';

defineProps<{ activeId: string; items: AiThreadSummary[]; open: boolean }>();
const emit = defineEmits<{
  (event: 'new'): void;
  (event: 'remove', id: string): void;
  (event: 'select', id: string): void;
  (event: 'update:open', value: boolean): void;
}>();

function formatTime(timestamp: number): string {
  return new Date(timestamp).toLocaleString();
}
</script>

<style scoped>
.thread-locked {
  margin-bottom: 10px;
  padding: 8px 12px;
  border: 1px dashed var(--ipd-line);
  border-radius: 8px;
  background: var(--ipd-bg);
  color: var(--ipd-muted);
  font-size: 12px;
  line-height: 1.8;
}
.thread-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}
.thread-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--ipd-line);
  border-radius: 8px;
  background: var(--ipd-surface);
}
.thread-item.is-active {
  border-color: var(--ipd-blue);
  background: var(--ipd-blue-soft);
}
.thread-main {
  flex: 1;
  cursor: pointer;
}
.thread-title {
  color: var(--ipd-text);
  font-size: 13px;
  font-weight: 600;
}
.thread-meta {
  color: var(--ipd-muted);
  font-size: 12px;
}
</style>
```

**B5-③ `ai-assistant.vue` 接线补丁（P3-04 段）**
```ts
// <script setup> 追加：
import ThreadsDrawer from './ai-workspace/threads-drawer.vue';
import {
  createThreadId,
  loadThreadSnapshots,
  removeThreadSnapshot,
  saveThreadSnapshot,
  summarize,
  type AiThreadSummary,
} from './ai-workspace/ai-thread-store';

const threadsOpen = ref(false);
const activeThreadId = ref(createThreadId());
const threadSummaries = ref<AiThreadSummary[]>(loadThreadSnapshots().map(summarize));

/** 归档当前会话快照（send() 的 finally 里调用一次；streaming 态被归一为 false）。 */
function persistCurrentThread() {
  saveThreadSnapshot(undefined, {
    id: activeThreadId.value,
    messageCount: messages.value.length,
    messages: messages.value.map((m) => ({ ...m, streaming: false })),
    title: messages.value.find((m) => m.role === 'user')?.content.slice(0, 24) || '未命名会话',
    updatedAt: Date.now(),
  });
  threadSummaries.value = loadThreadSnapshots().map(summarize);
}

function onThreadSelect(id: string) {
  const snapshot = loadThreadSnapshots().find((item) => item.id === id);
  if (!snapshot) return;
  abort?.abort();               // 切会话先断当前流（与 toggleOpen 同口径）
  activeThreadId.value = id;
  messages.value = snapshot.messages;   // 同一份状态换数据，零第二通道
  clearCardState();
  threadsOpen.value = false;
}

function onThreadNew() {
  abort?.abort();
  activeThreadId.value = createThreadId();
  messages.value = [];
  clearCardState();
  threadsOpen.value = false;
  antMessage.info('已开启新会话');
}

function onThreadRemove(id: string) {
  threadSummaries.value = removeThreadSnapshot(undefined, id).map(summarize);
  if (id === activeThreadId.value) onThreadNew();
}
```
> 精确锚点：`send()` 的 `finally` 块在 `await scrollToListBottom();` 后追加 `persistCurrentThread();`；模板 `#extra` 内「放大/还原」按钮前加
> `<button class="ipd-ai-size-btn" data-testid="ipd-ai-threads-open" aria-label="会话列表" type="button" @click="threadsOpen = true"><ListIcon :size="16" /></button>`（`ListIcon` 用 `PhList` from `@phosphor-icons/vue`，与现有图标同源）；模板末尾（`</Drawer>` 之后、`</CopilotKitProvider>` 之前）挂
> `<ThreadsDrawer v-model:open="threadsOpen" :active-id="activeThreadId" :items="threadSummaries" @new="onThreadNew" @remove="onThreadRemove" @select="onThreadSelect" />`。
> **注意**：`ThreadsDrawer` 必须放在 `CopilotKitProvider` 内（若 L2 接 `useThreads` 需要 provider context）——现锚点满足。

**B5-④ `ai-thread-store.test.ts`（新，整文件）**
```ts
import { describe, expect, it } from 'vitest';

import {
  AI_THREAD_LIMIT,
  AI_THREAD_STORE_KEY,
  createThreadId,
  loadThreadSnapshots,
  removeThreadSnapshot,
  saveThreadSnapshot,
  summarize,
  type AiThreadSnapshot,
} from './ai-thread-store';

function fakeStorage(initial: Record<string, string> = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
  };
}

function snapshot(id: string, updatedAt = 1): AiThreadSnapshot {
  return {
    id,
    messageCount: 1,
    messages: [{ content: '你好', intent: null, role: 'user', sources: null, streaming: true }],
    title: '会话',
    updatedAt,
  };
}

describe('ai-thread-store', () => {
  it('存取闭环：streaming 归一 false，按 updatedAt 倒序', () => {
    const storage = fakeStorage();
    saveThreadSnapshot(storage, snapshot('t1', 1));
    saveThreadSnapshot(storage, snapshot('t2', 2));
    const loaded = loadThreadSnapshots(storage);
    expect(loaded.map((item) => item.id)).toEqual(['t2', 't1']);
    expect(loaded[0].messages[0].streaming).toBe(false);
    expect(loaded[0].messageCount).toBe(1);
  });

  it('坏 JSON / 超限 / 删除均安全', () => {
    const dirty = fakeStorage({ [AI_THREAD_STORE_KEY]: '{oops' });
    expect(loadThreadSnapshots(dirty)).toEqual([]);
    const storage = fakeStorage();
    for (let i = 0; i < AI_THREAD_LIMIT + 5; i += 1) {
      saveThreadSnapshot(storage, snapshot(`t${i}`, i));
    }
    expect(loadThreadSnapshots(storage)).toHaveLength(AI_THREAD_LIMIT);
    const after = removeThreadSnapshot(storage, 't3');
    expect(after.some((item) => item.id === 't3')).toBe(false);
    expect(summarize(snapshot('x', 9))).toMatchObject({ id: 'x', updatedAt: 9 });
    expect(createThreadId()).toMatch(/^thread-/);
  });
});
```

### 验证命令
三件套 + 人工双会话验证（dev server 15666）：会话 A 发消息 → 新建会话 B → 切回 A 内容完整、卡片态已清、无第二个 `streamCopilot` 请求并行（Network 面板只一条 `/ai-copilot/chat/stream`）。

### 失败路径与回滚
- localStorage 配额超限（消息长文）→ `persist` 已裁剪 50 条；再爆则按 messageCount 降采样（在 `persist` 内加 `.slice(0, AI_THREAD_LIMIT)` 前按 `updatedAt` 保留——已有），报错不落盘半截 JSON（`setItem` 外层 try 由调用方 `persistCurrentThread` 兜 `try/catch` 空转）。
- 多标签页并发写覆盖 → 接受 L1 语义（后写覆盖），L2 远端化再解；不引入跨标签锁（复杂度不配收益）。
- 回滚 = 删两新文件 + 还原 `ai-assistant.vue` 三处锚点补丁；localStorage 键残留可忽略。

---

## B6 A2UI / Open Generative UI 启用决策门（需 ADR）

**结论（默认，可直接执行）**：**不启用**。契约 §3.1 已锁定"本融合不启用 A2UI"；启用属新决策，必须先 ADR。本 Task 落「决策门」代码：gate 常量 + Provider 条件接线（gate=false 时零行为变化），并出 ADR 文稿模板供 owner 裁决。两条路线的事实基线（主计划 §0.3）：
- **A2UI**：agent 发声明式 UI 操作，Vue 渲染器不拉 React 依赖；`vueBasicCatalog` 可从 `@copilotkit/vue/v2` 直接导出（**可能零新依赖**，仍需核实 `@a2ui/web_core` 是否被 `/v2` 传递引入——U-B2）；
- **Open Generative UI**：agent 生成 HTML/CSS 渲染在**无同源访问的沙箱 iframe**，host functions 显式白名单（`sandboxFunctions` 必须稳定数组）；安全面 = 生成内容任意性 + 设计漂移（生成 UI 会脱离 `--ipd-*` 体系 → 与 Global Constraint #21 直接冲突，除非 `designSkill` 硬约束 + 主题注入）。

### Files
| 路径 | 动作 |
|---|---|
| `apps/web-antd/src/views/ipd/_shared/ai-workspace/generative-ui-gate.ts` | 新建（决策门常量 + 授权读取函数） |
| `apps/web-antd/src/views/ipd/_shared/ai-assistant.vue` | 补丁（Provider 按 gate 条件传 `:a2ui`/`:open-generative-ui`，gate 关闭时 prop 不出现） |
| `ruoyi-ai/docs/ipd-系统说明/开发计划-分节草稿/ADR-B6-生成式UI启用决策.md` | 新建（ADR 文稿，owner 裁决后回填状态） |

### 步骤（真实代码）

**B6-① `generative-ui-gate.ts`（新，整文件）**
```ts
/**
 * 生成式 UI 决策门（契约 §3.1「本融合不启用 A2UI」的代码化）。
 * 开关只允许由 ADR 裁决后改本文件一处（false→true 时必须同步：
 * ① ADR 状态=已批准；② 后端 GET /copilotkit/info 响应补 a2ui 键；
 * ③ Global Constraints 增补条目）。禁止在业务代码里旁路判断。
 */
export interface GenerativeUiGate {
  /** A2UI 声明式生成 UI（ADR-B6 未批准前恒 false）。 */
  a2ui: false;
  /** Open Generative UI 沙箱 HTML（与 --ipd-* 颜色红线冲突未解前恒 false）。 */
  openGenerativeUi: false;
}

export const GENERATIVE_UI_GATE: GenerativeUiGate = {
  a2ui: false,
  openGenerativeUi: false,
};

/** Provider props 计算：gate 全 false 时返回空对象（Provider 现有三 prop 原样，零行为变化）。 */
export function generativeUiProviderProps(): {
  a2ui?: Record<string, unknown>;
  openGenerativeUi?: Record<string, unknown>;
} {
  return GENERATIVE_UI_GATE.a2ui ? { a2ui: {} } : {};
}
```

**B6-② `ai-assistant.vue` Provider 补丁（gate 关闭时与现状逐字节等价）**
```vue
<CopilotKitProvider
  v-bind="generativeUiProviderProps()"
  :enable-inspector="false"
  :headers="copilotKitAuthHeaders"
  runtime-url="/api/copilotkit"
>
```
（`<script setup>` 增 `import { generativeUiProviderProps } from './ai-workspace/generative-ui-gate';`）

**B6-③ ADR 文稿（新文件骨架，裁决栏留 owner 填——这是决策文档的合法待填，不是代码占位符）**
```markdown
# ADR-B6 生成式 UI（A2UI / Open Generative UI）启用决策
- 状态：提议（待 owner 裁决）
- 上下文：单轨契约 §3.1 锁定「本融合不启用 A2UI」；主计划 §0.3 记录六条 Generative UI 路径，
  其中 A2UI/Open Generative UI 挂在 CopilotKitProvider 的 :a2ui / :open-generative-ui。
- 决策选项：
  A（默认）维持不启用——生成式 UI 走既有 useRenderTool 4 卡体系（单轨红线 #2）；
  B 启用 A2UI（vueBasicCatalog）——需实证 @a2ui/web_core 是否零新依赖（Global Constraint #3）、
    后端 /info 补 a2ui 键、卡片体系与 A2UI 渲染并存的优先级规则（getCardType 未命中才落 A2UI？）；
  C 启用 Open Generative UI——需先解「生成 UI 脱离 --ipd-* 色板」与 Global Constraint #21 的冲突
    （designSkill 硬约束 + 沙箱 host functions 白名单评审）。
- 后果：选 B/C 必须补契约文档 §3.1 修订记录 + Global Constraints 增补，且 gate 常量同步翻转。
- 裁决：（owner 填写）
```

### 验证命令
三件套 + 断言「gate=false 时 Provider 行为零变化」：`copilotkit-render.test.ts` 既有 14 用例原样全绿（渲染层不感知 gate）；`ai-assistant.test.ts` 既有断言零改动。

### 失败路径与回滚
- 有人试图在 gate 外直接给 Provider 加 `:a2ui` → code review 一票否决（契约 §3.1 + Constraint #21 双违反）。
- 回滚 = 删 `generative-ui-gate.ts` + 还原 Provider 行（无状态残留）。

---

## 多智能体并行编排表（Track B）

| 波次 | 并行任务 | 写权限隔离（allowedPaths） | 串行窗口（须单写者） | 错峰/门禁 |
|---|---|---|---|---|
| W-B1 | B1（单会话独占） | `views/ipd/_shared/ai-workspace/**` + `ai-assistant.vue` | `ai-assistant.vue` 仅 B1/B5/B6 三补丁串行落，**同一时刻只允许一个会话改它** | 三件套 |
| W-B2 | B2 ∥ B3 ∥ B4 ∥ B5 | B2: `api/ipd/stage-sub-stages.ts` + `stage-step-nav*`；B3: `canvas-*`；B4: `doc-*`；B5: `ai-thread-store.ts`/`threads-drawer.vue` + `ai-assistant.vue` 补丁 | **`ai-assistant.vue`**（B5 接线时其他 B 任务冻结该文件）；`vitest.ipd.config.mts` B 轨**不改**（测试面已覆盖） | B2 的 G0 E2E 与 Track C/D 的 G0 **错峰**（单测试环境铁律） |
| W-B3 | B6（可与 W-B2 并行出 ADR 文稿） | `generative-ui-gate.ts` + Provider 行 | Provider 行改动与 B1 补丁合并审 | ADR 未批前 gate 恒 false |
| 跨轨 | B ↔ E 同仓并行 | B 只写 `views/ipd/**`+`api/ipd/**`；E 只写 `views/mcp/**`+`views/agent/agent/**`+`api/mcp/**`+`api/agent/**`（**零重叠**） | 共享只读：`preferences.ts`/`bootstrap.ts`（颜色入口，两轨禁改）、`styles/ipd-tokens.css`（只读消费） | 两轨各自三件套；`build:antd` 天然全量回归 |
| 跨轨请求 | B-EXT-1（可选）：`layouts/ipd.vue` 顶部全局模式切换入口 | **超出 B allowedPaths**，需主协调会话扩权或代写 | `layouts/ipd.vue` | 未获批前双模式入口以 `ai-assistant.vue` 的 fab/抽屉按钮为准 |

## 里程碑门禁（Track B）

| 门禁 | 验收 | 证据 |
|---|---|---|
| G-B1 | 双模式切换可用；`ai-assistant.test.ts` 既有断言零回归；卡片三态 testid 全保留 | 三件套输出 + vitest 全量清单 |
| G-B2 | 22 小阶段渲染 + 不变量校验 + 与画布联动；A 契约不符只改 DTO 层 | 与 Track A 联调 HTTP 录屏 + 不变量单测 |
| G-B3 | 画布节点/边/Gate 高亮/联动 | dev server 快照 |
| G-B4 | word(.doc)/html 导出，**WPS + Word 实机打开验证** | 截图存档 |
| G-B5 | 会话归档/切换/删除；Network 单通道证明 | 录屏 + 单测 |
| G-B6 | gate=false 零行为变化；ADR 裁决记录 | 既有测试清单 + ADR 状态 |

## 风险与未证明项（Track B）

**三大技术风险**：
1. **R-B2 word 导出保真度**：mso-HTML `.doc` 非 OOXML——复杂表格/图片/字体在 Word 与 WPS 间表现不一，图片 URL 异地打开可能拉不到（需 `inlineImages` Base64 化）。真 .docx 需新增依赖（docx 库）→ 违反 Global Constraint #3，除非 owner 拍板走 ADR。**门禁：G-B4 实机验证不过不许标 done**。
2. **R-B6 A2UI / Open Generative UI**：契约已锁不启用；启用 = ①契约修订 ②`@a2ui/web_core` 依赖归属核实（#3）③Open Generative UI 生成样式脱离 `--ipd-*`（#21 直接冲突）④沙箱 host functions 安全评审。任何一条不落地都不开闸。
3. **R-B5/B1 单轨回归面**：`ai-assistant.vue` 是 970 行高密度宿主（四帧 + 卡片 + P3-02 双栏），B1/B5/B6 三补丁叠加最易误伤断流/双通道。缓解：补丁锚点化（本文档给了精确锚点）、每补丁后跑 `ai-assistant.test.ts` 全量、Network 单通道人工证明。

**其他风险**：B2 阻塞于 Track A 契约（延期时以空态合并、门禁标 PARTIAL）；Vue Flow 在 happy-dom 的测试盲区（以纯函数测试补偿）。

**未证明项**：
| # | 项 | 现状 | 消解 |
|---|---|---|---|
| U-B1 | `useThreads`/`CopilotThreadsDrawer` 在 `@copilotkit/vue@1.74.0` 的真实导出名与 license 行为 | 主计划 §0.3 从官方文档镜像记录，本仓 bundle 未复核 | B5-L2 接线前 `grep` `node_modules/@copilotkit/vue/dist` 实证；无 license 走 L1（已实现） |
| U-B2 | `@a2ui/web_core` 是否被 `/v2` 传递引入（决定 A2UI 是否零新依赖） | 未实证 | B6 若选 B，先跑 `pnpm why @a2ui/web_core` |
| U-B3 | Track A `GET /stage/sub-stages` 最终形状 | 以本文档 Interfaces 为联调对齐面 | 契约冻结会签 |
| U-B4 | Word/WPS 对 mso-HTML 的真实渲染 | 未实机 | G-B4 |
| U-B5 | `Tinymce` 导出图片 URL 在 Word 的可达性 | 未实机 | G-B4（失败触发 `inlineImages`） |
