package com.jsav.chat;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * La secuencia aguanta envíos simultáneos (DESIGN.md §8.3): todos salen a la vez, detrás de un
 * mismo latch, contra Postgres real.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MessageSeqConcurrencyIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();
  private static final int AUTHORS = 5;

  @Autowired MessageService service;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired JdbcClient jdbc;

  List<User> authors;
  Long channel;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    authors = new ArrayList<>();
    for (int i = 0; i < AUTHORS; i++) {
      authors.add(users.save(new User("seq" + run + "-" + i + "@x.test", "x", "Autor " + i)));
    }
    Long serverId =
        serverService.create(authors.get(0).getId(), new CreateServerRequest("Seq" + run)).id();
    for (User u : authors.subList(1, AUTHORS)) {
      serverMembers.save(new ServerMember(serverId, u.getId(), Role.MEMBER));
    }
    channel = channelService.list(authors.get(0).getId(), serverId).get(0).id();
  }

  /** Lanza todas las tareas a la vez y devuelve sus resultados en el orden en que se crearon. */
  private static <T> List<T> allAtOnce(List<Callable<T>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    CountDownLatch ready = new CountDownLatch(tasks.size());
    CountDownLatch go = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    for (Callable<T> task : tasks) {
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
    List<T> results = new ArrayList<>();
    for (Future<T> f : futures) {
      results.add(f.get());
    }
    pool.shutdown();
    return results;
  }

  private List<Long> storedSeqs() {
    return jdbc.sql("select seq from messages where channel_id = :id order by seq")
        .param("id", channel)
        .query(Long.class)
        .list();
  }

  private long lastSeq() {
    return jdbc.sql("select last_seq from channels where id = :id")
        .param("id", channel)
        .query(Long.class)
        .single();
  }

  private static List<Long> oneTo(int n) {
    return LongStream.rangeClosed(1, n).boxed().toList();
  }

  @Test
  void fiftySimultaneousSends_produceSeqsOneToFifty_withoutRepeatsOrGaps() throws Exception {
    List<Callable<Sent>> sends = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      User author = authors.get(i % AUTHORS);
      SendMessageRequest req = new SendMessageRequest(UUID.randomUUID(), "m" + i);
      sends.add(() -> service.send(author.getId(), channel, req));
    }

    List<Sent> results = allAtOnce(sends);

    assertThat(results).noneMatch(Sent::replayed);
    assertThat(results.stream().map(s -> s.message().seq()).sorted().toList()).isEqualTo(oneTo(50));
    assertThat(storedSeqs()).isEqualTo(oneTo(50));
    assertThat(lastSeq()).isEqualTo(50);
  }

  /**
   * El caso delicado del envío: los diez pasan la búsqueda de la clave antes de que el primero
   * confirme, así que los diez reservan un seq. Nueve chocan en el INSERT y deben revertir su
   * reserva; si no, el canal quedaría con last_seq = 10 y nueve huecos.
   */
  @Test
  void tenSimultaneousRetriesOfOneMessage_storeItOnce_andLeaveNoSeqGap() throws Exception {
    User author = authors.get(1);
    SendMessageRequest req = new SendMessageRequest(UUID.randomUUID(), "una sola vez");
    List<Callable<Sent>> retries = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      retries.add(() -> service.send(author.getId(), channel, req));
    }

    List<Sent> results = allAtOnce(retries);

    assertThat(results).filteredOn(s -> !s.replayed()).hasSize(1);
    assertThat(results)
        .extracting(s -> s.message().id())
        .containsOnly(results.get(0).message().id());
    assertThat(results).extracting(s -> s.message().seq()).containsOnly(1L);
    assertThat(storedSeqs()).containsExactly(1L);
    assertThat(lastSeq()).isEqualTo(1);

    // El siguiente mensaje del canal es el 2: ningún reintento se quedó con un número.
    Sent next =
        service.send(author.getId(), channel, new SendMessageRequest(UUID.randomUUID(), "x"));
    assertThat(next.message().seq()).isEqualTo(2);
  }
}
