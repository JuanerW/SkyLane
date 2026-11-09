package com.skylane.agent;
import com.fasterxml.jackson.databind.*;
import com.skylane.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class DeepSeekAgentModelTest {
 private final ObjectMapper mapper=new ObjectMapper();
 private AgentProperties props(String key,String base){
  return new AgentProperties(key,"test-model",base,100,100000,1024,5,10,5);
 }
 @Test void sendsToolProtocolAndReadsUsage() throws Exception{
  AtomicReference<String> body=new AtomicReference<>(),version=new AtomicReference<>(),key=new AtomicReference<>();
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/v1/messages",exchange->{
   body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
   version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));key.set(exchange.getRequestHeaders().getFirst("x-api-key"));
   byte[] result="""
    {"content":[{"type":"tool_use","id":"t1","name":"list_orders","input":{}}],
     "stop_reason":"tool_use","usage":{"input_tokens":20,"output_tokens":10,"cache_read_input_tokens":5}}
    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
   exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();
  });server.start();
  try{
   var client=new DeepSeekAgentModel(props("test-only","http://127.0.0.1:"+server.getAddress().getPort()),mapper);
   var reply=client.reply(List.of(mapper.readTree("{\"role\":\"user\",\"content\":\"Hello\"}")),mapper.readTree("[]"));
   assertThat(reply.stopReason()).isEqualTo("tool_use");assertThat(reply.inputTokens()).isEqualTo(25);
   assertThat(version.get()).isEqualTo("2023-06-01");assertThat(key.get()).isEqualTo("test-only");
   JsonNode request=mapper.readTree(body.get());assertThat(request.path("model").asText()).isEqualTo("test-model");
   assertThat(request.path("system").asText()).contains("untrusted data");assertThat(request.path("tools").isArray()).isTrue();assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
  }finally{server.stop(0);}
 }
 @Test void missingKeyFailsBeforeSendingAnyRequest(){
  var model=new DeepSeekAgentModel(props("","http://127.0.0.1:1"),mapper);
  assertThatThrownBy(model::checkReady).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("AGENT_NOT_CONFIGURED"));
 }
 @Test void upstreamErrorBodyDoesNotLeakToTheCaller() throws Exception{
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/v1/messages",exchange->{byte[] b="sensitive upstream diagnostics".getBytes();exchange.sendResponseHeaders(429,b.length);exchange.getResponseBody().write(b);exchange.close();});
  server.start();
  try{
   var client=new DeepSeekAgentModel(props("test-only","http://127.0.0.1:"+server.getAddress().getPort()),mapper);
   assertThatThrownBy(()->client.reply(List.of(),mapper.createArrayNode())).isInstanceOfSatisfying(ApiException.class,
     e->{assertThat(e.code()).isEqualTo("MODEL_UNAVAILABLE");assertThat(e.getMessage()).doesNotContain("sensitive");});
  }finally{server.stop(0);}
 }
}
