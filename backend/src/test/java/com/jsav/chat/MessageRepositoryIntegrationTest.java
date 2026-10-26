package com.jsav.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.message.Message;
import com.jsav.chat.message.MessageRepository;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Asignación del {@code seq} e inserción, contra Postgres real (DESIGN.md §3.2). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MessageRepositoryIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired MessageRepository messages;
  @Autowired UserRepository users;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;

  User author;
  Long general;
  Long other;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    author = users.save(new User("repo" + run + "@x.test", "x", "Autora " + run));
    Long serverId =
        serverService.create(author.getId(), new CreateServerRequest("Repo" + run)).id();
    general = channelService.list(author.getId(), serverId).get(0).id();
    other =
        channelService
            .create(author.getId(), serverId, new CreateChannelRequest("otro", "text"))
            .id();
  }

  private long lastSeq(Long channelId) {
    return jdbc.sql("select last_seq from channels where id = :id")
        .param("id", channelId)
        .query(Long.class)
        .single();
  }

  private Message send(Long channelId, UUID key, String body) {
    return tx.execute(
        s -> {
          long seq = messages.nextSeq(channelId);
          return messages.insert(channelId, seq, author.getId(), key, body).orElseThrow();
        });
  }

  @Test
  void nextSeq_countsFromOne_andEachChannelHasItsOwnSequence() {
    assertThat(send(general, UUID.randomUUID(), "a").seq()).isEqualTo(1);
    assertThat(send(general, UUID.randomUUID(), "b").seq()).isEqualTo(2);
    assertThat(send(other, UUID.randomUUID(), "c").seq()).isEqualTo(1);

    assertThat(lastSeq(general)).isEqualTo(2);
    assertThat(lastSeq(other)).isEqualTo(1);
  }

  @Test
  void insert_returnsTheMessageWithAuthorName() {
    UUID key = UUID.randomUUID();

    Message m = send(general, key, "hola");

    assertThat(m.id()).isNotNull();
    assertThat(m.channelId()).isEqualTo(general);
    assertThat(m.authorId()).isEqualTo(author.getId());
    assertThat(m.authorName()).isEqualTo(author.getDisplayName());
    assertThat(m.clientMsgId()).isEqualTo(key);
    assertThat(m.body()).isEqualTo("hola");
    assertThat(m.createdAt()).isNotNull();
  }

  @Test
  void insert_withRepeatedClientMsgId_insertsNothing() {
    UUID key = UUID.randomUUID();
    send(general, key, "primero");

    Optional<Message> second =
        tx.execute(
            s -> {
              long seq = messages.nextSeq(general);
              Optional<Message> result =
                  messages.insert(general, seq, author.getId(), key, "reintento");
              s.setRollbackOnly();
              return result;
            });

    assertThat(second).isEmpty();
  }

  @Test
  void rollback_returnsTheReservedSeq_soNoGapIsLeft() {
    send(general, UUID.randomUUID(), "uno");

    tx.executeWithoutResult(
        s -> {
          assertThat(messages.nextSeq(general)).isEqualTo(2);
          s.setRollbackOnly();
        });

    assertThat(lastSeq(general)).isEqualTo(1);
    assertThat(send(general, UUID.randomUUID(), "dos").seq()).isEqualTo(2);
  }

  @Test
  void findByClientMsgId_findsOnlyTheAuthorsOwnKey() {
    UUID key = UUID.randomUUID();
    Message sent = send(general, key, "hola");
    User stranger = users.save(new User("stranger" + RUNS.get() + "@x.test", "x", "Otro"));

    assertThat(messages.findByClientMsgId(author.getId(), key)).contains(sent);
    assertThat(messages.findByClientMsgId(stranger.getId(), key)).isEmpty();
    assertThat(messages.findByClientMsgId(author.getId(), UUID.randomUUID())).isEmpty();
  }
}
