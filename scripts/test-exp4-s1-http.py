"""Run S1 HTTP checks against isolated gateway and user-service processes.

Uses Maven Surefire classpaths from a successful test run. No database writes.
Only redacted evidence is saved. All processes started here are stopped finally.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]


def classpath(module):
    reports = sorted((ROOT / 'backend' / module / 'target/surefire-reports').glob('TEST-*.xml'))
    for report in reports:
        prop = ET.parse(report).find("./properties/property[@name='java.class.path']")
        if prop is not None:
            entries = prop.attrib['value'].split(os.pathsep)
            entries = [p for p in entries if not p.replace('\\', '/').rstrip('/').endswith('/test-classes')]
            return os.pathsep.join(entries)
    raise RuntimeError(f'Run Maven tests first: missing Surefire classpath for {module}')


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java.exe'))
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/04-测试报告/证据/2026-11-17')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    password, service_secret = secrets.token_urlsafe(20), secrets.token_urlsafe(36)
    user_port, gateway_port = free_port(), free_port()
    identity = 'exp4-s1-http-user'
    processes, handles, records, tokens = [], [], [], []

    def redact(value):
        text = json.dumps(value, ensure_ascii=False)
        for secret in [password, service_secret, *tokens]:
            text = text.replace(secret, '[REDACTED]')
        return json.loads(text)

    def request(port, method, path, body=None, headers=None):
        payload = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=payload, method=method,
                                     headers={'Content-Type': 'application/json', **(headers or {})})
        try:
            response = urllib.request.urlopen(req, timeout=8)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode('utf-8')
            try:
                content = json.loads(raw) if raw else None
            except json.JSONDecodeError:
                content = raw
            return response.status, dict(response.headers), content

    def check(number, name, port, method, path, expected, body=None, headers=None, predicate=None):
        status, received_headers, content = request(port, method, path, body, headers)
        if isinstance(content, dict):
            token = (content.get('data') or {}).get('token') if isinstance(content.get('data'), dict) else None
            if token:
                tokens.append(token)
        passed = status == expected and (predicate(content) if predicate else True)
        records.append(redact(dict(id=f'FT-S1-{number:03}', name=name,
            time=datetime.now().astimezone().isoformat(), tester='S1（Codex 辅助执行）',
            request=dict(method=method, path=path, port=port, body=body, headers=headers or {}),
            expected_status=expected, actual_status=status, passed=passed,
            response_headers={k: v for k, v in received_headers.items() if k.lower() in
                              ('content-type', 'cache-control', 'x-trace-id')}, response=content)))
        return content

    def start(module, main_class, params):
        handle = (args.output / f'{module}-http-runtime.log').open('w', encoding='utf-8')
        handles.append(handle)
        proc = subprocess.Popen([args.java, '-cp', classpath(module), main_class, *params], cwd=ROOT,
                stdout=handle, stderr=subprocess.STDOUT,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        processes.append(proc)

    def ready(port, process):
        deadline = time.monotonic() + 50
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Service failed to start; inspect ignored runtime logs')
            try:
                if request(port, 'GET', '/actuator/health')[0] == 200:
                    return
            except (OSError, TimeoutError):
                pass
            time.sleep(.3)
        raise RuntimeError('Service startup timed out')

    try:
        start('user-service', 'com.digitalbank.user.UserServiceApplication', [
            f'--server.port={user_port}', '--server.address=127.0.0.1',
            '--auth.demo.enabled=true', f'--auth.demo.identity-id={identity}',
            f'--auth.demo.password={password}', f'--auth.internal-token={service_secret}'])
        ready(user_port, processes[-1])
        start('gateway', 'com.digitalbank.gateway.GatewayApplication', [
            f'--server.port={gateway_port}', '--server.address=127.0.0.1',
            f'--USER_SERVICE_URL=http://127.0.0.1:{user_port}',
            f'--auth.user-service-url=http://127.0.0.1:{user_port}',
            f'--auth.internal-token={service_secret}'])
        ready(gateway_port, processes[-1])
        g, u = gateway_port, user_port
        login_body = {'identityId': identity, 'password': password}
        login = check(1, '经网关登录成功', g, 'POST', '/api/v1/auth/login', 200, login_body,
                      predicate=lambda b: b['data']['tokenType'] == 'Bearer' and len(b['data']['token']) == 43)
        bearer = {'Authorization': 'Bearer ' + login['data']['token']}
        check(2, '错误密码', g, 'POST', '/api/v1/auth/login', 401,
              {'identityId': identity, 'password': 'wrong-password'})
        check(3, '不存在的身份', g, 'POST', '/api/v1/auth/login', 401,
              {'identityId': 'unknown-s1-user', 'password': 'wrong-password'})
        check(4, '缺少登录字段', g, 'POST', '/api/v1/auth/login', 400, {})
        check(5, 'UTF8 密码超过 72 字节', g, 'POST', '/api/v1/auth/login', 400,
              {'identityId': identity, 'password': '密' * 25})
        check(6, '未登录访问受保护接口', g, 'GET', '/api/v1/auth/me', 401)
        check(7, '畸形令牌', g, 'GET', '/api/v1/auth/me', 401, headers={'Authorization': 'Bearer invalid'})
        check(8, '合法令牌读取当前身份', g, 'GET', '/api/v1/auth/me', 200, headers=bearer,
              predicate=lambda b: b['data']['identityId'] == identity)
        check(9, '伪造身份头不能覆盖会话', g, 'GET', '/api/v1/auth/me', 200,
              headers={**bearer, 'X-Identity-Id': 'forged-user', 'X-Customer-Id': 'forged-customer'},
              predicate=lambda b: b['data']['identityId'] == identity)
        check(10, '内部认证接口不对网关开放', g, 'GET', '/internal/v1/auth/session', 404, headers=bearer)
        check(11, '直连内部接口缺服务令牌', u, 'GET', '/internal/v1/auth/session', 401, headers=bearer)
        check(12, '直连内部接口错误服务令牌', u, 'GET', '/internal/v1/auth/session', 401,
              headers={**bearer, 'X-Service-Token': 'wrong-service-token'})
        check(13, '服务令牌与用户令牌均合法', u, 'GET', '/internal/v1/auth/session', 200,
              headers={**bearer, 'X-Service-Token': service_secret},
              predicate=lambda b: b['data']['identityId'] == identity)
        check(14, '仅服务令牌不能代替用户令牌', u, 'GET', '/internal/v1/auth/session', 401,
              headers={'X-Service-Token': service_secret})
        check(15, '通过网关退出', g, 'POST', '/api/v1/auth/logout', 200, headers=bearer)
        check(16, '退出后旧令牌立即失效', g, 'GET', '/api/v1/auth/me', 401, headers=bearer)
        check(17, '公共健康检查无需登录', g, 'GET', '/api/v1/user/health', 200)
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for handle in handles:
            handle.close()
        result = dict(scope='真实本机 HTTP 用户服务与网关联调；demo 凭据；不使用 MySQL，不代表资金链路验收',
            time=datetime.now().astimezone().isoformat(), user_port=user_port, gateway_port=gateway_port,
            total=len(records), passed=sum(r['passed'] for r in records),
            failed=sum(not r['passed'] for r in records), cases=records)
        (args.output / 's1-http-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: result[k] for k in ('total', 'passed', 'failed')}, ensure_ascii=False))
    if result['total'] != 17 or result['failed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
