# deploy · 部署与初始化

实验四部署入口。目标：在干净环境按本文从零跑到"数据库可验收、五服务可启动"。方案细节见根目录《部署方案.md》。

## 1. 目录规划

```
deploy/
├── README.md                 # 本文件
├── .env.example              # 环境变量模板，复制为 .env 后填写，.env 不进仓库
├── docker-compose.dev.yml    # 本地依赖：MySQL 8.0.36 + Redis 7（可选）
├── sql/
│   ├── schema.sql            # 21 张表 DDL，可重复执行（会 DROP 后重建）
│   ├── init-data.sql         # 演示数据，幂等
│   ├── verify.sql            # 验收脚本，幂等，需 mysql -f 执行
│   └── set-demo-password.sh  # 运行时生成 BCrypt 哈希并写入三个演示账号
├── scripts/
│   ├── start-all.ps1         # 按顺序启动五个服务并等待健康检查
│   ├── health-check.ps1      # 五个健康接口 + 网关路由检查
│   ├── verify-db.ps1         # root 执行数据库验收，输出证据
│   └── backup-restore.ps1    # 备份 / 恢复 / 演练
└── backups/                  # 备份归档，不入库
```

## 2. 依赖环境

方式 A：本机 MySQL 8.0（当前采用）

```bash
# Git Bash，root 执行
mysql -u root -p < deploy/sql/schema.sql
mysql -u root -p payment_db < deploy/sql/init-data.sql
```

方式 B：Docker Compose

```bash
docker compose -f deploy/docker-compose.dev.yml up -d mysql
docker compose -f deploy/docker-compose.dev.yml --profile optional up -d redis
```

Compose 已将 `schema.sql`、`init-data.sql` 挂载到 `/docker-entrypoint-initdb.d/`，首次启动自动执行。

> MySQL 必须 8.0.16 及以上 CHECK 约束才生效；本机已于 2026-11-19 升级到 8.0.46，实测 48 条 CHECK 生效。

## 3. 演示登录口令

`init-data.sql` 已内置与 S2/S3 文档一致的演示口令，建库后可直接登录：

| 身份标识 | 用户名 | 口令 | 账户 |
|---|---|---|---|
| `UID-000001` | zhangsan | `demo123` | `ACC_S2_001`（50000.00） |
| `UID-000002` | lisi | `demo123` | `ACC_S2_002`（30000.00） |
| `OPS-ADMIN` | admin | `admin123` | 运营 |

库中保存的是 BCrypt（`$2a$12`）哈希，不是明文。需要改口令时执行（仅首次建库后或改口令时执行一次）：

```bash
bash deploy/sql/set-demo-password.sh
```

按提示输入个人口令、运营口令、MySQL root 口令（均不回显），脚本运行时生成 BCrypt 哈希写入三个账号。
重跑 `init-data.sql` 不会覆盖已设置的口令；只有执行 `schema.sql` 重建表后才需要再执行一次。

## 4. 服务账号与授权

root 执行一次，口令自行替换为随机值：

