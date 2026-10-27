package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.ForbiddenException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.common.UnauthorizedException;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class StompSecurityInterceptorTest {

  private final JwtDecoder decoder = mock(JwtDecoder.class);
  private final AccessPolicy policy = mock(AccessPolicy.class);
  private final StompSecurityInterceptor interceptor =
      new StompSecurityInterceptor(decoder, policy);

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

  @Test
  void send_isAlways403_evenForAnAuthenticatedUser() {
    Message<byte[]> send = frame(StompCommand.SEND, null);

    assertThatThrownBy(() -> interceptor.preSend(send, null))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void aCommandThatIsNotSupported_isRejected() {
    assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.BEGIN, null), null))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aMessageWithoutAStompAccessor_isRejected() {
    Message<byte[]> bare = MessageBuilder.withPayload(new byte[0]).build();

    assertThatThrownBy(() -> interceptor.preSend(bare, null))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aHeartbeat_passes() {
    StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
    accessor.setLeaveMutable(true);
    Message<byte[]> heartbeat =
        MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    assertThat(interceptor.preSend(heartbeat, null)).isSameAs(heartbeat);
  }

  @Test
  void subscribeWithoutAUser_is401() {
    assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/channel.5", null), null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void subscribeToAnotherDestination_is404_andNeverAsksThePolicy() {
    assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/anything", user("7")), null))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(policy);
  }

  @Test
  void subscribeAsksThePolicyToReadThatChannel() {
    Message<byte[]> sub = subscribe("/topic/channel.5", user("7"));

    interceptor.preSend(sub, null);

    verify(policy).requireChannel(7L, 5L, ChannelAction.READ);
  }

  @Test
  void whatThePolicyRefuses_reachesTheClient() {
    when(policy.requireChannel(7L, 5L, ChannelAction.READ))
        .thenThrow(new NotFoundException("Canal no encontrado"));

    assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/channel.5", user("7")), null))
        .isInstanceOf(NotFoundException.class);
  }

  private static Principal user(String id) {
    return new UsernamePasswordAuthenticationToken(id, null, List.of());
  }

  private static Message<byte[]> subscribe(String destination, Principal user) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    accessor.setSubscriptionId("sub-0");
    accessor.setUser(user);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
