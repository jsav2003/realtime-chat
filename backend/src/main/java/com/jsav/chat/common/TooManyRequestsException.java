package com.jsav.chat.common;

/** El usuario agotó su cupo. Se responde 429 con {@code Retry-After} en segundos. */
public class TooManyRequestsException extends RuntimeException {

  private final long retryAfterSeconds;

  public TooManyRequestsException(String message, long retryAfterSeconds) {
    super(message);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
