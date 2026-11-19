# 交接 D：设计类属性清单

本表只依据本地《实验一需求规格说明书》整合版 v1.2，面向实验二的逻辑设计。属性均使用设计类型，物理字段类型、表划分与索引由 D 决定。本成果未改动原需求书或远端仓库。

七个实体的名称和职责来自需求书表 1-4；具体字段名、枚举值、并发版本及渠道依据等属于本次设计细化。下表“设计新增”说明新增的是设计表达，不能理解为新增已批准业务范围。TransferOrder、RiskRule、RiskEvent 的详细属性和操作是为避免阻塞交接形成的本地协作建议，尚未经成员 C 确认；本表提供完整建议值，不以空白项冒充完成评审。

## 1 账户域实体

“可空”为“否”的集合允许为空集合；“有条件”必须满足同一行写明的条件。金额均为精确 Decimal，不使用二进制浮点；本实验仅 CNY，正金额最多两位小数，余额允许零。以下 ID 为稳定、不透明的逻辑标识。

| 类名 | 属性名 | 设计类型 | 约束与含义 | 可空 | 需求来源 | 设计新增 |
|---|---|---|---|---|---|---|
| Customer | customerId | String | 客户唯一标识；创建后稳定 | 否 | 表 1-4 Customer | 字段名细化 |
| Customer | identityId | String | 绑定认证身份；同一客户绑定身份唯一；不保存令牌 | 否 | 表 1-4；NFR-01/07 | 身份映射设计 |
| Customer | realNameStatus | RealNameStatus | 反映模拟实名状态；枚举见第 3 节 | 否 | UC-ID-01；表 1-4 | 枚举设计 |
| Account | accountId | String | 本行账户唯一标识；创建后稳定 | 否 | UC-AC-01；表 1-4 | 字段名细化 |
| Account | customerId | String | 恰好归属一个 Customer；不能任意改归属 | 否 | 表 1-4；BR-20 | 关联键设计 |
| Account | currency | Currency | 固定为 CNY | 否 | BR-01 | 枚举设计 |
| Account | status | AccountStatus | NORMAL、FROZEN、CLOSED；正常和冻结均计入未注销数；UC-AC-07 规定注销须 B=R=0 且无未决交易 | 否 | BR-02/03/09；UC-AC-07 | 枚举设计 |
| Account | bookBalance | Decimal | 账面余额 B≥0；开户初始 0 | 否 | BR-02/04 | 字段名细化 |
| Account | reservedAmount | Decimal | 占用 R，0≤R≤B；开户初始 0 | 否 | BR-02/04/14 | 字段名细化 |
| Account | activeFreezeReasonIds | Set<String> | 有效冻结原因引用集合；清空全部有效原因并有解除依据后才允许运营解冻 | 否 | BR-09；NFR-10 | 原因关联设计 |
| Account | personalSingleLimit | Decimal | 可选个人出账单笔限额；正数、两位小数；适用限额取个人与全局较严者 | 是 | UC-AC-08；BR-05 | 个人限额设计建议 |
| Account | personalDailyLimit | Decimal | 可选个人出账日限额；正数、两位小数；不替代全局限额 | 是 | UC-AC-08；BR-05 | 个人限额设计建议 |
| Account | version | Integer | 从 0 递增；并发条件提交或等效锁机制使用；同一提交读取和验证 | 否 | NFR-02；BR-04 | 并发控制设计 |
| Transaction | tradeNo | String | 全局唯一交易号；资金与外部处理沿用原号 | 否 | BR-11/12；NFR-03 | 字段名细化 |
| Transaction | identityId | String | 发起身份；是幂等作用域的一部分；不替代对象权限检查 | 否 | BR-11/12/20 | 幂等作用域设计 |
| Transaction | businessType | BusinessType | DEPOSIT、WITHDRAWAL、INTERNAL_TRANSFER、EXTERNAL_TRANSFER | 否 | UC-TR-01～04；BR-11/12 | 枚举设计 |
| Transaction | requestId | String | 同一身份、业务类型内由客户端提供的请求标识；不可重新分配以绕过重试语义 | 否 | BR-11/12；NFR-03 | 字段名细化 |
| Transaction | requestHash | String | 规范化业务内容摘要；不得将非法精度舍入为合法金额；同幂等键摘要不同时冲突 | 否 | BR-11/12 | 内容冲突检测设计 |
| Transaction | accountId | String | 请求主账户；存款为入账账户，出账为付款账户 | 否 | 表 1-4；UC-TR-01～04 | 关联角色设计 |
| Transaction | amount | Decimal | 合法已受理交易为正数、最多两位小数；非法请求细节留在处理记录，不能伪造成合法交易金额 | 否 | BR-01/13/14 | 字段名细化 |
| Transaction | currency | Currency | 固定为 CNY | 否 | BR-01 | 枚举设计 |
| Transaction | status | TransactionStatus | 七种状态见第 3 节；终态不能被迟到未知结果覆盖 | 否 | 2.4 交易处理状态；NFR-06 | 状态机设计 |
| Transaction | createdAt | Instant | 交易记录创建时间 | 否 | NFR-03 | 时间字段设计 |
| Transaction | acceptedAt | Instant | 正常账户及金额/限额通过，并完成受理提交的时间；受理前为空 | 是 | BR-03/13；NFR-03 | 区分已受理设计 |
| Transaction | completedAt | Instant | 进入 REJECTED、SUCCEEDED 或 FAILED 时记录；非终态为空 | 是 | 2.4 处理状态；NFR-03 | 终态时间设计 |
| Transaction | channelEvidenceId | String | 最近已验证渠道依据的引用；不得存完整令牌或敏感原始报文 | 是 | BR-13；NFR-03/09 | 可追溯依据设计 |
| Transaction | lastChannelResult | ChannelResultCode | 最近已验证的渠道结果；无依据为空；不得覆盖本地已确定终态 | 是 | BR-13/16/17；NFR-05 | 核对状态设计 |
| Transaction | lastCheckedAt | Instant | 最近一次渠道查询或回执核对时间 | 是 | UC-TR-08；NFR-05 | 核对时间设计 |
| Transaction | failureReason | String | 拒绝/失败原因码或脱敏说明；不混存资金流水 | 是 | BR-19/20；NFR-06 | 可解释反馈设计 |
| FundEntry | entryId | String | 资金事件唯一标识；创建后不可修改 | 否 | BR-19 | 流水主标识设计 |
| FundEntry | tradeNo | String | 恰好关联一笔交易；保留原交易追踪号 | 否 | BR-19；NFR-03 | 关联键设计 |
| FundEntry | accountId | String | 恰好属于一个账户；与 Transaction 的主账户角色不混淆 | 否 | 表 1-4 FundEntry | 关联键设计 |
| FundEntry | entryRole | EntryRole | 账户在该资金事件中的角色；用于业务去重；同交易同账户同角色唯一 | 否 | BR-11/12/15/19 | 资金效果去重设计 |
| FundEntry | direction | EntryDirection | CREDIT 或 DEBIT；仅实际入账、扣账或已生效纠正产生 | 否 | BR-19 | 方向枚举设计 |
| FundEntry | amount | Decimal | 正数、最多两位小数；方向由 direction 表达 | 否 | BR-01/19 | 字段名细化 |
| FundEntry | bookBalanceAfter | Decimal | 同次业务提交后的该账户 B，≥0；用于核对追溯 | 否 | BR-04/19；NFR-03 | 核对快照设计 |
| FundEntry | occurredAt | Instant | 实际资金变化提交时间；非请求提交时间 | 否 | BR-13/19；NFR-03 | 事件时间设计 |

