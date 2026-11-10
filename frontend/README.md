# frontend · 前端工程（实验三 · S5）

## 技术栈（ADR-004 已定稿）

| 项目 | 选型 | 状态 |
|---|---|---|
| 框架 | Vue 3 + `<script setup>` | ✅ 定稿（ADR-004，2026-10-27） |
| 构建 | Vite 6 | ✅ 定稿 |
| UI 库 | Element Plus | ✅ 定稿 |
| 路由 | Vue Router 4 | ✅ 定稿 |
| 状态 | Pinia | ✅ 定稿 |
| 请求 | Axios（统一拦截器处理错误码） | ✅ 定稿 |

后端为 Java（Spring Boot，多模块工程由 S1 搭建）。推荐用 IntelliJ IDEA 同时打开
`backend`（Maven 模块）与本 `frontend` 目录（IDEA 内置 Vue 插件支持）。

## 已实现页面（实验三 S5 分工）

| 页面 | 路由 | 覆盖用例 | 说明 |
|---|---|---|---|
| 登录 | `/login` | UC-AU-01 | 401 统一跳回登录，带 redirect 回跳 |
| 注册 | `/register` | UC-ID-01 | 注册操作带 requestId 幂等 |
| 我的账户 | `/accounts` | UC-AC-01/02 | 账户卡片 + 开户（二次确认，BR-02 三户上限提示） |
| 账户详情 | `/accounts/:id` | UC-AC-03/04、UC-TR-06 | 余额三值 B/R/A 同快照展示 + 资金流水分页 |
| 存款 | `/deposit` | UC-TR-01 | 金额两位小数校验、loading、重试复用 requestId |
| 取款 | `/withdraw` | UC-TR-02 | 同上 |
| 转账 | `/transfer` | UC-TR-03/04 | 本行/跨行切换、强制二次确认、跨行 PENDING 提示 |
| 交易记录 | `/transactions` | UC-TR-05 | 状态筛选、非终态交易可查询收敛（触发渠道核对） |

## 目录结构（实际）

```
frontend/
├── index.html
├── package.json
├── vite.config.js            # /api 代理到网关 8080；element-plus 拆包
├── .nvmrc                    # Node 22
└── src/
    ├── main.js
    ├── App.vue
    ├── router/index.js       # 路由守卫：未登录只能访问 login/register
    ├── stores/session.js     # Pinia 会话（token + 客户信息，localStorage 持久化）
    ├── api/
    │   ├── request.js        # axios 拦截器：token、统一响应体、401、网络重试
    │   ├── auth.js           # user-service 端点
    │   ├── account.js        # account-service 端点
    │   └── transaction.js    # transaction-service 端点
    ├── composables/
    │   └── useTradeSubmit.js # 提交/网络重试/非终态轮询收敛
    ├── components/
    │   └── TradeResultPanel.vue  # 交易结果面板（tradeNo/状态/traceId/查询按钮）
    ├── utils/
    │   ├── requestId.js      # UUID 生成（BR-11/12 幂等）
    │   ├── money.js          # 金额校验与 ¥1,234.56 格式化
    │   └── status.js         # 七态状态机、业务类型、账户状态展示映射
    ├── layouts/MainLayout.vue
    ├── views/                # 8 个页面（见上表）
    └── styles/index.css
```

## 开发约定（沿用并已落实）

- 接口地址统一走 `src/api/`，**组件里不写 axios 裸调用**。✅
- 金额展示统一 `¥1,234.56`，保留两位小数，不用浮点运算。✅（`utils/money.js`）
- 所有提交按钮有 loading 态 + 二次确认（转账强制）。✅
- 错误提示用后端返回的 `message`，不硬编码"操作失败"。✅（统一响应体透传）
- 表单校验前后端都做，前端校验不能替代后端校验。✅（金额格式前端先校验）

## S5 验收标准对照

| 验收标准 | 实现位置 |
|---|---|
| 页面展示的数据来自真实后端 | 全部经 `src/api/` 调 `/api/v1/`，无 mock |
| 提交后有加载状态、结果提示，成功后刷新余额和流水 | `useTradeSubmit` + `TradeResultPanel` |
| 同一次操作网络重试时复用原 requestId | `useTradeSubmit.retry` 复用提交闭包；axios 层网络错误自动重试一次（同 requestId） |
| 待确认交易展示当前状态并支持查询 | `TradeResultPanel` 查询按钮 + 5 秒轮询（最多 6 次）；交易记录页"查询状态" |
| 按演示脚本完整操作 | 见下文联调步骤 |

## 本地运行

```bash
npm install        # Node 22（.nvmrc）
npm run dev        # http://localhost:5173，/api 代理到 http://localhost:8080（gateway）
npm run build      # 产物 dist/
```

后端四服务 + 网关由 S1/S2/S3/S4 交付后启动，前端无需改动即可联调。

## 与后端联调的前置约定（已按接口契约 v1.0 对齐）

- 统一响应体：`{ code, message, data, traceId }`；金额字符串两位小数；时间 ISO-8601 东八区。
- 分页：请求 `page`/`pageSize`，响应 `data.items` + `data.total`。
- 登录令牌：`Authorization: Bearer <token>`，登录响应 `data.token`。
- 写操作一律带 `requestId`（UUID v4）。
- 网关端口默认 8080；如不同，改 `vite.config.js` 的 proxy target。

## 待办（联调阶段）

- [ ] 与真实后端联调：登录 → 开户 → 存款 → 流水 → 转账全链路
- [ ] 渠道超时/失败场景演示（PENDING 收敛）
- [ ] 页面截图（实验报告第 3 章"核心功能展示"）
- [ ] 演示脚本整理（S5 交付物）
