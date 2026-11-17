# 实验四执行证据

日期：2026-11-17。测试人：S1，Codex 辅助执行，待本人复核。代码基线：4adeb17 加本次工作区修改。

## 可交付证据

- junit-results.json：最终 Maven 全量运行导出的 156 个测试实例，失败、错误、跳过为 0；排除 JVM 属性和原始 system-out。
- s1-http-results.json：17 个真实本机 HTTP 用例，全部通过；请求和响应中的密码、服务密钥和会话令牌已脱敏。
- source-sha256.json：本次关键被测文件及修复文件的摘要。
- auth-token-control-flow.svg 和 PNG：真实方法的控制流图；N=11、E=15、V(G)=6。

## 本机实际命令

在 backend 目录以 JAVA_HOME=E:\Java\jdk-21 使用以下 Maven 参数：

```text
E:\exp\软件工程实验\tools\apache-maven-3.9.11\bin\mvn.cmd
-o -s E:\exp\软件工程实验\tools\maven-central-settings.xml
-Dmaven.repo.local=E:\exp\软件工程实验\tools\maven-repository
-fae test -Dstyle.color=never
```

最后一次全量执行结束于 2026-11-17 16:21:43 +08:00，BUILD SUCCESS。逐模块结果来自 Surefire XML 的实际执行结果。默认 Java 为 17，故显式选择 JDK 21；本地缓存按 central-https 仓库标识配置。沙箱禁止 Java HttpClient 创建回环连接，最终在获准的沙箱外执行。

在仓库根目录使用 Python 运行 scripts/test-exp4-s1-http.py --java E:\Java\jdk-21\bin\java.exe；脚本读取本轮 Maven classpath，启动独立 JVM，监听随机 127.0.0.1 端口，执行 17 个用例后关闭自身启动的进程。不连接真实 MySQL。

在 frontend 目录执行 npm run build，最终日志为 built in 7.88s。修改后的 Vite 配置实测加载结果：默认 http://localhost:8085；设置 GATEWAY_URL 后为 http://127.0.0.1:18085。构建存在第三方 Element Plus chunk 超过 500 kB 的提示，未导致失败，本轮没有做性能验收。

## 未执行范围

Docker CLI 可用，但 Docker Desktop Linux Engine 管道不存在。未导入真实 MySQL，未验证五服务资金闭环、真实数据库备份恢复、应用版本回滚、浏览器资金操作。schema.sql 静态计数 13 表、15 外键，不冒充数据库查询输出。

原始 *.log 文件受仓库忽略规则排除，留在本地用于排查。重新执行会更新证据，报告中的日期、统计与结论也需随之核对。
