package com.skylane.agent;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skylane.common.ApiException;
import com.skylane.order.OrderService;
import com.skylane.order.OrderDtos.Recipient;
import com.skylane.quote.*;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component @Profile("!memory")
public class AgentTools {
 public record Outcome(JsonNode content,AgentStore.Action action){}
 private final QuoteService quotes;private final OrderService orders;private final AddressSearch address;
 private final AgentStore store;private final ObjectMapper mapper;private final Validator validator;
 private final JdbcTemplate jdbc;private final Clock clock;private final AgentProperties props;
 public AgentTools(QuoteService quotes,OrderService orders,AddressSearch address,AgentStore store,
    ObjectMapper mapper,Validator validator,JdbcTemplate jdbc,Clock clock,AgentProperties props){
  this.quotes=quotes;this.orders=orders;this.address=address;this.store=store;
  this.mapper=mapper;this.validator=validator;this.jdbc=jdbc;this.clock=clock;this.props=props;
 }
 public Outcome execute(AgentStore.Turn turn,String name,JsonNode input){
  if(!input.isObject())throw invalid();
  JsonNode definition=null;
  for(JsonNode tool:definitions())if(tool.path("name").asText().equals(name))definition=tool.path("input_schema");
  if(definition==null)throw new ApiException(HttpStatus.BAD_REQUEST,"TOOL_NOT_ALLOWED","Unknown assistant tool");
  var fields=input.fieldNames();
  while(fields.hasNext())if(!definition.path("properties").has(fields.next()))throw invalid();
  for(JsonNode required:definition.path("required"))if(!input.hasNonNull(required.asText()))throw invalid();
  return switch(name){
   case "search_address" -> new Outcome(address.search(text(input,"query",200)),null);
   case "create_quote" -> {
    QuoteRequest request;
    try{request=mapper.treeToValue(input,QuoteRequest.class);}catch(Exception e){throw invalid();}
    validate(request);
    JsonNode result=mapper.valueToTree(quotes.createQuote(turn.userId(),request));
    result.path("options").forEach(o->((ObjectNode)o).remove("route_points"));
    yield new Outcome(result,null);
   }
   case "get_order" -> {
    ObjectNode result=mapper.valueToTree(orders.get(turn.userId(),uuid(input,"order_id")));
    result.remove("route_points");yield new Outcome(result,null);
   }
   case "list_orders" -> {
    if(input.has("limit") && (!input.path("limit").isIntegralNumber() || !input.path("limit").canConvertToInt()))throw invalid();
    int limit=input.path("limit").asInt(10);if(limit<1 || limit>20)throw invalid();
    yield new Outcome(mapper.valueToTree(orders.list(turn.userId(),limit)),null);
   }
   case "propose_order" -> {
    UUID quote=uuid(input,"quote_id"),option=uuid(input,"option_id");
    Recipient recipient;
    try{recipient=mapper.treeToValue(input.path("recipient"),Recipient.class);}catch(Exception e){throw invalid();}
    validate(recipient);
    if(!recipient.phone().matches("^\\+[1-9][0-9]{7,14}$"))throw invalid();
    List<Instant> expiry=jdbc.query("""
     SELECT q.expires_at FROM quote q JOIN quote_option o ON o.quote_id=q.id
     WHERE q.id=? AND q.user_id=? AND o.id=? AND o.available=true
     """,(rs,i)->rs.getTimestamp(1).toInstant(),quote,turn.userId(),option);
    if(expiry.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"OPTION_NOT_FOUND","Available owned option not found");
    if(!expiry.getFirst().isAfter(clock.instant()))throw new ApiException(HttpStatus.CONFLICT,"QUOTE_EXPIRED","Refresh delivery options");
    var summaries=jdbc.query("""
     SELECT s.name AS station_name,o.device_type,o.price_cents,o.departs_at,o.eta_at,
            q.pickup_text,q.dropoff_text,q.item_description,q.weight_kg
     FROM quote q JOIN quote_option o ON o.quote_id=q.id JOIN station s ON s.id=o.station_id
     WHERE q.id=? AND q.user_id=? AND o.id=?
     """,(rs,i)->Map.of("station_name",rs.getString("station_name"),"device_type",rs.getString("device_type"),
       "price_cents",rs.getInt("price_cents"),"departs_at",rs.getTimestamp("departs_at").toInstant(),
       "eta_at",rs.getTimestamp("eta_at").toInstant(),"pickup_text",rs.getString("pickup_text"),
       "dropoff_text",rs.getString("dropoff_text"),"item_description",rs.getString("item_description"),
       "weight_kg",rs.getBigDecimal("weight_kg")),quote,turn.userId(),option);
    JsonNode payload=mapper.valueToTree(Map.of("quote_id",quote,"option_id",option,"recipient",recipient,"summary",summaries.getFirst()));
    Instant expires=clock.instant().plusSeconds(props.actionTtlMinutes()*60L);
    if(expiry.getFirst().isBefore(expires))expires=expiry.getFirst();
    var action=store.action(turn,"place_order",payload,expires);
    yield new Outcome(mapper.valueToTree(action),action);
   }
   case "cancel_order" -> {
    UUID order=uuid(input,"order_id");
    var detail=orders.get(turn.userId(),order);
    if(!detail.cancellable())
     throw new ApiException(HttpStatus.CONFLICT,"NOT_CANCELLABLE","Order can no longer be canceled");
    if(input.has("reason") && !input.path("reason").isTextual())throw invalid();
    String reason=input.path("reason").asText("");if(reason.length()>200)throw invalid();
    var action=store.action(turn,"cancel_order",mapper.valueToTree(Map.of("order_id",order,"reason",reason,"summary",Map.of("pickup_text",detail.pickupText(),"dropoff_text",detail.dropoffText(),"recipient_name",detail.recipientName()))),
      clock.instant().plusSeconds(props.actionTtlMinutes()*60L));
    yield new Outcome(mapper.valueToTree(action),action);
   }
   default -> throw new ApiException(HttpStatus.BAD_REQUEST,"TOOL_NOT_ALLOWED","Unknown assistant tool");
  };
 }
 private <T> void validate(T value){if(value==null || !validator.validate(value).isEmpty())throw invalid();}
 private static String text(JsonNode n,String field,int max){
  JsonNode v=n.path(field);if(!v.isTextual() || v.asText().isBlank() || v.asText().length()>max)throw invalid();return v.asText();
 }
 private static UUID uuid(JsonNode n,String field){try{return UUID.fromString(text(n,field,36));}catch(IllegalArgumentException e){throw invalid();}}
 private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid tool input");}
 public JsonNode definitions(){
  try{return mapper.readTree(AgentTools.class.getResourceAsStream("/agent-tools.json"));}
  catch(Exception e){throw new IllegalStateException("Missing assistant tool definitions",e);}
 }
}
