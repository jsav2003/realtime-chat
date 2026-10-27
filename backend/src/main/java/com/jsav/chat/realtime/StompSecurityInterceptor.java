package com.jsav.chat.realtime;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.ForbiddenException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.common.UnauthorizedException;
import java.security.Principal;
import java.util.List;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
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
  private final AccessPolicy policy;

  public StompSecurityInterceptor(JwtDecoder jwtDecoder, AccessPolicy policy) {
    this.jwtDecoder = jwtDecoder;
    this.policy = policy;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null) {
      // Un frame que no se sabe leer no pasa: ante la duda, cerrado.
      throw new BadRequestException("Frame STOMP no reconocido");
    }
    StompCommand command = accessor.getCommand();
    if (command == null) {
      if (accessor.getMessageType() == SimpMessageType.HEARTBEAT) {
        return message;
      }
      throw new BadRequestException("Frame STOMP no reconocido");
    }
    switch (command) {
      case CONNECT -> authenticate(accessor);
      case SUBSCRIBE -> authorizeSubscribe(accessor);
      case SEND ->
          // Con el relay, un SEND a /topic/channel.N llegaría a los suscriptores sin pasar por el
          // seq ni por AccessPolicy. Se escribe solo por HTTP (ADR 0006).
          throw new ForbiddenException("Los mensajes se envían por HTTP; STOMP solo recibe");
      case DISCONNECT, UNSUBSCRIBE -> {}
      default -> throw new BadRequestException("Comando STOMP no soportado");
    }
    return message;
  }

  private void authorizeSubscribe(StompHeaderAccessor accessor) {
    Principal user = accessor.getUser();
    if (user == null) {
      throw new UnauthorizedException("No autenticado");
    }
    long channelId =
        ChannelTopic.parse(accessor.getDestination())
            .orElseThrow(() -> new NotFoundException("Destino no encontrado"));
    policy.requireChannel(Long.valueOf(user.getName()), channelId, ChannelAction.READ);
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
