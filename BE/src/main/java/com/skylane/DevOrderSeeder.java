package com.skylane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dev only, runs after DevRunner (stations, devices): 2 test accounts, each with 3 completed orders,
 * 1 of them reviewed (design.md, demo data). Password for both: password123. Skips users that already exist.
 */
@Component
@Profile("dev")
@Order(2)
public class DevOrderSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevOrderSeeder.class);
    public static final String PASSWORD = "password123";

    private record UserSeed(String email, String name) {
    }

    private record PastOrder(String station, String deviceType, String item, double weightKg,
                             String pickupText, double pLat, double pLng, String dropoffText, double dLat, double dLng,
                             String recipient, int daysAgo, Integer rating, String comment) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public DevOrderSeeder(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int users = 0;
        for (UserSeed u : List.of(new UserSeed("alice@skylane.dev", "Alice"), new UserSeed("bob@skylane.dev", "Bob"))) {
            if (!jdbc.queryForList("SELECT 1 FROM app_user WHERE email = ?", u.email()).isEmpty()) {
                continue;
            }
            UUID userId = jdbc.queryForObject("INSERT INTO app_user (email, display_name) VALUES (?, ?) RETURNING id",
                    UUID.class, u.email(), u.name());
            jdbc.update("INSERT INTO user_credential (user_id, password_hash) VALUES (?, ?)", userId, encoder.encode(PASSWORD));
            for (PastOrder o : history()) {
                seedOrder(userId, o);
            }
            users++;
        }
        log.info("Development users ready: {} new (password '{}')", users, PASSWORD);
    }

    private static List<PastOrder> history() {
        return List.of(
                new PastOrder("soma", "drone", "Birthday cupcakes", 1.2,
                        "Ferry Building, San Francisco", 37.7955, -122.3937, "Oracle Park, San Francisco", 37.7786, -122.3893,
                        "Jamie Lee", 6, 5, "Fast and careful, cupcakes arrived perfect."),
                new PastOrder("mission", "robot", "Signed contracts", 0.4,
                        "Dolores Park, San Francisco", 37.7596, -122.4269, "16th St Mission BART, San Francisco", 37.7650, -122.4195,
                        "Chris Park", 3, null, null),
                new PastOrder("marina", "robot", "Phone case", 0.3,
                        "Palace of Fine Arts, San Francisco", 37.8029, -122.4484, "Chestnut St & Fillmore St, San Francisco", 37.8005, -122.4363,
                        "Morgan Diaz", 1, null, null));
    }

    private void seedOrder(UUID userId, PastOrder o) {
        UUID stationId = jdbc.queryForObject("SELECT id FROM station WHERE code = ?", UUID.class, o.station());
        Double sLat = jdbc.queryForObject("SELECT ST_Y(location::geometry) FROM station WHERE id = ?", Double.class, stationId);
        Double sLng = jdbc.queryForObject("SELECT ST_X(location::geometry) FROM station WHERE id = ?", Double.class, stationId);
        UUID deviceId = jdbc.queryForObject("""
                SELECT id FROM device WHERE station_id = ? AND device_type = ? ORDER BY code LIMIT 1""",
                UUID.class, stationId, o.deviceType());

        Instant created = Instant.now().minus(Duration.ofDays(o.daysAgo())).minus(Duration.ofHours(2));
        Instant departs = created.plusSeconds(60);
        int leg2 = (int) Math.round(haversine(o.pLat(), o.pLng(), o.dLat(), o.dLng()) * ("robot".equals(o.deviceType()) ? 1.3 : 1));
        int leg1 = (int) Math.round(haversine(sLat, sLng, o.pLat(), o.pLng()) * ("robot".equals(o.deviceType()) ? 1.3 : 1));
        double mps = "robot".equals(o.deviceType()) ? 1.5 : 15;
        Instant eta = departs.plusSeconds(Math.round((leg1 + leg2) / mps) + ("robot".equals(o.deviceType()) ? 60 : 180));
        int price = Math.max(100, (int) Math.round(o.weightKg() * leg2 / 1609.344 * 100));
        String route = "[[%s,%s],[%s,%s],[%s,%s]]".formatted(sLat, sLng, o.pLat(), o.pLng(), o.dLat(), o.dLng());

        UUID quoteId = jdbc.queryForObject("""
                        INSERT INTO quote (user_id, item_description, is_fragile, weight_kg, pickup_text, pickup_location,
                                           dropoff_text, dropoff_location, expires_at, created_at)
                        VALUES (?, ?, false, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                                ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?)
                        RETURNING id""",
                UUID.class, userId, o.item(), o.weightKg(), o.pickupText(), o.pLng(), o.pLat(),
                o.dropoffText(), o.dLng(), o.dLat(), ts(created.plusSeconds(600)), ts(created));
        UUID optionId = jdbc.queryForObject("""
                        INSERT INTO quote_option (quote_id, station_id, device_type, available, departs_at, eta_at,
                                                  price_cents, distance_m, leg1_m, leg2_m, route_points)
                        VALUES (?, ?, ?, true, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id""",
                UUID.class, quoteId, stationId, o.deviceType(), ts(departs), ts(eta), price, leg1 + leg2, leg1, leg2, route);
        Instant completed = eta.plusSeconds(30);
        UUID orderId = jdbc.queryForObject("""
                        INSERT INTO delivery_order (user_id, quote_id, option_id, device_id, device_type, status,
                            recipient_name, recipient_phone, price_cents, pickup_text, pickup_location,
                            dropoff_text, dropoff_location, route_points, departs_at, eta_at, idempotency_key,
                            confirmed_at, completed_at, created_at, updated_at)
                        SELECT ?, ?, ?, ?, ?, 'completed', ?, '+14155550100', ?, pickup_text, pickup_location,
                               dropoff_text, dropoff_location, ?::jsonb, ?, ?, ?, ?, ?, ?, ?
                        FROM quote WHERE id = ? RETURNING id""",
                UUID.class, userId, quoteId, optionId, deviceId, o.deviceType(), o.recipient(), price, route,
                ts(departs), ts(eta), "seed-" + UUID.randomUUID(), ts(created), ts(completed), ts(created), ts(completed),
                quoteId);
        jdbc.update("INSERT INTO order_item (order_id, description, is_fragile, weight_kg) VALUES (?, ?, false, ?)",
                orderId, o.item(), o.weightKg());

        double t1 = leg1 / mps + ("drone".equals(o.deviceType()) ? 60 : 0);
        Object[][] events = {
                {"confirmed", created, "user"},
                {"dispatched", departs, "device"},
                {"picked_up", departs.plusSeconds(Math.round(t1)), "device"},
                {"in_transit", departs.plusSeconds(Math.round(t1) + 60), "device"},
                {"arrived", eta, "device"},
                {"completed", completed, "device"}};
        for (Object[] e : events) {
            jdbc.update("INSERT INTO order_event (order_id, status, occurred_at, actor_type) VALUES (?, ?, ?, ?)",
                    orderId, e[0], ts((Instant) e[1]), e[2]);
        }
        if (o.rating() != null) {
            jdbc.update("INSERT INTO delivery_review (order_id, user_id, rating, comment, created_at) VALUES (?, ?, ?, ?, ?)",
                    orderId, userId, o.rating(), o.comment(), ts(completed.plusSeconds(300)));
        }
    }

    private static Timestamp ts(Instant t) {
        return Timestamp.from(t);
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
