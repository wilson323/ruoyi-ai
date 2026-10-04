#!/usr/bin/env python3
"""Read-only single-file Docling v1 probe; receipts contain no document text.
Contract: docling-serve/app.py process_file, docling/datamodel/service/responses.py
ConvertDocumentResponse. Explicit literal loopback only; no proxies or redirects.
"""
import argparse
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

MAX_BYTES = 32 * 1024 * 1024

class ProbeError(Exception):
    pass

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ProbeError('redirect_rejected')

def validate_endpoint(endpoint):
    try:
        url = urllib.parse.urlsplit(endpoint)
        ip = ipaddress.ip_address(url.hostname or '')
        port = url.port
    except ValueError:
        raise ProbeError('endpoint_rejected') from None
    if (url.scheme != 'http' or not ip.is_loopback or not port or url.username
            or url.password or url.query or url.fragment
            or url.path != '/v1/convert/file'):
        raise ProbeError('endpoint_rejected')

def summarize(payload):
    if not isinstance(payload, dict):
        raise ProbeError('invalid_contract')
    status = payload.get('status')
    if status not in ('success', 'partial_success', 'failure'):
        raise ProbeError('invalid_contract')
    doc = payload.get('document')
    errors = payload.get('errors', [])
    if not isinstance(doc, dict) or not isinstance(errors, list):
        raise ProbeError('invalid_contract')
    processing_time = payload.get('processing_time')
    if (not isinstance(doc.get('filename'), str) or not doc['filename']
            or type(processing_time) not in (int, float)
            or not math.isfinite(processing_time) or processing_time < 0):
        raise ProbeError('invalid_contract')
    markdown = doc.get('md_content')
    if not isinstance(markdown, str):
        raise ProbeError('invalid_contract')
    structure = doc.get('json_content')
    if not isinstance(structure, dict):
        raise ProbeError('invalid_contract')
    counts = {}
    for key in ('texts', 'tables', 'pictures', 'groups'):
        items = structure.get(key, [])
        if not isinstance(items, list):
            raise ProbeError('invalid_contract')
        counts[key] = len(items)
    pages = structure.get('pages', {})
    if not isinstance(pages, dict):
        raise ProbeError('invalid_contract')
    numbers = []
    for key in pages:
        try:
            number = int(key)
        except (ValueError, TypeError):
            raise ProbeError('invalid_contract') from None
        if number < 1:
            raise ProbeError('invalid_contract')
        numbers.append(number)
    category = ('conversion_partial' if status == 'partial_success' else
                'conversion_failed' if status == 'failure' else
                'conversion_errors' if errors else
                'empty_body' if not markdown.strip() else None)
    return {'conversion_status': status, 'error_category': category,
            'success': category is None, 'body_sha256': hashlib.sha256(markdown.encode()).hexdigest(),
            'body_chars': len(markdown), 'structure_counts': counts,
            'page_numbers': sorted(numbers), 'error_count': len(errors)}

def probe(source, endpoint, timeout=30):
    started = time.monotonic()
    result = {'success': False, 'error_category': None}
    try:
        validate_endpoint(endpoint)
        if not math.isfinite(timeout) or not 0 < timeout <= 60:
            raise ProbeError('invalid_timeout')
        path = Path(source)
        if not path.is_file():
            raise ProbeError('invalid_source')
        with path.open('rb') as handle:
            data = handle.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES or not data:
            raise ProbeError('source_size_rejected')
        result.update(source_sha256=hashlib.sha256(data).hexdigest(), source_bytes=len(data))
        boundary = uuid.uuid4().hex
        pieces = []
        for key, value in [('to_formats', 'md'), ('to_formats', 'json'), ('target_type', 'inbody')]:
            pieces.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        # Generic filename avoids exposing local path or injecting multipart headers.
        suffix = path.suffix.lower()
        suffix = suffix if suffix[1:].isalnum() else '.bin'
        pieces.append(f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="source{suffix}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode())
        pieces.extend([data, f'\r\n--{boundary}--\r\n'.encode()])
        req = urllib.request.Request(endpoint, data=b''.join(pieces), headers={
            'Content-Type': f'multipart/form-data; boundary={boundary}', 'Accept': 'application/json'})
        # Auth from environment only; never recorded or printed.
        key = os.environ.get('DOCLING_PROBE_API_KEY')
        if key:
            req.add_header('X-Api-Key', key)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(req, timeout=timeout) as response:
            result['http_status'] = response.status
            if response.status != 200 or response.headers.get_content_type() != 'application/json':
                raise ProbeError('invalid_http_response')
            raw = response.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES:
                raise ProbeError('response_size_rejected')
        payload = json.loads(raw, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        result.update(summarize(payload))
    except ProbeError as exc:
        result['error_category'] = str(exc)
    except urllib.error.HTTPError as exc:
        result.update(http_status=exc.code, error_category='redirect_rejected' if 300 <= exc.code < 400 else 'http_error')
    except (TimeoutError, socket.timeout):
        result['error_category'] = 'timeout'
    except urllib.error.URLError as exc:
        result['error_category'] = 'timeout' if isinstance(exc.reason, TimeoutError) else 'connection_failed'
    except (ValueError, UnicodeError):
        result['error_category'] = 'invalid_contract'
    except OSError:
        result['error_category'] = 'local_io_error'
    result['elapsed_seconds'] = round(time.monotonic() - started, 6)
    return result

def save_receipt(path, result):
    # Exclusive create: never overwrite evidence or follow a destination symlink.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as handle:
        json.dump(result, handle, indent=2, ensure_ascii=False)
        handle.write('\n')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True)
    parser.add_argument('--endpoint', required=True)
    parser.add_argument('--receipt', required=True)
    parser.add_argument('--timeout', type=float, default=30)
    args = parser.parse_args()
    result = probe(args.source, args.endpoint, args.timeout)
    try:
        save_receipt(args.receipt, result)
    except OSError:
        print('receipt_write_failed')
        return 2
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['success'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