说明：非法金额等请求仍须留下拒绝处理记录及幂等结果，但不能把无法解析的输入强塞进 `Transaction.amount:Decimal`。本模型的 Transaction 是已形成合法金额的业务交易；无法形成合法金额的拒绝输入由幂等/请求处理技术记录保存 requestId、requestHash、拒绝结果，不产生 FundEntry。技术记录由 D 与 A 映射，不新增为实验一核心实体。对于合法金额但冻结或超限的请求，可以生成 REJECTED 交易；其 acceptedAt 为空。

## 2 C 协作实体完整建议

本节是逻辑交接建议，尚未经 C 确认。它覆盖实验一已经列出的转账和基本风控职责，不表示 B 已完成 C 的序列图或交易服务设计。

| 类名 | 属性名 | 设计类型 | 约束与含义 | 可空 | 需求来源 | 设计新增 |
|---|---|---|---|---|---|---|
| TransferOrder | transferId | String | 转账单唯一标识 | 否 | 表 1-4 TransferOrder | 字段名细化 |
| TransferOrder | tradeNo | String | 唯一关联一笔转账交易；交易类型必须与 transferType 相符 | 否 | 表 1-4；BR-15/16/17 | 关联键设计 |
| TransferOrder | transferType | TransferType | INTERNAL 或 EXTERNAL | 否 | UC-TR-03/04 | 枚举设计 |
| TransferOrder | payerAccountId | String | 付款方本行账户；与 Transaction.accountId 一致 | 否 | 表 1-4；BR-14/15 | 双账户角色设计 |
| TransferOrder | payeeAccountId | String | 本行转账必填；跨行为空；指向行内收款 Account | 有条件 | 表 1-4；BR-15/16/17 | 双账户角色设计 |
| TransferOrder | externalBankCode | String | 跨行必填；本行转账为空；只标识模拟外部银行 | 有条件 | UC-TR-04；BR-16/17 | 模拟渠道字段设计 |
| TransferOrder | externalPayeeRef | String | 跨行必填；本行转账为空；脱敏展示，不代表真实银行接入 | 有条件 | UC-TR-04；NFR-09 | 外部收款标识设计 |
| TransferOrder | amount | Decimal | 正数、最多两位小数；与关联交易一致 | 否 | BR-01/14/15 | 字段名细化 |
| TransferOrder | currency | Currency | CNY；与关联交易一致 | 否 | BR-01 | 枚举设计 |
| TransferOrder | clearingStatus | ClearingStatus | INTERNAL 为 NOT_APPLICABLE；EXTERNAL 为 SUBMITTED、UNKNOWN、CONFIRMED_SUCCESS、CONFIRMED_FAILURE 之一；外部成功不能单独推出交易成功 | 否 | BR-15/16/17；NFR-06 | 区分外部和本地状态 |
| RiskRule | ruleId | String | 规则标识；历史事件引用该规则及版本 | 否 | 表 1-4 RiskRule | 字段名细化 |
| RiskRule | ruleType | RuleType | 当前建议 LIMIT_POLICY；不借此纳入候选名单/告警功能 | 否 | 表 1-4；BR-05 | 规则类别设计 |
| RiskRule | depositSingleLimit | Decimal | 正数、两位小数；课堂建议 50,000，可配置 | 否 | BR-05；NFR-12 | 配置字段设计 |
| RiskRule | outgoingSingleLimit | Decimal | 取款和两类转账共用，课堂建议 10,000，可配置 | 否 | BR-05；NFR-12 | 配置字段设计 |
| RiskRule | outgoingDailyLimit | Decimal | 每账户自然日累计；含成功和仍占用，失败恢复后释放原日额度；课堂建议 50,000，可配置 | 否 | BR-05；NFR-12 | 配置字段设计 |
| RiskRule | enabled | Boolean | 生效标志；基本资金约束不能因该标志关闭而失效 | 否 | 表 1-4；NFR-12 | 规则配置设计 |
| RiskRule | ruleVersion | Integer | 正整数；金额校验和 RiskEvent 引用同一生效版本 | 否 | NFR-03/12 | 规则版本设计 |
| RiskEvent | eventId | String | 拦截事件唯一标识；不得当成资金流水 | 否 | 表 1-4；BR-19 | 事件标识设计 |
| RiskEvent | tradeNo | String | 关联被拦截的合法金额交易；拒绝交易没有资金效果 | 否 | 表 1-4；BR-19 | 关联键设计 |
| RiskEvent | ruleId | String | 命中规则标识 | 否 | 表 1-4 RiskEvent | 关联键设计 |
| RiskEvent | ruleVersion | Integer | 命中时的规则版本，不能只引用当前配置 | 否 | NFR-03 | 审计版本设计 |
| RiskEvent | operatorIdentityId | String | 触发身份；自动检查用系统身份，不冒充人工操作 | 否 | BR-20；NFR-10 | 操作者设计 |
| RiskEvent | occurredAt | Instant | 命中时间 | 否 | BR-20；NFR-10 | 审计时间设计 |
| RiskEvent | reasonCode | String | 可解释原因；日志不记录密码、完整令牌 | 否 | BR-19/20；NFR-03/09 | 原因码设计 |
| RiskEvent | result | RiskDecision | PASS 或 REJECT；拦截事件当前使用 REJECT；不宣称已生成实际资金流水 | 否 | 表 1-4；BR-19 | 结果枚举设计 |

