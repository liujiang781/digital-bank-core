# S3 实验四真实 MySQL 跨服务联调记录

> 记录人：S3（交易与风控）。日期：2026-11-17。代码基线：`origin/main@16034e3`（Spring Boot 3.5.7 / JDK 21）+ S3 币种校验修复（DEF-S3-001）叠加。
> 本文是真实服务与真实 MySQL 的端到端执行记录，补充《S3-存款与风控功能测试记录.md》中此前标注"未执行"的跨服务部分。

## 1. 环境

| 项目 | 值 |
|---|---|
| MySQL | 本机 8.0.13，`127.0.0.1:3306`，库 `payment_db`（13 张表，S4 `schema.sql` 建库） |
| 演示数据 | S4 `init-data.sql`；账户 `ACC_S2_001=50000.00`、`ACC_S2_002=30000.00`；风控 `RK-02(50000/10000/50000)` |
| 登录账号 | `UID-000001` / `UID-000002`，口令 `demo123`（本机 BCrypt 写入） |
| 服务 | user-service 8081、account-service 8082、transaction-service 8083、risk-service 8084 |
| 网关 | 18080（本机 8080 被 `NI Application Web Server` 占用，沿用 S4 口径；团队约定仍为 8080） |
| 内部令牌 | 本机临时生成（≥32 字节），S2/S3 同值、风控同值、渠道演示独立值 |
| 构建命令 | `backend> .\mvnw.cmd -o -DskipTests install`；服务以 `java -jar <module>-0.1.0-SNAPSHOT.jar` 启动 |

## 2. 场景与结果

请求经网关 `http://localhost:18080`，`Authorization: Bearer <登录令牌>`。每笔新业务用新 UUID 作 `requestId`。

### 2.1 登录与账户查询

| 步骤 | 请求 | 实际输出 |
|---|---|---|
| 登录 | `POST /api/v1/auth/login`，`{"identityId":"UID-000001","password":"demo123"}` | HTTP 200，`data.token` 返回（`data.customer.identityId=UID-000001`） |
| 查余额（前） | `GET /api/v1/accounts/ACC_S2_001/balance` | `bookBalance=50000.00`、`availableBalance=50000.00` |

### 2.2 成功存款 + 幂等

| 步骤 | 请求/操作 | 实际输出 |
|---|---|---|
| 存款 | `POST /api/v1/deposits`，`amount=100.00`、`requestId=R1` | HTTP 200，`status=SUCCESS`，`tradeNo=TRD202611179836A6F9` |
| 查余额（后） | `GET .../balance` | `bookBalance=50100.00`（只加 100） |
| 重放 | 同 `R1`、同金额再提交 | HTTP 200，返回同一 `tradeNo` |
| 冲突 | 同 `R1`、金额改 `200.00` | HTTP 409 `REQUEST_CONFLICT` |
| 查余额（重放/冲突后） | `GET .../balance` | `bookBalance=50100.00`（未重复入账） |
| 查流水 | `GET .../fund-entries` | 该单 1 条：`DEPOSIT_IN / CREDIT / 100.00 / bookBalanceAfter=50100.00` |

### 2.3 渠道三态（FAILED / UNKNOWN / 未决收敛）

| 步骤 | 操作 | 实际输出 |
|---|---|---|
| 切失败 | `POST :8083/internal/v1/channel/mode` `{"result":"FAILED"}` | 200 |
| 存款（失败渠道） | `amount=100.00`、`requestId=R2` | HTTP 200，`status=FAILED`，`tradeNo=TRD202611174B6065C4` |
| 查渠道记录 | `GET :8083/internal/v1/channel/deposits/{tradeNo}` | `result=FAILED`、`reasonCode=CHANNEL_DECLINED`、`evidenceId=EVD...` |
| 查余额 | `GET .../balance` | `bookBalance=50100.00`（不变） |
| 切未决 | `{"result":"UNKNOWN"}` | 200 |
| 存款（未决渠道） | `amount=100.00`、`requestId=R3` | HTTP 200，`status=UNKNOWN`，`tradeNo=TRD20261117F1EA18F7` |
| 查渠道记录 | `GET .../channel/deposits/{tradeNo}` | `result=UNKNOWN`、`reasonCode=CHANNEL_TIMEOUT`、`evidenceId` 空、`nextCheckAt` 非空 |
| 查余额 | `GET .../balance` | `bookBalance=50100.00`（未决不计成功） |
| 切成功并核对 | `{"result":"SUCCESS"}` 后 `GET /api/v1/transactions/{tradeNo}` | 第 1 次 `status=SUCCESS`（`failureReason` 暂留 `CHANNEL_TIMEOUT`，符合交接文档提示）；第 2 次 `status=SUCCESS` |
| 查余额 | `GET .../balance` | `bookBalance=50200.00`（明确成功后只入账一次） |

