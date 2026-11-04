package com.jsav.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.TestUsers;
import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.attachment.AttachmentDtos.UploadRequest;
import com.jsav.chat.attachment.AttachmentDtos.UploadResponse;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Un adjunto pertenece a un solo mensaje (ADR 0014). Diez mensajes distintos, a la vez, compiten
 * por el mismo adjunto: entra exactamente uno, y los demás se revierten enteros, con su {@code
 * seq}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AttachmentClaimConcurrencyIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();
  private static final int RACERS = 10;

  @Autowired AttachmentService attachmentService;
  @Autowired AttachmentRepository attachments;
  @Autowired MessageService messages;
  @Autowired UserRepository users;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired JdbcClient jdbc;

  @Test
  void tenMessagesRaceForOneAttachment_exactlyOneWins_andNoSeqIsLost() throws Exception {
    int run = RUNS.incrementAndGet();
    User author = users.save(TestUsers.user("race" + run + "@x.test", "x", "Autor"));
    Long serverId =
        serverService.create(author.getId(), new CreateServerRequest("Race" + run)).id();
    Long channel = channelService.list(author.getId(), serverId).get(0).id();

    UploadResponse up =
        attachmentService.requestUpload(
            author.getId(), channel, new UploadRequest("unico.txt", "text/plain", 8));
    var put =
        HttpRequest.newBuilder(URI.create(up.uploadUrl()))
            .PUT(BodyPublishers.ofByteArray(new byte[8]));
    up.headers().forEach(put::header);
    assertThat(HttpClient.newHttpClient().send(put.build(), BodyHandlers.discarding()).statusCode())
        .isEqualTo(200);
    attachmentService.complete(author.getId(), up.id());

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(RACERS);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < RACERS; i++) {
      Callable<Boolean> send =
          () -> {
            start.await();
            try {
              messages.send(
                  author.getId(),
                  channel,
                  new SendMessageRequest(UUID.randomUUID(), "yo lo quiero", List.of(up.id())));
              return true;
            } catch (ConflictException e) {
              return false;
            }
          };
      results.add(pool.submit(send));
    }
    start.countDown();
    int won = 0;
    for (Future<Boolean> f : results) {
      if (f.get()) {
        won++;
      }
    }
    pool.shutdown();

    assertThat(won).as("mensajes que se quedaron con el adjunto").isEqualTo(1);
    assertThat(
            jdbc.sql("select count(*) from messages where channel_id = :c")
                .param("c", channel)
                .query(Long.class)
                .single())
        .as("mensajes guardados")
        .isEqualTo(1);
    // Los nueve que perdieron devolvieron su seq: no hay huecos.
    assertThat(
            jdbc.sql("select last_seq from channels where id = :c")
                .param("c", channel)
                .query(Long.class)
                .single())
        .as("last_seq")
        .isEqualTo(1);
    assertThat(attachments.findById(up.id()).orElseThrow().isAttached()).isTrue();
  }
}
