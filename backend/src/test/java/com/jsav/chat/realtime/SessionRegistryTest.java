package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class SessionRegistryTest {

  private final List<String> events = new ArrayList<>();
  private final SessionRegistry registry =
      new SessionRegistry(
          List.of(
              new ConnectionListener() {
                @Override
                public void connected(String sessionId, Long userId) {
                  events.add("+" + sessionId + ":" + userId);
                }

                @Override
                public void disconnected(String sessionId, Long userId) {
                  events.add("-" + sessionId + ":" + userId);
                }
              }));

  private WebSocketSession socket(String id) {
    WebSocketSession s = mock(WebSocketSession.class);
    when(s.getId()).thenReturn(id);
    registry.opened(s);
    return s;
  }

  @Test
  void closesEverySocketOfTheUser_withPolicyViolation() throws Exception {
    WebSocketSession tab1 = socket("a");
    WebSocketSession tab2 = socket("b");
    registry.bind("a", 7L);
    registry.bind("b", 7L);

    int closed = registry.closeSocketsOf(7L);

    assertThat(closed).isEqualTo(2);
    verify(tab1).close(CloseStatus.POLICY_VIOLATION.withReason("Ya no tienes acceso"));
    verify(tab2).close(CloseStatus.POLICY_VIOLATION.withReason("Ya no tienes acceso"));
  }

  @Test
  void leavesTheSocketsOfOtherUsersAlone() throws Exception {
    WebSocketSession mine = socket("a");
    WebSocketSession other = socket("b");
    registry.bind("a", 7L);
    registry.bind("b", 8L);

    registry.closeSocketsOf(7L);

    verify(mine).close(any(CloseStatus.class));
    verify(other, never()).close(any(CloseStatus.class));
  }

  @Test
  void aSocketNotYetAuthenticatedBelongsToNobody() throws Exception {
    WebSocketSession anonymous = socket("a");

    assertThat(registry.closeSocketsOf(7L)).isZero();
    verify(anonymous, never()).close(any(CloseStatus.class));
  }

  @Test
  void aClosedSocketIsForgotten() {
    socket("a");
    registry.bind("a", 7L);

    registry.closed("a");

    assertThat(registry.openSockets()).isZero();
    assertThat(registry.closeSocketsOf(7L)).isZero();
  }

  @Test
  void listenersHearEachAuthenticatedSessionOpenAndClose() {
    socket("a");
    registry.bind("a", 7L);
    registry.closed("a");

    assertThat(events).containsExactly("+a:7", "-a:7");
  }

  @Test
  void aSocketThatNeverAuthenticatedIsNotAnnouncedWhenItCloses() {
    socket("a");

    registry.closed("a");

    assertThat(events).isEmpty();
  }

  @Test
  void aSecondCloseOfTheSameSessionIsNotAnnouncedTwice() {
    socket("a");
    registry.bind("a", 7L);

    registry.closed("a");
    registry.closed("a");

    assertThat(events).containsExactly("+a:7", "-a:7");
  }

  @Test
  void aFailingListenerDoesNotBreakTheRegistryNorTheOtherListeners() {
    List<String> heard = new ArrayList<>();
    SessionRegistry guarded =
        new SessionRegistry(
            List.of(
                new ConnectionListener() {
                  @Override
                  public void connected(String sessionId, Long userId) {
                    throw new IllegalStateException("se cayó");
                  }

                  @Override
                  public void disconnected(String sessionId, Long userId) {
                    throw new IllegalStateException("se cayó");
                  }
                },
                new ConnectionListener() {
                  @Override
                  public void connected(String sessionId, Long userId) {
                    heard.add("+");
                  }

                  @Override
                  public void disconnected(String sessionId, Long userId) {
                    heard.add("-");
                  }
                }));

    guarded.bind("a", 7L);
    guarded.closed("a");

    assertThat(heard).containsExactly("+", "-");
    assertThat(guarded.isBound("a")).isFalse();
  }

  @Test
  void boundSessionsListsOnlyTheAuthenticatedOnes() {
    socket("a");
    socket("b");
    registry.bind("a", 7L);

    assertThat(registry.boundSessions()).containsExactly(java.util.Map.entry("a", 7L));
    assertThat(registry.isBound("a")).isTrue();
    assertThat(registry.isBound("b")).isFalse();
  }

  @Test
  void aSocketThatFailsToCloseDoesNotStopTheOthers() throws Exception {
    WebSocketSession broken = socket("a");
    WebSocketSession fine = socket("b");
    registry.bind("a", 7L);
    registry.bind("b", 7L);
    doThrow(new IOException("ya cerrado")).when(broken).close(any(CloseStatus.class));

    int closed = registry.closeSocketsOf(7L);

    assertThat(closed).isEqualTo(1);
    verify(fine).close(any(CloseStatus.class));
  }
}