### 2.4 风控拒绝 / 币种 / 越权

| 步骤 | 请求/操作 | 实际输出 |
|---|---|---|
| 单笔超限 | 存款 `amount=50000.01` | HTTP 422，`code=SINGLE_LIMIT_EXCEEDED`，`message=风控拒绝: SINGLE_LIMIT_EXCEEDED` |
| 非法币种 | 存款 `amount=100.00`、`currency=USD` | HTTP 400，`code=INVALID_ARGUMENT`，`message=币种固定为 CNY`（DEF-S3-001 修复生效） |
| 越权查询 | `UID-000002` 查 `UID-000001` 的 `TRD202611179836A6F9` | HTTP 404，`code=TRADE_NOT_FOUND` |

## 3. 数据库最终状态（root 查询）

`SELECT ... FROM payment_db.\`transaction\``：

| trade_no | business_type | status | amount |
|---|---|---|---|
| TRD202611179836A6F9 | DEPOSIT | SUCCESS | 100.00 |
| TRD202611174B6065C4 | DEPOSIT | FAILED | 100.00 |
| TRD20261117F1EA18F7 | DEPOSIT | SUCCESS | 100.00 |
| TRD20261117DA6BC24E | DEPOSIT | REJECTED | 50000.01 |

`mock_channel_deposit`：3 行（两笔 SUCCESS、一笔 FAILED，各 `CHD+tradeNo` 唯一），**REJECTED 那笔无渠道记录**。

`risk_event`：1 行，`TRD20261117DA6BC24E / RK-02 / SINGLE_LIMIT / REJECT`。

`transaction_flow`：2 行，均为 `DEPOSIT_IN / CREDIT / 100.00`（只对应两笔成功存款；FAILED、REJECTED 无流水）。

最终余额：`ACC_S2_001.bookBalance=50200.00`（50000 + 两次成功各 100；失败与拒绝未入账）。

## 4. 结论

- 存款成功只入账一次、重放返回原单、同键异内容 409、渠道 FAILED/UNKNOWN 不记成功、UNKNOWN 明确后收敛且只入账一次、风控拒绝不产生渠道与流水、非法币种 400、越权查询 404 —— 全部与设计口径一致。
- 真实 MySQL + 五服务 + 网关的完整链路本次已验证通过，可作为 S3 侧跨服务联调证据；S5 若需截图，可复跑上述命令与查询。

## 5. 复现命令（摘要）

```powershell
# 建库与演示数据（S4 包）
mysql -u root -p < deploy/sql/schema.sql
mysql -u root -p payment_db < deploy/sql/init-data.sql
# 演示口令 BCrypt、服务账号授权、内部令牌（本机临时值）
# 构建并启动五服务（网关 18080）
cd backend && .\mvnw.cmd -o -DskipTests install
java -jar user-service/target/user-service-0.1.0-SNAPSHOT.jar   # 8081
java -jar account-service/target/account-service-0.1.0-SNAPSHOT.jar # 8082
java -jar transaction-service/target/transaction-service-0.1.0-SNAPSHOT.jar # 8083
java -jar risk-service/target/risk-service-0.1.0-SNAPSHOT.jar # 8084
SERVER_PORT=18080 java -jar gateway/target/gateway-0.1.0-SNAPSHOT.jar
```
