# DeepSeek Assistant：会话、工具与操作确认

## 本地运行

1. 启动 Docker Desktop，在 BE 运行 docker compose up -d。
2. 在 BE/.env 填写 DEEPSEEK_API_KEY，启动 .\gradlew.bat bootRun。后端已配置自动读取 .env，从仓库根目录或 BE 目录运行均可。首次配置可复制 BE/.env.example；.env 已被 Git 忽略。也可以使用 IDE/shell 环境变量设置 Key。
3. 前端设置 NEXT_PUBLIC_USE_MOCK=0，NEXT_PUBLIC_API_BASE=http://localhost:8080/api/v1，再运行 npm run dev。
4. 登录后打开 http://localhost:3000/assistant，或点击导航 Assistant。可输入寄件需求、查询订单；收件人和地址不完整时助手应追问。

模型 deepseek-flash、服务地址 https://api.deepseek.com/anthropic 以及消息/token 限额直接配置在 BE/src/main/resources/application.yml，可以提交到 GitHub。需要更换模型或调整限额时修改该文件；.env 只保存 DEEPSEEK_API_KEY。Key 只放在后端，前端没有模型密钥。当前请求显式设置 thinking.type=disabled，使用 DeepSeek 官方 Anthropic 兼容 Messages API，保留原工具协议和历史格式。

未配置 Key 时会话创建/读取和已有操作确认仍可使用；发送消息返回 503 AGENT_NOT_CONFIGURED。推荐接口仍使用规则生成理由，不额外调用模型。

## API 与 SSE

以下路径均带 /api/v1 前缀，要求 Bearer 登录令牌：

| 方法 | 路径 | 返回 |
|---|---|---|
| POST | /agent/conversations | 201 {conversation_id} |
| GET | /agent/conversations/{id} | messages、actions（包含已执行结果） |
| POST | /agent/conversations/{id}/messages | 请求 {text}，响应 SSE |
| POST | /agent/actions/{id}/confirm | 200 {action_id,kind,status,result} |
| POST | /agent/actions/{id}/reject | 200 {action_id,kind,status,result:null} |

SSE 使用带 Authorization 的 fetch POST，不使用 EventSource，不自动重试消息。事件 delta{text} 持续显示 DeepSeek 的文本增量，action{action_id,kind,payload,status,expires_at,result} 显示待确认卡片，done{turn_id,success} 结束回复。流内失败为 error{error:{code,message,details}}；已经建立的流保留 HTTP 200。权限、参数、配置、首次配额错误在建立流前返回普通 JSON/HTTP 错误。每 15 秒发送注释心跳。

上游流必须收到 message_stop，工具输入 JSON 必须完整，才会交给工具执行；提前 EOF、截断和无效帧均不能执行部分工具调用。用户点击 Stop reply 会关闭客户端连接；正在进行的上游调用最多等待配置超时，已产生的待确认卡片仍可在历史中找到。断线不自动重新发送用户消息。

## 确认与拒绝

助手只能执行 search_address、create_quote、get_order、list_orders、propose_order、cancel_order 六个工具。下单/取消工具仅创建 pending 卡片，不直接执行操作。聊天文字里的“确认”也不会绕过卡片按钮。

确认接口按当前用户校验会话归属，锁定 agent_action 行，检查 pending 状态和有效期，再调用原有 OrderService。下单的 Idempotency-Key 使用 action id。订单写入和 action=result/confirmed 在同一事务内提交。重复或并发确认最多一次成功，其余返回 409 ACTION_NOT_PENDING；原结果可从会话历史读取。

拒绝仅将 pending 操作标为 rejected。已决定或过期均返回 409 ACTION_NOT_PENDING；其他用户访问返回 404 ACTION_NOT_FOUND。业务校验失败时订单事务回滚，操作另行标为 failed，前端要求重新请求建议。取消确认仍重新检查出发时间，并释放订单的 device_block。过期 pending 操作每 30 秒标记 expired。前端也检查过期时间，但服务器决定最终有效性。

卡片显示站点/车辆、报价、出发/到达时间、地址和收件人。确认下单后可跳订单追踪页。卡片复用 PlanCard 的只读展示，只有独立确认按钮会调用执行接口。

