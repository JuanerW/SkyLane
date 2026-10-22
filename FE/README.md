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

## 约定

- 所有请求走 `lib/api.ts`，页面里只按错误体的 `code` 分支，不解析 `message`。
- 所有时间用 `lib/time.ts` 按美西时间（America/Los_Angeles）显示。
- 后端还没完成的接口，先对着 OpenAPI 的假数据开发。

## 环境变量（`.env.local`，不要提交）

| 变量 | 说明 |
|---|---|
| `NEXT_PUBLIC_API_BASE` | 后端地址，如 `http://localhost:8080/api/v1` |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | Google 登录（第二轮） |
