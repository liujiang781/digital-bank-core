# S2 账户服务运行与联调说明

`account-service` 负责账户生命周期、余额查询和资金原子记账，默认端口为 `8082`。交易受理、三元组幂等、模拟渠道和风控服务由 S3 提供；账户服务通过内部接口接收资金命令。

本模块以提交 `f331eabb2d82d10a15fd23d809af096f0a55a5e4` 的 Java 21 / Spring Boot 3.5.7 工程为基础，执行 [S2 与 S3 接口交接说明](docs/S2-S3接口交接说明.md)。实现说明和验证记录见 [S2 账户与记账实现说明](docs/S2-账户与记账实现说明.md)。

按组长分工，S2 的源码、测试、SQL 交接材料和说明全部放在 `backend/account-service/`，提交与交付均以本目录为边界。`backend/pom.xml`、`common`、其他业务服务、`deploy`、`frontend` 由对应负责人维护。需要独立部署执行的 SQL 交给 S4 纳入 `deploy`。

IDEA 从完整仓库的 `backend/pom.xml` 打开父工程，S2 日常编辑本模块的 `src/main/java/`。交付包只包含本模块，需要合入小组完整仓库。

## 1 环境与配置

需要 JDK 21、Maven Wrapper、MySQL 8.0.16 或更新的兼容版本。生产配置不自动建表；S4 负责初始化数据库和访问权限。

| 环境变量 | 默认值或要求 | 用途 |
|---|---|---|
| `SERVER_PORT` | `8082` | 账户服务监听端口 |
| `ACCOUNT_DB_URL` | `jdbc:mysql://localhost:3306/payment_db?serverTimezone=Asia/Shanghai&characterEncoding=utf8` | 同一主库的 JDBC URL |
| `ACCOUNT_DB_USER` | `account_service` | 数据库账户 |
| `ACCOUNT_DB_PASSWORD` | 无，运行时提供 | 数据库密码 |
| `ACCOUNT_INTERNAL_TOKEN` | 无；为空时拒绝内部调用 | S2 与 S3 共享的服务令牌 |
| `ACCOUNT_DEPOSIT_SINGLE_LIMIT` | `50000.00` | 未配置有效规则时的存款单笔上限 |
| `ACCOUNT_OUTGOING_SINGLE_LIMIT` | `10000.00` | 未配置有效规则时的出账单笔上限 |
| `ACCOUNT_OUTGOING_DAILY_LIMIT` | `50000.00` | 未配置有效规则时的出账日上限 |

启用的 `risk_rule.LIMIT_POLICY` 优先于默认限额，按 `rule_version DESC, id DESC` 选取。个人出账限额与系统限额取较小值。金额的接口类型是两位小数字符串，Java 使用 `BigDecimal`，数据库使用 `DECIMAL(18,2)`。

连接池为每个 MySQL 会话执行 `SET time_zone = '+08:00'`；DATETIME 以 JDBC LocalDateTime 读写，使自然日限额不依赖 JVM 或数据库主机的默认时区。资金写事务使用 READ COMMITTED，并通过交易行锁、固定账户锁序和账户锁内复核保证一致性；快照查询使用 REPEATABLE READ。

## 2 数据库初始化顺序

1. S4 在空的开发数据库执行 [实验二基础 schema](../../docs/02-设计规格说明书/s4数据库设计/sql/schema.sql)，创建 `payment_db` 的基础表。
2. 在同一个 `payment_db` 执行 [S2 增量脚本](src/main/resources/db/s2-incremental.sql)，创建 `account_command` 和 `account_reservation`。
3. S4 初始化真实关联的 `customer` 和 `account` 演示记录。`customer_id` 是客户标识，`identity_id` 是认证身份；不能混用。开户要求客户实名为 `VERIFIED`、客户状态为 `NORMAL`。
4. S3 提供并执行自己的模拟渠道增量脚本；S2 不创建或修改 S3 的 `mock_channel_deposit` 表。
5. 配置权限：S2 写账户、资金流水、状态日志及自身增量表，更新 `transaction` 的资金推进字段，读取所需客户、转账单和风控规则。S3 不直接更新余额或预留记录。

