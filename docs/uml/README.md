# UML 模型目录

## 为什么用 PlantUML

- 图是**文本**，能 diff、能 review、能查到谁改了哪根线
- 与代码同仓，改需求时顺手改图，不会出现"图还是三个月前的版本"
- 导出 SVG 放进报告，矢量图打印不失真

## 目录约定

```
uml/
├── storymap/      用户故事地图（可用表格或图）
├── usecase/       用例图        overview.puml / account.puml / risk.puml
├── activity/      活动图        deposit.puml / withdraw.puml / transfer.puml
├── class/         类图          domain-model.puml（分析） / design-class.puml（设计）
├── sequence/      序列图        deposit.puml / transfer.puml / risk-check.puml
├── package/       包图          logical-arch.puml
├── deployment/    部署图        physical-arch.puml
└── state/         状态图        account-status.puml / transfer-order.puml
```

文件名：**全小写 + 短横线 + 语义化英文**，与报告中的图号对应关系写在 `docs/README.md` 的索引里。

## 命名与编号

| 报告图号 | 文件 |
|---|---|
| 图 1-1 系统总用例图 | `usecase/overview.puml` |
| 图 1-2 存款活动图 | `activity/deposit.puml` |
| 图 2-1 逻辑架构包图 | `package/logical-arch.puml` |
| 图 2-2 物理架构部署图 | `deployment/physical-arch.puml` |

## 渲染方式

**方式一：VS Code 插件（推荐）**

1. 装 `PlantUML` 扩展（jebbs.plantuml）
2. 需要 Java 运行时；`Alt+D` 预览、`Ctrl+Shift+P → PlantUML: Export Current Diagram` 导出

**方式二：命令行**

```bash
# 需要 plantuml.jar + Java 8+
java -jar plantuml.jar -tsvg docs/uml/**/*.puml
```

**方式三：在线**——http://www.plantuml.com/plantuml ，只用于快速看效果，
注意**内部业务设计不要贴到公网**（课程项目无所谓，但养成习惯）。

## 规范

1. **中文标签必须加 `skinparam defaultFontName`**，否则导出中文乱码（见模板文件）。
2. 图内不放实现细节：分析阶段的类图不写 `varchar(50)`、不写 `@Transactional`。
3. 一张图只讲一件事；用例图超过 15 个用例就拆成按模块的多张。
4. 导出图与源文件同名，放 `out/`，`.gitignore` 默认保留（方便直接取用报告）。

## 检查清单

- [ ] 每个 `.puml` 都能单独渲染通过，无语法错误
- [ ] 中文不乱码、不出现方框
- [ ] 图中元素名与需求/设计文档用词一致（别一处写"用户"一处写"客户"）
- [ ] 报告的每张图都能追到源文件
