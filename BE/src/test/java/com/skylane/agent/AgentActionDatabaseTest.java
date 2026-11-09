package com.skylane.agent;

import com.fasterxml.jackson.databind.*;
import com.skylane.auth.JwtService;
import com.skylane.routing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SKYLANE_TEST_DB",matches=".+")
@ActiveProfiles("dev") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentActionDatabaseTest {
 @TestConfiguration static class Offline {
  @Bean @Primary RoutingClient routing(){return (s,p,d)->OsmRoutingClient.fallback(s,p,d,1.3);}
 }
 @MockBean AgentModel model;
 @Autowired TestRestTemplate rest; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;
 @Autowired JwtService jwt; @Autowired AgentStore store; @Autowired AgentTools tools;
 private UUID user,conversation; private AgentStore.Turn turn;
 @BeforeEach void setup(){
  user=jdbc.queryForObject("INSERT INTO app_user(email,display_name) VALUES(?,'Action test') RETURNING id",UUID.class,UUID.randomUUID()+"@action.test");
  conversation=store.create(user);turn=store.begin(user,conversation,"test action");
 }
 private AgentStore.Action propose(){
  JsonNode quote=tools.execute(turn,"create_quote",store.parse("""
   {"item":{"description":"Cake","fragile":true},"weight_kg":1.5,
    "pickup":{"text":"Ferry Building","lat":37.7955,"lng":-122.3937},
    "dropoff":{"text":"Oracle Park","lat":37.7786,"lng":-122.3893}}
   """)).content();
  JsonNode option=null;for(JsonNode o:quote.path("options"))if(o.path("available").asBoolean() && o.path("device_type").asText().equals("drone")){option=o;break;}
  assertThat(option).isNotNull();
  return tools.execute(turn,"propose_order",mapper.valueToTree(Map.of("quote_id",quote.path("quote_id").asText(),"option_id",option.path("option_id").asText(),"recipient",Map.of("name","Jamie","phone","+14155550100")))).action();
 }
 private HttpHeaders headers(UUID owner){HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.setBearerAuth(jwt.issue(owner,0));return h;}
 private ResponseEntity<String> decide(UUID owner,UUID id,String operation){return rest.postForEntity("/api/v1/agent/actions/"+id+"/"+operation,new HttpEntity<>(headers(owner)),String.class);}
 private int orders(UUID action){return jdbc.queryForObject("SELECT count(*) FROM delivery_order WHERE user_id=? AND idempotency_key=?",Integer.class,user,action.toString());}
 private String state(UUID id){return jdbc.queryForObject("SELECT status FROM agent_action WHERE id=?",String.class,id);}
 @Test void confirmCreatesOneOrderAndPersistsResultForHistory(){
  var a=propose();var result=decide(user,a.actionId(),"confirm");assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
  String order=store.parse(result.getBody()).path("result").path("order_id").asText();
  assertThat(order).isNotBlank();assertThat(orders(a.actionId())).isEqualTo(1);assertThat(state(a.actionId())).isEqualTo("confirmed");
  var history=rest.exchange("/api/v1/agent/conversations/"+conversation,HttpMethod.GET,new HttpEntity<>(headers(user)),String.class);
  assertThat(history.getBody()).contains(order,"confirmed","station_name");
  assertThat(decide(user,a.actionId(),"confirm").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(orders(a.actionId())).isEqualTo(1);
 }
 @Test void sixConcurrentConfirmsHaveOneWinner() throws Exception{
  var a=propose();
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   CountDownLatch start=new CountDownLatch(1);List<Future<ResponseEntity<String>>> results=new ArrayList<>();
   for(int i=0;i<6;i++)results.add(pool.submit(()->{start.await();return decide(user,a.actionId(),"confirm");}));
   start.countDown();int accepted=0,rejected=0;
   for(var f:results){int code=f.get(15,TimeUnit.SECONDS).getStatusCode().value();if(code==200)accepted++;else if(code==409)rejected++;}
   assertThat(accepted).isEqualTo(1);assertThat(rejected).isEqualTo(5);
  }
  assertThat(orders(a.actionId())).isEqualTo(1);assertThat(state(a.actionId())).isEqualTo("confirmed");
 }
 @Test void rejectionNeverPlacesAnOrder(){
  var a=propose();assertThat(decide(user,a.actionId(),"reject").getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(state(a.actionId())).isEqualTo("rejected");assertThat(orders(a.actionId())).isZero();
  assertThat(decide(user,a.actionId(),"confirm").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
 }
 @Test void expiredActionsCannotExecute(){
  var a=propose();jdbc.update("UPDATE agent_action SET expires_at=now()-interval '1 minute' WHERE id=?",a.actionId());
  var res=decide(user,a.actionId(),"confirm");assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  assertThat(res.getBody()).contains("ACTION_NOT_PENDING");assertThat(state(a.actionId())).isEqualTo("expired");assertThat(orders(a.actionId())).isZero();
 }
 @Test void foreignUserCannotConfirmOrReject(){
  var a=propose();UUID other=jdbc.queryForObject("SELECT id FROM app_user WHERE email='bob@skylane.dev'",UUID.class);
  assertThat(decide(other,a.actionId(),"confirm").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  assertThat(decide(other,a.actionId(),"reject").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  assertThat(state(a.actionId())).isEqualTo("pending");assertThat(orders(a.actionId())).isZero();
 }
 @Test void failedOrderExecutionRollsBackAndMarksActionFailed(){
  var a=propose();jdbc.update("UPDATE quote SET expires_at=now()-interval '1 minute' WHERE id=?::uuid",a.payload().path("quote_id").asText());
  var res=decide(user,a.actionId(),"confirm");assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(res.getBody()).contains("QUOTE_EXPIRED");
  assertThat(state(a.actionId())).isEqualTo("failed");assertThat(orders(a.actionId())).isZero();
  assertThat(decide(user,a.actionId(),"confirm").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
 }
 @Test void confirmedCancellationReleasesDeviceBookings(){
  var a=propose();var created=decide(user,a.actionId(),"confirm");String order=store.parse(created.getBody()).path("result").path("order_id").asText();
  // Arrange a scheduled order fixture so cancel timing is deterministic.
  jdbc.update("UPDATE delivery_order SET status='confirmed',departs_at=now()+interval '1 hour' WHERE id=?::uuid",order);
  var cancellation=tools.execute(turn,"cancel_order",mapper.valueToTree(Map.of("order_id",order,"reason","Changed plans"))).action();
  assertThat(jdbc.queryForObject("SELECT status FROM delivery_order WHERE id=?::uuid",String.class,order)).isEqualTo("confirmed");
  var result=decide(user,cancellation.actionId(),"confirm");assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(jdbc.queryForObject("SELECT status FROM delivery_order WHERE id=?::uuid",String.class,order)).isEqualTo("canceled");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM device_block WHERE order_id=?::uuid AND released_at IS NULL",Integer.class,order)).isZero();
  assertThat(decide(user,cancellation.actionId(),"confirm").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
 }
 @Test void confirmAndRejectRaceCannotBothSucceed() throws Exception{
  var a=propose();
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   CountDownLatch start=new CountDownLatch(1);
   var confirm=pool.submit(()->{start.await();return decide(user,a.actionId(),"confirm");});
   var reject=pool.submit(()->{start.await();return decide(user,a.actionId(),"reject");});start.countDown();
   assertThat(List.of(confirm.get(15,TimeUnit.SECONDS).getStatusCode().value(),reject.get(15,TimeUnit.SECONDS).getStatusCode().value())).containsExactlyInAnyOrder(200,409);
  }
  assertThat(orders(a.actionId())).isEqualTo(state(a.actionId()).equals("confirmed")?1:0);
 }
}
