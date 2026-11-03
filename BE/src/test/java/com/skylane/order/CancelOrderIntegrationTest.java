package com.skylane.order;

import com.skylane.auth.JwtService;
import com.skylane.common.ApiException;
import com.skylane.tracking.DeviceSimulator;
import com.skylane.tracking.TrackingEventBus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "SKYLANE_ORDER_TEST_DB", matches = "1")
@SpringBootTest(properties = "skylane.sim.speed=10")
@AutoConfigureMockMvc
@ActiveProfiles("order-test")
class CancelOrderIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired OrderService orders;
    @Autowired OrderStateMachine states;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager transactions;
    @MockBean DeviceSimulator simulator;
    @SpyBean TrackingEventBus bus;
    UUID user, other, station, device, quote, option, order;
    String token;

    @BeforeEach
    void fixture() {
        user=UUID.randomUUID(); other=UUID.randomUUID(); station=UUID.randomUUID();
        device=UUID.randomUUID(); quote=UUID.randomUUID(); option=UUID.randomUUID(); order=UUID.randomUUID();
        for (UUID id : new UUID[]{user, other}) jdbc.update(
                "INSERT INTO app_user(id,email,display_name) VALUES (?,?,'Cancel test')", id, id+"@example.test");
        token="Bearer "+jwt.issue(user,0);
        jdbc.update("""
                INSERT INTO station(id,code,name,address_text,location)
                VALUES (?,?,'Test','Station',ST_GeogFromText('POINT(-122.40 37.78)'))""",station,station.toString());
        jdbc.update("INSERT INTO device(id,code,station_id,device_type) VALUES (?,?,?,'drone')",device,device.toString(),station);
        jdbc.update("""
                INSERT INTO quote(id,user_id,item_description,weight_kg,pickup_text,pickup_location,
                    dropoff_text,dropoff_location,expires_at)
                VALUES (?,?,'Cake',1.5,'Pickup',ST_GeogFromText('POINT(-122.40 37.78)'),
                    'Dropoff',ST_GeogFromText('POINT(-122.39 37.79)'),now()+interval '10 minutes')""",quote,user);
        jdbc.update("""
                INSERT INTO quote_option(id,quote_id,station_id,device_type,available,price_cents)
                VALUES (?,?,?,'drone',true,150)""",option,quote,station);
        jdbc.update("""
                INSERT INTO delivery_order(id,user_id,quote_id,option_id,device_id,device_type,
                    recipient_name,recipient_phone,price_cents,pickup_text,pickup_location,
                    dropoff_text,dropoff_location,route_points,departs_at,eta_at,idempotency_key)
                SELECT ?,user_id,q.id,?,?,'drone','Ziyue','+14155550100',150,pickup_text,pickup_location,
                    dropoff_text,dropoff_location,'[[37.78,-122.40],[37.79,-122.39]]'::jsonb,
                    now()+interval '5 minutes',now()+interval '10 minutes','cancel-test' FROM quote q WHERE q.id=?""",
                order,option,device,quote);
        jdbc.update("""
                INSERT INTO device_block(device_id,order_id,kind,starts_at,ends_at)
                SELECT device_id,id,'order',departs_at,eta_at FROM delivery_order WHERE id=?
                UNION ALL SELECT device_id,id,'return',eta_at,eta_at+interval '5 minutes' FROM delivery_order WHERE id=?""",order,order);
        jdbc.update("INSERT INTO order_event(order_id,status,actor_type,actor_id) VALUES (?,'confirmed','user',?)",order,user);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM device_block WHERE device_id=?",device);
        jdbc.update("DELETE FROM order_event WHERE order_id IN (SELECT id FROM delivery_order WHERE user_id=?)",user);
        jdbc.update("DELETE FROM delivery_order WHERE user_id=?",user);
        jdbc.update("DELETE FROM quote WHERE id=?",quote);
        jdbc.update("DELETE FROM device WHERE id=?",device);
        jdbc.update("DELETE FROM station WHERE id=?",station);
        jdbc.update("DELETE FROM app_user WHERE id IN (?,?)",user,other);
    }

    String statusOfOrder() { return jdbc.queryForObject("SELECT status FROM delivery_order WHERE id=?",String.class,order); }
    int activeBlocks() { return jdbc.queryForObject("SELECT count(*) FROM device_block WHERE order_id=? AND released_at IS NULL",Integer.class,order); }
    int canceledEvents() { return jdbc.queryForObject("SELECT count(*) FROM order_event WHERE order_id=? AND status='canceled'",Integer.class,order); }
    void rejectsCancellation() {
        assertThatThrownBy(()->orders.cancel(user,order,null)).isInstanceOfSatisfying(ApiException.class,
                e->assertThat(e.code()).isEqualTo("NOT_CANCELLABLE"));
        assertThat(activeBlocks()).isEqualTo(2);
        assertThat(canceledEvents()).isZero();
    }

    @Test
    void successSavesReasonAndTimeReleasesBothBlocksAndPublishesAfterCommit() throws Exception {
        doAnswer(invocation->{
            // A new transaction/connection must already see all cancellation effects.
            ExecutorService observer=Executors.newSingleThreadExecutor();
            try {
                observer.submit(()->{
                    assertThat(statusOfOrder()).isEqualTo("canceled");
                    assertThat(activeBlocks()).isZero();
                    assertThat(jdbc.queryForObject("SELECT cancel_reason FROM delivery_order WHERE id=?",String.class,order))
                            .isEqualTo("Plans changed");
                }).get(10,TimeUnit.SECONDS);
            } finally { observer.shutdownNow(); }
            return invocation.callRealMethod();
        }).when(bus).publishStatus(eq(order),anyLong(),eq("canceled"),any());
        mvc.perform(post("/api/v1/orders/"+order+"/cancel").header("Authorization",token)
                        .contentType("application/json").content("{\"reason\":\"  Plans changed  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("canceled"));
        assertThat(canceledEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT canceled_at IS NOT NULL FROM delivery_order WHERE id=?",Boolean.class,order)).isTrue();
        assertThat(jdbc.queryForObject("SELECT actor_id FROM order_event WHERE order_id=? AND status='canceled'",UUID.class,order)).isEqualTo(user);
        verify(bus,times(1)).publishStatus(eq(order),anyLong(),eq("canceled"),any());
    }

    @Test
    void acceptsAbsentBodyAndBlankReasonAsNull() throws Exception {
        mvc.perform(post("/api/v1/orders/"+order+"/cancel").header("Authorization",token)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM delivery_order WHERE id=?",String.class,order)).isNull();
    }

    @Test
    void blankReasonIsNormalized() {
        orders.cancel(user,order,"   ");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM delivery_order WHERE id=?",String.class,order)).isNull();
    }

    @Test
    void requiresLoginAndHidesOtherUsersOrInvalidOrders() throws Exception {
        mvc.perform(post("/api/v1/orders/"+order+"/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/orders/"+order+"/cancel").header("Authorization","Bearer "+jwt.issue(other,0)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        for(String id:new String[]{UUID.randomUUID().toString(),"bad-id"}) {
            mvc.perform(post("/api/v1/orders/"+id+"/cancel").header("Authorization",token))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        }
        assertThat(statusOfOrder()).isEqualTo("confirmed");
        assertThat(activeBlocks()).isEqualTo(2);
    }

    @Test
    void validatesReasonLengthBeforeChangingAnything() throws Exception {
        mvc.perform(post("/api/v1/orders/"+order+"/cancel").header("Authorization",token)
                        .contentType("application/json").content("{\"reason\":\""+"x".repeat(201)+"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(statusOfOrder()).isEqualTo("confirmed");
        assertThat(activeBlocks()).isEqualTo(2);
    }

    @Test
    void rejectsAlreadyDepartedAndEveryNonConfirmedStatus() {
        jdbc.update("UPDATE delivery_order SET departs_at=clock_timestamp() WHERE id=?",order);
        rejectsCancellation();
        for(String state:new String[]{"dispatched","picked_up","in_transit","arrived","completed","canceled","failed"}) {
            jdbc.update("UPDATE delivery_order SET status=?,departs_at=now()+interval '5 minutes' WHERE id=?",state,order);
            rejectsCancellation();
            assertThat(statusOfOrder()).isEqualTo(state);
        }
        verify(bus,never()).publishStatus(any(),anyLong(),anyString(),any());
    }

    @Test
    void repeatedCancelCreatesOnlyOneEvent() throws Exception {
        orders.cancel(user,order,"first");
        mvc.perform(post("/api/v1/orders/"+order+"/cancel").header("Authorization",token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOT_CANCELLABLE"));
        assertThat(canceledEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM delivery_order WHERE id=?",String.class,order)).isEqualTo("first");
        verify(bus,times(1)).publishStatus(eq(order),anyLong(),eq("canceled"),any());
    }

    @Test
    void concurrentCancelsHaveOneWinner() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        Callable<Boolean> task=()->{
            start.await();
            try { orders.cancel(user,order,null); return true; }
            catch(ApiException e) { assertThat(e.code()).isEqualTo("NOT_CANCELLABLE"); return false; }
        };
        try {
            Future<Boolean> a=pool.submit(task), b=pool.submit(task); start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)).isNotEqualTo(b.get(10,TimeUnit.SECONDS));
            assertThat(canceledEvents()).isEqualTo(1);
            assertThat(activeBlocks()).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test
    void departureAndCancellationCannotBothWin() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Boolean> cancel=pool.submit(()->{start.await();try {orders.cancel(user,order,null);return true;}
                catch(ApiException e){assertThat(e.code()).isEqualTo("NOT_CANCELLABLE");return false;}});
            Future<Boolean> dispatch=pool.submit(()->{start.await();return states.transition(order,"confirmed","dispatched",Instant.now(),"device");});
            start.countDown();
            boolean canceled=cancel.get(10,TimeUnit.SECONDS), dispatched=dispatch.get(10,TimeUnit.SECONDS);
            assertThat(canceled).isNotEqualTo(dispatched);
            assertThat(statusOfOrder()).isEqualTo(canceled?"canceled":"dispatched");
            assertThat(activeBlocks()).isEqualTo(canceled?0:2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_event WHERE order_id=? AND status IN ('canceled','dispatched')",Integer.class,order)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test
    void lockWaitCannotExtendCancellationWindow() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        Future<?>[] pending=new Future<?>[1];
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForList("SELECT id FROM delivery_order WHERE id=? FOR UPDATE",order);
                pending[0]=pool.submit(()->assertThatThrownBy(()->orders.cancel(user,order,null))
                        .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("NOT_CANCELLABLE")));
                await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(()->jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                        AND wait_event_type='Lock' AND query LIKE '%SELECT status FROM delivery_order%'""",Integer.class)>0);
                jdbc.update("UPDATE delivery_order SET departs_at=clock_timestamp()+interval '200 milliseconds' WHERE id=?",order);
                await().pollInSameThread().atMost(Duration.ofSeconds(5)).until(()->jdbc.queryForObject(
                        "SELECT clock_timestamp()>departs_at FROM delivery_order WHERE id=?",Boolean.class,order));
            });
            pending[0].get(10,TimeUnit.SECONDS);
            assertThat(statusOfOrder()).isEqualTo("confirmed");
            assertThat(activeBlocks()).isEqualTo(2);
            assertThat(canceledEvents()).isZero();
        } finally {pool.shutdownNow();}
    }

    @Test
    void laterReservationsKeepTheirOriginalTimes() {
        UUID later=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO device_block(id,device_id,kind,starts_at,ends_at)
                VALUES (?,?,'maintenance',now()+interval '20 minutes',now()+interval '30 minutes')""",later,device);
        var before=jdbc.queryForMap("SELECT starts_at,ends_at,released_at FROM device_block WHERE id=?",later);
        orders.cancel(user,order,null);
        assertThat(jdbc.queryForMap("SELECT starts_at,ends_at,released_at FROM device_block WHERE id=?",later)).isEqualTo(before);
    }

    String installFailure(String table, String condition) {
        String name="cancel_fail_"+user.toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "+condition+
                " THEN RAISE EXCEPTION 'injected failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+name+" BEFORE INSERT OR UPDATE ON "+table+" FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        return name;
    }
    void removeFailure(String table,String name) {
        jdbc.execute("DROP TRIGGER "+name+" ON "+table); jdbc.execute("DROP FUNCTION "+name+"()");
    }

    @Test
    void releaseFailureRollsBackStatusReasonEventAndNotification() {
        String trigger=installFailure("device_block","NEW.order_id='"+order+"'::uuid AND NEW.released_at IS NOT NULL");
        try {
            assertThatThrownBy(()->orders.cancel(user,order,"changed")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(statusOfOrder()).isEqualTo("confirmed");
            assertThat(activeBlocks()).isEqualTo(2);
            assertThat(canceledEvents()).isZero();
            assertThat(jdbc.queryForObject("SELECT canceled_at IS NULL AND cancel_reason IS NULL FROM delivery_order WHERE id=?",Boolean.class,order)).isTrue();
            verify(bus,never()).publishStatus(any(),anyLong(),anyString(),any());
        } finally {removeFailure("device_block",trigger);}
    }

    @Test
    void simulatorTransitionRollsBackIfTimelineInsertFails() {
        String trigger=installFailure("order_event","NEW.order_id='"+order+"'::uuid AND NEW.status='dispatched'");
        try {
            assertThatThrownBy(()->states.transition(order,"confirmed","dispatched",Instant.now(),"device"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(statusOfOrder()).isEqualTo("confirmed");
            verify(bus,never()).publishStatus(any(),anyLong(),anyString(),any());
        } finally {removeFailure("order_event",trigger);}
    }
}
