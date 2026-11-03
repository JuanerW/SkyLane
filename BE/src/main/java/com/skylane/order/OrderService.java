package com.skylane.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skylane.common.ApiException;
import com.skylane.geo.Geo;
import com.skylane.geo.LatLng;
import com.skylane.order.OrderDtos.CreateOrderRequest;
import com.skylane.order.OrderDtos.CreateOrderResponse;
import com.skylane.order.OrderDtos.DeviceRef;
import com.skylane.order.OrderDtos.Item;
import com.skylane.order.OrderDtos.OrderDetail;
import com.skylane.order.OrderDtos.OrderSummary;
import com.skylane.order.OrderDtos.Place;
import com.skylane.order.OrderDtos.Review;
import com.skylane.order.OrderDtos.StationRef;
import com.skylane.order.OrderDtos.TimelineEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("!memory")
public class OrderService {
    private static final String SUMMARY_COLUMNS = """
            o.id, o.status, o.device_type, o.price_cents, o.created_at, o.departs_at, o.eta_at, o.completed_at,
            o.pickup_text, o.dropoff_text, o.recipient_name,
            EXISTS (SELECT 1 FROM delivery_review r WHERE r.order_id = o.id) AS reviewed""";

    private record Device(UUID id, String code, Instant freeAt) {
    }

    private record QuotedOption(UUID userId, String deviceType, UUID stationId, boolean available,
                                Instant departsAt, Instant expiresAt, Instant deadlineAt, int priceCents,
                                String routePoints, double pickupLat, double pickupLng, double dropoffLat,
                                double dropoffLng) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OrderStateMachine stateMachine;
    private final Clock clock;
    private final double simSpeed;
    private final Duration staleTolerance;

