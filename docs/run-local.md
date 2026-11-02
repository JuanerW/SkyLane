# 本地运行 Demo

按顺序做完下面 4 步，就能在本机走完 README 里的 7 步 Demo 脚本。

## 0. 需要安装的软件

| 软件 | 版本 | 用途 | 检查命令 |
|---|---|---|---|
| Docker Desktop | 最新版 | 跑 PostgreSQL 16 + PostGIS | `docker --version` |
| JDK | 21 | 跑后端（Gradle 会自动下载，不用单独装 Gradle） | `java -version` |
| Node.js | 20 LTS | 跑前端 | `node -v` |
| Git | 任意 | 拉代码 | `git --version` |

Windows 用户：Docker Desktop 需要开启 WSL 2，安装后重启一次。

## 1. 启动数据库

```bash
cd BE
docker compose up -d
docker ps                  # 能看到 skylane-db，状态 Up
```

- 地址 `localhost:5432`，库名、用户名、密码都是 `skylane`。
- **不用手动建表**：后端启动时 Flyway 会自动执行 `src/main/resources/db/migration/V1__init.sql`。
- 想清空数据重来：`docker compose down -v` 后再 `docker compose up -d`（`-v` 会删掉数据卷）。

## 2. 启动后端

```bash
cd BE
# macOS / Linux / Git Bash
./gradlew bootRun --args="--skylane.sim.speed=10"
# Windows PowerShell
.\gradlew.bat bootRun --args="--skylane.sim.speed=10"
```

看到下面三行日志就是启动成功了：

```
Started SkyLaneApplication in ... seconds
Development seed data ready: 3 new stations, 15 new devices
Development users ready: 2 new (password 'password123')
```

- `--skylane.sim.speed=10` 让模拟器用 10 倍速，演示时一单很快跑完。不加就是真实速度。
- 默认 profile 是 `dev`，启动时会自动写入种子数据（见下表），重复启动不会重复写入。
- 接口文档：http://localhost:8080/swagger-ui.html

**建议设置的环境变量**（不设也能跑）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `JWT_SECRET` | 空 | 不设的话每次启动用随机密钥，**重启后端后所有人都要重新登录**。本地可以设成任意长字符串 |
| `FRONTEND_ORIGIN` | `http://localhost:3000` | 前端地址，跨域白名单 |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/skylane` | 数据库连接 |

```bash
# Git Bash 示例
JWT_SECRET=any-long-random-string-for-local-dev ./gradlew bootRun --args="--skylane.sim.speed=10"
```

### 种子数据

| 内容 | 说明 |
|---|---|
| 站点 | SoMa、Mission、Marina 三个站 |
| 设备 | 每站 3 台机器人 + 2 架无人机，共 15 台 |
| 测试账号 | `alice@skylane.dev`、`bob@skylane.dev`，密码都是 `password123` |
| 历史订单 | 每个测试账号 3 笔已完成订单，其中 1 笔已评价（Demo 第 7 步用） |

## 3. 启动前端

```bash
cd FE
npm ci                         # 第一次运行或 package-lock.json 变了时
cp .env.example .env.local     # Windows PowerShell: copy .env.example .env.local
npm run dev
```

打开 http://localhost:3000 ，会跳到登录页。

`.env.local` 里 `NEXT_PUBLIC_USE_MOCK=0` 表示连真实后端；改成 `1` 则用 `src/lib/mock.ts` 的假数据，不需要后端（只用于单独开发前端页面）。

## 4. 走一遍 Demo

| # | 操作 | 说明 |
|---|---|---|
| 1 | 注册新账号 | 登录页点 "Create an account"，密码至少 8 位 |
| 2 | 填写寄件信息 | 推荐地址：Pickup 输入 `Ferry Building`，Delivery 输入 `Oracle Park`，从补全里选；重量 ≤ 2 kg 时无人机方案可用；电话按 `(415) 555-0123` 这样写即可 |
| 3 | Find Delivery Options | 6 个方案，机器人有真实街道路线 |
| 4 | 选方案，Place order | 跳到追踪页 |
| 5 | 等待 | 10 倍速下 SoMa 无人机约 1 分钟完成；机器人要几分钟 |
| 6 | 提交评价 | 状态到 completed 后出现评价表单 |
| 7 | My Orders | 新订单在最上面。想看历史订单，用 `alice@skylane.dev` / `password123` 登录 |

## 常见问题

| 现象 | 原因和处理 |
|---|---|
| 后端启动报 `Connection refused` | 数据库没起来，先做第 1 步，`docker ps` 确认 |
| 后端启动报 `extension "postgis" is not available` | 用的不是 `postgis/postgis` 镜像，用 `BE/docker-compose.yml` |
| 前端所有请求都失败，浏览器控制台有 CORS 错误 | 前端不是跑在 `http://localhost:3000`，把实际地址设到后端的 `FRONTEND_ORIGIN` |
| 重启后端后被踢回登录页 | 没设 `JWT_SECRET`，见第 2 步 |
| 报价时卡几秒 | 机器人路线要请求 routing.openstreetmap.de；连不上会在 3 秒后退回直线路线，不影响下单 |
| 下单后马上点取消提示已出发 | 设备空闲时立即出发，出发后不能取消（design.md D11）。排队中的订单才能取消 |
| 端口被占用 | 5432 / 8080 / 3000 已被别的程序占用，关掉它，或改 `docker-compose.yml` 的端口和对应配置 |

## 运行测试

```bash
cd BE
./gradlew test                                  # 不需要数据库的测试
SKYLANE_TEST_DB=1 ./gradlew test                # 连本地数据库的接口测试（需要先做第 1 步）
```
