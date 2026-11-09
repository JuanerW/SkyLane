package com.skylane.agent;
import com.fasterxml.jackson.databind.*;
import com.skylane.auth.JwtService;
import com.skylane.common.ApiException;
import com.skylane.routing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="SKYLANE_TEST_DB",matches=".+")
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"skylane.agent.daily-messages=3"})
class AgentDatabaseTest {
 @TestConfiguration static class Offline {
  @Bean @Primary RoutingClient routing(){return (s,p,d)->OsmRoutingClient.fallback(s,p,d,1.3);}
 }
 @LocalServerPort int port;
 @Autowired TestRestTemplate rest;
 @Autowired JdbcTemplate jdbc;
 @Autowired ObjectMapper mapper;
 @Autowired JwtService jwt;
 @Autowired AgentStore store;
 @Autowired AgentTools tools;
 @Autowired AgentProperties props;
 @Autowired com.skylane.quote.QuoteService quoteService;
 @Autowired com.skylane.order.OrderService orderService;
 @MockBean AgentModel model;
 @MockBean AddressSearch address;
 private UUID user;
 @BeforeEach void setup(){
  user=newUser();
  when(model.stream(anyList(),any(),any())).thenAnswer(call->{
   AgentModel.Reply value=model.reply(call.getArgument(0),call.getArgument(1));
   java.util.function.Consumer<String> consumer=call.getArgument(2);
   value.content().stream().filter(b->b.path("type").asText().equals("text")).forEach(b->consumer.accept(b.path("text").asText()));
   return value;
  });
  when(model.reply(anyList(),any())).thenReturn(reply(text("Hello from SkyLane")));
 }
 private UUID newUser(){return jdbc.queryForObject("INSERT INTO app_user(email,display_name) VALUES(?,?) RETURNING id",UUID.class,UUID.randomUUID()+"@agent.test","Test");}
 private AgentModel.Reply reply(JsonNode... blocks){return new AgentModel.Reply(List.of(blocks),Arrays.stream(blocks).anyMatch(b->b.path("type").asText().equals("tool_use"))?"tool_use":"end_turn",20,10);}
 private JsonNode text(String value){return mapper.valueToTree(Map.of("type","text","text",value));}
 private JsonNode tool(String name,Object input){return mapper.valueToTree(Map.of("type","tool_use","id","tool_"+UUID.randomUUID(),"name",name,"input",input));}
 private HttpHeaders headers(UUID owner){
  HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.setBearerAuth(jwt.issue(owner,0));return h;
 }
 private UUID create(){
  var res=rest.postForEntity("/api/v1/agent/conversations",new HttpEntity<>(headers(user)),String.class);
  assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  return UUID.fromString(store.parse(res.getBody()).path("conversation_id").asText());
 }
 private ResponseEntity<String> send(UUID owner,UUID c,String value){
  return rest.postForEntity("/api/v1/agent/conversations/"+c+"/messages",new HttpEntity<>(Map.of("text",value),headers(owner)),String.class);
 }
 private void successful(ResponseEntity<String> res){
  assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(res.getBody()).contains("event:done","\"success\":true");
 }
 @Test void orderQueriesGetFreshOwnedDataEvenAfterAnIncorrectEarlierAnswer() throws Exception {
  UUID c=create();
  var old=store.begin(user,c,"earlier question");store.message(old,"assistant",List.of(text("There are no orders.")));store.finish(old,"complete","");
  var request=mapper.readValue("""
   {"item":{"description":"Test delivery","fragile":false},"weight_kg":1.0,
    "pickup":{"text":"Ferry Building","lat":37.7955,"lng":-122.3937},
    "dropoff":{"text":"Oracle Park","lat":37.7786,"lng":-122.3893}}
   """,com.skylane.quote.QuoteRequest.class);
  var quote=quoteService.createQuote(user,request);
  var option=quote.options().stream().filter(o->o.available() && o.deviceType().equals("drone")).findFirst().orElseThrow();
  var order=orderService.create(user,UUID.randomUUID().toString(),
   new com.skylane.order.OrderDtos.CreateOrderRequest(quote.quoteId(),option.optionId(),new com.skylane.order.OrderDtos.Recipient("Test","+14155550100")));
  when(model.reply(anyList(),any())).thenAnswer(call->{
   List<JsonNode> messages=call.getArgument(0);
   JsonNode result=messages.getLast().path("content").get(0);
   assertThat(result.path("type").asText()).isEqualTo("tool_result");assertThat(result.path("is_error").asBoolean()).isFalse();
   JsonNode orders=store.parse(result.path("content").asText());
   assertThat(orders.size()).isEqualTo(1);assertThat(orders.get(0).path("order_id").asText()).isEqualTo(order.orderId());
   return reply(text("Found your order "+order.orderId()));
  });
  for(String query:List.of("Show my recent orders","查看我的订单")){
   var response=send(user,c,query);successful(response);assertThat(response.getBody()).contains(order.orderId());
  }
  assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_message WHERE conversation_id=? AND role='tool'",Integer.class,c)).isEqualTo(2);
 }
 @Test void realHttpPersistsConversationAndSseReply(){
  UUID c=create();var res=send(user,c,"hello");successful(res);assertThat(res.getBody()).contains("event:delta","Hello from SkyLane");
  var history=rest.exchange("/api/v1/agent/conversations/"+c,HttpMethod.GET,new HttpEntity<>(headers(user)),String.class);
  assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(history.getBody()).contains("Hello from SkyLane","hello");
  assertThat(jdbc.queryForObject("SELECT sum(input_tokens+output_tokens) FROM conversation_message WHERE conversation_id=?",Integer.class,c)).isEqualTo(30);
 }
 @Test void deniesForeignConversationAndUnauthenticatedRequests(){
  UUID c=create();UUID other=newUser();
  assertThat(send(other,c,"hello").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  assertThat(rest.exchange("/api/v1/agent/conversations/"+c,HttpMethod.GET,new HttpEntity<>(headers(other)),String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  try {
   var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/v1/agent/conversations"))
     .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
   var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
   assertThat(response.statusCode()).isEqualTo(401);
  } catch(Exception e) {throw new RuntimeException(e);}
  verify(model,never()).reply(anyList(),any());
 }
 @Test void validatesBlankMessagesAndBusyConversations(){
  UUID c=create();
  assertThat(send(user,c," ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  var turn=store.begin(user,c,"in progress");
  assertThat(send(user,c,"another message").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  store.finish(turn,"failed","TEST");
 }
 @Test void messageQuotaIsPerUserAcrossConversations(){
  for(int i=0;i<3;i++)successful(send(user,create(),"hello"));
  var fourth=send(user,create(),"hello");
  assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  assertThat(fourth.getBody()).contains("RATE_LIMITED");
 }
 @Test void insufficientTokenBudgetFailsBeforeSseStarts(){
  UUID c=create();var prior=store.begin(user,c,"prior usage");store.reserve(prior,99000);store.finish(prior,"failed","TEST");
  var res=send(user,c,"hello");
  assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);assertThat(res.getBody()).contains("RATE_LIMITED");
  verify(model,never()).reply(anyList(),any());
 }
 @Test void concurrentTokenReservationsCannotOverspend(){
  var a=store.begin(user,create(),"a");var b=store.begin(user,create(),"b");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
   for(var turn:List.of(a,b))results.add(pool.submit(()->{start.await();try{store.reserve(turn,70000);return true;}catch(ApiException e){assertThat(e.code()).isEqualTo("RATE_LIMITED");return false;}}));
   start.countDown();int accepted=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))accepted++;
   assertThat(accepted).isEqualTo(1);
  }catch(Exception e){throw new RuntimeException(e);}
 }
 @Test void addressQuoteAndOrderProposalUseToolsWithoutPlacingOrder(){
  UUID c=create();int before=jdbc.queryForObject("SELECT count(*) FROM delivery_order",Integer.class);
  AtomicInteger round=new AtomicInteger();
  when(address.search(anyString())).thenReturn(mapper.valueToTree(Map.of("places",List.of(Map.of("text","Ferry Building","lat",37.7955,"lng",-122.3937)))));
  when(model.reply(anyList(),any())).thenAnswer(call->{
   List<JsonNode> messages=call.getArgument(0);
   return switch(round.getAndIncrement()){
    case 0 -> reply(tool("search_address",Map.of("query","Ferry Building")));
    case 1 -> reply(tool("create_quote",store.parse("""
     {"item":{"description":"Cake","fragile":true},"weight_kg":1.5,
      "pickup":{"text":"Ferry Building","lat":37.7955,"lng":-122.3937},
      "dropoff":{"text":"Oracle Park","lat":37.7786,"lng":-122.3893}}
     """)));
    case 2 -> {
     JsonNode result=store.parse(messages.getLast().path("content").get(0).path("content").asText());
     assertThat(messages.getLast().path("role").asText()).isEqualTo("user");
     assertThat(result.path("options").get(0).has("route_points")).isFalse();
     JsonNode option=null;for(JsonNode o:result.path("options"))if(o.path("available").asBoolean()){option=o;break;}
     assertThat(option).isNotNull();
     yield reply(tool("propose_order",Map.of("quote_id",result.path("quote_id").asText(),"option_id",option.path("option_id").asText(),
      "recipient",Map.of("name","Jamie","phone","+14155550100"))));
    }
    default -> reply(text("Please confirm the pending delivery card."));
   };
  });
  var res=send(user,c,"Please quote and propose this delivery");successful(res);
  assertThat(res.getBody()).contains("event:action","place_order","pending");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_action WHERE conversation_id=? AND status='pending'",Integer.class,c)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_order",Integer.class)).isEqualTo(before);
 }
 @Test void foreignOrderAndUnknownToolsBecomeToolErrors(){
  UUID c=create();
  UUID foreign=jdbc.queryForObject("SELECT id FROM delivery_order LIMIT 1",UUID.class);
  AtomicInteger round=new AtomicInteger();
  when(model.reply(anyList(),any())).thenAnswer(call->{
   List<JsonNode> messages=call.getArgument(0);
   if(round.getAndIncrement()==0)return reply(tool("get_order",Map.of("order_id",foreign)),tool("execute_sql",Map.of()));
   assertThat(messages.getLast().toString()).contains("ORDER_NOT_FOUND","TOOL_NOT_ALLOWED","is_error");
   return reply(text("That order is unavailable."));
  });
  successful(send(user,c,"get an order"));
 }
 @Test void failedTurnIsExcludedFromNextModelContextAndUsageRemainsCharged(){
  UUID c=create();
  when(model.reply(anyList(),any())).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_UNAVAILABLE","offline"));
  var failed=send(user,c,"failed private text");
  assertThat(failed.getBody()).contains("event:error","MODEL_UNAVAILABLE","\"success\":false");
  assertThat(jdbc.queryForObject("SELECT sum(input_tokens) FROM conversation_message WHERE conversation_id=?",Integer.class,c)).isGreaterThan(1000);
  doAnswer(call->{
   List<JsonNode> messages=call.getArgument(0);
   assertThat(messages).hasSize(1);assertThat(messages.getFirst().toString()).doesNotContain("failed private text");
   return reply(text("Recovered"));
  }).when(model).reply(anyList(),any());
  successful(send(user,c,"new request"));
 }
 @Test void cancellationOnlyCreatesAPendingAction(){
  UUID alice=jdbc.queryForObject("SELECT id FROM app_user WHERE email='alice@skylane.dev'",UUID.class);
  UUID c=store.create(alice);var turn=store.begin(alice,c,"cancel");
  UUID order=jdbc.queryForObject("SELECT id FROM delivery_order WHERE user_id=? LIMIT 1",UUID.class,alice);
  jdbc.update("UPDATE delivery_order SET status='confirmed',departs_at=now()+interval '1 hour' WHERE id=?",order);
  var action=tools.execute(turn,"cancel_order",mapper.valueToTree(Map.of("order_id",order))).action();
  assertThat(action.kind()).isEqualTo("cancel_order");assertThat(action.status()).isEqualTo("pending");
  assertThat(jdbc.queryForObject("SELECT status FROM delivery_order WHERE id=?",String.class,order)).isEqualTo("confirmed");
  store.finish(turn,"complete","");
 }
 @Test void rejectsMalformedToolInputAndIdentityOverrides(){
  UUID c=create();var turn=store.begin(user,c,"invalid tool inputs");
  for(var input:List.<JsonNode>of(mapper.valueToTree(Map.of("limit","abc")),mapper.valueToTree(Map.of("user_id",UUID.randomUUID()))))
   assertThatThrownBy(()->tools.execute(turn,"list_orders",input)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
  store.finish(turn,"failed","TEST");
 }
 @Test void concurrentMessagesRespectTheSharedDailyLimit(){
  List<UUID> conversations=List.of(create(),create(),create(),create());
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
   for(var c:conversations)results.add(pool.submit(()->{start.await();try{store.begin(user,c,"hello");return true;}catch(ApiException e){assertThat(e.code()).isEqualTo("RATE_LIMITED");return false;}}));
   start.countDown();int count=0;for(var r:results)if(r.get(10,TimeUnit.SECONDS))count++;
   assertThat(count).isEqualTo(3);
  }catch(Exception e){throw new RuntimeException(e);}
 }
 @Test void loopLimitStopsRepeatedToolCalls(){
  UUID c=create();when(model.reply(anyList(),any())).thenAnswer(call->reply(tool("list_orders",Map.of("limit",1))));
  var res=send(user,c,"repeat");
  assertThat(res.getBody()).contains("TOOL_ROUND_LIMIT","\"success\":false");
  verify(model,times(props.maxRounds())).reply(anyList(),any());
 }
}
