package com.skylane.tracking;

import com.skylane.geo.LatLng;
import com.skylane.order.DeliveryPlan;
import com.skylane.order.OrderService;
import com.skylane.order.OrderDtos.OrderDetail;
import com.skylane.order.OrderDtos.TimelineEntry;
import com.skylane.tracking.TrackingEventBus.PositionEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Position, progress and remaining time come from the route and departs_at, never from stored GPS (D15). */
@Service
@Profile("!memory")
public class TrackingService {
    private static final Set<String> MOVING = Set.of("dispatched", "picked_up", "in_transit");
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

    private final OrderService orders;
    private final TrackingEventBus bus;
    private final Clock clock;
    private final Map<UUID, DeliveryPlan> plans = new ConcurrentHashMap<>();

    public TrackingService(OrderService orders, TrackingEventBus bus, Clock clock) {
        this.orders = orders;
        this.bus = bus;
        this.clock = clock;
    }

    /** Parsed routes are cached; a route never changes after the order is placed. */
    public DeliveryPlan plan(UUID orderId, String routeJson, LatLng pickup, String deviceType) {
        return plans.computeIfAbsent(orderId, id -> new DeliveryPlan(orders.parseRoute(routeJson), pickup, deviceType));
    }

    public void forget(UUID orderId) {
        plans.remove(orderId);
    }

    public double simSecondsSince(Instant departsAt, Instant now) {
        return Math.max(0, Duration.between(departsAt, now).toMillis() / 1000.0 * orders.simSpeed());
    }

    public Instant realTime(Instant departsAt, double simOffsetSeconds) {
        return departsAt.plusMillis(Math.round(simOffsetSeconds / orders.simSpeed() * 1000));
    }

    public Optional<PositionEvent> position(DeliveryPlan plan, double t) {
        String status = plan.statusAt(t);
        if (!MOVING.contains(status)) {
            return Optional.empty();
        }
        DeliveryPlan.Position p = plan.positionAt(t);
        return Optional.of(new PositionEvent(p.lat(), p.lng(), round(p.progress()),
                Math.round(p.remainingS() / orders.simSpeed())));
    }

    /** design.md tracking: check owner -> replay missed status events -> current position -> register. */
    public SseEmitter open(UUID userId, UUID orderId, String lastEventId) {
        orders.requireOwned(userId, orderId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        // Register first so nothing published during the replay is lost; a duplicate status is harmless.
        bus.register(orderId, emitter);

        long after = parseEventId(lastEventId);
        List<TimelineEntry> missed = orders.timeline(orderId, after);
        for (TimelineEntry e : missed) {
            if (!TrackingEventBus.sendStatus(emitter, e.eventId(), e.status(), e.occurredAt())) {
                return emitter;
            }
        }
        OrderDetail order = orders.get(userId, orderId);
        if (TrackingEventBus.TERMINAL.contains(order.status())) {
            if (missed.isEmpty()) {
                // Reconnected after the end: resend the final status so the client knows to stop.
                List<TimelineEntry> all = orders.timeline(orderId, 0);
                TimelineEntry last = all.get(all.size() - 1);
                TrackingEventBus.sendStatus(emitter, last.eventId(), last.status(), last.occurredAt());
            }
            emitter.complete();
            return emitter;
        }
        if (MOVING.contains(order.status())) {
            DeliveryPlan plan = new DeliveryPlan(order.routePoints().stream().map(p -> new LatLng(p[0], p[1])).toList(),
                    new LatLng(order.pickup().lat(), order.pickup().lng()), order.deviceType());
            position(plan, simSecondsSince(order.departsAt(), clock.instant()))
                    .ifPresent(p -> bus.publishPosition(orderId, p));
        }
        return emitter;
    }

    private static long parseEventId(String raw) {
        try {
            return raw == null ? 0 : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
