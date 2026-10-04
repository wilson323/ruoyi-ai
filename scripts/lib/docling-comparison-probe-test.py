#!/usr/bin/env python3
"""Local HTTP contract tests; no Docling install or business data required."""
import importlib.util
import json
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import tempfile
import threading
import time
import unittest

spec = importlib.util.spec_from_file_location('probe', Path(__file__).with_name('docling-comparison-probe.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

class ContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.source = Path(self.temp.name) / 'private.pdf'
        self.source.write_bytes(b'%PDF-1.4 private-source')
        self.status = 200
        self.delay = 0
        self.payload = {'status': 'success', 'errors': [], 'processing_time': 0.01,
                        'document': {'filename': 'source.pdf', 'md_content': 'private正文 42.13亿元',
                                     'json_content': {'pages': {'1': {}}, 'texts': [{}], 'tables': [{}]}}}
        self.requests = []
        case = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                case.requests.append((self.path, self.rfile.read(int(self.headers['Content-Length']))))
                time.sleep(case.delay)
                self.send_response(case.status)
                self.send_header('Content-Type', 'application/json')
                if case.status == 302:
                    self.send_header('Location', 'http://example.com/private')
                self.end_headers()
                try:
                    self.wfile.write(json.dumps(case.payload).encode())
                except (BrokenPipeError, ConnectionResetError):
                    pass
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.endpoint = f'http://127.0.0.1:{self.server.server_port}/v1/convert/file'
    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()
    def run_probe(self, timeout=1):
        return probe.probe(self.source, self.endpoint, timeout)
    def test_success_upload_and_redaction(self):
        result = self.run_probe()
        self.assertTrue(result['success'])
        self.assertEqual(result['page_numbers'], [1])
        self.assertEqual(result['structure_counts']['tables'], 1)
        self.assertNotIn('private', json.dumps(result))
        path, body = self.requests[0]
        self.assertEqual(path, '/v1/convert/file')
        self.assertIn(self.source.read_bytes(), body)
        self.assertNotIn(str(self.source).encode(), body)
        self.assertNotIn(b'http_sources', body)
    def test_partial(self):
        self.payload['status'] = 'partial_success'
        self.assertEqual(self.run_probe()['error_category'], 'conversion_partial')
    def test_failure(self):
        self.payload['status'] = 'failure'
        self.assertEqual(self.run_probe()['error_category'], 'conversion_failed')
    def test_empty(self):
        self.payload['document']['md_content'] = '  '
        self.assertEqual(self.run_probe()['error_category'], 'empty_body')
    def test_timeout(self):
        self.delay = 0.1
        self.assertEqual(self.run_probe(0.02)['error_category'], 'timeout')
    def test_redirect(self):
        self.status = 302
        self.assertEqual(self.run_probe()['error_category'], 'redirect_rejected')
        self.assertEqual(len(self.requests), 1)
    def test_remote_and_malformed_endpoint(self):
        for endpoint in ['http://example.com:5001/v1/convert/file', 'http://127.0.0.1:5001/v1/convert/source', 'http://user:secret@127.0.0.1:5001/v1/convert/file', 'http://localhost:5001/v1/convert/file']:
            with self.subTest(endpoint=endpoint):
                result = probe.probe(self.source, endpoint)
                self.assertEqual(result['error_category'], 'endpoint_rejected')
        self.assertEqual(self.requests, [])
    def test_unknown_status(self):
        self.payload['status'] = 'completed'
        self.assertEqual(self.run_probe()['error_category'], 'invalid_contract')
    def test_invalid_structure(self):
        self.payload['document']['json_content']['tables'] = 'invalid'
        self.assertEqual(self.run_probe()['error_category'], 'invalid_contract')
    def test_reported_errors(self):
        self.payload['errors'] = [{'error_message': 'private-secret'}]
        result = self.run_probe()
        self.assertEqual(result['error_category'], 'conversion_errors')
        self.assertNotIn('private-secret', json.dumps(result))
    def test_receipt_private_and_no_overwrite(self):
        path = Path(self.temp.name) / 'receipt.json'
        probe.save_receipt(path, self.run_probe())
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
        with self.assertRaises(FileExistsError):
            probe.save_receipt(path, {})
    def test_invalid_timeout(self):
        self.assertEqual(self.run_probe(float('nan'))['error_category'], 'invalid_timeout')
        self.assertEqual(self.requests, [])

if __name__ == '__main__':
    unittest.main()
