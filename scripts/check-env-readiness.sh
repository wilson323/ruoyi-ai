#!/usr/bin/env bash
# 环境就绪协议一键自检（R轮 2026-09-28 login-single-track / env-readiness）
#
# 根除对象：操作契约靠人肉发现/凭记忆构造请求（实测踩坑：对平台 /auth/login 发
# {username,password} 得 code500「请求参数校验失败」——双契约错位；以及 runbook
# mvn -o 与本机 .m2 不符的文档失真）。把"起环境后的正确姿势"固化为会跑的资产。
#
# 契约裁决（owner 2026-09-28 拍板：禁止双轨、统一到占比多数的 IPD 契约）：
#   - 唯一密码登录契约：POST /api/v1/auth/login（Person 凭据 {username,password}，code0 包络）
#   - 平台 Sa-Token 票唯一签发口：POST /api/v1/auth/platform-token（IPD 会话换票，不走密码）
#   - POST /auth/login 已下线恒 410（哨兵 LoginContractSingleTrackTest 钉死）——
#     本脚本以负向对照复核：它必须 410，若复活即红（双轨再生）。
#   - clientid 契约（clientid-contract 2026-09-28）：基线 /system/** 鉴权头 clientid 必须等于
#     票内 extra 的 sys_client.client_id（UUID），由换票响应交付；"pc" 仅是登录查询键
#     client_key，绝不进鉴权头（历史教训：带 "pc" 被伪装成未登录 401 逼人猜契约）。
#
# 探针顺序 = 六路基础环境 → 统一登录 → 双轨负向对照 → 平台换票全链 → 管理域探针
# 退出码：0 全绿 / 1 有红项 / 2 用法或输入错（如凭据文件缺失）
# 凭据从 .codex/ipd-dev/config/auth-admin-current.json 读取，全程不打印。
set -u

BASE="${BASE:-http://127.0.0.1:16039}"
FE="${FE:-http://127.0.0.1:15666}"
CREDS="${CREDS:-/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/auth-admin-current.json}"
PASS=0; FAIL=0; TMP_TOK=""

ok()   { PASS=$((PASS+1)); printf 'PASS  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf 'FAIL  %s\n' "$1"; }

probe_port() { # name port
  if lsof -nP -iTCP:"$2" -sTCP:LISTEN >/dev/null 2>&1; then ok "$1 (:$2 LISTEN)"; else bad "$1 (:$2 无监听)"; fi
}

probe_http() { # name expected_codes url [curl_args...]
  local name="$1" want="$2" url="$3"; shift 3
  local code
  code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$@" "$url" 2>/dev/null)
  case " $want " in
    *" $code "*) ok "$name (HTTP $code)";;
    *) bad "$name (HTTP ${code:-000}，期望 $want)";;
  esac
}

echo "== 六路基础环境 =="
probe_port "MySQL"        13306
probe_port "IPD-Redis"    16379
probe_port "Weaviate"     28080
probe_port "MockEmbed"    8765
probe_port "Backend"      16039
probe_port "Frontend"     15666
probe_http "Weaviate ready"   "200" "http://127.0.0.1:28080/v1/.well-known/ready"
probe_http "BE health(authn)" "200 401" "$BASE/actuator/health"
probe_http "FE index"         "200" "$FE/"

echo "== 统一登录契约（唯一入口 POST /api/v1/auth/login）=="
if [ ! -f "$CREDS" ]; then
  bad "凭据文件缺失: $CREDS"; echo "RESULT PASS=$PASS FAIL=$FAIL"; exit 2
