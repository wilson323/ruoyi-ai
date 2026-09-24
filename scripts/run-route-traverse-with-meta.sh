#!/usr/bin/env bash
# scripts/run-route-traverse-with-meta.sh
# ----------------------------------------------------------------------
# 前端全量路由可见性遍历的「可复现包装」（R212-根因 RC-4 根除，2026-09-24）
#
# 为什么需要：前端仓 scripts/r212-route-visibility-traverse.mjs 取参链路失效
#   （localStorage 里取到的不是 IPD 会话 token → GET /api/v1/projects 返回 20001 → lst=0），
#   参数静默落到硬编码兜底 projectId=9140004（projects 表无此行，bonus_pools 悬空同源，卡 15d5e689）。
#   R212 首跑（12:38Z）靠环境变量注入有效 id 得 62 ok / 2 err，复跑（13:03Z）未注入得 52 / 12，
#   两次结果不可比却被当成同口径对比。本包装强制钉参 + 预检 + 元数据入结果，使遍历结果可复现、可比。
#
# 做什么：
#   1. 强制要求 R212_USER / R212_PROJECT_ID / R212_PRODUCT_ID / R212_PASS / OUT_TAG（缺一 exit 2，不再允许兜底）
#   2. 真库只读预检：projects / products 中该 id 必须存在（凭证走 .codex/ipd-dev/config/mysql-client.cnf，不上命令行）
#   3. 在 mktemp 目录作 cwd 运行前端脚本（不覆盖前端仓 scripts/r212-traverse-result.json，不改其遍历逻辑）
#   4. 输出单文件 {meta, summary, results} 到证据目录；meta 含账号/角色/参数/双仓 HEAD/采集时间/路由清单 sha256/取参是否自动成功
#   5. 日志若含密码明文则 exit 3 并删除产物
#
# 用法：
#   cd /Users/mac/Documents/ruoyi-ai && R212_USER=ipd-admin R212_PASS=<env,勿入库> \
#     R212_PROJECT_ID=<真库存在的项目id> R212_PRODUCT_ID=<真库存在的产品id> \
#     OUT_DIR=docs/ipd-系统说明/验收/<轮次目录> OUT_TAG=pinned-20260924 \
#     bash scripts/run-route-traverse-with-meta.sh
#
# 退出码：0 = 遍历完成并落证据；2 = 参数/预检失败；3 = 泄密防护触发；其他 = 遍历脚本失败
# 撞车 0：只读（浏览器只导航不点写按钮；SQL 只 SELECT）；scripts/ 白名单。

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FE_REPO="${FE_REPO:-/Users/mac/Documents/ruoyi-ipd-web}"
TRAVERSE="$FE_REPO/scripts/r212-route-visibility-traverse.mjs"
ROUTES_FILE="/tmp/r212_l3_routes.json"
MYSQL_BIN="${MYSQL_BIN:-$(command -v mysql || echo /opt/homebrew/bin/mysql)}"
MYSQL_CNF="$REPO/.codex/ipd-dev/config/mysql-client.cnf"

missing=()
for v in R212_USER R212_PASS R212_PROJECT_ID R212_PRODUCT_ID OUT_DIR OUT_TAG; do
  [[ -z "${!v:-}" ]] && missing+=("$v")
done
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "[ERROR] 缺少必填环境变量: ${missing[*]}（本包装禁止参数兜底）" >&2
  exit 2
fi
[[ -f "$TRAVERSE" ]] || { echo "[ERROR] 遍历脚本不存在: $TRAVERSE" >&2; exit 2; }
[[ -f "$ROUTES_FILE" ]] || { echo "[ERROR] 路由清单不存在: $ROUTES_FILE（先跑 r212_l3.py 生成）" >&2; exit 2; }
[[ "$R212_PROJECT_ID" =~ ^[0-9]+$ && "$R212_PRODUCT_ID" =~ ^[0-9]+$ ]] || { echo "[ERROR] id 必须为纯数字" >&2; exit 2; }

