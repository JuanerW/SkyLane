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

## 5. 数据库

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

- **Smart Recommendation**：`POST /ai/recommendation`，结合风速（Open-Meteo）、排队情况和用户备注，由 Claude API 给出推荐理由；新增 recommendation 表。
- **Agent Mode**：`POST /agent/chat`（SSE）+ 确认接口，对话式下单；新增 conversation、conversation_message、agent_action 表。
- **其他**：支付和 Checkout、重置密码（需要邮件服务）、收件人免登录追踪链接、接真实设备（启用 location_event 存 GPS 轨迹）。
