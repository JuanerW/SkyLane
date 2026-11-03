# SkyLane MVP 技术设计

旧金山同城配送：3 个站点，地面机器人和无人机两种运力。本文是 MVP 的完整设计，前端、后端、数据库和第三方服务都以这里为准。

可点击的结构图在 [`docs/design/`](design/)（下载后用浏览器打开 `index.html`）：

- [`index.html`](design/index.html)：设计总览（与本文内容相同）
- [`frontend.html`](design/frontend.html)：前端结构图
- [`backend.html`](design/backend.html)：后端结构图
- [`database.html`](design/database.html)：数据库结构图

## 1. 概述

### MVP 做什么

- **账号系统**：邮箱注册、邮箱登录、Google 登录、查看和修改资料、修改密码、退出登录。
- **新建订单**：填物品、重量、取件和收件地址、收件人、备注、送达时限。
- **比较方案并下单**：6 个方案（3 站点 × 2 设备），显示出发时间、到达时间、估算价格、路线。选中即下单，不付款。
- **实时追踪**：地图上看设备移动、进度、剩余时间；到达后自动完成。
- **取消**：设备出发前可以取消。
- **评价**：完成后 1–5 星加评论。
- **订单历史**：My Orders 列表和详情。

### MVP 不做

支付和 Checkout 页、重置密码、短信通知、收件人免登录追踪链接、Smart Recommendation、Agent Mode。

### 用户主流程

```
/login ─▶ /ship（填写）─▶ /ship/plans（比较并下单）─▶ /orders/[id]/track（追踪）─▶ 完成后评价
顶部导航：New Delivery · My Orders · Account
```

## 2. 关键设计决定

### 账号与安全

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| D1 | 需要账号系统，但不要重置密码 | 做 Account 页：查看邮箱、修改显示名、修改密码、退出登录。不做忘记密码和邮件。 | 重置密码需要发邮件服务，MVP 没有。 |
| D2 | JWT 无状态，改密码后旧 token 仍有效 | `app_user.token_version` 写进 JWT；改密码时加 1，过滤器发现版本不符就返回 401。改密码接口返回新 token。 | 不引入 Redis 黑名单也能让旧登录失效。 |
| D3 | 同一邮箱既用密码注册又用 Google 登录 | 一个用户可以同时有密码和 Google 身份。Google 登录时若 `email_verified` 且邮箱已存在，就绑定到这个账号（写入 `google_sub`）。 | 避免同一个人有两个账号、两份订单历史。 |
| D4 | 只用 Google 登录的用户能否改密码 | 不显示修改密码。后端返回 409 `NO_PASSWORD`。 | 没有旧密码可以校验。 |
| D5 | token 放哪里 | localStorage + `Authorization: Bearer`，有效期 7 天。不用 Cookie。 | 前后端分开部署、跨域，Bearer 最简单；SSE 也用 fetch 带头。前端不用 `dangerouslySetInnerHTML`。 |
| D6 | 密码哈希算法 | Spring Security 自带的 BCrypt，密码至少 8 位。 | 不用额外依赖。 |

### 下单与排队

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| D7 | 不做 Checkout，价格还要不要 | 只显示估算价，用来比较方案和打 cheapest 标签。选中方案后，底部确认条直接 Place order。 | 价格无所谓，但方案比较需要一个维度。 |
| D8 | 定价公式 | 重量 kg × 取件到收件的英里数 × $1，最低 $1，四舍五入到美分。机器人用路网距离，无人机用直线距离。 | 简单、可配置。 |
| D9 | 设备随时可接单，报价后到下单前别人先下了单 | 下单时重新计算出发时间。比报价时晚 5 分钟以内照常下单；晚更多返回 409 `QUOTE_STALE`，前端自动重新报价并提示。 | 设备能排队，所以几乎不会“没车”；但用户看到的时间不能悄悄变很多。 |
| D10 | 送达时限放前端算还是后端算 | 前端只传 `arrive_within_minutes`（空 = ASAP，或 30 / 60 / 120），后端用服务器时间算截止时间。报价和下单都校验，赶不上返回 `DEADLINE_MISSED`。 | 不受用户电脑时钟影响。 |
| D11 | 取消和出发同时发生 | 取消用条件更新：`WHERE status = 'confirmed' AND departs_at > now()`，影响 0 行就返回 409 `NOT_CANCELLABLE`。模拟器出发也用条件更新。 | 数据库保证两者只有一个成功。 |
| D12 | 取消后，排在后面的订单要不要提前 | 不提前。 | 用户已经看到的出发和到达时间不会变，逻辑也简单。 |
| D13 | 重复点击下单 | 前端选中方案时生成 `Idempotency-Key`，重试复用；数据库 `UNIQUE (user_id, idempotency_key)`。 | 网络重试不会生成两单。 |
| D14 | 订单号 | 直接用 UUID，页面显示前 8 位，点击复制完整 ID。 | 不需要额外的序列和字段。 |

