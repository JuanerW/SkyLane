package com.skylane.agent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("skylane.agent")
public record AgentProperties(
 @DefaultValue("") String apiKey, @DefaultValue("deepseek-flash") String model,
 @DefaultValue("https://api.deepseek.com/anthropic") String baseUrl,
 @DefaultValue("100") int dailyMessages, @DefaultValue("100000") int dailyTokens,
 @DefaultValue("1024") int maxOutputTokens, @DefaultValue("5") int maxRounds,
 @DefaultValue("10") int actionTtlMinutes, @DefaultValue("45") int timeoutSeconds) {
 public AgentProperties {
  if(dailyMessages<1 || dailyTokens<1 || maxOutputTokens<1 || maxRounds<1 || maxRounds>10 ||
     actionTtlMinutes<1 || actionTtlMinutes>60 || timeoutSeconds<1 || timeoutSeconds>60 || maxRounds * timeoutSeconds > 480)
   throw new IllegalArgumentException("Invalid agent limits");
 }
}
