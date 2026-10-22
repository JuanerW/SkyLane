# SkyLane

旧金山同城配送应用：3 个站点，地面机器人和无人机两种运力。用户填写寄件信息，比较 6 个方案后直接下单，在地图上实时追踪配送，完成后评价。

## 设计文档

- [`docs/design.md`](docs/design.md)：完整技术设计（关键决定、前端、后端、数据库、第三方 API），**实现以此为准**
- [`docs/design/`](docs/design/)：可点击的结构图，下载后用浏览器打开 `index.html`
  - `frontend.html`：页面、Hook、API 层
  - `backend.html`：Controller、Service、Repository、方法签名
  - `database.html`：ER 图、字段、约束

## 设计讨论记录（可以直接交给 AI 提问）

[`docs/conversation/2026-10-21-design-session.txt`](docs/conversation/2026-10-21-design-session.txt) 是整个设计过程和 AI 的完整对话记录：为什么这样设计、有哪些方案被否掉、每个决定的理由都在里面。

**关于自己负责的部分有疑问时，可以把这个文件交给 AI（Claude Code、ChatGPT 等）后直接提问。** 例如：

> 我是负责报价模块的 Zhuonan Yu。请先阅读 `docs/conversation/2026-10-21-design-session.txt` 和 `docs/design.md`，然后告诉我 `POST /quotes` 要实现哪些逻辑、用到哪些表、有哪些容易出错的地方。

注意：记录里是讨论的全过程，前面有些方案后来被推翻了（例如最早写过 Python 代码、订单号曾考虑 SL-10001 格式）。**内容冲突时一律以 `docs/design.md` 为准。** 提问时也请让 AI 以 `docs/design.md` 为准。

## 仓库结构

```
SkyLane/
├─ FE/          前端（Next.js），说明见 FE/README.md
├─ BE/          后端（Spring Boot）和数据库，说明见 BE/README.md
│  ├─ docker-compose.yml           本地 PostgreSQL + PostGIS
│  └─ database/migration/V1__init.sql   建表脚本
└─ docs/        设计文档
   ├─ design.md                     完整技术设计（以此为准）
   ├─ design/                       可点击的结构图
   └─ conversation/                 设计讨论的完整对话记录
```

## 技术栈

| 部分 | 技术 |
|---|---|
| 前端 | Next.js 14 + TypeScript、TanStack Query、MapLibre GL JS、Tailwind CSS |
| 后端 | Java 21、Spring Boot 3.3、Spring Security（JWT）、Spring Data JPA、Flyway |
| 数据库 | PostgreSQL 16 + PostGIS |
| 第三方 | Google Identity Services、Photon、OpenRouteService / OSRM、OpenFreeMap |

## 第一轮目标：做出第一个 Demo

第一轮的验收标准只有一条：**在本地完整演示一次下面的流程，中途不报错、不需要手动改数据库。**

### Demo 脚本（约 5 分钟）

| # | 操作 | 观众应该看到 |
|---|---|---|
| 1 | 打开前端，注册一个新账号 | 自动登录，进入 New Delivery |
| 2 | 填写物品（如 1.5 kg 蛋糕）、取件和收件地址（旧金山）、收件人、Arrive within 选 ASAP | 地址输入时有补全提示 |
| 3 | 点 Find Delivery Options | 地图上出现 3 个站点和路线，右侧 6 张方案卡片，有 fastest / cheapest / recommended 标签，不可用的方案显示原因 |
| 4 | 选一张卡片，点 Place order | 跳到追踪页 |
| 5 | 等待（模拟器开 10 倍速） | 设备从站点出发，机器人沿街道、无人机沿弧线移动；时间线依次出现 dispatched → picked_up → in_transit → arrived → completed |
| 6 | 完成后提交 5 星评价 | 评价保存成功 |
| 7 | 打开 My Orders | 新订单在列表最上面，状态 completed；下面还有种子数据里的历史订单 |

### Demo 验收清单

- [ ] `docker compose up -d` 启动数据库，后端启动时 Flyway 自动建表、DevRunner 写入种子数据
- [ ] 后端 `skylane.sim.speed` 设成 10，一单在 3 分钟内跑完
- [ ] 前端 `npm run dev` 启动，连上本地后端
- [ ] 上面 7 步连续走两遍都不出错
- [ ] 准备一组固定的演示地址，事先确认 6 个方案里至少 4 个可用

### 为了 Demo 稳定，第一轮可以先简化

| 内容 | 第一轮做法 | 第二轮 |
|---|---|---|
| 机器人路线 | 先用 routing.openstreetmap.de（不需要 Key）；出错时退回直线，不能让报价失败 | OpenRouteService / 自部署 OSRM |
| 实时追踪 | 能显示移动就行；如果 SSE 来不及，先用每 2 秒轮询 `GET /orders/{id}`，接口不变 | SSE + 断线重连 |
| 登录 | 只做邮箱注册和登录 | Google 登录、修改密码、Account 页 |
| My Orders | 只做列表和详情 | 搜索、筛选、排序 |
| 取消订单 | 后端接口做好，前端放一个按钮即可 | 出发前倒计时、边界情况 |
| 部署 | 只在本地演示 | 部署到线上 |

不在 Demo 脚本里的功能，第一轮都可以不做。

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