## 3 类型、关联与设计约束

| 类型/值对象 | 建议定义与约束 |
|---|---|
| Decimal | 十进制精确值；金额不超过两位小数；数据库精度和最大演示金额由 D 与 A 明确，不在设计类图写 SQL 类型 |
| Instant | 时间线上的时间点；自然日额度以统一业务日口径计算，部署时区由 A/C 确定；不因后续恢复重置原日额度 |
| Currency | CNY |
| RealNameStatus | UNVERIFIED、VERIFIED、REJECTED；为设计枚举建议 |
| AccountStatus | NORMAL、FROZEN、CLOSED |
| BusinessType | DEPOSIT、WITHDRAWAL、INTERNAL_TRANSFER、EXTERNAL_TRANSFER |
| TransactionStatus | RECEIVED（已接收）、REJECTED（已拒绝）、PROCESSING（处理中）、PENDING（待确认）、SUCCEEDED（成功）、RECOVERING（恢复中）、FAILED（失败） |
| ChannelResultCode | SUCCESS、FAILURE、UNKNOWN；只代表渠道证据，不能代替本地资金提交结果 |
| EntryDirection | CREDIT、DEBIT |
| EntryRole | DEPOSIT_IN、WITHDRAWAL_OUT、TRANSFER_OUT、TRANSFER_IN、CORRECTION_IN、CORRECTION_OUT；本次只详细设计 DEPOSIT_IN，纠正流程不在本次序列范围 |
| TransferType / ClearingStatus | 见 TransferOrder 属性表；本行无外部清算，跨行状态与本地交易状态分别记录 |
| RuleType / RiskDecision | LIMIT_POLICY；PASS、REJECT |
| DepositRequest | requestId、accountId、amount、currency；身份从认证上下文获得，不能信任客户端自报身份 |
| ChannelResult | tradeNo、accountId、amount、currency、code、evidenceId、来源及核验所需信息；由 ChannelGateway 验证来源、原号及业务内容一致 |
| BalanceSnapshot | accountId、currency、bookBalance、reservedAmount、availableBalance、status、version；同一一致性视图，A=B-R |
| TransactionSnapshot / DepositResult | 交易号、请求标识、主账户、金额、币种、已受理时间、当前状态和可解释结果；渠道适配器只接收只读快照验证依据；未成功不能写成已到账 |
| IdempotencyDecision | NEW、EXISTING_SAME、CONFLICT；同内容处理中仍返回原状态，不能把 NEW 重复返回给并发请求 |
| PostingOutcome | COMMITTED、ALREADY_APPLIED、RETRY_PENDING、CONFLICT；COMMITTED/ALREADY_APPLIED 方可报告相应成功终态 |

