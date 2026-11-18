# backend · 数字银行后端

实验三的 Java 21 / Spring Boot 轻量微服务骨架。父 POM 固定 Spring Boot 3.5.7、Spring Cloud 2025.0.0，Maven Wrapper 固定 Maven 3.9.11。
版本兼容关系见 [Spring Cloud 官方说明](https://spring.io/projects/spring-cloud/)。

## 1. 结构与职责

```text
backend/
├── pom.xml                 # 聚合、依赖和编译版本管理
├── mvnw / mvnw.cmd          # Maven Wrapper
├── common/                 # 响应体、错误码、异常、traceId、Servlet 公共配置
├── gateway/                # WebFlux 网关，统一入口
├── user-service/           # 用户与认证，S1
├── account-service/        # 账户与资金记账，S2
├── transaction-service/    # 交易受理与编排，S3
├── risk-service/           # 风控，S3
└── scripts/smoke.py         # 五进程路由冒烟验证
```

每个业务服务包含 `controller`、`service`、`repository`、`domain`、`dto` 包。
`common` 是普通依赖 JAR，不单独启动；其 Servlet 配置由四个业务服务显式导入。
网关使用 WebFlux，不导入 Servlet 配置，也不引入 `spring-boot-starter-web`。

当前已实现工程基础、健康检查、路由、公共异常处理、traceId、登录/退出、会话核验和网关鉴权。
S2 已补齐账户生命周期、对象权限适配、MySQL 连接及内部资金原子记账，运行方法见 [账户服务说明](account-service/README.md)。S2 的 47 项测试在 H2 和真实 MySQL 上均已通过。
S3 的交易受理/渠道/风控、S5 的前端联调按各模块进度推进；认证配置、接口示例及 MySQL 交接详见 [S1 登录认证与网关鉴权](../docs/03-原型实现/S1-登录认证与网关鉴权.md)。
健康检查返回 `UP` 只说明骨架存活，不表示银行业务已经完成。当前仅用于本地开发。

## 2. 构建

需要 JDK 21（先检查 `java -version` 和 `JAVA_HOME`）。在本目录执行：

```powershell
.\mvnw.cmd clean install
```

macOS/Linux 使用 `sh ./mvnw clean install`。首次执行需要联网下载 Maven 和依赖。
先执行 `install`，保证随后单独启动服务时可以解析本项目的父 POM 和 `common`。
IDE 中打开本目录 `pom.xml`，选择 JDK 21、Maven Wrapper 并重新加载 Maven。

若本机 Maven 的 `settings.xml` 配置了不可用镜像，可使用项目提供的官方仓库设置：

```powershell
.\mvnw.cmd -s .mvn/central-settings.xml clean install
```

这个参数只影响当前命令，不修改全局配置；网络访问仍取决于本机环境。

## 3. 启动

健康检查可直接启动；要联调登录，需要先按 S1 说明为五个服务配置同一个 `BANK_INTERNAL_TOKEN`，并为用户服务配置演示用户或 MySQL 凭据来源。

完成构建后，在五个终端中分别执行以下命令（工作目录均为 `backend`）：

```powershell
.\mvnw.cmd -pl user-service spring-boot:run
.\mvnw.cmd -pl account-service spring-boot:run
.\mvnw.cmd -pl transaction-service spring-boot:run
.\mvnw.cmd -pl risk-service spring-boot:run
.\mvnw.cmd -pl gateway spring-boot:run
```

也可以在 IDE 中分别运行五个 `*Application` 主类。

| 模块 | 默认端口 | 存活检查 |
|---|---|---|
| gateway | 8080 | `/actuator/health` |
| user-service | 8081 | `/api/v1/user/health` |
| account-service | 8082 | `/api/v1/account/health` |
| transaction-service | 8083 | `/api/v1/transaction/health` |
| risk-service | 8084 | `/api/v1/risk/health` |

所有进程均可用环境变量 `SERVER_PORT` 覆盖端口，但要在各自终端分别设置。
网关的下游地址可用 `USER_SERVICE_URL`、`ACCOUNT_SERVICE_URL`、`TRANSACTION_SERVICE_URL`、`RISK_SERVICE_URL` 覆盖。
默认指向 localhost 的 8081—8084，无需注册中心。账户业务及其数据库健康检查需要配置 MySQL，并先执行基础与 S2 增量脚本，详见账户服务说明。

## 4. 验证

服务启动后，通过网关访问：

```powershell
curl.exe -i -H "X-Trace-Id: demo-001" http://localhost:8080/api/v1/user/health
curl.exe http://localhost:8080/api/v1/account/health
curl.exe http://localhost:8080/api/v1/transaction/health
curl.exe http://localhost:8080/api/v1/risk/health
```

第一个请求应返回 HTTP 200，响应头包含 `X-Trace-Id: demo-001`，响应体为：

```json
{
  "code": "SUCCEEDED",
  "message": "请求成功",
  "data": {"service": "user-service", "status": "UP"},
  "traceId": "demo-001"
}
```

网关接收或生成 traceId，向业务服务传递；业务服务将其写入响应和 MDC 日志。
traceId 只允许 1—64 位字母、数字、下划线和短横线，其余输入替换为 UUID。
会话核验客户端已经转发 `X-Trace-Id`；后续业务调用还需要显式转发该头和 `Authorization`。

可运行自动冒烟验证（额外需要 Python 3.9+）：

```powershell
python scripts/smoke.py
```

脚本运行前须按账户服务说明设置 ACCOUNT_DB_URL、ACCOUNT_DB_USER、ACCOUNT_DB_PASSWORD，使 S2 数据库健康检查通过。
脚本使用 18080—18084 端口启动已打包的五个 JAR，验证存活、四条网关路由和traceId，
检查内部路径隔离，并验证登录、退出、直接端口鉴权、伪造身份头和认证依赖故障。
脚本使用随机临时密钥与密码，结束时停止本次启动的进程；日志位于 `logs/smoke/`。
`user-service` 的 22 项测试覆盖公共基础设施、认证接口、会话生命周期、对象归属与 JDBC 查询。

## 5. 业务接口预留

| 网关路径 | 服务 |
|---|---|
| `/api/v1/auth/**`、`/api/v1/customers/**` | user-service |
| `/api/v1/accounts/**` | account-service |
| `/api/v1/deposits/**`、`/api/v1/withdrawals/**`、`/api/v1/transfers/**`、`/api/v1/transactions/**` | transaction-service |
| `/api/v1/risk/**` | risk-service |

登录、退出、当前会话、注册与模拟实名、本人资料、运营客户列表和管理员授权接口已经实现。新增用户管理接口需要 MySQL 凭据模式及 S1 增量表，详见 [S1 用户管理接口交接](../docs/04-测试报告/s1/S1-用户管理接口交接.md)。
账户路径已有 S2 业务 Controller（开户/余额/冻结/流水等），其余模块按各负责人进度接入。
`/internal/v1/**` 不经网关开放。内部会话核验要求服务密钥，业务记账内部接口的认证仍由对应模块实现。
Servlet 用户鉴权过滤器跳过 `/internal/v1/` 下的接口：S2/S3 校验 `X-Internal-Token`，
S1 会话核验接口校验 `X-Service-Token`。跳过用户鉴权不代表跳过服务身份校验。
用户会话核验成功后，`getUserPrincipal().getName()` 和 `getRemoteUser()` 返回 `identityId`，
同时保留 `AccessService` 请求属性。会话包含 `roles`、`accountScope` 和 `authorizationVersion`；在线授权记录优先于静态清单，变更后旧会话失效。ADMIN 仅由服务端预置，只管理权限，不访问业务数据；S2 同时核验业务角色和具体账户范围。配置与验证见 [S1 用户管理接口交接](../docs/04-测试报告/s1/S1-用户管理接口交接.md)。
前端开发建议由 Vite 将 `/api` 代理到 `http://localhost:8080`。

实现以实验二 `s5接口契约与交付整合/02-设计文档/服务边界与接口清单.md` 为准：

- 错误码使用 `INVALID_ARGUMENT`、`REQUEST_CONFLICT` 等字符串码；旧数字码草案已废弃。
- 金额接口使用两位小数字符串，Java 使用 `BigDecimal`，数据库使用 `DECIMAL(18,2)`。
- 交易幂等键为 `(identityId, businessType, requestId)`，不能仅依赖 requestId 单字段。
- 交易服务负责受理和编排；账户服务在本地事务中执行余额、资金流水及相关状态更新。
- 不把跨 HTTP 调用包在一个 `@Transactional` 中当作分布式事务。
- 不在配置或日志中保存真实密码及完整敏感数据。

## 6. 后续交接

- S1：已完成登录、会话、网关鉴权及对象归属公共支持；后续与前端、真实 MySQL 联调。
- S2：账户生命周期、余额查询、内部原子记账已实现；按交接执行版对接 S1 认证与 S3 调用。
- S3：存款受理、渠道模拟、幂等、交易状态及风控。
- S4：数据库初始化、访问权限及运行环境。
- S5：前端和真实接口联调。

先打通存款闭环，再扩展取款与本行转账；每个负责人同步提交实现说明和验证记录。

