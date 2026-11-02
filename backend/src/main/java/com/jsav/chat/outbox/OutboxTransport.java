package com.jsav.chat.outbox;

import java.util.List;
import java.util.Set;

/**
 * Por dónde salen los eventos de la outbox hacia el broker. Es una interfaz para que las pruebas
 * puedan hacer que falle o que «muera» justo después de publicar, que es el momento que importa.
 */
public interface OutboxTransport {

  /**
   * Publica los eventos y espera la confirmación del broker. Devuelve los ids de los que el broker
   * confirmó; el resto sigue pendiente y se reintenta en la vuelta siguiente. Un broker caído no es
   * una excepción: simplemente no confirma nada.
   */
  Set<Long> publish(List<OutboxEvent> events);
}
