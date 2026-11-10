# S4 数据库与环境

## 1. 启动

### 方式 A：本地已有 MySQL

```bash
# 1) 复制环境变量
cp .env.example .env
# 编辑 .env，填 DB_PASSWORD

# 2) 建库（一次执行）
mysql -u root -p < sql/schema.sql

# 3) 灌演示数据
mysql -u root -p payment_db < sql/init-data.sql
```

### 方式 B：Docker

```bash
docker compose -f docker-compose.dev.yml up -d mysql redis
# 等 MySQL ready 后自动执行 schema.sql（docker-compose.dev.yml 里已配置）
```

## 2. 验证

```bash
mysql -u root -p payment_db < sql/verify.sql
```

预期输出：

| 检查项 | 预期值 |
|---|---|
| table_count | 13 |
| fk_count | 15 |
| ACC_S2_001 / ACC_S2_002 余额不变式 | 两个 OK |
| 幂等键重复插入 | Duplicate entry（约束生效） |

验证输出见 `验证输出.txt`。

## 3. 演示账户

| 用户名 | 密码 | 账户 | 初始余额 |
|---|---|---|---|
| zhangsan | demo123 | ACC_S2_001 | 50000.00 |
| lisi | demo123 | ACC_S2_002 | 30000.00 |
| admin | admin123 | 运营（UC-TR-08/09） | — |

## 4. 关键约束

- 金额列全部 `DECIMAL(18,2)`，无浮点
- 幂等键：`UNIQUE(identity_id, business_type, request_id)` — 同键同内容返回原交易，同键不同内容冲突
- 业务唯一键：`trade_no`、`uk_transaction_flow_role(trade_no, account_id, entry_role)` 防止双方同方向重复记流水
- CHECK 约束（MySQL 8.0.16+ 生效）：金额 > 0、交易状态枚举、业务类型枚举等

## 5. 交接信息

| 给谁 | 内容 |
|---|---|
| S1 | DB_URL = `jdbc:mysql://localhost:3306/payment_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai` |
| S2/S3 | 13 张表结构见 `sql/schema.sql`；`transaction.status` 7 态、`business_type` 5 值见 DDL 注释 |
| S3 | `mock_channel_deposit` 表已合入 schema.sql（S3 模拟渠道入金记录）；`risk_rule` 已预置 RK-02 LIMIT_POLICY（存款单笔 50000、出账单笔 10000、出账日累计 50000）；演示账户 `ACC_S2_001`/`ACC_S2_002` |
| S5 | 演示账号见上方 |

## 6. 文件清单

| 文件 | 作用 |
|---|---|
| `sql/schema.sql` | 13 表 DDL，可重复执行 |
| `sql/init-data.sql` | 演示数据，幂等（INSERT IGNORE） |
| `sql/verify.sql` | 验收脚本，幂等（先清 DEMO 数据再验证） |
| `docker-compose.dev.yml` | MySQL 8 + Redis 7 本地开发环境 |
| `.env.example` | 环境变量模板，复制为 `.env` |

## 7. 数据库账号（S3 服务用）

S3 的 transaction-service / risk-service 默认用以下 MySQL 账号（见各服务 `application.yml`）：

| 账号 | 用途 | 权限 |
|---|---|---|
| `transaction_service` | 交易服务 | 写 `transaction` 原单字段、`mock_channel_deposit`；读其余表 |
| `risk_service` | 风控服务 | 写 `risk_rule`、`risk_event`；读其余表 |

创建账号（root 执行，密码自行替换）：

```sql
CREATE USER IF NOT EXISTS 'transaction_service'@'%' IDENTIFIED BY 'change_me';
CREATE USER IF NOT EXISTS 'risk_service'@'%' IDENTIFIED BY 'change_me';

GRANT SELECT, INSERT, UPDATE ON payment_db.`transaction` TO 'transaction_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.mock_channel_deposit TO 'transaction_service'@'%';
GRANT SELECT ON payment_db.account TO 'transaction_service'@'%';
GRANT SELECT ON payment_db.account_reservation TO 'transaction_service'@'%';

GRANT SELECT, INSERT, UPDATE ON payment_db.risk_rule TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_event TO 'risk_service'@'%';
GRANT SELECT ON payment_db.account TO 'risk_service'@'%';
GRANT SELECT ON payment_db.`transaction` TO 'risk_service'@'%';

FLUSH PRIVILEGES;
```

> 本地联调也可以直接用 root：把各服务 `application.yml` 里的 `TRANSACTION_DB_USER`/`RISK_DB_USER` 改成 `root`，或通过环境变量覆盖。
