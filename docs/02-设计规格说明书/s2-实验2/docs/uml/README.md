# 成员 B 的 UML 图号索引

| 图号 | 源文件 | 渲染文件 |
|---|---|---|
| 图 2-3 存款鲁棒图 | [robustness/deposit.puml](robustness/deposit.puml) | [out/deposit-robustness.svg](out/deposit-robustness.svg) |
| 图 2-4a 存款受理 | [sequence/deposit.puml](sequence/deposit.puml) | [out/deposit-request.svg](out/deposit-request.svg) |
| 图 2-4b 渠道确认 | [sequence/deposit.puml](sequence/deposit.puml) | [out/deposit-confirmation.svg](out/deposit-confirmation.svg) |
| 图 2-5 余额查询 | [sequence/account-query.puml](sequence/account-query.puml) | [out/account-query.svg](out/account-query.svg) |
| 图 2-6a 核心实体 | [class/design-class.puml](class/design-class.puml) | [out/design-class-core.svg](out/design-class-core.svg) |
| 图 2-6b 边界与控制 | [class/design-class.puml](class/design-class.puml) | [out/design-class-collaboration.svg](out/design-class-collaboration.svg) |

图 2-4、2-6 为一个源文件中的两块 PlantUML，各自仍只占一个分配图号。所有图使用 Microsoft YaHei。边界类通过控制类访问业务实体，Account.availableBalance() 是计算操作。

业务规则按本地需求书 v1.2：BR-03 冻结，BR-04 余额，BR-11/12 幂等，BR-13 存款入账，BR-19 流水，BR-20 权限。FundEntry 是独立资金流水实体。资金提交框表示必须满足的一致性契约，不能据此声称尚未实现的分布式原子提交已完成。
