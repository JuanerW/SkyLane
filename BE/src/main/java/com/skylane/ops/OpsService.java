package com.skylane.ops;

import com.skylane.auth.JwtAuthFilter.Actor;
import com.skylane.common.ApiException;
import com.skylane.geo.LatLng;
import com.skylane.ops.OpsDtos.Booking;
import com.skylane.ops.OpsDtos.CreateDevice;
import com.skylane.ops.OpsDtos.DeviceRow;
import com.skylane.ops.OpsDtos.LiveDevice;
import com.skylane.ops.OpsDtos.Metrics;
import com.skylane.ops.OpsDtos.OpsOrder;
import com.skylane.ops.OpsDtos.Overview;
import com.skylane.ops.OpsDtos.PatchDevice;
import com.skylane.ops.OpsDtos.StationCount;
import com.skylane.order.DeliveryPlan;
import com.skylane.tracking.TrackingService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Operator back office, design 8.1 (D29-D32). Operators see only devices with their operator_id and the orders
 * those devices carry; admins see everything. Device state and position are derived from device_block (D31).
 */
@Service
@Profile("!memory")
public class OpsService {
    private static final ZoneId SF = ZoneId.of("America/Los_Angeles");
    private static final Set<String> ORDER_STATUSES =
            Set.of("confirmed", "dispatched", "picked_up", "in_transit", "arrived", "completed", "canceled", "failed");

    private final JdbcTemplate jdbc;
    private final TrackingService tracking;
    private final Clock clock;

    public OpsService(JdbcTemplate jdbc, TrackingService tracking, Clock clock) {
        this.jdbc = jdbc;
        this.tracking = tracking;
        this.clock = clock;
    }

    /** SQL condition limiting device alias {@code d} to what the actor may see; the argument goes into args. */
    private static String scope(Actor actor, List<Object> args) {
        if (actor.admin()) {
            return "TRUE";
        }
        args.add(actor.operatorId());
        return "d.operator_id = ?";
    }

    // ---------- devices and live state ----------

    private record RawDevice(UUID id, String code, String type, String status, UUID stationId, String stationName,
                             UUID operatorId, double stationLat, double stationLng, String blockKind, UUID orderId,
                             Instant blockStart, Instant blockEnd, String route, Double pickupLat, Double pickupLng,
                             Double dropoffLat, Double dropoffLng, Instant departsAt, int futureBookings) {
    }

