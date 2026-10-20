# backend · 后端服务

## 目录规划（Maven 多模块，待实验二确认）

```
backend/
├── pom.xml                    # 父 POM，统一依赖版本
├── common/                    # 公共模块：统一返回体、异常、工具、常量
│   └── src/main/java/.../common/
│       ├── ApiResponse.java
│       ├── BizException.java
│       └── ErrorCode.java
├── gateway/                   # 网关：路由、鉴权、限流
├── user-service/              # 客户与认证
├── account-service/           # 账户与余额
├── transaction-service/       # 交易、流水、幂等
└── risk-service/              # 风控规则
```

**如果最终选择单体架构**（见 ADR-001），则改为单模块 + 按业务分包：

```
backend/src/main/java/.../
├── user/  account/  transaction/  risk/
└── common/
```

包内统一分层：`controller` / `service` / `repository` / `domain` / `dto`。

## 开发约定

| 项目 | 约定 |
|---|---|
| JDK | 17（写进 `pom.xml` 的 `maven.compiler.release`） |
| 构建 | Maven，统一用 `./mvnw`，避免各人 Maven 版本不同 |
| 接口风格 | REST，路径前缀 `/api/v1/`，如 `POST /api/v1/transactions/deposit` |
| 返回体 | 统一 `{code, message, data, traceId}` |
| 错误码 | 1000 参数非法 / 1001 余额不足 / 1002 账户冻结 / 1003 触发风控 / 2000 系统异常 |
| 金额类型 | 一律 `BigDecimal`，禁止 `double`/`float` |
| 时间 | `LocalDateTime` + 数据库 `DATETIME(3)`，统一东八区 |
| 日志 | 关键业务打 `traceId`，禁止打印密码、完整卡号 |
| 敏感数据 | 卡号/手机号脱敏后返回前端 |

## 关键实现要点（验收必看）

1. **转账事务**：`@Transactional` 覆盖"扣款 + 入账 + 写流水"三步，异常必须整体回滚。
2. **幂等**：`transaction` 表 `request_id` 唯一索引；捕获唯一键冲突时查询首次结果返回。
3. **余额并发**：使用 `update account set balance = balance - ? where id = ? and balance >= ?`
   判断影响行数，避免"查-改-写"竞态；或 `select ... for update`。
4. **风控前置**：交易受理后、落库前执行规则校验，拦截时写 `risk_event` 但不写资金流水。

## 常用命令

```bash
./mvnw clean package            # 构建
./mvnw test                     # 跑测试
./mvnw -pl transaction-service spring-boot:run   # 单模块启动
```

## 待补充

- [ ] 父 POM 与模块骨架（Sprint 3 第 1 周）
- [ ] 数据库 schema 初始化脚本（放 `../deploy/sql/`）
- [ ] 接口文档（建议 springdoc-openapi，启动后 `/swagger-ui.html`）