| 关联 | 多重性与约束 |
|---|---|
| Customer—Account | Customer 1，Account 0..*；每账户恰好一个客户；每客户 status≠CLOSED 的账户数≤3；不是历史账户总数≤3 |
| Account—Transaction | Account 1，Transaction 0..*；请求主账户；转账收款方另由 TransferOrder 指明 |
| Transaction—FundEntry | Transaction 1，FundEntry 0..*；拒绝、处理中、待确认可无流水；成功存款恰好一条 DEPOSIT_IN |
| Account—FundEntry | Account 1，FundEntry 0..*；每条实际资金流水有明确所属账户 |
| Transaction—TransferOrder | Transaction 1，TransferOrder 0..1；转账交易必须恰有一单，非转账无单 |
| Account—TransferOrder（payer） | Account 1，TransferOrder 0..*；payer 为本行付款账户 |
| Account—TransferOrder（payee） | Account 0..1，TransferOrder 0..*；本行转账必须有一个行内收款账户；跨行外部收款不硬连本行账户 |
| Transaction—RiskEvent | Transaction 1，RiskEvent 0..*；不产生 FundEntry 也可有拦截记录 |
| RiskRule—RiskEvent | RiskRule 1，RiskEvent 0..*；规则标识与命中版本同时保留 |

## 4 D 需要保留的一致性语义

