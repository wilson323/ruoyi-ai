#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-write-endpoint-ownership.py — ruoyi-ipd 写接口「资源归属校验」门禁

问题陈述
--------
`@SaCheckPermission` 只回答「你是谁」（角色级权限），不回答「这是不是你的东西」
（资源归属）。仓库里 209 个写端点中，有相当一部分只有角色校验、没有任何归属解析，
攻击者只要拿到权限码就能操作别人的数据。已实证的三个例子：
  BidController.closeInvitation(id)        → 任何人可关闭任意项目的招标单
  KpiFunctionalMetricsController.delete(id) → 任何人可删任意 KPI 配置
  P0EscalationController.resolve(id)       → 任何组长可 resolve 别的组的升级链

正确范式（勿自创，照抄 AiDocumentController.requireVersionOnPathChain）：
    IpdActor actor = ipdPermission.requireInternal();
    requireVersionOnPathChain(id, versionId, actor);   // 先验「这行落在你可见的链上」
    return ApiV1Response.ok(svc.review(versionId, actor.id()));

本门禁做什么
------------
**新写的方法忘了做归属校验 → 立刻报红。** 存量按豁免清单基线放行，
不在清单里的新端点一律 FAIL（默认拒绝）。这样「每修一条少一条」才能变成「不再变多」。

判定规则（基于真实数据定，不是搬运启发式脚本）
----------------------------------------------
R1 扫描范围：`ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/` 全部 .java，
   走公共排除函数 gate_source_files 的同一套污染目录规则
   （.harness/ .codex/ .worktrees/ target/ node_modules/）。
R2 写端点 = 方法注解链里有 @PostMapping/@PutMapping/@PatchMapping/@DeleteMapping，
   或 @RequestMapping 显式带 method = RequestMethod.{POST,PUT,PATCH,DELETE}。
   引号变体（'...' / "..."）与空参 `@PostMapping` 都要能识别。
R3 待判定端点 = 写端点 且 带 @SaCheckPermission（只有角色码、才会被拿着越权用）。
R4 归属证据 = **调用图 + 断言入口的参数形状**（不依赖固定方法名清单）：
   (a) 断言入口**自动发现** —— 从 `org/ruoyi/ipd/security/` 源码里扫出
       「assert/require/check/ensure/verify 系前缀 + 参数含非身份型资源标识」
       的 public/protected 方法。新增守卫方法下次跑门禁自动生效，不改清单。
   (b) 沿**调用图**下钻 —— 端点方法体 → 本类私有方法（深度 2）→
       Service 一跳 → Service 内私有方法（深度 1）。**方法名不限**。
   (c) 遗留 GUARD_TOKENS 保留并入集（只加不减），保证口径不窄于历史。
   为什么 (b) 是必须的：上一轮实测 7 条 HIGH 里 6 条早已修好，校验却封装在
   私有 helper（requireInvitationWritable / requireDemandGroup / requireGateGroup），
   名字不在固定清单里 → 被长期误判成「无归属校验」→ 基线行永远删不掉。
   误报的代价是门禁被整体关掉，等于没有门禁。
   为什么必须按参数形状过滤、不能只按前缀：实测纯前缀发现出 18 个入口，其中
   requireAdmin / requireInternal / requireLeaderOrAdmin 是**角色门**不是归属门；
   算成归属校验会让门禁把 10 个真·无归属的端点误翻成「已合规」——门禁自我漏报，
   比误报更危险。形状过滤后现网翻成「已合规」= 0 条，基线 88 条不变。
   为什么必须做 Service 一跳：实测 inventory 里 59 个「已覆盖」接口的守卫
   大多落在 Service 层（如 `ProjectController.changeStatus` →
   `ProjectService.changeStatus` 里的 `assertSameGroupIpd`）。
R5 豁免清单为空 → FAIL（exit 1）。空清单 = 门禁没在检查任何东西 = 假绿。
R6 豁免清单里有、但代码里已不再需要（补了归属校验）→ FAIL，
   逼迫把该行从清单删掉，让基线只减不增。

退出码
------
0 = PASS
1 = FAIL（有违规，输出 file:line 与原因）
2 = 门禁自身错误（输入读不到 / 清单缺失 / 扫描无输入），不是违规

自证能红（双向）
----------------
    bash scripts/check-write-endpoint-ownership.sh --self-red    # 期望 exit 0
    OWNERSHIP_FAIL_SEED=1 bash scripts/check-write-endpoint-ownership.py  # 期望 exit 1
--self-red 真造 .java 文件走完整扫描路径（不允许往结果里塞虚拟记录）：
  方向一（该红）  造「有角色注解 + 按 id 直接写库 + 无任何归属校验」的控制器 → 必须判红。
  方向二（不该红）造「归属校验封在名字不在任何白名单上的私有 helper 里」的控制器
                → 必须判绿；再把 helper 内的断言**摘掉** → 同一份代码必须转红。
  方向二的对照基线是必须的：只验「不红」的话，判据整个失灵（什么都不报）也会
  「不红」，那样的自证证明不了任何事。