## 会话与限额

会话 ID 按用户保存在本地，刷新后读取服务器历史；不在前端保存模型 Key。模型上下文会包含最新操作状态快照，避免把已确认订单仍当成待确认卡片。

用户消息、assistant tool_use、tool_result 和最终文字均落库；失败轮次不加入后续模型上下文。GET 返回最近 500 条消息/轮次记录与最近 100 个操作；content.kind=message 的 blocks 保存实际文字和工具块，kind=turn 保存处理状态。UI 隐藏原始工具数据。

同一会话一个活动回复，重复请求返回 CONVERSATION_BUSY；数据库保存十分钟故障租约，每实例最多 8 个活动回复。按 UTC 日计算每用户共享的消息/token 限额，数据库用户行锁防止并发绕过。模型调用前保守预留 token，成功按报告用量结算，失败且用量未知时保留预留值，接近限额时可能提前拒绝短消息。

| application.yml 中 skylane.agent 配置项 | 当前值 |
|---|---|
| daily-messages | 100 |
| daily-tokens | 100000 |
| max-output-tokens | 1024 |
| max-rounds | 5 |
| action-ttl-minutes | 10 |

单条消息最多 4000 字符；上下文和工具定义最多 32000 UTF-8 字节；每次用户请求最多 16 次工具调用。模型不能切换 user_id、使用未注册工具或指定任意外部 URL。业务权限始终使用已登录身份。

## 验证方式

BE 普通客户端协议测试：
~~~powershell
.\gradlew.bat test --tests "com.skylane.agent.DeepSeekAgentModelTest" --tests "com.skylane.agent.DeepSeekStreamingTest"
~~~

真实数据库测试要求 DATABASE_URL 指向可丢弃 PostGIS 库，并设置 SKYLANE_TEST_DB=1：
~~~powershell
.\gradlew.bat test --tests "com.skylane.agent.*"
~~~

测试会创建账号、报价和订单，并修改临时库的演示订单，因此不能使用开发库或共享库。

FE：
~~~powershell
npm run test:agent
npm run build
~~~

SSE 客户端测试覆盖按字节拆分 UTF-8、跨块 CRLF、多行数据、操作卡片、错误、提前 EOF 和终止清理。后端测试覆盖用户隔离、配额、失败恢复、多轮工具循环、DeepSeek 增量解析、六路并发确认、拒绝、过期、失败回滚、取消释放占用及确认/拒绝竞争。

真实 HTTP/PostGIS 与无头 Chrome 端到端测试使用本地 DeepSeek 兼容模拟服务，不消耗真实模型额度。真实 DeepSeek API 和 Photon 在线可用性仍需配置 Key 后单独验收。浏览器验证包含：建议不执行、拒绝无订单、确认一单、刷新恢复、后续聊天、手机无横向溢出。

官方接口说明：https://api-docs.deepseek.com/guides/anthropic_api/

## 本次验证结果

后端选定回归共 77 项通过、0 失败、0 跳过：Agent 26 项，下单/取消 31 项，推荐与内存报价 20 项。前端 SSE 解析 7 项通过，Next.js 生产构建、TypeScript 和 Lint 检查通过。无头 Chrome 验证了真实前后端和临时 PostGIS 上的建议不执行、拒绝无订单、确认一单、刷新恢复、后续聊天及手机无横向溢出，页面异常为 0。

上游模型使用本地 DeepSeek 兼容 SSE 服务，未调用真实付费 API。测试端口和临时数据库 skylane_agent_finish_it_20261109 已清理，开发库未改动。

## 订单列表事实校验修复

对 Show my recent orders、查看我的订单等明确订单列表请求，后端在模型生成前以当前用户身份执行 list_orders，将实际结果保存为 tool_use/tool_result 并传入模型。历史对话中错误的空列表回答不能替代本轮查询。工具失败表示查询失败，不能解释成没有订单。此预查询仅涉及读取，不会创建或取消订单。

修复后 Agent 相关 29 项测试通过。另用临时库的合成账号和一笔订单完成一次真实 DeepSeek 请求：回复正确识别订单，数据库保存了真实工具结果。未修改开发库中的用户订单。应用正在运行时需要重启后端才能加载此修复。
