package com.jsav.chat;

import com.jsav.chat.config.RateLimitProperties;
import com.jsav.chat.config.RealtimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({RealtimeProperties.class, RateLimitProperties.class})
public class ChatApplication {

  public static void main(String[] args) {
    SpringApplication.run(ChatApplication.class, args);
  }
}
