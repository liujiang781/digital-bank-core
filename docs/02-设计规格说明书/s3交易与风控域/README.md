# s3交易与风控域 交付

> **先看 [`00-交付总览.md`](00-交付总览.md)。**
>
> 本目录是给你直接打开的最终副本：五张图、模块设计、ADR 和预览图都在这里。
> 需求口径全部按《实验一需求规格说明书》整合版 **v1.2** 表 1-3；争议点见 [`01-需求口径与争议处理.md`](01-需求口径与争议处理.md)。
>
> 本目录不再依赖 Git 仓库；如果后续要提交，按下面表格的“提交路径”复制即可。

## 文件对应关系

| 报告图号 | 提交路径（仓库相对路径） | 本目录位置 |
|---|---|---|
| 图 2-7 本行转账鲁棒图 | `docs/uml/robustness/transfer.puml` | `01-UML源文件/robustness/transfer.puml` |
| 图 2-8 本行转账序列图 | `docs/uml/sequence/transfer.puml` | `01-UML源文件/sequence/transfer.puml` |
| 图 2-9 风控拦截序列图 | `docs/uml/sequence/risk-check.puml` | `01-UML源文件/sequence/risk-check.puml` |
| 图 2-11 风控规则匹配活动图 | `docs/uml/activity/risk-match.puml` | `01-UML源文件/activity/risk-match.puml` |
| 图 2-12 幂等校验活动图 | `docs/uml/activity/idempotency.puml` | `01-UML源文件/activity/idempotency.puml` |
| 2.2.1 / 2.2.2 模块设计 | `docs/02-设计规格说明书/模块设计-交易域.md` | `02-设计文档/模块设计-交易与风控域.md` |
| 交接 D 属性清单 | — | `02-设计文档/交接D-交易与风控域属性清单.md` |
| ADR-003 | `docs/02-设计规格说明书/架构决策记录.md` | `02-设计文档/架构决策记录.md` |

> 提交路径仍按任务清单的 `模块设计-交易域.md`；本目录使用正式文件名 `模块设计-交易与风控域.md`，复制到仓库时按提交路径改名即可。

`03-预览图/` 里是同一批 `.puml` 用 PlantUML 1.2024.7 渲染出的 SVG/PNG，可直接双击查看；图中中文正常，五张图均已渲染通过。PNG 已按单页截图优化，最大边约 1140px（接近 A4 96dpi），可直接截图贴报告。

## 口径说明

- 需求口径统一按《实验一需求规格说明书》整合版 **v1.2** 表 1-3（沿用 S2 BR 编号）。
- 任务清单 1.1 的 8 条简化 BR 与 v1.2 冲突，已不采用；例如冻结是 **BR-03**、幂等是 **BR-11/12**、流水是 **BR-19**、本行转账是 **BR-15**、权限是 **BR-20**。
- 资金流水类统一为 `FundEntry`，字段采用 B 的 `entryRole`、`direction`、`bookBalanceAfter`；物理表建议 `fund_entry`，如 D 沿用任务清单表名可映射为 `transaction_flow`。
- 幂等键按 B 的 `IdempotencyService` 契约：`UK(identity_id, business_type, request_id)` + `request_hash`，不是单独的 `transaction.request_id`。
- 类名与状态按 B 的表 2-1：`TransactionStatus` 使用 `PENDING`/`SUCCEEDED`；`TransferOrder` 使用 `clearingStatus`；C 新增 4 类，复用 `AccessService`、`IdempotencyService`、`PostingService`，表 2-1 共 21 类。

## 快速查看

1. 先打开 `00-交付总览.md`，里面直接嵌了五张预览图。
2. 再看 `01-需求口径与争议处理.md`，确认每条争议按 v1.2 怎么处理。
3. 需要细节时看 `02-设计文档/模块设计-交易与风控域.md` 第 1.2 节 BR 对照表和第 6 节给 D 的字段清单。
4. 需要改图时，改 `01-UML源文件/` 里的 `.puml`，再用 PlantUML 重新导出。
