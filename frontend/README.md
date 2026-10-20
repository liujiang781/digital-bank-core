# frontend · 前端工程

## 技术栈

| 项目 | 选型 | 状态 |
|---|---|---|
| 框架 | Vue 3 + `<script setup>` | 待定（备选 React） |
| 构建 | Vite | 待定 |
| UI 库 | Element Plus | 待定 |
| 路由 | Vue Router | 待定 |
| 状态 | Pinia | 待定 |
| 请求 | Axios（统一拦截器处理错误码） | 待定 |

## 目录规划

```
frontend/
├── index.html
├── package.json
├── vite.config.js            # 配 /api 代理到后端网关
└── src/
    ├── main.js
    ├── router/
    ├── stores/               # 用户会话、账户状态
    ├── api/                  # 按模块拆：account.js / transaction.js
    ├── views/
    │   ├── Login.vue
    │   ├── Dashboard.vue         # 账户卡片、总资产
    │   ├── AccountDetail.vue     # 余额、流水
    │   └── Transfer.vue          # 存款/取款/转账表单
    ├── components/
    └── styles/
```

## 开发约定

- 接口地址统一走 `src/api/`，**组件里不写 axios 裸调用**。
- 金额展示统一 `¥1,234.56`，保留两位小数，不用浮点运算。
- 所有提交按钮必须有 loading 态 + 二次确认（转账尤其）。
- 错误提示用后端返回的 `message`，不要在页面里硬编码"操作失败"。
- 表单校验前后端都要做，前端校验不能替代后端校验。

## 常用命令

```bash
npm install
npm run dev        # 开发，默认 5173，/api 代理到 localhost:8080
npm run build      # 构建
```

## 待补充

- [ ] 工程初始化（Sprint 3 第 1 周）
- [ ] `.nvmrc` 锁定 Node 版本
- [ ] 登录态与路由守卫
- [ ] 统一请求拦截器（token、错误码、traceId 透传）
