"""Real gateway -> user/account HTTP checks against a disposable shared H2 TCP database.

No existing MySQL is contacted. All passwords/tokens are random and redacted from
evidence. Only processes created by this script are terminated.
"""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo
from staff_auth_smoke import free_port, redact

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--h2-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'logs/user-management/results.json')
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    modules = ('user-service', 'account-service', 'gateway')
    ports = {key: free_port() for key in (*modules, 'database')}
    if len(set(ports.values())) != 4:
        raise RuntimeError('Temporary port collision; retry')
    password = secrets.token_urlsafe(24)
    service_token = secrets.token_urlsafe(32)
    url = (f'jdbc:h2:tcp://127.0.0.1:{ports["database"]}/mem:user_smoke;MODE=MySQL;'
           'DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1')
    records, processes, streams = [], [], []
    completed = False

    def spawn(name, command, env=None):
        stream = (args.output.parent / (name + '.log')).open('w', encoding='utf-8')
        streams.append(stream)
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        processes.append(process)

    def call(name, method, path, expected, token=None, body=None, headers=None):
        request_headers = dict(headers or {})
        request_headers['X-Trace-Id'] = f'user-management-{len(records)+1:03}'
        if token:
            request_headers['Authorization'] = 'Bearer ' + token
        if body is not None:
            request_headers['Content-Type'] = 'application/json'
        data = None if body is None else json.dumps(body).encode('utf-8')
        request = Request(f'http://127.0.0.1:{ports["gateway"]}{path}',
                          data=data, headers=request_headers, method=method)
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            payload = json.load(response)
            records.append(dict(name=name, method=method, path=path, expected=expected,
                                actual=response.status, passed=response.status == expected,
                                response=redact(payload)))
            assert response.status == expected, (name, response.status, redact(payload))
            return payload.get('data')

    with tempfile.TemporaryDirectory(prefix='bank-user-management-') as temporary:
        temporary = Path(temporary)

        def sql(content):
            script = temporary / 'run.sql'
            script.write_text(content, encoding='utf-8')
            result = subprocess.run([args.java, '-cp', str(args.h2_jar.resolve()), 'org.h2.tools.RunScript',
                                     '-url', url, '-user', 'sa', '-password', '', '-script', str(script)],
                                    capture_output=True, timeout=15,
                                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            if result.returncode:
                raise RuntimeError('Isolated database setup failed: ' + result.stderr.decode('utf-8', errors='replace')[-800:])

        try:
            spawn('h2', [args.java, '-cp', str(args.h2_jar.resolve()), 'org.h2.tools.Server',
                         '-tcp', '-tcpPort', str(ports['database']), '-ifNotExists'])
            deadline = time.monotonic() + 10
            while True:
                try:
                    with socket.create_connection(('127.0.0.1', ports['database']), timeout=0.5):
                        break
                except OSError:
                    if time.monotonic() > deadline:
                        raise RuntimeError('H2 startup timed out')
                    time.sleep(0.2)
            schema = (ROOT / 'account-service/src/test/resources/schema-test.sql').read_text(encoding='utf-8')
            schema += '\n' + (ROOT / 'account-service/src/main/resources/db/s2-incremental.sql').read_text(encoding='utf-8')
            schema += '\n' + (ROOT / 'user-service/src/main/resources/db/s1-incremental.sql').read_text(encoding='utf-8')
            sql(schema)
            for module in modules:
                env = os.environ.copy()
                env.update(SERVER_PORT=str(ports[module]), BANK_INTERNAL_TOKEN=service_token,
                           USER_SERVICE_URL=f'http://127.0.0.1:{ports["user-service"]}',
                           ACCOUNT_SERVICE_URL=f'http://127.0.0.1:{ports["account-service"]}',
                           SPRING_PROFILES_ACTIVE='default')
                config = {'server': {'address': '127.0.0.1'}}
                if module == 'user-service':
                    config['auth'] = {'credential-store': 'mysql',
                                      'database': {'url': url, 'username': 'sa', 'password': '',
                                                   'driver-class-name': 'org.h2.Driver'},
                                      'staff': {'grants': {'OPS-ADMIN': {'roles': ['ADMIN'], 'account-scope': []}}}}
                if module == 'account-service':
                    config['spring'] = {'datasource': {'url': url, 'username': 'sa', 'password': '',
                                                       'driver-class-name': 'org.h2.Driver',
                                                       'hikari': {'connection-init-sql': "SET TIME ZONE '+08:00'"}}}
                env['SPRING_APPLICATION_JSON'] = json.dumps(config)
                jar = ROOT / module / 'target' / f'{module}-0.1.0-SNAPSHOT.jar'
                command = [args.java, '-Xmx192m', '-Dfile.encoding=UTF-8']
                if module != 'gateway':
                    command += [f'-Dloader.path={args.h2_jar.resolve().as_posix()}', '-cp', str(jar),
                                'org.springframework.boot.loader.launch.PropertiesLauncher']
                else:
                    command += ['-jar', str(jar)]
                spawn(module, command, env)
            deadline = time.monotonic() + 60
            pending = set(modules)
            while pending:
                if any(process.poll() is not None for process in processes):
                    raise RuntimeError('A child process exited; see smoke logs')
                for module in tuple(pending):
                    try:
                        with urlopen(f'http://127.0.0.1:{ports[module]}/actuator/health', timeout=1) as response:
                            if json.load(response)['status'] == 'UP':
                                pending.remove(module)
                    except (URLError, TimeoutError, OSError):
                        pass
                if time.monotonic() > deadline:
                    raise RuntimeError('Service startup timed out')
                if pending:
                    time.sleep(0.3)

            def register(identity):
                body = dict(realName='模拟客户', identityId=identity, password=password, requestId=str(uuid.uuid4()))
                result = call('register-' + identity, 'POST', '/api/v1/auth/register', 200, body=body)
                return body, result

            def login(identity):
                return call('login-' + identity, 'POST', '/api/v1/auth/login', 200,
                            body=dict(identityId=identity, password=password))['token']

            first, result = register('new-user-1')
            replay = call('register-replay', 'POST', '/api/v1/auth/register', 200, body=first)
            assert replay == result and result['realNameStatus'] == 'VERIFIED'
            call('register-content-conflict', 'POST', '/api/v1/auth/register', 409,
                 body={**first, 'password': password + 'changed'})
            customer_token = login('new-user-1')
            profile = call('own-profile', 'GET', '/api/v1/customers/me', 200, customer_token)
            assert '*' in profile['identityId']
            call('update-contact', 'PUT', '/api/v1/customers/me', 200, customer_token,
                 dict(phone='13812345678', email='member@example.com', requestId=str(uuid.uuid4())))
            call('immutable-identity', 'PUT', '/api/v1/customers/me', 400, customer_token,
                 dict(identityId='forged', requestId=str(uuid.uuid4())))
            account_a = call('open-registered-customer-account', 'POST', '/api/v1/accounts', 200, customer_token,
                             dict(currency='CNY', requestId=str(uuid.uuid4())))['accountId']
            register('new-user-2')
            second_token = login('new-user-2')
            account_b = call('open-second-account', 'POST', '/api/v1/accounts', 200, second_token,
                             dict(currency='CNY', requestId=str(uuid.uuid4())))['accountId']
            # 测试夹具通过受控 SQL 建立管理员身份，不提供匿名创建管理员的业务入口。
            register('bootstrap-fixture')
            sql("UPDATE customer SET identity_id='OPS-ADMIN' WHERE identity_id='bootstrap-fixture';")
            admin = login('OPS-ADMIN')
            grant = dict(identityId='new-user-1', enabled=True, accountScope=[account_a],
                         reason='岗位授权', requestId=str(uuid.uuid4()))
            assigned = call('assign-role', 'PUT', '/api/v1/roles/OPERATIONS', 200, admin, grant)
            assert assigned['authorizationVersion'] == 1
            call('old-customer-session-revoked', 'GET', '/api/v1/auth/me', 401, customer_token)
            staff = login('new-user-1')
            customers = call('scoped-customers', 'GET', '/api/v1/customers?reason=review', 200, staff)
            assert customers['total'] == 1 and customers['items'][0]['accounts'] == [dict(accountId=account_a, status='ACTIVE')]
            call('missing-query-reason', 'GET', '/api/v1/customers', 400, staff)
            call('forged-operations', 'GET', '/api/v1/customers?reason=review', 403, second_token,
                 headers={'X-Roles': 'OPERATIONS', 'X-Account-Scope': account_b})
            call('administrator-cannot-list-customers', 'GET', '/api/v1/customers?reason=review', 403, admin)
            call('administrator-cannot-access-accounts', 'GET', '/api/v1/accounts/' + account_a, 403, admin)
            call('cannot-promote-admin', 'PUT', '/api/v1/roles/ADMIN', 400, admin, grant)
            changed = {**grant, 'accountScope': [account_b], 'requestId': str(uuid.uuid4())}
            call('replace-account-scope', 'PUT', '/api/v1/roles/OPERATIONS', 200, admin, changed)
            call('old-staff-session-revoked', 'GET', '/api/v1/auth/me', 401, staff)
            staff = login('new-user-1')
            revoked = {**grant, 'enabled': False, 'accountScope': [], 'requestId': str(uuid.uuid4())}
            call('revoke-role', 'PUT', '/api/v1/roles/OPERATIONS', 200, admin, revoked)
            call('revoked-role-session-rejected', 'GET', '/api/v1/auth/me', 401, staff)
            call('logout-admin', 'POST', '/api/v1/auth/logout', 200, admin)
            call('logged-out-admin-rejected', 'PUT', '/api/v1/roles/OPERATIONS', 401, admin, grant)
            completed = True
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            for stream in streams:
                stream.close()
            args.output.write_text(json.dumps(dict(executedAt=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
                                                   database='isolated H2 TCP, MySQL mode', services=list(modules),
                                                   passed=completed, total=len(records), cases=records),
                                              ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'PASS {len(records)} HTTP cases; evidence: {args.output}')


if __name__ == '__main__':
    main()
