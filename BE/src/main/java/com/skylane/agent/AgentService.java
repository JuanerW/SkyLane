package com.skylane.agent;
import com.fasterxml.jackson.databind.*;
import com.skylane.common.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service @Profile("!memory")
public class AgentService {
 private final AgentStore store;private final AgentModel model;private final AgentTools tools;
 private final AgentProperties props;private final ObjectMapper mapper;
 private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
 private final Semaphore slots=new Semaphore(8);
 private final Set<SseEmitter> activeEmitters=ConcurrentHashMap.newKeySet();
 public AgentService(AgentStore store,AgentModel model,AgentTools tools,AgentProperties props,ObjectMapper mapper){
  this.store=store;this.model=model;this.tools=tools;this.props=props;this.mapper=mapper;
 }
 @PreDestroy public void close(){activeEmitters.forEach(SseEmitter::complete);executor.shutdownNow();}
 @org.springframework.scheduling.annotation.Scheduled(fixedDelay=15000)
 public void heartbeat(){
  for(var emitter:activeEmitters){
   try{emitter.send(SseEmitter.event().comment("ping"));}
   catch(Exception e){activeEmitters.remove(emitter);emitter.complete();}
  }
 }
 public SseEmitter open(UUID user,UUID conversation,String text){
  store.requireOwned(user,conversation);model.checkReady();
  if(!slots.tryAcquire())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AGENT_BUSY","Assistant is busy, try again shortly");
  AgentStore.Turn turn;
  try{turn=store.begin(user,conversation,text);}catch(RuntimeException e){slots.release();throw e;}
  List<JsonNode> context;
  JsonNode definitions;
  long firstReservation;
  try {
   context=new ArrayList<>(store.context(turn));definitions=tools.definitions();
   ((com.fasterxml.jackson.databind.node.ArrayNode)context.getLast().path("content")).add(
     mapper.valueToTree(Map.of("type","text","text","Server action state snapshot (data): "+store.json(store.actionContext(conversation)))));
   if(OrderQueryPolicy.requestsList(text))prefetchOrders(turn,context);
   firstReservation=reserve(turn,context,definitions);
  } catch(RuntimeException e) {
   store.finish(turn,"failed",e instanceof ApiException api?api.code():"AGENT_FAILED");
   slots.release();throw e;
  }
  SseEmitter emitter=new SseEmitter((props.maxRounds()*props.timeoutSeconds()+110L)*1000);
  AtomicBoolean closed=new AtomicBoolean();
  activeEmitters.add(emitter);
  Runnable remove=()->{closed.set(true);activeEmitters.remove(emitter);};
  emitter.onCompletion(remove);emitter.onTimeout(remove);emitter.onError(e->remove.run());
  executor.submit(()->{
   try{
    run(turn,context,definitions,firstReservation,(event,data)->{
     if(closed.get())throw new CancellationException();
     try{emitter.send(SseEmitter.event().name(event).data(data));}catch(Exception e){throw new CancellationException();}
    });
    store.finish(turn,"complete","");
    emitter.send(SseEmitter.event().name("done").data(Map.of("turn_id",turn.id(),"success",true)));
   }catch(Exception e){
    String code=e instanceof ApiException api?api.code():e instanceof CancellationException?"CLIENT_DISCONNECTED":"AGENT_FAILED";
    store.finish(turn,"failed",code);
    try{
     emitter.send(SseEmitter.event().name("error").data(Map.of("error",Map.of("code",code,"message","Assistant could not finish this reply","details",Map.of()))));
     emitter.send(SseEmitter.event().name("done").data(Map.of("turn_id",turn.id(),"success",false)));
    }catch(Exception ignored){}
   }finally{closed.set(true);activeEmitters.remove(emitter);emitter.complete();slots.release();}
  });
  return emitter;
 }
 @FunctionalInterface interface Events{void send(String event,Object data);}
 void run(AgentStore.Turn turn,List<JsonNode> messages,JsonNode definitions,long firstReservation,Events events){
  int toolCalls=0;
  for(int round=0;round<props.maxRounds();round++){
   events.send("delta",Map.of("text",""));
   long reservation=round==0?firstReservation:reserve(turn,messages,definitions);
   AgentModel.Reply reply=model.stream(messages,definitions,text->events.send("delta",Map.of("text",text)));
   store.settle(reservation,reply.inputTokens(),reply.outputTokens());
   if(reply.stopReason().equals("max_tokens"))throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_OUTPUT_LIMIT","Assistant response was truncated; try a shorter request");
   boolean hasTools=reply.content().stream().anyMatch(b->b.path("type").asText().equals("tool_use"));
   if(reply.content().isEmpty() || hasTools!=reply.stopReason().equals("tool_use"))
    throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_INVALID_RESPONSE","Invalid model tool protocol");
   if(reply.content().size()>16)throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_INVALID_RESPONSE","Too many tool calls");
   store.message(turn,"assistant",reply.content());
   messages.add(mapper.valueToTree(Map.of("role","assistant","content",reply.content())));
   List<JsonNode> results=new ArrayList<>();
   Set<String> ids=new HashSet<>();
   for(JsonNode block:reply.content()){
    if(block.path("type").asText().equals("text")){
     // Text has already been forwarded by the streaming model callback.
    }else if(block.path("type").asText().equals("tool_use")){
     if(++toolCalls>16)throw new ApiException(HttpStatus.CONFLICT,"TOOL_ROUND_LIMIT","Too many tool calls");
     String id=block.path("id").asText();
     if(id.isBlank() || !ids.add(id))throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_INVALID_RESPONSE","Invalid tool call ID");
     Object result;boolean error=false;
     try{
      var outcome=tools.execute(turn,block.path("name").asText(),block.path("input"));
      result=outcome.content();
      if(outcome.action()!=null)events.send("action",outcome.action());
     }catch(CancellationException e){throw e;}
     catch(ApiException e){result=Map.of("error",Map.of("code",e.code(),"message",e.getMessage()));error=true;}
     catch(RuntimeException e){result=Map.of("error",Map.of("code","TOOL_FAILED","message","Tool execution failed"));error=true;}
     results.add(mapper.valueToTree(Map.of("type","tool_result","tool_use_id",id,"content",store.json(result),"is_error",error)));
    }else throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_INVALID_RESPONSE","Unsupported model content");
   }
   if(results.isEmpty())return;
   store.message(turn,"tool",results);messages.add(mapper.valueToTree(Map.of("role","user","content",results)));
  }
  throw new ApiException(HttpStatus.CONFLICT,"TOOL_ROUND_LIMIT","Assistant reached its tool-call limit; please try a simpler request");
 }
 /** Query with the authenticated user; never let a model invent whether an order list is empty. */
 private void prefetchOrders(AgentStore.Turn turn,List<JsonNode> messages) {
  String callId="server_orders_"+turn.id();
  JsonNode call=mapper.valueToTree(Map.of("type","tool_use","id",callId,"name","list_orders","input",Map.of("limit",10)));
  store.message(turn,"assistant",List.of(call));
  messages.add(mapper.valueToTree(Map.of("role","assistant","content",List.of(call))));
  Object content;boolean error=false;
  try {content=tools.execute(turn,"list_orders",mapper.valueToTree(Map.of("limit",10))).content();}
  catch(ApiException e){content=Map.of("error",Map.of("code",e.code(),"message",e.getMessage()));error=true;}
  catch(RuntimeException e){content=Map.of("error",Map.of("code","TOOL_FAILED","message","Order lookup failed"));error=true;}
  JsonNode result=mapper.valueToTree(Map.of("type","tool_result","tool_use_id",callId,"content",store.json(content),"is_error",error));
  store.message(turn,"tool",List.of(result));
  messages.add(mapper.valueToTree(Map.of("role","user","content",List.of(result))));
 }
 private long reserve(AgentStore.Turn turn,List<JsonNode> messages,JsonNode definitions) {
  int bytes=(store.json(messages)+store.json(definitions)).getBytes(StandardCharsets.UTF_8).length;
  if(bytes>32000)throw new ApiException(HttpStatus.CONFLICT,"CONTEXT_TOO_LONG","Please start a new conversation");
  return store.reserve(turn,Math.addExact(bytes*2+2048,props.maxOutputTokens()));
 }
}
