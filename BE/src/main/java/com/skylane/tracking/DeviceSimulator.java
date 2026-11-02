package com.skylane.tracking;

import com.skylane.geo.LatLng;
import com.skylane.order.DeliveryPlan;
import com.skylane.order.OrderStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Moves every live order through its timetable once a second and pushes positions to open SSE streams
 * (design.md, device simulator). All durations are divided by skylane.sim.speed.
 */
@Component
@Profile("!memory")
public class DeviceSimulator {
    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);
    private static final List<String> FLOW = List.of("confirmed", "dispatched", "picked_up", "in_transit", "arrived", "completed");

    private record LiveOrder(UUID id, String status, Instant departsAt, String deviceType, String route,
                             double pickupLat, double pickupLng) {
    }

    private final JdbcTemplate jdbc;
    private final OrderStateMachine stateMachine;
    private final TrackingService tracking;
    private final TrackingEventBus bus;
    private final Clock clock;

    public DeviceSimulator(JdbcTemplate jdbc, OrderStateMachine stateMachine, TrackingService tracking,
                           TrackingEventBus bus, Clock clock) {
        this.jdbc = jdbc;
        this.stateMachine = stateMachine;
        this.tracking = tracking;
        this.bus = bus;
        this.clock = clock;
    }

    @Scheduled(fixedRate = 1000, initialDelay = 2000)
    public void tick() {
        Instant now = clock.instant();
        List<LiveOrder> live = jdbc.query("""
                        SELECT id, status, departs_at, device_type, route_points::text AS route,
                               ST_Y(pickup_location::geometry) AS plat, ST_X(pickup_location::geometry) AS plng
                        FROM delivery_order
                        WHERE status IN ('confirmed', 'dispatched', 'picked_up', 'in_transit', 'arrived')
                          AND departs_at <= ?""",
                (rs, i) -> new LiveOrder(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getTimestamp("departs_at").toInstant(), rs.getString("device_type"), rs.getString("route"),
                        rs.getDouble("plat"), rs.getDouble("plng")),
                Timestamp.from(now));
        Set<UUID> watched = bus.watchedOrders();
        for (LiveOrder o : live) {
            try {
                DeliveryPlan plan = tracking.plan(o.id(), o.route(), new LatLng(o.pickupLat(), o.pickupLng()), o.deviceType());
                double t = tracking.simSecondsSince(o.departsAt(), now);
                advance(o, plan, plan.statusAt(t));
                if (watched.contains(o.id())) {
                    tracking.position(plan, t).ifPresent(p -> bus.publishPosition(o.id(), p));
                }
            } catch (RuntimeException e) {
                log.warn("Simulator failed on order {}", o.id(), e);
            }
        }
    }

    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        bus.heartbeat();
    }

    /** One transition per step so the timeline shows every status, each stamped with its scheduled time. */
    private void advance(LiveOrder o, DeliveryPlan plan, String target) {
        int from = FLOW.indexOf(o.status());
        int to = FLOW.indexOf(target);
        for (int i = from; i < to; i++) {
            String next = FLOW.get(i + 1);
            Instant at = tracking.realTime(o.departsAt(), plan.offsetOf(next));
            if (!stateMachine.transition(o.id(), FLOW.get(i), next, at, "device")) {
                return; // canceled or moved by someone else in the meantime
            }
        }
        if (to == FLOW.size() - 1) {
            tracking.forget(o.id());
        }
    }
}
