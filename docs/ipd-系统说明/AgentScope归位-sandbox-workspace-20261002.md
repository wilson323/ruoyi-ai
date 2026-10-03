# AgentScope 归位方案 · sandbox + workspace + filesystem

- 日期：2026-10-02
- 范围：官方 `sandbox` / `workspace` / `filesystem` / `FilesystemTool` / `ShellExecuteTool` 五项的归位评估
- 官方源码仓：`/Users/mac/Documents/agentscope-java`，依赖 `io.agentscope:* 2.0.3`（本仓 `pom.xml` 已钉版）
- 装配点：`AgentScopeChatKernel`（ruoyi-chat）、`AgentScopeProjectAgentKernel`（ruoyi-ipd）
- 纪律：本文件只做实读源码的评估与方案设计，**未修改任何 `src/` 文件、未改 `pom.xml`**

---

## 0. 结论摘要（先读这段）

**归位可行，且应当归位——但归位的是「隔离与执行面」，不是「治理面」。** 官方这一族 14,228 行（sandbox 5,491 + filesystem 6,434 + workspace 1,893 + 两个 Tool 410），项目侧自研平行实现 33,103 行（`service/coding/harness` 31,499 + `chat/kernel` 1,604），两族**没有一行代码重叠**——项目引用官方 `SandboxManager` / `WorkspaceManager` / `FilesystemTool` / `ShellExecuteTool` / `DockerSandbox` / `PathPolicy` / `LocalFilesystemSpec` / `RemoteFilesystemSpec` 全部为 **0 处**（实测 `rg` 全 `src/` 树，见 §1.4）。

**四个必须在落地前知道的事实纠偏：**

1. **两个装配点当前把官方文件系统整个关掉了，且不是「降级为自研」而是「文件面为零」。** `AgentScopeChatKernel:334-335` 与 `AgentScopeProjectAgentKernel:275-276` 都调了 `disableFilesystemTools()` + `disableShellTool()`。项目自研的 `BuiltinCodingTools`（9 个工具）走的是**另一条装配链**（`DefaultHarnessToolRuntimeFactory` → `HarnessToolRegistry`），**根本没进这两个 HarnessAgent 的 Toolkit**。所以「平行实现」在聊天/IPD 内核这条线上是**缺席**的，不是「替换」。
2. **自研那 33k 行并不都属于本议题。** 真正与官方 filesystem/sandbox 对位的只有 `BuiltinCodingTools`(1,196) + `ExecuteProcessTool`(599) + `DockerSandboxCommandBuilder`(137) + `DockerSandboxConfig`(337) + `CommandWorkspaceGuard`(132) + `ExecutablePolicy`(67) + `InlineProbeTool`(84) ≈ 2,552 行。其余（会话状态机、计划、审批、技能目录、run 生命周期、账本）**官方 sandbox/workspace 不覆盖，不在可删清单内**。把「42k 行对 14k 行」当成 1:1 替换是错配。
3. **官方沙箱的执行面比自研弱一大截。** 官方 `ShellExecuteTool` 是个**薄委托**（全文 99 行，无 `ProcessBuilder`、无 `"-c"` 拼接），把整条命令原样交给 `AbstractSandboxFilesystem.execute(...)`；**官方工具层根本不存在「可执行文件白名单」这一层**（不是「有白名单但可被绕过」）。自研 `ExecuteProcessTool` 是「可执行名白名单 + 禁 shell 解释器 + argv 字面量数组 + 镜像钉版 + 只读根 + 断网 + 非 root + 资源上限 + 仅工作区单挂载」。**直接换成官方 `execute` 工具是一次实打实的安全回退**（详见 §3.2）。
4. **隔离键会出现「第二收口点」。** 官方 `SessionSandboxStateStore.slotSessionId()` 内部拼 `sandbox/user/{agentId}/{value}`，`WorkspaceManager.resolveRuntimeDataPath()` 内部经 `NamespaceFactory` 拼命名空间段。归位后若不设拦截点，`KernelScopeKey` 的「四维唯一收口」纪律当场破功。**具体做法见 §4，不是「注意」两个字。**

**归位建议（分三档，非全有全无）：**

| 档 | 范围 | 建议 | 理由 |
|---|---|---|---|
| **P0 必做** | `LocalFilesystemSpec`（本机 + shell 模式）替换自研 `BuiltinCodingTools` 的文件工具 | ✅ 做 | 官方 `LocalFsMode.ROOTED` + `PathPolicy` 的路径面比自研 `CommandWorkspaceGuard` 更完整（含符号链接、Windows 盘符、ROOTED 三根模型），且**能拿到官方那份后续升级路径** |
| **P1 建议做** | `WorkspaceManager` 归位（工作区上下文注入 + 命名空间） | ✅ 做 | 官方 workspace 是「多租户覆盖目录」的唯一正解，自研完全没有这一层；`AGENTS.md` / `MEMORY.md` 两层读也是官方独有 |
| **P2 谨慎** | `DockerFilesystemSpec` 沙箱模式替换自研 Docker 执行面 | ⚠️ 条件做 | 官方沙箱**执行校验层缺失**（见 §3.2），必须**在官方工具层之外从零自建**一道可执行文件白名单才可切换，否则是安全回退 |

> **口径纠正（2026-10-02 主会话复核）**：早前版本把官方 `execute` 描述为「有白名单但可被 `sh -c` 绕过」，**这是错的**。源码实读：`ShellExecuteTool`（99 行）内无 `ProcessBuilder`、无 `Runtime.exec`、无 `"-c"` 拼接，是纯薄委托；`sh -c` 出现在**沙箱实现层**（`DockerSandbox.doExec` 拼 `docker exec -w <root> <id> sh -c <command>`，见 `impl/docker/DockerSandbox.java:150-160`），**不在工具层**。正确表述是：**官方工具层不存在可执行白名单这一校验层**，因此不存在「绕过」问题，而是**整层缺失**。这个区别有实际工程后果：不是「检查沙箱有没有正确转义 argv」，而是**必须自建一整层拦截**。

---

## 1. 官方 API 面实证

> 口径遵循本项目 `agentscope-harness` skill：**「已实证」= 源码中真实存在并已逐行读过；「仅文档提及」= 只在 docs 中出现、未在代码中验证。**

### 1.1 已实证 · sandbox

#### `SandboxManager`（`.../harness/agent/sandbox/SandboxManager.java`，270 行）

```java
public SandboxManager(SandboxClient<?> client, SessionSandboxStateStore stateStore, String agentId)
public SandboxManager(SandboxClient<?> client, SessionSandboxStateStore stateStore, String agentId,
                      SandboxExecutionGuard executionGuard)   // null guard → noop()
public SandboxAcquireResult acquire(SandboxContext sandboxContext, RuntimeContext runtimeContext) throws Exception
public void release(SandboxAcquireResult result)
public void persistState(SandboxAcquireResult result, SandboxContext sandboxContext, RuntimeContext runtimeContext)
public void clearState(SandboxContext sandboxContext, RuntimeContext runtimeContext)
```

`acquire` 的四级优先级（源码 L69-155 逐行读过）：

| 级 | 条件 | 行为 | 是否过 guard |
|---|---|---|---|
| 1 | `sandboxContext.getExternalSandbox() != null` | 直接用调用方的沙箱 | ❌ 绕过 |
| 2 | `sandboxContext.getExternalSandboxState() != null` | `client.resume(state)` | ❌ 绕过 |
| 3 | `stateStore.load(scopeKey)` 有值 | 反序列化 state → `client.resume(state)` | ✅ |
| 4 | 兜底 | `client.create(spec, snapshotSpec, options)` | ✅ |

- guard 是在**优先级 3/4 之前**进入的（`executionGuard.tryEnter(scopeKey.get())`），lease 挂在 `SandboxAcquireResult` 上，由调用方（`SandboxLifecycleMiddleware`）在 `release` 之后关闭 → 覆盖完整 call 窗口。
- `acquire` 抛异常时先 `lease.close()` 再抛（源码 L150-154），无泄漏。
- resume 失败降级到 fresh create 时，`carryOverPersistedSnapshotId`（L165-182）把旧 snapshotId 搬进新沙箱，**工作区不清零**。

#### `SandboxIsolationKey`（`sandbox/SandboxIsolationKey.java`，140 行，final）

```java
public static Optional<SandboxIsolationKey> resolve(IsolationScope scope, RuntimeContext ctx, String agentId)
public IsolationScope getScope()
public String getValue()          // equals/hashCode/toString 齐全
```

解析规则（源码 L67-104 逐行读过）：

| scope | value | 缺字段时 |
|---|---|---|
| `SESSION` | `sessionId` | 缺 → `Optional.empty()`（跳过 state 查找） |
| `USER` | `userId` | 缺 userId → **降级 SESSION** 用 sessionId；两者都缺 → `empty()` + `log.warn` |
| `AGENT` | `agentId`（`Objects.requireNonNull`） | 不会降级 |
| `GLOBAL` | 常量 `__global__` | 不会降级 |
| `null` | 按 `USER` 处理 | — |

> ⚠️ 关键：`USER` 降级到 `SESSION` 时**不抛异常、不 fail-closed**，只 `log.debug`。这与项目 `KernelScopeKey` 的「空 userId → `__anon__` 命名空间」语义**不等价**（官方是塌缩到 session 维度，项目是塌缩到固定匿名桶）。见 §3.3。

#### `SandboxExecutionGuard`（`sandbox/SandboxExecutionGuard.java`，94 行，`@FunctionalInterface`）

```java
SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException;   // 唯一抽象方法
static SandboxExecutionGuard noop();                                            // 内置默认，无限制
final class NoopSandboxExecutionGuard implements SandboxExecutionGuard         // 单例
```

- 语义是**执行槽位互斥**，不是权限裁决：只回答「同一 slot 现在能不能进」，不回答「这个工具该不该被调用」。
- 生命周期契约（javadoc L52-57）：`acquire → start → (call) → stop → release → lease.close()`。
- **内置实现**（已实证文件存在）：
  - `RedisSandboxExecutionGuard`（`agentscope-extensions-redis/.../sandbox/RedisSandboxExecutionGuard.java`）—— `builder(UnifiedJedis)` + `leaseTtl` / `keyPrefix` / `retryIntervalMs`，底层 `SET NX PX`。
  - `JdbcSandboxExecutionGuard`（`agentscope-extensions-jdbc` 与 `agentscope-extensions-mysql` **各有一份**）—— 文档称 MySQL `GET_LOCK()`。

