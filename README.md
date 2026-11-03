# SkyLane

旧金山同城配送应用：3 个站点，地面机器人和无人机两种运力。用户填写寄件信息，比较 6 个方案后直接下单，在地图上实时追踪配送，完成后评价。

## 本地运行

从零搭建数据库、启动后端和前端、走一遍 Demo：见 [`docs/run-local.md`](docs/run-local.md)。

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

- [x] 后端启动时 Flyway 自动建表、DevRunner 写入种子数据（已在空库上验证；`docker compose` 这一步还需要在装了 Docker 的电脑上确认一次）
- [ ] 后端 `skylane.sim.speed` 设成 10，一单在 3 分钟内跑完：无人机约 1 分钟 ✅；机器人几公里要 5 分钟以上 ❌，演示时选无人机，或把 speed 调到 20–30
- [x] 前端 `npm run dev` 启动，连上本地后端（`.env.example` 默认 `NEXT_PUBLIC_USE_MOCK=0`）
- [ ] 上面 7 步连续走两遍都不出错：接口层已全部走通，**还需要在浏览器里完整彩排两遍**（Yuning 组织）
- [x] 固定演示地址：Pickup `Ferry Building`，Delivery `Oracle Park`，6 个方案全部可用（重量 ≤ 2 kg）

### 当前进度（2026-11-02，已合并到 `main`）

本地搭建和启动步骤见 [`docs/run-local.md`](docs/run-local.md)。测试账号 `alice@skylane.dev` / `bob@skylane.dev`，密码 `password123`。

| Demo 步骤 | 前端 | 后端 | 状态 |
|---|---|---|---|
| 1 注册登录 | `/login` | `/auth/register`、`/auth/login`、`/me`、JWT | ✅ 完成 |
| 2 填写寄件信息 | `/ship`、地址补全 | — | ✅ 完成 |
| 3 比较方案 | `/ship/plans`、地图 | `/stations`、`/quotes` | ✅ 完成 |
| 4 下单 | Place order | `POST /orders` | ✅ 完成 |
| 5 实时追踪 | `/orders/[id]/track` | DeviceSimulator、SSE `/track` | ✅ 完成 |
| 6 评价 | 评价表单 | `POST /orders/{id}/review` | ✅ 完成 |
| 7 My Orders | `/orders` | `GET /orders`、种子历史订单 | ✅ 完成 |

还没做（第二轮）：Google 登录、修改显示名和密码（`/account` 目前是占位页）、My Orders 搜索筛选、部署上线。

**需要负责人接手 Review 的代码**：账号 `BE/.../auth/` 和 `FE/src/app/(auth)/login/`（Yuning）、订单 `BE/.../order/`（Ziyue）、模拟与追踪 `BE/.../tracking/`（Yangming，时间规则沿用了 `DeliveryTracker` / `DroneTracker` 原型）、种子数据 `DevOrderSeeder`（Shuhua）。实现上与设计文档的差异见 `docs/design.md` 的“第一轮实现说明”。

### 为了 Demo 稳定，第一轮可以先简化

| 内容 | 第一轮做法 | 第二轮 |
|---|---|---|
| 机器人路线 | 先用 routing.openstreetmap.de（不需要 Key）；出错时退回直线，不能让报价失败 | OpenRouteService / 自部署 OSRM |
| 实时追踪 | 能显示移动就行；如果 SSE 来不及，先用每 2 秒轮询 `GET /orders/{id}`，接口不变（实际已做成 SSE + 断线补发） | SSE + 断线重连 |
| 登录 | 只做邮箱注册和登录 | Google 登录、修改密码、Account 页（目前是占位页） |
| My Orders | 只做列表和详情 | 搜索、筛选、排序 |
| 取消订单 | 后端接口做好，前端放一个按钮即可 | 出发前倒计时、边界情况 |
| 部署 | 只在本地演示 | 部署到线上 |

不在 Demo 脚本里的功能，第一轮都可以不做。

## 第二阶段：分工

