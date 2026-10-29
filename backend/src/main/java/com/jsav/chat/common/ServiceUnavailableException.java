package com.jsav.chat.common;

/** Algo de lo que depende la respuesta no está disponible por ahora (por ejemplo, Redis). */
public class ServiceUnavailableException extends RuntimeException {

  public ServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
