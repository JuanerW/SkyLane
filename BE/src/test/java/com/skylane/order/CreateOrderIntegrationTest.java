package com.skylane.order;

import com.skylane.common.ApiException;
import com.skylane.tracking.DeviceSimulator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Uses real PostgreSQL/PostGIS constraints, transactions and locks. Never uses seed accounts. */
@EnabledIfEnvironmentVariable(named = "SKYLANE_ORDER_TEST_DB", matches = "1")
@SpringBootTest(properties = "skylane.sim.speed=10")
@AutoConfigureMockMvc
@ActiveProfiles("order-test")
class CreateOrderIntegrationTest {
    @Autowired OrderService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.skylane.auth.JwtService jwt;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockBean DeviceSimulator simulator;
    UUID user, otherUser, station, first, second, quote, option;

    @BeforeEach
    void fixture() {
        user = UUID.randomUUID(); otherUser = UUID.randomUUID(); station = UUID.randomUUID();
        first = UUID.randomUUID(); second = UUID.randomUUID(); quote = UUID.randomUUID(); option = UUID.randomUUID();
        for (UUID id : new UUID[]{user, otherUser}) {
            jdbc.update("INSERT INTO app_user(id,email,display_name) VALUES (?, ?, 'Order test')", id, id + "@example.test");
        }
        jdbc.update("""
                INSERT INTO station(id,code,name,address_text,location)
                VALUES (?,?,'Test','Test',ST_GeogFromText('POINT(-122.40 37.78)'))""", station, station.toString());
        jdbc.update("INSERT INTO device(id,code,station_id,device_type) VALUES (?, ?, ?, 'drone'), (?, ?, ?, 'drone')",
                first, "A-" + first, station, second, "B-" + second, station);
        jdbc.update("""
                INSERT INTO quote(id,user_id,item_description,weight_kg,pickup_text,pickup_location,
                    dropoff_text,dropoff_location,expires_at)
                VALUES (?,?,'Cake',1.5,'Pickup',ST_GeogFromText('POINT(-122.40 37.781)'),
                    'Dropoff',ST_GeogFromText('POINT(-122.40 37.782)'),now()+interval '10 minutes')""", quote, user);
        jdbc.update("""
                INSERT INTO quote_option(id,quote_id,station_id,device_type,available,departs_at,eta_at,
                    price_cents,distance_m,route_points)
                VALUES (?,?,?,'drone',true,now(),now()+interval '5 minutes',150,111,
                    '[[37.78,-122.40],[37.781,-122.40],[37.782,-122.40]]'::jsonb)""", option, quote, station);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM device_block WHERE device_id IN (?,?)", first, second);
        jdbc.update("DELETE FROM order_event WHERE order_id IN (SELECT id FROM delivery_order WHERE user_id=?)", user);
        jdbc.update("DELETE FROM delivery_order WHERE user_id=?", user);
        jdbc.update("DELETE FROM quote WHERE id=?", quote);
        jdbc.update("DELETE FROM device WHERE station_id=?", station);
        jdbc.update("DELETE FROM station WHERE id=?", station);
        jdbc.update("DELETE FROM app_user WHERE id IN (?,?)", user, otherUser);
    }