### 路线、追踪与数据

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| D15 | 实时位置存不存库 | 不存。位置由路线、出发时间、速度实时算出；只把状态变化写进 `order_event`。 | 省掉每秒写库，重连和重启后位置自然正确。 |
| D16 | 追踪用 SSE 还是轮询 | SSE。每秒推一次位置，15 秒一次心跳；断线后前端带 `Last-Event-ID` 重连，后端补发错过的状态。订单到终态后关流。 | 团队原方案就是 REST + SSE。 |
| D17 | 还没出发的预约单打开追踪页 | 显示 Scheduled、出发倒计时和取消按钮；出发后切换成地图追踪。 | 排队订单很常见。 |
| D18 | 无人机怎么画 | 后端只算时间（起飞 1 分钟 + 直线距离 ÷ 15 m/s + 降落 1 分钟），推进度和剩余秒数。前端画弧线、放图标、显示倒计时。 | 空中不需要路网。 |
| D19 | 机器人陡坡怎么判断 | MVP 写死几个陡坡区域多边形（Twin Peaks、Nob Hill、Russian Hill、Telegraph Hill），路线穿过就不可用。 | 不依赖海拔 API，结果稳定、可测试。 |
| D20 | 风速 | MVP 不考虑。 | 减少外部依赖。 |
| D21 | 单位 | 后端内部用米、秒、美分；API 返回 `distance_m`、`price_cents`；前端显示英里和美元。 | 避免前后端各自换算出错。 |
| D22 | 时间和时区 | 数据库 `timestamptz` 按 UTC；API 用 ISO 8601 带 `Z`；前端统一按 America/Los_Angeles 显示。 | 只在显示时转换一次。 |
| D23 | 服务范围 | 旧金山范围：纬度 37.70–37.84，经度 −122.52 到 −122.35。超出返回 400 `OUT_OF_SERVICE_AREA`；前端 Photon 用同一范围。 | 前后端用同一组数字。 |
| D24 | 模拟器和多实例 | 后端只部署 1 个实例。以后扩容时给定时任务加 ShedLock。 | 多实例会重复推进状态。 |
| D25 | 前后端字段对不上 | 后端用 springdoc 生成 OpenAPI，前端用 openapi-typescript 生成类型。改接口必须先改后端 DTO。 | 契约只有一个来源。 |
| D26 | 数据库结构谁说了算 | Flyway 迁移脚本，`ddl-auto` 设为 `validate`。 | Staybooking 用的 `create-drop` 每次启动都会清库。 |

## 3. 前端

### 技术栈

Next.js 14（App Router）+ TypeScript；TanStack Query 管请求和缓存；React Hook Form + Zod 做表单校验；MapLibre GL JS 画地图；Tailwind CSS。

### 页面

| 路由 | 设计稿 | 主要内容 | 调用接口 |
|---|---|---|---|
| `/login` | F · Sign In | 登录 / 注册切换、Google 按钮。没有忘记密码。 | `/auth/register` `/auth/login` `/auth/google` |
| `/ship` | A · New Delivery | 物品描述、重量 kg（≤ 10）、易碎；取件和收件地址（Photon 补全）；收件人姓名和电话；备注；Arrive within；最近 3 单。 | Photon、`GET /orders?limit=100`（取前 3 条，与 My Orders 共用缓存） |
| `/ship/plans` | B · Compare Plans | 地图和路线；6 张方案卡片（出发 Now 或 Next free、到达时间、价格、距离、不可用原因）；Optimize for 和 Vehicle 过滤；底部确认条 Place order。 | `POST /quotes` `POST /orders` |
| `/orders/[id]/track` | D · Live Tracking | 出发前：倒计时和取消。出发后：地图、阶段、剩余时间、时间线。完成后：评价表单。 | `GET /orders/{id}`、SSE `/track`、`/cancel`、`/review` |
| `/orders` | E · My Orders | 状态 tab、搜索（订单号前 8 位或收件人）、按日期排序、详情抽屉。 | `GET /orders?limit=100` `/cancel` `/review` |
| `/account` | Account Settings | 邮箱（只读）、显示名、登录方式、修改密码（只对有密码的账号显示）、退出登录。 | `GET /me` `PATCH /me` `PUT /me/password` |

### 数据层

- `lib/api.ts`：所有 REST 请求的出口，加 Bearer 头，把错误体转成 `ApiError(code)`；收到 401 清 token 并跳 `/login`。
- `lib/sse.ts`：用 fetch + ReadableStream 读 SSE，记录最后一个事件 id，断线按 1 / 2 / 4 秒退避重连。
- `lib/photon.ts`：地址补全，≥ 3 个字符、防抖 300 ms，限定旧金山范围；注意 GeoJSON 坐标顺序是 [lng, lat]。
- `lib/time.ts`：统一按美西时间格式化。
- Hooks：`useAuth`、`useShipmentDraft`、`useQuote`、`useCreateOrder`、`useOrders`、`useTrackingStream`、`useReview`、`useAddressSearch`。
- 寄件草稿（表单、quote_id、option_id、idempotency_key）存 sessionStorage，两个 ship 页面共用，下单成功后清空。

### 错误码在页面上怎么处理

| 错误码 | 页面 | 处理 |
|---|---|---|
| `INVALID_CREDENTIALS` `EMAIL_TAKEN` | /login、/account | 表单内提示 |
| `OUT_OF_SERVICE_AREA` | /ship | 地址字段标红：Only addresses in San Francisco |
| `QUOTE_EXPIRED` `QUOTE_STALE` | /ship/plans | 提示 Options updated，自动重新报价 |
| `DEADLINE_MISSED` `DEVICE_UNAVAILABLE` | /ship/plans | 该卡片变灰，提示原因 |
| `NOT_CANCELLABLE` | /orders、追踪页 | 提示 Already departed，刷新订单 |
| `NO_PASSWORD` | /account | 不显示修改密码（正常不会触发） |

