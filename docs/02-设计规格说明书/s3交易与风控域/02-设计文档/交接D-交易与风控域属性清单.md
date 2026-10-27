# s3交易与风控域 · 交接 D：属性清单（C）

> 依据：《实验一需求规格说明书》整合版 v1.2 表 1-3；B 的《交接D-设计类属性清单》与表 2-1；C 的《模块设计-交易与风控域》第 6 节。
> 用途：作为 D 的表 2-3 与 `schema.sql` 的直接输入。设计类型为逻辑类型，物理类型由 D 统一。
> 约定：金额 `DECIMAL(18,2)`，时间 `DATETIME`，字符集 `utf8mb4`；物理表可保留 `id BIGINT UNSIGNED` 代理主键，业务唯一键按本表。

## 1 `transfer_order`（转账单）

| 字段 | 类型 | 约束 | 空 | 说明 |
|---|---|---|---|---|
| `transfer_id` | VARCHAR(32) | PK/UK | 否 | 转账单唯一标识 |
| `trade_no` | VARCHAR(32) | UK，FK → `transaction.trade_no` | 否 | 关联交易 |
| `transfer_type` | VARCHAR(16) | INTERNAL/EXTERNAL | 否 | 本行/跨行 |
| `payer_account_id` | VARCHAR(32) | FK → `account.account_id` | 否 | 付款方 |
| `payee_account_id` | VARCHAR(32) | FK → `account.account_id` | 有条件 | 本行必填；跨行为空 |
| `external_bank_code` | VARCHAR(32) | — | 有条件 | 跨行必填 |
| `external_payee_ref` | VARCHAR(64) | 脱敏 | 有条件 | 跨行必填 |
| `amount` | DECIMAL(18,2) | > 0 | 否 | 与交易一致 |
| `currency` | CHAR(3) | = 'CNY' | 否 | 课程模拟仅人民币 |
| `clearing_status` | VARCHAR(24) | NOT_APPLICABLE / SUBMITTED / UNKNOWN / CONFIRMED_SUCCESS / CONFIRMED_FAILURE | 否 | 本行固定 `NOT_APPLICABLE` |
| `created_at` | DATETIME | — | 否 | 技术字段 |

索引：`UK(transfer_id)`、`UK(trade_no)`、`IDX(payer_account_id)`、`IDX(payee_account_id)`。

## 2 `risk_rule`（风控规则）

| 字段 | 类型 | 约束 | 空 | 说明 |
|---|---|---|---|---|
| `rule_id` | VARCHAR(32) | PK/UK | 否 | `RK-01` 为内置账户状态规则；限额规则另一行 |
| `rule_type` | VARCHAR(24) | LIMIT_POLICY / ACCOUNT_STATUS | 否 | `ACCOUNT_STATUS` 为 C 扩展 |
| `deposit_single_limit` | DECIMAL(18,2) | > 0 | 有条件 | 存款单笔 50,000 |
| `outgoing_single_limit` | DECIMAL(18,2) | > 0 | 有条件 | 出账单笔 10,000 |
| `outgoing_daily_limit` | DECIMAL(18,2) | > 0 | 有条件 | 出账日累计 50,000 |
| `enabled` | TINYINT(1) | 0/1 | 否 | 基本资金约束不能因关闭失效 |
| `rule_version` | INT | > 0 | 否 | 命中版本 |
| `created_at` | DATETIME | — | 否 | 技术字段 |
| `updated_at` | DATETIME | — | 否 | 技术字段 |

说明：`LIMIT_POLICY` 行的三个限额非空；`ACCOUNT_STATUS` 行三个限额为空，仅用于 `risk_event.rule_id` 的内置规则登记。

## 3 `risk_event`（风险事件）

| 字段 | 类型 | 约束 | 空 | 说明 |
|---|---|---|---|---|
| `event_id` | VARCHAR(32) | PK/UK | 否 | 事件标识 |
| `trade_no` | VARCHAR(32) | FK → `transaction.trade_no` | 否 | 被拦截交易 |
| `rule_id` | VARCHAR(32) | FK → `risk_rule.rule_id` | 否 | 命中规则 |
| `rule_version` | INT | > 0 | 否 | 命中版本 |
| `operator_identity_id` | VARCHAR(64) | — | 否 | 系统/人工身份 |
| `occurred_at` | DATETIME | — | 否 | 命中时间 |
| `reason_code` | VARCHAR(32) | — | 否 | ACCOUNT_STATUS / SINGLE_LIMIT / DAILY_LIMIT |
| `result` | VARCHAR(16) | PASS / REJECT | 否 | 拦截事件当前用 REJECT |

索引：`UK(event_id)`、`IDX(trade_no)`、`IDX(rule_id, occurred_at)`。

## 4 关联表（C 域必需字段）

| 表 | 字段 | 说明 |
|---|---|---|
| `transaction` | `trade_no` UK、`identity_id`、`business_type`、`request_id`、`request_hash`、`account_id`、`amount`、`currency`、`status`、`created_at`、`accepted_at`、`completed_at`、`channel_evidence_id`、`last_channel_result`、`last_checked_at`、`failure_reason`；`UK(identity_id, business_type, request_id)` | B 的字段；`account_id` 为付款方主账户 |
| `fund_entry` | `entry_id` UK、`trade_no`、`account_id`、`entry_role`、`direction`、`amount`、`book_balance_after`、`occurred_at`；`UK(trade_no, account_id, entry_role)` | 类 `FundEntry`；如沿用任务清单表名可映射为 `transaction_flow` |
| `account` | `account_id` UK、`customer_id`、`currency`、`status`、`book_balance`、`reserved_amount`、`personal_single_limit`、`personal_daily_limit`、`version` | B 的字段；`available_balance` 为计算值 |
| `audit_log` | 操作者、时间、对象、动作、原因、结果、tradeNo | 由 D 统一设计 |

## 5 外键与一致性

- `transaction.account_id → account.account_id`
- `transfer_order.trade_no → transaction.trade_no`
- `transfer_order.payer_account_id / payee_account_id → account.account_id`
- `risk_event.trade_no → transaction.trade_no`
- `risk_event.rule_id → risk_rule.rule_id`
- `fund_entry.trade_no → transaction.trade_no`
- `fund_entry.account_id → account.account_id`

## 6 C 对 D 的三个提醒

1. **幂等唯一键**必须是 `UK(identity_id, business_type, request_id)`，不能退化为单独的 `request_id`；`request_hash` 用于同键异内容冲突。
2. **资金流水只记实际资金事件**：`FundEntry` 只在 `debitReserved`/`credit` 成功时写入；拒绝、预留、释放不写。
3. **两条转账流水**：本行转账成功时，同一 `trade_no` 下写 `TRANSFER_OUT` 与 `TRANSFER_IN` 两条 `FundEntry`，`book_balance_after` 分别为双方提交后的 B。
