package com.skylane.agent;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
public interface AgentModel {
 record Reply(List<JsonNode> content,String stopReason,int inputTokens,int outputTokens) {}
 default void checkReady() {}
 Reply reply(List<JsonNode> messages, JsonNode tools);
 default Reply stream(List<JsonNode> messages,JsonNode tools,java.util.function.Consumer<String> text) {
  Reply reply=reply(messages,tools);
  reply.content().stream().filter(b->b.path("type").asText().equals("text")).forEach(b->text.accept(b.path("text").asText()));
  return reply;
 }
}
