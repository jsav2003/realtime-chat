package com.jsav.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.TestUsers;
import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.attachment.AttachmentDtos.UploadRequest;
import com.jsav.chat.attachment.AttachmentDtos.UploadResponse;
import com.jsav.chat.channel.ChannelService;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** El limpiador de huérfanos (ADR 0014): se maneja con cleanOnce(), sin esperar al planificador. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AttachmentCleanerIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired AttachmentCleaner cleaner;
  @Autowired AttachmentService service;
  @Autowired AttachmentRepository attachments;
  @Autowired MessageService messages;
  @Autowired ObjectStorage storage;
  @Autowired UserRepository users;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  User author;
  Long channel;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    author = users.save(TestUsers.user("cln" + run + "@x.test", "x", "Autor"));
    Long serverId = serverService.create(author.getId(), new CreateServerRequest("Cln" + run)).id();
    channel = channelService.list(author.getId(), serverId).get(0).id();
    // Que el resto de las pruebas no deje huérfanos viejos que este test se lleve por delante.
    jdbc.sql("update attachments set created_at = now() where message_id is null").update();
  }

  private Long upload(boolean putBytes, boolean confirm) throws Exception {
    UploadResponse up =
        service.requestUpload(author.getId(), channel, new UploadRequest("a.txt", "text/plain", 8));
    if (putBytes) {
      var put =
          HttpRequest.newBuilder(URI.create(up.uploadUrl()))
              .PUT(BodyPublishers.ofByteArray(new byte[8]));
      up.headers().forEach(put::header);
      assertThat(http.send(put.build(), BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    }
    if (confirm) {
      service.complete(author.getId(), up.id());
    }
    return up.id();
  }

  private void makeOld(Long id) {
    jdbc.sql("update attachments set created_at = now() - interval '2 hours' where id = :id")
        .param("id", id)
        .update();
  }

  private String key(Long id) {
    return attachments.findById(id).orElseThrow().s3Key();
  }

  @Test
  void anOldOrphanIsDeletedWithItsObject_evenIfTheUploadWasNeverConfirmed() throws Exception {
    Long confirmed = upload(true, true);
    Long unconfirmed = upload(true, false);
    Long neverUploaded = upload(false, false);
    String confirmedKey = key(confirmed);
    String unconfirmedKey = key(unconfirmed);
    List.of(confirmed, unconfirmed, neverUploaded).forEach(this::makeOld);

    int deleted = cleaner.cleanOnce();

    assertThat(deleted).isEqualTo(3);
    assertThat(attachments.findByIds(List.of(confirmed, unconfirmed, neverUploaded))).isEmpty();
    assertThat(storage.head(confirmedKey)).isEmpty();
    assertThat(storage.head(unconfirmedKey)).isEmpty();
  }

  @Test
  void aYoungOrphanAndAnyAttachmentInAMessageAreLeftAlone() throws Exception {
    Long young = upload(true, true);
    Long attached = upload(true, true);
    messages.send(
        author.getId(),
        channel,
        new SendMessageRequest(UUID.randomUUID(), "con adjunto", List.of(attached)));
    makeOld(attached); // viejo, pero tiene mensaje: no es huérfano

    int deleted = cleaner.cleanOnce();

    assertThat(deleted).isZero();
    assertThat(attachments.findById(young)).isPresent();
    assertThat(attachments.findById(attached)).isPresent();
    assertThat(storage.head(key(young))).isPresent();
    assertThat(storage.head(key(attached))).isPresent();
  }

  @Test
  void aRowAnotherTransactionHasLockedIsSkipped_thenCleanedWhenItIsReleased() throws Exception {
    Long id = upload(true, true);
    makeOld(id);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    // Otra instancia (o un envío que reclama el adjunto) tiene la fila tomada.
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      jdbc.sql("select id from attachments where id = :id for update")
                          .param("id", id)
                          .query(Long.class)
                          .single();
                      locked.countDown();
                      try {
                        release.await(30, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

    assertThat(cleaner.cleanOnce()).as("mientras está bloqueada").isZero();
    assertThat(attachments.findById(id)).isPresent();

    release.countDown();
    holder.get(30, TimeUnit.SECONDS);
    assertThat(cleaner.cleanOnce()).as("ya liberada").isEqualTo(1);
    assertThat(attachments.findById(id)).isEmpty();
  }

  @Test
  void twoCleanersAtTheSameTimeDeleteEachOrphanExactlyOnce() throws Exception {
    List<Long> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 6; i++) {
      Long id = upload(true, true);
      makeOld(id);
      ids.add(id);
    }

    CompletableFuture<Integer> a = CompletableFuture.supplyAsync(cleaner::cleanOnce);
    CompletableFuture<Integer> b = CompletableFuture.supplyAsync(cleaner::cleanOnce);
    int total = a.get(60, TimeUnit.SECONDS) + b.get(60, TimeUnit.SECONDS);
    total += cleaner.cleanOnce(); // lo que una saltó por estar tomado por la otra

    assertThat(total).isEqualTo(6);
    assertThat(attachments.findByIds(ids)).isEmpty();
  }

  @Test
  void aSendWithAnAttachmentTheCleanerAlreadyDeletedGets400AndLeavesNoGap() throws Exception {
    Long id = upload(true, true);
    makeOld(id);
    cleaner.cleanOnce();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                messages.send(
                    author.getId(),
                    channel,
                    new SendMessageRequest(UUID.randomUUID(), "tarde", List.of(id))))
        .isInstanceOf(com.jsav.chat.common.BadRequestException.class);
    assertThat(
            jdbc.sql("select last_seq from channels where id = :c")
                .param("c", channel)
                .query(Long.class)
                .single())
        .isZero();
  }
}
