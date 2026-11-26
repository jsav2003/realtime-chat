package com.jsav.chat.readstate;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.TestUsers;
import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El cursor de lectura con marcados simultáneos (ADR 0015): todos salen a la vez, detrás de un
 * mismo latch, contra Postgres real. Es lo que pasa con dos pestañas abiertas o con respuestas que
 * llegan al revés: el orden de llegada no puede hacer retroceder el cursor.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReadStateConcurrencyIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();
  private static final int MARKS = 20;

  @Autowired ReadStateService service;
  @Autowired MessageService messages;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired JdbcClient jdbc;

  User owner;
  User reader;
  Long channel;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(TestUsers.user("rsc" + run + "-o@x.test", "x", "Owner"));
    reader = users.save(TestUsers.user("rsc" + run + "-r@x.test", "x", "Lectora"));
    Long serverId = serverService.create(owner.getId(), new CreateServerRequest("Rsc" + run)).id();
    serverMembers.save(new ServerMember(serverId, reader.getId(), Role.MEMBER));
    channel = channelService.list(owner.getId(), serverId).get(0).id();
  }

  /** Lanza todas las tareas a la vez y espera a que terminen. */
  private static void allAtOnce(List<Callable<Void>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    CountDownLatch ready = new CountDownLatch(tasks.size());
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Void>> futures = new ArrayList<>();
    for (Callable<Void> task : tasks) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return task.call();
              }));
    }
    ready.await();
    go.countDown();
    for (Future<Void> f : futures) {
      f.get();
    }
    pool.shutdown();
  }

  private void send(int count) {
    for (int i = 0; i < count; i++) {
      messages.send(owner.getId(), channel, new SendMessageRequest(UUID.randomUUID(), "m" + i));
    }
  }

  private long lastRead() {
    return jdbc.sql("select last_read_seq from read_state where user_id = :u and channel_id = :c")
        .param("u", reader.getId())
        .param("c", channel)
        .query(Long.class)
        .single();
  }

  private long lastSeq() {
    return jdbc.sql("select last_seq from channels where id = :c")
        .param("c", channel)
        .query(Long.class)
        .single();
  }

  /**
   * Veinte marcados del 1 al 20 en un orden distinto cada vez, todos a la vez: queda el 20. Sin el
   * {@code greatest} del upsert gana el último en escribir, que casi nunca es el 20.
   */
  @RepeatedTest(3)
  void twentySimultaneousMarksInRandomOrder_leaveTheHighest() throws Exception {
    send(MARKS);
    List<Long> seqs = new ArrayList<>();
    for (long s = 1; s <= MARKS; s++) {
      seqs.add(s);
    }
    Collections.shuffle(seqs, new Random(RUNS.get() * 0x9E3779B97F4A7C15L));

    List<Callable<Void>> tasks = new ArrayList<>();
    for (long s : seqs) {
      tasks.add(
          () -> {
            service.markRead(reader.getId(), channel, s);
            return null;
          });
    }
    allAtOnce(tasks);

    assertThat(lastRead()).isEqualTo(MARKS);
  }

  /**
   * Marcados de «todo» mezclados con envíos: el cursor nunca pasa del {@code last_seq} del canal,
   * así que el contador nunca da negativo, y el envío no espera al marcado ni se rompe por él.
   */
  @Test
  void marksRacingWithSends_neverPassTheLastSeq() throws Exception {
    send(1);
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tasks.add(
          () -> {
            service.markRead(reader.getId(), channel, 999);
            return null;
          });
      tasks.add(
          () -> {
            send(1);
            return null;
          });
    }
    allAtOnce(tasks);

    assertThat(lastSeq()).isEqualTo(11);
    assertThat(lastRead()).isBetween(1L, 11L);
    long unread =
        service.unread(reader.getId(), channelServerId()).channels().stream()
            .filter(c -> c.channelId() == channel.longValue())
            .findFirst()
            .orElseThrow()
            .unread();
    assertThat(unread).isEqualTo(11 - lastRead()).isNotNegative();
  }

  private Long channelServerId() {
    return jdbc.sql("select server_id from channels where id = :c")
        .param("c", channel)
        .query(Long.class)
        .single();
  }
}
