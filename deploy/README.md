# deploy · 部署与初始化

## 目录规划

```
deploy/
├── docker-compose.dev.yml     # 本地开发：MySQL + Redis（可选）
├── docker-compose.prod.yml    # 演示部署：全部服务
├── sql/
│   ├── schema.sql             # 建表脚本（必须与设计文档表结构一致）
│   └── init-data.sql          # 演示数据：2-3 个客户、各若干账户
└── nginx/
    └── default.conf           # 前端静态资源 + /api 反向代理
```

## 本地开发依赖（Sprint 3 前补齐）

```bash
docker compose -f deploy/docker-compose.dev.yml up -d
# MySQL: localhost:3306  root/****  db: bank_core
# Redis: localhost:6379
```

## 部署方案要写清的内容（实验四要求）

1. **拓扑**：与实验二的部署图一致，明确每台机器/容器跑什么
2. **环境要求**：Docker 版本、CPU/内存、端口开放清单
3. **配置项**：数据库地址、连接池大小、JVM 参数、前端 API 基址
4. **部署步骤**：从拉代码到验证可访问的完整命令序列
5. **回滚**：镜像版本回退方式、数据库变更的回退脚本
6. **备份**：数据库备份频率与恢复演练方式

## 约定

- 数据库密码、密钥通过 `.env` 注入，`.env` **不进仓库**，提交 `.env.example`。
- 每个镜像打 tag（`v0.1-20261101`），不要只用 `latest`，否则无法回滚。
- 演示数据脚本必须幂等（可重复执行不报错），方便答辩现场重置。