基础 schema 包含 `DROP TABLE`，只用于空的开发数据库初始化。已有数据环境只执行相应增量脚本。应用使用 `spring.sql.init.mode=never`，启动不会替代以上步骤。

使用 MySQL CLI 时，先从本机完整仓库根目录启动客户端，再执行以下相对路径命令；不依赖组员的盘符或项目绝对路径：

```sql
SOURCE docs/02-设计规格说明书/s4数据库设计/sql/schema.sql;
USE payment_db;
SOURCE backend/account-service/src/main/resources/db/s2-incremental.sql;
```

## 3 构建与启动

在 PowerShell 7 中进入仓库的 `backend` 目录，使用运行时输入的凭据：

```powershell
$env:JAVA_HOME = '<本机 JDK 21 目录>'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ACCOUNT_DB_URL = 'jdbc:mysql://127.0.0.1:3306/payment_db?serverTimezone=Asia/Shanghai&characterEncoding=utf8'
$env:ACCOUNT_DB_USER = 'account_service'
$env:ACCOUNT_DB_PASSWORD = Read-Host '数据库密码' -MaskInput
$env:ACCOUNT_INTERNAL_TOKEN = Read-Host '与 S3 相同的内部服务令牌' -MaskInput
.\mvnw.cmd -s .mvn/central-settings.xml -pl account-service -am clean install
.\mvnw.cmd -pl account-service spring-boot:run
```

