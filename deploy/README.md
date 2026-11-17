# 数据库与开发部署

完整配置、五服务启动、验收、备份恢复和回滚步骤见 [实验四部署方案](../docs/04-测试报告/部署方案.md)。当前 Compose 仅提供 MySQL 与可选 Redis。

## 结构与演示数据

schema.sql 当前定义 13 张表、15 条外键；这是静态 DDL 计数，实际数据库结果需执行验证后填写。演示账户为 ACC_S2_001、ACC_S2_002，初始账面余额分别为 50000.00、30000.00。

| 登录 identityId | 数据库 username | 实验密码 | 对应账户 |
| --- | --- | --- | --- |
| UID-000001 | zhangsan | demo123 | ACC_S2_001 |
| UID-000002 | lisi | demo123 | ACC_S2_002 |
| OPS-ADMIN | admin | admin123 | 无；凭据不等同于已实现运营授权 |

登录接口接收 identityId。用户服务使用 BANK_CREDENTIAL_STORE=mysql 与相应 BANK_DB_* 环境变量。演示密码哈希已与当前 BCrypt 编码器一致；demo 模式凭据另由 BANK_DEMO_* 配置，不能混用两种模式的身份。

## 初始化与启动依赖

在 deploy 目录执行 docker compose -f docker-compose.dev.yml up -d mysql，确认 MySQL 健康后再启动应用。Redis 是可选 profile，本轮 S1 会话使用进程内存。

schema.sql 包含 DROP TABLE，init-data.sql 包含 DELETE，二者都会清空现有实验数据；仅用于可重建的独立实验库。已有数据先备份，不能作为无损迁移重复执行。Docker 持久卷已有数据时，不会自动重新导入挂载的初始化 SQL。verify.sql 也应先审查再对实验库运行。

## 配置与验收

用户、账户、交易、风控各服务数据库账号需具备对应读写权限。业务内部令牌与 S1 内部认证令牌分别配置，见完整部署方案。真实数据库口令和令牌只保留在本机环境，不提交 Git。

验证脚本为 sql/verify.sql；执行后核对表与外键、演示账户的账面/预留/可用余额、幂等唯一键及流水一致性。现有验证输出是历史记录，不代表本轮重新执行。此次完成了 H2 回归、演示 SQL 密码兼容性测试和真实认证 HTTP 联调，尚未执行 MySQL 初始化及资金端到端验收。
