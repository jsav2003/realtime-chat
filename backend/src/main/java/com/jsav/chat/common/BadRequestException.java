package com.jsav.chat.common;

/** Parámetros que pasan la validación de tipos pero no tienen sentido juntos o fuera de rango. */
public class BadRequestException extends RuntimeException {
  public BadRequestException(String message) {
    super(message);
  }
}
