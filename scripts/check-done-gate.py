#!/usr/bin/env python3
"""
翻 done 硬门禁脚本（病根 3 根治）

功能：
输入卡号（如 P2-8.1），检查翻 done 必须满足的硬门禁：
1. 验收报告存在（docs/ipd-系统说明/验收/<卡号>-*.md）
2. 业务表非 0 行（按卡号映射业务表）
3. DDL apply（按卡号映射 DDL 文件，检查真库索引/列存在）
4. 正式16039完整Person会话与业务HTTP200/code0包络（拒跳转；
   会话从 env IPD_GATE_TOKEN 读取，缺会话或无端点映射不得通过）
5. 测试形态（GATE-CL-01 修订，owner 拍板口径 2026-09）：
   tier1 容器级 @SpringBootTest（永久根治形态）→ pass；
   tier2 测试链（Mockito/MockMvc）+ 门禁4 正式实例完整Person与业务HTTP/code0链实测 → pass
        （拍板原文「承认运行实例 HTTP 链+测试链为有效真活证据」）；
   tier3 仅进程内 MockMvc 或纯 Mock 链、无运行实例证据 → fail 且如实报测试形态。
   防钻空子：所有形态判定先剥字符串字面量+剥块/行注释，注释里写 @SpringBootTest
   不再判 pass（旧版子串匹配曾在 Api01AcceptanceTest/P073 注释字面量上假阳性）。

输出：PASS / FAIL + 失败原因

用法：
  python3 scripts/check-done-gate.py P2-8.1
  python3 scripts/check-done-gate.py P2-8.1 --json out.json

边界：只读探针，不改任何代码 / 不 commit / 不 push。
"""

import importlib.util
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ACCEPTANCE_DIR = REPO_ROOT / "docs/ipd-系统说明/验收"
TEST_DIR = REPO_ROOT / "ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd"
SQL_DIR = REPO_ROOT / "docs/script/sql/update"

# 正式IPD后端固定地址；环境变量和其他端口不能替代验收实例。
BACKEND_URL = "http://127.0.0.1:16039"
_probe_spec = importlib.util.spec_from_file_location("ipd_done_http_probe", REPO_ROOT / "scripts/lib/prod-http-probe.py")
HTTP_PROBE = importlib.util.module_from_spec(_probe_spec)
_probe_spec.loader.exec_module(HTTP_PROBE)

# 卡号 → 业务表映射（按镜像 allowedPaths + 主责 AC）
CARD_TO_TABLES = {
    "P2-6.1": ["requirement_changes"],
    "P2-6.2": ["requirement_changes"],
    "P2-7.1": ["handover_records"],
    "P2-7.2": ["handover_records"],
    "P2-7.3": ["handover_records"],
    "P2-7.4": ["handover_records"],
    "P2-8.1": ["handover_records", "requirement_changes"],
    "P1-10.1": ["ai_documents"],
    "P1-10.2": ["ai_documents"],
    "P3-1.3": ["kpi_records", "kpi_shared_confirms"],
    "P3-3.3": ["allowance_ledgers"],
    "P3-6.1": ["contributions"],
    "P3-8.1": ["negative_feedbacks"],
    "P0-3.4": ["ipd_business_config"],
    "P0-3.5": ["ipd_business_config", "bonus_pools"],
}

# 卡号 → HTTP 端点映射（按镜像 allowedPaths + 主责 AC）
CARD_TO_ENDPOINTS = {
    "P2-6.1": ["/api/v1/requirement-changes"],
    "P2-6.2": ["/api/v1/requirement-changes"],
    "P2-7.1": ["/api/v1/handovers"],
    "P2-7.2": ["/api/v1/handovers/batch"],
    "P2-7.3": ["/api/v1/handovers/super-admin"],
    "P2-8.1": ["/api/v1/handovers", "/api/v1/requirement-changes"],
    "P1-10.1": ["/api/v1/ai-documents"],
    "P1-10.2": ["/api/v1/ai-documents"],
    "P4-2.2": ["/api/v1/ai-documents"],   # GATE-CL-01 补登记：AI 生成护栏落点即 AiDocumentController
    "P4-2.3": ["/api/v1/ai-documents"],   # GATE-CL-01 补登记：AI 助手业务串联落点同上
    "P3-1.3": ["/api/v1/kpi-records"],
    "P3-3.3": ["/api/v1/allowance-ledgers"],
    "P3-6.1": ["/api/v1/contributions"],
    "P3-8.1": ["/api/v1/negative-feedbacks"],
}