第一轮 Demo 已完成并合并到 `main`。第二阶段按功能分成下面 5 个区块，每个区块端到端负责（后端 + 前端），开工前在群里认领。详细设计见 [`docs/design.md` 第 8 节](docs/design.md#8-第二阶段设计增量)。

**原则**：只做增量。已有接口只加可选字段，不改不删；数据库改动只在新的 Flyway 脚本里做，不修改 `V1`。

### 公共基础：数据库（✅ 已完成）

- `BE/src/main/resources/db/migration/V2__phase_two.sql`：新增 `operator`、`recommendation`、`conversation`、`conversation_message`、`agent_action` 5 张表；`app_user` 加 `role`、`operator_id`，`device` 加 `operator_id`，`quote` 加 `pickup_after`。
- 对应的 Entity 和 Repository 已加好。
- 种子数据：默认运营方 `SkyLane Fleet`（拥有全部 15 台设备）；新增 `admin@skylane.dev`（管理员）和 `ops@skylane.dev`（运营方），密码都是 `password123`。
- 已在旧库升级和全新空库两种情况下验证。

### 区块 1：运营方账号与后台监控（design 8.1，D27–D33）

| 端 | 要做 |
|---|---|
| 后端 | JwtAuthFilter 读出 `role`；`/ops/**`、`/admin/**` 鉴权（customer 返回 403）；设备管理（新增、停用、换站，有未来占用时返回 409 `DEVICE_HAS_BOOKINGS`）；`/ops/overview`、`/ops/live`（SSE）、`/ops/orders`、`/ops/metrics`；admin 创建公司和运营账号、禁用用户 |
| 前端 | `/ops` 路由组：总览（实时地图 + 数量卡片）、设备、订单、指标；导航按角色显示 Ops 入口 |

### 区块 2：推荐与 Agent（design 8.2，D34–D38）

| 端 | 要做 |
|---|---|
| 后端 | `POST /quotes/{id}/recommendation`（规则选方案，Claude 写理由，失败时只用规则）；`/agent/conversations` 及消息 SSE；工具调用；`agent_action` 确认和拒绝；用户每日限额 |
| 前端 | 方案页推荐标签和理由；`/assistant` 聊天页，下单和取消必须在操作卡片上点确认 |

### 区块 3：登录与 Account（design 8.3）

| 端 | 要做 |
|---|---|
| 后端 | `POST /auth/google`、`PATCH /me`、`PUT /me/password`；`/me` 返回 `role`；禁用账号登录返回 403 `ACCOUNT_DISABLED` |
| 前端 | 登录页 Google 按钮；完整的 `/account` 页面（替换现在的占位页） |

### 区块 4：预约下单（design 8.4，D39–D44）

| 端 | 要做 |
|---|---|
| 后端 | 报价支持 `pickup_after`；占用时长加安全余量；下单从“排在最后一单之后”改为按空档分配设备；新错误码 `PICKUP_TIME_INVALID` |
| 前端 | `/ship` 加取件时间选择；My Orders 和追踪页显示预约中的订单 |

### 区块 5：主页与整体 UI 优化

| 端 | 要做 |
|---|---|
| 前端 | 新的主页 `/`（产品介绍、站点和服务范围地图、“开始寄件”入口，未登录也能看）；统一导航、配色和组件样式；加载、空状态和错误提示；手机宽度适配；My Orders 搜索和筛选 |
| 后端 | 主页需要的公开接口（如 `GET /stations` 对未登录开放），按需加 |

### 顺序和依赖

1. 区块 1 的角色鉴权最先合并，区块 2、3 都会用到 `role`。
2. 区块 4 改动下单分配逻辑，和区块 2 的 Agent 下单有关，先于 Agent 联调完成。
3. 区块 5 可以和其他区块并行，最后统一过一遍样式。

## 协作约定

- **可以用 AI 写代码**（Claude Code、Cursor、Copilot 等都行）。提交前自己读一遍、跑通，对代码负责的是提交的人。
- 不直接推送到 `main`，一律通过 Pull Request 合并。
- **合并前必须经过一次 Review**，作者自己不能直接合并。Review 可以由另一位组员做，也可以用 AI Review（例如 Claude Code 的 `/code-review`）。用 AI Review 时，把 Review 结果贴到 PR 评论里，提出的问题处理完再合并。
- 分支命名：`fe/<功能>`、`be/<功能>`，例如 `be/quote-service`。
- 改接口字段时先改后端 DTO，并在 PR 里 @ 前端同学。
- 设计有变动时，同步更新 `docs/design.md`。
