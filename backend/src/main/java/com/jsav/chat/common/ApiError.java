package com.jsav.chat.common;

import java.util.Map;

/** Cuerpo uniforme de todos los errores de la API. */
public record ApiError(int status, String error, String message, Map<String, String> fields) {

  public static ApiError of(int status, String error, String message) {
    return new ApiError(status, error, message, Map.of());
  }
}