    public OrderService(JdbcTemplate jdbc, ObjectMapper mapper, OrderStateMachine stateMachine, Clock clock,
                        @Value("${skylane.sim.speed:1.0}") double simSpeed,
                        @Value("${skylane.quote.stale-tolerance-minutes:5}") int staleToleranceMinutes) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.stateMachine = stateMachine;
        this.clock = clock;
        this.simSpeed = simSpeed;
        this.staleTolerance = Duration.ofMinutes(staleToleranceMinutes);
    }

    public double simSpeed() {
        return simSpeed;
    }

    /** design.md "下单（一个事务）". */
    @Transactional
    public CreateOrderResponse create(UUID userId, String idempotencyKey, CreateOrderRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Idempotency-Key header is required");
        }
        // Serialize retries of the same user's request until commit. A concurrent retry
        // then sees the committed order rather than racing the unique constraint.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, userId + ":" + idempotencyKey);
                statement.execute();
            }
            return null;
        });
        CreateOrderResponse existing = findByIdempotencyKey(userId, idempotencyKey);
        if (existing != null) {
            return existing;
        }
        UUID quoteId = parseId(req.quoteId(), "OPTION_NOT_FOUND");
        UUID optionId = parseId(req.optionId(), "OPTION_NOT_FOUND");
        String phone = normalizePhone(req.recipient().phone());

        List<QuotedOption> found = jdbc.query("""
                        SELECT q.user_id, o.device_type, o.station_id, o.available, o.departs_at, q.expires_at,
                               q.deadline_at, coalesce(o.price_cents, 0) AS price_cents, o.route_points::text AS route,
                               ST_Y(q.pickup_location::geometry) AS plat, ST_X(q.pickup_location::geometry) AS plng,
                               ST_Y(q.dropoff_location::geometry) AS dlat, ST_X(q.dropoff_location::geometry) AS dlng
                        FROM quote q JOIN quote_option o ON o.quote_id = q.id
                        WHERE q.id = ? AND o.id = ?""",
                (rs, i) -> new QuotedOption(rs.getObject("user_id", UUID.class), rs.getString("device_type"),
                        rs.getObject("station_id", UUID.class), rs.getBoolean("available"), instant(rs, "departs_at"),
                        instant(rs, "expires_at"), instant(rs, "deadline_at"), rs.getInt("price_cents"),
                        rs.getString("route"), rs.getDouble("plat"), rs.getDouble("plng"),
                        rs.getDouble("dlat"), rs.getDouble("dlng")),
                quoteId, optionId);
        if (found.isEmpty() || !found.get(0).userId().equals(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OPTION_NOT_FOUND", "This option does not exist");
        }
        QuotedOption opt = found.get(0);
        Instant now = clock.instant();
        if (!now.isBefore(opt.expiresAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "QUOTE_EXPIRED", "The quote has expired");
        }
        if (!opt.available() || opt.routePoints() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_UNAVAILABLE", "This option is not available");
        }

        // Rank candidates without locking the entire fleet. Lock one at a time below.
        List<Device> devices = jdbc.query("""
                        SELECT d.id, d.code,
                               (SELECT max(b.ends_at) FROM device_block b
                                WHERE b.device_id = d.id AND b.released_at IS NULL) AS free_at
                        FROM device d
                        WHERE d.station_id = ? AND d.device_type = ? AND d.status = 'active'
                        ORDER BY coalesce((SELECT max(b.ends_at) FROM device_block b
                            WHERE b.device_id = d.id AND b.released_at IS NULL), '-infinity'::timestamptz), d.code""",
                (rs, i) -> new Device(rs.getObject("id", UUID.class), rs.getString("code"), instant(rs, "free_at")),
                opt.stationId(), opt.deviceType());
        for (Device candidate : devices) {
            List<String> locked = jdbc.queryForList("""
                    SELECT code FROM device WHERE id = ? AND status = 'active'
                    FOR UPDATE SKIP LOCKED""", String.class, candidate.id());
            if (locked.isEmpty()) {
                continue;
            }
            // Read occupation again after acquiring the device lock: another order
            // may have committed since the candidate list was read.
            Instant freeAt = jdbc.queryForObject("""
                    SELECT max(ends_at) FROM device_block
                    WHERE device_id = ? AND released_at IS NULL""",
                    (rs, i) -> instant(rs, 1), candidate.id());
            Device device = new Device(candidate.id(), locked.get(0), freeAt);
            now = clock.instant();
            if (!now.isBefore(opt.expiresAt())) {
                throw new ApiException(HttpStatus.CONFLICT, "QUOTE_EXPIRED", "The quote has expired");
            }

            Instant departs = departure(device, now);
            if (opt.departsAt() != null && departs.isAfter(opt.departsAt().plus(staleTolerance))) {
                throw new ApiException(HttpStatus.CONFLICT, "QUOTE_STALE", "Departure moved; please refresh the options");
            }
            List<LatLng> route = parseRoute(opt.routePoints());
            LatLng pickup = new LatLng(opt.pickupLat(), opt.pickupLng());
            LatLng dropoff = new LatLng(opt.dropoffLat(), opt.dropoffLng());
            DeliveryPlan plan = new DeliveryPlan(route, pickup, opt.deviceType());
            Instant eta = departs.plusMillis(Math.round(plan.arrivedAt() / simSpeed * 1000));
            if (opt.deadlineAt() != null && eta.isAfter(opt.deadlineAt())) {
                throw new ApiException(HttpStatus.CONFLICT, "DEADLINE_MISSED", "Cannot arrive before the deadline");
            }
            // Return trip: straight line back to the station (robots x1.3 for streets).
            double returnM = Geo.haversineM(dropoff, route.get(0)) * ("robot".equals(opt.deviceType()) ? 1.3 : 1);
            double returnS = returnM / ("robot".equals(opt.deviceType()) ? DeliveryPlan.ROBOT_MPS : DeliveryPlan.DRONE_MPS);
            Instant returnEnds = eta.plusMillis(Math.max(1000, Math.round(returnS / simSpeed * 1000)));

            // PostgreSQL aborts a transaction after an exclusion violation. Roll back
            // this attempt to a savepoint before trying another device.
            java.sql.Savepoint savepoint = jdbc.execute(
                    (org.springframework.jdbc.core.ConnectionCallback<java.sql.Savepoint>) java.sql.Connection::setSavepoint);
            try {
                UUID orderId = jdbc.queryForObject("""
                                    INSERT INTO delivery_order (user_id, quote_id, option_id, device_id, device_type,
                                        recipient_name, recipient_phone, price_cents, notes,
                                        pickup_text, pickup_location, dropoff_text, dropoff_location,
                                        route_points, departs_at, eta_at, idempotency_key, confirmed_at)
                                    SELECT ?, q.id, o.id, ?, o.device_type, ?, ?, o.price_cents, q.notes,
                                           q.pickup_text, q.pickup_location, q.dropoff_text, q.dropoff_location,
                                           o.route_points, ?, ?, ?, ?
                                    FROM quote q JOIN quote_option o ON o.quote_id = q.id
                                    WHERE q.id = ? AND o.id = ?
                                    RETURNING id""",
                            UUID.class, userId, device.id(), req.recipient().name().trim(), phone,
                            Timestamp.from(departs), Timestamp.from(eta), idempotencyKey, Timestamp.from(now),
                            quoteId, optionId);
                jdbc.update("""
                        INSERT INTO order_item (order_id, description, is_fragile, weight_kg)
                        SELECT ?, item_description, is_fragile, weight_kg FROM quote WHERE id = ?""", orderId, quoteId);
                jdbc.update("""
                        INSERT INTO device_block (device_id, order_id, kind, starts_at, ends_at) VALUES
                            (?, ?, 'order', ?, ?), (?, ?, 'return', ?, ?)""",
                        device.id(), orderId, Timestamp.from(departs), Timestamp.from(eta),
                        device.id(), orderId, Timestamp.from(eta), Timestamp.from(returnEnds));
                jdbc.update("INSERT INTO order_event (order_id, status, occurred_at, actor_type, actor_id) VALUES (?, 'confirmed', ?, 'user', ?)",
                        orderId, Timestamp.from(now), userId);

                return new CreateOrderResponse(orderId.toString(), "confirmed",
                        new DeviceRef(device.code(), opt.deviceType()), opt.priceCents(), departs, eta);
            } catch (RuntimeException e) {
                // Any SQL error aborts the transaction; roll back to the savepoint first so the
                // release below and the caller's error response are not masked by "transaction aborted".
                jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                    connection.rollback(savepoint);
                    return null;
                });
                if (e instanceof DuplicateKeyException) {
                    // A concurrent retry with the same key won the race (D13).
                    throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "This order is already being placed");
                }
                if (!(e instanceof DataIntegrityViolationException) || !isDeviceOverlap(e)) {
                    throw e;
                }
            } finally {
                jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                    connection.releaseSavepoint(savepoint);
                    return null;
                });
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "DEVICE_UNAVAILABLE", "No device is available; please retry");
    }

    private static boolean isDeviceOverlap(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    public List<OrderSummary> list(UUID userId, int limit) {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM delivery_order o WHERE o.user_id = ? "
                        + "ORDER BY o.created_at DESC, o.id DESC LIMIT ?",
                (rs, i) -> summary(rs), userId, Math.max(1, Math.min(limit, 100)));
    }

    // Details, review and timeline must come from one database snapshot, even
    // when the simulator advances the order or a review arrives during this read.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderDetail get(UUID userId, UUID orderId) {
        List<OrderDetail> rows = jdbc.query("SELECT " + SUMMARY_COLUMNS + """
                        , o.recipient_phone, o.notes, o.route_points::text AS route, o.canceled_at, o.cancel_reason,
                          ST_Y(o.pickup_location::geometry) AS plat, ST_X(o.pickup_location::geometry) AS plng,
                          ST_Y(o.dropoff_location::geometry) AS dlat, ST_X(o.dropoff_location::geometry) AS dlng,
                          d.code AS device_code, s.code AS station_code, s.name AS station_name,
                          ST_Y(s.location::geometry) AS slat, ST_X(s.location::geometry) AS slng,
                          coalesce(qo.distance_m, 0) AS distance_m,
                          i.description, i.is_fragile, i.weight_kg,
                          (o.status = 'confirmed' AND o.departs_at > now()) AS cancellable
                        FROM delivery_order o
                        JOIN device d ON d.id = o.device_id
                        JOIN station s ON s.id = d.station_id
                        JOIN quote_option qo ON qo.id = o.option_id
                        LEFT JOIN order_item i ON i.order_id = o.id
                        WHERE o.id = ? AND o.user_id = ?""",
                (rs, i) -> {
                    OrderSummary s = summary(rs);
                    return new OrderDetail(s.orderId(), s.status(), s.deviceType(), s.priceCents(), s.createdAt(),
                            s.departsAt(), s.etaAt(), s.completedAt(), s.pickupText(), s.dropoffText(),
                            s.recipientName(), s.reviewed(),
                            new DeviceRef(rs.getString("device_code"), s.deviceType()),
                            new StationRef(rs.getString("station_code"), rs.getString("station_name"),
                                    rs.getDouble("slat"), rs.getDouble("slng")),
                            new Item(rs.getString("description"), rs.getBoolean("is_fragile"),
                                    rs.getBigDecimal("weight_kg") == null ? BigDecimal.ZERO : rs.getBigDecimal("weight_kg")),
                            rs.getString("recipient_phone"), rs.getString("notes"),
                            new Place(s.pickupText(), rs.getDouble("plat"), rs.getDouble("plng")),
                            new Place(s.dropoffText(), rs.getDouble("dlat"), rs.getDouble("dlng")),
                            rs.getInt("distance_m"), routeTuples(rs.getString("route")),
                            rs.getBoolean("cancellable"), instant(rs, "canceled_at"), rs.getString("cancel_reason"),
                            null, null);
                }, orderId, userId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        OrderDetail d = rows.get(0);
        List<Review> review = jdbc.query("SELECT rating, comment, created_at FROM delivery_review WHERE order_id = ?",
                (rs, i) -> new Review(rs.getInt("rating"), rs.getString("comment"), instant(rs, "created_at")), orderId);
        return new OrderDetail(d.orderId(), d.status(), d.deviceType(), d.priceCents(), d.createdAt(), d.departsAt(),
                d.etaAt(), d.completedAt(), d.pickupText(), d.dropoffText(), d.recipientName(), d.reviewed(),
                d.device(), d.station(), d.item(), d.recipientPhone(), d.notes(), d.pickup(), d.dropoff(),
                d.distanceM(), d.routePoints(), d.cancellable(), d.canceledAt(), d.cancelReason(),
                timeline(orderId, 0), review.isEmpty() ? null : review.get(0));
    }

    public List<TimelineEntry> timeline(UUID orderId, long afterEventId) {
        return jdbc.query("""
                        SELECT event_id, status, occurred_at FROM order_event
                        WHERE order_id = ? AND event_id > ? ORDER BY event_id""",
                (rs, i) -> new TimelineEntry(rs.getLong("event_id"), rs.getString("status"), instant(rs, "occurred_at")),
                orderId, afterEventId);
    }

    /** 404 unless the order exists and belongs to the user. Returns its current status. */
    public String requireOwned(UUID userId, UUID orderId) {
        List<String> s = jdbc.queryForList("SELECT status FROM delivery_order WHERE id = ? AND user_id = ?",
                String.class, orderId, userId);
        if (s.isEmpty()) {
            throw notFound();
        }
        return s.get(0);
    }

    /** D11: only before departure; the WHERE clause decides races with the simulator. */
    @Transactional
    public Map<String, String> cancel(UUID userId, UUID orderId, String reason) {
        // Acquire the row before checking the deadline. PostgreSQL now() is fixed
        // at transaction start, so a lock wait must not extend the cancel window.
        List<String> owned = jdbc.queryForList("""
                SELECT status FROM delivery_order WHERE id = ? AND user_id = ? FOR UPDATE""",
                String.class, orderId, userId);
        if (owned.isEmpty()) {
            throw notFound();
        }
        boolean ok = stateMachine.transition(orderId, "confirmed", "canceled", clock.instant(), "user", userId,
                " AND departs_at > clock_timestamp()");
        if (!ok) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_CANCELLABLE", "The device has already departed");
        }
        String normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
        jdbc.update("UPDATE delivery_order SET cancel_reason = ? WHERE id = ?", normalizedReason, orderId);
        jdbc.update("UPDATE device_block SET released_at = now() WHERE order_id = ? AND released_at IS NULL", orderId);
        return Map.of("status", "canceled");
    }

    @Transactional
    public Review review(UUID userId, UUID orderId, int rating, String comment) {
        if (rating < 1 || rating > 5 || (comment != null && comment.length() > 1000)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "rating must be 1-5 and comment must be at most 1000 characters");
        }
        if (!"completed".equals(requireOwned(userId, orderId))) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_COMPLETED", "Only completed orders can be reviewed");
        }
        String text = comment == null || comment.isBlank() ? null : comment.trim();
        // A conflict is an expected business outcome, not a failed PostgreSQL
        // transaction. The unique order_id constraint also resolves concurrent reviews.
        List<Instant> created = jdbc.query("""
                INSERT INTO delivery_review (order_id, user_id, rating, comment) VALUES (?, ?, ?, ?)
                ON CONFLICT (order_id) DO NOTHING RETURNING created_at""",
                (rs, i) -> instant(rs, "created_at"), orderId, userId, rating, text);
        if (created.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "This order has already been reviewed");
        }
        return new Review(rating, text, created.get(0));
    }

    public List<LatLng> parseRoute(String json) {
        try {
            double[][] pts = mapper.readValue(json, double[][].class);
            return Arrays.stream(pts).map(p -> new LatLng(p[0], p[1])).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Bad route_points", e);
        }
    }

    private List<double[]> routeTuples(String json) {
        return parseRoute(json).stream().map(p -> new double[]{p.lat(), p.lng()}).toList();
    }

    private CreateOrderResponse findByIdempotencyKey(UUID userId, String key) {
        List<CreateOrderResponse> rows = jdbc.query("""
                        SELECT o.id, o.status, o.device_type, o.price_cents, o.departs_at, o.eta_at, d.code
                        FROM delivery_order o JOIN device d ON d.id = o.device_id
                        WHERE o.user_id = ? AND o.idempotency_key = ?""",
                (rs, i) -> new CreateOrderResponse(rs.getString("id"), rs.getString("status"),
                        new DeviceRef(rs.getString("code"), rs.getString("device_type")), rs.getInt("price_cents"),
                        instant(rs, "departs_at"), instant(rs, "eta_at")),
                userId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant departure(Device d, Instant now) {
        return d.freeAt() == null || d.freeAt().isBefore(now) ? now : d.freeAt();
    }

    /** The DB stores E.164. Accept what people type: "(415) 555-0100" becomes +14155550100. */
    static String normalizePhone(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        String e164 = raw.trim().startsWith("+") ? "+" + digits : digits.length() == 10 ? "+1" + digits : "+" + digits;
        if (!e164.matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "recipient.phone is not a valid phone number");
        }
        return e164;
    }

    private static UUID parseId(String raw, String code) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, code, "Not found");
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
    }

    private static OrderSummary summary(ResultSet rs) throws SQLException {
        return new OrderSummary(rs.getString("id"), rs.getString("status"), rs.getString("device_type"),
                rs.getInt("price_cents"), instant(rs, "created_at"), instant(rs, "departs_at"), instant(rs, "eta_at"),
                instant(rs, "completed_at"), rs.getString("pickup_text"), rs.getString("dropoff_text"),
                rs.getString("recipient_name"), rs.getBoolean("reviewed"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
