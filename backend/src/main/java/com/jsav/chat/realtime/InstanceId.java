package com.jsav.chat.realtime;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Identifica a esta instancia del backend, distinta en cada arranque. El canal de control la usa
 * para no reaccionar a su propio eco (ADR 0007) y la presencia, para saber de qué instancia es cada
 * conexión (ADR 0008).
 */
@Component
public class InstanceId {

  private final String value = UUID.randomUUID().toString();

  public String value() {
    return value;
  }
}
