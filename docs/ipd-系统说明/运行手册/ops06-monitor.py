#!/usr/bin/env python3
"""OPS-06: bounded, read-only checks of this checkout's loopback development runtime."""
import argparse
import configparser
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import urllib.error
import urllib.request

# Imported OPS helpers must not write shared __pycache__ or execute their CLI paths.
sys.dont_write_bytecode = True
import native_env as env
import ops03_backup as backup

MAX_LOG_BYTES = 1024 * 1024


class ProbeError(Exception):
    """Contains a constant machine code, never an exception message from a dependency."""


def result(status, code, **details):
    return {"status": status, "code": code, **details}


def guard(check):
    try:
        return check()
    except ProbeError as error:
        return result("UNKNOWN", error.args[0])
    except (TimeoutError, socket.timeout, subprocess.TimeoutExpired):
        return result("DOWN", "PROBE_TIMEOUT")
    except urllib.error.HTTPError as error:
        return result("DOWN", "HTTP_ERROR", http_status=error.code)
    except urllib.error.URLError:
        return result("DOWN", "CONNECTION_FAILED")
    except (ConnectionError, OSError):
        return result("UNKNOWN", "LOCAL_IO_OR_CONNECTION_FAILED")
    except Exception:
        # Never serialize exception messages, process arguments or raw stdout/stderr.
        return result("UNKNOWN", "PROBE_ERROR")


def mysql(select):
    """Reuse the installed client and app config; no migrator helper/log writes."""
    config = env.CFG / "mysql-app.cnf"
    if config.is_symlink() or config.stat().st_mode & 0o077:
        raise ProbeError("APP_CONFIG_NOT_PRIVATE")
    parser = configparser.ConfigParser(interpolation=None)
    parser.read(config)
    client = parser["client"]
    expected = {"user": "ipd_app", "host": "127.0.0.1", "port": "13306", "protocol": "TCP"}
    if any(client.get(key) != value for key, value in expected.items()):
        raise ProbeError("APP_CONFIG_TARGET_MISMATCH")
    # All callers use constant SELECTs. A read-only transaction is an additional guard.
    if not select.lstrip().upper().startswith("SELECT") or ";" in select:
        raise ProbeError("QUERY_NOT_ALLOWED")
    command = [str(env.MYSQL / "bin/mysql"), f"--defaults-file={config}",
               "--connect-timeout=3", "--batch", "--raw", "--skip-column-names", "ipd_dev"]
    query = ("SET SESSION time_zone='+08:00'; SET SESSION max_execution_time=5000; "
             "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY; " + select + "; ROLLBACK;")
    completed = subprocess.run(command, input=query, text=True, capture_output=True, timeout=10)
    if completed.returncode:
        match = re.search(r"ERROR (\d{4})", completed.stderr)
        code = match.group(1) if match else "UNCLASSIFIED"
        if code in {"2002", "2003", "2013"}:
            return {"connection_error": True}
        raise ProbeError("MYSQL_" + code)
    return json.loads(completed.stdout)


def db_health():
    row = mysql("SELECT JSON_OBJECT('identity',CURRENT_USER(),'database',DATABASE(),"
                "'port',@@port,'value',1)")
    if row.get("connection_error"):
        return result("DOWN", "MYSQL_CONNECTION_FAILED")
    if row != {"identity": "ipd_app@127.0.0.1", "database": "ipd_dev", "port": 13306, "value": 1}:
        return result("UNKNOWN", "DB_IDENTITY_MISMATCH")
    return result("UP", "READ_ONLY_QUERY_OK", identity=row["identity"], database="ipd_dev", port=13306)


def redis_health():
    answer = env.redis("PING")
    return result("UP", "PONG") if answer == "PONG" else result("DOWN", "REDIS_PING_REJECTED")


def local_http(path, port):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), backup.NoRedirect)
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method="GET")
    try:
        with opener.open(request, timeout=3) as response:
            return response.status, response.read(65537)
    except urllib.error.HTTPError as response:
        with response:
            return response.code, response.read(65537)