def check_acceptance_report(card_id):
    """门禁 1：验收报告存在"""
    if not ACCEPTANCE_DIR.exists():
        return {"pass": False, "reason": "验收报告目录不存在"}

    # 匹配 <卡号>-*.md 或 <卡号小写>-*.md
    patterns = [
        f"{card_id}-*.md",
        f"{card_id.lower()}-*.md",
        f"{card_id.replace('.', '_')}-*.md",
    ]
    for pat in patterns:
        matches = list(ACCEPTANCE_DIR.glob(pat))
        if matches:
            return {
                "pass": True,
                "files": [str(m.name) for m in matches],
            }
    return {
        "pass": False,
        "reason": f"验收报告不存在（搜索模式: {patterns}）",
    }


def check_business_tables(card_id):
    """门禁 2：业务表非 0 行"""
    tables = CARD_TO_TABLES.get(card_id, [])
    if not tables:
        return {"pass": True, "reason": "卡号无业务表映射，跳过", "skipped": True}

    try:
        root_pwd = subprocess.check_output(
            ["docker", "exec", "ruoyi-ai-mysql", "printenv", "MYSQL_ROOT_PASSWORD"],
            stderr=subprocess.DEVNULL, timeout=5,
        ).decode().strip()
    except Exception as e:
        return {"pass": False, "reason": "docker mysql 不可达；凭据与命令细节不输出"}

    results = {}
    for t in tables:
        try:
            out = subprocess.check_output(
                ["docker", "exec", "ruoyi-ai-mysql", "mysql", "-uroot", f"-p{root_pwd}",
                 "ipd_dev", "-e", f"SELECT COUNT(*) FROM {t};"],
                stderr=subprocess.DEVNULL, timeout=10,
            ).decode()
            count = int(out.strip().split("\n")[-1])
            results[t] = count
        except Exception as e:
            results[t] = "QUERY_FAILED"

    zero_tables = [t for t, c in results.items() if not isinstance(c, int) or c <= 0]
    if zero_tables:
        return {
            "pass": False,
            "reason": f"业务表无数据或查询未取得证据: {zero_tables}",
            "rows": results,
        }
    return {"pass": True, "rows": results}


def check_ddl_apply(card_id):
    """SQL 文件只证明待核来源；没有真实结构/应用回执不得判通过。"""
    patterns = [f"*{card_id.lower().replace('.', '')}*.sql",
                f"*{card_id.replace('.', '-')}*.sql"]
    files = sorted({f.name for pat in patterns for f in SQL_DIR.glob(pat)}) if SQL_DIR.exists() else []
    return {"pass": False, "state": "UNVERIFIED", "files": files,
            "reason": "未取得本事项真实库的结构与应用证据；SQL 文件存在或无匹配均不能证明已应用。"
                      "请按既有只读 p1-ddl-apply-check.py 核验；不适用须明确登记事项范围。"}


def check_http_endpoints(card_id):
    """正式16039上先核完整Person，再核业务code0包络；不跟随跳转。"""
    endpoints = CARD_TO_ENDPOINTS.get(card_id, [])
    if not endpoints:
        return {"pass": False, "state": "UNVERIFIED",
                "reason": "未登记本事项业务端点，不能以跳过作为真实HTTP证据"}

    token = os.environ.get("IPD_GATE_TOKEN", "").strip()
    results = {}
    try:
        HTTP_PROBE.probe(BACKEND_URL, "/api/v1/auth/me", token)
    except Exception:
        return {"pass": False, "state": "UNVERIFIED", "backend_port": 16039,
                "reason": "正式后端完整Person会话未验证；凭据与异常细节不输出"}
    for ep in endpoints:
        try:
            HTTP_PROBE.probe(BACKEND_URL, ep, token)
            results[ep] = "HTTP200_CODE0"
        except Exception:
            results[ep] = "UNVERIFIED"
    failed = [ep for ep, result in results.items() if result != "HTTP200_CODE0"]
    return {"pass": not failed, "state": "UNVERIFIED" if failed else "VERIFIED",
            "reason": "业务HTTP/code0证据未验证" if failed else "完整Person和业务HTTP/code0已验证",
            "results": results, "backend_port": 16039,
            "backend_state": "FULL_PERSON"}