fi
LOGIN_JSON=$(python3 - "$CREDS" "$BASE" <<'PY'
import json, sys, urllib.request, urllib.error
creds, base = sys.argv[1], sys.argv[2]
c = json.load(open(creds))
body = json.dumps({"username": c["username"], "password": c["password"]}).encode()
req = urllib.request.Request(base + "/api/v1/auth/login", data=body,
                             headers={"Content-Type": "application/json"})
try:
    r = json.load(urllib.request.urlopen(req, timeout=10))
    tok = (r.get("data") or {}).get("token") or ""
    print(json.dumps({"code": r.get("code"), "token": tok}))
except urllib.error.HTTPError as e:
    print(json.dumps({"code": "http-" + str(e.code), "token": ""}))
except Exception as e:
    print(json.dumps({"code": "err-" + type(e).__name__, "token": ""}))
PY
)
LOGIN_CODE=$(printf '%s' "$LOGIN_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["code"])')
TMP_TOK=$(printf '%s' "$LOGIN_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
if [ "$LOGIN_CODE" = "0" ] && [ -n "$TMP_TOK" ]; then
  ok "IPD 登录 code=0 拿到会话（契约唯一入口真活）"
else
  bad "IPD 登录失败 code=${LOGIN_CODE}（契约或凭据问题）"
fi

echo "== 双轨负向对照（POST /auth/login 必须 410，复活即双轨再生）=="
GONE_BODY=$(curl -s -m 5 -w '\n%{http_code}' -X POST "$BASE/auth/login" -H 'Content-Type: application/json' -d '{}')
GONE_CODE=$(printf '%s' "$GONE_BODY" | tail -1)
GONE_TEXT=$(printf '%s' "$GONE_BODY" | head -1)
if [ "$GONE_CODE" = "410" ] && printf '%s' "$GONE_TEXT" | grep -q "login-single-track"; then
  ok "平台 /auth/login 恒 410 + 统一契约指引（双轨已封）"
else
  bad "平台 /auth/login 未按裁决下线（HTTP ${GONE_CODE}）——双轨回归，见 LoginContractSingleTrackTest"
fi

echo "== 平台换票全链（IPD 会话 → /api/v1/auth/platform-token → /system/* 管理域）=="
if [ -n "$TMP_TOK" ]; then
  CHAIN=$(python3 - "$BASE" "$TMP_TOK" <<'PY'
import json, sys, urllib.request, urllib.error
base, tok = sys.argv[1], sys.argv[2]
hdr = {"Authorization": "Bearer " + tok}
def post(path):
    req = urllib.request.Request(base + path, data=b"{}", headers={**hdr, "Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=10))
out = {}
try:
    r = post("/api/v1/auth/platform-token")
    d = r.get("data") or {}
    ptok = d.get("token") or ""
    cid = d.get("clientId") or ""
    out["platform_token"] = "ok" if ptok else "empty"
    out["clientid_delivered"] = "ok" if cid else "missing"
    if ptok and cid:
        req = urllib.request.Request(base + "/system/info/list?pageNum=1&pageSize=5",
                                     headers={"Authorization": "Bearer " + ptok, "clientid": cid})
        kb = json.load(urllib.request.urlopen(req, timeout=10))
        out["kb_list"] = "ok" if str(kb.get("code")) in ("0", "200") or "rows" in kb else "code=" + str(kb.get("code"))
except urllib.error.HTTPError as e:
    out.setdefault("error", "http-" + str(e.code))
except Exception as e:
    out.setdefault("error", type(e).__name__)
print(json.dumps(out))
PY
)
  PT=$(printf '%s' "$CHAIN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("platform_token","-"))')
  CID=$(printf '%s' "$CHAIN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("clientid_delivered","-"))')
  KB=$(printf '%s' "$CHAIN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("kb_list","-"))')
  ERR=$(printf '%s' "$CHAIN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("error",""))')
  if [ "$PT" = "ok" ]; then ok "换票签发平台票（不走密码直登）"; else bad "换票失败 PT=$PT $ERR"; fi
  if [ "$CID" = "ok" ]; then ok "换票响应交付 clientId（clientid-contract：鉴权头唯一权威值）"; else bad "换票响应缺 clientId（clientid-contract 破损，调用方只能解 JWT 猜）"; fi
  if [ "$KB" = "ok" ]; then ok "管理域 /system/info/list 经平台票可达（知识库 API 真活）"; else bad "管理域探针失败 KB=$KB $ERR"; fi
else
  bad "跳过换票链（无 IPD 会话）"
fi

echo "RESULT PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
