package com.skylane.agent;
import com.fasterxml.jackson.databind.*;
import com.skylane.common.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Repository @Profile("!memory")
public class AgentStore {
 public record Turn(UUID id,UUID conversationId,UUID userId){}
 public record Action(UUID actionId,String kind,JsonNode payload,String status,Instant expiresAt,JsonNode result){
  public Action(UUID id,String kind,JsonNode payload,String status,Instant expiresAt){this(id,kind,payload,status,expiresAt,null);}
 }
 private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;private final AgentProperties props;
 public AgentStore(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock,AgentProperties props){
  this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;this.props=props;
 }
 public UUID create(UUID user){return jdbc.queryForObject("INSERT INTO conversation(user_id) VALUES (?) RETURNING id",UUID.class,user);}
 public void requireOwned(UUID user,UUID id){
  if(jdbc.queryForList("SELECT id FROM conversation WHERE id=? AND user_id=?",UUID.class,id,user).isEmpty())
   throw error(HttpStatus.NOT_FOUND,"CONVERSATION_NOT_FOUND","Conversation not found");
 }
 private void lockUser(UUID user){
  if(jdbc.queryForList("SELECT id FROM app_user WHERE id=? AND disabled_at IS NULL FOR UPDATE",UUID.class,user).isEmpty())
   throw error(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","Login required");
 }
 @Transactional public Turn begin(UUID user,UUID id,String text){
  lockUser(user);
  if(jdbc.queryForList("SELECT id FROM conversation WHERE id=? AND user_id=? FOR UPDATE",UUID.class,id,user).isEmpty())
   throw error(HttpStatus.NOT_FOUND,"CONVERSATION_NOT_FOUND","Conversation not found");
  jdbc.update("""
   UPDATE conversation_message SET content=jsonb_set(content,'{status}','"failed"')
   WHERE conversation_id=? AND content->>'kind'='turn' AND content->>'status'='running'
   AND (content->>'lease_until')::timestamptz <= ?
   """,id,Timestamp.from(clock.instant()));
  int active=jdbc.queryForObject("SELECT count(*) FROM conversation_message WHERE conversation_id=? AND content->>'kind'='turn' AND content->>'status'='running'",Integer.class,id);
  if(active>0)throw error(HttpStatus.CONFLICT,"CONVERSATION_BUSY","A reply is already in progress");
  int messages=jdbc.queryForObject("""
   SELECT count(*) FROM conversation_message m JOIN conversation c ON c.id=m.conversation_id
   WHERE c.user_id=? AND m.role='user' AND m.created_at>=? AND m.created_at<?
   """,Integer.class,user,dayStart(),dayEnd());
  if(messages>=props.dailyMessages() || usedTokens(user)>=props.dailyTokens())throw limited();
  UUID turn=UUID.randomUUID();
  append(id,"user",Map.of("kind","message","turn_id",turn,"blocks",List.of(Map.of("type","text","text",text))),null,null);
  append(id,"assistant",Map.of("kind","turn","turn_id",turn,"status","running","lease_until",clock.instant().plusSeconds(600).toString()),0,0);
  return new Turn(turn,id,user);
 }
 public List<JsonNode> context(Turn turn){
  return jdbc.query("""
   SELECT role,content::text FROM conversation_message WHERE conversation_id=? AND content->>'kind'='message'
   AND(content->>'turn_id'=? OR content->>'turn_id' IN
    (SELECT content->>'turn_id' FROM conversation_message WHERE conversation_id=? AND content->>'kind'='turn' AND content->>'status'='complete'))
   ORDER BY id
   """,(rs,i)->mapper.valueToTree(Map.of("role",rs.getString("role").equals("tool")?"user":rs.getString("role"),
      "content",parse(rs.getString("content")).path("blocks"))),
    turn.conversationId(),turn.id().toString(),turn.conversationId());
 }
 public void message(Turn turn,String role,List<JsonNode> blocks){
  append(turn.conversationId(),role,Map.of("kind","message","turn_id",turn.id(),"blocks",blocks),null,null);
 }
 private long append(UUID id,String role,Object content,Integer input,Integer output){
  return jdbc.queryForObject("INSERT INTO conversation_message(conversation_id,role,content,input_tokens,output_tokens) VALUES(?,?,?::jsonb,?,?) RETURNING id",
    Long.class,id,role,json(content),input,output);
 }
 @Transactional public long reserve(Turn turn,int tokens){
  lockUser(turn.userId());
  if(tokens<1 || usedTokens(turn.userId())+tokens>props.dailyTokens())throw limited();
  return append(turn.conversationId(),"assistant",Map.of("kind","usage","turn_id",turn.id(),"status","reserved"),tokens,0);
 }
 public void settle(long id,int input,int output){
  jdbc.update("UPDATE conversation_message SET input_tokens=?,output_tokens=?,content=jsonb_set(content,'{status}','\"complete\"') WHERE id=?",
   Math.max(0,input),Math.max(0,output),id);
 }
 private long usedTokens(UUID user){
  return jdbc.queryForObject("""
   SELECT coalesce(sum(coalesce(input_tokens,0)::bigint+coalesce(output_tokens,0)),0)
   FROM conversation_message m JOIN conversation c ON c.id=m.conversation_id
   WHERE c.user_id=? AND m.created_at>=? AND m.created_at<?
   """,Long.class,user,dayStart(),dayEnd());
 }
 public void finish(Turn turn,String status,String code){
  jdbc.update("""
   UPDATE conversation_message SET content=jsonb_set(jsonb_set(content,'{status}',to_jsonb(?::text)),'{error_code}',to_jsonb(?::text))
   WHERE conversation_id=? AND content->>'kind'='turn' AND content->>'turn_id'=? AND content->>'status'='running'
   """,status,code,turn.conversationId(),turn.id().toString());
 }
 public Action action(Turn turn,String kind,JsonNode payload,Instant expiry){
  UUID id=jdbc.queryForObject("INSERT INTO agent_action(conversation_id,kind,payload,expires_at) VALUES(?,?,?::jsonb,?) RETURNING id",
   UUID.class,turn.conversationId(),kind,json(payload),Timestamp.from(expiry));
  return new Action(id,kind,payload,"pending",expiry);
 }
 public Map<String,Object> history(UUID user,UUID id){
  requireOwned(user,id);
  var messages=jdbc.query("""
   SELECT * FROM(SELECT id,role,content::text,created_at FROM conversation_message
   WHERE conversation_id=? AND content->>'kind' IN ('message','turn') ORDER BY id DESC LIMIT 500) recent ORDER BY id
   """,(rs,i)->Map.of("id",rs.getLong("id"),"role",rs.getString("role"),"content",parse(rs.getString("content")),
    "created_at",rs.getTimestamp("created_at").toInstant()),id);
  var actions=jdbc.query("SELECT id,kind,payload::text,status,expires_at,result::text FROM agent_action WHERE conversation_id=? ORDER BY created_at DESC LIMIT 100",
   (rs,i)->new Action(rs.getObject("id",UUID.class),rs.getString("kind"),parse(rs.getString("payload")),rs.getString("status"),rs.getTimestamp("expires_at").toInstant(),rs.getString("result")==null?null:parse(rs.getString("result"))),id);
  return Map.of("conversation_id",id,"messages",messages,"actions",actions);
 }
 public JsonNode actionContext(UUID conversation) {
  return mapper.valueToTree(jdbc.query("SELECT id,kind,status,result::text FROM agent_action WHERE conversation_id=? ORDER BY created_at DESC LIMIT 20",
   (rs,i)->{Map<String,Object> row=new LinkedHashMap<>();row.put("action_id",rs.getObject("id",UUID.class));row.put("kind",rs.getString("kind"));row.put("status",rs.getString("status"));row.put("result",rs.getString("result")==null?null:parse(rs.getString("result")));return row;},conversation));
 }
 public String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 public JsonNode parse(String value){try{return mapper.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private Timestamp dayStart(){return Timestamp.from(clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant());}
 private Timestamp dayEnd(){return Timestamp.from(dayStart().toInstant().plus(Duration.ofDays(1)));}
 private static ApiException limited(){return error(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Daily assistant limit reached");}
 private static ApiException error(HttpStatus s,String c,String m){return new ApiException(s,c,m);}
}
