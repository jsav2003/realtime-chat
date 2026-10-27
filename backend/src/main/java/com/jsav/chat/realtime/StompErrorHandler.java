package com.jsav.chat.realtime;

import com.jsav.chat.common.ForbiddenException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.common.UnauthorizedException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Convierte lo que lance el interceptor en un frame ERROR cuyo header {@code message} lleva el
 * mismo código que daría HTTP ({@code "404: Canal no encontrado"}), para que el cliente y los tests
 * distingan 401, 403 y 404.
 */
public class StompErrorHandler extends StompSubProtocolErrorHandler {

  @Override
  public Message<byte[]> handleClientMessageProcessingError(
      Message<byte[]> clientMessage, Throwable ex) {
    // El canal envuelve lo que lanza el interceptor en un MessageDeliveryException.
    Throwable cause =
        ex instanceof MessageDeliveryException && ex.getCause() != null ? ex.getCause() : ex;
    return super.handleClientMessageProcessingError(clientMessage, cause);
  }

  @Override
  protected Message<byte[]> handleInternal(
      StompHeaderAccessor errorHeaderAccessor,
      byte[] errorPayload,
      Throwable cause,
      StompHeaderAccessor clientHeaderAccessor) {
    errorHeaderAccessor.setMessage(statusOf(cause) + ": " + cause.getMessage());
    return super.handleInternal(errorHeaderAccessor, errorPayload, cause, clientHeaderAccessor);
  }

  static int statusOf(Throwable cause) {
    if (cause instanceof UnauthorizedException) {
      return 401;
    }
    if (cause instanceof ForbiddenException) {
      return 403;
    }
    if (cause instanceof NotFoundException) {
      return 404;
    }
    return 400;
  }
}
