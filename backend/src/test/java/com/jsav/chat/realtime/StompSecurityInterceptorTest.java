package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.common.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class StompSecurityInterceptorTest {

  private final JwtDecoder decoder = mock(JwtDecoder.class);
  private final StompSecurityInterceptor interceptor =
      new StompSecurityInterceptor(decoder, mock(AccessPolicy.class));

  private static Message<byte[]> frame(StompCommand command, String authorization) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    if (authorization != null) {
      accessor.addNativeHeader("Authorization", authorization);
    }
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  @Test
  void connectWithoutToken_is401() {
    assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null), null))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage("Falta el token");
  }

  @Test
  void connectWithAnotherScheme_is401() {
    assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Basic abc"), null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void connectWithABadToken_is401() {
    when(decoder.decode("bad")).thenThrow(new BadJwtException("firma"));

    assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer bad"), null))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage("Token inválido");
  }

  @Test
  void connectWithAValidToken_setsTheUserId() {
    Jwt jwt = Jwt.withTokenValue("ok").header("alg", "HS256").subject("7").build();
    when(decoder.decode("ok")).thenReturn(jwt);
    Message<byte[]> connect = frame(StompCommand.CONNECT, "Bearer ok");

    interceptor.preSend(connect, null);

    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(connect, StompHeaderAccessor.class);
    assertThat(accessor.getUser()).isNotNull();
    assertThat(accessor.getUser().getName()).isEqualTo("7");
  }
}