"""

import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CTRL_DIR = os.path.join(REPO_ROOT, "ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller")
EXEMPT_FILE = os.environ.get("OWNERSHIP_EXEMPT_FILE") or os.path.join(
    REPO_ROOT, "scripts/ownership-gate-exempt.txt"
)

# ---------------------------------------------------------------- 污染目录排除
# 与 scripts/lib/audit-gate-input.sh 的 gate_source_files **共享同一套污染目录**
# （由 test_pollution_dirs_match_shared_rule 钉住，漂了就 exit 1）。
# 两者有意不等：shell 侧多排 test/spec/__tests__ 等测试目录，因为它的扫描面可能含
# 测试源码；本门禁只扫 main 下的 controller，那些目录对本门禁没有意义。
# **只排除 .harness/ 是不够的**：实测 .codex/ 下另有 442 份 ipd/controller/*.java
# （ipd-integration 阶段快照 + ruflo 草稿），不排除统计会翻数倍。
POLLUTION_DIRS = (".harness", ".codex", ".worktrees", "worktrees", "target", "node_modules", ".git")

# ---------------------------------------------------------------- 正则
# 引号变体：@PostMapping("x") / @PostMapping('x') / @PostMapping 都算
WRITE_MAPPING = re.compile(r"@(Post|Put|Patch|Delete)Mapping\b")
REQ_MAPPING = re.compile(r"@RequestMapping\b")
REQ_METHOD = re.compile(r"@RequestMethod\.(POST|PUT|PATCH|DELETE)\b")
PERM = re.compile(r"@SaCheckPermission\b")
METHOD_SIG = re.compile(
    r"^\s*(?:public|protected|private)\s+(?:static\s+)?[\w<>,\[\]\.\s]+?\s(\w+)\s*\("
)
KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "class", "interface", "else", "do"}
# 注释与字符串屏蔽：**长度与行数都必须守恒**（换成等长空格，且换行符原样保留），
# 否则 file:line 证据会整体偏移。
#
# 为什么不用正则：已实证正则交替在此处不可靠 ——
#  `(?:\/\*.*?\*\/)|(?:\/\/[^\n]*)` 会在「`//` 行注释起始位置早于后随的 `/*`」时
#  先吃掉 `/*` 那一行的前半段，导致后面的块注释整体漏屏蔽。实测
#  CoefficientChangeService.java 的 javadoc（含 `{@link #propose(...)}`）就是这么
#  漏掉的，方法体匹配到 javadoc 残片、归属守卫被误判为「没有」。
#  改用状态机，顺带把字符串字面量也屏蔽（注释里写 `requireProject(...)` 不算真校验）。
#
# 换行符必须保留：块注释（javadoc 动辄十几行）里若把 \n 也抹成空格，
# 之后所有行号都会前移 —— 这正是「屏蔽注释必须保留行号与偏移」踩的坑。
# 第一版就犯了这个错（块注释内 \n 被抹掉），已由 test_strip_preserves_geometry 抓住。
def strip_comments_and_strings(text):
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif c == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif c == '"' or c == "'":
            quote = c
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == quote:
                    j += 1
                    break
                if text[j] == "\n":  # 未闭合（跨行字符串在 Java 里非法），保守退出
                    break
                j += 1
            for k in range(i + 1, min(j, n) - 1):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)

# 归属守卫 token：可加不可减。命中任一即认为该端点做了资源归属校验。
#
# 来源：ownership-inventory 逐条核出的实际守卫方法名（不是猜的）。
# 早期版本只列了 11 个，结果把 Service 层已有守卫的 26 个真·已覆盖接口
# 误报成违规 —— 误报的代价是门禁被整体关掉，等于没有门禁。
# 增补方式：确证某方法确实做归属校验后，往这里加，并同步登记豁免理由。
#
# ⚠️ 这份清单是**遗留兼容层**，不是判据本身。判据见下面的「结构性判据」：
# 断言入口从 org.ruoyi/ipd/security 源码按参数形状自动发现 + 沿调用图下钻，
# 不再依赖「某个私有 helper 的名字恰好在这张表里」。
# 本清单只保留是为了口径不倒退（历史已认可的入口都留），增补优先走结构发现。
GUARD_TOKENS = (
    # IpdIdorGuard 七个静态 helper，任一调用即算归属校验
    "IpdIdorGuard.",
    # 归属解析：路径 id -> 资源链 -> 项目 -> actor 可见性
    "requireVersionOnPathChain",
    "assertGateProjectSameGroup",
    "requireProject",
    "requireProjectAccess",
    "requireProjectMemberOrSuperAdmin",
    "requireProjectReadable",
    "requireProjectVisible",
    "requireProjectCreator",
    "requireProductCreator",
    "requireRequestedActorMatches",
    "requireTargetMember",
    "requireVisible",
    "requireVisibleProject",
    "requireWritable",
    "requireWritableProject",
    "requireOwnRun",
    # IpdPermission 上的归属闸：以 Supplier 传入资源加载器，加载失败即拒
    "requireActionWriter",
    # 组/租户/角色维度的一致性校验
    "assertSameGroup",
    "assertSameGroupIpd",
    "assertProjectVisible",
    "assertProjectWritable",
    "assertApprover",
    "requireProjectTenantMatch",
    "requireRoleOrSuperAdmin",
    "requireSuperAdmin",
    "requireAuthenticated",
)
GUARD_RE = re.compile("|".join(re.escape(t) for t in GUARD_TOKENS))

# ================================================================ 结构性判据
# 为什么要有这一层：GUARD_TOKENS 是**固定名字清单**，判定完全依赖「做校验的方法
# 恰好叫这个名字」。一旦开发者把校验封装进私有 helper（上一轮实测的
# requireInvitationWritable / requireDemandGroup / requireGateGroup），
# 名字就不在清单里 → 端点被判成「无归属校验」→ 基线行永远删不掉。
# 误报的代价是门禁被整体关掉，等于没有门禁。
#
# 判据改为两件与名字无关的事：
#   (1) 断言入口**自动发现**：从 org.ruoyi/ipd/security 源码里扫出「assert/require/
#       check 系前缀 + 参数含资源标识」的 public/protected 方法。**不手写名字**，
#       新增守卫方法下次跑门禁自动生效。
#   (2) 沿**调用图**下钻：端点方法体 → 本类私有方法（深度 2）→ Service 一跳 →
#       Service 内私有方法（深度 1）。校验封在 helper 里也看得见。
#
# 为什么必须按「参数形状」过滤，不能只按前缀：
#   实测纯前缀发现出 18 个入口，其中 requireAdmin() / requireInternal() /
#   requireLeaderOrAdmin() 是**角色门**不是**归属门**。把它们算成归属校验会让门禁
#   把 ProductLineSpaceController 的 7 个端点 + RequirementChangeController 的 3 个
#   误翻成「已合规」——门禁自我漏报，比误报更危险。实测（见 /tmp/gate-structure-fix.md）：
#   加形状过滤后现网翻成「已合规」= 0 条，基线 88 条不变。
SEC_PKG_REL = "ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/security"
SECURITY_DIR = os.path.join(REPO_ROOT, SEC_PKG_REL)
ASSERT_PREFIXES = ("assert", "require", "check", "ensure", "verify")
# 纯身份型参数：只出现这些说明「这个入口只管你是谁 / 你什么角色」，与「这是不是你的东西」无关。
IDENTITY_PARAM_TYPES = frozenset(
    ("IpdActor", "String", "String... ", "boolean", "int", "long", "Long... ")
)


def discover_assert_entry_points():
    """从 security 包源码自动发现归属断言入口。返回 {方法名: {归属类简单名}}。

    只收「参数里含非身份型资源标识」的入口 —— 角色门（requireAdmin 等）不是归属门。
    读不到 security 目录时返回空集并让上层按「发现不到 → 门禁自身错误」处理，
    不静默降级成「没发现任何守卫」（那等于把门禁关掉）。
    """
    owners = {}
    if not os.path.isdir(SECURITY_DIR):
        return owners
    for fn in sorted(os.listdir(SECURITY_DIR)):
        if not fn.endswith(".java"):
            continue
        simple = fn[: -len(".java")]
        try:
            with open(os.path.join(SECURITY_DIR, fn), encoding="utf-8") as fh:
                text = strip_comments_and_strings(fh.read())
        except OSError as exc:
            fail("security 包源码不可读: %s (%s)" % (fn, exc), 2)
        for m in re.finditer(
            r"^\s*(?:public|protected)\s+(?:static\s+)?[\w<>\[\],\.\s]+?\s(\w+)\s*\(([^)]*)\)",
            text,
            re.M,
        ):
            name, params = m.group(1), m.group(2)
            if not name.startswith(ASSERT_PREFIXES):
                continue
            types = [
                p.strip().split()[0]
                for p in re.split(r",(?![^<]*>)", params.replace("\n", " "))
                if p.strip()
            ]
            if not any(t not in IDENTITY_PARAM_TYPES for t in types):
                continue  # 纯身份/角色入口，不是归属断言
            owners.setdefault(name, set()).add(simple)
    return owners


ASSERT_OWNERS = {}
_LEGACY_BARE_RE = re.compile(
    r"(?<![.\w])(%s)\s*\(" % "|".join(re.escape(t) for t in GUARD_TOKENS if re.fullmatch(r"\w+", t))
)
_QUALIFIED_CALL = re.compile(r"\b([A-Z]\w*)\.(\w+)\s*\(")
_BARE_CALL = re.compile(r"(?<![.\w])(\w+)\s*\(")


def build_criteria():
    """初始化结构判据。返回 (结构发现的入口数, 判据并集大小)。"""
    global ASSERT_OWNERS
    ASSERT_OWNERS = discover_assert_entry_points()
    if not ASSERT_OWNERS:
        fail(
            "归属断言入口发现失败：%s 下没扫到任何「带资源标识的 assert/require 系」"
            "public 方法 —— 判据面为空 = 门禁自我失效，不许降级放行" % SEC_PKG_REL,
            2,
        )
    return len(ASSERT_OWNERS), len(set(ASSERT_OWNERS) | {t for t in GUARD_TOKENS if re.fullmatch(r"\w+", t)})


def has_assert_call(text):
    """text 里是否出现归属断言调用（不限方法名，来自结构发现 ∪ 遗留清单）。

    三种形态都认：
      IpdIdorGuard.assertSameGroupIpd(...)  类名限定的结构发现入口
      assertSameGroupIpd(...)               静态导入的裸调用
      requireVersionOnPathChain(...)        遗留清单里的裸方法名 token
    """
    for recv, meth in _QUALIFIED_CALL.findall(text):
        # 键是方法名、值是归属该方法的类集合 —— 必须查 meth 的值拿类名，
        # 拿 meth 当类名去查键会永远不成立（写反了会让类名限定调用全部漏判，
        # 而漏判方向正好是「有校验却报红」这条误报路径）。
        if recv in ASSERT_OWNERS.get(meth, ()):
            return True
    for meth in _BARE_CALL.findall(text):
        if meth in ASSERT_OWNERS:
            return True
    return bool(_LEGACY_BARE_RE.search(text))


def local_methods(class_text):
    """类里所有方法名 -> 方法体（同名取第一个）。用于沿本类调用图下钻。"""
    out = {}
    for m in re.finditer(
        r"^\s*(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\],\.\s]+?\s(\w+)\s*\(",
        class_text,
        re.M,
    ):
        name = m.group(1)
        if name in KEYWORDS or name in out:
            continue
        body = method_body(class_text, name)
        if body:
            out[name] = body
    return out


def reaches_assert(body, methods, depth, seen):
    """body 或其沿本类方法 depth 跳可达的调用链里是否有归属断言。"""
    if has_assert_call(body):
        return True
    if depth <= 0:
        return False
    for name in set(_BARE_CALL.findall(body)):
        if name in seen or name not in methods:
            continue
        seen.add(name)
        if reaches_assert(methods[name], methods, depth - 1, seen):
            return True
    return False


def _read_stripped(path, cache):
    if path not in cache:
        try:
            with open(path, encoding="utf-8") as fh:
                cache[path] = strip_comments_and_strings(fh.read())
        except OSError:
            cache[path] = ""
    return cache[path]


def has_guard_structurally(class_text, method_name, svc_idx, cache):
    """结构性判定：端点方法体 / 本类私有方法 / Service 一跳 / Service 内私有方法。"""
    methods = local_methods(class_text)
    body = method_body(class_text, method_name)
    if not body:
        return False
    if reaches_assert(body, methods, 2, set()):
        return True
    return _via_service(class_text, body, svc_idx, cache)


def _via_service(class_text, method_text, svc_idx, cache):
    """Service 一跳，且在 Service 类内继续沿私有方法下钻（深度 1）。"""
    field_types = {}
    for _cls, var in FIELD_DECL.findall(class_text):
        field_types.setdefault(var, _cls)
    for var, meth in CALL.findall(method_text):
        path = resolve_service(svc_idx, field_types.get(var) or "")
        if not path:
            continue
        svc_text = _read_stripped(path, cache)
        mbody = method_body(svc_text, meth)
        if mbody and reaches_assert(mbody, local_methods(svc_text), 1, set()):
            return True
    return False

# 一跳服务解析：按字段声明的类型精确匹配服务文件，不按方法名全仓乱撞
FIELD_DECL = re.compile(
    r"(?:private|protected|public)\s+(?:final\s+)?"
    r"(?:[\w]+\.)*([A-Z]\w*Service)\s+(\w+)\s*;"
)
CALL = re.compile(r"\b(\w+)\.(\w+)\s*\(")
# 服务类遍布 org.ruoyi.ipd 下多个包（service / agent.service / …），
# 所以按整个模块的 main 源码建索引，而不是只扫 ipd/service ——
# 只扫 service 会漏掉 ProjectAgentRunService（在 agent.service），
# 导致 ProjectAgentController 的 5 个端点被误报成无归属校验。
IPD_MAIN = "ruoyi-modules/ruoyi-ipd/src/main/java"

# 服务类源码索引：{简单类名: 文件绝对路径}
def service_index():
    idx = {}
    root = os.path.join(REPO_ROOT, IPD_MAIN)
    if not os.path.isdir(root):
        fail("模块源码根目录不存在: %s" % root, 2)
    for dp, dn, fns in os.walk(root):
        dn[:] = [x for x in dn if x not in POLLUTION_DIRS]
        for fn in fns:
            if fn.endswith("Service.java"):
                idx.setdefault(fn[: -len(".java")], os.path.join(dp, fn))
    return idx


def resolve_service(svc_idx, cls):
    """把字段声明的接口/实现类型名解析到**有方法体**的那个 .java 源文件。

    控制器字段常声明成接口（`private final IDeliverableService deliverableService;`）。
    索引里同时存在 `IDeliverableService.java`（只有抽象方法声明、没有实现体）和
    `DeliverableService.java`（真身）。**必须优先取实现**：命中接口文件时
    method_body 找不到任何方法体，归属校验会被整段漏掉
    （实测漏掉 DeliverableController.upload）。

    ⚠️ 2026-10-03 复核：本节与上面的索引条件**已知有两处缺口，故意未修**——
    ① 索引只收 `*Service.java`，实现文件叫 `*ServiceImpl.java` 且无同名接口的
    7 个 Service 不在索引内（AuditLog / BusinessConfig / CorrectionLog /
    DeletionRequest / ProjectCert / ProjectMember / SystemConfig）；
    ② 剥 `I` 后找 `ProjectCertService` 找不到时退回接口文件（无方法体）。
    实测把两处都修好后，待分类数**从 82 降到 76**，但同一批实验显示其中 1 条是误消
    （`ProjectCertServiceImpl.requireProject` 是纯存在性检查，却因 GUARD_TOKENS 里
    有 `requireProject` 而被当成归属校验），而**同时剔除弱 token
    `requireProject` / `requireAuthenticated` 后数会升到 88**（多出 6 条此前被弱 token
    掩盖的真缺口：AllowanceLedger#confirmStop、Bid#submitResponse、Bid#withdrawResponse、
    Contribution#adjustMarketShare/#confirm/#preview/#saveSelf）。
    只修索引 = 让门禁少报、把真缺口盖住，故**不单独修**；要与弱 token 清理
    一并做并重设基线（属改变判定口径的决定，见 log.md 2026-10-03 段）。
    """
    # 先剥开头的 I 找实现类
    if len(cls) > 1 and cls[0] == "I" and cls[1].isupper():
        impl = cls[1:]
        if impl in svc_idx:
            return svc_idx[impl]
    return svc_idx.get(cls)


def method_body(text, name):
    """在 Java 源码文本里取指定方法名的方法体（同名取第一个）。

    **必须先屏蔽注释**：javadoc 里的 `{@link #propose(Long)}` 会被当成方法
    声明开始，找到的是一段 52 字符的 javadoc 残片而不是真方法体 ——
    已实证这会让 CoefficientChangeController.propose 等 5 个端点被误判成
    「无归属校验」（误报）。屏蔽后行号与偏移不变，file:line 证据仍可用。
    """
    text = strip_comments_and_strings(text)
    pat = re.compile(r"\b%s\s*\(" % re.escape(name))
    for m in pat.finditer(text):
        rest = text[m.end():]
        # 跳过参数列表到 '{'
        depth, idx = 1, 0
        while idx < len(rest) and depth > 0:
            if rest[idx] == "(":
                depth += 1
            elif rest[idx] == ")":
                depth -= 1
            idx += 1
        while idx < len(rest) and rest[idx] != "{":
            if rest[idx] == ";":
                break
            idx += 1
        if idx >= len(rest) or rest[idx] != "{":
            continue
        depth, start = 0, idx
        while idx < len(rest):
            if rest[idx] == "{":
                depth += 1
            elif rest[idx] == "}":
                depth -= 1
                if depth == 0:
                    return rest[start : idx + 1]
            idx += 1
    return ""


def has_guard_via_service(class_text, method_text, svc_idx, cache):
    """控制器方法体调用的第一个 Service 方法里是否有归属守卫（一跳）。

    class_text  只用于取字段声明（服务变量 -> 服务类）；
    method_text 只用于取调用点。
    **两者必须分开传**：早期版本把整类文本和方法体拼在一起再找调用，
    结果「类里任何一个方法调了有守卫的服务」会让**该类全部端点**被判为已覆盖
    —— 已实证漏掉 BidController.closeInvitation / KpiFunctionalMetricsController.delete
    等设计文档亲自点名的越权接口。门禁漏报比误报危险得多。
    """
    field_types = {}  # 变量名 -> 服务简单类名
    for _cls, var in FIELD_DECL.findall(class_text):
        field_types.setdefault(var, _cls)
    if not field_types:
        return False
    for var, meth in CALL.findall(method_text):
        cls = field_types.get(var)
        if not cls:
            continue
        path = resolve_service(svc_idx, cls)
        if not path:
            continue
        if path not in cache:
            try:
                with open(path, encoding="utf-8") as fh:
                    cache[path] = fh.read()
            except OSError:
                cache[path] = ""
        mbody = method_body(cache[path], meth)
        if mbody and GUARD_RE.search(mbody):
            return True
    return False


def fail(msg, code=1):
    sys.stderr.write("[ownership-gate] %s\n" % msg)
    sys.exit(code)


def source_files(root):
    """枚举控制器源码；输入不可读/为空必须 exit 2，不降级成 0 个文件。"""
    if not os.path.isdir(root):
        fail("输入目录不存在: %s" % root, 2)
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in POLLUTION_DIRS]
        for fn in sorted(filenames):
            if fn.endswith(".java"):
                files.append(os.path.join(dirpath, fn))
    if not files:
        fail("没有扫描输入: %s" % root, 2)
    for f in files:
        try:
            with open(f, encoding="utf-8") as fh:
                fh.read()
        except OSError as exc:
            fail("文件不可读: %s (%s)" % (f, exc), 2)
    return files


def scan_file(path, svc_idx=None, cache=None):
    """返回该文件里 (line, method, has_perm, has_guard) 的写端点列表。"""
    svc_idx = svc_idx if svc_idx is not None else {}
    cache = cache if cache is not None else {}
    try:
        with open(path, encoding="utf-8") as fh:
            raw = fh.read()
    except OSError as exc:
        fail("文件不可读: %s (%s)" % (path, exc), 2)
    lines = raw.split("\n")
    # 字段声明要扫整个类（可能在方法之外），注释屏蔽后再取，避免注释里的假声明
    decl_text = strip_comments_and_strings(raw)

    out = []
    n = len(lines)
    i = 0
    while i < n:
        m = METHOD_SIG.match(lines[i])
        if not m:
            i += 1
            continue
        name = m.group(1)
        if name in KEYWORDS:
            i += 1
            continue
        decl_line = i  # 报告用行号 = 方法声明行（1-based），i 随后会被推进

        # 向上收集紧邻的注解行
        annos, j = [], i
        while j > 0 and lines[j - 1].strip().startswith("@"):
            annos.append(lines[j - 1])
            j -= 1

        # 签名可能跨行，延伸到出现 '{'
        k, sig, steps = i, lines[i], 0
        while "{" not in sig and steps < 8 and k + 1 < n:
            k += 1
            sig += " " + lines[k].strip()
            steps += 1
        if "{" not in sig:
            i = max(k, i) + 1
            continue

        # 大括号配平取方法体
        depth, body, b, started, guard = 0, [], k, False, 0
        while b < n and guard < 600:
            ln = lines[b]
            body.append(ln)
            depth += ln.count("{") - ln.count("}")
            if "{" in ln:
                started = True
            if started and depth <= 0:
                break
            b += 1
            guard += 1
        text = "\n".join(body)
        block = "\n".join(annos + [sig, text])
        i = max(b, i) + 1

        is_write = bool(WRITE_MAPPING.search(block)) or (
            bool(REQ_MAPPING.search(block)) and bool(REQ_METHOD.search(block))
        )
        if not is_write:
            continue
        has_guard = bool(GUARD_RE.search(text))
        if not has_guard:
            # 结构性判据：沿调用图下钻（本类私有方法 / Service 一跳 / Service 内私有方法），
            # 方法名不限 —— 治「校验封装进 helper 就被误判成没做」这一类误报。
            has_guard = has_guard_structurally(decl_text, name, svc_idx, cache)
        if not has_guard:
            # 兜底老路径（一跳服务解析），保证口径不窄于历史
            has_guard = has_guard_via_service(decl_text, text, svc_idx, cache)
        out.append((decl_line + 1, name, bool(PERM.search(block)), has_guard))
    return out


def load_exempt():
    return load_exempt_at(EXEMPT_FILE)


def load_exempt_at(path):
    """读豁免清单。文件缺失 → exit 2；清单为空 → exit 1（假绿拦截）。"""
    if not os.path.isfile(path):
        fail("豁免清单不存在: %s（门禁无基线即无法判定，不许静默放行）" % path, 2)
    entries = set()
    with open(path, encoding="utf-8") as fh:
        for lineno, raw in enumerate(fh, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            # 行内注释用 `//`（`#` 是「文件名#方法」的分隔符，不能当注释符）
            line = line.split("//", 1)[0].strip()
            if not line:
                continue
            if "#" not in line:
                fail("豁免行 %d 缺少「文件名#方法」分隔符: %s" % (lineno, raw.rstrip()), 2)
            # 左边到第一个 `#` 是「文件名」，右边是「方法名 + 理由」
            key, _, rest = line.partition("#")
            key = key.strip()
            parts = rest.split(None, 1)
            method = parts[0].strip() if parts else ""
            reason = parts[1].strip() if len(parts) > 1 else ""
            if not key or not method:
                fail("豁免行 %d 缺少「文件名#方法」分隔符: %s" % (lineno, raw.rstrip()), 2)
            if not reason:
                fail(
                    "豁免行 %d 缺豁免理由（防止「先加白名单让它过」）: %s"
                    % (lineno, raw.rstrip()),
                    2,
                )
            entries.add((key, method))
    if not entries:
        fail(
            "豁免清单为空 —— 门禁当前不判定任何存量端点，等于假绿。\n"
            "  请先由 ownership-inventory 逐条分类落入 %s" % path
        )
    return entries


FIXTURE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")

# 自证能红用的真实样本：一个「新写的写方法忘了做归属校验」的控制器。
# 与其往扫描结果里塞一条虚拟记录（那证明不了扫描逻辑），不如造一个**真文件**，
# 让它跟现网源码走完全相同的 source_files → scan_file → 判定 → 报告路径。
FIXTURE_NO_GUARD = "OwnershipGateFailSeedController.java"
FIXTURE_WITH_GUARD = "OwnershipGateOkSeedController.java"

FIXTURE_NO_GUARD_SRC = '''package org.ruoyi.ipd.controller;

import cn.dev33.satoken.stp.annotation.SaCheckPermission;
import org.ruoyi.ipd.service.SeedService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 自证能红样本：形态与真实越权端点一致（有角色注解、按 id 直接写库、无归属校验）。 */
@RestController
public class OwnershipGateFailSeedController {

    private final SeedService seedService;
    private final org.ruoyi.ipd.security.IpdPermission ipdPermission;

    public OwnershipGateFailSeedController(SeedService seedService,
                                           org.ruoyi.ipd.security.IpdPermission ipdPermission) {
        this.seedService = seedService;
        this.ipdPermission = ipdPermission;
    }

    @SaCheckPermission("ipd:seed:delete")
    @DeleteMapping("/seed/{id}")
    public Object remove(@PathVariable Long id) {
        ipdPermission.requireInternal();
        return seedService.remove(id);
    }
}
'''

FIXTURE_WITH_GUARD_SRC = FIXTURE_NO_GUARD_SRC.replace(
    "OwnershipGateFailSeedController", "OwnershipGateOkSeedController"
).replace(
    "        ipdPermission.requireInternal();\n",
    "        ipdPermission.requireInternal();\n"
    "        IpdIdorGuard.requireProjectMemberOrSuperAdmin(\n"
    "                ipdPermission.requireInternal(), id, \"ipd:seed:delete\");\n",
).replace(
    "import org.ruoyi.ipd.service.SeedService;",
    "import org.ruoyi.ipd.security.IpdActor;\n"
    "import org.ruoyi.ipd.security.IpdIdorGuard;\n"
    "import org.ruoyi.ipd.service.SeedService;",
)

# 自证能红的**反向对照样本**：归属校验真实存在，但被封装进一个名字**不在任何白名单上**
# 的私有 helper 里 —— 这正是本轮要治的缺陷（旧门禁在这里必红 → 误报 → 基线行删不掉）。
#
# 两个断言入口分别覆盖两条判定路径：
#   locateTargetSpace  → IpdIdorGuard.assertSameGroupIpd：端点→本类私有 helper 下钻
#   lockKnowledgeEntry → IpdKnowledgeAccessGate.assertManageable：结构发现（这个名字
#                        既不在遗留 GUARD_TOKENS，类名也不是 IpdIdorGuard，
#                        旧门禁连类名限定 token 都匹配不上）
FIXTURE_HELPER_GUARD = "OwnershipGateHelperOkSeedController.java"
FIXTURE_HELPER_SRC = '''package org.ruoyi.ipd.controller;

import cn.dev33.satoken.stp.annotation.SaCheckPermission;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.ipd.security.IpdKnowledgeAccessGate;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.SeedService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 自证能红反向样本：校验封在私有 helper 里，helper 名字不在任何白名单上。 */
@RestController
public class OwnershipGateHelperOkSeedController {

    private final SeedService seedService;
    private final IpdPermission ipdPermission;

    public OwnershipGateHelperOkSeedController(SeedService seedService, IpdPermission ipdPermission) {
        this.seedService = seedService;
        this.ipdPermission = ipdPermission;
    }

    private Long locateTargetSpace(Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        IpdIdorGuard.assertSameGroupIpd(actor, seedService.loadGroupId(id));
        return id;
    }

    private void lockKnowledgeEntry(Long id) {
        IpdKnowledgeAccessGate.assertManageable(id);
    }

    @SaCheckPermission("ipd:seed:delete")
    @DeleteMapping("/seed-helper/{id}")
    public Object removeViaHelper(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        Long target = locateTargetSpace(id);
        lockKnowledgeEntry(target);
        return seedService.remove(target);
    }
}
'''


SHARED_GATE_LIB = os.path.join(
    REPO_ROOT, "scripts/lib/audit-gate-input.sh"
)
# shell 侧额外排除的测试目录：本门禁只扫 main 源码，这些对本门禁无意义，
# 列出是为了「只多不少、绝不少」这条不变式可被自动检查。
SHELL_ONLY_POLLUTION = (
    "test", "tests", "spec", "__tests__", "__mocks__", "test-helpers",
)


def test_pollution_dirs_match_shared_rule():
    """污染目录清单必须与 scripts/lib 的公共函数**同步**，否则统计会悄悄漂。

    为什么要有这道：公共排除函数在 shell 里，本门禁在 Python 里，两处各写一份。
    写的人只改一处的话，门禁仍会跑、仍会报绿，但**扫描面悄悄变了**
    （漏排一个 .codex/ 就会把 442 份快照算进去）。没有这道检查的话，
    这种漂移要等到数字对不上才有人发现。
    不变式：Python 侧必须是 shell 侧的**子集**（只多不少）。
    """
    if not os.path.isfile(SHARED_GATE_LIB):
        fail("公共排除函数不存在: %s" % SHARED_GATE_LIB, 2)
    with open(SHARED_GATE_LIB, encoding="utf-8") as fh:
        sh = fh.read()
    shell_dirs = set(re.findall(r"! -path '\*/([^/']+)/\*'", sh))
    mine = set(POLLUTION_DIRS)
    missing = sorted(shell_dirs - mine - set(SHELL_ONLY_POLLUTION))
    if missing:
        fail(
            "污染目录清单与公共排除函数漂移：scripts/lib/audit-gate-input.sh 排除了 %s，"
            "本门禁没排 —— 扫描面会悄悄变大（漏排 .codex/ 会多算 442 份快照）"
            % missing,
            1,
        )
    sys.stderr.write(
        "[ownership-gate] 自证能红 ✅ 污染目录清单与公共排除函数同步"
        "（本门禁 %d 项，含 shell 侧 %d 项测试目录豁免）\n"
        % (len(mine), len(shell_dirs - mine))
    )


def test_pollution_dirs_actually_excluded():
    """正向对照：污染目录里的 .java 必须**真的**不被扫到。

    上一道只比对了清单文字。这一道造真文件验证行为 ——
    清单写对了但 os.walk 剪枝写错的话，照样会多扫。
    """
    import shutil
    import tempfile

    tmp = tempfile.mkdtemp(prefix="ownership-gate-pollution-")
    try:
        for d in POLLUTION_DIRS:
            sub = os.path.join(tmp, d, "org", "ruoyi", "ipd", "controller")
            os.makedirs(sub, exist_ok=True)
            with open(os.path.join(sub, "Ghost.java"), "w", encoding="utf-8") as fh:
                fh.write("class Ghost {}\n")
        keep = os.path.join(tmp, "org", "ruoyi", "ipd", "controller")
        os.makedirs(keep, exist_ok=True)
        with open(os.path.join(keep, "Real.java"), "w", encoding="utf-8") as fh:
            fh.write("class Real {}\n")
        found = {os.path.basename(f) for f in source_files(tmp)}
        if found != {"Real.java"}:
            fail(
                "污染目录没被真正排除：期望只扫到 ['Real.java']，实际扫到 %s" % sorted(found)
            )
        sys.stderr.write(
            "[ownership-gate] 自证能红 ✅ %d 个污染目录的正向对照通过（只扫到 Real.java）\n"
            % len(POLLUTION_DIRS)
        )
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_strip_preserves_geometry():
    """屏蔽注释必须**长度与行数都守恒**，否则 file:line 证据会偏移。

    这不是洁癖：本仓第一版屏蔽器把块注释里的 \\n 也抹成空格，
    导致 javadoc 之后的每一个行号都前移几行 —— 报告给开发者的
    「文件:行号」指的不是出问题的那一行，等于证据不可用。
    另外注释/字符串里的假守卫也必须被屏蔽（写了不等于做）。
    """
    cases = [
        ("行注释", "a\n  // x\nb"),
        ("多行块注释", "a\n  /* x\n y\n z */\nb"),
        ("字符串字面量", 'a\n  "s"\nb'),
        ("javadoc + 注解 + 方法", "/**\n * d\n */\n@Anno\npublic void m(){}"),
    ]
    for name, src in cases:
        out = strip_comments_and_strings(src)
        if len(out) != len(src):
            fail("注释屏蔽破坏了长度（%s）: %d -> %d" % (name, len(src), len(out)))
        if out.count("\n") != src.count("\n"):
            fail(
                "注释屏蔽破坏了行数（%s）: %d -> %d —— 后续 file:line 会整体偏移"
                % (name, src.count("\n"), out.count("\n"))
            )
    # 注释/字符串里的守卫名不算真校验
    fake = "class A {\n // IpdIdorGuard.requireSuperAdmin fake\n void m(){ String s=\"requireProject\"; }\n}"
    if GUARD_RE.search(strip_comments_and_strings(fake)):
        fail("注释/字符串里的守卫名被误判为真校验（假绿）")
    # 类名限定调用与裸调用都要命中
    if not GUARD_RE.search("IpdIdorGuard.assertSameGroupIpd(actor, g);"):
        fail("类名限定的守卫调用未被识别")
    if not GUARD_RE.search("assertSameGroupIpd(actor, g);"):
        fail("裸守卫调用未被识别")
    sys.stderr.write("[ownership-gate] 自证能红 ✅ 注释屏蔽几何守恒 + 假守卫不误判\n")


def self_red():
    """真造文件跑一遍完整扫描，证明门禁能红；再验补了守卫后恢复绿。"""
    import shutil
    import tempfile

    tmp = tempfile.mkdtemp(prefix="ownership-gate-selfred-")
    try:
        build_criteria()
        # 把现网控制器整份复制进去，样本与真实代码同场扫描
        shutil.copytree(CTRL_DIR, os.path.join(tmp, "ctrl"))
        # 一跳服务解析需要一个真实服务类，否则 service_index 会 exit 2
        shutil.copytree(os.path.join(REPO_ROOT, IPD_MAIN), os.path.join(tmp, "svc"))
        ctrl = os.path.join(tmp, "ctrl")
        exempt = os.path.join(tmp, "exempt.txt")
        shutil.copyfile(EXEMPT_FILE, exempt)

        bad = os.path.join(ctrl, FIXTURE_NO_GUARD)
        with open(bad, "w", encoding="utf-8") as fh:
            fh.write(FIXTURE_NO_GUARD_SRC)

        def run(exempt_path, ctrl_dir):
            """复用与 main 完全相同的判定路径（同一批函数，无捷径）。"""
            eps, total = [], 0
            svc_idx, cache = service_index(), {}
            for p in source_files(ctrl_dir):
                for line, name, has_perm, has_guard in scan_file(p, svc_idx, cache):
                    total += 1
                    if has_perm and not has_guard:
                        eps.append((os.path.basename(p), line, name))
            allowed = load_exempt_at(exempt_path)
            viol = [e for e in eps if (e[0], e[2]) not in allowed]
            return viol, total

        viol_bad, total = run(exempt, ctrl)
        seed_name = FIXTURE_NO_GUARD
        hit = [v for v in viol_bad if v[0] == seed_name]
        if not hit:
            fail(
                "自证失败：注入的样本 %s 没被判红 —— 门禁是假绿，"
                "扫描逻辑没在检查归属校验（写端点 %d 个）" % (seed_name, total)
            )
        sys.stderr.write(
            "[ownership-gate] 自证能红 ✅ 样本 %s 被判红（写端点 %d 个）\n"
            % (seed_name, total)
        )

        os.remove(bad)
        good = os.path.join(ctrl, FIXTURE_WITH_GUARD)
        with open(good, "w", encoding="utf-8") as fh:
            fh.write(FIXTURE_WITH_GUARD_SRC)
        viol_good, _ = run(exempt, ctrl)
        still = [v for v in viol_good if v[0] == FIXTURE_WITH_GUARD]
        if still:
            fail(
                "自证失败：样本 %s 已补 IpdIdorGuard 归属守卫却仍被判红 —— "
                "守卫 token 判定逻辑坏了" % FIXTURE_WITH_GUARD
            )
        sys.stderr.write(
            "[ownership-gate] 自证能红 ✅ 样本 %s 补守卫后不再报红（反向对照通过）\n"
            % FIXTURE_WITH_GUARD
        )

        # --- 反向对照 2：校验封在**名字不在任何白名单上**的私有 helper 里 ---
        # 这一条是本轮结构性修复的核心证明。只跑上一个样本是不够的：那个样本的守卫
        # 名字（requireProjectMemberOrSuperAdmin）本来就在遗留白名单里，**旧的
        # 固定名字判据也能放它过**，所以它证明不了「不再依赖名字」这件事。
        # 本样本的 helper 叫 locateTargetSpace / lockKnowledgeEntry，
        # 其中 assertManageable 连遗留 GUARD_TOKENS 和 "IpdIdorGuard." 类名 token
        # 都匹配不上 —— 旧判据在这里必红（误报），新判据必须绿。
        os.remove(good)
        helper = os.path.join(ctrl, FIXTURE_HELPER_GUARD)
        with open(helper, "w", encoding="utf-8") as fh:
            fh.write(FIXTURE_HELPER_SRC)
        viol_helper, _ = run(exempt, ctrl)
        still = [v for v in viol_helper if v[0] == FIXTURE_HELPER_GUARD]
        if still:
            fail(
                "自证失败：样本 %s 的归属校验真实存在（封在私有 helper "
                "locateTargetSpace/lockKnowledgeEntry 里，helper 名字不在任何白名单上），"
                "门禁却仍判它无归属校验 —— 判据还在依赖固定方法名清单，"
                "结构缺陷未修复" % FIXTURE_HELPER_GUARD
            )
        # 反向对照 2 的对照基线：把 helper 里的断言**摘掉**，同一份代码必须转红。
        # 只验「不红」不够 —— 万一判据整个失灵（什么都不报）也会「不红」。
        with open(helper, "w", encoding="utf-8") as fh:
            fh.write(
                FIXTURE_HELPER_SRC.replace(
                    "IpdIdorGuard.assertSameGroupIpd(actor, seedService.loadGroupId(id));",
                    "actor.id();",
                ).replace(
                    "IpdKnowledgeAccessGate.assertManageable(id);", "// 断言被摘掉"
                )
            )
        viol_stripped, _ = run(exempt, ctrl)
        red_again = [v for v in viol_stripped if v[0] == FIXTURE_HELPER_GUARD]
        if not red_again:
            fail(
                "自证失败：样本 %s 摘掉 helper 内的断言后门禁仍判绿 —— "
                "判据面失灵（对什么都没检查），比误报更危险" % FIXTURE_HELPER_GUARD
            )
        sys.stderr.write(
            "[ownership-gate] 自证能红 ✅ 样本 %s 校验封在非白名单命名的私有 helper 里"
            "仍判绿，摘掉断言后转红（双向自证通过）\n" % FIXTURE_HELPER_GUARD
        )
        sys.stderr.write("[ownership-gate] 自证能红 PASS\n")
        sys.exit(0)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if "--self-red" in sys.argv[1:]:
        test_strip_preserves_geometry()
        test_pollution_dirs_match_shared_rule()
        test_pollution_dirs_actually_excluded()
        self_red()
    list_mode = "--list" in sys.argv[1:]

    files = source_files(CTRL_DIR)
    n_struct, n_union = build_criteria()
    svc_idx = service_index()
    if not svc_idx:
        fail("服务层源码索引为空，无法做一跳归属解析: %s" % IPD_MAIN, 2)
    cache = {}

    endpoints = []
    total_write = 0
    for path in files:
        rel = os.path.basename(path)
        for line, name, has_perm, has_guard in scan_file(path, svc_idx, cache):
            total_write += 1
            if has_perm and not has_guard:
                endpoints.append((rel, line, name))

    # --list：输出当前所有「无归属校验且有角色注解」的端点，供人工分类。
    # 清单由本门禁自己产出，避免出现第二套扫描逻辑导致两套数字打架。
    if list_mode:
        for rel, line, name in sorted(endpoints):
            print("%s#%s\t%s" % (rel, name, "LINE=%d" % line))
        sys.stderr.write(
            "[ownership-gate] --list：写端点 %d 个，待分类 %d 个\n"
            % (total_write, len(endpoints))
        )
        sys.exit(0)

    exempt = load_exempt()
    # found = 当前「有角色注解且无归属校验」的端点集合（也就是基线本该覆盖的面）
    found = {(f, m) for f, _l, m in endpoints}

    violations = [e for e in endpoints if (e[0], e[2]) not in exempt]
    # 失效豁免 = 清单里有、但当前已不属于「无归属校验」集合
    # （代码补了守卫 / 端点被删 / 角色注解被摘掉）→ 基线必须收敛回去。
    # 注意方向是 exempt - found；写成 found - exempt 会永远为空，等于这条规则是死的。
    stale = sorted(exempt - found)

    if violations:
        sys.stderr.write(
            "\n[ownership-gate] ❌ FAIL：%d 个写端点只有角色级权限、无资源归属校验"
            "（%d 个已登记豁免）\n" % (len(violations), len(exempt))
        )
        for rel, line, name in sorted(violations):
            sys.stderr.write("  %s:%s  %s()\n" % (rel, line, name))
        sys.stderr.write(
            "\n  修法二选一：\n"
            "    1) 在方法体里补归属校验（照抄 requireVersionOnPathChain / IpdIdorGuard.*）\n"
            "    2) 确认该端点确无归属概念（如全局配置类），登记进 %s 并写明理由\n"
            % os.path.relpath(EXEMPT_FILE, REPO_ROOT)
        )
    if stale:
        sys.stderr.write(
            "\n[ownership-gate] ❌ FAIL：%d 条豁免已失效（代码已补校验或端点已删），"
            "基线必须只减不增：\n" % len(stale)
        )
        for rel, name in stale:
            sys.stderr.write("  %s  %s()\n" % (rel, name))

    if violations or stale:
        sys.exit(1)

    sys.stderr.write(
        "[ownership-gate] ✅ PASS：扫描 %d 个控制器文件 / %d 个写端点，"
        "无归属校验且未登记豁免的写端点 = 0（豁免基线 %d 条；"
        "归属判据 = 结构发现断言入口 %d 个 ∪ 遗留清单，判据并集 %d 项）\n"
        % (len(files), total_write, len(exempt), n_struct, n_union)
    )
    sys.exit(0)


if __name__ == "__main__":
    main()
