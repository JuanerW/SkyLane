package com.skylane.agent;

import com.fasterxml.jackson.databind.*;
import com.skylane.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class DeepSeekStreamingTest {
 private final ObjectMapper mapper=new ObjectMapper();
 private DeepSeekAgentModel client(int port){return new DeepSeekAgentModel(new AgentProperties("test-key","deepseek-flash","http://127.0.0.1:"+port,100,100000,1024,5,10,5),mapper);}
 private String frame(String type,Object value) throws Exception{return "event: "+type+"\ndata: "+mapper.writeValueAsString(value)+"\n\n";}
 private Map<String,Object> event(String type,Object... pairs){Map<String,Object> v=new LinkedHashMap<>();v.put("type",type);for(int i=0;i<pairs.length;i+=2)v.put((String)pairs[i],pairs[i+1]);return v;}
 @Test void forwardsTextBeforeCompletionAndDoesNotDoubleCountUsage() throws Exception{
  CountDownLatch seen=new CountDownLatch(1);HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/v1/messages",x->{
   try{
    var request=mapper.readTree(x.getRequestBody());assertThat(request.path("stream").asBoolean()).isTrue();assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
    x.getResponseHeaders().add("Content-Type","text/event-stream");x.sendResponseHeaders(200,0);var out=x.getResponseBody();
    String first=frame("message_start",event("message_start","message",Map.of("usage",Map.of("input_tokens",10,"cache_read_input_tokens",5,"output_tokens",1))))+
     frame("content_block_start",event("content_block_start","index",0,"content_block",Map.of("type","text","text","")))+
     frame("content_block_delta",event("content_block_delta","index",0,"delta",Map.of("type","text_delta","text","你好")));
    out.write(first.getBytes(StandardCharsets.UTF_8));out.flush();
    assertThat(seen.await(3,TimeUnit.SECONDS)).isTrue();
    String last=frame("content_block_stop",event("content_block_stop","index",0))+
     frame("message_delta",event("message_delta","delta",Map.of("stop_reason","end_turn"),"usage",Map.of("input_tokens",12,"cache_read_input_tokens",5,"output_tokens",5)))+
     frame("message_stop",event("message_stop"));
    out.write(last.getBytes(StandardCharsets.UTF_8));x.close();
   }catch(Exception e){x.close();throw new RuntimeException(e);}
  });server.start();
  try(var c=client(server.getAddress().getPort())){
   var reply=c.stream(List.of(),mapper.createArrayNode(),value->{assertThat(value).isEqualTo("你好");seen.countDown();});
   assertThat(reply.content().getFirst().path("text").asText()).isEqualTo("你好");assertThat(reply.inputTokens()).isEqualTo(17);assertThat(reply.outputTokens()).isEqualTo(5);
  }finally{server.stop(0);}
 }
 @Test void assemblesToolArgumentsAndRejectsPrematureEof() throws Exception{
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);java.util.concurrent.atomic.AtomicBoolean complete=new java.util.concurrent.atomic.AtomicBoolean(true);
  server.createContext("/v1/messages",x->{
   try{
    String body=frame("message_start",event("message_start","message",Map.of("usage",Map.of("input_tokens",10))))+
     frame("content_block_start",event("content_block_start","index",0,"content_block",Map.of("type","tool_use","id","call_1","name","list_orders","input",Map.of())))+
     frame("content_block_delta",event("content_block_delta","index",0,"delta",Map.of("type","input_json_delta","partial_json","{\"limit\":")))+
     frame("content_block_delta",event("content_block_delta","index",0,"delta",Map.of("type","input_json_delta","partial_json","1}")));
    if(complete.get())body+=frame("content_block_stop",event("content_block_stop","index",0))+frame("message_delta",event("message_delta","delta",Map.of("stop_reason","tool_use"),"usage",Map.of("output_tokens",5)))+frame("message_stop",event("message_stop"));
    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();
   }catch(Exception e){x.close();throw new RuntimeException(e);}
  });server.start();
  try(var c=client(server.getAddress().getPort())){
   var reply=c.stream(List.of(),mapper.createArrayNode(),v->{});assertThat(reply.content().getFirst().path("input").path("limit").asInt()).isEqualTo(1);
   complete.set(false);assertThatThrownBy(()->c.stream(List.of(),mapper.createArrayNode(),v->{})).isInstanceOf(ApiException.class);
  }finally{server.stop(0);}
 }
}
