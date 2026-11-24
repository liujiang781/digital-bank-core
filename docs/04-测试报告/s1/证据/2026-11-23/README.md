# S1当前主干后端回归

执行日期：2026年11月23日。代码基线：aa1a0f7，工作区仅文档修订。由Codex按S1任务执行，人工复核待本人确认。

使用JDK 21、Maven 3.9.11及现有离线缓存运行全后端test，BUILD SUCCESS。仅汇总本次开始后生成的Surefire XML，262项测试通过，失败、错误、跳过均为0。

| 模块 | 测试 | 通过 |
|---|---|---|
| account-service | 56 | 56 |
| common | 27 | 27 |
| gateway | 9 | 9 |
| risk-service | 37 | 37 |
| transaction-service | 81 | 81 |
| user-service | 52 | 52 |
| 合计 | 262 | 262 |

main-regression.json保留模块、测试类和实例名称及时间；source-sha256.json记录源码和测试配置指纹，不复制JVM环境或原始system-out。原始构建日志在本地核查目录保留，未纳入仓库。

命令：在backend目录指定JAVA_HOME为JDK21，使用Maven3.9.11、tools/maven-central-settings.xml及tools/maven-repository，执行离线test。本批使用项目测试profile中的H2与替身，不是重新执行真实MySQL资金链路。S5历史30接口/6页面也不算入262项。审批、跨行异常、性能、业务库恢复仍按剩余清单补证。
