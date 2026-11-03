# Order API tests

`CreateOrderControllerTest` runs with the normal backend test suite and needs no database.

`CreateOrderIntegrationTest` is opt-in and requires a **dedicated disposable PostgreSQL 16 + PostGIS database**. It creates isolated users, stations, quotes and devices, and temporary triggers to verify rollback and conflict recovery. Do not run against a shared development or production database. Each test cleans up its fixtures; the simulator and dev seed runners are disabled for this test context.

PowerShell (replace the database URL with your disposable test database):

```powershell
$env:SKYLANE_ORDER_TEST_DB = '1'
$env:DATABASE_URL = 'jdbc:postgresql://localhost:15432/skylane_order_test'
.\gradlew.bat test --tests 'com.skylane.order.*'
Remove-Item Env:SKYLANE_ORDER_TEST_DB
Remove-Item Env:DATABASE_URL
```

The default credentials are `skylane` / `skylane`; use `DATABASE_USERNAME` and `DATABASE_PASSWORD` if needed. JDK 21 is required.

`GetOrderIntegrationTest` uses the same opt-in database. It covers ownership/404s, authentication, order snapshots, map coordinate order, nullable fields, cancellation boundaries, timeline ordering/isolation, saved reviews and cancellation details. Its concurrency test commits a status/event/review update between the main detail query and the remaining queries, verifying that one response uses one consistent snapshot and a later response sees the committed update. Most fixtures roll back automatically; the concurrency test explicitly cleans up its committed fixtures.

`CancelOrderIntegrationTest` uses committed isolated fixtures and cleans them up after each test. It verifies authentication/ownership, optional and normalized reasons, reason length, cancellation deadlines and statuses, repeated/concurrent cancellation, competition with departure, lock waits crossing the departure deadline, release of both reservations without moving later reservations, rollback on release failure, and status/event atomicity for simulator calls. It also checks that the cancellation notification is published only after the complete transaction is visible to another connection. Temporary database triggers inject failures; the dedicated test database user must be able to create functions/triggers and inspect its own sessions in `pg_stat_activity`.

`ReviewOrderControllerTest` needs no database. It checks valid requests, integer-only rating input, required/range validation and malformed/overlong comments. `ReviewOrderIntegrationTest` uses the same opt-in disposable database and cleans up committed fixtures. It covers authentication/ownership/404s, all non-completed states, ratings 1–5, optional/normalized comments and the 1000-character boundary, service validation, duplicate/concurrent reviews and visibility in both detail and list responses. Duplicate reviews use `ON CONFLICT (order_id) DO NOTHING` and return `ALREADY_REVIEWED` without overwriting the first review.

Coverage: HTTP request validation and snake_case fields, authentication, required idempotency key, successful snapshots and reservations, sequential and concurrent retries, quote ownership/expiry/option mismatch/availability, phone validation, stale departures, deadlines, locked/offline devices, exclusion-conflict retry and atomic rollback.

`ListOrdersIntegrationTest` uses the same opt-in database and rolls back each test's fixtures. It checks authentication, empty lists, user isolation, newest-first and deterministic tied-time ordering, snake_case fields and UTC times, review flags, default/explicit/clamped limits, and malformed-limit errors. Numeric limits retain the existing clamp to 1–100. The shared controller test also covers list parameter validation without a database.