#### `Sandbox` / `SandboxClient`（SPI）

```java
public interface Sandbox extends AutoCloseable {
    void start() throws Exception;  void stop() throws Exception;
    default void shutdown() throws Exception;  void close() throws Exception;
    boolean isRunning();  SandboxState getState();
    ExecResult exec(RuntimeContext rc, String command, Integer timeoutSeconds);
    InputStream persistWorkspace() throws Exception;   void hydrateWorkspace(InputStream archive) throws Exception;
}
public interface SandboxClient<O extends SandboxClientOptions> {
    Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec, O options);
    Sandbox resume(SandboxState state);   void delete(Sandbox sandbox);
    String serializeState(SandboxState state);   SandboxState deserializeState(String json);
    default SandboxState deserializeState(String json, SandboxSnapshotSpec snapshotSpec) { … }
}
```

`WorkspaceSpec`：`getRoot/setRoot`、`getEntries/setEntries`（`Map<String, WorkspaceEntry>`）、`getEnvironment/setEnvironment`、`copy()`。`WorkspaceEntry` 的 `@JsonSubTypes` 已实证 7 种：`FileEntry` / `DirEntry` / `LocalFileEntry` / `LocalDirEntry` / `GitRepoEntry` / `WorkspaceProjectionEntry` / `BindMountEntry`（仅 Docker 支持 bind mount）。

快照：`SandboxSnapshotSpec.build(String snapshotId)` 一个方法；实现已实证 `NoopSnapshotSpec`（默认）/ `LocalSnapshotSpec` / `RemoteSnapshotSpec` / `RedisSnapshotSpec` / `OssSnapshotSpec` / `JdbcSnapshotSpec`（jdbc + mysql 各一）/ `PostgresSnapshotSpec`。

### 1.2 已实证 · workspace

#### `WorkspaceManager`（`.../harness/agent/workspace/WorkspaceManager.java`，1,026 行）

四个构造（`WorkspaceManager(Path)` / `(Path, AbstractFilesystem)` / `(Path, AbstractFilesystem, WorkspaceIndex)` / 四参全量），关键公开方法：

| 方法 | 作用 |
|---|---|
| `getNamespaceFactory()` / `getIndex()` / `getFilesystem()` | 依赖暴露 |
| `validate()` / `close()` / `getWorkspace()` | 生命周期 |
| **`resolveRuntimeDataPath(RuntimeContext rc, String relativePath)`** | **拼命名空间前缀**（L239-249）—— 隔离键第二收口点，见 §4 |
| `readAgentsMd(rc)` / `readKnowledgeMd(rc)` / `readMemoryMd(rc)` | 三份关键文件，均走 `readWithOverride` 两层读 |
| `readManagedWorkspaceFileUtf8(rc, relativePath)` | 任意相对路径两层读，**内含 `resolved.startsWith(workspace)` 校验**（L277-280） |
| `writeUtf8WorkspaceRelative(rc, rel, content)` | 写入永远走 filesystem 层 |
| `writeDraftSkillFile` / `moveSkill` | 自学习技能落档 |
| `getMemoryDir(rc)` / `getSkillsDir()` / `getKnowledgeDir()` / `listKnowledgeFiles(rc)` | 目录解析 |
| `resolveSessionFile` / `resolveSessionContextFile` / `resolveSessionLogFile` / `appendUtf8WorkspaceRelative` / `updateSessionIndex` | 会话日志 |
| `writeTaskRecord` / `readTaskRecord` / `listTaskRecords` / `listAllTaskRecords` | 子 agent 后台任务 |
| `listMemoryFilePaths(rc)` / `listSessionLogFiles()` / `toWorkspaceRelativeString(Path)` | 工具侧 |
| `static String normalizeRelativePath(String)` | 包级静态，归一化 |

**两层读**（已实证，javadoc L196-205 + `readManagedWorkspaceFileUtf8` 实现）：先问 `AbstractFilesystem` 有没有该相对路径 → 有则返回（覆盖层）；没有则读 `workspace.resolve(relPath)` 本地磁盘。**写入永远走第 1 层。**

`WorkspaceConstants`（已实证）：`DEFAULT_WORKSPACE_ROOT = ".agentscope/workspace"`、`AGENTS_MD` / `MEMORY_MD` / `TOOLS_JSON` / `MEMORY_DIR` / `SKILLS_DIR` / `KNOWLEDGE_DIR` / `KNOWLEDGE_MD` / `RULES_DIR` / `AGENTS_DIR` / `SESSIONS_DIR` / `TASKS_DIR` / `SESSIONS_STORE = "sessions.json"` / `SESSION_CONTEXT_EXT = ".jsonl"` / `SESSION_LOG_EXT = ".log.jsonl"`。

#### `PathPolicy`（`workspace/PathPolicy.java`，126 行，final，不可变）

```java
public static PathPolicy empty()
public static PathPolicy of(Path first, Path... rest)
public static PathPolicy of(Collection<Path> roots)
public List<Path> roots();  public boolean isEmpty()
public boolean isAllowed(Path candidate)     // candidate 必须 absolute，否则 false
```

`isAllowed` 实现是 `candidate.normalize()` 后 `normalized.startsWith(root)` 逐根比对（L99-110）。**不检查符号链接**——防 symlink 逃逸靠调用方 `LocalFilesystem` 另有逻辑。

#### `WorkspacePathNormalizer`（164 行，final）

```java
public static WorkspacePathNormalizer of(String workspacePrefix)
public static WorkspacePathNormalizer of(String... workspacePrefixes)
public static WorkspacePathNormalizer of(String workspacePrefix, NamespaceFactory namespaceFactory)
public String normalize(String path)                            // @Deprecated
public String normalize(String path, RuntimeContext rc)          // 实际入口
```

`normalize(String, RuntimeContext)` 先试「带命名空间的 prefix」再试裸 prefix（L119-141）。**这是官方唯一把「命名空间」和「绝对路径」缝合的点**——归位后模型若拿到带命名空间的绝对路径会被正确归一。

#### `LocalFsMode`（41 行，enum）

`SANDBOXED`（一切锚定根、拒 `..` 与绝对路径）/ `ROOTED`（**默认**，绝对路径只允许落在 `workspace + project + additionalRoots` 三根之下）/ `UNRESTRICTED`（仅测试与完全信任环境）。

#### `WorkspaceIndex`（372 行）/ `plan/PlanModeManager`

`WorkspaceIndex` 是给 `RemoteFilesystemSpec` 加速远端 ls/glob/grep 的 SQLite 索引（`RemoteFilesystemSpec.workspaceIndex(WorkspaceIndex)`）。`PlanModeManager` 属 Plan Mode 子系统，**与本议题无关**（2026-10-02 晚起项目智能体内核已启用 Plan Mode、聊天内核仍未启用；Plan Mode 状态经 AgentStateStore 按 (userId, sessionId) 隔离，不触及本议题的沙箱/workspace 语义）。

### 1.3 已实证 · filesystem 与官方工具

#### `FilesystemTool`（`.../harness/agent/tool/FilesystemTool.java`，311 行）

```java
public FilesystemTool(AbstractFilesystem abstractFilesystem)
public FilesystemTool(AbstractFilesystem abstractFilesystem, WorkspacePathNormalizer pathNormalizer)
```

注册的 6 个 `@Tool`（已实证注解 `name` 与参数）：

| 工具名 | 参数 | 常量 |
|---|---|---|
| `read_file` | `path`, `offset`, `limit` | — |
| `write_file` | `path`, `content` | — |
| `edit_file` | `path`, `old_string`, `new_string`, `replace_all` | — |
| `grep_files` | `pattern`（**字面量文本**）, `path`, `glob`, `limit` | `DEFAULT_GREP_LIMIT=100`, `MAX_SEARCH_LIMIT=1_000` |
| `glob_files` | `pattern`, `path`, `limit` | `DEFAULT_GLOB_LIMIT=200` |
| `list_files` | `path` | `MAX_LISTING_ENTRIES=200`, `MAX_LISTING_CHARS=16_000` |

> 注意：官方 `grep_files` 的 `pattern` 描述明写 **"Literal text pattern to search for"**（L187），**不是正则**。项目自研 `search_text` 有 `regex` 布尔参数走 ripgrep。**这是能力回退，不是增强。**

**官方 `FilesystemTool` 没有任何写入前置条件**：没有 `expectedSha256`、没有原子替换、没有 symlink 跟随策略声明、没有 `.gitignore` 感知的入口。项目 `write_file` 强制 `expectedSha256`（`BuiltinCodingTools.java:292`），`replace_text` 同样（L321），且是「先算 SHA → 校验 → 原子落盘」。**这也是安全回退点。**

#### `ShellExecuteTool`（`.../harness/agent/tool/ShellExecuteTool.java`，99 行）

```java
public static final String NAME = "execute";
public ShellExecuteTool(AbstractSandboxFilesystem sandbox)
public String execute(RuntimeContext rc,
                      @ToolParam("command") String command,
                      @ToolParam("working_directory", required=false) String workingDirectory,
                      @ToolParam("timeout", required=false) Integer timeout)
static String commandWithWorkingDirectory(String workingDirectory, String command, boolean windows)
```

- 超时默认 **30 秒**（L78）。
- `working_directory` 校验：拒 `/` 开头、拒 `~`、拒含 `..`（L67-70）——**纯字符串启发式，不查文件系统**。
- 拼命令：`"cd '" + wd.replace("'", "'\\''") + "' && " + command`（L97），Windows 分支用 `cd /d "..."`。
- **该类全文 99 行，无 `ProcessBuilder`、无 `Runtime.getRuntime().exec()`、无 `"-c"` 字符串拼接**——它是**薄委托**，整条命令原样交给 `AbstractSandboxFilesystem.execute(rc, command, timeout)`（`filesystem/sandbox/AbstractSandboxFilesystem.java:48`，参数 javadoc 明写 `@param command full shell command string to execute`）。
- 因此：**官方工具层不存在可执行文件白名单、不存在 shell 解释器禁令、不存在资源上限、不存在网络策略**。`sh -c` 的实际拼接发生在沙箱实现层（`DockerSandbox.doExec`：`docker exec -w <workspaceRoot> <containerId> sh -c <command>`，`impl/docker/DockerSandbox.java:150-160`），**属于沙箱后端语义，不是工具层能力**。
- **落地含义**：要保住 R1/R2（见 §3.2），不能靠「检查官方沙箱有没有正确转义 argv」——**官方根本没有这一层，必须在官方工具层之外自建一整道可执行文件白名单拦截**。工程量与「等沙箱就位再开」是完全不同的两套。