### 与 UI 设计稿的有意差异

UI 设计稿《Dispatch & Delivery Management》比 MVP 多一些内容。实现时按下表调整，其余照设计稿做。

| 设计稿 | MVP 做法 |
|---|---|
| Jinshan City 和示意地图 | 真实旧金山地图（MapLibre + OpenFreeMap） |
| C · Checkout（Stripe 付款、价格明细、短信） | 不做；在 Compare Plans 底部确认条直接 Place order |
| Sign In 的 Forgot password | 不做，隐藏入口 |
| Account 的 Company、Phone、默认地址、通知、付款方式 | 只保留邮箱、显示名、修改密码、退出 |
| New Delivery 的 Size、Special handling、Pickup notes、Delivery time | 去掉 Size 和 Special handling（保留 Fragile）；合并为一个 Notes；Delivery time 统一为 Arrive within |
| 按产品命名的方案（Robot Standard 等） | 6 个方案按设备类型分组，站点作副标题 |
| 订单号 DD-20431 | UUID 前 8 位 |
| Tracking 的 Confirm receipt | 到达后自动完成，只显示评价 |
| AI 对话填表、AI 推荐横幅 | 后续阶段，MVP 隐藏 |

### 目录结构

```
skylane-fe/src/
├─ app/(auth)/login/page.tsx
├─ app/(app)/layout.tsx              # AppShell：顶部导航 + 登录守卫
├─ app/(app)/ship/page.tsx
├─ app/(app)/ship/plans/page.tsx
├─ app/(app)/orders/page.tsx
├─ app/(app)/orders/[id]/track/page.tsx
├─ app/(app)/account/page.tsx
├─ components/{ui,shell,ship,plans,tracking,orders,account,map}/
├─ hooks/
├─ lib/{api,sse,photon,time}.ts
└─ types/api.ts                      # openapi-typescript 生成，不手改
```

## 4. 后端

### 技术栈

Java 21、Spring Boot 3.3：Spring Web、Spring Security、Spring Data JPA + Hibernate Spatial、Flyway、springdoc-openapi；PostgreSQL 16 + PostGIS；JWT 用 jjwt；Google 登录校验用 google-api-client。分层沿用 Twitch / Staybooking：Controller → Service → Repository。

### 包结构

```
com.laioffer.skylane
├─ SkyLaneApplication, AppConfig（声明 SecurityFilterChain、PasswordEncoder）,
│  GlobalControllerExceptionHandler, DevRunner（种子数据）
├─ user/        AuthController（/auth/**、/me）, UserService, GoogleTokenVerifier
├─ quote/       QuoteController, QuoteService（含定价）, RouteService（含陡坡判断）
├─ order/       OrderController, OrderService, OrderStateMachine
├─ tracking/    TrackingController, TrackingService（含位置计算）, TrackingEventBus
├─ review/      ReviewController, ReviewService
├─ device/      DeviceSimulator（@Scheduled）
├─ security/    JwtAuthFilter, JwtService
└─ repository/  UserRepository, DeviceRepository, QuoteRepository, OrderRepository,
                DeviceBlockRepository, OrderEventRepository, ReviewRepository
```

### 接口

前缀 `/api/v1`。除 `/auth/**` 外都需要登录。字段统一 snake_case。

| 方法 | 路径 | 请求 | 响应 | 错误 |
|---|---|---|---|---|
| POST | `/auth/register` | email, password（≥ 8）, display_name | 201 access_token, user | `EMAIL_TAKEN` |
| POST | `/auth/login` | email, password | 200 access_token, user | `INVALID_CREDENTIALS` |
| POST | `/auth/google` | id_token | 200 access_token, user | `INVALID_GOOGLE_TOKEN` |
| GET | `/me` | — | id, email, display_name, has_password, google_linked, created_at | 401 |
| PATCH | `/me` | display_name | 200 同上 | `VALIDATION_ERROR` |
| PUT | `/me/password` | current_password, new_password | 200 新 access_token | `INVALID_CREDENTIALS` `NO_PASSWORD` |
| GET | `/stations` | — | [id, code, name, lat, lng] | — |
| POST | `/quotes` | item{description, fragile}, weight_kg, pickup{text, lat, lng}, dropoff{…}, notes?, arrive_within_minutes? | 201 quote_id, expires_at, options[6]{option_id, station, device_type, available, unavailable_reason, departs_at, eta_at, price_cents, distance_m, route_points, tags} | `OUT_OF_SERVICE_AREA` `SAME_ADDRESS` |
| POST | `/orders` | quote_id, option_id, recipient{name, phone}；头 Idempotency-Key | 201 order_id, status, device, price_cents, departs_at, eta_at | `QUOTE_EXPIRED` `QUOTE_STALE` `DEADLINE_MISSED` `DEVICE_UNAVAILABLE` `OPTION_NOT_FOUND` |
| GET | `/orders?limit=100` | — | [order_id, status, device_type, price_cents, created_at, departs_at, eta_at, completed_at, pickup_text, dropoff_text, recipient_name, reviewed] | — |
| GET | `/orders/{id}` | — | 订单全部字段 + route_points + cancellable + timeline[] + review | `ORDER_NOT_FOUND` |
| POST | `/orders/{id}/cancel` | reason? | 200 status = canceled | `NOT_CANCELLABLE` |
| SSE | `/orders/{id}/track` | 头 Last-Event-ID（重连时） | `status` {status, at}（带 id）；`position` {lat, lng, progress, remaining_s} | `ORDER_NOT_FOUND` |
| POST | `/orders/{id}/review` | rating 1–5, comment? | 201 rating, comment | `NOT_COMPLETED` `ALREADY_REVIEWED` |

