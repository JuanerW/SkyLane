# SkyLane

旧金山同城配送应用：3 个站点，地面机器人和无人机两种运力。用户填写寄件信息，比较 6 个方案后直接下单，在地图上实时追踪配送，完成后评价。

## 设计文档

- [`docs/design.md`](docs/design.md)：完整技术设计（关键决定、前端、后端、数据库、第三方 API），**实现以此为准**
- [`docs/design/`](docs/design/)：可点击的结构图，下载后用浏览器打开 `index.html`
  - `frontend.html`：页面、Hook、API 层
  - `backend.html`：Controller、Service、Repository、方法签名
  - `database.html`：ER 图、字段、约束

## 仓库结构

```
SkyLane/
├─ FE/          前端（Next.js），说明见 FE/README.md
├─ BE/          后端（Spring Boot）和数据库，说明见 BE/README.md
│  ├─ docker-compose.yml           本地 PostgreSQL + PostGIS
│  └─ database/migration/V1__init.sql   建表脚本
└─ docs/        设计文档
```

## 技术栈

| 部分 | 技术 |
|---|---|
| 前端 | Next.js 14 + TypeScript、TanStack Query、MapLibre GL JS、Tailwind CSS |
| 后端 | Java 21、Spring Boot 3.3、Spring Security（JWT）、Spring Data JPA、Flyway |
| 数据库 | PostgreSQL 16 + PostGIS |
| 第三方 | Google Identity Services、Photon、OpenRouteService / OSRM、OpenFreeMap |

## 第一轮目标：走通一次完整流程

> 注册 / 登录 → 填写寄件信息 → 看到 6 个方案 → 下单 → 地图上看到设备移动 → 自动完成 → 评价 → 在 My Orders 里看到这一单

第一轮只求跑通，不求完善。下面这些放到第二轮：Google 登录、修改密码、订单搜索和筛选、取消订单的边界情况、自部署 OSRM。

## 分工

### 后端（API + 数据库）

| 成员 | 模块 | 第一轮要交付的 |
|---|---|---|
| **Shuhua Zhang** | 数据库与工程底座 | Spring Boot 项目骨架；docker-compose 启动 PostgreSQL + PostGIS；Flyway V1 迁移（11 张表和全部约束）；所有 Entity 和 Repository；DevRunner 种子数据（3 个站点、15 台设备、2 个测试账号）；GlobalControllerExceptionHandler 统一错误体 |
| **Yuning Cao** | 账号与安全 | SecurityFilterChain、JwtService、JwtAuthFilter；`POST /auth/register`、`POST /auth/login`、`GET /me`。第二轮：Google 登录、`PATCH /me`、`PUT /me/password` |
| **Zhuonan Yu** | 报价 | `GET /stations`、`POST /quotes`；RouteService（OpenRouteService，失败时退回直线）；定价（kg × 英里 × $1，最低 $1）；超重、陡坡、航程判断；最早出发时间和到达时间 |
| **Ziyue Wang** | 订单 | `POST /orders`（一个事务：锁设备、写占用、幂等）；`GET /orders`、`GET /orders/{id}`；OrderStateMachine；`POST /orders/{id}/review`；`POST /orders/{id}/cancel` |
| **Yangming Tang** | 模拟与追踪 | DeviceSimulator（每秒推进订单状态）；`GET /orders/{id}/track`（SSE，推送状态和位置）；TrackingEventBus；按路线和时间计算设备位置 |

### 前端

| 成员 | 模块 | 第一轮要交付的 |
|---|---|---|
| **Zhiyuan Wang** | 工程底座、账号、订单、追踪 | Next.js 项目骨架；`lib/api.ts`、`lib/sse.ts`、`lib/time.ts`；从后端 OpenAPI 生成类型；顶部导航和登录守卫；`/login`；`/orders`；`/orders/[id]/track`（SSE、时间线、评价表单）。第二轮：`/account` |
| **Pei Li** | 下单流程与地图 | `/ship`（表单、Photon 地址补全、寄件草稿）；`/ship/plans`（6 张方案卡片、下单）；地图组件（站点、路线、机器人沿路移动、无人机弧线），追踪页复用 |

### 模块之间的依赖

```
Shuhua（表、Repository、种子数据）
  ├─▶ Yuning（账号）──▶ 其他所有接口都要登录
  ├─▶ Zhuonan（报价）──▶ Ziyue（下单要用报价）──▶ Yangming（模拟和追踪要用订单）
  └─▶ Ziyue（评价、订单列表）
前端：对着后端的假数据先做页面，后端完成一个接口就切换一个
```

## 第一轮的做法

1. **先定契约（第 1–2 天）**：后端每人先写好自己的 Controller 和请求、响应 DTO，接口先返回假数据。springdoc 自动生成 OpenAPI，前端据此生成 TypeScript 类型，不用等后端实现就能开工。Shuhua 同时把数据库迁移和种子数据做完，因为其他人都依赖它。
2. **各自实现**：后端按上面的依赖顺序把假数据换成真实逻辑；前端对着假数据把页面做完。
3. **联调**：后端每完成一个接口就在群里说一声，前端切换到真接口。全部切换后，按“第一轮目标”那条流程完整走一遍。

## 协作约定

- **可以用 AI 写代码**（Claude Code、Cursor、Copilot 等都行）。提交前自己读一遍、跑通，对代码负责的是提交的人。
- 不直接推送到 `main`，一律通过 Pull Request 合并。
- **合并前必须经过一次 Review**，作者自己不能直接合并。Review 可以由另一位组员做，也可以用 AI Review（例如 Claude Code 的 `/code-review`）。用 AI Review 时，把 Review 结果贴到 PR 评论里，提出的问题处理完再合并。
- 分支命名：`fe/<功能>`、`be/<功能>`，例如 `be/quote-service`。
- 改接口字段时先改后端 DTO，并在 PR 里 @ 前端同学。
- 设计有变动时，同步更新 `docs/design.md`。