#### 文件系统实现族（均已实证文件存在）

- `AbstractFilesystem`（抽象基类）、`OverlayFilesystem`（本机双层）、`CompositeFilesystem`（共享存储路由）、`ProjectAwareOverlay`（`projectWritable(true)` 时写落项目目录）、`RoutedSandboxFilesystem`、`BakedContextFilesystem`。
- `local/LocalFilesystem`（两种路径解析模式）、`local/LocalFilesystemWithShell`。
- `sandbox/{AbstractSandboxFilesystem, BaseSandboxFilesystem, PinnedSandboxFilesystem, SandboxBackedFilesystem}`。
- `remote/RemoteFilesystem` + `remote/store/{BaseStore, InMemoryStore, NamespaceFactory, StoreItem}`。
- `spec/{LocalFilesystemSpec, RemoteFilesystemSpec, SandboxFilesystemSpec}`。

`LocalFilesystem` 的路径裁决（已实证，L609-653）：

- `resolveSandboxed`：拒含 `..` 或以 `~` 开头 → `SecurityException`；`normalize()` 后必须 `startsWith(cwd)`。另处理 Windows 盘符剥离（`stripWindowsDrive`）。
- `resolveRooted`：先 `AbstractFilesystem.validatePath(key)`；绝对路径则 `startsWith(cwd) || pathPolicy.isAllowed(normalized)` 才放行；不存在且非 `/` 开头 → `rootAccessDenied`。

#### `LocalFilesystemSpec` / `SandboxFilesystemSpec` / `RemoteFilesystemSpec`

`LocalFilesystemSpec`（已实证全部 Builder 方法）：`executeTimeoutSeconds(int)`（默认 120）、`maxOutputBytes(int)`（默认 100_000）、`env(String,String)`、`inheritEnv(boolean)`（默认 false）、`virtualMode(boolean)`、`mode(LocalFsMode)`、`isolationScope(IsolationScope)`、`addRoot(Path)`、`additionalRoots(Collection)`、`project(Path)`、`projectWritable(boolean)`、`sharedLocalWorkspace(boolean)`，以及终局 `AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory localNamespaceFactory)`——后者在源码里**自建 `PathPolicy.of(project, workspace, additionalRoots)` 三根**，并组装 `LocalFilesystemWithShell`(上层) + `LocalFilesystem`(下层)，`projectWritable` 时再叠一层 `ProjectAwareOverlay`。

`SandboxFilesystemSpec` 公共部分：`isolationScope` / `snapshotSpec` / `executionGuard` / `workspaceProjectionEnabled(boolean)`（默认 true）/ `workspaceProjectionRoots(List<String>)`（默认 `AGENTS.md`, `skills`, `subagents`, `knowledge`, `.skills-cache`）。`DockerFilesystemSpec` 另有 `client` / `image` / `workspaceRoot`（默认 `/workspace`）/ `environment` / `memorySizeBytes` / `cpuCount` / `exposedPorts` / `network` / `additionalRunArgs` / `workspaceSpec`。

#### `HarnessAgent.Builder` 相关开关（均已实证，源码 `HarnessAgent.java`）

| Builder 方法 | 行号 | 作用 | 本项目是否调用 |
|---|---|---|---|
| `workspace(Path)` / `workspace(String)` | 1807 / 1821 | 显式工作区，覆盖系统属性/环境变量/默认值 | ✅ **两个内核都调了** |
| `filesystem(SandboxFilesystemSpec)` | 1846 | 沙箱模式 | ❌ 未调 |
| `filesystem(RemoteFilesystemSpec)` | 1852 | 共享存储模式 | ❌ 未调 |
| `filesystem(LocalFilesystemSpec)` | 1858 | 本机 + shell 模式 | ❌ 未调 |
| `abstractFilesystem(AbstractFilesystem)` | 1840 | 完全自管（与 `filesystem(...)` **互斥**） | ❌ 未调 |
| `filesystemRoute(String prefix, AbstractFilesystem)` | 1875 | 路径前缀路由到不同 filesystem | ❌ 未调 |
| `disableFilesystemTools()` | 2075 | **跳过 `FilesystemTool` 注册** | ✅ **两个内核都调了** |
| `disableShellTool()` | 2081 | **跳过 `ShellExecuteTool` 注册** | ✅ **两个内核都调了** |
| `disableWebTools()` | 2087 | 跳过 web_search / web_fetch | ❌ 未调（改用 `toolsConfig.deny`） |
| `stateStore(AgentStateStore)` | 1644 | 状态存储 | ✅ 都调了 |
| `distributedStore(DistributedStore)` | 1662 | 一键注入 stateStore + baseStore + snapshotSpec + **executionGuard** + sessionTurnGate | ❌ 未调 |
| `toolsConfig(ToolsConfig)` | 1930 | allow/deny + MCP | ✅ 都调了（仅 deny 三项） |
| `artifactDeliveryTarget(ArtifactDeliveryTarget)` | 2069 | 沙箱产物外送；**javadoc 明写：该工具依赖 agent filesystem，`disableFilesystemTools()` 时一并被抑制** | ❌ 未调 |
| `toolExecutionContext(ToolExecutionContext)` | 1672 | 工具执行上下文 | ❌ 未调 |
| `permissionContext(PermissionContextState)` | 1737 | 权限三态上下文 | ❌ 未调（改用工具级 `checkPermissions`） |
| `disableWorkspaceContext()` | 2288 | 关闭 system prompt 注入 | ❌ 未调 |
| `getWorkspaceManager()` | 246 | 取 workspace 门面 | ❌ 未调 |

> **`distributedStore(...)` 是本方案的关键杠杆**：它一个调用就同时注入 `agentStateStore` / `baseStore` / `sandboxSnapshotSpec` / `sandboxExecutionGuard` / `sessionTurnGate`。项目已经在用 Redisson 和 `agentscope-extensions-redis`，改接 `RedisDistributedStore` 的边际成本很低（见 §5）。

### 1.4 仅文档提及（未在源码逐行验证）

以下只在 `docs/v2/zh/docs/harness/{sandbox,workspace,filesystem}.md` 中出现，本方案**未在代码中验证**，落地前须复核：

- `Sandbox` 的 **Kubernetes / agent-sandbox / PVC / WarmPool** 全部机制（`SandboxClaim`、HTTP 文件 API 契约、`/execute` 需 POSIX shell 语义等）。扩展模块 `agentscope-extensions-sandbox-kubernetes` 的文件**存在**，但内部实现未读。
- `Daytona` / `E2B` / `AgentRun`（阿里云 FC）后端。Spec 文件存在，内部实现未读。
- `WorkspaceArchiveExtractor` / `WorkspaceProjectionApplier` / `WorkspaceSpecApplier` / `WorkspaceMountSupport` 的完整行为（文件存在，未逐行读）。
- `IsMemoryEnabled` 之外的**记忆/技能/计划子系统**在本议题的联动影响（`MEMORY.md` 注入、`skills/` 投影）。项目两内核都关了 `disableMemoryTools` + `disableMemoryHooks`，归位 workspace 时若不同步关 `disableWorkspaceContext`，`AGENTS.md` 会开始注入——**这是一个当前未预期的行为变更点**。
- `AbstractFilesystem.validatePath(String)` 的完整规则（只在 `LocalFilesystem.resolveRooted` 中被调用，未读其定义）。

### 1.5 项目侧引用数实测

```
SandboxManager=0  WorkspaceManager=0  FilesystemTool=0  ShellExecuteTool=0
DockerSandbox=0   DockerFilesystemSpec=0  LocalFilesystemSpec=0  RemoteFilesystemSpec=0
SandboxExecutionGuard=0  SandboxIsolationKey=0  PathPolicy=0  OverlayFilesystem=0
AbstractFilesystem=0  LocalFilesystem=0  WorkspaceIndex=0  PlanModeManager=0
LocalFilesystemWithShell=0  CompositeFilesystem=0  ProjectAwareOverlay=0
```

实测范围：`ruoyi-modules` / `ruoyi-admin` / `ruoyi-common` 全部 `.java`。任务书「实测引用数均为 0」**核实为真**。

---

## 2. 能力对账表

差异分类：**① 官方更强**（归位后增强）/ **② 等价**（可替换）/ **③ 官方更弱**（归位后回退，需补层）。

### 2.1 官方 sandbox ↔ 自研 Docker 执行面

| 能力 | 官方 | 自研 | 类 |
|---|---|---|---|
| 容器化执行 | `DockerSandbox` + `SandboxClient.exec` | `DockerCliRuntime`(741) + `DockerSandboxCommandBuilder`(137) | ② 等价 |
| 镜像钉版 | `.image(String)`，**未强制不可变** | `DockerSandboxConfig` 强制「evaluator-approved immutable local image id」+ 拒控制字符/逗号 | ③ 官方更弱 |
| 禁 shell 解释器 | ❌ **校验层缺失**（工具层无此检查；`sh -c` 在沙箱实现层） | `ExecutablePolicy.SHELL_INTERPRETERS` 11 个解释器全禁 | ③ 官方更弱 |
| 可执行白名单 | ❌ **校验层缺失**（`ShellExecuteTool` 纯薄委托，无此层） | `config.executableAllowlist()` 精确匹配（剥 `.exe/.cmd/.bat/.com`） | ③ 官方更弱 |
| argv 字面量 | ❌（只有 `command` 字符串；沙箱层 `sh -c` 整串解释） | `argv: List<String>`，**无 shell 解析参数** | ③ 官方更弱 |
| 只读根 / 断网 | `.network(String)` 可配，未强制断网 | networkless + read-only（非沙箱自研侧强制） | ③ 官方更弱 |
| 非 root / 资源上限 | `memorySizeBytes` / `cpuCount` 可配，`user` 未暴露 | `user` 正则校验必填 + `memoryBytes` + `cpuMilliCores` + `pidsLimit` + `tmpfsBytes` **全部必填** | ③ 官方更弱 |
| 挂载面 | `WorkspaceSpec.entries` 支持 `bind_mount`（可多挂载） | **仅工作区单挂载** | ① 官方更强 |
| 跨调用恢复 | 快照 7 种实现（Noop/Local/Remote/Redis/Oss/Jdbc/Postgres） | ❌ 无（每次新容器 + nonce） | ① 官方更强 |
| 并发执行互斥 | `SandboxExecutionGuard`（Redis/JDBC/自定义） | `LocalSessionTurnGate` JVM 级 `static` | ① 官方更强 |
| 沙箱状态持久化 | `SessionSandboxStateStore` 落 `AgentStateStore` | ❌ 无 | ① 官方更强 |
| 产物外送 | `ArtifactDeliveryTarget` SPI + `deliver_artifact` 工具 | `HarnessArtifactTools`（自研） | ② 等价 |
| 资源清理 | `stop` + `shutdown` 分离，失败各记 warn 不抛出 | `DockerCliRuntime` 自管进程树清理 | ② 等价 |

