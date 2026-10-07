#!/usr/bin/env python3
"""Weaviate -> Qdrant 存量向量迁移（IPD 知识库 kid=2097275993856180226）。

背景：应用已切到 Qdrant（commit fc578ff2，owner 选 B），但 Qdrant 是空库；
存量 309 条向量在 Weaviate 的 LocalKnowledge2097275993856180226 类里。
本脚本按应用读取路径的结构要求迁移（依据 QdrantVectorStoreStrategy.java）：

- collection 名 = collectionname + kid，即 LocalKnowledge2097275993856180226
  （QdrantVectorStoreStrategy.java:88/116/161/201）
- size=1024 / Distance.Cosine / 单默认向量，无命名向量（:93-97, :133-134）
- payload 键名改写：text -> text_segment、docId -> doc_id
  （常量定义 :48-51；写入 :135-138；检索读取 :183/224/230/236）
- 其余 12 个驼峰键原样保留（buildFragmentPayload，:139-142 汇入）
- 向量原值搬运：存量已是单位向量（L2=1.0），禁止再缩放/归一化

只读 Weaviate + 写 Qdrant（REST 16333 与 gRPC 16334 是同一实例）。
幂等：同 UUID 覆盖式 upsert，可重复执行；--dry-run 只拉取校验不写。

用法：
  python3 scripts/migrate-weaviate-to-qdrant.py --dry-run
  python3 scripts/migrate-weaviate-to-qdrant.py
"""

import json
import sys
import urllib.error
import urllib.request
import uuid

WEAVIATE = "http://127.0.0.1:28080"
QDRANT = "http://127.0.0.1:16333"
CLASS = "LocalKnowledge2097275993856180226"
KID = "2097275993856180226"
COLLECTION = "LocalKnowledge" + KID
DIM = 1024
BATCH = 50

PAYLOAD_KEYS = (
    "text", "fid", "kid", "docId", "scopeType", "groupId", "projectId",
    "ownerPersonId", "ownerAgentId", "sensitivity", "embeddingModel", "embeddingDim",
)


def http(method, url, body=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        url, data=data, method=method, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as err:
        detail = err.read().decode(errors="replace")
        raise SystemExit("HTTP %s %s -> %s %s" % (method, url, err.code, detail[:500]))


def fetch_all():
    query = (
        "{ Get { %s(limit: 500) { %s _additional { id vector } } } }"
        % (CLASS, " ".join(PAYLOAD_KEYS))
    )
    resp = http("POST", WEAVIATE + "/v1/graphql", {"query": query})
    if "errors" in resp:
        raise SystemExit("Weaviate GraphQL 错误: %s" % json.dumps(resp["errors"])[:500])
    return resp["data"]["Get"][CLASS]


def build_points(objects):
    missing = [o for o in objects if not o.get("text") or not o.get("docId")]
    if missing:
        raise SystemExit("存在 text/docId 为空的对象 %d 条，拒绝迁移（先人工检查）" % len(missing))
    bad_dim = [o for o in objects if len(o["_additional"]["vector"]) != DIM]
    if bad_dim:
        raise SystemExit("存在维度 != %d 的对象 %d 条，拒绝迁移" % (DIM, len(bad_dim)))
    kids = {o.get("kid") for o in objects}
    if kids != {KID}:
        raise SystemExit("kid 集合与预期不符: %s" % kids)
    points = []
    for o in objects:
        point_id = o["_additional"]["id"]
        uuid.UUID(point_id)
        payload = {}
        for key in PAYLOAD_KEYS:
            value = o.get(key)
            if value is not None:
                payload[key] = value
        payload["text_segment"] = o["text"]
        payload["doc_id"] = o["docId"]
        points.append({"id": point_id, "vector": o["_additional"]["vector"], "payload": payload})
    return points


def main():
    dry = "--dry-run" in sys.argv
    objects = fetch_all()
    print("Weaviate 拉取: %d 条" % len(objects))
    points = build_points(objects)
    doc_ids = {p["payload"]["doc_id"] for p in points}
    print("校验通过: 维度=%d, kid=%s, UUID 合法, 覆盖 %d 个 docId" % (DIM, KID, len(doc_ids)))
    print("样例 payload 键: %s" % sorted(points[0]["payload"].keys()))

    if dry:
        print("dry-run: 不写 Qdrant，退出。")
        return

    try:
        http("GET", "%s/collections/%s" % (QDRANT, COLLECTION))
        print("collection 已存在，跳过创建: %s" % COLLECTION)
    except SystemExit:
        http("PUT", "%s/collections/%s" % (QDRANT, COLLECTION),
             {"vectors": {"size": DIM, "distance": "Cosine"}})
        print("collection 创建完成: %s (size=%d, Cosine)" % (COLLECTION, DIM))

    for start in range(0, len(points), BATCH):
        batch = points[start:start + BATCH]
        resp = http("PUT",
                    "%s/collections/%s/points?wait=true" % (QDRANT, COLLECTION),
                    {"points": batch}, timeout=180)
        print("upsert %d..%d: %s" % (start, start + len(batch) - 1, resp.get("status")))

    info = http("GET", "%s/collections/%s" % (QDRANT, COLLECTION))
    count = info["result"]["points_count"]
    print("Qdrant 回读 points_count=%s（期望 %d）" % (count, len(points)))
    if count != len(points):
        raise SystemExit("回读数量不一致，迁移未完成")
    print("迁移完成。")


if __name__ == "__main__":
    main()
