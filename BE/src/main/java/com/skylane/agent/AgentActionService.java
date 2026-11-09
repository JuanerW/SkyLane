package com.skylane.agent;

import com.fasterxml.jackson.databind.*;
import com.skylane.common.ApiException;
import com.skylane.order.OrderService;
import com.skylane.order.OrderDtos.CreateOrderRequest;
import com.skylane.order.OrderDtos.Recipient;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Service @Profile("!memory")
public class AgentActionService {
 public record Response(UUID actionId,String kind,String status,JsonNode result) {}
 private record Pending(UUID id,String kind,String status,JsonNode payload,JsonNode result,Instant expiresAt) {}
 private record Decision(Response response,ApiException error) {}
 private final JdbcTemplate jdbc; private final AgentStore store; private final OrderService orders;
 private final ObjectMapper mapper; private final Clock clock; private final TransactionTemplate tx;
 public AgentActionService(JdbcTemplate jdbc,AgentStore store,OrderService orders,ObjectMapper mapper,
                           Clock clock,PlatformTransactionManager transactions) {
  this.jdbc=jdbc;this.store=store;this.orders=orders;this.mapper=mapper;this.clock=clock;
  this.tx=new TransactionTemplate(transactions);
 }
 public Response decide(UUID user,UUID id,boolean confirm) {
  Decision decision;
  try {
   decision=tx.execute(status->{
    List<Pending> found=jdbc.query("""
     SELECT a.id,a.kind,a.status,a.payload::text,a.result::text,a.expires_at
     FROM agent_action a JOIN conversation c ON c.id=a.conversation_id
     WHERE a.id=? AND c.user_id=? FOR UPDATE OF a
     """,(rs,i)->new Pending(rs.getObject("id",UUID.class),rs.getString("kind"),rs.getString("status"),
       store.parse(rs.getString("payload")),rs.getString("result")==null?null:store.parse(rs.getString("result")),
       rs.getTimestamp("expires_at").toInstant()),id,user);
    if(found.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"ACTION_NOT_FOUND","Action not found");
    Pending action=found.getFirst();
    if(!action.status().equals("pending"))return new Decision(null,notPending());
    if(!action.expiresAt().isAfter(clock.instant())) {
     jdbc.update("UPDATE agent_action SET status='expired' WHERE id=?",id);
     return new Decision(null,notPending());
    }
    if(!confirm) {
     jdbc.update("UPDATE agent_action SET status='rejected' WHERE id=?",id);
     return new Decision(new Response(id,action.kind(),"rejected",null),null);
    }
    JsonNode p=action.payload();
    Object result=switch(action.kind()) {
     case "place_order" -> orders.create(user,id.toString(),new CreateOrderRequest(
       p.path("quote_id").asText(),p.path("option_id").asText(),
       mapper.convertValue(p.path("recipient"),Recipient.class)));
     case "cancel_order" -> orders.cancel(user,UUID.fromString(p.path("order_id").asText()),p.path("reason").asText(""));
     default -> throw new ApiException(HttpStatus.CONFLICT,"ACTION_INVALID","Unsupported action");
    };
    JsonNode saved=mapper.valueToTree(result);
    jdbc.update("UPDATE agent_action SET status='confirmed',result=?::jsonb WHERE id=?",store.json(saved),id);
    return new Decision(new Response(id,action.kind(),"confirmed",saved),null);
   });
  } catch(RuntimeException e) {
   // The execution transaction has rolled back before this independent failure update.
   // Never overwrite a successful/rejected decision from a competing request.
   ApiException failure=e instanceof ApiException api?api:new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"ACTION_FAILED","Action could not be completed");
   if(!failure.code().equals("ACTION_NOT_FOUND")) {
    tx.execute(status->jdbc.update("""
      UPDATE agent_action a SET status='failed',result=?::jsonb FROM conversation c
      WHERE a.conversation_id=c.id AND c.user_id=? AND a.id=? AND a.status='pending'
      """,store.json(Map.of("error",Map.of("code",failure.code(),"message",failure.getMessage()))),user,id));
   }
   throw failure;
  }
  if(decision.error()!=null)throw decision.error();
  return decision.response();
 }
 @Scheduled(fixedDelay=30000)
 public void expirePending() {
  jdbc.update("UPDATE agent_action SET status='expired' WHERE status='pending' AND expires_at<=clock_timestamp()");
 }
 private static ApiException notPending() {
  return new ApiException(HttpStatus.CONFLICT,"ACTION_NOT_PENDING","Action has expired or has already been decided");
 }
}
