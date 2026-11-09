package com.skylane.agent;
import com.skylane.common.ApiException;
import com.skylane.quote.CurrentUserProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @Profile("!memory") @RequestMapping("/api/v1/agent/actions")
public class AgentActionController {
 private final AgentActionService service;private final CurrentUserProvider user;
 public AgentActionController(AgentActionService service,CurrentUserProvider user){this.service=service;this.user=user;}
 @PostMapping("/{id}/confirm") public AgentActionService.Response confirm(@PathVariable String id){return service.decide(user.currentUserId(),id(id),true);}
 @PostMapping("/{id}/reject") public AgentActionService.Response reject(@PathVariable String id){return service.decide(user.currentUserId(),id(id),false);}
 private static UUID id(String id){try{return UUID.fromString(id);}catch(IllegalArgumentException e){throw new ApiException(HttpStatus.NOT_FOUND,"ACTION_NOT_FOUND","Action not found");}}
}