错误体统一为 `{"error": {"code", "message", "details"}}`。状态码：400 校验，401 未登录，404 不存在或不属于当前用户，409 业务冲突。

### 报价

1. 校验两个地址都在服务范围内且不相同；截止时间 = 现在 + arrive_within_minutes（为空则无截止）。
2. 对“活跃站点 × 设备类型”的每个组合（MVP 6 个）：
    - 路线：机器人调 RouteService 走路网；无人机算直线。
    - 硬约束：`OVERWEIGHT`（无人机 > 2 kg，机器人 > 10 kg）、`STEEP_TERRAIN`、`OUT_OF_RANGE`（无人机往返 > 15 km）。命中则价格和时间为空。
    - 出发时间 = max(现在, 该站该类设备最早空闲时间)；没有 active 设备则 `NO_DEVICE_AVAILABLE`。
    - 到达时间 = 出发 + 行驶时间 + 装卸；晚于截止时间则 `DEADLINE_MISSED`。
    - 价格按 D8。
3. 可用方案里打 `fastest`、`cheapest`、`recommended`（到达时间和价格归一化后按 0.6 : 0.4 加权最低）。
4. 保存 quote 和 6 条 quote_option，有效期 10 分钟。

### 下单（一个事务）

1. Idempotency-Key 已存在 → 返回原订单。
2. 校验报价未过期、属于当前用户、方案属于该报价且可用。
3. 锁定该站该类 active 设备（`FOR UPDATE SKIP LOCKED`），选最早空闲的一台，出发时间 = max(现在, 它最后一段占用的结束时间)。
4. 出发时间比报价晚 5 分钟以上 → `QUOTE_STALE`；到达晚于截止 → `DEADLINE_MISSED`。
5. 插入两段占用 `device_block`：order（出发到送达）和 return（返航）。违反排斥约束就换下一台。
6. 插入 delivery_order（地址、价格、备注、路线都做快照）、order_item、第一条 order_event（confirmed）。

### 订单状态

```
confirmed ─▶ dispatched ─▶ picked_up ─▶ in_transit ─▶ arrived ─▶ completed
confirmed（出发前）─▶ canceled          任何非终态 ─▶ failed
```

所有状态变化只经过 `OrderStateMachine.transition()`：同一事务更新 `delivery_order.status`、追加 `order_event`，提交后推给 TrackingEventBus。取消时释放该订单的两段占用。

### 设备模拟器

`@Scheduled(fixedRate = 1000)` 每秒扫描进行中的订单，按时间推进状态：到 departs_at → dispatched；到达取件点 → picked_up，停留 1 分钟后 → in_transit；到达收件点 → arrived，30 秒后 → completed。速度：机器人 1.5 m/s，无人机 15 m/s。所有时长除以 `skylane.sim.speed`，演示时设成 10。

### 追踪

连接时：校验订单归属 → 按 Last-Event-ID 补发状态事件 → 推一次当前位置 → 注册到 TrackingEventBus。之后每秒由模拟器推位置。TrackingService 根据 route_points、departs_at 和速度算出位置、进度和剩余秒数。SseEmitter 超时 30 分钟，15 秒心跳，终态后关流。

### 安全

- SecurityFilterChain：无状态、关闭 CSRF、CORS 只允许前端域名；`/api/v1/auth/**` 放行，其余要求登录。
- JwtAuthFilter：解析 Bearer，校验签名、过期时间和 token_version，把 userId 放进 SecurityContext。Controller 用 `@AuthenticationPrincipal` 取当前用户。
- 所有订单查询都带 user_id 条件，别人的订单返回 404。

### 配置

```yaml
skylane:
  jwt: { secret: ${JWT_SECRET}, ttl-days: 7 }
  google: { client-id: ${GOOGLE_CLIENT_ID} }
  cors: { allowed-origins: ${FRONTEND_ORIGIN} }
  routing: { provider: ors, ors-api-key: ${ORS_API_KEY}, osrm-base-url: ${OSRM_URL:} }
  pricing: { usd-per-kg-mile: 1.00, min-cents: 100 }
  quote: { ttl-minutes: 10, stale-tolerance-minutes: 5 }
  service-area: { min-lat: 37.70, max-lat: 37.84, min-lng: -122.52, max-lng: -122.35 }
  sim: { speed: 1.0 }
spring.jpa.hibernate.ddl-auto: validate
```

### 演示数据（DevRunner，仅 dev）

3 个站点（SoMa、Mission、Marina），每站 3 台机器人 + 2 架无人机；2 个测试账号，各有 3 笔已完成订单，其中 1 笔已评价。

### 第一轮实现说明（与上文的差异）

第一轮 Demo 的代码与本文一致，以下几处是实现时的取舍，以代码为准：