def _strip_java_noise(text):
    """剥字符串字面量→剥块注释→剥行注释。GATE-CL-01 防钻空子底座：
    旧版 `"SpringBootTest" in content` 子串匹配直接吃注释/字符串字面量——
    Api01AcceptanceTest javadoc「不需 @SpringBootTest」、P073 注释提及同名标记，
    旧逻辑都会假阳性判 pass。判定只认活代码，不认字面量。"""
    text = re.sub(r'"(?:\\\\.|[^\"\\\\])*"', '\"\"', text)   # 字符串字面量掏空
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)         # 块注释（含 javadoc）
    text = re.sub(r"//[^\n]*", "", text)                       # 行注释
    return text


CONTAINER_IMPORT = "import org.springframework.boot.test.context.SpringBootTest;"


def check_test_type(card_id, http_gate=None):
    """门禁 5：测试形态（GATE-CL-01 修订，owner 拍板口径 2026-09）。

    tier1 容器级 @SpringBootTest（永久根治形态）→ pass；
    tier2 测试链（Mockito/MockMvc）+ 门禁4 正式实例完整Person与业务HTTP/code0链实测 → pass
         （拍板原文：承认运行实例 HTTP 链+测试链为有效真活证据）；
    tier3 仅进程内 MockMvc / 纯 Mock 链，无运行实例证据 → fail，
         reason 如实报告测试真实形态（不再出现「测试类型未知」式误判）。
    """
    # 找 AcceptanceTest 文件（按卡号匹配）
    # P2-8.1 → P281AcceptanceTest
    num_part = card_id.replace("P", "").replace("-", "").replace(".", "")
    test_name = f"P{num_part}AcceptanceTest.java"

    test_files = list(TEST_DIR.rglob(test_name))
    if not test_files:
        return {
            "pass": False,
            "reason": f"测试文件不存在: {test_name}",
        }

    test_file = test_files[0]
    content = test_file.read_text(encoding="utf-8", errors="ignore")
    code = _strip_java_noise(content)

    has_container = CONTAINER_IMPORT in code and "@SpringBootTest" in code
    has_inproc_http = "MockMvcBuilders" in code or "TestRestTemplate" in code
    has_mockito = "org.mockito" in code or "MockitoExtension" in code
    rel = str(test_file.relative_to(REPO_ROOT))

    if has_container:
        return {
            "pass": True,
            "test_type": "容器级 @SpringBootTest（永久根治形态：真容器内 HTTP 链）",
            "file": rel,
        }

    if has_mockito or has_inproc_http:
        live = bool(http_gate) and http_gate.get("pass") and not http_gate.get("skipped")
        if live:
            return {
                "pass": True,
                "test_type": "正式16039完整Person与业务HTTP/code0链(门禁4实测) + Mock 测试链（owner 拍板口径有效真活证据）",
                "file": rel,
                "note": "容器级 @SpringBootTest 套件为永久根治形态，本 pass 依拍板口径承认现组合证据",
            }
        form = ("进程内 HTTP 链（MockMvc standalone，真 Controller 非运行实例）"
                if has_inproc_http else "纯 Mock 服务链（无任何 HTTP 链）")
        gate4_state = ("门禁4 跳过（卡号无端点映射）" if (not http_gate or http_gate.get("skipped"))
                       else "门禁4 未过（无实例 200 实测）")
        return {
            "pass": False,
            "reason": f"测试链={form}；拍板口径要求叠加运行实例 HTTP 链证据（当前：{gate4_state}），"
                      f"或补容器级 @SpringBootTest 套件（永久根治）",
            "test_type": form,
            "file": rel,
        }

    return {
        "pass": False,
        "reason": "未识别测试链（剥字符串+剥注释后：无 org.mockito 导入、无 MockMvc/TestRestTemplate、"
                  "无容器级 @SpringBootTest——注释或字符串字面量不计为证据）",
        "file": rel,
    }