1. **幂等作用域**是 `(identityId, businessType, requestId)`。同键同规范内容返回原交易状态，同键异内容冲突。摘要不包含会话令牌、展示时间等非业务内容；金额 `10`、`10.0` 与 `10.00` 应有相同合法语义，`10.001` 不得先四舍五入后接受。唯一键或等效原子 claim 用于抵御并发；只“先查再插”不构成并发幂等。claim 结果若要跨崩溃恢复，必须可持久关联原 tradeNo 和请求处理结果。
2. **受理提交**必须在受控并发边界内重检账户 NORMAL 及适用规则，并将 RECEIVED 推进到 PROCESSING、记录 acceptedAt；冻结与受理竞争按该提交次序决定是新请求还是旧已受理。渠道调用在受理成功后发生。不能先调用渠道再发现本地尚未受理。
3. **存款成功提交**由 PostingService 统一完成 Account.credit、追加 FundEntry、Transaction.markSucceeded；三者同一业务提交，任一失败全部回滚。Account.version、交易状态条件更新及流水去重约束是建议实现手段，不能将远程分段调用画成已具备原子性。A 可选择架构，但必须保留等价业务语义。
4. **流水去重**建议逻辑唯一 `(tradeNo, accountId, entryRole)`；这是辅助防线，不代替整体一致性。entryId 唯一也不够，因为重复回执可以生成两个不同 entryId。历史 FundEntry 不改不删；未来纠正只能追加新实际资金事件，其流程另行设计。
5. **旧已受理继续确认**：PostingService 不因当前 FROZEN 重新拒绝已记录 acceptedAt 的交易；它仍检查交易身份、渠道依据、终态与资金效果是否已提交。相同成功回执重复到达返回原成功；迟到 UNKNOWN 不得覆盖成功或失败；矛盾的明确终态依据转核对，不能自行反向改账。
6. **渠道依据与提交失败**：先验证并保存原交易的渠道依据，再尝试资金提交。资金提交失败时保留待确认/可重试状态，后续沿原 tradeNo 核对和重试本地提交，不重新发起一笔渠道入金。明确失败用 recordFailure 记录失败终态，不增加 B，也不产生 FundEntry。无法证明失败时保持待确认。
7. **查询及审计**：先认证、再检查操作和账户/已受理交易对象权限，之后才读余额、状态或幂等结果。`requireAccountAccess` 接收操作上下文：`operation=DEPOSIT` 只允许 ACT-C 操作本人账户；`operation=READ_BALANCE` 允许客户本人，或同时具备岗位权限和对象范围授权的后台人员；查询授权不能授予存款权限。余额从同一快照返回 B、R、A、状态。冻结不阻止已授权只读查询。冻结/解冻、规则变更、核对和恢复的审计记录由 A/D 确认逻辑映射，至少保留操作者、时间、对象、原因和结果，不作为资金流水。
8. **日额度与个人限额**：出账额度包含成功及仍占用，失败恢复释放原日额度；不得把存款额度计入出账额度。个人限额字段只提供逻辑输入，日额度聚合、自然日口径与并发实现由 C/D/A 对齐。本地建议不声称已经完成小组评审。

## 5 边界与控制类交接

边界类：DepositForm、DepositController、AccountQueryView、AccountQueryController、ChannelGateway，其中接口 Controller 按系统入口归为边界。控制类：AccessService、DepositService、IdempotencyService、AccountQueryService、PostingService。它们是本次设计新增职责，不要求一类一张表。IdempotencyService 的处理记录和渠道依据保存可映射为技术记录，但必须保留上文语义，不擅自新增核心业务实体。

渠道入金操作为 `requestDeposit(tradeNo, accountId, amount, currency)`，完整受理上下文由 DepositService 提供，ChannelGateway 不直接读取账户存储。金额规则操作为 `validateDepositAmount(amount, currency)`，同时校验 CNY 与金额。渠道核验操作 `verifyResult(transaction:TransactionSnapshot, result:ChannelResult)` 接收只读快照；快照带原号、主账户、金额和币种，边界层不直接操作实体。

完整操作契约及同名类登记见 `data/design-model.json`，图 2-6（a）展示七实体，图 2-6（b）展示协作类。C 评审引起字段修改时须同时更新 JSON、类图和本表，再交 D；当前文件可用于本地评审和数据设计准备，不能写成“已获得 C/D 确认”。
