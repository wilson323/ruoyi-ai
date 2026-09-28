#!/usr/bin/env python3
"""OPS-03 签名客户端（最小复原，2026-09-27）。

背景与边界（如实登记，防误读为原件）：
- ops06-monitor.py 依赖本模块的 `NoRedirect` 与 `s3()`（OSS 只读探测：对 OPS-01 既有
  fixture 对象做签名 HEAD）。原 OPS-03 交付件**从未入库**（runbook「探测范围与真实边界」
  及历轮挂账均已登记），监测器因此在任何新 checkout 都无法启动（ModuleNotFoundError）。
- 本文件是按 ops06-monitor.py 的**实际调用面**（redirect 阻断 + SigV4 签名请求）做的最小
  复原，非失物找回；调用面之外的能力（备份/恢复/桶管理）一律不实现——OPS-03 原卡能力
  仍以原卡验收为准，本文件不代任何备份职责。
- 只读契约：monitor 仅调用 `s3("HEAD", bucket, key)`；本客户端不建桶、不写对象、不跟随
  3xx（NoRedirect 语义是演练判据之一，改跟随即破坏「阻止重定向」场景）。
- 凭据：仅在调用时从 native_env.credentials()（0600 守卫）读取 minio_access/minio_secret，
  不落盘、不打印、不进异常消息（runbook「日志不含凭证」验收要点）。
"""
import datetime as dt
import hashlib
import hmac
import urllib.error
import urllib.request

import native_env as env

MINIO_HOST = "127.0.0.1:19000"
REGION = "us-east-1"
SERVICE = "s3"
MAX_BODY = 65537


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """3xx 不跟随且立即失败：抛 RuntimeError（非 HTTPError/OSError），
    由 monitor.guard 落 UNKNOWN/PROBE_ERROR——「阻止重定向」演练判据要求
    服务端只收到首跳请求（test_redirect_is_not_followed 锁死本行为）。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("HTTP redirect blocked: " + str(code))


def _hmac(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def s3(method, bucket, key, body=b""):
    """对本地 MinIO 发 AWS SigV4 签名请求，返回 (http_code, body_bytes)。

    HEAD 无载荷（payload hash = SHA256(b"")）；签名材料每次调用现取，不缓存不外泄。
    HTTP 非 2xx 以状态码返回（不抛）；网络层异常才上抛，由 monitor 的分组件捕获兜住。
    """
    creds = env.credentials()
    access, secret = creds["minio_access"], creds["minio_secret"]
    now = dt.datetime.now(dt.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(body).hexdigest()
    canonical_uri = "/" + bucket + "/" + key
    canonical_headers = (
        "host:" + MINIO_HOST + "\n"
        + "x-amz-content-sha256:" + payload_hash + "\n"
        + "x-amz-date:" + amz_date + "\n"
    )
    signed_headers = "host;x-amz-content-sha256;x-amz-date"
    canonical_request = "\n".join(
        [method, canonical_uri, "", canonical_headers, signed_headers, payload_hash]
    )
    scope = date_stamp + "/" + REGION + "/" + SERVICE + "/aws4_request"
    string_to_sign = "\n".join([
        "AWS4-HMAC-SHA256", amz_date, scope,
        hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
    ])
    signing_key = _hmac(_hmac(_hmac(_hmac(("AWS4" + secret).encode("utf-8"), date_stamp), REGION), SERVICE), "aws4_request")
    signature = hmac.new(signing_key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    authorization = (
        "AWS4-HMAC-SHA256 Credential=" + access + "/" + scope
        + ", SignedHeaders=" + signed_headers + ", Signature=" + signature
    )
    request = urllib.request.Request("http://" + MINIO_HOST + canonical_uri, method=method)
    request.add_header("x-amz-date", amz_date)
    request.add_header("x-amz-content-sha256", payload_hash)
    request.add_header("Authorization", authorization)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect)
    try:
        with opener.open(request, timeout=30) as response:
            return response.status, response.read(MAX_BODY)
    except urllib.error.HTTPError as response:
        with response:
            return response.code, response.read(MAX_BODY)
