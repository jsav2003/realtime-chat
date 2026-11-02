package com.jsav.chat;

/**
 * Un proceso que «muere» en un punto exacto. No es una falla del negocio: nada en el código de
 * producción la atrapa, así que la transacción en curso se pierde y el mensaje de RabbitMQ que se
 * estaba procesando queda sin confirmar, como si el proceso se hubiera caído.
 */
public class SimulatedCrash extends RuntimeException {

  public SimulatedCrash(String where) {
    super("caída simulada: " + where, null, false, false);
  }
}
