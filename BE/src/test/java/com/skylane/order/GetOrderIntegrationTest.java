package com.skylane.order;

import com.skylane.auth.JwtService;
import com.skylane.tracking.DeviceSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "SKYLANE_ORDER_TEST_DB", matches = "1")
@SpringBootTest(properties = "skylane.sim.speed=10")
@AutoConfigureMockMvc
@ActiveProfiles("order-test")
@Transactional
class GetOrderIntegrationTest {
    @SpyBean JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean DeviceSimulator simulator;
    UUID user, other, station, device, quote, option, order;
    String token;

    @BeforeEach
    void fixture() {
        user = UUID.randomUUID(); other = UUID.randomUUID(); station = UUID.randomUUID();
        device = UUID.randomUUID(); quote = UUID.randomUUID(); option = UUID.randomUUID(); order = UUID.randomUUID();
        for (UUID id : new UUID[]{user, other}) jdbc.update(
                "INSERT INTO app_user(id,email,display_name) VALUES (?,?,'Detail test')", id, id + "@example.test");
        token = "Bearer " + jwt.issue(user, 0);
        jdbc.update("""
                INSERT INTO station(id,code,name,address_text,location)
                VALUES (?,?,'Test Station','Station',ST_GeogFromText('POINT(-122.40 37.78)'))""", station, station.toString());
        jdbc.update("INSERT INTO device(id,code,station_id,device_type) VALUES (?,'D-' || ?::text,?,'drone')", device, device, station);
        jdbc.update("""
                INSERT INTO quote(id,user_id,item_description,weight_kg,pickup_text,pickup_location,
                    dropoff_text,dropoff_location,expires_at)
                VALUES (?,?,'Original quote',1.5,'Quote pickup',ST_GeogFromText('POINT(-122.41 37.77)'),
                    'Quote dropoff',ST_GeogFromText('POINT(-122.42 37.76)'),now()+interval '10 minutes')""", quote, user);
        jdbc.update("""
                INSERT INTO quote_option(id,quote_id,station_id,device_type,available,price_cents,distance_m)
                VALUES (?,?,?,'drone',true,999,1234)""", option, quote, station);
        jdbc.update("""
                INSERT INTO delivery_order(id,user_id,quote_id,option_id,device_id,device_type,
                    recipient_name,recipient_phone,price_cents,notes,pickup_text,pickup_location,
                    dropoff_text,dropoff_location,route_points,departs_at,eta_at,idempotency_key)
                VALUES (?,?,?,?,?,'drone','Ziyue','+14155550100',150,'Fragile cake',
                    'Order pickup',ST_GeogFromText('POINT(-122.40 37.781)'),
                    'Order dropoff',ST_GeogFromText('POINT(-122.39 37.79)'),
                    '[[37.78,-122.40],[37.781,-122.40],[37.79,-122.39]]'::jsonb,
                    now()+interval '5 minutes',now()+interval '10 minutes','detail-key')""",
                order, user, quote, option, device);
        jdbc.update("INSERT INTO order_item(order_id,description,is_fragile,weight_kg) VALUES (?,'Cake',true,1.5)", order);
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder detail() {
        return get("/api/v1/orders/" + order).header("Authorization", token);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/orders/" + order)).andExpect(status().isUnauthorized());
    }

    @Test
    void hidesOtherUsersOrdersAndMissingOrMalformedIds() throws Exception {
        String otherToken = "Bearer " + jwt.issue(other, 0);
        mvc.perform(get("/api/v1/orders/" + order).header("Authorization", otherToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        for (String id : new String[]{UUID.randomUUID().toString(), "not-a-uuid"}) {
            mvc.perform(get("/api/v1/orders/" + id).header("Authorization", token))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        }
    }

    @Test
    void returnsOrderSnapshotsAndMapCoordinatesInLatLngOrder() throws Exception {
        mvc.perform(detail()).andExpect(status().isOk())
                .andExpect(jsonPath("$.order_id").value(order.toString()))
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.price_cents").value(150))
                .andExpect(jsonPath("$.recipient_name").value("Ziyue"))
                .andExpect(jsonPath("$.recipient_phone").value("+14155550100"))
                .andExpect(jsonPath("$.notes").value("Fragile cake"))
                .andExpect(jsonPath("$.pickup.text").value("Order pickup"))
                .andExpect(jsonPath("$.pickup_text").value("Order pickup"))
                .andExpect(jsonPath("$.pickup.lat").value(37.781))
                .andExpect(jsonPath("$.pickup.lng").value(-122.40))
                .andExpect(jsonPath("$.dropoff.text").value("Order dropoff"))
                .andExpect(jsonPath("$.dropoff.lat").value(37.79))
                .andExpect(jsonPath("$.dropoff.lng").value(-122.39))
                .andExpect(jsonPath("$.item.description").value("Cake"))
                .andExpect(jsonPath("$.item.is_fragile").value(true))
                .andExpect(jsonPath("$.item.weight_kg").value(1.5))
                .andExpect(jsonPath("$.device.code").value("D-" + device))
                .andExpect(jsonPath("$.device.device_type").value("drone"))
                .andExpect(jsonPath("$.station.name").value("Test Station"))
                .andExpect(jsonPath("$.station.lat").value(37.78))
                .andExpect(jsonPath("$.station.lng").value(-122.40))
                .andExpect(jsonPath("$.distance_m").value(1234))
                .andExpect(jsonPath("$.route_points.length()").value(3))
                .andExpect(jsonPath("$.route_points[0][0]").value(37.78))
                .andExpect(jsonPath("$.route_points[0][1]").value(-122.40))
                .andExpect(jsonPath("$.user_id").doesNotExist())
                .andExpect(jsonPath("$.idempotency_key").doesNotExist());
    }

    @Test
    void absentOptionalFieldsHaveStableNullAndArrayShapes() throws Exception {
        jdbc.update("UPDATE delivery_order SET notes=null WHERE id=?", order);
        mvc.perform(detail()).andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value(nullValue()))
                .andExpect(jsonPath("$.review").value(nullValue()))
                .andExpect(jsonPath("$.reviewed").value(false))
                .andExpect(jsonPath("$.completed_at").value(nullValue()))
                .andExpect(jsonPath("$.canceled_at").value(nullValue()))
                .andExpect(jsonPath("$.cancel_reason").value(nullValue()))
                .andExpect(jsonPath("$.timeline").isArray())
                .andExpect(jsonPath("$.timeline.length()").value(0));
    }

    @Test
    void onlyConfirmedOrdersStrictlyBeforeDepartureAreCancellable() throws Exception {
        mvc.perform(detail()).andExpect(status().isOk()).andExpect(jsonPath("$.cancellable").value(true));
        jdbc.update("UPDATE delivery_order SET departs_at=now() WHERE id=?", order);
        mvc.perform(detail()).andExpect(jsonPath("$.cancellable").value(false));
        jdbc.update("UPDATE delivery_order SET departs_at=now()-interval '1 minute' WHERE id=?", order);
        mvc.perform(detail()).andExpect(jsonPath("$.cancellable").value(false));
        for (String state : new String[]{"dispatched", "picked_up", "in_transit", "arrived", "completed", "canceled", "failed"}) {
            jdbc.update("UPDATE delivery_order SET status=?,departs_at=now()+interval '5 minutes' WHERE id=?", state, order);
            mvc.perform(detail()).andExpect(status().isOk()).andExpect(jsonPath("$.cancellable").value(false));
        }
    }

    @Test
    void timelineContainsOnlyThisOrdersEventsOrderedByEventId() throws Exception {
        UUID another = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO delivery_order(id,user_id,quote_id,option_id,device_id,device_type,recipient_name,
                    recipient_phone,price_cents,pickup_text,pickup_location,dropoff_text,dropoff_location,
                    route_points,departs_at,eta_at,idempotency_key)
                SELECT ?,user_id,quote_id,option_id,device_id,device_type,recipient_name,recipient_phone,
                    price_cents,pickup_text,pickup_location,dropoff_text,dropoff_location,route_points,departs_at,
                    eta_at,'another' FROM delivery_order WHERE id=?""", another, order);
        Long first = jdbc.queryForObject("INSERT INTO order_event(order_id,status,actor_type,occurred_at) VALUES (?,'confirmed','user','2026-11-02T12:00:00Z') RETURNING event_id", Long.class, order);
        jdbc.update("INSERT INTO order_event(order_id,status,actor_type) VALUES (?,'failed','system')", another);
        Long second = jdbc.queryForObject("INSERT INTO order_event(order_id,status,actor_type,occurred_at) VALUES (?,'dispatched','device','2026-11-02T12:01:00Z') RETURNING event_id", Long.class, order);
        mvc.perform(detail()).andExpect(status().isOk())
                .andExpect(jsonPath("$.timeline.length()").value(2))
                .andExpect(jsonPath("$.timeline[0].event_id").value(first.intValue()))
                .andExpect(jsonPath("$.timeline[0].status").value("confirmed"))
                .andExpect(jsonPath("$.timeline[0].occurred_at").value("2026-11-02T12:00:00Z"))
                .andExpect(jsonPath("$.timeline[1].event_id").value(second.intValue()))
                .andExpect(jsonPath("$.timeline[1].status").value("dispatched"));
    }

    @Test
    void returnsSavedReviewAndCompletionTime() throws Exception {
        jdbc.update("UPDATE delivery_order SET status='completed',completed_at='2026-11-02T12:10:00Z' WHERE id=?", order);
        jdbc.update("INSERT INTO delivery_review(order_id,user_id,rating,comment,created_at) VALUES (?,?,5,'Great','2026-11-02T12:11:00Z')", order, user);
        mvc.perform(detail()).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed_at").value("2026-11-02T12:10:00Z"))
                .andExpect(jsonPath("$.reviewed").value(true))
                .andExpect(jsonPath("$.review.rating").value(5))
                .andExpect(jsonPath("$.review.comment").value("Great"))
                .andExpect(jsonPath("$.review.created_at").value("2026-11-02T12:11:00Z"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void keepsDetailsReviewAndTimelineConsistentDuringConcurrentWrite() throws Exception {
        jdbc.update("INSERT INTO order_event(order_id,status,actor_type) VALUES (?,'confirmed','user')", order);
        AtomicBoolean updated = new AtomicBoolean();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM delivery_order o") && updated.compareAndSet(false, true)) {
                writer.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    jdbc.update("UPDATE delivery_order SET status='completed',completed_at=now() WHERE id=?", order);
                    jdbc.update("INSERT INTO order_event(order_id,status,actor_type) VALUES (?,'completed','device')", order);
                    jdbc.update("INSERT INTO delivery_review(order_id,user_id,rating) VALUES (?,?,5)", order, user);
                })).get(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(), any(Object[].class));
        try {
            mvc.perform(detail()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("confirmed"))
                    .andExpect(jsonPath("$.reviewed").value(false))
                    .andExpect(jsonPath("$.review").value(nullValue()))
                    .andExpect(jsonPath("$.timeline.length()").value(1));
            org.assertj.core.api.Assertions.assertThat(updated).isTrue();
            mvc.perform(detail()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("completed"))
                    .andExpect(jsonPath("$.reviewed").value(true))
                    .andExpect(jsonPath("$.review.rating").value(5))
                    .andExpect(jsonPath("$.timeline.length()").value(2));
        } finally {
            writer.shutdownNow();
            writer.awaitTermination(10, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM delivery_review WHERE order_id=?", order);
            jdbc.update("DELETE FROM order_event WHERE order_id=?", order);
            jdbc.update("DELETE FROM delivery_order WHERE id=?", order);
            jdbc.update("DELETE FROM quote WHERE id=?", quote);
            jdbc.update("DELETE FROM device WHERE id=?", device);
            jdbc.update("DELETE FROM station WHERE id=?", station);
            jdbc.update("DELETE FROM app_user WHERE id IN (?,?)", user, other);
        }
    }

    @Test
    void returnsCancellationDetails() throws Exception {
        jdbc.update("UPDATE delivery_order SET status='canceled',canceled_at='2026-11-02T12:02:00Z',cancel_reason='Plans changed' WHERE id=?", order);
        mvc.perform(detail()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("canceled"))
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.canceled_at").value("2026-11-02T12:02:00Z"))
                .andExpect(jsonPath("$.cancel_reason").value("Plans changed"));
    }
}
