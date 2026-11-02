package com.jsav.chat.mention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.message.MessageService.Sent;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Las menciones llegan a la outbox en la misma transacción que el mensaje, y solo para quien debe
 * enterarse (ADR 0004, ADR 0011). Postgres real: lo que se prueba es qué queda en las tablas.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MentionOutboxIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired MessageService service;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired JdbcClient jdbc;
  @Autowired JdbcTemplate jdbcTemplate;

  int run;
  User owner;
  User ana;
  User beto;
  User outsider;
  Long serverId;
  Long general;
  Long secret;

  @BeforeEach
  void setUp() {
    run = RUNS.incrementAndGet();
    owner = users.save(new User("mo" + run + "@x.test", "x", "Owner", "owner_m" + run));
    ana = users.save(new User("ma" + run + "@x.test", "x", "Ana", "ana_m" + run));
    beto = users.save(new User("mb" + run + "@x.test", "x", "Beto", "beto_m" + run));
    outsider = users.save(new User("mx" + run + "@x.test", "x", "Fuera", "fuera_m" + run));
    serverId = serverService.create(owner.getId(), new CreateServerRequest("Men" + run)).id();
    serverMembers.save(new ServerMember(serverId, ana.getId(), Role.MEMBER));
    serverMembers.save(new ServerMember(serverId, beto.getId(), Role.MEMBER));
    general = channelService.list(owner.getId(), serverId).get(0).id();
    // El privado lo ven el owner y ana; beto es del servidor pero no está en él.
    secret =
        channelService
            .create(owner.getId(), serverId, new CreateChannelRequest("secreto", "private"))
            .id();
    channelService.addMember(owner.getId(), secret, ana.getId());
  }

  private Sent send(User who, Long channelId, UUID key, String body) {
    return service.send(who.getId(), channelId, new SendMessageRequest(key, body));
  }

  private long sendNew(User who, Long channelId, String body) {
    return send(who, channelId, UUID.randomUUID(), body).message().id();
  }

  /** A quién va dirigido cada evento de mención de ese mensaje, en orden. */
  private List<Long> recipientsOf(long messageId) {
    return jdbc.sql(
            """
            select (payload->>'userId')::bigint from outbox
            where event_type = 'mention' and (payload->>'messageId')::bigint = :id
            order by 1
            """)
        .param("id", messageId)
        .query(Long.class)
        .list();
  }

  private long lastSeq(Long channelId) {
    return jdbc.sql("select last_seq from channels where id = :id")
        .param("id", channelId)
        .query(Long.class)
        .single();
  }

  private long messageCount(Long channelId) {
    return jdbc.sql("select count(*) from messages where channel_id = :id")
        .param("id", channelId)
        .query(Long.class)
        .single();
  }

  private long eventCount(Long channelId) {
    return jdbc.sql("select count(*) from outbox where (payload->>'channelId')::bigint = :id")
        .param("id", channelId)
        .query(Long.class)
        .single();
  }

  @Test
  void eachMentionedPerson_getsOneEvent_withTheDataTheWorkerNeeds() {
    long messageId = sendNew(owner, general, "hola @ana_m%d y @BETO_m%d".formatted(run, run));

    assertThat(recipientsOf(messageId)).containsExactly(ana.getId(), beto.getId());
    var payload =
        jdbc.sql(
                "select payload->>'channelId', payload->>'authorId' from outbox "
                    + "where (payload->>'messageId')::bigint = :id and (payload->>'userId')::bigint = :u")
            .param("id", messageId)
            .param("u", ana.getId())
            .query((rs, i) -> List.of(rs.getString(1), rs.getString(2)))
            .single();
    assertThat(payload).containsExactly(String.valueOf(general), String.valueOf(owner.getId()));
  }

  @Test
  void aMessageWithoutMentions_writesNoEvents() {
    long plain = sendNew(owner, general, "hola a todos");
    long email = sendNew(owner, general, "escríbeme a ana_m%d@correo.com".formatted(run));

    assertThat(recipientsOf(plain)).isEmpty();
    assertThat(recipientsOf(email)).isEmpty();
  }

  @Test
  void theSamePersonMentionedTwice_isOneEvent() {
    long messageId = sendNew(owner, general, "@ana_m%d @ANA_M%d".formatted(run, run));

    assertThat(recipientsOf(messageId)).containsExactly(ana.getId());
  }

  @Test
  void aRetry_doesNotWriteTheEventsAgain() {
    UUID key = UUID.randomUUID();
    String body = "@ana_m%d @beto_m%d".formatted(run, run);
    Sent first = send(owner, general, key, body);

    Sent retry = send(owner, general, key, body);

    assertThat(retry.replayed()).isTrue();
    assertThat(recipientsOf(first.message().id())).hasSize(2);
    assertThat(eventCount(general)).isEqualTo(2);
  }

  @Test
  void inAPrivateChannel_onlyWhoCanSeeItIsNotified() {
    long messageId = sendNew(owner, secret, "@ana_m%d @beto_m%d".formatted(run, run));

    // beto es del servidor pero no ve #secreto: notificarlo revelaría que el mensaje existe.
    assertThat(recipientsOf(messageId)).containsExactly(ana.getId());
  }

  @Test
  void aPersonOutsideTheServer_isNotNotified() {
    long messageId = sendNew(owner, general, "@fuera_m%d".formatted(run));

    assertThat(recipientsOf(messageId)).isEmpty();
  }

  @Test
  void aKickedMember_isNotNotified() {
    serverService.kick(owner.getId(), serverId, beto.getId());

    long messageId = sendNew(owner, general, "@beto_m%d".formatted(run));

    assertThat(recipientsOf(messageId)).isEmpty();
  }

  @Test
  void mentioningYourself_orSomeoneWhoDoesNotExist_writesNothing() {
    long self = sendNew(ana, general, "@ana_m%d".formatted(run));
    long ghost = sendNew(ana, general, "@nadie_existe_%d".formatted(run));

    assertThat(recipientsOf(self)).isEmpty();
    assertThat(recipientsOf(ghost)).isEmpty();
  }

  @Test
  void ifTheOutboxFails_theMessageItsSeqAndTheOtherEventsAreRolledBack() {
    // Un trigger hace fallar el INSERT en la outbox solo para beto. El de ana, que va antes, ya se
    // escribió: tiene que desaparecer también, igual que el mensaje y su seq.
    jdbcTemplate.execute(
        """
        create or replace function test_fail_outbox() returns trigger as $$
        begin
          if (new.payload->>'userId')::bigint = %d then
            raise exception 'outbox caída (simulada)';
          end if;
          return new;
        end $$ language plpgsql
        """
            .formatted(beto.getId()));
    jdbcTemplate.execute(
        "create trigger test_fail_outbox before insert on outbox "
            + "for each row execute function test_fail_outbox()");
    try {
      assertThatThrownBy(
              () ->
                  send(owner, general, UUID.randomUUID(), "@ana_m%d @beto_m%d".formatted(run, run)))
          .isInstanceOf(DataAccessException.class);
    } finally {
      jdbcTemplate.execute("drop trigger test_fail_outbox on outbox");
      jdbcTemplate.execute("drop function test_fail_outbox()");
    }

    assertThat(messageCount(general)).isZero();
    assertThat(lastSeq(general)).isZero();
    assertThat(eventCount(general)).isZero();
  }
}