| 内容 | 本文写法 | 实际实现 | 原因 |
|---|---|---|---|
| 包名 | `com.laioffer.skylane`，`user/` `security/` `review/` `device/` | `com.skylane`；账号在 `auth/`，评价接口并入 `order/`，模拟器在 `tracking/` | 沿用已有骨架；模块少，合并后更好找 |
| JWT | jjwt | 自己用 JDK 的 HmacSHA256 实现 HS256（`auth/JwtService`） | 不新增依赖；只需要签发和校验 |
| 订单读写 | Spring Data JPA | `JdbcTemplate` + SQL | 与报价模块一致；PostGIS、`FOR UPDATE SKIP LOCKED`、条件更新直接写 SQL 更清楚 |
| 返航占用时长 | 未规定 | 收件点到站点直线距离（机器人 ×1.3）÷ 速度 | 设备回站后才能接下一单，需要一个估算 |
| 下单时的 `eta_at` | 出发 + 行驶 + 装卸 | 同一公式，但除以 `skylane.sim.speed` | 演示时 10 倍速，页面上的到达时间要和模拟一致；speed = 1 时与报价完全相同 |
| 收件人电话 | E.164 | 后端把 `(415) 555-0123` 这类写法规范成 `+14155550100` | 表单不要求用户输入 E.164 |
| 新增错误码 | — | `DUPLICATE_REQUEST`（同一 Idempotency-Key 并发提交）；不可用方案下单返回 `DEVICE_UNAVAILABLE` | 补齐边界情况 |
| `position.progress` | 进度 | 已行驶距离 ÷ 路线总长（0–1） | 前端按比例在路线或弧线上放图标 |
| 未登录访问不存在的路径 | — | 401 而不是 404 | 除 `/auth/**` 外都要求登录，不暴露路由 |
| 第一轮未做 | — | `/auth/google`、`PATCH /me`、`PUT /me/password`；`/account` 只有占位页（显示邮箱、退出登录） | README 第一轮简化 |

## 5. 数据库

> 第二阶段新增的表、列和索引见 8.6（Flyway `V2__phase_two.sql`、`V3__phase_two_indexes.sql`）。

PostgreSQL 16 + PostGIS，MVP 共 11 张表。主键 UUID（order_event 用自增 bigint）；时间 `timestamptz`；坐标 `geography(Point, 4326)`；金额整数美分。

| 表 | 主要字段 | 约束 / 说明 |
|---|---|---|
| `app_user` | id, email, display_name, google_sub, token_version, created_at, updated_at, disabled_at | email citext UNIQUE；google_sub 可空、UNIQUE；token_version 默认 0 |
| `user_credential` | user_id, password_hash, updated_at | PK = FK → app_user；只用 Google 的用户没有这一行 |
| `station` | id, code, name, address_text, location, is_active | code UNIQUE；location GiST 索引 |
| `device` | id, code, station_id, device_type, status, battery_pct, location, last_seen_at | code UNIQUE（如 R-114）；battery 仅展示 |
| `quote` | id, user_id, item_description, is_fragile, weight_kg, notes, pickup/dropoff text 与 location, deadline_at, expires_at | weight_kg 0–10；notes ≤ 500 字；只追加不修改 |
| `quote_option` | id, quote_id, station_id, device_type, available, unavailable_reason, departs_at, eta_at, price_cents, distance_m, route_points, tags | UNIQUE (quote_id, id)；route_points 为 [[lat, lng], …] 的 jsonb |
| `delivery_order` | id, user_id, quote_id, option_id, device_id, device_type, status, recipient_name, recipient_phone, price_cents, notes, 地址快照, route_points, departs_at, eta_at, idempotency_key, confirmed/completed/canceled_at, cancel_reason | (quote_id, option_id) → quote_option；UNIQUE (user_id, idempotency_key)；status CHECK；索引 (user_id, created_at DESC)、(status) |
| `order_item` | id, order_id, description, is_fragile, weight_kg | order_id UNIQUE（每单一件） |
| `device_block` | id, device_id, order_id, kind, starts_at, ends_at, period, released_at | kind = order / return；排斥约束防止同一设备时间重叠 |
| `order_event` | event_id, order_id, status, occurred_at, actor_type, details | event_id 自增，兼作 SSE 的事件 id；索引 (order_id, event_id) |
| `delivery_review` | id, order_id, user_id, rating, comment, created_at | order_id UNIQUE；rating 1–5 |

### 关键约束（Flyway V1）

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS citext;

ALTER TABLE device_block ADD COLUMN period tstzrange
  GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED;
ALTER TABLE device_block ADD CONSTRAINT device_block_no_overlap
  EXCLUDE USING gist (device_id WITH =, period WITH &&) WHERE (released_at IS NULL);

ALTER TABLE quote_option ADD CONSTRAINT quote_option_quote_uq UNIQUE (quote_id, id);
ALTER TABLE delivery_order ADD CONSTRAINT order_option_fk
  FOREIGN KEY (quote_id, option_id) REFERENCES quote_option (quote_id, id);
ALTER TABLE delivery_order ADD CONSTRAINT order_idem_uq UNIQUE (user_id, idempotency_key);
ALTER TABLE delivery_order ADD CONSTRAINT order_status_chk CHECK (status IN
  ('confirmed','dispatched','picked_up','in_transit','arrived','completed','canceled','failed'));
