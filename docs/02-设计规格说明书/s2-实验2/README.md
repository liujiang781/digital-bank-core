# 实验二成员 B 账户域成果

本目录包含成员 B 的账户域设计成果，业务规则以本地《实验一需求规格说明书》整合版 v1.2 为准。全部内容在本地独立编写，未修改原始需求书、分工清单或 GitHub 仓库。

双击[成果预览](成果预览.html)可浏览正文、全部图片、需求追踪、属性交接表和验证记录。查看源文件或继续编辑时使用下面的文件入口。

| 交付项 | 文件 |
|---|---|
| 账户域正文和表 2-1 | [模块设计-账户域](docs/02-设计规格说明书/模块设计-账户域.md) |
| 需求、规则与场景追踪 | [需求追踪与基线说明](docs/02-设计规格说明书/需求追踪与基线说明.md) |
| 给 D 的属性表和操作契约 | [交接 D](docs/02-设计规格说明书/交接D-设计类属性清单.md) |
| 图 2-3 存款鲁棒图 | [PlantUML](docs/uml/robustness/deposit.puml)／[SVG](docs/uml/out/deposit-robustness.svg) |
| 图 2-4 存款序列图 | [PlantUML](docs/uml/sequence/deposit.puml)／[受理面板](docs/uml/out/deposit-request.svg)／[确认面板](docs/uml/out/deposit-confirmation.svg) |
| 图 2-5 账户查询序列图 | [PlantUML](docs/uml/sequence/account-query.puml)／[SVG](docs/uml/out/account-query.svg) |
| 图 2-6 设计类图 | [PlantUML](docs/uml/class/design-class.puml)／[核心实体](docs/uml/out/design-class-core.svg)／[边界与控制](docs/uml/out/design-class-collaboration.svg) |
| 本地核对结果 | [验证记录](docs/02-设计规格说明书/本地验证记录.md) |

四个 UML 源文件对应四个分配图号；图 2-4 与图 2-6 各拆成两个面板以便阅读，因此共有六份 SVG 与六份 PNG。SVG 适合放大与报告排版，PNG 便于查看。

当前类名登记共 17 类：七个核心实体、五个控制类、五个边界类。表 2-1 是可交接的本地全局初版；C 的交易／风控细化、A 的架构实现及 D/E 的映射和接口仍需小组联合对齐，不表示其他成员已验收。B 的交付内容已经形成，未以“待填写”占位。

`data/design-model.json` 是图、表与属性的校对登记；`.tools/render.ps1` 可使用现有本地 Java 和 PlantUML 重新导出图片，`.tools/verify.py` 检查文件、类名、属性及链接，`.tools/build_preview.py` 重新生成离线预览。工具不会联网或提交仓库。

本包不包含其他成员的总体架构、交易／风控序列图、数据库 DDL 或全组 Word 定稿。它可以按现有目录结构交给组长汇编。
