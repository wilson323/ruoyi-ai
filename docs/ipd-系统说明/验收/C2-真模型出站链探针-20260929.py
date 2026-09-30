#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""C2-3f 真模型出站链探针（2026-09-29）：A→B→A 模型切换 + 端点热切换 + 预算拒绝 + 失效配置拒绝
——全部走生产 HTTP 面（POST /api/v1/ai/suggest → AiSuggestionService → AiGateway 记账面），
DB 回读 ai_model_usage_ledger / ai_model_budget 三证（出站特征 + HTTP 包络 + 落账行）。

约定（与 d3-ops-probe.py 同风格）：
  - 凭据从 .codex/ipd-dev/config/credentials.json 读取，输出与落盘一律脱敏
  - DB 写操作仅限 ai_model_configs.is_active/endpoint_url 与自插预算行，finally 恢复基态
  - 判定契约（不把断言改成现状；字段名现查 AiSuggestResp=markdown/degraded）：
      S2/S4 真模型出站  : code=0 且 markdown 非 MOCK 特征
      S3   mock 出站    : code=0 且 markdown 含 MOCK 特征（8765 R213 mock）
      S5   端点热切换   : MiniMax 配置 endpoint 临时指 8765 → markdown 含 MOCK（端点每次现读生效，不重启）
      S6   预算拒绝     : code≠0 且 message 含 BUDGET_EXCEEDED，ledger 落 REJECTED:BUDGET
      S7   失效配置     : 全部 is_active=0 → degraded=true +「未配置生效的 AI 模型」（不出站）
