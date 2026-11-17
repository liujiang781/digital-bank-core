"""Export only Surefire results, excluding environment properties and raw logs."""
import json
import hashlib
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime

root = Path(__file__).resolve().parents[1]
output = root / 'docs/04-测试报告/证据/2026-11-17'
output.mkdir(parents=True, exist_ok=True)
suites = []
for report in sorted((root / 'backend').glob('*/target/surefire-reports/TEST-*.xml')):
    element = ET.parse(report).getroot()
    cases = []
    for case in element.findall('testcase'):
        state = 'passed'
        if case.find('failure') is not None:
            state = 'failed'
        elif case.find('error') is not None:
            state = 'error'
        elif case.find('skipped') is not None:
            state = 'skipped'
        cases.append(dict(name=case.get('name'), class_name=case.get('classname'),
                          seconds=case.get('time'), result=state))
    suites.append(dict(module=report.parents[2].name, name=element.get('name'),
        timestamp=element.get('timestamp'), tests=int(element.get('tests', 0)),
        failures=int(element.get('failures', 0)), errors=int(element.get('errors', 0)),
        skipped=int(element.get('skipped', 0)), cases=cases))
result = dict(generated_at=datetime.now().astimezone().isoformat(),
    baseline=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
    working_changes='新增令牌路径测试、网关鉴权测试、部署凭据兼容性测试与 gateway 测试依赖；修复前端代理端口及演示凭据哈希',
    java='21.0.7', maven='3.9.11', scope='本地 JUnit、Spring 测试上下文和 H2；非真实 MySQL 全链路验收',
    totals={key: sum(s[key] for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')}, suites=suites)
(output / 'junit-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
tracked_sources = [
    'backend/common/src/main/java/com/digitalbank/common/auth/AuthTokens.java',
    'backend/common/src/test/java/com/digitalbank/common/auth/AuthTokensPathTest.java',
    'backend/common/src/main/java/com/digitalbank/common/web/AuthenticationFilter.java',
    'backend/gateway/pom.xml',
    'backend/gateway/src/main/java/com/digitalbank/gateway/filter/AuthenticationWebFilter.java',
    'backend/gateway/src/test/java/com/digitalbank/gateway/filter/AuthenticationWebFilterTest.java',
    'backend/user-service/src/main/java/com/digitalbank/user/service/SessionService.java',
    'scripts/test-exp4-s1-http.py', 'frontend/vite.config.js', 'deploy/sql/init-data.sql',
    'backend/user-service/src/test/java/com/digitalbank/user/repository/DemoSeedAuthenticationTest.java']
(output / 'source-sha256.json').write_text(json.dumps({
    name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in tracked_sources
}, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result['totals']))