```

相对团队原设计的改动：删掉 quote 上的收件人字段；weight_tier 改为 weight_kg；app_user 用 google_sub 和 token_version 取代 auth_provider / provider_subject；新增 user_credential、departs_at、notes、idempotency_key、route_points；补 device_block.released_at；location_event 和 recommendation 放到后续阶段。

## 6. 第三方 API

MVP 只依赖 4 个外部服务，全部免费。额度来自各家官网的公开说明，接入前以官网为准。

| 服务 | 用途 | 调用方 | Key | 额度 | 失败时 |
|---|---|---|---|---|---|
| **Google Identity Services** | Google 登录，前端拿 id_token，后端校验 | 前端 + 后端 | OAuth Client ID（Google Cloud Console 创建） | 免费 | 隐藏 Google 按钮，仍可邮箱登录 |
| **Photon**（photon.komoot.io） | 地址补全 | 前端 | 不需要 | 公共服务，合理使用 | 提示稍后重试；限流时降低请求频率 |
| **OpenRouteService** | 机器人步行路线 | 后端 | 免费注册的 API Key | 约每天 2000 次路线请求 | 退回直线 × 1.3 并标记 approximate |
| **OSRM**（自部署） | 演示时替代 ORS | 后端 | 不需要 | 无限制 | 同上 |
| **MapLibre GL JS + OpenFreeMap** | 旧金山底图 | 前端 | 不需要 | 免费，需显示 OpenStreetMap 署名 | 换 Leaflet + OSM 瓦片 |

每次报价最多 3 次路线请求（只有机器人方案需要），路线存进报价，模拟时不再调用。所以开发和演示期间远低于免费额度。演示前用 Docker 自部署 OSRM（Geofabrik 北加州数据），避免依赖公共服务。

## 7. 后续阶段

Smart Recommendation、Agent Mode 已细化到第 8 节，作为第二阶段的一部分。

- **Smart Recommendation**：`POST /ai/recommendation`，结合风速（Open-Meteo）、排队情况和用户备注，由 Claude API 给出推荐理由；新增 recommendation 表。
- **Agent Mode**：`POST /agent/chat`（SSE）+ 确认接口，对话式下单；新增 conversation、conversation_message、agent_action 表。
- **其他**：支付和 Checkout、重置密码（需要邮件服务）、收件人免登录追踪链接、接真实设备（启用 location_event 存 GPS 轨迹）。

## 8. 第二阶段设计（增量）

第二阶段做 5 块：运营方账号与后台监控、推荐与 Agent、账号完善、预约下单、主页与 UI。原则是**只做增量**：已有接口的请求和响应只加可选字段，不改不删；数据库改动放在新的 Flyway 脚本里（`V2__phase_two.sql`、`V3__phase_two_indexes.sql`），已合并的脚本一律不修改。

### 8.1 运营方账号与后台监控

机器人或无人机公司（运营方）有自己的账号，可以管理自己的设备，并在后台监控设备和订单。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| D27 | 角色怎么表示 | `app_user.role` 取 `customer`（默认）/ `operator` / `admin`。operator 必须属于一个 `operator`（公司），admin 是平台管理员，能看全部数据。 | 一个字段就够，不需要 RBAC 表。 |
| D28 | 角色放不放进 JWT | 不放。JwtAuthFilter 本来每次都查 `app_user`（校验 token_version），顺便读出 role 和 operator_id。 | 改角色立即生效，不用重新登录。 |
| D29 | 运营方能看到什么 | 只能看到 `device.operator_id` 是自己公司的设备，以及由这些设备执行的订单。收件人电话在后台只显示后 4 位。 | 运营方不是平台方，按最小权限处理用户数据。 |
| D30 | 设备能不能删除 | 不删，只能停用（`status = 'maintenance'` 或 `'offline'`）。停用时如果还有未释放的未来占用，返回 409 `DEVICE_HAS_BOOKINGS`，需要先取消或改派这些订单。 | 历史订单要能查到设备；避免已预约的订单突然没车。 |
| D31 | 设备实时状态从哪来 | 不新增存储。由 `device_block` 加当前时间算出：`idle` / `on_order` / `returning` / 停用状态，位置沿用 D15 的计算。 | 与 D15 一致，不改模拟器。 |
| D32 | 后台怎么实时刷新 | 一条 SSE `/ops/live`，每 2 秒推一次可见范围内所有设备的状态和位置。 | 复用 TrackingService，不需要每个订单开一条流。 |
| D33 | 运营方账号怎么开通 | 由 admin 在后台创建公司并邀请（直接创建账号、设初始密码），不开放自助注册。dev 环境种子数据加 1 个 admin 和 1 个 operator 账号。 | MVP 没有邮件服务，也不需要审核流程。 |

接口（前缀 `/api/v1/ops`，需要 operator 或 admin；customer 访问返回 403 `FORBIDDEN`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ops/overview` | 各站点设备数量（idle / on_order / returning / 停用）、今日订单数、进行中订单数 |
| SSE | `/ops/live` | `devices` 事件：[{device_id, code, state, lat, lng, order_id?}] |
| GET | `/ops/devices` | 设备列表，含实时状态和未来占用数 |
| POST | `/ops/devices` | code, station_id, device_type → 201；admin 需额外传 operator_id |
| PATCH | `/ops/devices/{id}` | status、station_id；有未来占用时改站点或停用返回 409 `DEVICE_HAS_BOOKINGS` |
| GET | `/ops/devices/{id}/schedule` | 这台设备今天和未来的占用（order / return），供预约排班查看 |
| GET | `/ops/orders?status=&from=&to=` | 可见范围内的订单 |
| GET | `/ops/metrics?from=&to=` | 准点率（completed_at ≤ eta_at）、平均配送时长、评分分布、设备利用率 |
| POST | `/admin/operators` | 仅 admin：创建公司 |
| POST | `/admin/users` | 仅 admin：创建 operator 账号 |
| PATCH | `/admin/users/{id}` | 仅 admin：禁用（`disabled_at`）、强制下线（`token_version` 加 1） |