用法: python3 docs/ipd-系统说明/验收/C2-真模型出站链探针-20260929.py
"""
import json
import pathlib
import subprocess
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:16039"
REPO = pathlib.Path("/Users/mac/Documents/ruoyi-ai")
CRED = REPO / ".codex/ipd-dev/config/credentials.json"
MYSQL_CNF = REPO / ".codex/ipd-dev/config/mysql-client.cnf"
DB = "ipd_dev"
MINIMAX_ID = "2104885081318375426"
TEST_ID = "1"
MINIMAX_ENDPOINT = "https://api.minimax.cn/v1"
BUDGET_MONTH = "2026-09"

passed = failed = 0
results = []


def check(name, ok, evidence=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"  \033[32mPASS\033[0m [{name}] {evidence}")
    else:
        failed += 1
        print(f"  \033[31mFAIL\033[0m [{name}] {evidence}")
    results.append((name, ok, evidence))


def sql(stmt):
    r = subprocess.run(
        ["mysql", f"--defaults-file={MYSQL_CNF}", DB, "-e", stmt],
        capture_output=True, text=True)
    return r.stdout.strip()


def login():
    cred = json.loads(CRED.read_text())
    pwd = cred.get("ipd_qa_pwd_ipd-admin")
    if not pwd:
        print("credentials.json 无 ipd_qa_pwd_ipd-admin"); sys.exit(2)
    body = json.dumps({"username": "ipd-admin", "password": pwd}).encode()
    req = urllib.request.Request(BASE + "/api/v1/auth/login", data=body,
                                headers={"Content-Type": "application/json"})
    try:
        d = json.loads(urllib.request.urlopen(req, timeout=10).read())
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        print(f"login HTTP {e.code}: {body[:120]}")
        sys.exit(2)
    if d.get("code") != 0:
        print(f"login code={d.get('code')} msg={d.get('message')}"); sys.exit(2)
    return d["data"]["token"]


def suggest(token):
    """POST /api/v1/ai/suggest → (code, message, markdown, degraded)。
    信封契约现查 AiSuggestResp.java:22——内容字段是 markdown 不是 content；
    S7 失效路径是 degraded=true 降级应答（code=0），非异常拒绝。
    scene 必须在服务白名单内（product.name-classify 属素材驱动场景，userPrompt 必填，已验）。"""
    body = json.dumps({"scene": "product.name-classify",
                       "userPrompt": "C2-3f 真模型出站链探针：给产品命名候选分类。"}).encode()
    req = urllib.request.Request(BASE + "/api/v1/ai/suggest", data=body,
                                headers={"Content-Type": "application/json",
                                         "Authorization": f"Bearer {token}"})
    try:
        d = json.loads(urllib.request.urlopen(req, timeout=60).read())
    except urllib.error.HTTPError as e:
        d = json.loads(e.read())
    data = d.get("data") or {}
    return (d.get("code"), d.get("message") or "",
            (data.get("markdown") or ""), bool(data.get("degraded")))


def ledger_after(n0):
    out = sql(f"SELECT id, model_config_id, scene, status, prompt_tokens, completion_tokens "
              f"FROM ai_model_usage_ledger WHERE id > {n0} ORDER BY id")
    return out or "(no rows)"


def set_active(active_id):
    """全局唯一 active 互斥切换：目标置 1 其余置 0。"""
    sql(f"UPDATE ai_model_configs SET is_active=0 WHERE is_active=1")
    sql(f"UPDATE ai_model_configs SET is_active=1 WHERE id={active_id}")


def restore():
    """幂等恢复基态：MiniMax 唯一 active + 原 endpoint + 清自插预算行。"""
    sql(f"UPDATE ai_model_configs SET is_active=0 WHERE is_active=1")
    sql(f"UPDATE ai_model_configs SET is_active=1, endpoint_url='{MINIMAX_ENDPOINT}' "
        f"WHERE id={MINIMAX_ID}")
    sql(f"DELETE FROM ai_model_budget WHERE model_config_id={MINIMAX_ID} "
        f"AND budget_month='{BUDGET_MONTH}' AND budget_tokens=1")


def main():
    n0 = sql("SELECT COALESCE(MAX(id),0) FROM ai_model_usage_ledger").splitlines()[-1]
    token = login()
    print(f"== C2-3f 真模型出站链探针 == ledger 起点 N0={n0}")

    try:
        # S2 A1=MiniMax 真模型出站
        set_active(MINIMAX_ID)
        code, msg, md, deg = suggest(token)
        check("S2-A1-真模型出站", code == 0 and md and "MOCK" not in md,
              f"code={code} markdown_head={md[:32]!r} msg={msg[:60]!r}")

        # S3 B=TEST mock(8765) 出站
        set_active(TEST_ID)
        code, msg, md, deg = suggest(token)
        check("S3-B-mock出站", code == 0 and "MOCK" in md,
              f"code={code} markdown_head={md[:32]!r} msg={msg[:60]!r}")

        # S4 A2=MiniMax 切回（A→B→A 第三段）
        set_active(MINIMAX_ID)
        code, msg, md, deg = suggest(token)
        check("S4-A2-真模型出站", code == 0 and md and "MOCK" not in md,
              f"code={code} markdown_head={md[:32]!r} msg={msg[:60]!r}")

        # S5 端点热切换：MiniMax 配置 endpoint 临时指 mock，不重启
        sql(f"UPDATE ai_model_configs SET endpoint_url='http://127.0.0.1:8765/v1' "
            f"WHERE id={MINIMAX_ID}")
        code, msg, md, deg = suggest(token)
        check("S5-端点热切换", code == 0 and "MOCK" in md,
              f"code={code} markdown_head={md[:32]!r}（model_config_id 仍="
              f"{MINIMAX_ID}，端点现读生效）")
        sql(f"UPDATE ai_model_configs SET endpoint_url='{MINIMAX_ENDPOINT}' "
            f"WHERE id={MINIMAX_ID}")

        # S6 预算拒绝：budget_tokens=1 必拒（预占 fail-closed 不出站）
        sql(f"INSERT INTO ai_model_budget (model_config_id, budget_month, budget_tokens, "
            f"preoccupied_tokens, consumed_tokens, overage_tokens) "
            f"VALUES ({MINIMAX_ID}, '{BUDGET_MONTH}', 1, 0, 0, 0)")
        code, msg, md, deg = suggest(token)
        check("S6-预算拒绝", code != 0 and "BUDGET_EXCEEDED" in msg,
              f"code={code} msg={msg[:60]!r}")
        sql(f"DELETE FROM ai_model_budget WHERE model_config_id={MINIMAX_ID} "
            f"AND budget_month='{BUDGET_MONTH}' AND budget_tokens=1")

        # S7 失效配置拒绝：全部 is_active=0 → 不出站（服务设计契约是 degraded=true 降级应答
        # + 引导文案，非异常——AiSuggestionService:242-249；判定用 degraded+文案双契约，
        # 比 code≠0 更严：参数拒绝等其它 code≠0 路径不算 PASS）
        set_active("0")  # 目标 0 不存在 → 全部置 0
        code, msg, md, deg = suggest(token)
        check("S7-失效配置拒绝", deg and "未配置生效的 AI 模型" in md,
              f"code={code} degraded={deg} markdown_head={md[:40]!r}")
    finally:
        restore()

    print(f"\n== ledger 新增行（id>{n0}）==")
    print(ledger_after(n0))
    print(f"\n== 终态核对（应 MiniMax 唯一 active + 原 endpoint + 无自插预算行）==")
    print(sql(f"SELECT id, model_name, is_active, endpoint_url FROM ai_model_configs "
              f"WHERE is_active=1"))
    print(f"\n== 结果: PASS={passed} FAIL={failed} ==")
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