**净结论：官方 sandbox 在「持久化 / 隔离模型 / 挂载灵活度」上更强，在「单次执行的约束强度」上明显更弱。** 直接切换 = 净回退，除非叠加自研的白名单层。

### 2.2 官方 workspace ↔ 自研 `KernelScopeKey`

| 能力 | 官方 | 自研 | 类 |
|---|---|---|---|
| 四维（project×user×agent×session）复合键 | **无 project 维**（只有 scope+value 二元） | `KernelScopeKey.of(projectId, userId, agentId, sessionId)` 四维收口 | ① 自研更强 |
| 键段注入防护 | ❌ `SandboxIsolationKey.resolve` 不做任何字符校验 | `validateSegment` 拒 `:`、拒 `..` | ① 自研更强 |
| 工作区路径分桶 | `NamespaceFactory` 拼路径前缀 | `AgentScopeChatKernel.workspaceFor(root, project, user, agent)` 三段目录 | ② 等价 |
| 路径段 fail-closed | `LocalFilesystem` 有 `SecurityException`（文件系统层，非键层） | `workspaceSegment()` 拒 `: / \ ..`，+ **逐段 symlink 检查** + 目录类型检查 | ① 自研更强 |
| 多租户覆盖目录（`<userId>/skills/` 覆盖 `skills/`） | ✅ 官方独有 | ❌ 完全没有 | ① 官方更强 |
| `AGENTS.md`/`MEMORY.md` 两层读 | ✅ 官方独有 | ❌ | ① 官方更强 |
| `MEMORY.md` 预算注入 + 截断 | ✅ `maxContextTokens` | ❌（记忆系统整体关闭） | ① 官方更强 |
| `knowledge/` 目录索引式注入 | ✅ | ❌ | ① 官方更强 |
| `tools.json` allow/deny + MCP | ✅ build 期一次性 | 仅 `toolsConfig.setDeny`（用的就是官方类，✅）——【2026-10-03 实测更正：`ToolsConfig.setDeny(List.of("web_fetch","web_search","wait_async_results"))` 已于 commit `b757fa7a`（commit message 仅 "test"）被删除，当前 `AgentScopeProjectAgentKernel.java:435` 为 `new ToolsConfig()` 空对象。`web_fetch` 实际仍可用并已真实调用成功（由 ProjectAgentOfficialToolGovernance 的 SSRF 防护部分兜底）；`web_search` 无任何出站管控，仅因缺 `TAVILY_API_KEY` 才不可用。依据原描述做的安全判断失效。】 | ② 等价 |
| 会话日志 JSONL 落 workspace | ✅ | ❌（`disableSessionPersistence` + `disableTranscript`） | ① 官方更强 |
| 显式相对路径写接口 + `startsWith` 校验 | ✅ `writeUtf8WorkspaceRelative` | ❌ | ① 官方更强 |

**净结论：这两者不是同一层的东西。** `KernelScopeKey` 管的是**状态寻址**（谁能看到谁的对话状态），官方 `WorkspaceManager` 管的是**文件面**（谁的工作区文件可见）。归位 workspace **不能删 `KernelScopeKey`**，必须二者并存——这是本方案最容易被误判的一点。

### 2.3 官方 FilesystemTool/ShellExecuteTool ↔ 自研 BuiltinCodingTools/ExecuteProcessTool

| 自研工具 | 官方对应 | 类 | 关键差异 |
|---|---|---|---|
| `read_file`(path,offset,limit,lineNumbers,includeBinary) | `read_file`(path,offset,limit) | ① 自研更强 | 官方无行号、无二进制标记；自研返回 SHA-256 供后续写复用 |
| `read_source` | — | ① 自研独有 | 官方无「字面文本 vs JSON content 字段」的区分 |
| `list_files`(path,maxDepth,limit) | `list_files`(path) | ① 自研更强 | 官方无 `maxDepth`；官方 200 条 / 16k 字符硬截断 |
| `glob_files`(glob,path,maxDepth,limit) | `glob_files`(pattern,path,limit) | ① 自研更强 | 自研「遵循 .gitignore、从不跟随链接」；官方无 depth |
| `search_text`(query,regex,glob,maxResults,maxLineChars) | `grep_files`(pattern,path,glob,limit) | ① 自研更强 | **官方 `pattern` 是字面量、不支持正则**；自研支持正则 + ripgrep |
| `git_status` / `git_diff` | — | ① 自研独有 | 官方无 Git 工具（只能靠 `execute` 跑 git，而那要求镜像有 git） |
| `write_file`(path,content,**expectedSha256**) | `write_file`(path,content) | **③ 官方更弱** | 官方无乐观锁、无原子替换 |
| `replace_text`(path,oldText,newText,**expectedSha256**) | `edit_file`(path,old_string,new_string,replace_all) | **③ 官方更弱** | 官方靠容器内 `python3` 做精确替换；自研要求「恰好一次匹配」+ SHA 校验 + 保持原行尾 |
| `execute_process`(executable,argv,cwd,timeoutMs,stdin) | `execute`(command,working_directory,timeout) | **③ 官方更弱（严重）** | 官方工具层**无白名单校验层**（非「可绕过」而是「缺失」），见 §3.2 R1/R2 |
| `run_inline_probe`(stdin 脚本) | — | ① 自研独有 | 官方无 stdin 通道 |
| — | `LocalFsMode.ROOTED` + `PathPolicy` 三根 | ① 官方更强 | 官方路径模型更完整（Windows 盘符、symlink 语义统一在 filesystem 层） |

**净结论：文件工具侧官方是「架构更正、语义更弱」；执行工具侧官方是「纯回退 + 校验层缺失」。** 因此 P0 档的做法不是「换成官方工具」，而是**「换官方 filesystem 承载 + 保留自研工具的约束语义」**——具体见 §5 步骤 2。

---

## 3. 沙箱安全语义差异（最高风险项）

### 3.1 两套模型的本质区别

| 维度 | 官方 `SandboxExecutionGuard` + `SandboxIsolationKey` | 项目 `KernelToolGovernance` + 原生 permission |
|---|---|---|
| 回答的问题 | **「同一 slot 现在能不能进？」**（互斥/串行） | **「这个工具这次调用该不该执行？」**（授权） |
| 决策维度 | 1 维：key 身份 | 3 维：工具名 + 入参内容 + 权限模式 |
| 输出 | `SandboxLease`（拿到 / 阻塞 / 中断） | `PermissionDecision{ALLOW, DENY, ASK, PASSTHROUGH}` |
| 失败方向 | 阻塞等待（`tryEnter` 阻塞至 `InterruptedException`） | 拒绝（`DENY` 零副作用）或挂起（`ASK` 等人） |
| 状态 | 无状态（只有 lease 生命周期） | **有状态**：`KernelToolEffectLedger` 写前意图 → commit/abandon/settle 三态 |
| 缺键行为 | `Optional.empty()` → 跳过 state 查找，**只 warn** | `KernelScopeKey.of` 抛 `IllegalArgumentException` → **fail-closed 拒绝，引擎不触达** |

**二者是正交的，不是替代关系。** 官方 guard 无论如何都替代不了 `KernelToolGovernance`：guard 不看工具名、不看入参、不留账本。

### 3.2 归位后**会回退**的安全保证（必须先补才可切）

> **本节术语纠正（重要，直接影响落地工程量）**：早前版本把 R1/R2 描述为「官方有白名单但可被 `sh -c` 绕过」。**这是错的**。源码实读结论：`ShellExecuteTool`（99 行）是**纯薄委托**——全文无 `ProcessBuilder`、无 `Runtime.exec`、无 `"-c"` 拼接，只把 `command` 交给 `AbstractSandboxFilesystem.execute(...)`。`sh -c` 出现在**沙箱实现层**（`DockerSandbox.doExec`：`docker exec -w <root> <id> sh -c <command>`，`impl/docker/DockerSandbox.java:150-160`）。
>
> 正确表述是 **「校验层缺失」**，不是「校验层可绕过」。工程含义完全不同：
> - ❌ 错误做法：等沙箱就位，检查它的 argv 转义对不对
> - ✅ 正确做法：**在官方工具层之外，从零自建一整道可执行文件白名单拦截**（`SandboxClient` / `Sandbox` 包装，或工具层前置闸）
>
> 这是两套完全不同的工程量，不能按前者排期。

| # | 回退项 | 官方现状 | 自研现状 | 后果 |
|---|---|---|---|---|
| **R1** | **shell 解释器禁令** | **校验层缺失**：`ShellExecuteTool` 无此检查，`sh -c` 由沙箱实现层固定使用 | `ExecutablePolicy` 11 个解释器全禁 + 显式报错 `SHELL_INTERPRETER_DENIED` | 官方侧**无任何位置**阻止模型提交 `sh -c '...'`。**这是最严重的一条。** |
| **R2** | **可执行白名单** | **校验层缺失**：工具层整层不存在（薄委托，无 ProcessBuilder / 无 argv 处理） | `executableAllowlist` 精确匹配（剥 `.exe/.cmd/.bat/.com`） | 沙箱内任意二进制可执行，且**必须在官方之外自建拦截**才能补 |
| **R3** | **强制断网** | `.network(String)` 由调用方决定，默认 Docker 默认网 | `DockerSandboxCommandBuilder` 强制 networkless | 沙箱内可外连（`curl`/DNS exfil） |
| **R4** | **强制只读根 + 非 root + 资源上限必填** | 全部是可选 setter | `DockerSandboxConfig` 构造时 `throw` 校验，缺一不可 | 忘配即默认放行 |
| **R5** | **写文件乐观锁（`expectedSha256`）** | 官方 `write_file` 无条件写 | 自研强制 SHA 校验，不匹配则零副作用失败 | 并发/陈旧证据下静默覆盖他人改动 |
| **R6** | **`replace_text` 唯一匹配** | 官方 `edit_file` 语义为 `replace_all` 可选 | 自研「恰好一次匹配」否则无副作用失败 | 宽泛替换误伤 |
| **R7** | **`grep_files` 正则** | 官方明写字面量 | 自研支持 regex + ripgrep | 检索能力下降（非安全） |
| **R8** | **Git 工具纯 Java 化** | 官方无 | 自研 `git_status`/`git_diff` 纯 Java，不调外部 filter/textconv 命令 | 需退到 `execute` 跑 git → 与 R1/R2 叠加后不安全（**且 R1/R2 是缺失不是可绕，等于无任何防线**） |

