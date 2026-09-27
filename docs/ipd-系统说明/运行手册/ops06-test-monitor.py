#!/usr/bin/env python3
"""Deterministic OPS-06 fixtures and isolated loopback HTTP fault drills; no DB writes."""
import contextlib
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest import mock

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ops06_monitor", HERE / "ops06-monitor.py")
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)
SECRET = "do-not-emit-password-or-token"
TRACE = "0123456789abcdef0123456789abcdef"


def counts(**overrides):
    result = dict(total=5, failed=0, exhausted=0, retry=0, lease_expired=0, retry_overdue=0)
    return result | overrides


@contextlib.contextmanager
def server(handler):
    http = HTTPServer(("127.0.0.1", 0), handler)
    worker = threading.Thread(target=http.serve_forever, daemon=True)
    worker.start()
    try:
        yield http.server_port
    finally:
        http.shutdown()
        worker.join()
        http.server_close()


class LoopbackHandler(BaseHTTPRequestHandler):
    calls = []

    def log_message(self, *args):
        pass

    def do_GET(self):
        type(self).calls.append(self.path)
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", f"http://127.0.0.1:{self.server.server_port}/credential-sink")
        elif self.path == "/denied":
            self.send_response(403)
        else:
            self.send_response(503)
        self.end_headers()
        self.wfile.write(SECRET.encode())


