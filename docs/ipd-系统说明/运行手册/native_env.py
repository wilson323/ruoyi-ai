#!/usr/bin/env python3
"""OPS-01: project-local native services. Never touches global services or data."""
import argparse
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / '.codex/ipd-dev'
CFG = BASE / 'config'
MYSQL = BASE / 'software/mysql-8.0.46-macos15-arm64'
REDIS = BASE / 'software/redis-5.0.14/src'
MINIO = BASE / 'software/minio.RELEASE.2025-09-07T16-13-09Z'
PORTS = {'mysql': [13306], 'redis': [16379], 'minio': [19000, 19001]}


def require_free_port(port):
    # TIME_WAIT from our previous connection is not an active listener.
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
        s.listen(1)


def private_file(path, value):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as f:
        f.write(value)
    path.chmod(0o600)


def run(args, **kw):
    result = subprocess.run([str(a) for a in args], text=True, capture_output=True, **kw)
    if result.returncode:
        # Command/stdout may contain credentials; leave service diagnostics in private logs.
        raise RuntimeError(f'{Path(args[0]).name} exit {result.returncode}; inspect private logs')
    return result.stdout.strip()


def credentials():
    path = CFG / 'credentials.json'
    if path.stat().st_mode & 0o077:
        raise RuntimeError('Credentials file must have mode 0600')
    return json.loads(path.read_text())


def sql(query):
    return run([MYSQL / 'bin/mysql', f'--defaults-file={CFG}/mysql-client.cnf',
                '--batch', '--skip-column-names'], input=query, timeout=15)


def redis(*args):
    env = dict(os.environ, REDISCLI_AUTH=credentials()['redis'])
    return run([REDIS / 'redis-cli', '-h', '127.0.0.1', '-p', '16379', '--raw', *args],
               env=env, timeout=10)


def identity(name):
    path = BASE / 'run' / f'{name}.json'
    if not path.exists():
        return None
    record = json.loads(path.read_text())
    p = subprocess.run(['ps', '-p', str(record['pid']), '-o', 'lstart=', '-o', 'command='],
                       capture_output=True, text=True)
    if not p.stdout.strip():
        return None
    if p.stdout.strip() != record['identity']:
        raise RuntimeError(f'{name}: PID identity changed; refusing to signal/reuse it')
    return record