### 3.3 归位后**会增强**的安全保证

| # | 增强项 | 说明 |
|---|---|---|
| **E1** | 跨副本互斥 | `RedisSandboxExecutionGuard` 用 `SET NX PX`；项目 `LocalSessionTurnGate` 是 **`static` JVM 级**（`AgentScopeChatKernel:66`），多副本部署时同 slot 并发不互斥——注释里已记录「双副本 CAS 竞态」教训 |
| **E2** | 状态持久化的 fail-closed 面 | 官方 `SessionSandboxStateStore` 复用 `AgentStateStore`（项目已有 Redis/MySQL），沙箱状态不再丢 |
| **E3** | 快照恢复的工作区连续性 | `carryOverPersistedSnapshotId` 保证 resume 失败不清零工作区 |
| **E4** | 沙箱内外产物边界的显式化 | `deliver_artifact` + `ArtifactDeliveryTarget`，沙箱内文件默认出不来，边界可审计 |
| **E5** | 路径裁决集中化 | `LocalFsMode.ROOTED` + `PathPolicy` 三根把「哪些绝对路径合法」变成一份配置，而不是散在多个 guard 里 |

### 3.4 一个必须显式裁决的语义冲突

`SandboxIsolationKey.resolve(USER, ctx, agentId)` 在 `userId` 缺失时**降级到 SESSION**（用 sessionId 作 value），不抛异常。
`KernelScopeKey.of(...)` 在 `userId` 为空时**塌缩到 `__anon__` 固定桶**。

两者在同一场景下会产生**不同的 slot**：

| 场景 | `KernelScopeKey` 落库 slotId | 官方 `slotSessionId(USER)` |
|---|---|---|
| userId 空、sessionId=s1、agentId=a | `pP:u__anon__:aA:sS1` | `sandbox/user/aA/s1`（USER 列 null） |
| 同一匿名用户开第二个会话 s2 | `pP:u__anon__:aA:sS2` | `sandbox/user/aA/s2` |

官方把匿名用户的沙箱按 session 分开，项目把会话状态按 session 分开——**在 USER scope 下二者的分桶粒度不一致**。若直接归位而不裁决，会出现「会话状态按 session 隔离、沙箱按 session 隔离，但用户的工作区按 session 隔离而项目原设计是共享」的偏差。

**裁决建议**：项目工作区当前是 `project × user × agent` **三维**（`AgentScopeChatKernel.workspaceFor`，`AgentScopeChatKernel:363-367` 的注释明写「会话维由 stateStore 四维键硬隔离，同一用户同一员工的多会话共用员工工作区」）。因此沙箱 scope **应选 `AGENT` scope 的等价物**（value=agentId），而不是 `USER`。落地时通过包装 `SandboxContext.builder().isolationScope(...)` 显式指定，**不用默认值**。

#### 3.4.1 「禁用默认值」不只是保守偏好——有 fail-closed 不一致的结构性证据

除本节的 `SandboxIsolationKey` 外，官方 harness 寻址层**普遍缺 project/tenant 维度**，且在 tenant 维度上**降级行为与项目纪律相反**。以下均为本轮实读源码：

| 官方位置 | 实读到的行为 | 与项目纪律的冲突 |
|---|---|---|
| `SandboxIsolationKey.resolve(USER, ctx, agentId)`（`sandbox/SandboxIsolationKey.java:78-95`） | `userId` 缺失 → **静默降级 SESSION**，仅 `log.debug`；两者都缺才 `log.warn` | 项目 `KernelScopeKey.of` 空 `userId` → 塌缩 `__anon__` **固定桶**；且非法段直接 `throw` fail-closed |
| `TranscriptRef` 紧凑构造器（`transcript/TranscriptRef.java:24-33`） | `tenant` 空值**静默降级 `"default"`**（L25-27），而同一构造器里 `agentId`（L29）与 `sessionId`（L32）**是 `throw` fail-closed** | **同一构造器内两套失败语义**：可注入维度 fail-open，不可注入维度 fail-closed。项目要求全部 fail-closed |
| `StoreBackedSubagentRegistry.NAMESPACE`（`.../StoreBackedSubagentRegistry.java:46`） | 固定 `List.of("subagents", "exposed")`，**无 tenant 段** | 子 agent 记录在共享 store 中无租户前缀；项目要求每维独立分桶 |

**这三条构成一条一致的证据链**：官方寻址层在**「租户/项目身份缺失」时倾向于降级到共享默认值**（`"default"` / SESSION 塌缩 / 无前缀命名空间），而项目 `KernelScopeKey` 的纪律是**任何维度缺失或含注入特征一律 fail-closed 拒绝**。

**因此 RL4「禁止用默认值」应从「保守建议」升格为「结构性要求」**：官方默认值不是「中性默认」，而是**在身份缺失时把多个租户折叠到同一桶**的行为。归位时任何 `IsolationScope` / `tenant` / `NamespaceFactory` 参数都必须由 `KernelScopeIdentity` 显式提供，**不允许落到官方默认分支**。落地时应加一条门禁：扫描项目侧对 `IsolationScope` / `TranscriptRef` / `NamespaceFactory` 的使用，出现「未显式传值」即 FAIL。

---

## 4. 隔离键收口约束（具体做法）

### 4.1 问题

归位后，复合键的拼接点从 1 个变成 **5 个**：

| 收口点 | 源码位置 | 拼接形态 | 拼出的东西 |
|---|---|---|---|
| ① 项目 | `KernelScopeKey.of`（`chat/kernel/KernelScopeKey.java:53-64`） | 字符串 `+ ":" +` | `p{P}:u{U}:a{A}:s{S}`（状态 slot） |
| ② 项目 | `AgentScopeChatKernel.workspaceFor`（`chat/kernel/AgentScopeChatKernel.java:363-367`） | **`Path` 链式 `root.resolve().resolve().resolve()`** | `root/P/U/A`（工作区目录） |
| ②' 项目 | `ProjectAgentWorkspace.prepare`（`ipd/.../ProjectAgentWorkspace.java:34-37`） | 同上，`Path` 链式 | `root/P/U/A`（项目智能体工作区） |
| ③ 官方 | `SessionSandboxStateStore.slotSessionId`（`sandbox/SessionSandboxStateStore.java:84-91`） | 字符串 `+ "/"` | `sandbox/user/{agentId}/{value}`（沙箱状态 slot） |
| ④ 官方 | `WorkspaceManager.resolveRuntimeDataPath` + `NamespaceFactory.getNamespace`（`workspace/WorkspaceManager.java:239-249`） | `Path.resolve(ns.join("/")).resolve(rel)` | `workspace/{ns...}/{relPath}`（运行时数据路径） |

#### 4.1.1 门禁 C2 目前是**假绿**（本轮新增发现）

`AgentScopeChatKernel.workspaceFor`（:363-367）是**第二处手拼三段路径**，`ProjectAgentWorkspace.prepare`（:34-37）是第三处。三处的 `workspaceSegment` / `segment` **确实都是 fail-closed**（拒 `: / \ ..`、`.`、空值，且逐段 symlink 检查）——**不是活漏洞**。

但门禁检测器 `.agents/skills/agentscope-harness/scripts/scope-key-statements.py:7` 的唯一匹配模式是：

```python
PAIR = re.compile(r'\+\s*":"\s*\+')
```

**只认字符串 `+ ":" +` 拼接**，`Path` 链式（`.resolve().resolve().resolve()`）完全不在检测范围内。实测：把 `workspaceFor` 的实现喂进该脚本 → **EXIT=1（未命中）**。

**结论：门禁 C2「复合键单一收口」当前处于假绿状态——它只证明了字符串拼接收口，没有证明路径拼接收口。** 归位 sandbox 后，③ 和 ④ 会**复用同一批已复合的身份段**再拼一次（`SandboxIsolationKey` 拼 slotId、`NamespaceFactory` 拼工作区路径），如果 ② 的三处 Path 链式不被纳入检测，未来任何一处新增的路径拼接都不会被门禁发现。

**因此 C9/C10 门禁扩展（§4.2 层三）必须同时把「`Path.resolve` 链式拼接」纳入检测模式**，否则只是把假绿从字符串面挪到路径面。

#### 4.1.2 归位后官方层会二次拼接已复合的身份段

③ 和 ④ 都会**用 `RuntimeContext` 里的 `userId` / `sessionId` 当原始材料再拼一次**。项目已经把这两个字段**预先复合**过了（`p{P}:u{U}` / `a{A}:s{S}`），所以官方拼出来的是「**含冒号的复合串再嵌进路径**」——例如 `sandbox/user/ipd-project-agent/p10:u55`。

这会**击穿门禁 C2/C3 的前提**（`harness-contract-check.sh`：C2 要求「每工作树内复合键手拼 ≤1 处」，C3 要求收口文件必须 fail-closed 拒 `:` 与 `..`）。官方源码在**依赖 jar 里**不在扫描范围内，门禁不会报——但语义上收口纪律已破：**同一个冒号分隔的复合串，被两处不同代码用不同分隔规则再次拼接**，一旦某一处未来改成不校验 `:`，就会产生可注入的路径。

结合 §4.1.1：**当前 C2 假绿（看不见 Path 链式）+ 归位后官方层新增 2 个二次拼接点** = 收口纪律在两个维度上同时失守。这正是 §4.2 三层做法要一次性收住的原因。

### 4.2 做法（三层，全部需要）

#### 层一：**值不透明化**（消除冒号在官方层复活的机会）

在 `KernelScopeKey` 增加一个**不可逆、无分隔符**的派生段，专供官方层使用，业务语义段保留原样：

```java
// KernelScopeKey 内新增（示意，落地时才写）
/** 供官方 sandbox/workspace 层使用的无分隔符摘要值：SHA-256(userId|sessionId, 前 32 hex)。
 *  官方层（SandboxIsolationKey / NamespaceFactory）会把它再拼进路径或 slotId，
 *  必须在进入官方层前就消除 ':' 与 '..' 这两类可注入字符。 */
public String opaqueScopeValue() { ... }
```

传给 `RuntimeContext` 的 `userId` / `sessionId` **保持复合原样**（`AgentStateStore` 寻址不变、既有测试不变），另外通过一个包装层把「官方层看到的身份」替换为摘要值。

