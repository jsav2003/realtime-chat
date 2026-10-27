package com.jsav.chat.realtime;

import com.jsav.chat.common.UnauthorizedException;
import java.util.List;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Seguridad de los frames STOMP que llegan del cliente (DESIGN.md §4). La seguridad HTTP de Spring
 * no cubre STOMP, así que se revisa aquí:
 *
 * <ul>
 *   <li>{@code CONNECT}: exige un JWT válido en el header {@code Authorization} y fija como usuario
 *       de la sesión el id del dueño del token.
 * </ul>
 */
@Component
public class StompSecurityInterceptor implements ChannelInterceptor {

  private final JwtDecoder jwtDecoder;

  public StompSecurityInterceptor(JwtDecoder jwtDecoder) {
    this.jwtDecoder = jwtDecoder;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message;
    }
    if (accessor.getCommand() == StompCommand.CONNECT) {
      authenticate(accessor);
    }
    return message;
  }

  /** Valida el JWT del frame CONNECT. El navegador no puede mandarlo en el handshake. */
  private void authenticate(StompHeaderAccessor accessor) {
    String header = accessor.getFirstNativeHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      throw new UnauthorizedException("Falta el token");
    }
    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(header.substring("Bearer ".length()).trim());
    } catch (JwtException e) {
      throw new UnauthorizedException("Token inválido");
    }
    accessor.setUser(new UsernamePasswordAuthenticationToken(jwt.getSubject(), null, List.of()));
  }
}
