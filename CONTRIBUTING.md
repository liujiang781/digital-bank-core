# 协作规范（CONTRIBUTING）

本文件是小组的唯一协作标准。**开工前每人读一遍**，有异议在小组会上提出并修改本文，不要各自为政。

---

## 1. 分支模型

采用简化版 GitHub Flow：`main` 永远可运行，所有改动经特性分支 + PR 合入。

| 分支 | 用途 | 生命周期 |
|---|---|---|
| `main` | 主干，受保护，只接受 PR 合并 | 永久 |
| `feat/<模块>-<简述>` | 新功能，如 `feat/account-open` | 合并后删除 |
| `fix/<简述>` | 缺陷修复，如 `fix/transfer-idempotent` | 合并后删除 |
| `docs/<简述>` | 文档/UML 修改，如 `docs/usecase-v2` | 合并后删除 |
| `chore/<简述>` | 构建、配置、依赖调整 | 合并后删除 |

命名规则：**全小写、短横线分隔、不含中文、不超过 40 字符**。

```bash
git switch main && git pull
git switch -c feat/account-open
# ... 开发 ...
git push -u origin feat/account-open
```

**禁止**：直接在 `main` 上提交；`git push --force` 到 `main`；把 `.env`、密钥、数据库文件提交上来。

---

## 2. 提交信息规范（Conventional Commits）

格式：

```
<type>(<scope>): <subject>

<body 可选>
<footer 可选，如 Closes #12>
```

| type | 含义 |
|---|---|
| `feat` | 新功能 |
| `fix` | 修 bug |
| `docs` | 文档 / UML |
| `refactor` | 重构（不改外部行为） |
| `test` | 测试代码 |
| `style` | 格式调整（不影响逻辑） |
| `chore` | 构建、依赖、配置 |
| `perf` | 性能优化 |

`scope` 用模块名：`account` / `transaction` / `risk` / `user` / `gateway` / `web` / `docs`。

**示例**

```
feat(account): 实现账户开立接口与余额查询
fix(transaction): 修复重复提交导致重复扣款的问题

同一笔转账请求通过 requestId 做幂等校验，
在 transaction 表上加唯一索引 (request_id)。

Closes #23
```

**要求**

- 一次提交只做一件事，不要把"改 5 个模块 + 顺便格式化全项目"塞进同一个 commit。
- 标题不超过 50 字，用现在时祈使句（"实现"而不是"实现了"）。
- 提交前自检：`git diff --staged` 看清每一行。
- **Commit 记录是本实验的评分依据**（组内互评表要基于它），不要出现"11"、"update"、"test"、"最终版"这类无信息提交。

---

## 3. Pull Request 流程

1. 先建 Issue 描述要做什么（模板在 `.github/ISSUE_TEMPLATE/`）。
2. 建分支开发，本地自测通过。
3. 推送后开 PR，标题与 Issue 对齐，描述按模板填：改了什么 / 怎么验证 / 影响范围。
4. 指定 **至少 1 名组员 Review**，Reviewer 必须真的看代码，不能秒点 Approve。
5. CI 或本地构建通过 → **Squash and merge** → 删除分支。
6. PR 描述末尾写 `Closes #<issue号>`，合并时自动关 Issue。

**Review 关注点**

- 业务规则是否符合需求文档（限额、冻结、幂等）
- 异常路径是否有处理（余额不足、账户冻结、跨行超时）
- 是否有硬编码的密码/地址
- 是否有对应测试

---

## 4. 任务管理

- 所有工作先落 Issue，用 Labels 分类：`需求分析` `设计` `后端` `前端` `测试` `文档` `uml` `blocked`。
- 认领方式：在 Issue 里评论"我领"并把自己设为 Assignee，或直接在 Projects 看板上拖到 In Progress。
- 每个 Issue 估算工作量（故事点 1/2/3/5），Sprint 结束统计完成率。
- 每日/隔日同步一次看板，**不要等周末才更新**。

---

## 5. 目录与文件约定

- 文档：`docs/<实验目录>/`，文件名用中文主题，如 `用例列表.md`；不要用 `文档1.docx`。
- UML：源文件 `docs/uml/<类型>/<名称>.puml`，导出图 `docs/uml/out/<名称>.svg`（统一 SVG，PNG 二选一）。
- 代码：后端按服务分模块，前端按功能分目录，禁止在仓库根目录堆散装文件。
- 二进制大文件（PPT、录屏）不超过 20MB，超了发群共享，不要塞进 Git。

---

## 6. 提交前自检清单

- [ ] 分支名符合规范，没有直接改 `main`
- [ ] 提交信息符合 Conventional Commits，拆分了粒度
- [ ] 没有提交 `.env`、密钥、IDE 配置、`node_modules`、`target`、数据库文件
- [ ] 本地能跑通（后端编译通过 / 前端 `npm run build` 通过）
- [ ] 有对应 Issue，PR 里写了 `Closes #xx`
- [ ] 新增的接口/表结构已同步更新到设计文档

---

## 7. 冲突处理

```bash
git switch main && git pull
git switch feat/xxx
git rebase main           # 或 git merge main，组内统一一种
# 解决冲突后
git add <冲突文件> && git rebase --continue
git push --force-with-lease   # 仅限自己的特性分支
```

冲突超过 10 个文件或涉及核心表结构时，**当面一起改**，不要在群里互相覆盖。

---

## 8. 环境一致性

- Node 版本写进 `frontend/.nvmrc`，JDK 版本写进 `backend/pom.xml`。
- 数据库连接统一从环境变量读取，默认值写在 `application-dev.yml`，真实密码不进仓库。
- 依赖装不上先看代理，统一走组内约定的镜像源，别各装各的版本。