#### 层二：**包装层拦截**（不侵入官方代码，也不侵入业务代码）

在两个装配点各加一个**唯一收口的包装类**（建议 `KernelScopeIdentity`，与 `KernelScopeKey` 同包）：

```java
/**
 * 官方身份层的唯一收口：把 KernelScopeKey 的四维复合身份翻译成官方层可安全再拼接的形态。
 * 任何 SandboxContext / NamespaceFactory / RuntimeContext 的官方侧构造都必须经过本类。
 */
public final class KernelScopeIdentity {
    /** 官方层身份：userId 段与 sessionId 段均已摘要化，无 ':' 无 '/' 无 '..'。 */
    public record OfficialIdentity(String userSegment, String sessionSegment) {
        public RuntimeContext toRuntimeContext() { … }
    }
    public static OfficialIdentity of(KernelScopeKey.Scope scope) { … }

    /** NamespaceFactory 包装：官方每次 store 操作都会调它，这里再 fail-closed 一次。 */
    public static NamespaceFactory guardNamespace(NamespaceFactory delegate) {
        return rc -> {
            List<String> ns = delegate.getNamespace(rc);
            ns.forEach(KernelScopeIdentity::assertSafeSegment);   // 二次校验，不信任上游
            return ns;
        };
    }
}
```

`assertSafeSegment` 的规则**照抄 `KernelScopeKey.validateSegment`**（拒 `: / \ ..`、拒 `.`、拒空、拒超长），保证「官方层产出的段」与「项目层收口的段」用**同一套规则**。这样即使官方未来改了拼接规则，门禁与单测仍然拦得住。

#### 层三：**门禁扩展**（把纪律从「人记」变成「机器拦」

扩展 `.agents/skills/agentscope-harness/scripts/scope-key-statements.py` + `harness-contract-check.sh`（本方案不改它们，改动属落地步骤）：

- **修 C2 假绿（前置，优先于 C9/C10）**：现有 `PAIR = re.compile(r'\+\s*":"\s*\+')` **只认字符串拼接**。必须新增一条 `Path` 链式模式（例如 `\.resolve\s*\(` 连续 ≥2 次），把 `AgentScopeChatKernel.workspaceFor:363-367` 与 `ProjectAgentWorkspace.prepare:34-37` 纳入检测。**否则 C9/C10 建在假绿地基上。**
- **C9（新）**：`io.agentscope.harness.agent.{sandbox,workspace}` 包的**业务侧引用点**（import 该两包的项目 `.java` 文件）必须全部落在 `KernelScopeIdentity` 一个文件内。命中其他文件 → FAIL。
- **C10（新）**：扫描项目 `.java` 中所有 `NamespaceFactory` / `SandboxContext` / `IsolationScope` 的实例化点，命中即要求其所在文件是 `KernelScopeIdentity`；且 `IsolationScope` **必须显式传值**，落到默认值 → FAIL（依据 §3.4.1 的 fail-open 证据链）。
- **C9/C10 与 C2 修复均需配 `--self-red`**：分别注入 ① 一个违规 import、② 一段 `Path` 链式拼接、③ 一个未显式传 `IsolationScope` 的构造，验证门禁三者都会红（沿用本仓既有的「自证能红」纪律，避免假绿）。

#### 收口后的单点责任表

| 拼接内容 | 唯一允许的代码位置 | 官方 jar 内部拼接 |
|---|---|---|
| `p{P}:u{U}` / `a{A}:s{S}` 复合身份 | `KernelScopeKey.of` | 不可见（不传入） |
| 官方层可见的身份段 | `KernelScopeIdentity.of` | 不可见 |
| 官方层命名空间段安全校验 | `KernelScopeIdentity.assertSafeSegment` | 不可见（只做只读消费） |
| 工作区目录 `P/U/A`（`Path` 链式） | `AgentScopeChatKernel.workspaceFor` / `ProjectAgentWorkspace.prepare`（**须被 C2 修复后的检测覆盖**） | 不可见（传绝对 `Path`） |

**注意最后一行的设计选择**：给官方 `workspace(Path)` 传**已经算好的绝对路径**，而不是让官方从 `userId` 重新拼路径——这样 ④ 号收口点（`NamespaceFactory`）在 `LocalFilesystemSpec.sharedLocalWorkspace(true)` 时会**被官方主动置 null**（`LocalFilesystemSpec.toFilesystem` 源码 L318-326：`sharedLocalWorkspace ? null : localNamespaceFactory`），从源头掐断一半风险。这是官方提供的正式开关，优先用它。

---

## 5. 自研可删清单 + 落地步骤

### 5.1 可删清单（严格分层，避免误删）

#### A 类 · 归位后**可整体删除**（官方 100% 覆盖，且自研无额外语义）

| 文件 | 行数 | 官方替代 |
|---|---|---|
| `service/coding/harness/tool/command/CommandWorkspaceGuard.java` | 132 | `LocalFilesystem` 的 `resolveSandboxed` / `resolveRooted` + `PathPolicy` |
| `service/coding/harness/tool/command/ExecutablePolicy.java` | 67 | ⚠️ **官方无对应层**（`ShellExecuteTool` 薄委托，白名单校验层整体缺失）——**必须在官方工具层之外自建等价拦截才可删**，见 §3.2 / 步骤 4 |
| `service/coding/harness/tool/command/DockerSandboxCommandBuilder.java` | 137 | `DockerSandbox` + `WorkspaceSpec.entries`（bind_mount） |
| `service/coding/harness/tool/command/DockerRuntime.java` + `DockerCliRuntime.java` | 13 + 741 | `SandboxClient<DockerSandboxClientOptions>` |
| `service/coding/harness/tool/command/ProcessExecutionInterruptedException.java` | 9 | `ExecResult` |
| `service/coding/harness/tool/command/ControlledEnvironment.java` | 33 | `WorkspaceSpec.setEnvironment` / `DockerFilesystemSpec.environment` |
| `service/coding/harness/tool/command/BoundedOutputCollector.java` | 62 | `maxOutputBytes` + `LocalFilesystemSpec.maxOutputBytes` |

小计 **1,194 行**。

#### B 类 · 归位后**可删除工具方法，保留类骨架**

| 文件 | 可删 | 保留 | 理由 |
|---|---|---|---|
| `builtin/BuiltinCodingTools.java`(1,196) | `read_file` / `list_files` / `glob_files` / `search_text` / `write_file` / `replace_text`（6 个，官方 6 个同名工具覆盖） | `read_source` / `git_status` / `git_diff`（3 个官方无对应） | 官方工具无 `expectedSha256` 乐观锁 → 若要保 R5/R6 需自研薄壳，详见步骤 3 |
| `command/ExecuteProcessTool.java`(599) | 全部执行逻辑 | 类壳 + 一个「白名单闸」构造参数 | 换成官方 `execute` 后仍需自研白名单层，见步骤 4 |
| `command/InlineProbeTool.java`(84) | 保留 | 全部 | 官方 `execute` 无 stdin 通道，无法替代 |

小计 **最多 1,795 行**（取决于 B 类保留粒度）。

#### C 类 · **明确不可删**（官方 sandbox/workspace 不覆盖）

- `KernelScopeKey.java`(82) —— 官方无 project 维、无 `:`/`..` 注入防护。
- `chat/kernel/tool/` 全部 7 个文件（`KernelToolGovernance` 204 + `KernelGovernedTool` 110 + 4 个小类 159）—— 官方 `SandboxExecutionGuard` 是互斥不是授权。
- `service/coding/harness/` 下的会话状态机、计划（`HarnessPlanCommandService`）、审批、技能目录（`HarnessSkillCatalog`）、run 生命周期（`DurableHarnessRunProcessor`）、账本（`HarnessToolEffect`）—— **与本议题无关**。
- `DefaultHarnessToolRuntimeFactory` 的阶段化工具面裁剪（`codingDescriptorsFor`）—— 官方无「PLAN / BUILD / VERIFY 三阶段广告不同工具集」的等价物。

**可删总计上限 ≈ 2,989 行 / 自研 33,103 行 ≈ 9%。** 这个比例必须写进决策材料——「归位官方」在本议题上**不是瘦身 60%**，是**换掉 9% 的执行面并接入官方 14,228 行的持续升级轨道**。真正的价值在后半句。

### 5.2 落地步骤

> 每步给文件与验证方式。**真 HTTP + 真 DB 验收，Mock 不接受**（工具层单测可用 Mock，但验收关卡必须真跑）。

#### 步骤 0 · 冻结基线（不改代码）

- 文件：无（只跑命令 + 记 `docs/ipd-系统说明/log.md`）
- 命令：
  ```bash
  cd /Users/mac/Documents/ruoyi-ai
  git rev-parse HEAD                                   # 记基线 commit
  mvn -q -pl ruoyi-modules/ruoyi-chat -am test -Dtest='Kernel*Test,Harness*Test' -DfailIfNoTests=false
  ```
- 验证：全绿 + 记下测试数 N0。后续每一步回归必须 ≥ N0。

#### 步骤 1 · 接入官方 filesystem 承载，但**不动工具**（零行为变更）

- 文件：
  - 改 `AgentScopeChatKernel.java`（builder 段 L325-348）：**新增** `.filesystem(new LocalFilesystemSpec().project(workspace).mode(LocalFsMode.ROOTED).projectWritable(false).sharedLocalWorkspace(true))`
  - 改 `AgentScopeProjectAgentKernel.java`（builder 段 L265-291）：同上
  - **保持** `disableFilesystemTools()` / `disableShellTool()` 不动
- 目的：只让官方 `WorkspaceManager` / `OverlayFilesystem` / `PathPolicy` 起来，供 workspace 上下文注入用，工具面仍为零 → **行为零变更**，是纯粹的「先把地基换了」。
- 验证：
  - `mvn -pl ruoyi-modules/ruoyi-chat -am test` 全绿
  - 启动后端，`GET /actuator/health` = UP；日志无 `IllegalStateException`（官方在 `filesystem(SandboxFilesystemSpec|RemoteFilesystemSpec)` + 本地 stateStore 时会抛，此处用 Local 模式不触发）
  - **真 DB**：`SELECT COUNT(*) FROM <agent_state 表>` 前后一致（无新增 slot）

#### 步骤 2 · 归位 `WorkspaceManager` 上下文注入