class MonitorTests(unittest.TestCase):
    def test_one_failed_dependency_does_not_suppress_other_checks(self):
        funcs = ("db_health", "redis_health", "oss_health", "backend_health", "scheduler_health", "audit_health")
        with contextlib.ExitStack() as stack:
            mocks = [stack.enter_context(mock.patch.object(monitor, name,
                     return_value=monitor.result("UP", "FIXTURE"))) for name in funcs]
            mocks[0].side_effect = RuntimeError(SECRET)
            result = monitor.collect()
        self.assertEqual(result["status"], "PARTIAL")
        self.assertEqual(result["exit_code"], 3)
        self.assertEqual(result["checks"]["db"]["code"], "PROBE_ERROR")
        for probe in mocks:
            probe.assert_called_once()
        self.assertNotIn(SECRET, json.dumps(result))

    def test_alert_takes_precedence_over_unknown(self):
        with mock.patch.object(monitor, "guard", side_effect=[monitor.result("DOWN", "FIXTURE")]
                               + [monitor.result("UNKNOWN", "FIXTURE")] * 5):
            result = monitor.collect()
        self.assertEqual((result["status"], result["exit_code"]), ("ALERT", 2))

    def test_process_timeout_does_not_leak_command_or_stderr(self):
        def broken():
            raise subprocess.TimeoutExpired([SECRET], 1, output=SECRET, stderr=SECRET)
        result = monitor.guard(broken)
        self.assertEqual(result, monitor.result("DOWN", "PROBE_TIMEOUT"))

    def test_loopback_connection_refused(self):
        with socket.socket() as held:
            held.bind(("127.0.0.1", 0))
            port = held.getsockname()[1]  # bound but not listening: isolated refused connection
            result = monitor.guard(lambda: monitor.local_http("/health", port))
        self.assertEqual(result["code"], "CONNECTION_FAILED")

    def test_loopback_service_unavailable_and_error_body_not_reported(self):
        with server(LoopbackHandler) as port:
            original = monitor.local_http
            with mock.patch.object(monitor, "local_http", side_effect=lambda path, ignored: original(path, port)):
                result = monitor.guard(monitor.oss_health)
        self.assertEqual(result, monitor.result("DOWN", "OSS_LIVE_FAILED", http_status=503))
        self.assertNotIn(SECRET, json.dumps(result))

    def test_loopback_denied_status(self):
        with server(LoopbackHandler) as port:
            status, _ = monitor.local_http("/denied", port)
        self.assertEqual(status, 403)

    def test_redirect_is_not_followed(self):
        LoopbackHandler.calls = []
        with server(LoopbackHandler) as port:
            result = monitor.guard(lambda: monitor.local_http("/redirect", port))
        self.assertEqual(result["code"], "PROBE_ERROR")
        self.assertEqual(LoopbackHandler.calls, ["/redirect"])

    def test_retry_exhausted_is_alert(self):
        result = monitor.summarize_scheduler(counts(failed=1, exhausted=1))
        self.assertEqual(result["status"], "ALERT")
        self.assertEqual(result["counts"]["exhausted"], 1)

    def test_permanent_failure_is_not_labeled_exhausted(self):
        result = monitor.summarize_scheduler(counts(failed=1, exhausted=0))
        self.assertEqual(result["status"], "ALERT")
        self.assertEqual(result["counts"]["exhausted"], 0)

    def test_expired_claim_and_overdue_retry_are_actionable(self):
        for field in ["lease_expired", "retry_overdue"]:
            with self.subTest(field=field):
                self.assertEqual(monitor.summarize_scheduler(counts(**{field: 1}))["status"], "ALERT")

    def test_zero_tasks_cannot_prove_scheduler_health(self):
        self.assertEqual(monitor.summarize_scheduler(counts(total=0))["code"], "SCHEDULER_NO_DATA")

    def test_success_rows_do_not_prove_consumer_liveness(self):
        result = monitor.summarize_scheduler(counts())
        self.assertEqual(result["status"], "OBSERVED_CLEAR")
        self.assertEqual(result["consumer_liveness"], "NOT_VERIFIED")

    def test_missing_schema_not_mistaken_for_idle(self):
        with mock.patch.object(monitor, "mysql", return_value={"visible_tables": 0}) as query:
            result = monitor.scheduler_health()
        query.assert_called_once()
        self.assertEqual(result["code"], "SCHEDULER_SCHEMA_NOT_VISIBLE_OR_MISSING")

    def test_redis_uses_ping_only_and_noauth_is_down(self):
        with mock.patch.object(monitor.env, "redis", return_value="NOAUTH " + SECRET) as redis:
            result = monitor.redis_health()
        redis.assert_called_once_with("PING")
        self.assertEqual(result["status"], "DOWN")
        self.assertNotIn(SECRET, json.dumps(result))

    def test_signed_oss_probe_is_head_only(self):
        with mock.patch.object(monitor, "local_http", return_value=(200, b"")), \
             mock.patch.object(Path, "is_file", return_value=True), \
             mock.patch.object(Path, "read_text", return_value=json.dumps({"bucket": "ipd-env-probe-0123abcd"})), \
             mock.patch.object(monitor.backup, "s3", return_value=(403, SECRET.encode())) as s3:
            result = monitor.oss_health()
        s3.assert_called_once_with("HEAD", "ipd-env-probe-0123abcd", "probe.txt")
        self.assertEqual(result["status"], "DOWN")
        self.assertNotIn(SECRET, json.dumps(result))

    def scan(self, text):
        with tempfile.TemporaryDirectory(prefix="ops06-log-") as directory:
            path = Path(directory) / "fixture.log"
            path.write_text(text)
            return monitor.scan_audit_log(path)

    def test_audit_failure_trace_and_no_raw_exception(self):
        result = self.scan("2026-09-05 10:00:00 ERROR [IPD] 未捕获异常 traceId=" + TRACE + "\n"
                           + "java.lang.Exception: " + SECRET + "\n"
                           + " at org.ruoyi.ipd.service.AuditLogService.append(AuditLogService.java:66)\n")
        self.assertEqual(result["event_count"], 1)
        self.assertEqual(result["events"][0]["trace_id"], TRACE)
        self.assertNotIn(SECRET, json.dumps(result))

    def test_audit_malicious_trace_is_not_echoed(self):
        result = self.scan("2026-09-05 10:00:00 ERROR [IPD] 未捕获异常 traceId=" + SECRET + "\n"
                           + " at org.ruoyi.ipd.service.AuditLogService.append(X.java:1)\n")
        self.assertEqual(result["event_count"], 1)
        self.assertIsNone(result["events"][0]["trace_id"])
        self.assertNotIn(SECRET, json.dumps(result))

    def test_trace_does_not_cross_another_log_event(self):
        result = self.scan("2026-09-05 10:00:00 ERROR [IPD] 未捕获异常 traceId=" + TRACE + "\n"
                           + "2026-09-05 10:00:01 INFO another event\n"
                           + " at org.ruoyi.ipd.service.AuditLogService.append(X.java:1)\n")
        self.assertEqual(result["event_count"], 0)

    def test_tail_scan_bounded_and_reports_truncation(self):
        result = self.scan(("safe line\n" * 200000))
        self.assertLessEqual(result["window_bytes"], monitor.MAX_LOG_BYTES)
        self.assertTrue(result["history_truncated"])

    def test_audit_records_and_empty_log_never_green(self):
        with mock.patch.object(monitor, "mysql", return_value={"record_count": 100}), \
             mock.patch.object(monitor, "scan_audit_log", return_value={"event_count": 0}):
            result = monitor.audit_health()
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertEqual(result["code"], "AUDIT_TRACE_NOT_WIRED")

    def test_mysql_uses_app_identity_and_read_only_transaction(self):
        config = "[client]\nuser=ipd_app\nhost=127.0.0.1\nport=13306\nprotocol=TCP\npassword=" + SECRET
        with tempfile.TemporaryDirectory(prefix="ops06-client-") as directory:
            path = Path(directory) / "mysql-app.cnf"
            path.write_text(config); path.chmod(0o600)
            with mock.patch.object(monitor.env, "CFG", Path(directory)), \
                 mock.patch.object(monitor.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, '{"ok":1}', '')) as run:
                self.assertEqual(monitor.mysql("SELECT JSON_OBJECT('ok',1)"), {"ok": 1})
            args, kwargs = run.call_args
            self.assertNotIn(SECRET, str(args))
            self.assertIn("READ ONLY", kwargs["input"])
            self.assertTrue(kwargs["input"].endswith("ROLLBACK;"))
            with mock.patch.object(monitor.env, "CFG", Path(directory)):
                self.assertEqual(monitor.guard(lambda: monitor.mysql("DELETE FROM audit_logs"))["code"], "QUERY_NOT_ALLOWED")
                path.chmod(0o644)
                self.assertEqual(monitor.guard(lambda: monitor.mysql("SELECT 1"))["code"], "APP_CONFIG_NOT_PRIVATE")

    def test_evidence_file_not_overwritten(self):
        with tempfile.TemporaryDirectory(prefix="ops06-output-") as directory:
            path = Path(directory) / "exists.json"
            path.write_text("original")
            with mock.patch.object(sys, "argv", ["ops06-monitor", "--output", str(path)]), \
                 mock.patch.object(monitor, "collect", return_value={"exit_code": 3}):
                with self.assertRaises(FileExistsError):
                    monitor.main()
            self.assertEqual(path.read_text(), "original")


if __name__ == "__main__":
    unittest.main(verbosity=2)