前端新增 `/ops` 路由组（同一个 Next.js 项目）：总览（地图 + 数量卡片）、设备、订单、指标。地图复用 `components/map/`。顶部导航按 role 显示 Ops 入口。

### 8.2 推荐与 Agent

沿用第 7 节的方向，具体化如下。模型用 Claude API，Key 只放在后端（`ANTHROPIC_API_KEY`）。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| D34 | 推荐由谁算 | 规则先选出推荐方案（例如满足时限里最便宜的，或最快的），LLM 只负责写一两句推荐理由。LLM 不可用时只显示规则结果，不影响下单。 | 结果稳定、可测试；LLM 只是锦上添花。 |
| D35 | 推荐要考虑什么 | 价格、到达时间、排队（departs_at）、用户备注；风速用 Open-Meteo，风大时降低无人机排序。 | 第一版的 D20 不考虑风速，第二阶段补上。 |
| D36 | Agent 能不能直接下单 | 不能。Agent 只能报价、查询、提出下单建议，生成一条 `agent_action`（待确认），用户在卡片上点确认后，后端才调用现有下单逻辑，Idempotency-Key 就用 action id。 | 下单是有后果的操作，必须由用户确认；重复确认也不会生成两单。 |
| D37 | Agent 能用哪些工具 | `search_address`（Photon）、`create_quote`、`get_order`、`list_orders`、`propose_order`、`cancel_order`（同样需要确认）。全部以当前用户身份执行，复用现有 Service 的权限检查。 | 不给 Agent 额外权限。 |
| D38 | 提示注入和成本 | 用户备注、地址文本只作为数据传给模型；每个用户每天消息数和 token 有上限（配置项），超出返回 429 `RATE_LIMITED`。 | 防止滥用和费用失控。 |

接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/quotes/{id}/recommendation` | 201 {option_id, reason, factors}；同一报价重复调用返回缓存结果 |
| POST | `/agent/conversations` | 201 {conversation_id} |
| POST | `/agent/conversations/{id}/messages` | 请求 {text}；SSE 返回 `delta`（文字）、`action`（待确认的操作卡片）、`done` |
| GET | `/agent/conversations/{id}` | 历史消息和操作 |
| POST | `/agent/actions/{id}/confirm` | 执行操作，返回结果（例如订单）；过期或已执行返回 409 `ACTION_NOT_PENDING` |
| POST | `/agent/actions/{id}/reject` | 放弃操作 |

`/quotes` 的响应只新增可选字段 `recommended_option_id`，不改已有字段。前端：方案页显示推荐标签和理由；新增 `/assistant` 聊天页，操作卡片复用方案卡片组件。

运营方后台可以再加一个只读的运营助手（例如“现在哪个站最忙”“总结今天的延误”），工具只读，放在本阶段最后视时间决定。

### 8.3 登录与 Account（按第 2–4 节完成）

设计不变，第二阶段把第一轮没做的部分做完：Google 登录（D3）、`PATCH /me`、`PUT /me/password`（D2、D4）、`/account` 页面。数据库已有 `google_sub` 和 `token_version`，不需要改表。新增两点：

- `GET /me` 响应新增 `role`、`operator_id`，前端据此显示 Ops 入口。
- 被禁用的账号（`disabled_at` 不为空）登录返回 403 `ACCOUNT_DISABLED`。

### 8.4 预约下单

用户可以预约某个时间之后再取件（例如“下午 3 点以后来取”）。系统要提前占住设备，并保证同一台设备的时间不冲突。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| D39 | 用户怎么指定时间 | `/quotes` 新增可选字段 `pickup_after`（ISO 时间）。空 = 现在。最多提前 7 天，必须在服务时间内（配置项）。 | 只加一个字段；与 `arrive_within_minutes` 可以同时用，截止时间从 pickup_after 起算。 |
| D40 | 一单占用设备多久 | 预测时长 = 去取件点 + 取件停留 + 送件 + 卸货 + 返航（第一轮已有的公式），再乘以安全系数 `1 + buffer-ratio`（默认 0.2），最少加 `min-buffer-minutes`（默认 5）。整段写进 `device_block`。 | 真实设备会慢于估算；预留余量，后面的预约才不会被拖累。 |
| D41 | 冲突怎么防 | 继续靠 `device_block_no_overlap` 排斥约束，数据库层面保证同一设备时间不重叠。 | V1 已有，不用改。 |
| D42 | 怎么找空闲设备 | 从“排在最后一单之后”改为**找空档**：在站点同类设备里，找最早能完整放下 [出发, 返航结束 + 余量] 的空档（可以插在两单之间）。仍然 `FOR UPDATE SKIP LOCKED`；插入时撞上排斥约束则换下一台设备。 | 有了预约以后，设备未来会有不连续的占用，只看最后一单会浪费空档、或把即时单排得太晚。 |
| D43 | 预约单在等待期间怎么显示 | 沿用 D17：显示 Scheduled 和出发倒计时，出发前都可以取消；取消时把 `device_block.released_at` 设为当前时间，空档可以被别人使用。 | 不新增状态，状态机不变。 |
| D44 | 前一单延误了怎么办 | 模拟器里不会延误。接真实设备后，前一单超出占用时，后台标红并提示运营方改派；自动改派放到以后。 | MVP 先保证可见，不做复杂调度。 |

数据和接口改动：

- `quote` 新增 `pickup_after timestamptz`（可空）。
- `quote_option`、`delivery_order` 不改：`departs_at` 本来就是计划出发时间，预约单只是这个时间在未来。
- `GET /orders` 不变，前端用 `departs_at > now()` 区分预约中的订单。
- 新增错误码 `PICKUP_TIME_INVALID`（过去的时间、超过 7 天、不在服务时间）。
- 配置：`skylane.booking: { max-days-ahead: 7, buffer-ratio: 0.2, min-buffer-minutes: 5, service-hours: "08:00-20:00" }`。

### 8.5 主页与整体 UI

- 新增公开主页 `/`：产品介绍、站点和服务范围地图、“开始寄件”按钮（未登录跳到 `/login?next=/ship`）。已登录用户访问 `/` 仍显示主页，导航里有 New Delivery 入口。
- `GET /stations` 对未登录用户开放（主页地图要用），其余接口仍需登录。
- 统一导航、配色和组件；补齐加载、空状态和错误提示；手机宽度可用。
- 不改接口和数据库。

### 8.6 数据库增量（V2__phase_two.sql + V3__phase_two_indexes.sql，已完成）

以 `BE/src/main/resources/db/migration/` 下的 `V2__phase_two.sql`（表和列）和 `V3__phase_two_indexes.sql`（索引）为准，内容如下：

```sql
-- ---------- 运营方与角色（8.1） ----------

