package com.skylane.order;

import com.skylane.auth.JwtService;
import com.skylane.common.ApiException;
import com.skylane.tracking.DeviceSimulator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "SKYLANE_ORDER_TEST_DB", matches = "1")
@SpringBootTest(properties = "skylane.sim.speed=10")
@AutoConfigureMockMvc
@ActiveProfiles("order-test")
class ReviewOrderIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired OrderService orders;
    @Autowired JwtService jwt;
    @MockBean DeviceSimulator simulator;
    UUID user, other, station, device, quote, option, order;
    String token;
    String path() { return "/api/v1/orders/" + order + "/review"; }

    @BeforeEach
    void fixture() {
        user=UUID.randomUUID(); other=UUID.randomUUID(); station=UUID.randomUUID();
        device=UUID.randomUUID(); quote=UUID.randomUUID(); option=UUID.randomUUID(); order=UUID.randomUUID();
        for (UUID id : new UUID[]{user, other}) jdbc.update(
                "INSERT INTO app_user(id,email,display_name) VALUES (?,?,'Review test')", id,id+"@example.test");
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
                INSERT INTO delivery_order(id,user_id,quote_id,option_id,device_id,device_type,status,completed_at,
                    recipient_name,recipient_phone,price_cents,pickup_text,pickup_location,
                    dropoff_text,dropoff_location,route_points,departs_at,eta_at,idempotency_key)
                SELECT ?,user_id,q.id,?,?,'drone','completed',now(),
                    'Ziyue','+14155550100',150,pickup_text,pickup_location,
                    dropoff_text,dropoff_location,'[[37.78,-122.40],[37.79,-122.39]]'::jsonb,
                    now()-interval '10 minutes',now()-interval '5 minutes','review-test' FROM quote q WHERE q.id=?""",
                order,option,device,quote);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM delivery_review WHERE order_id=?",order);
        jdbc.update("DELETE FROM delivery_order WHERE id=?",order);
        jdbc.update("DELETE FROM quote WHERE id=?",quote);
        jdbc.update("DELETE FROM device WHERE id=?",device);
        jdbc.update("DELETE FROM station WHERE id=?",station);
        jdbc.update("DELETE FROM app_user WHERE id IN (?,?)",user,other);
    }

    int reviewCount() { return jdbc.queryForObject("SELECT count(*) FROM delivery_review WHERE order_id=?",Integer.class,order); }

    @Test
    void requiresAuthenticationAndOwnershipForValidRequests() throws Exception {
        mvc.perform(post(path()).contentType("application/json").content("{\"rating\":5}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path()).header("Authorization","Bearer "+jwt.issue(other,0))
                        .contentType("application/json").content("{\"rating\":5}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        for (String id : new String[]{UUID.randomUUID().toString(),"bad-id"}) {
            mvc.perform(post("/api/v1/orders/"+id+"/review").header("Authorization",token)
                            .contentType("application/json").content("{\"rating\":5}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        }
        assertThat(reviewCount()).isZero();
    }

    @Test
    void rejectsEveryNonCompletedStatusWithoutSaving() throws Exception {
        for (String state : new String[]{"confirmed","dispatched","picked_up","in_transit","arrived","canceled","failed"}) {
            jdbc.update("UPDATE delivery_order SET status=? WHERE id=?",state,order);
            mvc.perform(post(path()).header("Authorization",token).contentType("application/json").content("{\"rating\":5}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOT_COMPLETED"));
        }
        assertThat(reviewCount()).isZero();
    }

    @Test
    void acceptsAllFiveIntegerRatingsAndPersistsUserAndNormalizedComment() throws Exception {
        for (int rating=1;rating<=5;rating++) {
            mvc.perform(post(path()).header("Authorization",token).contentType("application/json")
                            .content(mapper.writeValueAsString(java.util.Map.of("rating", rating, "comment", "  Great  "))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.rating").value(rating))
                    .andExpect(jsonPath("$.comment").value("Great"))
                    .andExpect(jsonPath("$.created_at").isString());
            assertThat(jdbc.queryForObject("SELECT user_id FROM delivery_review WHERE order_id=?",UUID.class,order)).isEqualTo(user);
            assertThat(jdbc.queryForObject("SELECT rating FROM delivery_review WHERE order_id=?",Integer.class,order)).isEqualTo(rating);
            jdbc.update("DELETE FROM delivery_review WHERE order_id=?",order);
        }
    }

    @Test
    void optionalAndBlankCommentsAreNullAnd1000CharactersAreAccepted() throws Exception {
        for (String body : new String[]{"{\"rating\":5}","{\"rating\":5,\"comment\":null}","{\"rating\":5,\"comment\":\"   \"}"}) {
            mvc.perform(post(path()).header("Authorization",token).contentType("application/json").content(body))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.comment").value(org.hamcrest.Matchers.nullValue()));
            assertThat(jdbc.queryForObject("SELECT comment FROM delivery_review WHERE order_id=?",String.class,order)).isNull();
            jdbc.update("DELETE FROM delivery_review WHERE order_id=?",order);
        }
        mvc.perform(post(path()).header("Authorization",token).contentType("application/json")
                        .content("{\"rating\":5,\"comment\":\""+"x".repeat(1000)+"\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.comment").value("x".repeat(1000)));
    }

    @Test
    void serviceAlsoRejectsInvalidValuesWithoutDatabaseConstraintErrors() {
        for (int rating : new int[]{0,6,-1,Integer.MAX_VALUE}) {
            assertThatThrownBy(()->orders.review(user,order,rating,null)).isInstanceOfSatisfying(ApiException.class,
                    e->assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        }
        assertThatThrownBy(()->orders.review(user,order,5,"x".repeat(1001))).isInstanceOfSatisfying(ApiException.class,
                e->assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        assertThat(reviewCount()).isZero();
    }

    @Test
    void duplicateReviewReturnsConflictWithoutOverwritingOriginal() throws Exception {
        orders.review(user,order,5,"original");
        mvc.perform(post(path()).header("Authorization",token).contentType("application/json")
                        .content("{\"rating\":1,\"comment\":\"replacement\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ALREADY_REVIEWED"));
        assertThat(reviewCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT comment FROM delivery_review WHERE order_id=?",String.class,order)).isEqualTo("original");
        assertThat(jdbc.queryForObject("SELECT rating FROM delivery_review WHERE order_id=?",Integer.class,order)).isEqualTo(5);
    }

    @Test
    void concurrentReviewsHaveOneWinnerAndOneBusinessConflict() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        Callable<Boolean> task=()->{
            start.await();
            try {orders.review(user,order,5,"concurrent");return true;}
            catch(ApiException e){assertThat(e.code()).isEqualTo("ALREADY_REVIEWED");return false;}
        };
        try {
            Future<Boolean> a=pool.submit(task),b=pool.submit(task); start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)).isNotEqualTo(b.get(10,TimeUnit.SECONDS));
            assertThat(reviewCount()).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test
    void savedReviewIsVisibleInDetailsAndListWithoutChangingOrderState() throws Exception {
        orders.review(user,order,4,"Nice");
        mvc.perform(get("/api/v1/orders/"+order).header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.reviewed").value(true))
                .andExpect(jsonPath("$.review.rating").value(4)).andExpect(jsonPath("$.review.comment").value("Nice"));
        mvc.perform(get("/api/v1/orders").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].order_id").value(order.toString()))
                .andExpect(jsonPath("$[0].reviewed").value(true));
        assertThat(jdbc.queryForObject("SELECT status FROM delivery_order WHERE id=?",String.class,order)).isEqualTo("completed");
    }
}
