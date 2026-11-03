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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real SQL and HTTP tests; all fixtures roll back after each test. */
@EnabledIfEnvironmentVariable(named = "SKYLANE_ORDER_TEST_DB", matches = "1")
@SpringBootTest(properties = "skylane.sim.speed=10")
@AutoConfigureMockMvc
@ActiveProfiles("order-test")
@Transactional
class ListOrdersIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired OrderService orders;
    @Autowired JwtService jwt;
    @MockBean DeviceSimulator simulator;
    UUID user, other, station, device, quote, option;
    String token;
    final Instant base = Instant.parse("2026-11-02T12:00:00Z");

    @BeforeEach
    void fixture() {
        user = UUID.randomUUID(); other = UUID.randomUUID(); station = UUID.randomUUID();
        device = UUID.randomUUID(); quote = UUID.randomUUID(); option = UUID.randomUUID();
        for (UUID id : new UUID[]{user, other}) {
            jdbc.update("INSERT INTO app_user(id,email,display_name) VALUES (?,?,'List test')", id, id + "@example.test");
        }
        token = "Bearer " + jwt.issue(user, 0);
        jdbc.update("""
                INSERT INTO station(id,code,name,address_text,location)
                VALUES (?,?,'List test','Station',ST_GeogFromText('POINT(-122.40 37.78)'))""", station, station.toString());
        jdbc.update("INSERT INTO device(id,code,station_id,device_type) VALUES (?,?,?,'drone')", device, device.toString(), station);
        jdbc.update("""
                INSERT INTO quote(id,user_id,item_description,weight_kg,pickup_text,pickup_location,
                    dropoff_text,dropoff_location,expires_at)
                VALUES (?,?,'Cake',1.5,'Pickup',ST_GeogFromText('POINT(-122.40 37.78)'),
                    'Dropoff',ST_GeogFromText('POINT(-122.40 37.79)'),now()+interval '10 minutes')""", quote, user);
        jdbc.update("""
                INSERT INTO quote_option(id,quote_id,station_id,device_type,available,price_cents)
                VALUES (?,?,?,'drone',true,150)""", option, quote, station);
    }

    UUID addOrder(UUID owner, int number, String status, Instant created) {
        UUID id = new UUID(0, number);
        jdbc.update("""
                INSERT INTO delivery_order(id,user_id,quote_id,option_id,device_id,device_type,status,
                    recipient_name,recipient_phone,price_cents,pickup_text,pickup_location,
                    dropoff_text,dropoff_location,route_points,departs_at,eta_at,idempotency_key,created_at,completed_at)
                VALUES (?,?,?,?,?,'drone',?,'Recipient','+14155550100',150,'Pickup',
                    ST_GeogFromText('POINT(-122.40 37.78)'),'Dropoff',ST_GeogFromText('POINT(-122.40 37.79)'),
                    '[[37.78,-122.40],[37.79,-122.40]]'::jsonb,?,?,?, ?,?)""",
                id, owner, quote, option, device, status,
                Timestamp.from(created.plusSeconds(60)), Timestamp.from(created.plusSeconds(180)),
                "list-" + number, Timestamp.from(created),
                "completed".equals(status) ? Timestamp.from(created.plusSeconds(210)) : null);
        return id;
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void emptyAccountReturnsArrayAndCannotSeeOtherUsersOrders() throws Exception {
        addOrder(other, 1, "confirmed", base);
        mvc.perform(get("/api/v1/orders").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void listsOnlyOwnedOrdersNewestFirstIncludingAllStatuses() throws Exception {
        addOrder(user, 1, "completed", base);
        addOrder(other, 2, "confirmed", base.plusSeconds(100));
        UUID canceled = addOrder(user, 3, "canceled", base.plusSeconds(10));
        UUID newest = addOrder(user, 4, "confirmed", base.plusSeconds(20));
        mvc.perform(get("/api/v1/orders").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].order_id").value(newest.toString()))
                .andExpect(jsonPath("$[1].order_id").value(canceled.toString()))
                .andExpect(jsonPath("$[2].status").value("completed"));
    }

    @Test
    void sameCreationTimeHasDeterministicOrderBeforeApplyingLimit() {
        addOrder(user, 1, "confirmed", base);
        UUID second = addOrder(user, 2, "confirmed", base);
        UUID third = addOrder(user, 3, "confirmed", base);
        assertThat(orders.list(user, 2)).extracting(OrderDtos.OrderSummary::orderId)
                .containsExactly(third.toString(), second.toString());
    }

    @Test
    void exposesContractFieldsAndReviewFlagWithoutPrivateDetails() throws Exception {
        UUID completed = addOrder(user, 1, "completed", base);
        UUID pending = addOrder(user, 2, "confirmed", base.plusSeconds(10));
        jdbc.update("INSERT INTO delivery_review(order_id,user_id,rating) VALUES (?,?,5)", completed, user);
        mvc.perform(get("/api/v1/orders").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].order_id").value(pending.toString()))
                .andExpect(jsonPath("$[0].reviewed").value(false))
                .andExpect(jsonPath("$[0].completed_at").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].reviewed").value(true))
                .andExpect(jsonPath("$[1].device_type").value("drone"))
                .andExpect(jsonPath("$[1].price_cents").value(150))
                .andExpect(jsonPath("$[1].created_at").value("2026-11-02T12:00:00Z"))
                .andExpect(jsonPath("$[1].departs_at").value("2026-11-02T12:01:00Z"))
                .andExpect(jsonPath("$[1].eta_at").value("2026-11-02T12:03:00Z"))
                .andExpect(jsonPath("$[1].completed_at").value("2026-11-02T12:03:30Z"))
                .andExpect(jsonPath("$[1].pickup_text").value("Pickup"))
                .andExpect(jsonPath("$[1].dropoff_text").value("Dropoff"))
                .andExpect(jsonPath("$[1].recipient_name").value("Recipient"))
                .andExpect(jsonPath("$[1].user_id").doesNotExist())
                .andExpect(jsonPath("$[1].idempotency_key").doesNotExist());
    }

    @Test
    void defaultsTo100AndClampsNumericLimitsToOneThrough100() throws Exception {
        for (int i = 1; i <= 105; i++) addOrder(user, i, "confirmed", base.plusSeconds(i));
        for (String value : new String[]{"100", "101", "2147483647"}) {
            mvc.perform(get("/api/v1/orders").param("limit", value).header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(100));
        }
        mvc.perform(get("/api/v1/orders").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(100));
        for (String value : new String[]{"0", "-1", "-2147483648", "1", "3"}) {
            mvc.perform(get("/api/v1/orders").param("limit", value).header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value("3".equals(value) ? 3 : 1));
        }
    }

    @Test
    void malformedLimitReturnsValidationErrorInsteadOfServerError() throws Exception {
        for (String value : new String[]{"abc", "1.5", "2147483648", "-2147483649", " "}) {
            mvc.perform(get("/api/v1/orders").param("limit", value).header("Authorization", token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }
}
