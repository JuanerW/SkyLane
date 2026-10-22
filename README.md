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

## 分工（围绕 Demo 规划）

规划思路：
- **前端是瓶颈**：2 个人要做 5 个页面加地图组件。后端有 5 个人，其中账号模块在 Demo 范围内（只有邮箱注册、登录）工作量最轻。
- 所以由 **Yuning 端到端负责账号**：后端接口加前端 `/login` 页面，同时担任 **Demo 联调负责人**，减轻前端压力。
- 每个人都对应 Demo 脚本里的具体步骤，自己那几步跑不通就是没完成。

### 后端（API + 数据库）

| 成员 | 负责 | Demo 第一轮交付 | 对应 Demo 步骤 |
|---|---|---|---|
| **Shuhua Zhang** | 数据库与工程底座 | Spring Boot 项目骨架；Flyway 接入 `V1__init.sql`；全部 Entity 和 Repository；DevRunner 种子数据（3 站点、15 台设备、2 个测试账号各带 3 笔已完成订单）；统一错误体；springdoc 生成 OpenAPI | 全部（底座），第 7 步的历史订单 |
| **Yuning Cao** | 账号（前后端）+ Demo 联调负责人 | 后端：JWT、`POST /auth/register`、`POST /auth/login`、`GET /me`、CORS。前端：`/login` 页面、登录状态保存。联调：`.env.example`、一键启动说明、组织两次完整彩排并记录问题 | 第 1 步；整场 Demo 彩排 |
| **Zhuonan Yu** | 报价 | `GET /stations`、`POST /quotes`；路线（routing.openstreetmap.de，出错退回直线）；定价；超重、陡坡、航程；最早出发和到达时间；**准备一组固定的演示地址**，保证 6 个方案里至少 4 个可用 | 第 3 步 |
| **Ziyue Wang** | 订单 | `POST /orders`（事务、锁设备、写占用、幂等）；`GET /orders`、`GET /orders/{id}`（含时间线）；OrderStateMachine；`POST /orders/{id}/review`；`POST /orders/{id}/cancel` | 第 4、6、7 步 |
| **Yangming Tang** | 模拟与追踪 | DeviceSimulator（支持 10 倍速）；按路线和时间计算位置；`GET /orders/{id}/track`（SSE）。**保底方案**：如果 SSE 在联调前跑不稳，在 `GET /orders/{id}` 里加 `position` 字段供前端轮询，并同步更新 `docs/design.md` | 第 5 步 |

### 前端

| 成员 | 负责 | Demo 第一轮交付 | 对应 Demo 步骤 |
|---|---|---|---|
| **Zhiyuan Wang** | 前端底座、订单、追踪（兼前端协调） | Next.js 项目骨架；`lib/api.ts`、`lib/sse.ts`、`lib/time.ts`；从 OpenAPI 生成类型；顶部导航和登录守卫；`/orders`（列表和详情）；`/orders/[id]/track`（状态时间线、评价表单，地图用 Pei 的组件） | 第 5、6、7 步 |
| **Pei Li** | 下单流程与地图 | `/ship`（表单、Photon 地址补全、寄件草稿）；`/ship/plans`（6 张方案卡片、Place order）；地图组件 `components/map/`（站点、路线、机器人沿路移动、无人机弧线），追踪页复用 | 第 2、3、4、5 步 |

### 时间安排（按 8 个工作日，可按实际截止日期等比例压缩）

| 阶段 | 天数 | 要做到 | 关键人 |
|---|---|---|---|
| 定契约 | 第 1–2 天 | 后端每人写好 Controller 和 DTO，先返回假数据，OpenAPI 可以访问；**Shuhua 第 2 天前完成数据库和种子数据**；前端骨架和 `lib/api.ts` 就绪 | Shuhua、Zhiyuan |
| 各自实现 | 第 3–5 天 | 后端把假数据换成真实逻辑；前端对着假数据做完所有页面；Pei 的地图组件可用 | 全员 |
| 联调 | 第 6 天 | 前端全部切到真实接口，Demo 7 步能从头走到尾 | Yuning 组织 |
| 彩排 | 第 7 天 | 完整彩排两次，修掉所有阻塞问题，冻结代码 | Yuning 组织 |
| Demo | 第 8 天 | 演示 | 全员 |

### 关键路径和风险

```
Shuhua（数据库、种子数据）──▶ Yuning（登录）──▶ Zhuonan（报价）──▶ Ziyue（下单）──▶ Yangming（模拟、追踪）
                                                      │                   │                  │
前端：Pei（/ship、/ship/plans、地图）──────────────────┘                   │                  │
      Zhiyuan（/orders、追踪页）────────────────────────────────────────────┴──────────────────┘
```

- **Shuhua 的数据库**是所有人的前提，第 2 天必须完成。
- **Ziyue 的下单事务**和 **Yangming 的模拟器**是后端最复杂的两块，第 4 天要能用假数据跑通，留出联调时间。
- **Pei 的地图组件**同时被方案页和追踪页使用，第 5 天前要能用。
- 卡住超过半天，在群里说出来，由 Yuning 协调别人帮忙。

## 协作约定

- **可以用 AI 写代码**（Claude Code、Cursor、Copilot 等都行）。提交前自己读一遍、跑通，对代码负责的是提交的人。
- 不直接推送到 `main`，一律通过 Pull Request 合并。
- **合并前必须经过一次 Review**，作者自己不能直接合并。Review 可以由另一位组员做，也可以用 AI Review（例如 Claude Code 的 `/code-review`）。用 AI Review 时，把 Review 结果贴到 PR 评论里，提出的问题处理完再合并。
- 分支命名：`fe/<功能>`、`be/<功能>`，例如 `be/quote-service`。
- 改接口字段时先改后端 DTO，并在 PR 里 @ 前端同学。
- 设计有变动时，同步更新 `docs/design.md`。
