package com.jsav.chat;

import com.jsav.chat.config.PresenceProperties;
import com.jsav.chat.config.RateLimitProperties;
import com.jsav.chat.config.RealtimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
  RealtimeProperties.class,
  RateLimitProperties.class,
  PresenceProperties.class
})
public class ChatApplication {

  public static void main(String[] args) {
    SpringApplication.run(ChatApplication.class, args);
  }
}
