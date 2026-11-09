package com.skylane.agent;
import com.skylane.common.ApiException;
import com.skylane.quote.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;

@RestController @Profile("!memory")
@RequestMapping("/api/v1/agent/conversations")
public class AgentController {
 public record MessageRequest(@NotBlank @Size(max=4000)String text){}
 private final AgentStore store;private final AgentService service;private final CurrentUserProvider user;
 public AgentController(AgentStore store,AgentService service,CurrentUserProvider user){this.store=store;this.service=service;this.user=user;}
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 public Map<String,UUID> create(){return Map.of("conversation_id",store.create(user.currentUserId()));}
 @GetMapping("/{id}")
 public Map<String,Object> history(@PathVariable String id){return store.history(user.currentUserId(),id(id));}
 @PostMapping(value="/{id}/messages",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
 public SseEmitter message(@PathVariable String id,@Valid @RequestBody MessageRequest request){
  return service.open(user.currentUserId(),id(id),request.text());
 }
 private static UUID id(String value){
  try{return UUID.fromString(value);}catch(IllegalArgumentException e){throw new ApiException(HttpStatus.NOT_FOUND,"CONVERSATION_NOT_FOUND","Conversation not found");}
 }
}
