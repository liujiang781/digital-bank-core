"""Real user/account/gateway HTTP authorization checks using an isolated H2 database.

Requires packaged JARs, JDK 21 and an H2 JAR. Never connects to the existing MySQL.
Passwords/tokens are generated for this run and omitted from evidence. Only child
processes created by this script are stopped.
"""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('user-service', 'account-service', 'gateway')


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def redact(value):
    if isinstance(value, dict):
        return {key: ('[redacted]' if key in {'token', 'password'} else redact(item))
                for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--h2-jar', type=Path, required=True)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--output', type=Path, default=ROOT / 'logs/staff-auth/results.json')
    args = parser.parse_args()
    h2 = args.h2_jar.resolve()
    if not h2.is_file():
        parser.error('H2 JAR does not exist')
    jars = {module: ROOT / module / 'target' / f'{module}-0.1.0-SNAPSHOT.jar' for module in MODULES}
    if not args.java or not all(jar.is_file() for jar in jars.values()):
        parser.error('JDK 21 and packaged service JARs are required')
    ports = dict(zip(MODULES, [free_port() for _ in MODULES]))
    if len(set(ports.values())) != len(MODULES):
        raise RuntimeError('Port allocation collision; run again')
    secret = secrets.token_urlsafe(32)
    password = secrets.token_urlsafe(24)
    records, processes, streams = [], [], []
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def check(name, method, path, expected, token=None, payload=None, headers=None, module='gateway'):
        request_headers = dict(headers or {})
        request_headers['X-Trace-Id'] = f'staff-auth-{len(records) + 1:03}'
        if method == 'POST' and path.startswith('/api/v1/accounts/'):
            request_headers['X-Request-Id'] = str(uuid.uuid4())
        if token:
            request_headers['Authorization'] = 'Bearer ' + token
        data = None
        if payload is not None:
            request_headers['Content-Type'] = 'application/json'
            data = json.dumps(payload).encode('utf-8')
        request = Request(f'http://127.0.0.1:{ports[module]}{path}', data=data,
                          headers=request_headers, method=method)
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            body = json.load(response)
            records.append(dict(name=name, method=method, path=path, module=module,
                                expected=expected, actual=response.status,
                                passed=response.status == expected, response=redact(body)))
            assert response.status == expected, (name, response.status, redact(body))
            return body['data']

    success = False
    with tempfile.TemporaryDirectory(prefix='bank-staff-auth-') as temporary:
        temporary = Path(temporary)
        seed = temporary / 'seed.sql'
        seed.write_text("""
INSERT INTO customer (customer_id, identity_id, name, real_name_status, status)
VALUES ('DEMO-CUSTOMER-001', 'smoke-customer', 'Smoke Customer', 'VERIFIED', 'NORMAL');
INSERT INTO account (account_id, customer_id, currency, status, book_balance, reserved_amount)
VALUES ('S2-ACCOUNT-A', 'DEMO-CUSTOMER-001', 'CNY', 'ACTIVE', 0.00, 0.00),
       ('S2-ACCOUNT-B', 'DEMO-CUSTOMER-001', 'CNY', 'ACTIVE', 0.00, 0.00);
""", encoding='utf-8')
        try:
            for module in MODULES:
                env = os.environ.copy()
                env.update(BANK_INTERNAL_TOKEN=secret, SERVER_PORT=str(ports[module]),
                           USER_SERVICE_URL=f'http://127.0.0.1:{ports["user-service"]}',
                           ACCOUNT_SERVICE_URL=f'http://127.0.0.1:{ports["account-service"]}',
                           SPRING_PROFILES_ACTIVE='default')
                config = {'server': {'address': '127.0.0.1'}}
                if module == 'user-service':
                    config['auth'] = {
                        'credential-store': 'demo',
                        'demo': {'enabled': True, 'identity-id': 'smoke-customer', 'password': password,
                                 'users': {name: {'customer-id': 'STAFF-' + name.upper(), 'password': password}
                                           for name in ('ops-s2', 'risk-s2', 'ops-no-scope')}},
                        'staff': {'grants': {
                            'ops-s2': {'roles': ['OPERATIONS'], 'account-scope': ['S2-ACCOUNT-A']},
                            'risk-s2': {'roles': ['RISK'], 'account-scope': ['S2-ACCOUNT-A']},
                            'ops-no-scope': {'roles': ['OPERATIONS'], 'account-scope': []}}}}
                if module == 'account-service':
                    schema = ROOT / 'account-service/src/test/resources/schema-test.sql'
                    incremental = ROOT / 'account-service/src/main/resources/db/s2-incremental.sql'
                    config['spring'] = {
                        'datasource': {'url': 'jdbc:h2:mem:staff_smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
                                       'username': 'sa', 'password': '', 'driver-class-name': 'org.h2.Driver',
                                       'hikari': {'connection-init-sql': "SET TIME ZONE '+08:00'"}},
                        'sql': {'init': {'mode': 'always', 'schema-locations':
                                        [schema.as_uri(), incremental.as_uri()], 'data-locations': [seed.as_uri()]}}}
                env['SPRING_APPLICATION_JSON'] = json.dumps(config)
                stream = (args.output.parent / f'{module}.log').open('w', encoding='utf-8')
                streams.append(stream)
                command = [args.java, '-Xmx192m', '-Dfile.encoding=UTF-8']
                if module == 'account-service':
                    command += [f'-Dloader.path={h2.as_posix()}', '-cp', str(jars[module]),
                                'org.springframework.boot.loader.launch.PropertiesLauncher']
                else:
                    command += ['-jar', str(jars[module])]
                processes.append(subprocess.Popen(command, cwd=ROOT, env=env, stdout=stream,
                                                   stderr=subprocess.STDOUT,
                                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0))
            deadline = time.monotonic() + 75
            pending = set(MODULES)
            while pending:
                for module in tuple(pending):
                    if processes[MODULES.index(module)].poll() is not None:
                        raise RuntimeError(f'{module} exited; see {args.output.parent / (module + ".log")}')
                    try:
                        with urlopen(f'http://127.0.0.1:{ports[module]}/actuator/health', timeout=1) as response:
                            if json.load(response)['status'] == 'UP':
                                pending.remove(module)
                    except (URLError, TimeoutError, OSError):
                        pass
                if time.monotonic() > deadline:
                    raise RuntimeError(f'Startup timed out: {pending}')
                if pending:
                    time.sleep(0.3)

            tokens = {}
            for identity in ('smoke-customer', 'ops-s2', 'risk-s2', 'ops-no-scope'):
                tokens[identity] = check('login-' + identity, 'POST', '/api/v1/auth/login', 200,
                                         payload={'identityId': identity, 'password': password})['token']
            ops, risk, customer, no_scope = (tokens[key] for key in
                                           ('ops-s2', 'risk-s2', 'smoke-customer', 'ops-no-scope'))
            identity = check('verified-staff-scope', 'GET', '/api/v1/auth/me', 200, ops)
            assert identity['roles'] == ['OPERATIONS'] and identity['accountScope'] == ['S2-ACCOUNT-A']
            identity = check('customer-has-no-staff-grant', 'GET', '/api/v1/auth/me', 200, customer)
            assert identity['roles'] == [] and identity['accountScope'] == []
            check('internal-verification-requires-service-token', 'GET', '/internal/v1/auth/session', 401,
                  ops, module='user-service')
            check('internal-verification-carries-scope', 'GET', '/internal/v1/auth/session', 200,
                  ops, headers={'X-Service-Token': secret}, module='user-service')
            base = '/api/v1/accounts/'
            freeze = {'reason': 'S1-S2 authorization smoke test'}
            check('anonymous-freeze-denied', 'POST', base + 'S2-ACCOUNT-A/freeze', 401, payload=freeze)
            for name, token, headers, module in (
                    ('customer-freeze-denied', customer, {}, 'gateway'),
                    ('forged-customer-role-denied', customer,
                     {'X-Roles': 'OPERATIONS', 'X-Account-Scope': 'S2-ACCOUNT-A'}, 'gateway'),
                    ('direct-port-forged-role-denied', customer,
                     {'X-Roles': 'OPERATIONS', 'X-Account-Scope': 'S2-ACCOUNT-A'}, 'account-service'),
                    ('role-without-scope-denied', no_scope, {}, 'gateway')):
                check(name, 'POST', base + 'S2-ACCOUNT-A/freeze', 403, token, freeze, headers, module)
            check('out-of-scope-freeze-denied', 'POST', base + 'S2-ACCOUNT-B/freeze', 403, ops, freeze)
            check('forged-scope-cannot-expand-grant', 'POST', base + 'S2-ACCOUNT-B/freeze', 403,
                  ops, freeze, {'X-Account-Scope': 'S2-ACCOUNT-B'})
            before = check('rejected-actions-leave-B-active', 'GET', base + 'S2-ACCOUNT-B/balance', 200, customer)
            assert before['status'] == 'ACTIVE' and before['bookBalance'] == '0.00'
            check('operations-freeze-authorized', 'POST', base + 'S2-ACCOUNT-A/freeze', 200, ops, freeze)
            frozen = check('risk-freeze-authorized', 'POST', base + 'S2-ACCOUNT-A/freeze', 200, risk, freeze)
            restrictions = [item['restrictionId'] for item in frozen['restrictions']]
            unfreeze = {'reason': 'S1-S2 verified release', 'releaseEvidence': 'Both smoke restrictions resolved',
                        'resolvedRestrictionIds': restrictions}
            check('risk-cannot-unfreeze', 'POST', base + 'S2-ACCOUNT-A/unfreeze', 403, risk, unfreeze)
            check('customer-cannot-unfreeze', 'POST', base + 'S2-ACCOUNT-A/unfreeze', 403, customer, unfreeze)
            check('operations-unfreeze-authorized', 'POST', base + 'S2-ACCOUNT-A/unfreeze', 200, ops, unfreeze)
            after = check('account-active-after-release', 'GET', base + 'S2-ACCOUNT-A/balance', 200, customer)
            assert after['status'] == 'ACTIVE' and after['bookBalance'] == '0.00'
            audit = check('successful-actions-audited', 'GET', base + 'S2-ACCOUNT-A/operations', 200, ops)
            assert audit['total'] == 3
            flows = check('freeze-release-create-no-fund-flow', 'GET', base + 'S2-ACCOUNT-A/fund-entries', 200, ops)
            assert flows['total'] == 0
            check('logout-staff', 'POST', '/api/v1/auth/logout', 200, ops)
            check('revoked-at-gateway', 'POST', base + 'S2-ACCOUNT-A/freeze', 401, ops, freeze)
            check('revoked-at-account-service', 'POST', base + 'S2-ACCOUNT-A/freeze', 401,
                  ops, freeze, module='account-service')
            success = True
        finally:
            for process in processes:
                if process.poll() is None:
                    process.terminate()
            for process in processes:
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            for stream in streams:
                stream.close()
            evidence = dict(executedAt=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
                            database='isolated H2 2.3.232 (MySQL mode)',
                            credentialStore='explicit demo credentials; real BCrypt and in-memory sessions',
                            services=list(MODULES), ports=ports, passed=success,
                            total=len(records), cases=records)
            args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'PASS {len(records)} real HTTP cases; evidence: {args.output}')


if __name__ == '__main__':
    main()