    OrderDtos.CreateOrderRequest request() {
        return new OrderDtos.CreateOrderRequest(quote.toString(), option.toString(),
                new OrderDtos.Recipient("Ziyue", "(415) 555-0100"));
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE order_id IN "
                + "(SELECT id FROM delivery_order WHERE user_id=?)", Integer.class, user);
    }

    void rejects(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_order WHERE user_id=?", Integer.class, user)).isZero();
    }

    @Test
    void createsSnapshotsAndTwoNonOverlappingBlocksAndReplaysSameOrder() {
        var created = orders.create(user, "one", request());
        assertThat(created.status()).isEqualTo("confirmed");
        assertThat(created.priceCents()).isEqualTo(150);
        assertThat(created.etaAt()).isAfter(created.departsAt());
        assertThat(count("order_item")).isEqualTo(1);
        assertThat(count("order_event")).isEqualTo(1);
        assertThat(count("device_block")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT recipient_phone FROM delivery_order WHERE id=?::uuid", String.class,
                created.orderId())).isEqualTo("+14155550100");
        jdbc.update("UPDATE quote SET expires_at=now()-interval '1 second' WHERE id=?", quote);
        assertThat(orders.create(user, "one", request()).orderId()).isEqualTo(created.orderId());
        assertThat(count("order_item")).isEqualTo(1);
    }

    @Test
    void rejectsExpiredQuote() {
        jdbc.update("UPDATE quote SET expires_at=now()-interval '1 second' WHERE id=?", quote);
        rejects("QUOTE_EXPIRED", () -> orders.create(user, "one", request()));
    }

    @Test
    void rejectsAnotherUsersQuote() {
        rejects("OPTION_NOT_FOUND", () -> orders.create(otherUser, "one", request()));
    }

    @Test
    void rejectsOptionFromDifferentQuote() {
        var bad = new OrderDtos.CreateOrderRequest(UUID.randomUUID().toString(), option.toString(), request().recipient());
        rejects("OPTION_NOT_FOUND", () -> orders.create(user, "one", bad));
    }

    @Test
    void rejectsUnavailableOption() {
        jdbc.update("UPDATE quote_option SET available=false,unavailable_reason='OVERWEIGHT' WHERE id=?", option);
        rejects("DEVICE_UNAVAILABLE", () -> orders.create(user, "one", request()));
    }

    @Test
    void rejectsMissingKeyAndInvalidPhone() {
        rejects("VALIDATION_ERROR", () -> orders.create(user, " ", request()));
        rejects("VALIDATION_ERROR", () -> orders.create(user, "x".repeat(101), request()));
        var bad = new OrderDtos.CreateOrderRequest(quote.toString(), option.toString(), new OrderDtos.Recipient("Ziyue", "abc"));
        rejects("VALIDATION_ERROR", () -> orders.create(user, "one", bad));
    }

    @Test
    void rejectsMissedDeadline() {
        jdbc.update("UPDATE quote SET deadline_at=now()-interval '1 second' WHERE id=?", quote);
        rejects("DEADLINE_MISSED", () -> orders.create(user, "one", request()));
    }

    @Test
    void rejectsStaleDeparture() {
        jdbc.update("UPDATE quote_option SET departs_at=now()-interval '6 minutes' WHERE id=?", option);
        rejects("QUOTE_STALE", () -> orders.create(user, "one", request()));
    }

    @Test
    void concurrentIdenticalRequestsReturnOneOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> task = () -> { start.await(); return orders.create(user, "same", request()).orderId(); };
        try {
            Future<String> a = pool.submit(task), b = pool.submit(task);
            start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
            assertThat(count("order_item")).isEqualTo(1);
            assertThat(count("device_block")).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void skipsDeviceLockedByAnotherTransaction() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.queryForList("SELECT id FROM device WHERE id=? FOR UPDATE", first);
            locked.countDown();
            try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Lock timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(orders.create(user, "one", request()).device().code()).isEqualTo("B-" + second);
        } finally { release.countDown(); holder.get(15, TimeUnit.SECONDS); pool.shutdownNow(); }
    }

    @Test
    void noActiveDeviceLeavesNoPartialOrder() {
        jdbc.update("UPDATE device SET status='offline' WHERE station_id=?", station);
        rejects("DEVICE_UNAVAILABLE", () -> orders.create(user, "one", request()));
    }

    @Test
    void realEndpointRequiresLoginAndKeyThenCreatesOrder() throws Exception {
        String body = mapper.writeValueAsString(request());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        String token = "Bearer " + jwt.issue(user, 0);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .header("Authorization", token).contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .header("Authorization", token).header("Idempotency-Key", "http-test")
                        .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("confirmed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.order_id").exists());
    }

    @Test
    void exclusionConflictRetriesNextDeviceWithoutLeavingPartialRows() {
        // Reproduce PostgreSQL's exclusion error after the order and item have
        // been inserted, so the retry must restore a usable transaction first.
        String function = "order_conflict_" + user.toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.device_id='" + first + "'::uuid THEN RAISE EXCEPTION 'test overlap' "
                + "USING ERRCODE='23P01'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON device_block FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        try {
            assertThat(orders.create(user, "one", request()).device().code()).isEqualTo("B-" + second);
            assertThat(count("order_item")).isEqualTo(1);
            assertThat(count("order_event")).isEqualTo(1);
            assertThat(count("device_block")).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_order WHERE user_id=?", Integer.class, user)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER " + function + " ON device_block");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }

    @Test
    void failedItemInsertRollsBackOrderAndReservation() {
        // Force an insert failure after delivery_order was written, using a temporary
        // database trigger scoped to this test quote's orders.
        String function = "order_test_" + user.toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF EXISTS(SELECT 1 FROM delivery_order WHERE id=NEW.order_id AND user_id='" + user + "'::uuid) "
                + "THEN RAISE EXCEPTION 'test item failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON order_item FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        try {
            assertThatThrownBy(() -> orders.create(user, "one", request())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_order WHERE user_id=?", Integer.class, user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM device_block WHERE device_id IN (?,?)", Integer.class, first, second)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER " + function + " ON order_item");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }
}