CREATE TABLE operator (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE app_user ADD COLUMN role text NOT NULL DEFAULT 'customer'
    CHECK (role IN ('customer', 'operator', 'admin'));
ALTER TABLE app_user ADD COLUMN operator_id uuid REFERENCES operator (id);
-- operator 必须属于一家公司，其他角色不能有公司
ALTER TABLE app_user ADD CONSTRAINT app_user_operator_chk
    CHECK ((role = 'operator') = (operator_id IS NOT NULL));

ALTER TABLE device ADD COLUMN operator_id uuid REFERENCES operator (id);  -- 空 = 平台自营
CREATE INDEX device_operator_idx ON device (operator_id);

-- ---------- 预约下单（8.4） ----------

ALTER TABLE quote ADD COLUMN pickup_after timestamptz;                   -- 空 = 现在

-- ---------- 推荐与 Agent（8.2） ----------

CREATE TABLE recommendation (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    quote_id   uuid        NOT NULL UNIQUE REFERENCES quote (id),
    option_id  uuid        NOT NULL,
    reason     text        NOT NULL,
    factors    jsonb       NOT NULL,
    model      text,                                                     -- 空 = 只用了规则
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT recommendation_option_fk
        FOREIGN KEY (quote_id, option_id) REFERENCES quote_option (quote_id, id)
);

CREATE TABLE conversation (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES app_user (id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX conversation_user_idx ON conversation (user_id, created_at DESC);

CREATE TABLE conversation_message (
    id              bigserial   PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES conversation (id),
    role            text        NOT NULL CHECK (role IN ('user', 'assistant', 'tool')),
    content         jsonb       NOT NULL,
    input_tokens    integer,
    output_tokens   integer,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX conversation_message_conv_idx ON conversation_message (conversation_id, id);

CREATE TABLE agent_action (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid        NOT NULL REFERENCES conversation (id),
    kind            text        NOT NULL CHECK (kind IN ('place_order', 'cancel_order')),
    payload         jsonb       NOT NULL,
    status          text        NOT NULL DEFAULT 'pending'
                                CHECK (status IN ('pending', 'confirmed', 'rejected', 'expired', 'failed')),
    result          jsonb,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_action_conv_idx ON agent_action (conversation_id, created_at);

-- ---------- V3：第二阶段查询用的索引 ----------

-- 运营方后台按设备查订单（/ops/orders、/ops/metrics，D29）
CREATE INDEX order_device_created_idx ON delivery_order (device_id, created_at DESC);
-- 后台指标按时间范围统计
CREATE INDEX order_created_idx ON delivery_order (created_at);
-- 按时间顺序找设备空档（D42）、设备排班页（/ops/devices/{id}/schedule）
CREATE INDEX device_block_active_idx ON device_block (device_id, starts_at) WHERE released_at IS NULL;
-- 定时把过期的待确认操作标成 expired（D36）
CREATE INDEX agent_action_pending_idx ON agent_action (expires_at) WHERE status = 'pending';
```

V3 的 4 个索引：`delivery_order (device_id, created_at DESC)`、`delivery_order (created_at)`、`device_block (device_id, starts_at) WHERE released_at IS NULL`、`agent_action (expires_at) WHERE status = 'pending'`，分别用于运营方查订单、指标统计、找设备空档、过期待确认操作。

V1 的 11 张表都保留，第二阶段新增 5 张表（operator、recommendation、conversation、conversation_message、agent_action），给 3 张已有的表加列（app_user、device、quote）。