def wait_for(predicate, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except (OSError, RuntimeError):
            pass
        time.sleep(.4)
    raise RuntimeError('Service readiness timed out; inspect project-private logs')


def launch(name, args, env=None):
    with (BASE / 'logs' / f'{name}.log').open('a') as out:
        p = subprocess.Popen([str(a) for a in args], stdin=subprocess.DEVNULL,
                             stdout=out, stderr=out, env=env, start_new_session=True)
    time.sleep(.5)
    if p.poll() is not None:
        raise RuntimeError(f'{name} exited during startup; inspect private logs')
    observed = run(['ps', '-p', str(p.pid), '-o', 'lstart=', '-o', 'command='])
    private_file(BASE / 'run' / f'{name}.json', json.dumps({'pid': p.pid, 'identity': observed}))


def stop_one(name):
    record = identity(name)
    if record:
        os.kill(record['pid'], signal.SIGTERM)
        wait_for(lambda: identity(name) is None)


def initialize():
    BASE.mkdir(exist_ok=True, mode=0o700)
    BASE.chmod(0o700)
    for child in ['config', 'data', 'run', 'logs', 'evidence']:
        (BASE / child).mkdir(exist_ok=True, mode=0o700)
    if (CFG / 'initialized.json').exists():
        marker = json.loads((CFG / 'initialized.json').read_text())
        if marker.get('root') != str(ROOT):
            raise RuntimeError('Initialization belongs to another checkout')
        for required in ['credentials.json', 'mysql.cnf', 'mysql-client.cnf', 'redis.conf', 'application-ipd-local.yml']:
            if not (CFG / required).is_file():
                raise RuntimeError(f'Incomplete initialization: missing {required}; inspect and recover configuration')
        credentials()
        return {'initialized': True, 'reused': True}
    # Never overwrite an existing or partially initialized instance.
    if (CFG / 'credentials.json').exists() or (BASE / 'data/mysql').exists():
        raise RuntimeError('Partial initialization exists; inspect it instead of reinitializing')
    for binary in [MYSQL / 'bin/mysqld', REDIS / 'redis-server', MINIO]:
        if not os.access(binary, os.X_OK):
            raise RuntimeError(f'Missing executable: {binary.name}')
    for name in PORTS:
        for port in PORTS[name]:
            require_free_port(port)
    secret = {k: secrets.token_hex(24) for k in ['mysql_root', 'mysql_app', 'mysql_migrator', 'redis', 'minio_secret']}
    secret['minio_access'] = 'ipd-local-' + secrets.token_hex(6)
    private_file(CFG / 'credentials.json', json.dumps(secret))
    for name in PORTS:
        (BASE / 'data' / name).mkdir(mode=0o700)
    private_file(CFG / 'mysql.cnf', f'''[mysqld]
basedir={MYSQL}
datadir={BASE}/data/mysql
socket={BASE}/run/mysql.sock
pid-file={BASE}/run/mysql.pid
port=13306
bind-address=127.0.0.1
mysqlx=0
log-error={BASE}/logs/mysql-error.log
local-infile=0
default-time-zone=+08:00
character-set-server=utf8mb4
''')
    private_file(CFG / 'mysql-client.cnf', f'''[client]
user=root
password={secret['mysql_root']}
protocol=SOCKET
socket={BASE}/run/mysql.sock
''')
    private_file(CFG / 'redis.conf', f'''bind 127.0.0.1
protected-mode yes
port 16379
daemonize no
requirepass {secret['redis']}
dir {BASE}/data/redis
appendonly yes
appendfsync everysec
logfile {BASE}/logs/redis-server.log
''')
    # Initialize without a listener; blank password exists only on the private bootstrap socket.
    run([MYSQL / 'bin/mysqld', f'--defaults-file={CFG}/mysql.cnf', '--initialize-insecure'], timeout=90)
    launch('mysql', [MYSQL / 'bin/mysqld', f'--defaults-file={CFG}/mysql.cnf', '--skip-networking'])
    try:
        client = [MYSQL / 'bin/mysql', '--no-defaults', '-u', 'root',
                  f'--socket={BASE}/run/mysql.sock', '--batch']
        wait_for(lambda: run(client, input='SELECT 1;', timeout=3))
        run(client, input=f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{secret['mysql_root']}';", timeout=10)
        assert sql('SELECT CURRENT_USER();') == 'root@localhost'
    finally:
        stop_one('mysql')
    # Loaded explicitly, with ipd-local profile (dev Mock initializer is not selected).
    private_file(CFG / 'application-ipd-local.yml', f'''server:
  port: 16039
demo:
  enabled: false
spring:
  datasource:
    dynamic:
      primary: master
      strict: true
      datasource:
        master:
          driverClassName: com.mysql.cj.jdbc.Driver
          url: jdbc:mysql://127.0.0.1:13306/ipd_dev?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai&allowPublicKeyRetrieval=true&useSSL=false
          username: ipd_app
          password: "{secret['mysql_app']}"
  data:
    redis:
      host: 127.0.0.1
      port: 16379
      password: "{secret['redis']}"
      database: 0
''')
    private_file(CFG / 'initialized.json', json.dumps({'root': str(ROOT), 'ports': PORTS}))
    return {'initialized': True, 'reused': False, 'application_database': 'pending OPS-02'}


def start():
    if not (CFG / 'initialized.json').exists():
        raise RuntimeError('Run init first')
    initialize()  # Validate the completed marker and required files before starting services.
    pending = [n for n in PORTS if not identity(n)]
    for name in pending:
        for port in PORTS[name]:
            require_free_port(port)
    secret = credentials()
    commands = {
        'mysql': [MYSQL / 'bin/mysqld', f'--defaults-file={CFG}/mysql.cnf'],
        'redis': [REDIS / 'redis-server', CFG / 'redis.conf'],
        'minio': [MINIO, 'server', BASE / 'data/minio', '--address', '127.0.0.1:19000',
                  '--console-address', '127.0.0.1:19001'],
    }
    for name in pending:
        env = None
        if name == 'minio':
            env = dict(os.environ, MINIO_ROOT_USER=secret['minio_access'],
                       MINIO_ROOT_PASSWORD=secret['minio_secret'], MINIO_UPDATE='off', MINIO_BROWSER='on')
        launch(name, commands[name], env)
    wait_for(lambda: sql('SELECT 1;') == '1')
    wait_for(lambda: redis('PING') == 'PONG')
    wait_for(lambda: urllib.request.urlopen('http://127.0.0.1:19000/minio/health/live', timeout=3).status == 200)
    return status()


def status():
    services = {n: {'running': bool(identity(n)), 'ports': ports} for n, ports in PORTS.items()}
    if all(s['running'] for s in services.values()):
        services['mysql']['version'] = sql('SELECT VERSION();')
        services['redis']['ping'] = redis('PING')
        services['minio']['health'] = urllib.request.urlopen('http://127.0.0.1:19000/minio/health/live', timeout=3).status
    return services


def stop():
    for name in reversed(PORTS):
        stop_one(name)
    return status()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'start', 'status', 'stop'])
    args = parser.parse_args()
    print(json.dumps({'init': initialize, 'start': start, 'status': status, 'stop': stop}[args.action](), indent=2))
