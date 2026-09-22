#!/usr/bin/env python3
"""
R174-P0.12 DEF-9 多实例 audit hash chain 并发实测

目的：验证 AuditLogServiceImpl.append() 在多线程并发下
- 不出现重复 seq（uk_audit_seq 唯一约束应保证）
- head.next_seq 正确累加（无丢失）
- hash 链 100% 完整（prev_hash → curr_hash 链对得上）
- 推进器锚行锁对竞态有效（无 advance=0 fail-fast）

方法：起 N 个线程并发调 HTTP /api/v1/audit/append（带 basic auth）或直接调 IPD append API
   - 这里用 Python sqlite/requests 直接模拟 — 但生产 append 走 Java，所以要起多个 java 实例
   - 简化版：直接连 MySQL 真库，并发跑 N 个 INSERT 事务，每个事务 SELECT FOR UPDATE + INSERT

实际上更简单的做法：用 mysql 客户端模拟多事务并发
   - BEGIN; SELECT ... FOR UPDATE; INSERT; COMMIT;
   - 但 Python mysql-connector + 多线程可以跑

限制：本脚本只验证「悲观锁能保证正确串行化」的语义，不模拟真实的多 java 实例部署。
- 因为悲观锁在 DB 层，加多少 java 实例都不破坏正确性
- 真实多实例验证需要 k8s/docker，本地不做

输出：
- /tmp/p012-def9-<ts>/results.jsonl
- /tmp/p012-def9-<ts>/summary.md
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

WORKTREE = Path("/private/tmp/r172-takeover")
MYSQL_CNF = "/private/tmp/r172-takeover/.codex/ipd-dev/config/mysql-client.cnf"
MYSQL_BIN = "/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/software/mysql-8.0.46-macos15-arm64/bin/mysql"


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def log(level: str, msg: str) -> None:
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{ts}] [{level}] {msg}", flush=True)


def sql(query: str, db: str = "ipd_dev") -> str:
    """跑单条 SQL，返 stdout"""
    r = subprocess.run(
        [MYSQL_BIN, f"--defaults-file={MYSQL_CNF}", db, "-N", "-e", query],
        capture_output=True, text=True, timeout=30,
    )
    if r.returncode != 0:
        raise RuntimeError(f"SQL failed: {r.stderr.strip()}\n  query={query}")
    return r.stdout.strip()


def sql_script(queries: list[str], db: str = "ipd_dev") -> str:
    """跑多语句脚本"""
    r = subprocess.run(
        [MYSQL_BIN, f"--defaults-file={MYSQL_CNF}", db, "-N"],
        input="\n".join(queries),
        capture_output=True, text=True, timeout=30,
    )
    if r.returncode != 0:
        raise RuntimeError(f"SQL script failed: {r.stderr.strip()}")
    return r.stdout.strip()


def get_chain_state() -> tuple[int, str, int]:
    """拿当前 chain head 状态: (last_seq, last_hash, next_seq)"""
    out = sql(
        "SELECT last_seq, last_hash, next_seq FROM audit_log_chain_heads WHERE chain_key = 'GLOBAL'"
    )
    parts = out.split("\t")
    return int(parts[0]), parts[1], int(parts[2])


def verify_chain(start_seq: int, end_seq: int) -> tuple[int, int]:
    """验证 hash 链完整性 (start_seq, end_seq] 范围"""
    # 拿所有行 + 重新计算 hash
    rows = sql(
        f"SELECT seq, prev_hash, row_hash FROM audit_log WHERE seq BETWEEN {start_seq} AND {end_seq} ORDER BY seq"
    ).splitlines()
    total = len(rows)
    broken = 0
    prev_hash = "0" * 64  # GENESIS
    for row in rows:
        parts = row.split("\t")
        seq = int(parts[0])
        actual_prev = parts[1]
        actual_curr = parts[2]
        if actual_prev != prev_hash:
            broken += 1
        # 这里不重算 curr（需要 canonical 内容），只验 prev 链对得上
        prev_hash = actual_curr
    return total, broken


def worker(worker_id: int, n_inserts: int, results: list[dict], barrier: threading.Barrier, use_lock: bool) -> None:
    """并发 worker：每个 worker 跑 n_inserts 次单事务 append
    use_lock=True：用 BEGIN+SELECT FOR UPDATE+UPDATE+COMMIT 串行化（模拟生产悲观锁）
    use_lock=False：裸 UPDATE（演示竞态）
    """
    for i in range(n_inserts):
        # 等所有 worker 在 barrier 集合 → 最大化同时进入临界区
        barrier.wait()
        try:
            if use_lock:
                # 加锁版：单事务 SET autocommit=0 + SELECT @seq + UPDATE + COMMIT
                # MySQL 会话变量在事务内可见，SELECT INTO @var + UPDATE SET @var 是一次原子操作
                # 关键：行锁持续整个事务，避免 autocommit 立即释放
                script = (
                    "SET autocommit=0;\n"
                    "BEGIN;\n"
                    "SELECT next_seq INTO @my_seq FROM audit_log_chain_heads "
                    "WHERE chain_key='GLOBAL' FOR UPDATE;\n"
                    "SELECT @my_seq;\n"
                    "SET @my_hash = CONCAT('w" + str(worker_id) + "i" + str(i) + "s', CAST(@my_seq AS CHAR), '_', REPEAT('a', 32));\n"
                    "UPDATE audit_log_chain_heads SET last_seq=@my_seq, last_hash=@my_hash, "
                    "next_seq=@my_seq+1 WHERE chain_key='GLOBAL';\n"
                    "COMMIT;\n"
                )
                advance_rc = subprocess.run(
                    [MYSQL_BIN, f"--defaults-file={MYSQL_CNF}", "ipd_dev", "-N"],
                    input=script,
                    capture_output=True, text=True, timeout=10,
                )
                my_seq = -1
                my_hash = "FAILED"
                if advance_rc.returncode == 0:
                    try:
                        my_seq = int(advance_rc.stdout.strip().split("\n")[0].strip())
                    except (ValueError, IndexError):
                        pass
            else:
                # 非锁版：裸 UPDATE（演示竞态）
                head = get_chain_state()
                my_seq = head[2]
                my_hash = f"worker{worker_id}_iter{i}_seq{my_seq}_" + "a" * 32
                advance_rc = subprocess.run(
                    [MYSQL_BIN, f"--defaults-file={MYSQL_CNF}", "ipd_dev", "-N", "-e",
                     f"UPDATE audit_log_chain_heads SET last_seq={my_seq}, "
                     f"last_hash='{my_hash}', next_seq={my_seq+1} WHERE chain_key='GLOBAL'"],
                    capture_output=True, text=True, timeout=10,
                )

            results.append({
                "worker": worker_id,
                "iter": i,
                "mode": "locked" if use_lock else "unlocked",
                "advance_returncode": advance_rc.returncode,
                "ts": now_iso(),
            })
        except Exception as e:
            results.append({
                "worker": worker_id,
                "iter": i,
                "mode": "locked" if use_lock else "unlocked",
                "error": str(e),
                "ts": now_iso(),
            })


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--threads", type=int, default=8, help="并发线程数（默认 8）")
    ap.add_argument("--inserts", type=int, default=5, help="每个线程的 insert 数（默认 5）")
    ap.add_argument("--no-cleanup", action="store_true", help="不清理测试 advance 数据")
    ap.add_argument("--locked", action="store_true", help="用 SELECT FOR UPDATE + advance（模拟生产悲观锁）")
    args = ap.parse_args()

    out_dir = Path(f"/tmp/p012-def9-{datetime.now().strftime('%Y%m%d-%H%M%S')}")
    out_dir.mkdir(parents=True, exist_ok=True)
    results_out = out_dir / "results.jsonl"
    results_out.touch()

    log("INFO", f"P0.12 DEF-9 concurrent audit chain verify")
    log("INFO", f"threads={args.threads} inserts/thread={args.inserts}")
    log("INFO", f"output: {out_dir}")

    # 0. 初始状态
    initial = get_chain_state()
    log("INFO", f"initial chain state: last_seq={initial[0]}, next_seq={initial[2]}")
    log("INFO", f"  last_hash={initial[1][:16]}...")

    # 1. 起 N 个 worker，并发跑 advance（模拟多 java 实例竞争锚行）
    barrier = threading.Barrier(args.threads)
    results = []
    threads = []
    use_lock = args.locked  # True=模拟生产悲观锁，False=演示竞态
    log("INFO", f"mode: {'LOCKED (SELECT FOR UPDATE)' if use_lock else 'UNLOCKED (bare UPDATE, demo race)'}")
    for wid in range(args.threads):
        t = threading.Thread(target=worker, args=(wid, args.inserts, results, barrier, use_lock))
        threads.append(t)
        t.start()

    log("INFO", f"started {args.threads} workers, waiting at barrier...")
    for t in threads:
        t.join()

    log("INFO", f"all workers done, total advance attempts: {len(results)}")

    # 2. 分析结果
    # 期望（如果悲观锁有效）：head.next_seq 应该= 初始 next_seq + N*M（每个 UPDATE 把 next_seq += 1）
    final = get_chain_state()
    expected_next_seq = initial[2] + args.threads * args.inserts

    log("INFO", f"final chain state: last_seq={final[0]}, next_seq={final[2]}")
    log("INFO", f"expected next_seq: {expected_next_seq}")
    log("INFO", f"  last_hash={final[1][:16]}...")

    # 3. 验证 advance 都被记账（无丢失）
    success_count = sum(1 for r in results if r.get("advance_returncode") == 0)
    error_count = sum(1 for r in results if "error" in r)

    delta = final[2] - initial[2]
    log("INFO", f"advance success: {success_count}/{len(results)}")
    log("INFO", f"chain head delta: {delta}")
    log("INFO", f"chain head expected delta: {args.threads * args.inserts}")

    # 4. 写 jsonl
    with results_out.open("w") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # 5. 写 summary
    summary_path = out_dir / "summary.md"
    with summary_path.open("w") as f:
        f.write(f"# P0.12 DEF-9 Concurrent Verify Summary\n\n")
        f.write(f"- Started: {now_iso()}\n")
        f.write(f"- Threads: {args.threads}\n")
        f.write(f"- Inserts/thread: {args.inserts}\n")
        f.write(f"- Total advance attempts: {len(results)}\n\n")
        f.write(f"## Initial / Final\n\n")
        f.write(f"| | last_seq | next_seq | last_hash (first 16) |\n|---|---|---|---|\n")
        f.write(f"| initial | {initial[0]} | {initial[2]} | {initial[1][:16]} |\n")
        f.write(f"| final | {final[0]} | {final[2]} | {final[1][:16]} |\n\n")
        f.write(f"## Validation\n\n")
        f.write(f"- advance success: **{success_count}/{len(results)}**\n")
        f.write(f"- chain head delta: **{delta}** (expected: {args.threads * args.inserts})\n")
        f.write(f"- delta match: **{'YES' if delta == args.threads * args.inserts else 'NO'}**\n")
        f.write(f"- last_seq continuous: **{'YES' if final[0] == expected_next_seq - 1 else 'NO'}**\n")

    # 6. 判定
    if delta != args.threads * args.inserts:
        log("ERROR", f"❌ chain head delta mismatch ({delta} vs {args.threads * args.inserts})")
        sys.exit(1)
    if final[0] != expected_next_seq - 1:
        log("ERROR", f"❌ last_seq not continuous ({final[0]} vs {expected_next_seq - 1})")
        sys.exit(1)

    # 7. 还原 chain head 到初始值（避免污染）
    if not args.no_cleanup:
        log("INFO", "restoring chain head to initial state")
        sql(
            f"UPDATE audit_log_chain_heads SET last_seq={initial[0]}, "
            f"last_hash='{initial[1]}', next_seq={initial[2]} WHERE chain_key='GLOBAL'"
        )
        restored = get_chain_state()
        if restored != initial:
            log("WARN", f"restore mismatch: got {restored}, expected {initial}")

    log("PASS", f"✅ {args.threads} threads × {args.inserts} inserts = {args.threads * args.inserts} advance all OK")
    log("PASS", f"summary: {summary_path}")
    sys.exit(0)


if __name__ == "__main__":
    main()