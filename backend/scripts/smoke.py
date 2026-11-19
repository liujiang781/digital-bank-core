"""Build first, then run: python scripts/smoke.py. Stops only processes it starts."""
import json
import os
from pathlib import Path
import shutil
import secrets
import socket
import subprocess
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

ROOT = Path(__file__).resolve().parents[1]
MODULES = ["user-service", "account-service", "transaction-service", "risk-service", "gateway"]
PORTS = dict(zip(MODULES, [18081, 18082, 18083, 18084, 18080]))

def get(port, path, trace=None):
    headers = {"X-Trace-Id": trace} if trace else {}
    with urlopen(Request(f"http://127.0.0.1:{port}{path}", headers=headers), timeout=2) as res:
        return json.load(res), res.headers

def call(port, path, method="GET", payload=None, token=None, headers=None):
    request_headers = dict(headers or {})
    if token:
        request_headers["Authorization"] = "Bearer " + token
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request = Request(f"http://127.0.0.1:{port}{path}", data=data,
                      headers=request_headers, method=method)
    try:
        response = urlopen(request, timeout=5)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        body = json.loads(raw) if raw else None
        return response.status, body, response.headers


def check_auth(processes, password, service_secret):
    gateway = PORTS["gateway"]
    login_body = {"identityId": "smoke-user", "password": password}
    status, body, _ = call(gateway, "/api/v1/auth/login", "POST", login_body)
    assert status == 200, (status, body)
    token = body["data"]["token"]
    assert body["data"]["tokenType"] == "Bearer"
    assert password not in json.dumps(body)

    wrong = {"identityId": "smoke-user", "password": "incorrect-password"}
    status, body, _ = call(gateway, "/api/v1/auth/login", "POST", wrong)
    assert status == 401 and body["code"] == "UNAUTHENTICATED", (status, body)
    status, body, _ = call(gateway, "/api/v1/auth/login", "POST", {})
    assert status == 400 and body["code"] == "INVALID_ARGUMENT", (status, body)

    for credential in [None, "not-a-token", "A" * 43]:
        status, body, headers = call(gateway, "/api/v1/auth/me", token=credential,
                                     headers={"X-Trace-Id": "auth-denied"})
        assert status == 401 and body["code"] == "UNAUTHENTICATED", (status, body)
        assert body["traceId"] == headers["X-Trace-Id"] == "auth-denied"

    status, body, _ = call(gateway, "/api/v1/auth/me", token=token,
                           headers={"X-Identity-Id": "forged", "X-Customer-Id": "forged"})
    assert status == 200 and body["data"]["identityId"] == "smoke-user", (status, body)

    internal = "/internal/v1/auth/session"
    status, _, _ = call(PORTS["user-service"], internal, token=token)
    assert status == 401
    status, body, _ = call(PORTS["user-service"], internal, token=token,
                           headers={"X-Service-Token": service_secret})
    assert status == 200 and body["data"]["identityId"] == "smoke-user", (status, body)
    status, _, _ = call(gateway, internal, token=token,
                        headers={"X-Service-Token": service_secret})
    assert status == 404

    # 所有服务的非公开入口都要自行核验令牌，不能直接端口绕过网关。
    for module in MODULES:
        status, _, _ = call(PORTS[module], "/actuator/info")
        assert status == 401, (module, status)
        status, _, _ = call(PORTS[module], "/actuator/info", token=token)
        assert status == 200, (module, status)
    status, body, _ = call(gateway, "/api/v1/accounts/not-implemented", token=token)
    assert status == 404, (status, body)
    print("PASS login, malformed input, forged headers, internal credentials and service-side verification")

    status, _, _ = call(gateway, "/api/v1/auth/logout", "POST", token=token)
    assert status == 200
    for port in [gateway, PORTS["account-service"]]:
        status, body, _ = call(port, "/actuator/info", token=token)
        assert status == 401 and body["code"] == "UNAUTHENTICATED", (status, body)
    print("PASS logout invalidates the session at gateway and downstream services")

    status, body, _ = call(gateway, "/api/v1/auth/login", "POST", login_body)
    assert status == 200
    active_token = body["data"]["token"]
    processes[MODULES.index("user-service")].terminate()
    processes[MODULES.index("user-service")].wait(timeout=10)
    for port in [gateway, PORTS["account-service"]]:
        status, body, _ = call(port, "/actuator/info", token=active_token)
        assert status == 503 and body["code"] == "SERVICE_UNAVAILABLE", (status, body)
    print("PASS authentication dependency failure returns 503 and never bypasses verification")