def oss_health():
    status, _ = local_http("/minio/health/live", 19000)
    if status != 200:
        return result("DOWN", "OSS_LIVE_FAILED", http_status=status)
    # Read only an existing OPS-01 object; never invoke fixtures(), PUT, or bucket creation.
    marker = env.BASE / "evidence/probe-fixture.json"
    if not marker.is_file():
        return result("UNKNOWN", "OSS_FIXTURE_NOT_AVAILABLE", live_http_status=status)
    bucket = json.loads(marker.read_text())["bucket"]
    if not re.fullmatch(r"ipd-env-probe-[a-f0-9]{8}", bucket):
        raise ProbeError("OSS_FIXTURE_TARGET_MISMATCH")
    code, _ = backup.s3("HEAD", bucket, "probe.txt")
    return result("UP" if code == 200 else "DOWN", "SIGNED_HEAD_OK" if code == 200 else "SIGNED_HEAD_FAILED",
                  live_http_status=status, signed_head_http_status=code,
                  identity_scope="local infrastructure identity; application OSS identity NOT_VERIFIED")


def backend_health():
    code, body = local_http("/api/v1/auth/me", 16039)
    if len(body) > 65536:
        raise ProbeError("HTTP_BODY_TOO_LARGE")
    response = json.loads(body)
    if code != 401 or response.get("code") != 20001:
        return result("DOWN", "AUTH_BOUNDARY_UNEXPECTED", http_status=code)
    return result("UP", "ANONYMOUS_AUTH_BOUNDARY_OK", http_status=code,
                  scope="authentication boundary only; no successful business operation tested")


def summarize_scheduler(row):
    if row.get("connection_error"):
        return result("DOWN", "MYSQL_CONNECTION_FAILED")
    total = row["total"]
    if not isinstance(total, int) or total < 0:
        raise ProbeError("SCHEDULER_DATA_INVALID")
    if not total:
        return result("UNKNOWN", "SCHEDULER_NO_DATA", counts=row)
    if row["exhausted"] or row["failed"] or row["lease_expired"] or row["retry_overdue"]:
        return result("ALERT", "SCHEDULER_ACTION_REQUIRED", counts=row)
    return result("OBSERVED_CLEAR", "NO_FAILURE_IN_SNAPSHOT", counts=row,
                  consumer_liveness="NOT_VERIFIED")


def scheduler_health():
    visibility = mysql("SELECT JSON_OBJECT('visible_tables',COUNT(*)) FROM information_schema.tables "
                       "WHERE table_schema=DATABASE() AND table_name IN "
                       "('ipd_scheduled_tasks','ipd_task_attempts','ipd_task_schedules')")
    if visibility.get("connection_error"):
        return result("DOWN", "MYSQL_CONNECTION_FAILED")
    if visibility["visible_tables"] != 3:
        return result("UNKNOWN", "SCHEDULER_SCHEMA_NOT_VISIBLE_OR_MISSING",
                      visible_tables=visibility["visible_tables"], expected_tables=3)
    # MySQL wall-clock fields are Shanghai time, matching the OPS-04 contract.
    row = mysql("SELECT JSON_OBJECT('total',COUNT(*),'failed',COALESCE(SUM(state='FAILED'),0),"
                "'exhausted',COALESCE(SUM(state='FAILED' AND attempts>=4),0),"
                "'retry',COALESCE(SUM(state='RETRY'),0),"
                "'lease_expired',COALESCE(SUM(state='RUNNING' AND lease_until<NOW()),0),"
                "'retry_overdue',COALESCE(SUM(state='RETRY' AND next_attempt_at<DATE_SUB(NOW(),INTERVAL 5 MINUTE)),0)) "
                "FROM ipd_scheduled_tasks")
    return summarize_scheduler(row)


