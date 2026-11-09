# SkyLane 前端（FE）

Next.js 14（App Router）+ TypeScript。完整设计见 [`docs/design.md`](../docs/design.md) 第 3 节，可点击的页面结构图见 [`docs/design/frontend.html`](../docs/design/frontend.html)。

## 目录（由 Zhiyuan 创建项目后按此组织）

```
FE/src/
├─ app/(auth)/login/page.tsx
├─ app/(app)/layout.tsx              # AppShell：顶部导航 + 登录守卫
├─ app/(app)/ship/page.tsx           # 填写寄件信息
├─ app/(app)/ship/plans/page.tsx     # 比较方案并下单
├─ app/(app)/orders/page.tsx         # My Orders
├─ app/(app)/orders/[id]/track/page.tsx
├─ app/(app)/account/page.tsx        # 第二轮
├─ components/{ui,shell,ship,plans,tracking,orders,account,map}/
├─ hooks/
├─ lib/{api,sse,photon,time}.ts
└─ types/api.ts                      # 从后端 OpenAPI 生成，不手改
```

创建项目：

```bash
cd FE
npx create-next-app@14 . --ts --tailwind --app --src-dir --eslint
```

## 分工（第一轮）

| 成员 | 负责 |
|---|---|
| Zhiyuan Wang | 项目骨架；`lib/api.ts`、`lib/sse.ts`、`lib/time.ts`；类型生成；AppShell 和登录守卫；`/orders`；`/orders/[id]/track`（状态时间线、评价）；前端协调。第二轮：`/account` |
| Pei Li | `/ship`（表单、Photon 地址补全、寄件草稿）；`/ship/plans`（方案卡片、下单）；地图组件 `components/map/`（站点、路线、机器人沿路移动、无人机弧线），追踪页复用 |
| Yuning Cao（后端，兼做） | `/login` 页面和登录状态保存（账号模块前后端一人负责） |

## 本地运行

```bash
cd FE
npm ci
cp .env.example .env.local      # 默认 NEXT_PUBLIC_USE_MOCK=0，连本地后端；改成 1 用假数据
npm run dev                     # http://localhost:3000
```

### 假数据（占位，联调后删除）

后端接口还没完成时，`NEXT_PUBLIC_USE_MOCK=1` 会让 `lib/api.ts` 和 `lib/sse.ts` 改用 `src/lib/mock.ts` 里的内存假后端：

- 只是占位，不是种子数据，刷新页面就重置；字段按 `docs/design.md` 第 4 节写。
- 第一笔订单是“实时”的，约 10 倍速跑完 confirmed → completed，用来开发追踪页。
- 页面不能直接 import `mock.ts`。接口好了以后把 `.env.local` 改成 `NEXT_PUBLIC_USE_MOCK=0` 就连真实后端。

### 类型生成

后端接好 springdoc 后运行 `npm run gen:types`，从 `/v3/api-docs` 生成 `src/types/api.ts`。在那之前，页面用的是按设计文档手写的 `src/types/contract.ts`；生成后在 `contract.ts` 里改成从 `api.ts` 再导出。

### 已完成 / 留给别人的接口

| 内容 | 状态 |
|---|---|
| `lib/api.ts`、`lib/sse.ts`、`lib/time.ts`、`lib/auth.ts` | 完成 |
| AppShell + 登录守卫 `app/(app)/layout.tsx` | 完成；顶部导航，手机宽度时导航换到第二行；Logo 链接到主页；没有 token 时跳 `/login?next=…` |
| 公开主页 `/` | 完成（区块 5）：产品介绍、站点和服务范围地图、开始寄件入口；未登录也能看，`GET /stations` 不带 token |
| 样式 | `app/ui.css` 是全站共用的组件类（`card`、`btn`、`ghost`、`inp`、`seg`、`tab` 等），配色与顶部导航、`/orders`、`/login` 一致（slate 灰白）；加载、空状态、错误提示用 `components/ui/States.tsx` |
| `/orders` 列表和详情抽屉 | 完成；搜索（订单号前 8 位或收件人）、状态筛选、按日期排序，手机宽度为卡片列表 |
| `/orders/[id]/track` 倒计时、取消、进度、时间线、评价 | 完成 |
| `/login` | 完成：邮箱登录 / 注册切换，成功后 `setToken(access_token)` 并跳到 `next` 参数 |
| `/ship`、`/ship/plans`（Pei） | 完成；视觉统一为全站的 slate 灰白风格，假数据模式下报价和下单走 `mock.ts`，地址补全调用真实的 Photon |
| 地图（Pei） | 完成；`components/map/`（MapLibre + OpenFreeMap），追踪页通过 `TrackingMap` 复用。瓦片服务连不上时退回纯色底图，路线和标记照常显示 |

## 约定

- 所有请求走 `lib/api.ts`，页面里只按错误体的 `code` 分支，不解析 `message`。
- 所有时间用 `lib/time.ts` 按美西时间（America/Los_Angeles）显示。
- 后端还没完成的接口，先对着 OpenAPI 的假数据开发。

## 环境变量（`.env.local`，不要提交）

| 变量 | 说明 |
|---|---|
| `NEXT_PUBLIC_API_BASE` | 后端地址，如 `http://localhost:8080/api/v1` |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | Google 登录按钮用的 Client ID（不是密钥，可共享）；不设置则登录页不显示 Google 按钮。需要和后端的 `GOOGLE_CLIENT_ID` 一致 |