def main():
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Java 21+ is required")
    jars = {m: ROOT / m / "target" / f"{m}-0.1.0-SNAPSHOT.jar" for m in MODULES}
    for module, jar in jars.items():
        if not jar.exists():
            raise RuntimeError(f"Missing {jar}; run Maven clean install first")
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", PORTS[module]))
    logs = ROOT / "logs" / "smoke"
    logs.mkdir(parents=True, exist_ok=True)
    processes, streams = [], []
    password = secrets.token_urlsafe(24)
    service_secret = secrets.token_urlsafe(32)
    try:
        for module in MODULES:
            env = os.environ.copy()
            env["BANK_INTERNAL_TOKEN"] = service_secret
            env["BANK_CREDENTIAL_STORE"] = "demo"
            env["BANK_DEMO_ENABLED"] = "true"
            env["BANK_DEMO_IDENTITY"] = "smoke-user"
            env["BANK_DEMO_PASSWORD"] = password
            env["BANK_SESSION_TTL"] = "30m"
            env["SERVER_PORT"] = str(PORTS[module])
            env["SPRING_PROFILES_ACTIVE"] = "default"
            for name in MODULES[:-1]:
                env[name.upper().replace("-", "_") + "_URL"] = f"http://127.0.0.1:{PORTS[name]}"
            stream = (logs / f"{module}.log").open("w", encoding="utf-8")
            streams.append(stream)
            process = subprocess.Popen(
                [java, "-Xmx192m", "-Dfile.encoding=UTF-8", "-jar", str(jars[module])],
                cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            processes.append(process)
        deadline = time.monotonic() + 90
        pending = set(MODULES)
        while pending:
            for module in list(pending):
                if processes[MODULES.index(module)].poll() is not None:
                    raise RuntimeError(f"{module} exited; see {logs}")
                try:
                    data, _ = get(PORTS[module], "/actuator/health")
                    if data["status"] == "UP":
                        pending.remove(module)
                except (URLError, TimeoutError, OSError):
                    pass
            if time.monotonic() > deadline:
                raise RuntimeError(f"Startup timeout: {pending}; see {logs}")
            if pending:
                time.sleep(0.5)
        for module in MODULES[:-1]:
            domain = module.removesuffix("-service")
            path = f"/api/v1/{domain}/health"
            direct, _ = get(PORTS[module], path)
            routed, headers = get(PORTS["gateway"], path, "smoke-trace-123")
            assert direct["data"]["service"] == module, direct
            assert routed["data"]["service"] == module, routed
            assert routed["traceId"] == headers["X-Trace-Id"] == "smoke-trace-123"
            print(f"PASS {module}: direct health, gateway route, trace propagation")
        routed, headers = get(PORTS["gateway"], "/api/v1/user/health", "invalid trace!")
        assert routed["traceId"] == headers["X-Trace-Id"] != "invalid trace!"
        try:
            get(PORTS["gateway"], "/internal/v1/postings")
            raise AssertionError("Internal endpoint must not be routed")
        except HTTPError as error:
            assert error.code == 404, error.code
        print("PASS invalid trace replacement and internal route isolation")
        check_auth(processes, password, service_secret)
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for stream in streams:
            stream.close()

if __name__ == "__main__":
    main()
