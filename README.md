# SkyLane

Same-city delivery in San Francisco by **ground robot or drone**. Customers enter pickup and drop-off,
compare 6 delivery plans on a map, book one, and watch the device move live until delivery. A
**DeepSeek-powered assistant** can quote and place orders through tool calls, but every order or
cancellation waits for the user to confirm it on an action card.

**Live demo:** https://skylane.hanyuuu.com &nbsp;·&nbsp; MIT licensed

| Frontend | Backend | Data | AI |
|---|---|---|---|
| Next.js 14, TypeScript, TanStack Query, MapLibre GL JS, Tailwind | Java 21, Spring Boot 3.3, Spring Security (JWT), Spring Data JPA, Flyway | PostgreSQL 16 + PostGIS | DeepSeek (Anthropic-compatible Messages API), tool calling over SSE |

## What it does

1. **Ship:** enter item weight, pickup and drop-off (Photon address autocomplete), recipient, and an
   "arrive within" window.
2. **Compare:** 3 stations × 2 transport modes = **6 plans**, labelled *fastest / cheapest /
   recommended*. Unavailable plans say why (`OVERWEIGHT`, `OUT_OF_RANGE`, `STEEP_TERRAIN`, `DEADLINE_MISSED`...). Robots follow real street
   routes; drones fly a straight arc.
3. **Book:** the order is assigned a free device. A row lock plus a database constraint guarantee
   that two customers racing for the last free robot cannot both get it.
4. **Track:** a device simulator streams `dispatched → picked_up → in_transit → arrived → completed`
   over **Server-Sent Events**, with replay after a reconnect.
5. **Review** the delivery, browse **My Orders**, or just ask the **assistant**.

Accounts have roles (customer / operator / admin). Role checks run on every request, so a role change
applies immediately; operator and admin routes return 403 to customers, and operators can manage
their devices through the API (`/api/v1/ops/devices`). The operator dashboard UI is next on the roadmap.

## The AI assistant: tool calling with human confirmation

`/assistant` is a chat over SSE backed by a server-side tool loop
([`BE/.../agent/`](BE/src/main/java/com/skylane/agent), full write-up in [`docs/agent.md`](docs/agent.md)).

```
user message ──► DeepSeek ──► tool_use ──► AgentTools (as the logged-in user) ──► tool_result ──┐
                    ▲                                                                           │
                    └───────────────────────── next round (bounded) ◄───────────────────────────┘
                    │
                    └─► text deltas + action cards ──SSE──► browser ──► Confirm / Reject
```

- **Tools** ([`agent-tools.json`](BE/src/main/resources/agent-tools.json)): `search_address`,
  `create_quote`, `recommend_delivery`, `get_order`, `list_orders`, `propose_order`, `cancel_order`.
- **The model cannot act on its own.** `propose_order` / `cancel_order` only create a *pending*
  `agent_action` card. Typing "confirm" in chat does not execute anything; only the card's button does.
- **Confirming is transactional and idempotent.** The confirm endpoint checks ownership, locks the
  action row, checks status and expiry, and calls the normal `OrderService` with the action id as the
  idempotency key. Of concurrent confirms, at most one succeeds; the rest get `409 ACTION_NOT_PENDING`.
  Pending actions expire after 10 minutes.
- **Safe streaming.** A tool runs only after the upstream stream reaches `message_stop` with complete
  tool-input JSON. Truncated or invalid frames never execute a partial tool call.
- **Guardrails.** Max 16 tool calls per user request, per-user daily message and token quotas
  (enforced with a row lock), 4,000-character messages, one active reply per conversation. The model
  cannot switch users, call unregistered tools, or fetch arbitrary URLs. The API key stays on the
  server.
- **Grounded answers.** For "show my orders" requests the backend runs `list_orders` before the model
  replies and passes the real result in, so the assistant can't claim there are no orders.
- **Persistence.** User messages, `tool_use`, `tool_result` and replies are stored per conversation,
  so a refresh restores the chat; failed turns are kept out of later model context.

Plan recommendations use the same split: rules pick the plan and all numbers, and the LLM only writes
the one-line reason, falling back to rule text if the call fails ([`docs/recommendation.md`](docs/recommendation.md)).

## Testing

- **Backend:** 77 selected regression tests pass, including 26 agent tests (user isolation, quotas,
  multi-round tool loop, streaming delta parsing, six-way concurrent confirm, reject, expiry, rollback
  on failure, confirm/reject races) and 31 order placement/cancellation tests.
- **Frontend:** SSE client tests (UTF-8 split across chunks, CRLF across chunks, multi-line data,
  action cards, errors, early EOF), plus Next.js production build, TypeScript and lint.
- **End to end:** headless Chrome against the real backend and a throwaway PostGIS database, using a
  local DeepSeek-compatible mock so no paid API calls are made. It checks that a proposal is not
  executed, reject creates no order, confirm creates exactly one, history survives a refresh, and
  there is no horizontal overflow at phone width.

## Run it locally

Docker (PostgreSQL + PostGIS) → `BE`: `./gradlew bootRun` → `FE`: `npm run dev`.
Step by step, with seed accounts and the demo script: [`docs/run-local.md`](docs/run-local.md).
Flyway creates the schema (`V1`–`V3`) and seed data on first start. Set `DEEPSEEK_API_KEY` in
`BE/.env` to enable the assistant; everything else works without it.

## Repository

```
FE/     Next.js app (login, ship, plans, tracking, orders, account, assistant)
BE/     Spring Boot API, agent, simulator; docker-compose for PostGIS; Flyway migrations
docs/   design.md (source of truth), agent.md, recommendation.md, run-local.md,
        design/ (clickable frontend/backend/database diagrams), team.md (team handbook, Chinese)
```

Deployment runs from a mirror repository ([SkyLane-CI](https://github.com/JuanerW/SkyLane-CI)):
a push builds the images and ships them with Docker Compose via GitHub Actions.

## Team

Built by a student team, led by [@JuanerW](https://github.com/JuanerW). Work split,
review rules and progress notes (Chinese): [`docs/team.md`](docs/team.md).
