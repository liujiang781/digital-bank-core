# 数字银行核心业务模拟系统 · 实验二：候选类、CRC 卡片与领域模型

## 一、候选类整理

### 1.1 提取方法

本组用三条来源找候选类。第一条来源是用例里的名词。第二条来源是领域概念。第三条来源是必须建模的行为。

找到候选类以后要剔除三类。第一类是界面类，比如登录页面、存款表单。界面不是领域概念。第二类是控制类，比如交易控制器。控制类放到设计模型里。第三类是纯技术细节，比如数据库连接、HTTP 客户端。

### 1.2 候选类清单与取舍

| 候选类 | 来源 | 决定 | 理由 |
|--------|------|------|------|
| User 用户 | 注册登录用例 | 保留，实体，聚合根 | 身份是核心概念，有唯一标识 |
| RealNameInfo 实名信息 | 实名认证用例 | 保留，值对象 | 没有独立标识，随用户创建和销毁 |
| Account 账户 | 开户、存款用例 | 保留，实体，聚合根 | 余额和状态的载体 |
| BankCard 银行卡 | 绑定银行卡用例 | 保留，实体 | 有卡号标识，归属账户聚合 |
| Transaction 交易流水 | 存款、取款、转账用例 | 保留，实体，聚合根 | 审计核心，有流水号标识 |
| RiskResult 风控结果 | 存款用例的包含关系 | 保留，值对象 | 一次校验产生一份结果，不需单独标识 |
| IdempotencyKey 幂等号 | 存款用例的异常流 | 保留，值对象 | 重复请求去重的依据 |
| RiskRule 风控规则 | 配置风控规则用例 | 保留，实体，聚合根 | 规则可配置，有规则编号 |
| Alert 告警单 | 异常交易告警用例 | 保留，实体 | 有告警编号和处理状态 |
| ReconciliationTask 对账任务 | 日终对账用例 | 保留，实体，聚合根 | 一次对账是一个任务，有任务编号 |
| Discrepancy 差错单 | 差错处理用例 | 保留，实体 | 有差错编号和处理状态 |
| PaymentChannelAdapter 通道适配器 | 跨行转账用例 | 保留，技术协作类 | 封装外部支付通道，隔离变化 |
| NotificationService 通知服务 | 各用例的通知步骤 | 归入应用服务 | 没有领域状态，不进领域模型 |
| LoginPage 登录页面 | 注册登录用例 | 剔除 | 界面类，不是领域概念 |
| DepositForm 存款表单 | 存款用例 | 剔除 | 界面类，不是领域概念 |
| TransactionController 交易控制器 | 存款用例 | 剔除 | 控制类，属于设计模型 |

## 二、CRC 卡片

### 2.1 方法说明

CRC 的意思是类、职责、协作者。这个方法来自 Beck 和 Cunningham 的论文。用法是一张卡片写一个类。卡片写两栏。第一栏写这个类负责什么。第二栏写它要和谁合作。写卡片的过程就是分派职责的过程。职责分派完，类的边界就清楚了。

### 2.2 CRC 卡片表

| 类 | 职责 | 协作者 |
|----|------|--------|
| User 用户 | 维护储户身份、登录凭证和实名状态 | Account、RealNameAuthService、NotificationService |
| Account 账户 | 维护余额、状态和绑定卡；保证余额不为负 | User、BankCard、Transaction、AccountRepository |
| BankCard 银行卡 | 维护绑定卡信息和卡号归属校验 | Account、PaymentChannelAdapter |
| Transaction 交易流水 | 记录交易全生命周期；用幂等号去重 | Account、RiskEngine、PaymentChannelAdapter、Alert |
| IdempotencyKey 幂等号 | 保证同一请求重复提交只生效一次 | Transaction |
| RiskRule 风控规则 | 定义限额和频次规则；匹配交易 | RiskEngine、Alert |
| Alert 告警单 | 记录异常告警并跟踪处理状态 | Transaction、RiskRule、NotificationService |
| ReconciliationTask 对账任务 | 组织日终对账，生成差异并跟踪处理 | Transaction、PaymentChannelAdapter、Discrepancy |
| Discrepancy 差错单 | 记录差错类型、金额和处理状态 | ReconciliationTask、Transaction |
| PaymentChannelAdapter 通道适配器 | 封装扣款、贷记、状态查询和对账文件获取 | Transaction、ReconciliationTask |

