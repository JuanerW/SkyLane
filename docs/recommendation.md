# Rule recommendation (phase 2)

POST `/api/v1/quotes/{id}/recommendation` requires the logged-in owner of an unexpired quote. Returns 201 `{option_id, reason, factors}`. Existing quote responses retain their fields and add nullable `recommended_option_id`; new quote responses currently return null here, and the separate recommendation endpoint supplies the selected option_id. The plans page fetches the recommendation after quoting, replaces the recommended tag, and displays its reason. Recommendation failures never block selecting a plan or placing an order.

Only available quote options with price and departure/arrival timestamps participate. Existing quote validation enforces the deadline and physical constraints. Scores normalize price, arrival and queue time to 0–1; lower wins. Default weights: price .40, arrival .45, queue .15. Budget keywords (cheap, budget, lowest cost, 便宜, 省钱, 低价): .75/.15/.10. Speed keywords (urgent, fast, as soon, 尽快, 加急, 最快): .15/.75/.10. If both match, use balanced weights. Ties break by arrival, price, then option UUID.

Current wind at pickup comes from Open-Meteo, in m/s, with a three-second timeout. Wind >= 10 m/s adds .35 to drone scores. This affects ranking only and does not establish flight safety. Weather failure produces a rule result and an explicit reason/factors fallback. This release uses rule-written reasons; `model` is NULL and no Claude key is needed.

A transaction locks the owned quote, checks expiry, reads the cached recommendation, and inserts at most one result. The database unique quote_id constraint is the final guard. Weather and factors are a snapshot for the quote lifetime. Missing/other-user quotes return 404 `QUOTE_NOT_FOUND`, expired quotes return 409 `QUOTE_EXPIRED`, no available options return 409 `NO_AVAILABLE_OPTIONS`.

Configuration (application.yml):

```yaml
skylane:
  recommendation:
    weather-enabled: ${RECOMMENDATION_WEATHER_ENABLED:true}
    wind-threshold-mps: ${RECOMMENDATION_WIND_THRESHOLD_MPS:10}
```

Run:

1. The identical duplicate V1__0.sql has been backed up to zsh_docs/migration-backups/V1__0.duplicate.sql, outside Flyway scanning. V1__init.sql is unchanged.
2. Start Docker Desktop, then `docker compose up -d` in BE. Start the backend with `./gradlew.bat bootRun` (JDK 21).
3. Start FE with `npm ci` then `npm run dev`; use `NEXT_PUBLIC_USE_MOCK=0` for the real API. Mock mode displays a demo recommendation, not production scoring. The backend memory profile does not expose this database-backed endpoint.
4. Log in, quote Ferry Building -> Oracle Park, compare balanced notes against `cheap` or `urgent`. The chosen card displays its reason; selection still requires a click.
5. In Swagger, authorize with the login token and POST the same quote recommendation twice: responses should match, and `SELECT count(*) FROM recommendation WHERE quote_id = '<id>';` should return 1.
6. Call with another user's token (404), an expired quote (409), and an all-unavailable quote (409). Disable weather to check fallback. Restarting the backend preserves cached recommendations.

Tests: `./gradlew.bat test --tests 'com.skylane.recommendation.*'`. By default these cover scoring and MVC contract/error handling. To include RecommendationDatabaseTest, set SKYLANE_TEST_DB=1 and DATABASE_URL to a disposable PostGIS database before running the command. The database suite creates quotes and changes their expiry/availability; do not point it at a shared or production database.

## Real database validation

Completed against a fresh, isolated PostGIS 16 database in local Docker:

- 15 recommendation tests passed, none skipped: 5 scoring, 3 MVC, 7 real database integration tests.
- V1, V2, V3 migrations and JPA schema validation succeeded. Development seeds created 3 stations and 15 devices.
- Recommendation persisted with model NULL, and repeat requests returned the same result with exactly one row per quote. A fresh service instance also loaded the stored result.
- Another user received 404 even when a cached recommendation existed; unauthenticated requests received 401. Expired quotes received 409 QUOTE_EXPIRED; all-unavailable quotes received 409 NO_AVAILABLE_OPTIONS.
- Six simultaneous first-time service requests selected the same option and wrote one cache row.
- A standalone backend on port 18081 also passed real HTTP login -> quote -> six concurrent recommendation requests -> cached repeat. HTTP 401/404 checks passed. Restarting the backend validated all three migrations and skipped existing seed data.

The integration suite stubs external routing and wind; the real HTTP smoke run disabled wind to exercise fallback. These results validate the application/database integration, not external-provider availability or browser rendering. The temporary server and isolated test database were removed after verification; the developer's skylane database was not modified.