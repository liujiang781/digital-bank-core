# 设计规格说明书 → Word（docx）生成说明（交付收尾·打印版）

> 负责人：成员 E（接口契约与交付整合）
> 目标：把 `docs/02-设计规格说明书/` 下的 Markdown 合并为一份可打印的 `设计规格说明书.docx`，
> 满足验收：目录、页码、题注、A4 打印版；表格不超出页边距；无跨页断图。

## 一、前置条件

1. 安装 pandoc（本机当前未安装；winget 未收录，请用其一）：
   - 官网下载：<https://pandoc.org/installing.html>（选 Windows 安装包）
   - 或 Chocolatey：`choco install pandoc`
   - 或 Scoop：`scoop install pandoc`
2. 若要做「题注自动编号 + 交叉引用」（如图 2-1 引用），额外装 `pandoc-crossref` 过滤器（可选）。
3. 准备学校/课程 Word 模板 `模板.docx`（含封面、页眉页脚、页码、标题样式、题注样式），
   用 `--reference-doc=模板.docx` 套用，避免手工调格式。

## 二、合并顺序（建议）

按报告阅读顺序拼接 Markdown：

1. `设计规格说明书.md`（组长：概述 + 总体设计 + 模块设计，对应 二-1~二-3）
2. `数据库设计.md`（库表映射、表 2-2、表 2-3）
3. `服务边界与接口清单.md`（成员 E）
4. `架构决策记录.md`（ADR-001~005）

> 顺序可由组长定；最终以「设计规格说明书.md 为正文母文件、其余为附录/章节」为准。

## 三、转换命令

```bash
cd docs/02-设计规格说明书

pandoc 设计规格说明书.md 数据库设计.md 服务边界与接口清单.md 架构决策记录.md ^
  -o 设计规格说明书.docx ^
  --toc --toc-depth=2 ^
  -V lang=zh-CN ^
  -V documentclass=article ^
  -V geometry:margin=2cm ^
  -V fontsize=10.5pt ^
  --reference-doc=模板.docx ^
  --standalone
```

要点说明：

- `--toc --toc-depth=2`：自动生成目录（二级以内），满足「目录」要求。
- `geometry:margin=2cm` + `fontsize=10.5pt`：A4 页边距与字号，降低表格超出页边距的概率。
- `--reference-doc=模板.docx`：套用含页码页脚、题注样式的模板，满足「页码、题注」。
- 图片（`.svg`）在 pandoc 中直接嵌入；Word 对 SVG 支持依赖版本，建议转 PNG 或用 `--embed-resources` 的 HTML 中间步骤。若 Word 打不开 SVG，用 `render_all.py` 同级目录已有 SVG，或加 `-f markdown+raw_attribute` 后用 `rsvg-convert`/`Inkscape` 转 PNG 再引用。
- 防「跨页断图」：在模板里把图片样式设为「嵌入型 + 与下段同页」，或正文用 Markdown 图片并避免图片正好落在页尾。

## 四、验收前自检

- [ ] Word 打开后目录可跳转、页码连续。
- [ ] 每张图有题注（如图 2-1 系统逻辑架构），且与正文「如图 2-x 所示」一致。
- [ ] 所有表格宽度在页边距内，无横向溢出。
- [ ] 无图片在页尾被截断（跨页断图）。
- [ ] 中文显示正常，无乱码。

## 五、当前阻塞（需其他成员交付）

- `设计规格说明书.md`（组长：正文）、`数据库设计.md`、`sql/schema.sql` 尚未编写，
  故**最终 `设计规格说明书.docx` 暂不能生成**。成员 E 已交付的
  `服务边界与接口清单.md`、`架构决策记录.md`（ADR-004/005）、`context-map.svg` 可先单独转 docx 预览，
  待组长正文就位后并入上面的合并命令即可。