- 文件：两个 Kernel builder 段；新增 `chat/kernel/KernelScopeIdentity.java`
- 内容：显式决定 `disableWorkspaceContext()` 的取值。**当前是两个内核都没调它 → 官方 `AGENTS.md` 注入其实已经可能在生效**（见 §1.4 纠偏 4）。步骤 1 之后必须实测确认到底注没注入，再决定：
  - 若确认当前「意外注入」是想要的 → 显式保留并在 `log.md` 登记为「已承认行为」
  - 若不是 → 显式 `.disableWorkspaceContext()`，恢复原状
- 验证：
  - **真 HTTP**：起后端，`POST /resource/sse`（或项目实际聊天流端点）发一轮，**抓 system prompt 事件帧**确认 `<agents_context>` 是否出现 → 与步骤 1 的结果做对比
  - **真 DB**：工作区目录下 `AGENTS.md` 存在且内容正确（`ls ${workspace-root}/p{P}/u{U}/a{A}/AGENTS.md`）
  - 回归：步骤 0 的 N0 全绿

#### 步骤 3 · 文件工具归位（保留自研约束语义）

- 文件：
  - `DefaultHarnessToolRuntimeFactory.java` L146-152：把 `BuiltinCodingTools` 的 6 个官方同名工具**改为从官方 `FilesystemTool` 取**，但通过 `KernelGovernedTool.wrap` 包装（沿用现有治理链，L146 已有 `KernelToolGovernance` 注入模式）
  - 新增薄壳 `chat/kernel/tool/LeaseCheckedFilesystemTool.java`：在官方 `FilesystemTool` 外面套一层，**把 `expectedSha256` 校验补回来**（读时算 SHA、写时比对）→ 保住 R5
  - 保留 `read_source` / `git_status` / `git_diff` 不动（官方无对应）
- 验证（**真 HTTP + 真 DB 硬验收**）：
  1. 起后端 + 真 MySQL
  2. 触发一次 coding agent run（走真 `DurableHarnessRunProcessor`），让模型调 `write_file`
  3. **真 HTTP**：`curl` 拉 run 状态 → 200；工具结果帧返回成功
  4. **真 DB**：`SELECT` run 记录 + `SELECT` 工作区文件内容 → 落盘内容与模型提交一致
  5. **负向**：手工构造过期 `expectedSha256` 调用 → 必须失败且**文件字节未变**（`SELECT` 前后对比 / `md5` 前后对比）
  6. **穿越负向**：`read_file("/etc/passwd")` 与 `read_file("../../etc/passwd")` → 必须拒绝，且事件帧里有拒绝记录
- 门禁：`bash .claude/skills/agentscope-harness/scripts/harness-contract-check.sh` EXIT=0

#### 步骤 4 · 执行面归位（**硬前置：官方侧不存在可执行白名单层，必须从零自建**）

- ⚠️ **前置条件（硬阻断，工程量按「自建一整层」估算）**：
  官方 `ShellExecuteTool` 是**纯薄委托**（99 行，无 `ProcessBuilder` / 无 `"-c"` / 无 argv 处理），**工具层根本不存在可执行白名单这一层**；`sh -c` 由沙箱实现层 `DockerSandbox.doExec` 固定使用（`impl/docker/DockerSandbox.java:150-160`）。因此：
  - ❌ **不可**按「等沙箱就位 → 检查它的 argv 转义对不对」排期——那一层不存在，转义与否都不解决问题
  - ✅ **必须**在官方工具层**之外**自建一整道拦截。推荐落点（按拦截强度排序）：
    1. **包装 `Sandbox` / `SandboxClient`**（最强，覆盖所有 `execute` 调用方，含官方 `BaseSandboxFilesystem` 内部的 `read_file`/`grep_files` 等自造 shell 命令——**注意这会一并拦掉官方自身命令，必须用「内部调用标记」旁路**，否则官方文件工具全部失效）
    2. **包装 `AbstractSandboxFilesystem.execute`**（次强，仅覆盖经 filesystem 的执行面）
    3. 工具层前置闸（最弱，只拦模型直接调用，拦不住框架内部调用）
  - 必须实现：拒 shell 解释器（R1）、可执行白名单（R2）、强制 networkless（R3）、强制只读根 + 非 root + 资源上限必填（R4）
- 文件：`DockerFilesystemSpec` 装配点 + 新增 `SandboxExecutableWhitelist` + 删 `ExecutablePolicy` / `DockerCliRuntime` / `DockerSandboxCommandBuilder`
- ⚠️ 额外注意（落点 1 的陷阱）：官方 `BaseSandboxFilesystem` 的 `readFile` / `listFiles` / `globFiles` / `grepFiles` / `editFile` **全部内部调用 `execute(...)` 执行自造 shell 命令**（源码 :95 / :139 / :174 / :205 / :290 / :353 / :395）。白名单若不区分「模型发起」与「框架自造」，会把官方文件工具一并打死。**这是步骤 4 最容易踩的坑，必须有专门的负向用例。**
- 验证：
  1. `mvn -pl ruoyi-modules/ruoyi-chat -am test` 全绿
  2. **真 HTTP** 触发 `execute`：
     - `execute("mvn -v")` → 200，输出正常
     - `execute("sh -c 'cat /etc/passwd'")` → **必须被拒**，事件帧有 `SHELL_INTERPRETER_DENIED` 等价错误
     - `execute("curl http://example.com")` → **必须被拒**（断网）
     - **白名单回归负向**：`read_file` / `grep_files` / `edit_file` / `list_files` / `glob_files` **必须仍然全部可用**（验证 §步骤 4 的「框架自造命令」旁路生效，没被白名单误杀）——这是落点 1 最容易挂的一条
  3. **真 DB**：run 记录终态 = 失败/拒绝，**且工作区文件无变化**
  4. **并发**：同一 scope 并发两个 `call()` → `SandboxExecutionGuard` 生效（第二个人被串行化），用真 Redis 观察 `SET NX` 键
- 门禁：同步骤 3

#### 步骤 5 · 门禁扩展与自证能红

- 文件：
  - `.agents/skills/agentscope-harness/scripts/scope-key-statements.py`（**修 C2 假绿**：新增 `Path` 链式拼接检测模式，覆盖 `workspaceFor` / `ProjectAgentWorkspace.prepare`）
  - `.claude/skills/agentscope-harness/scripts/harness-contract-check.sh`（新增 C9/C10）
  - ⚠️ 两处是**镜像关系**，`.agents/` 是 IDE 自动发现用的镜像，`.claude/` 是事实源，改动必须字节一致（项目既有 `skill-lint.sh` L5 会 `diff -r` 校验）
- 验证：
  ```bash
  bash .claude/skills/agentscope-harness/scripts/verify.sh;             echo "verify   EXIT=$?"   # 期望 0
  bash .claude/skills/agentscope-harness/scripts/verify.sh --self-red;  echo "self-red EXIT=$?"   # 期望 ≠ 0
  ```
  另需**三项独立自证能红**（缺一即说明该项仍是假绿）：
  1. 违规 import `io.agentscope.harness.agent.sandbox.SandboxContext` 到非收口文件 → C9 必须 FAIL
  2. 任意文件加入 `root.resolve(a).resolve(b).resolve(c)` 形式的 `Path` 链式拼接 → **C2 修复后必须 FAIL**（当前版本不会 FAIL，即假绿实证）
  3. `IsolationScope` 不显式传值的构造点 → C10 必须 FAIL
  三项撤销后均须恢复 PASS。

#### 步骤 6 · 文档闭环

- 文件：`docs/ipd-系统说明/log.md`（加本轮 R 段）、本文件（追加「实际落地结果 vs 预期」列）
- 三源对账：按项目 `CLAUDE.md` SOP-8 跑 `log.md` / `BCP-Registry` / `BCP-Closure-Log` 三处登记。

---

## 6. 风险与红线

### 6.1 红线（触碰即停，必须回主会话裁决）

| # | 红线 | 理由 |
|---|---|---|
| **RL1** | ❌ **禁止**在未完成步骤 4 前置自建白名单层的情况下，把 `disableShellTool()` 去掉并启用官方 `execute` | 官方工具层**不存在**可执行白名单这一校验层（薄委托，`sh -c` 在沙箱实现层），启用即等于**零校验执行面**。这是本方案唯一的**净安全回退路径** |
| **RL2** | ❌ **禁止**删除 `KernelScopeKey` 或 `KernelToolGovernance` | 官方无 project 维、无键段注入防护、无授权裁决与账本。删 = 串桶 + 越权 |
| **RL3** | ❌ **禁止**让官方层看到含 `:` 的复合身份串 | 直接击穿门禁 C2/C3 的 fail-closed 前提（§4） |
| **RL4** | ❌ **禁止**在 `IsolationScope` / `tenant` / `NamespaceFactory` 上用默认值 | 官方默认值**不是中性默认，而是身份缺失时把多租户折叠到同一桶**（`SandboxIsolationKey` USER→SESSION 静默降级、`TranscriptRef` tenant→`"default"`、`StoreBackedSubagentRegistry.NAMESPACE` 固定无租户段）。与项目 fail-closed 纪律结构性冲突（§3.4.1） |
| **RL5** | ❌ **禁止**在归位期间改 `pom.xml` 的 `agentscope.version`（2.0.3） | 本方案所有 API 签名均按 2.0.3 实测；升级需重跑 §1 全表 |
| **RL6** | ❌ **禁止**把「归位」当作删代码指标推进 | 可删上限 ≈ 2,989 行 ≈ 9%；按「删 60%」设 KPI 会诱导跳过步骤 4 的白名单层 |
| **RL7** | ❌ **禁止**用 Mock 结果作为步骤 3/4 的验收证据 | 项目既有纪律（真 HTTP + 真 DB）；Mock 会掩盖 WorkspaceManager 落盘路径与 stateStore slot 命中的真实行为 |

### 6.2 主要风险

| # | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| K0 | **门禁 C2 假绿**：`scope-key-statements.py` 只认 `+ ":" +` 字符串拼接，`workspaceFor` / `ProjectAgentWorkspace.prepare` 的 `Path` 链式拼接**检测不到**。归位后官方再新增 2 个二次拼接点，收口纪律双维度失守而门禁全绿 | **高**（已实证） | **高** | §4.1.1 已定位；步骤 5 把「修 C2 假绿」列为**优先于 C9/C10 的前置项**，并配第 2 项自证能红 |
| K1 | 步骤 1 后发现官方 `AGENTS.md` 注入**当前已在生效**（两内核都没调 `disableWorkspaceContext`），行为变更被生产用户先发现 | 中 | 中 | 步骤 2 显式裁决 + `log.md` 登记；步骤 1 后立即真 HTTP 抓帧确认 |
| K2 | 官方 `workspace(Path)` 与项目 `workspaceFor` 路径分桶不一致，导致 `OverlayFilesystem` 的下层（project 根）泄漏进 agent 可见范围 | 中 | **高** | 步骤 1 只用 `project(workspace)` 把下层锚到工作区自身，绝不指向仓库根；步骤 3 负向用例必须覆盖 |
| K3 | 官方 `LocalFilesystemSpec.executeTimeoutSeconds` 默认 120s 与自研 run 级 deadline 不匹配，长命令逃逸 | 中 | 中 | 步骤 4 显式设 `executeTimeoutSeconds` < 自研 deadline，并保留 `DeadlineMiddleware` |
| K4 | 官方 `grep_files` 字面量语义导致模型正则检索失效，产出错误证据 | **高** | 中 | 步骤 3 **保留自研 `search_text`**，不删（列为不可删） |
| K5 | `RedisDistributedStore` 接入后 stateStore 从 `MysqlAgentStateStore` 切到 Redis，既有会话状态全部失读 | 中 | **高** | 步骤 1-4 **不切** `distributedStore`；如需切，单独一步且必须先做 state 迁移 |
| K6 | 沙箱容器启动耗时（Docker pull/boot）叠加进 turn 延迟，SLA 破线 | 中 | 中 | 步骤 4 加真 HTTP 延迟基线测量；必要时 `IsolationScope.AGENT` 复用容器 |
| K7 | 官方 `WorkspaceManager` 每次操作经 `NamespaceFactory`，若守卫层有性能问题会放大到每次文件读 | 低 | 中 | 步骤 4 前先在真环境压一次带守卫的文件读 QPS |
| K8 | 官方 `SandboxExecutionGuard` 与项目 `LocalSessionTurnGate` **双重加锁**导致死锁 | 低 | **高** | 二者锁序必须固定（先 guard 后 turn gate，或反之但全局唯一）；步骤 4 并发用例必须能观测到锁释放 |
| K9 | 步骤 4 白名单若包装 `Sandbox.exec` 而不区分调用来源，会把官方 `read_file`/`grep_files`/`edit_file`/`list_files`/`glob_files` 的**自造 shell 命令一并拦死**（`BaseSandboxFilesystem` :95/:139/:174/:205/:290/:353/:395 全部内部调 `execute`） | **中** | **高**（功能全瘫） | 步骤 4 已加「内部调用标记」旁路设计 + 专门的回归负向用例（5 个文件工具必须仍可用） |
| K10 | **官方工具自检 ASK 被授权面 ALLOW 压制**（已实证并已修复）：SDK 2.0.3 `PermissionEngine` 仅当工具 `checkPermissions` 自检 ASK 的 `decisionReason` 含 "safety" 才尊重之，否则穿透至 allowRule；`ProjectAgentOfficialPermissions.extend()` 曾把 `HarnessPlatformTools.NAMES`（含 plan_exit）全量 ALLOW，导致官方 plan_exit HITL 静默失效（模型退计划模式不问用户）。**任何未来给官方工具集全量 ALLOW 的授权面都会踩同一坑** | **高**（已实证） | **高** | 已修复：`extend()` 为 plan_exit 加显式 askRule。**机制已由 2.0.3 字节码完全实证（2026-10-02 22:0x，`PermissionEngine` + `lambda$checkPermission$3` 偏移 44-79）**：①决策流水 = DENY规则 → ASK规则 → 模式闸 → `tool.checkPermissions` → {DENY/ALLOW 直返；ASK 且 decisionReason 非空且 toLowerCase 含 "safety" 直返 ASK；**PASSTHROUGH / reason 为 null / reason 不含 safety 则落入放行侧**} → ALLOW规则 → BYPASS→ALLOW / DONT_ASK→DENY / 默认→ASK。②`checkAskRules`（偏移 41）命中即在 64 `areturn`，**结构上根本不进入 `checkAllowRules`**（位于 `continueAfterToolCheck` 偏移 0）——ask>allow 是分支顺序的必然，非配置巧合。③`PlanExitTool.checkPermissions` 的 decisionReason 实为 **null**：`PermissionDecision.ask(String)` 字节码只调 `.message(String)`（偏移 9/10），**从不设置 decisionReason**；故即使 reason 文本（本例为 "The agent wants to finish planning and start executing..."）不含 "safety" 也走不到，偏移 44 `ifnull 79` 直接进放行侧。**推论：任何『工具自检 ASK』在无显式 askRule 时一律无效，与工具无关**——新增官方工具若需人工确认，必须显式加 askRule。，契约测试 `ProjectAgentPlanModeHitlTest` **4 例**守护（第 4 例 `planModePermissionContract` 于 2026-10-02 21:5x 补「只读」权限契约覆盖；已变异自证：askRule→allowRule 后 4/4 全红）；新增官方工具自检 ASK 需求时逐一评估显式 askRule，不得依赖工具自检穿透 |

### 6.3 「未实证」清单（落地前必须补）

1. `AbstractFilesystem.validatePath(String)` 的完整规则——决定 `read_file` 对畸形路径的拒绝强度。
2. `WorkspaceProjectionApplier` / `WorkspaceSpecApplier` 在 `LocalFilesystemSpec` 模式下是否也被触发（文档只说沙箱模式）。
3. `SandboxLifecycleMiddleware` 的实际装配位置与 guard lease 的关闭时机（`SandboxManager` 只保证不泄漏，关闭方在 middleware 里）。
4. `agentscope-extensions-sandbox-kubernetes` 的 Java 侧实现（本方案未评估 K8s 后端可行性）。
5. 项目若要走沙箱，宿主是否具备可用 Docker daemon（`DockerCliRuntime` 已经在用 docker CLI，说明大概率有，但需实测容器网络与镜像仓可达性）。

---

## 附：本次评估的实测命令留痕

```bash
# 官方源码行数
find agentscope-harness/src/main/java/io/agentscope/harness/agent \
  \( -path '*/sandbox/*' -o -path '*/workspace/*' -o -path '*/filesystem/*' \
     -o -name FilesystemTool.java -o -name ShellExecuteTool.java \) -name '*.java' -exec cat {} + | wc -l
# → 14228

# 自研行数
find ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/harness -name '*.java' -exec cat {} + | wc -l   # → 31499
find ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel -name '*.java' -exec cat {} + | wc -l              # → 1604

# 官方类在项目中的引用数（全部 0）
for c in SandboxManager WorkspaceManager FilesystemTool ShellExecuteTool DockerSandbox \
         DockerFilesystemSpec LocalFilesystemSpec RemoteFilesystemSpec SandboxExecutionGuard \
         SandboxIsolationKey PathPolicy OverlayFilesystem AbstractFilesystem LocalFilesystem \
         WorkspaceIndex LocalFilesystemWithShell CompositeFilesystem ProjectAwareOverlay; do
  printf '%s = %s\n' "$c" "$(rg -l "\b$c\b" --include='*.java' ruoyi-modules ruoyi-admin ruoyi-common | wc -l)"
done
```

> 注：本仓 `ripgrep` 不可用时用 `grep -rl`。上表以 `rg` 口径记录，均为 0。

---

## 修订记录

| 版本 | 时间 | 修订内容 | 触发方 |
|---|---|---|---|
| v1 | 2026-10-02 | 初版：官方 API 面实证表、能力对账、沙箱安全语义差异、隔离键收口、可删清单、6 步落地、风险红线 | 本方案 |
| **v2** | **2026-10-02** | **① 纠正 §0/§1.3/§2.1/§2.3/§3.2/步骤 4/RL1 对官方 `execute` 的假论断**：原写「有白名单但可被 `sh -c` 绕过」→ 实读 `ShellExecuteTool`（99 行，无 `ProcessBuilder` / 无 `"-c"` / 无 argv 处理）为**纯薄委托**，`sh -c` 位于沙箱实现层 `DockerSandbox.doExec`（`impl/docker/DockerSandbox.java:150-160`）。正确表述为**「校验层缺失」而非「可绕过」**，并据此改写步骤 4 的前置条件与工程量估算 | 主会话复核（已实读 4,224 字节源码） |
| **v2** | **2026-10-02** | **② §4 新增「门禁 C2 目前是假绿」**：定位 `workspaceFor`(:363-367) 与 `ProjectAgentWorkspace.prepare`(:34-37) 两处 `Path` 链式手拼（三处 fail-closed **正确、非活漏洞**），但 `scope-key-statements.py:7` 唯一模式 `re.compile(r'\+\s*":"\s*\+')` **只认字符串拼接、看不见 `Path` 链式**（实测 EXIT=1）。收口点由 4 个修正为 **5 个**，层三门禁扩展新增「修 C2 假绿」为前置项，风险表新增 K0 | 主会话（独立复核） |
| **v2** | **2026-10-02** | **③ §3.4 新增 3.4.1 节**：并入另两路独立发现作为「禁用默认值」的结构性佐证——`TranscriptRef:24-33` 紧凑构造器 `tenant` 空值**静默降级 `"default"`** 而同构造器 `agentId`/`sessionId` **是 throw**（同构造器两套失败语义）；`StoreBackedSubagentRegistry:46` `NAMESPACE` 固定 `["subagents","exposed"]` **无租户段**。RL4 措辞由「保守建议」升格为「结构性要求」 | 主会话（两路独立发现） |
| **v2** | **2026-10-02** | **④ 顺带修正步骤 5 的镜像一致性**：门禁脚本改动需同时落到 `.agents/`（IDE 镜像）与 `.claude/`（事实源），项目 `skill-lint.sh` L5 会 `diff -r` 校验 | 本方案（落地时自查） |
| **v3** | **2026-10-02** | **§1.3 L176 修正「两内核均未启用 Plan Mode」**（项目智能体内核当晚已启用）+ **§6.2 新增 K10**：实证 SDK 2.0.3 `PermissionEngine` 不尊重不含 "safety" 的工具自检 ASK，`ProjectAgentOfficialPermissions` 全量 ALLOW 曾使 plan_exit HITL 静默失效；修复=显式 askRule（引擎序 ask>allow），`ProjectAgentPlanModeHitlTest` 3/3 守护 | plan_exit HITL 闭环任务（交接单 t7） |

> **v2 未变更的结论**：官方 14,228 行 vs 自研 33,103 行、可删上限 2,989 行 ≈ 9%、官方类引用数全 0、`disableWorkspaceContext()` 两装配点均未调用、`search_text` 不可替代——以上均为**主会话已独立复核确认成立**，原样保留。