```sql
CREATE USER IF NOT EXISTS 'bank_user'@'%'          IDENTIFIED BY '<随机口令>';
CREATE USER IF NOT EXISTS 'account_service'@'%'    IDENTIFIED BY '<随机口令>';
CREATE USER IF NOT EXISTS 'transaction_service'@'%' IDENTIFIED BY '<随机口令>';
CREATE USER IF NOT EXISTS 'risk_service'@'%'       IDENTIFIED BY '<随机口令>';

GRANT SELECT, INSERT, UPDATE         ON payment_db.customer             TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE         ON payment_db.credential           TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE         ON payment_db.user_command         TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_db.staff_authorization   TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_db.staff_account_scope   TO 'bank_user'@'%';
GRANT SELECT, INSERT                 ON payment_db.audit_log            TO 'bank_user'@'%';
GRANT SELECT                         ON payment_db.account              TO 'bank_user'@'%';

GRANT SELECT, INSERT, UPDATE ON payment_db.account             TO 'account_service'@'%';
GRANT SELECT, INSERT         ON payment_db.account_status_log  TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.account_command     TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.account_reservation TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.transaction_flow    TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.`transaction`       TO 'account_service'@'%';
GRANT SELECT, INSERT         ON payment_db.audit_log           TO 'account_service'@'%';
GRANT SELECT ON payment_db.customer        TO 'account_service'@'%';
GRANT SELECT ON payment_db.transfer_order  TO 'account_service'@'%';
GRANT SELECT ON payment_db.risk_rule       TO 'account_service'@'%';
GRANT SELECT ON payment_db.risk_event      TO 'account_service'@'%';
GRANT LOCK TABLES ON payment_db.*          TO 'account_service'@'%';

GRANT SELECT, INSERT, UPDATE ON payment_db.`transaction`          TO 'transaction_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.mock_channel_deposit   TO 'transaction_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.trade_manual_action    TO 'transaction_service'@'%';
GRANT SELECT, INSERT         ON payment_db.audit_log              TO 'transaction_service'@'%';
GRANT SELECT ON payment_db.account            TO 'transaction_service'@'%';
GRANT SELECT ON payment_db.account_reservation TO 'transaction_service'@'%';

GRANT SELECT, INSERT, UPDATE         ON payment_db.risk_rule             TO 'risk_service'@'%';
GRANT SELECT, INSERT                 ON payment_db.risk_event            TO 'risk_service'@'%';
GRANT SELECT, INSERT                 ON payment_db.risk_alert_disposal   TO 'risk_service'@'%';
GRANT SELECT, INSERT, UPDATE         ON payment_db.risk_change_request   TO 'risk_service'@'%';
GRANT SELECT, INSERT                 ON payment_db.risk_change_approval  TO 'risk_service'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_db.risk_list_entry       TO 'risk_service'@'%';
GRANT SELECT, INSERT                 ON payment_db.audit_log             TO 'risk_service'@'%';
GRANT SELECT ON payment_db.account     TO 'risk_service'@'%';
GRANT SELECT ON payment_db.`transaction` TO 'risk_service'@'%';

FLUSH PRIVILEGES;
```

## 5. 构建与启动

```powershell
cd backend
.\mvnw.cmd clean install
```

必须先 `install`，否则单独启动模块解析不到父 POM 与 `common`。

```powershell
# 五个终端，工作目录均为 backend
.\mvnw.cmd -pl user-service spring-boot:run
.\mvnw.cmd -pl account-service spring-boot:run
.\mvnw.cmd -pl transaction-service spring-boot:run
.\mvnw.cmd -pl risk-service spring-boot:run
$env:SERVER_PORT = "8085"
.\mvnw.cmd -pl gateway spring-boot:run
```

`SERVER_PORT` 只在网关终端设置；前四个业务服务不要设，否则会全部绑到 8085 而只剩一个能起。

启动顺序：MySQL → 建库与演示数据 → 四个业务服务 → 网关。网关最后起，避免路由到未就绪实例。

一键拉起：

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/start-all.ps1
```

停止（关闭本机所有由 `start-all.ps1` 拉起的服务）：

```powershell
Get-Process java -ErrorAction SilentlyContinue | Stop-Process
```

确认端口已释放：

```powershell
Get-NetTCPConnection -LocalPort 8081,8082,8083,8084,8085 -State Listen -ErrorAction SilentlyContinue
```

## 6. 健康检查

| 组件 | 地址 |
|---|---|
| 网关 | `http://localhost:8085/actuator/health` |
| user | `http://localhost:8085/api/v1/user/health` |
| account | `http://localhost:8085/api/v1/account/health` |
| transaction | `http://localhost:8085/api/v1/transaction/health` |
| risk | `http://localhost:8085/api/v1/risk/health` |

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/health-check.ps1
```

自动化冒烟（五 JAR 起在 18080–18084）：

```bash
python backend/scripts/smoke.py
```

## 7. 数据库验收

```bash
# 需 root；-f 让脚本遇故意制造的 Duplicate entry 后继续执行清理
mysql -f -u root -p payment_db < deploy/sql/verify.sql
```

期望：表数 13、外键 15、两账户余额不变式 OK、凭据为 BCrypt、按 `UID-000001` 能查到 1 行、两处 Duplicate entry 证明约束生效、存款 100 后余额 50100 且流水 1 条。

一键生成证据（root 口令不回显）：

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/verify-db.ps1
```