def main():
    if len(sys.argv) < 2:
        print("用法: python3 scripts/check-done-gate.py <卡号> [--json out.json]")
        print("示例: python3 scripts/check-done-gate.py P2-8.1")
        sys.exit(1)

    card_id = sys.argv[1]
    json_out = None
    if "--json" in sys.argv:
        idx = sys.argv.index("--json")
        if idx + 1 < len(sys.argv):
            json_out = sys.argv[idx + 1]

    print("=" * 70)
    print(f"翻 done 硬门禁检查：{card_id}")
    print("=" * 70)
    print()

    gates = {}

    print("[1/5] 门禁 1：验收报告存在...")
    gates["acceptance_report"] = check_acceptance_report(card_id)
    g = gates["acceptance_report"]
    print(f"  {'✓ PASS' if g['pass'] else '✗ FAIL'}: {g.get('reason') or g.get('files')}")
    print()

    print("[2/5] 门禁 2：业务表非 0 行...")
    gates["business_tables"] = check_business_tables(card_id)
    g = gates["business_tables"]
    if g.get("skipped"):
        print(f"  ⊘ SKIP: {g['reason']}")
    else:
        print(f"  {'✓ PASS' if g['pass'] else '✗ FAIL'}: {g.get('reason') or g.get('rows')}")
    print()

    print("[3/5] 门禁 3：DDL apply...")
    gates["ddl_apply"] = check_ddl_apply(card_id)
    g = gates["ddl_apply"]
    if g.get("skipped"):
        print(f"  ⊘ SKIP: {g['reason']}")
    else:
        print(f"  {'✓ PASS' if g['pass'] else '✗ FAIL'}: {g.get('reason')}")
    print()

    print("[4/5] 门禁 4：正式16039完整Person与业务HTTP/code0...")
    gates["http_endpoints"] = check_http_endpoints(card_id)
    g = gates["http_endpoints"]
    if g.get("skipped"):
        print(f"  ⊘ SKIP: {g['reason']}")
    else:
        print(f"  {'✓ PASS' if g['pass'] else '✗ FAIL'}: {g.get('reason') or g.get('results')}")
    print()

    print("[5/5] 门禁 5：测试类型...")
    gates["test_type"] = check_test_type(card_id, gates.get("http_endpoints"))
    g = gates["test_type"]
    print(f"  {'✓ PASS' if g['pass'] else '✗ FAIL'}: {g.get('reason') or g.get('test_type')}")
    print()

    # 汇总
    failed_gates = [k for k, v in gates.items() if not v["pass"] and not v.get("skipped") and v.get("state") != "UNVERIFIED"]
    unverified_gates = [k for k, v in gates.items() if v.get("state") == "UNVERIFIED" or v.get("skipped")]
    passed_gates = [k for k, v in gates.items() if v["pass"]]
    skipped_gates = [k for k, v in gates.items() if v.get("skipped")]

    print("=" * 70)
    print(f"汇总：PASS {len(passed_gates)} / FAIL {len(failed_gates)} / SKIP {len(skipped_gates)}")
    print("=" * 70)

    if failed_gates:
        print(f"\n⚠️  {card_id} 翻 done 门禁 FAIL！失败门禁: {failed_gates}")
        print("\n失败原因：")
        for k in failed_gates:
            print(f"  - {k}: {gates[k].get('reason')}")
        print("\n根治：补充真活证据后再翻 done")
        overall = "FAIL"
    elif unverified_gates:
        print(f"\n{card_id} 翻 done 门禁 UNVERIFIED：{unverified_gates}")
        overall = "UNVERIFIED"
    else:
        print(f"\n✓ {card_id} 翻 done 门禁 PASS")
        overall = "PASS"

    report = {
        "card_id": card_id,
        "overall": overall,
        "gates": gates,
        "failed_gates": failed_gates,
        "passed_gates": passed_gates,
        "skipped_gates": skipped_gates,
        "unverified_gates": unverified_gates,
    }

    if json_out:
        Path(json_out).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nJSON 报告已写入: {json_out}")

    sys.exit(0 if overall == "PASS" else 2 if overall == "UNVERIFIED" else 1)


if __name__ == "__main__":
    main()