    private List<RawDevice> rawDevices(Actor actor, Instant now) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        String where = scope(actor, args);
        return jdbc.query("""
                SELECT d.id, d.code, d.device_type, d.status, d.station_id, s.name AS station_name, d.operator_id,
                       ST_Y(s.location::geometry) AS slat, ST_X(s.location::geometry) AS slng,
                       b.kind, b.order_id, b.starts_at, b.ends_at,
                       o.route_points::text AS route, o.departs_at,
                       ST_Y(o.pickup_location::geometry) AS plat, ST_X(o.pickup_location::geometry) AS plng,
                       ST_Y(o.dropoff_location::geometry) AS dlat, ST_X(o.dropoff_location::geometry) AS dlng,
                       (SELECT count(*) FROM device_block f WHERE f.device_id = d.id AND f.released_at IS NULL
                          AND f.kind = 'order' AND f.ends_at > ?) AS future
                FROM device d
                JOIN station s ON s.id = d.station_id
                LEFT JOIN LATERAL (
                    SELECT kind, order_id, starts_at, ends_at FROM device_block
                    WHERE device_id = d.id AND released_at IS NULL AND starts_at <= ? AND ends_at > ?
                    ORDER BY starts_at DESC LIMIT 1) b ON TRUE
                LEFT JOIN delivery_order o ON o.id = b.order_id
                WHERE """ + where + " ORDER BY s.name, d.device_type, d.code",
                (rs, i) -> new RawDevice(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("device_type"), rs.getString("status"), rs.getObject("station_id", UUID.class),
                        rs.getString("station_name"), rs.getObject("operator_id", UUID.class),
                        rs.getDouble("slat"), rs.getDouble("slng"), rs.getString("kind"),
                        rs.getObject("order_id", UUID.class), instant(rs, "starts_at"), instant(rs, "ends_at"),
                        rs.getString("route"), dbl(rs, "plat"), dbl(rs, "plng"), dbl(rs, "dlat"), dbl(rs, "dlng"),
                        instant(rs, "departs_at"), rs.getInt("future")),
                args.toArray());
    }

    /** D31: state and position at {@code now}, from the current block (or the station when idle). */
    private LiveDevice live(RawDevice d, Instant now) {
        String id = d.id().toString();
        String station = d.stationId().toString();
        if (!"active".equals(d.status())) {
            return new LiveDevice(id, d.code(), d.type(), station, d.status(), d.stationLat(), d.stationLng(), null);
        }
        if ("order".equals(d.blockKind()) && d.route() != null) {
            DeliveryPlan plan = tracking.plan(d.orderId(), d.route(), new LatLng(d.pickupLat(), d.pickupLng()), d.type());
            DeliveryPlan.Position p = plan.positionAt(tracking.simSecondsSince(d.departsAt(), now));
            return new LiveDevice(id, d.code(), d.type(), station, "on_order", p.lat(), p.lng(), d.orderId().toString());
        }
        if ("return".equals(d.blockKind()) && d.dropoffLat() != null) {
            // Straight line drop-off -> station; the block also holds the safety buffer, so it arrives a bit early.
            double total = Duration.between(d.blockStart(), d.blockEnd()).toMillis();
            double f = total <= 0 ? 1 : Math.min(1, Duration.between(d.blockStart(), now).toMillis() / (total / 1.2));
            double lat = d.dropoffLat() + (d.stationLat() - d.dropoffLat()) * f;
            double lng = d.dropoffLng() + (d.stationLng() - d.dropoffLng()) * f;
            return new LiveDevice(id, d.code(), d.type(), station, f >= 1 ? "idle" : "returning", lat, lng,
                    d.orderId() == null ? null : d.orderId().toString());
        }
        return new LiveDevice(id, d.code(), d.type(), station, "idle", d.stationLat(), d.stationLng(), null);
    }

    public List<LiveDevice> liveDevices(Actor actor) {
        Instant now = clock.instant();
        return rawDevices(actor, now).stream().map(d -> live(d, now)).toList();
    }

    public List<DeviceRow> devices(Actor actor) {
        Instant now = clock.instant();
        return rawDevices(actor, now).stream().map(d -> {
            LiveDevice l = live(d, now);
            return new DeviceRow(l.deviceId(), d.code(), d.type(), d.status(), l.state(), l.stationId(),
                    d.stationName(), d.operatorId() == null ? null : d.operatorId().toString(), l.lat(), l.lng(),
                    l.orderId(), d.futureBookings());
        }).toList();
    }

    public Overview overview(Actor actor) {
        Instant now = clock.instant();
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (RawDevice d : rawDevices(actor, now)) {
            String sid = d.stationId().toString();
            names.put(sid, d.stationName());
            int[] c = counts.computeIfAbsent(sid, k -> new int[4]);
            switch (live(d, now).state()) {
                case "idle" -> c[0]++;
                case "on_order" -> c[1]++;
                case "returning" -> c[2]++;
                default -> c[3]++;
            }
        }
        List<StationCount> stations = counts.entrySet().stream().map(e -> new StationCount(e.getKey(),
                names.get(e.getKey()), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3])).toList();

        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now.atZone(SF).toLocalDate().atStartOfDay(SF).toInstant()));
        args.add(Timestamp.from(now));
        String where = scope(actor, args);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE o.created_at >= ?) AS today,
                       count(*) FILTER (WHERE o.status IN ('dispatched', 'picked_up', 'in_transit', 'arrived')) AS active,
                       count(*) FILTER (WHERE o.status = 'confirmed' AND o.departs_at > ?) AS scheduled
                FROM delivery_order o JOIN device d ON d.id = o.device_id WHERE """ + where, args.toArray());
        return new Overview(stations, num(row.get("today")), num(row.get("active")), num(row.get("scheduled")));
    }

    @Transactional
    public DeviceRow createDevice(Actor actor, CreateDevice req) {
        UUID stationId = uuid(req.stationId(), "STATION_NOT_FOUND");
        UUID operatorId = actor.admin() ? (req.operatorId() == null || req.operatorId().isBlank() ? null
                : uuid(req.operatorId(), "OPERATOR_NOT_FOUND")) : actor.operatorId();
        if (jdbc.queryForList("SELECT 1 FROM station WHERE id = ?", stationId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND", "This station does not exist");
        }
        if (operatorId != null && jdbc.queryForList("SELECT 1 FROM operator WHERE id = ?", operatorId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OPERATOR_NOT_FOUND", "This operator does not exist");
        }
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO device (code, station_id, device_type, operator_id) VALUES (?, ?, ?, ?) RETURNING id""",
                    UUID.class, req.code().trim(), stationId, req.deviceType(), operatorId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_CODE_TAKEN", "Another device already uses this code");
        }
        return deviceRow(actor, id);
    }

    /** D30: no delete; deactivating or moving a device with future bookings returns 409 DEVICE_HAS_BOOKINGS. */
    @Transactional
    public DeviceRow patchDevice(Actor actor, UUID id, PatchDevice req) {
        List<Object> args = new ArrayList<>(List.of(id));
        String where = scope(actor, args);
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT d.status, d.station_id FROM device d WHERE d.id = ? AND " + where + " FOR UPDATE", args.toArray());
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "This device does not exist");
        }
        UUID newStation = req.stationId() == null ? null : uuid(req.stationId(), "STATION_NOT_FOUND");
        boolean moving = newStation != null && !newStation.equals(found.get(0).get("station_id"));
        boolean deactivating = req.status() != null && !"active".equals(req.status());
        if (moving || deactivating) {
            Integer future = jdbc.queryForObject("""
                    SELECT count(*) FROM device_block WHERE device_id = ? AND released_at IS NULL AND ends_at > ?""",
                    Integer.class, id, Timestamp.from(clock.instant()));
            if (future != null && future > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "DEVICE_HAS_BOOKINGS",
                        "This device has " + future + " booking(s) still to run; cancel or reassign them first");
            }
        }
        if (newStation != null) {
            if (jdbc.queryForList("SELECT 1 FROM station WHERE id = ?", newStation).isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND", "This station does not exist");
            }
            jdbc.update("UPDATE device SET station_id = ? WHERE id = ?", newStation, id);
        }
        if (req.status() != null) {
            jdbc.update("UPDATE device SET status = ? WHERE id = ?", req.status(), id);
        }
        return deviceRow(actor, id);
    }

    private DeviceRow deviceRow(Actor actor, UUID id) {
        return devices(actor).stream().filter(d -> d.deviceId().equals(id.toString())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "This device does not exist"));
    }

    /** Today's and future blocks of one device (start of today in San Francisco). */
    public List<Booking> schedule(Actor actor, UUID id) {
        List<Object> args = new ArrayList<>(List.of(id));
        String where = scope(actor, args);
        if (jdbc.queryForList("SELECT 1 FROM device d WHERE d.id = ? AND " + where, args.toArray()).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "This device does not exist");
        }
        Instant today = clock.instant().atZone(SF).toLocalDate().atStartOfDay(SF).toInstant();
        return jdbc.query("""
                SELECT b.kind, b.order_id, b.starts_at, b.ends_at, o.status
                FROM device_block b LEFT JOIN delivery_order o ON o.id = b.order_id
                WHERE b.device_id = ? AND b.released_at IS NULL AND b.ends_at >= ? ORDER BY b.starts_at""",
                (rs, i) -> new Booking(rs.getString("kind"),
                        rs.getObject("order_id") == null ? null : rs.getObject("order_id").toString(),
                        instant(rs, "starts_at"), instant(rs, "ends_at"), rs.getString("status")),
                id, Timestamp.from(today));
    }

    // ---------- orders and metrics ----------

    public List<OpsOrder> orders(Actor actor, String status, Instant from, Instant to) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT o.id, o.status, d.code, o.device_type, s.name AS station_name, o.recipient_name,
                       o.recipient_phone, o.pickup_text, o.dropoff_text, o.price_cents, o.created_at, o.departs_at,
                       o.eta_at, o.completed_at
                FROM delivery_order o JOIN device d ON d.id = o.device_id JOIN station s ON s.id = d.station_id
                WHERE """);
        sql.append(scope(actor, args));
        if (status != null && !status.isBlank()) {
            if ("active".equals(status)) {
                sql.append(" AND o.status IN ('dispatched', 'picked_up', 'in_transit', 'arrived')");
            } else if (ORDER_STATUSES.contains(status)) {
                sql.append(" AND o.status = ?");
                args.add(status);
            } else {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unknown status " + status);
            }
        }
        if (from != null) {
            sql.append(" AND o.created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND o.created_at < ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY o.created_at DESC LIMIT 200");
        Instant now = clock.instant();
        return jdbc.query(sql.toString(), (rs, i) -> {
            String phone = rs.getString("recipient_phone");
            Instant eta = instant(rs, "eta_at");
            Instant done = instant(rs, "completed_at");
            String st = rs.getString("status");
            // D44: highlight orders running past their ETA.
            boolean late = done != null ? done.isAfter(eta)
                    : Set.of("dispatched", "picked_up", "in_transit", "arrived").contains(st) && now.isAfter(eta);
            return new OpsOrder(rs.getObject("id").toString(), st, rs.getString("code"), rs.getString("device_type"),
                    rs.getString("station_name"), rs.getString("recipient_name"),
                    phone == null ? null : phone.substring(Math.max(0, phone.length() - 4)), // D29: last 4 only
                    rs.getString("pickup_text"), rs.getString("dropoff_text"), rs.getInt("price_cents"),
                    instant(rs, "created_at"), instant(rs, "departs_at"), eta, done, late);
        }, args.toArray());
    }

    public Metrics metrics(Actor actor, Instant from, Instant to) {
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;
        if (!start.isBefore(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "from must be before to");
        }
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(start), Timestamp.from(end)));
        String where = scope(actor, args);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) AS completed,
                       avg(CASE WHEN o.completed_at <= o.eta_at THEN 1.0 ELSE 0.0 END) AS on_time,
                       avg(extract(epoch FROM o.completed_at - o.departs_at) / 60.0) AS avg_minutes
                FROM delivery_order o JOIN device d ON d.id = o.device_id
                WHERE o.status = 'completed' AND o.completed_at >= ? AND o.completed_at < ? AND """ + where,
                args.toArray());

        Map<Integer, Integer> ratings = new LinkedHashMap<>();
        for (int r = 1; r <= 5; r++) {
            ratings.put(r, 0);
        }
        args = new ArrayList<>(List.of(Timestamp.from(start), Timestamp.from(end)));
        where = scope(actor, args);
        jdbc.query("""
                SELECT r.rating, count(*) AS n FROM delivery_review r
                JOIN delivery_order o ON o.id = r.order_id JOIN device d ON d.id = o.device_id
                WHERE r.created_at >= ? AND r.created_at < ? AND """ + where + " GROUP BY r.rating",
                rs -> {
                    ratings.put(rs.getInt("rating"), rs.getInt("n"));
                }, args.toArray());

        // Utilization: busy device-time (order + return blocks clipped to the window) / (devices x window).
        args = new ArrayList<>(List.of(Timestamp.from(end), Timestamp.from(start), Timestamp.from(start),
                Timestamp.from(end)));
        where = scope(actor, args);
        Map<String, Object> util = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM device d WHERE %s) AS devices,
                       coalesce(sum(extract(epoch FROM least(b.ends_at, ?) - greatest(b.starts_at, ?))), 0) AS busy_s
                FROM device_block b JOIN device d ON d.id = b.device_id
                WHERE b.released_at IS NULL AND b.ends_at > ? AND b.starts_at < ? AND %s"""
                        .formatted(where, where),
                utilArgs(actor, args));
        double windowS = Duration.between(start, end).toSeconds();
        int devices = num(util.get("devices"));
        Double utilization = devices == 0 ? null : Math.min(1, ((Number) util.get("busy_s")).doubleValue() / (devices * windowS));

        return new Metrics(start, end, num(row.get("completed")), dblOrNull(row.get("on_time")),
                dblOrNull(row.get("avg_minutes")), ratings, utilization);
    }

    /** The utilization query uses the scope twice: once in the device count, once in the block filter. */
    private static Object[] utilArgs(Actor actor, List<Object> args) {
        if (actor.admin()) {
            return args.toArray();
        }
        List<Object> all = new ArrayList<>();
        all.add(actor.operatorId());
        all.addAll(args);
        return all.toArray();
    }

    // ---------- helpers ----------

    private static UUID uuid(String raw, String code) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, code, "Not found");
        }
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static Double dblOrNull(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }
}
