package com.skylane.order;

import com.skylane.tracking.TrackingEventBus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only place that changes delivery_order.status (design.md, order states). Each change is a conditional update
 * on the expected current status plus an order_event row; the SSE push happens after commit.
 */
@Component
@Profile("!memory")
public class OrderStateMachine {
    private static final Map<String, Set<String>> NEXT = Map.of(
            "confirmed", Set.of("dispatched", "canceled", "failed"),
            "dispatched", Set.of("picked_up", "failed"),
            "picked_up", Set.of("in_transit", "failed"),
            "in_transit", Set.of("arrived", "failed"),
            "arrived", Set.of("completed", "failed"));

    private final JdbcTemplate jdbc;
    private final TrackingEventBus bus;

    public OrderStateMachine(JdbcTemplate jdbc, TrackingEventBus bus) {
        this.jdbc = jdbc;
        this.bus = bus;
    }

    /** false when the order was no longer in {@code from} (someone else moved it first). */
    public boolean transition(UUID orderId, String from, String to, Instant at, String actorType) {
        return transition(orderId, from, to, at, actorType, null, "");
    }

    /** {@code extraCondition} is appended to the WHERE clause, e.g. the cancel window check (D11). */
    public boolean transition(UUID orderId, String from, String to, Instant at, String actorType,
                              UUID actorId, String extraCondition) {
        if (!NEXT.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("Illegal transition " + from + " -> " + to);
        }
        Timestamp ts = Timestamp.from(at);
        int updated = jdbc.update("""
                        UPDATE delivery_order SET status = ?, updated_at = now(),
                               completed_at = CASE WHEN ? = 'completed' THEN ? ELSE completed_at END,
                               canceled_at = CASE WHEN ? = 'canceled' THEN ? ELSE canceled_at END
                        WHERE id = ? AND status = ?""" + extraCondition,
                to, to, ts, to, ts, orderId, from);
        if (updated == 0) {
            return false;
        }
        Long eventId = jdbc.queryForObject("""
                        INSERT INTO order_event (order_id, status, occurred_at, actor_type, actor_id)
                        VALUES (?, ?, ?, ?, ?) RETURNING event_id""",
                Long.class, orderId, to, ts, actorType, actorId);
        Runnable publish = () -> bus.publishStatus(orderId, eventId, to, at);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
        return true;
    }
}
