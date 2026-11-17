# 实验二完整 ER 图

- `完整ER图.png`：2210 × 2809 像素，可插入报告。
- `完整ER图.svg`：矢量图，可放大查看或用于打印。
- `完整ER图.puml`：可编辑的 PlantUML 源文件。
- `generate_er.py`：从 `../sql/schema.sql` 提取表、字段及约束，重新生成源文件。

图以实验二 SQL 为基线，覆盖 10 张表、119 个字段、13 个唯一约束和 11 个物理外键；不混入实验三的增量表结构。连线采用 Crow's Foot（乌鸦脚）记法。U1/U2/U3 的编号仅在各自表内有效，相同编号的字段构成同一个唯一约束。

交易到转账单的关系为 1 : 0..1；跨行转账的 `payee_account_id` 为空。本图只绘制物理外键，身份标识和审计日志的逻辑引用在图例中说明。

在工作区根目录运行：

```powershell
python 'digital-bank-core/docs/02-设计规格说明书/s4数据库设计/er/generate_er.py'
java -DPLANTUML_LIMIT_SIZE=14000 -jar tools/plantuml/plantuml.jar -charset UTF-8 -tsvg 'digital-bank-core/docs/02-设计规格说明书/s4数据库设计/er/完整ER图.puml'
java -DPLANTUML_LIMIT_SIZE=14000 -jar tools/plantuml/plantuml.jar -charset UTF-8 -tpng 'digital-bank-core/docs/02-设计规格说明书/s4数据库设计/er/完整ER图.puml'
```

验证：已比对 SQL 的表数、字段数与外键数；已检查 SVG 中全部字段可见；SVG geometry/composition 检查通过。

visual_review: passed（已回读最终 PNG，检查字段、关系线、标签和图例）。
