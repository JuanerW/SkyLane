# SkyLane 后端（BE）

Java 21 + Spring Boot 3.3 + PostgreSQL 16 / PostGIS。完整设计见 [`docs/design.md`](../docs/design.md) 第 4、5 节。

## 目录

```
BE/
├─ docker-compose.yml               # 本地数据库（PostgreSQL 16 + PostGIS）
├─ database/
│  └─ migration/V1__init.sql        # 建表脚本（11 张表 + 全部约束），已在 PostGIS 16 上验证
└─ （Spring Boot 项目由 Shuhua 创建在这里）
```

创建 Spring Boot 项目后，把 `database/migration/` 下的脚本移到 `src/main/resources/db/migration/`，由 Flyway 在启动时执行，`spring.jpa.hibernate.ddl-auto` 设为 `validate`。

## 本地启动数据库

```bash
cd BE
docker compose up -d
# 连接串：jdbc:postgresql://localhost:5432/skylane  用户名 / 密码：skylane / skylane
```

还没接 Flyway 时，可以手动执行建表脚本：

```bash
docker exec -i skylane-db psql -U skylane -d skylane < database/migration/V1__init.sql
```

## 分工（第一轮）

| 成员 | 模块 | 包 | 接口 |
|---|---|---|---|
| Shuhua Zhang | 数据库与工程底座 | 根包、`repository/` | —（Entity、Repository、Flyway、DevRunner、统一错误体） |
| Yuning Cao | 账号与安全 | `user/`、`security/` | `POST /auth/register`、`POST /auth/login`、`GET /me` |
| Zhuonan Yu | 报价 | `quote/` | `GET /stations`、`POST /quotes` |
| Ziyue Wang | 订单 | `order/`、`review/` | `POST /orders`、`GET /orders`、`GET /orders/{id}`、`POST /orders/{id}/cancel`、`POST /orders/{id}/review` |
| Yangming Tang | 模拟与追踪 | `device/`、`tracking/` | `GET /orders/{id}/track`（SSE） |

所有接口前缀 `/api/v1`，错误体统一为 `{"error": {"code", "message", "details"}}`。

## 种子数据（DevRunner，仅 dev profile）

| 站点 code | 名称 | 纬度 | 经度 | 设备 |
|---|---|---|---|---|
| `soma` | SoMa Station | 37.7785 | −122.3950 | R-101 ~ R-103，DRN-101 ~ DRN-102 |
| `mission` | Mission Station | 37.7599 | −122.4148 | R-201 ~ R-203，DRN-201 ~ DRN-202 |
| `marina` | Marina Station | 37.8008 | −122.4368 | R-301 ~ R-303，DRN-301 ~ DRN-302 |

测试账号 2 个（如 `alice@skylane.dev`、`bob@skylane.dev`，密码 `password123`），每个账号 3 笔已完成订单，其中 1 笔已评价。

## 环境变量

| 变量 | 说明 |
|---|---|
| `JWT_SECRET` | JWT 签名密钥 |
| `GOOGLE_CLIENT_ID` | Google 登录（第二轮） |
| `FRONTEND_ORIGIN` | CORS 允许的前端地址，如 `http://localhost:3000` |
| `ORS_API_KEY` | OpenRouteService 免费 Key |
| `OSRM_URL` | 可选，自部署 OSRM 地址 |

密钥只放在本地 `.env` 或 IDE 运行配置里，不要提交到仓库。