## 三、领域模型

### 3.1 聚合划分

| 聚合 | 聚合根 | 包含成员 | 边界理由 |
|------|--------|---------|---------|
| 用户聚合 | User | RealNameInfo | 身份和实名信息一起变更，不拆开 |
| 账户聚合 | Account | BankCard | 余额和绑定卡由一个边界管理 |
| 交易聚合 | Transaction | RiskResult、IdempotencyKey | 一次交易自包含，结果和幂等号不单独存在 |
| 风控聚合 | RiskRule | Alert | 规则和它产生的告警放一起 |
| 对账聚合 | ReconciliationTask | Discrepancy | 一次对账任务和它的差错单放一起 |

外部访问一个聚合时，只能通过聚合根。比如修改余额必须调用 Account，不能直接改 BankCard 再影响余额。

### 3.2 领域模型图

![图9 领域模型](images/fig9-domain-model.png)

### 3.3 关系与多重性

| 关系 | 多重性 | 说明 |
|------|--------|------|
| User — Account | 1 对 1 | 一个用户开立一个电子账户 |
| Account — BankCard | 1 对 0..* | 一个账户可以绑定多张卡 |
| Account — Transaction | 1 对 0..* | 一个账户有多条流水 |
| Transaction — Transaction | 0..1 对 0..1 | 自关联，失败流水通过关联流水号指向冲正流水 |
| Transaction — RiskResult | 1 对 0..1 | 一笔交易带一份风控结果，值对象 |
| RiskRule — Alert | 1 对 0..* | 一条规则触发多条告警 |
| Transaction — Alert | 0..* 对 1 | 一条告警由一笔交易触发 |
| ReconciliationTask — Discrepancy | 1 对 0..* | 一次对账产生多条差错 |
| Discrepancy — Transaction | 0..* 对 1 | 差错定位到具体流水 |
| Transaction — PaymentChannelAdapter | 依赖 | 交易通过适配器调用外部通道 |

### 3.4 不变式

不变式是领域模型必须始终成立的条件。本组列出五条。

1. 账户余额永远不为负。
2. 交易流水只增不改，任何状态变更都写新记录或者更新状态字段，不删记录。
3. 幂等号在系统内唯一，同一幂等号只允许一笔成功交易。
4. 冻结状态的账户不能发起交易。
5. 未实名的用户不能开立账户。

### 3.5 状态机

交易流水的状态流转是这样的。交易提交后进入处理中。风控拒绝后进入拒绝。渠道失败后进入失败。入账成功后进入成功。回调失败并冲正后，原流水保持失败，同时生成一笔冲正流水。

| 状态机 | 状态 | 流转 |
|--------|------|------|
| Transaction | 处理中、成功、失败、拒绝 | 处理中 → 成功；处理中 → 失败；处理中 → 拒绝；失败 → 触发冲正流水 |
| Alert | 待处理、处理中、已关闭、已升级 | 待处理 → 处理中；处理中 → 已关闭；处理中 → 已升级并冻结账户 |
| ReconciliationTask | 未开始、对账中、平账、有差异 | 未开始 → 对账中；对账中 → 平账；对账中 → 有差异并生成差错单 |

### 3.6 需求追溯

| 用例 | 主要协作类 |
|------|-----------|
| UC-DEP-01 存款 | Transaction、Account、RiskRule、PaymentChannelAdapter |
| UC-AUTH-02 实名认证 | User、RealNameInfo |
| UC-RISK-02 异常交易告警 | Alert、RiskRule、Transaction |
| UC-OPS-01 日终对账 | ReconciliationTask、Discrepancy、Transaction |

## 参考文献

Beck, Kent, and Ward Cunningham. "A Laboratory for Teaching Object-Oriented Thinking." *OOPSLA '89 Addendum*, 1989, pp. 1-6.

Cockburn, Alistair. *Writing Effective Use Cases*. Addison-Wesley, 2001.

Object Management Group. *OMG Unified Modeling Language (OMG UML), Version 2.5.1*. OMG, 2017.