## 8. 备份与恢复

```powershell
# 备份
powershell -ExecutionPolicy Bypass -File deploy/scripts/backup-restore.ps1 -Mode backup

# 恢复（先确认已备份）
powershell -ExecutionPolicy Bypass -File deploy/scripts/backup-restore.ps1 -Mode restore -File deploy/backups/payment_db-20261117-1015.sql

# 演练（在独立库建表、备份、删表、恢复、比对）
powershell -ExecutionPolicy Bypass -File deploy/scripts/backup-restore.ps1 -Mode drill
```

## 9. 应用回滚

1. 记录当前提交：`git rev-parse HEAD`。
2. 回滚代码：`git checkout <上一个提交>` → `.\mvnw.cmd clean install` → 重启。
3. 数据库：结构变更前先备份，回滚用备份恢复；**不能**靠重跑 `schema.sql` 回滚（它会 DROP 表）。
4. 产物按 `v0.1-20261117` 打标，不用 `latest`。

## 10. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 网关启动失败，端口占用 | 8080 被 `NI Application Web Server` 占用 | `SERVER_PORT=8085` |
| PowerShell 执行 `<` 报错 | PowerShell 不支持输入重定向 | 用 Git Bash，或 `Get-Content x.sql \| mysql ...` |
| 表数只有 11 | 非 root 账号查询 `information_schema` 被权限过滤 | 用 root 或全库 SELECT 账号查询 |
| CHECK 数量为 0 | MySQL < 8.0.16 | 升级数据库；当前靠应用层校验 |
| 登录失败 `using password: NO` | `mysql` 客户端被 `-p` 后的管道吞掉口令 | 用 `MYSQL_PWD` 环境变量或交互执行 |
| 重跑 init-data 后登录不了 | 曾用旧版脚本覆盖了口令 | 重新执行 `set-demo-password.sh` |
| `ERROR 1054 Unknown column 'CUS' in where clause` | PowerShell 给原生 `mysql.exe` 传参时会丢掉参数里的双引号，`customer_id="CUS-ADMIN"` 变成 `customer_id=CUS-ADMIN` | SQL 里的字符串用单引号，或直接进 `mysql -u root -p payment_db` 交互模式粘贴 |
| `$2a$12$...` 哈希写进去变成空 | PowerShell 双引号里 `$2a`、`$12` 被当变量展开 | 用单引号包裹整条 SQL，或用 here-string `@' ... '@` |
| 服务账号 `mysql -u account_service -p` 报 `ERROR 1045`，但服务能连上库 | 服务账号按 `'user'@'%'` 建，客户端不带 `-h` 时走 `localhost` 命名管道，匹配不到 `%` 账号 | 自查权限时加 `-h 127.0.0.1 --protocol=TCP`，与 JDBC 的连接方式保持一致 |

## 11. 约定

- 数据库口令、内部令牌通过 `.env` 注入，`.env`、`.env.local`、`deploy/backups/` 不进仓库，只提交 `.env.example`。
- 演示数据脚本必须幂等，方便答辩现场重置。
- 镜像/产物必须打 tag，不用 `latest`，否则无法回滚。
- 健康检查返回 UP 只说明进程存活，不代表业务功能已完成。

## S1 增量（来自 main）

- 已有数据库升级只需执行 `deploy/sql/s1-incremental.sql`，不要重跑会清库的 `schema.sql` / `init-data.sql`
- `schema.sql` 当前为 21 张表、16 条外键、48 条 CHECK（基线 13 + S1 新增 `user_command`/`staff_authorization`/`staff_account_scope` + S3 新增 5 张风控与人工动作表）
- 管理员与员工认证样例见 `auth-admin.example.yml`、`auth-staff.example.yml`
- S1 接口与授权说明见 `docs/04-测试报告/s1/S1-用户管理接口交接.md`