def scan_audit_log(path):
    """Recognize existing exception blocks without echoing stack traces or arbitrary IDs."""
    with path.open("rb") as stream:
        before = os.fstat(stream.fileno())
        start = max(0, before.st_size - MAX_LOG_BYTES)
        stream.seek(start)
        content = stream.read(MAX_LOG_BYTES)
        after = os.fstat(stream.fileno())
    if start:
        first_newline = content.find(b"\n")
        consumed = first_newline + 1 if first_newline >= 0 else len(content)
        content = content[consumed:]  # discard incomplete first line, with bounded IO
        start += consumed
    text = content.decode("utf-8", errors="replace")
    events, current = [], None
    header = re.compile(r"^\x1b\[[0-9;]*m|\x1b\[[0-9;]*m")
    timestamp = re.compile(r"^\d{4}-\d\d-\d\d[ T]\d\d:\d\d:\d\d")
    trace = re.compile(r"traceId=([0-9a-fA-F]{32}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})(?=\s|$)")
    for index, line in enumerate(text.splitlines(), 1):
        line = header.sub("", line)
        if timestamp.match(line):
            current = None
        if "[IPD] 未捕获异常 traceId=" in line:
            found = trace.search(line)
            current = {"line_in_window": index, "trace_id": found.group(1) if found else None,
                       "audit_failure": False}
        if current and index - current["line_in_window"] <= 100 and (
                "org.ruoyi.ipd.service.AuditLogService" in line or
                re.search(r"审计(?:链头|追加|写入|序号).*(?:失败|不一致|冲突|耗尽|未初始化|未写入)", line)):
            if not current["audit_failure"]:
                current["audit_failure"] = True
                events.append({"line_in_window": current["line_in_window"],
                               "trace_id": current["trace_id"],
                               "trace_lookup": "LOG_ONLY" if current["trace_id"] else "UNAVAILABLE"})
    return {"events": events[-20:], "event_count": len(events), "sample_limit": 20,
            "sample_truncated": len(events) > 20, "window_bytes": len(content),
            "window_start_byte": start, "window_sha256": hashlib.sha256(content).hexdigest(),
            "snapshot_changed_during_read": before.st_size != after.st_size or before.st_mtime_ns != after.st_mtime_ns,
            "history_truncated": start > 0}


def audit_health():
    # A success-row count is context only; failed append transactions need not leave a row.
    rows = mysql("SELECT JSON_OBJECT('record_count',COUNT(*),'latest_record_time',MAX(create_time)) FROM audit_logs")
    if rows.get("connection_error"):
        return result("DOWN", "MYSQL_CONNECTION_FAILED")
    log = scan_audit_log(env.BASE / "logs/backend.log")
    return result("ALERT" if log["event_count"] else "UNKNOWN",
                  "AUDIT_FAILURE_OBSERVED" if log["event_count"] else "AUDIT_TRACE_NOT_WIRED",
                  database_snapshot=rows, log_observation=log,
                  coverage="PARTIAL: ServiceException has no audit-failure log; absence cannot establish health",
                  trace_correlation="HTTP response to log correlation NOT_VERIFIED")


def collect():
    checks = {name: guard(check) for name, check in (
        ("db", db_health), ("redis", redis_health), ("oss", oss_health),
        ("backend", backend_health), ("scheduler", scheduler_health), ("audit", audit_health))}
    actionable = any(item["status"] in {"DOWN", "ALERT"} for item in checks.values())
    incomplete = any(item["status"] in {"UNKNOWN", "OBSERVED_CLEAR"} for item in checks.values())
    return {"schema_version": 1, "card": "OPS-06", "mode": "live_read_only",
            "recorded_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "status": "ALERT" if actionable else "PARTIAL" if incomplete else "HEALTHY",
            "checks": checks, "delivery": "LOCAL_JSON_AND_EXIT_CODE; external alert NOT_SENT",
            "exit_code": 2 if actionable else 3 if incomplete else 0,
            "boundaries": ["No database mutation, Redis mutation, OSS mutation or service control",
                           "No business scheduler/trace wiring is inferred from a healthy dependency",
                           "One snapshot, not an installed daemon; no log rotation history or audit hash-chain verification"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Create a new JSON file; never overwrite existing evidence")
    args = parser.parse_args()
    report = collect()
    data = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        # Parent creation is deliberately left to the caller; no automatic directory changes.
        with os.fdopen(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            stream.write(data)
    print(data, end="")
    return report["exit_code"]


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        print(json.dumps(result("UNKNOWN", "MONITOR_INPUT_OR_OUTPUT_ERROR")))
        sys.exit(3)