也可启动已打包的 JAR：

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar account-service/target/account-service-0.1.0-SNAPSHOT.jar
```

S3 将 `ACCOUNT_SERVICE_URL` 设置为账户服务地址，两个进程设置相同的 `ACCOUNT_INTERNAL_TOKEN`。令牌使用至少 32 字节的随机值，只通过环境配置传递。

## 4 S1 用户认证适配

账户对外接口读取服务器设置的 Servlet `Principal`，忽略客户端的 `X-Customer-Id` 和 `X-Identity-Id`。没有服务器认证身份时返回 401。

S1 认证过滤器可提供 `com.digitalbank.account.security.Actor`：

```java
new Actor(identityId, customerId, roles, accountScope)
```

`roles` 使用 `OPERATIONS` 和 `RISK`；`accountScope` 是明确授权的账户标识集合。冻结允许有范围权限的运营或风控人员，解冻只允许运营人员。客户只访问本人账户。普通 `Principal.getName()` 按 `identity_id` 映射已实名的正常客户，不赋予后台账户范围权限。

本模块提供身份适配边界，尚需 S1 的真实登录、会话和认证过滤器接入后进行端到端验证。仅在网关透传普通身份头不会建立上述 Principal。模块测试使用测试框架提供的服务器身份，不提供可绕过登录的线上测试接口。

## 5 S3 内部接口

内部请求直接访问账户服务端口，带 `X-Internal-Token` 和透传的 `X-Trace-Id`；网关不路由 `/internal/v1/**`。令牌为空、缺失或不匹配时返回 401。

| 方法与路径 | 用途 |
|---|---|
| `POST /internal/v1/accounts/snapshots` | 授权账户快照与有效限额 |
| `POST /internal/v1/postings/deposit-acceptances` | 锁内确认存款可受理，写 `accepted_at` |
| `POST /internal/v1/postings/deposit-results` | 成功、失败、未知渠道结果 |
| `POST /internal/v1/postings/deposit-completions` | 存款成功结果的兼容入口 |
| `GET /internal/v1/postings/{tradeNo}` | 主库读取当前资金状态 |
| `POST /internal/v1/postings/reserve` | 取款或本行转账逐笔预留 |
| `POST /internal/v1/postings/withdrawal-completions` | 消耗预留并完成取款 |
| `POST /internal/v1/postings/internal-transfers` | 双账户原子记账 |
| `POST /internal/v1/postings/releases` | 明确本地失败后的逐笔释放 |
| `POST /internal/v1/postings/failures` | 风控拒绝或明确出账失败 |

S3 先认领 `transaction` 原单，再调用账户侧受理；收到 `PROCESSING` 后才调用模拟渠道。`channelRequestId` 固定为 `CHD` 加 `tradeNo`。所有回执须与原单的账户、金额、币种、类型一致，S2 不从回执创建新交易。

公共响应 `code=SUCCEEDED` 说明命令已处理，S3 还必须读取 `data.status`：账户侧业务拒绝持久化为 `REJECTED` 并通过 Outcome 返回；真正入账成功状态是 `SUCCESS`。`UNKNOWN` 保留未决状态，不能当成失败。网络超时后按原 `tradeNo` 查询或重试，不新建原单。

## 6 对外账户接口

前缀为 `/api/v1/accounts`，所有 POST 在 JSON 中携带 UUID `requestId`。提供 `X-Request-Id` 时必须与 JSON 一致。

| 方法与相对路径 | 用途 |
|---|---|
| `POST /` | 开户；每客户最多三个未注销账户 |
| `GET /`、`GET /{accountId}` | 授权账户列表与详情 |
| `GET /{accountId}/balance` | 同快照返回 B、R、A |
| `POST /{accountId}/freeze`、`/unfreeze` | 冻结及解除全部有效限制 |
| `POST /{accountId}/limits` | 本人个人出账限额 |
| `POST /{accountId}/close` | 零余额、零占用、无未决交易时注销 |
| `GET /{accountId}/operations` | 操作记录 |
| `GET /{accountId}/fund-entries` | 实际资金流水 |

分页参数 `page` 从 1 起，`pageSize` 默认 20、最大 100。时间筛选使用 `startTime/endTime` 的 ISO-8601 带时区格式。

解冻请求包括 `reason`、`releaseEvidence`、`resolvedRestrictionIds`、`requestId`；`resolvedRestrictionIds` 须覆盖当前全部有效限制。限制编号由详情或冻结响应的 `restrictions` 返回，不能用一个笼统说明直接解除多项限制。

取款和转账的 RECOVERING 原单只允许沿原失败原因和凭证恢复释放，不接受迟到的成功提交。失败原因使用大写机器码；超时、未知、响应丢失不能作为明确失败或释放依据。

## 7 验证方法

默认 `test` profile 使用 H2 的 MySQL 兼容模式，运行模块测试：

```powershell
.\mvnw.cmd -s .mvn/central-settings.xml -pl account-service -am test
```

测试覆盖范围和实际执行结果记录在实现说明中。H2 的测试结果与真实 MySQL 验证分别记录。真实 MySQL 必须执行基础 DDL、S2 增量、业务重复调用、并发和回滚检查；S1/S3 尚未接入时，不能将模块测试写成全组端到端联调已通过。

本次已在 H2 与 MySQL 8.4.13 各执行 47 项测试并全部通过。测试 profile 会重建测试表，只使用独立的空测试库。可用以下变量将同一套测试切换为 MySQL：`S2_TEST_DB_URL`、`S2_TEST_DB_USER`、`S2_TEST_DB_PASSWORD`、`S2_TEST_DB_DRIVER=com.mysql.cj.jdbc.Driver`、`S2_TEST_DB_INIT_SQL=SET time_zone = '+08:00'`。未设置时使用内存 H2。

联调后可用以下 SQL 核验余额与逐笔占用；异常查询应为空：

```sql
SELECT account_id, book_balance, reserved_amount,
       book_balance - reserved_amount AS available_balance
FROM account ORDER BY account_id;

SELECT account_id FROM account
WHERE book_balance < 0 OR reserved_amount < 0
   OR reserved_amount > book_balance;

SELECT a.account_id, a.reserved_amount,
       COALESCE(SUM(CASE WHEN r.status = 'RESERVED' THEN r.amount ELSE 0 END), 0) AS recorded_reserved
FROM account a LEFT JOIN account_reservation r ON r.account_id = a.account_id
GROUP BY a.account_id, a.reserved_amount
HAVING a.reserved_amount <> recorded_reserved;

SELECT trade_no, account_id, entry_role, COUNT(*) AS duplicate_count
FROM transaction_flow GROUP BY trade_no, account_id, entry_role
HAVING COUNT(*) > 1;
```