sql_count() {
  "$MYSQL_BIN" --defaults-extra-file="$MYSQL_CNF" ipd_dev -N -e "$1" 2>/dev/null | tail -1
}
p_cnt="$(sql_count "SELECT COUNT(*) FROM projects WHERE id=$R212_PROJECT_ID")"
d_cnt="$(sql_count "SELECT COUNT(*) FROM products WHERE id=$R212_PRODUCT_ID")"
if [[ "$p_cnt" != "1" || "$d_cnt" != "1" ]]; then
  echo "[ERROR] 预检失败：projects.id=$R212_PROJECT_ID 行数=${p_cnt:-?}，products.id=$R212_PRODUCT_ID 行数=${d_cnt:-?}（必须各为 1）" >&2
  exit 2
fi
role="$(sql_count "SELECT person_type FROM persons WHERE username='${R212_USER//\'/}' LIMIT 1")"

mkdir -p "$REPO/$OUT_DIR"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/scripts"

be_head="$(cd "$REPO" && git rev-parse --short=8 HEAD)"
fe_head="$(cd "$FE_REPO" && git rev-parse --short=7 HEAD)"
be_dirty="$(cd "$REPO" && git status --porcelain | wc -l | tr -d ' ')"
fe_dirty="$(cd "$FE_REPO" && git status --porcelain | wc -l | tr -d ' ')"
routes_sha="$(shasum -a 256 "$ROUTES_FILE" | cut -c1-16)"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

set +e
(cd "$WORK" && node "$TRAVERSE") > "$WORK/traverse.log" 2>&1
rc=$?
set -e

if grep -qF -- "$R212_PASS" "$WORK/traverse.log" "$WORK/scripts/r212-traverse-result.json" 2>/dev/null; then
  echo "[ERROR] 产物含密码明文，已丢弃" >&2
  exit 3
fi
[[ $rc -eq 0 && -f "$WORK/scripts/r212-traverse-result.json" ]] || { echo "[ERROR] 遍历失败 rc=$rc"; tail -20 "$WORK/traverse.log"; exit "$rc"; }

OUT_JSON="$REPO/$OUT_DIR/traverse-$OUT_TAG.json"
OUT_LOG="$REPO/$OUT_DIR/traverse-$OUT_TAG.log"
cp "$WORK/traverse.log" "$OUT_LOG"

META_USER="$R212_USER" META_ROLE="$role" META_PID="$R212_PROJECT_ID" META_DID="$R212_PRODUCT_ID" \
META_BE="$be_head" META_FE="$fe_head" META_BED="$be_dirty" META_FED="$fe_dirty" \
META_SHA="$routes_sha" META_START="$started_at" \
python3 - "$WORK/scripts/r212-traverse-result.json" "$WORK/traverse.log" "$OUT_JSON" <<'PY'
import json, os, re, sys, datetime
src, log, out = sys.argv[1:4]
data = json.load(open(src))
m = re.search(r"\[resolveParams\] projects code=(\S+) lst=(\d+)", open(log).read())
e = os.environ
meta = {
    "user": e["META_USER"], "role": e["META_ROLE"] or None,
    "projectId": e["META_PID"], "productId": e["META_DID"], "paramsPinned": True,
    "resolveParamsAuto": {"projectsCode": m.group(1) if m else None, "listSize": int(m.group(2)) if m else None,
                          "ok": bool(m and m.group(1) == "0" and int(m.group(2)) > 0)},
    "beHead": e["META_BE"], "feHead": e["META_FE"],
    "beDirtyFiles": int(e["META_BED"]), "feDirtyFiles": int(e["META_FED"]),
    "routesSha256_16": e["META_SHA"], "startedAt": e["META_START"],
    "collectedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "command": "scripts/run-route-traverse-with-meta.sh → ruoyi-ipd-web/scripts/r212-route-visibility-traverse.mjs",
}
json.dump({"meta": meta, "summary": data["summary"], "results": data["results"]}, open(out, "w"), ensure_ascii=False, indent=1)
s = data["summary"]
print(f"[OK] {out}\n  ok={s['ok']} total={s['total']} withErrors={len(s['withErrors'])} whiteScreens={len(s['whiteScreens'])} "
      f"projectId={meta['projectId']} beHead={meta['beHead']} feHead={meta['feHead']} autoParams={meta['resolveParamsAuto']['ok']}")
PY
