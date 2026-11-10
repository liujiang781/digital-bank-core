"""Build first, then run: python scripts/smoke.py. Stops only processes it starts."""
import json
import os
from pathlib import Path
import shutil
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
    try:
        for module in MODULES:
            env = os.environ.copy()
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
