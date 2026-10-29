package com.jsav.chat.common;

import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Carga los scripts Lua de {@code src/main/resources}. */
public final class RedisScripts {

  private RedisScripts() {}

  /** Un script que devuelve un solo número. */
  public static DefaultRedisScript<Long> ofLong(String classpathLocation) {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource(classpathLocation));
    script.setResultType(Long.class);
    return script;
  }

  /** Un script que devuelve una lista de números. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static DefaultRedisScript<List<Long>> listOfLongs(String classpathLocation) {
    DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource(classpathLocation));
    script.setResultType((Class) List.class);
    return script;
  }
}
