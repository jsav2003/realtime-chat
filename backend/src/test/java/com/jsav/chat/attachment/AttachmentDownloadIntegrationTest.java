package com.jsav.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestUsers;
import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.attachment.AttachmentDtos.DownloadResponse;
import com.jsav.chat.attachment.AttachmentDtos.UploadRequest;
import com.jsav.chat.attachment.AttachmentDtos.UploadResponse;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Descargar: una URL de 60 s decidida con el acceso de ahora (ADR 0014). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AttachmentDownloadIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired AttachmentService service;
  @Autowired MessageService messages;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;

  private final HttpClient http = HttpClient.newHttpClient();

  User owner;
  User author;
  User reader;
  User outsider;
  Long serverId;
  Long channel;
  Long privateChannel;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(TestUsers.user("dl" + run + "-owner@x.test", "x", "Owner"));
    author = users.save(TestUsers.user("dl" + run + "-author@x.test", "x", "Autor"));
    reader = users.save(TestUsers.user("dl" + run + "-reader@x.test", "x", "Lector"));
    outsider = users.save(TestUsers.user("dl" + run + "-out@x.test", "x", "Ajeno"));
    serverId = serverService.create(owner.getId(), new CreateServerRequest("Dl" + run)).id();
    serverMembers.save(new ServerMember(serverId, author.getId(), Role.MEMBER));
    serverMembers.save(new ServerMember(serverId, reader.getId(), Role.MEMBER));
    channel = channelService.list(owner.getId(), serverId).get(0).id();
    privateChannel =
        channelService
            .create(owner.getId(), serverId, new CreateChannelRequest("secreto", "private"))
            .id();
    channelService.addMember(owner.getId(), privateChannel, author.getId());
  }

  private Long upload(
      User who, Long inChannel, String name, String type, byte[] bytes, boolean confirm)
      throws Exception {
    UploadResponse up =
        service.requestUpload(who.getId(), inChannel, new UploadRequest(name, type, bytes.length));
    var put =
        HttpRequest.newBuilder(URI.create(up.uploadUrl())).PUT(BodyPublishers.ofByteArray(bytes));
    up.headers().forEach(put::header);
    assertThat(http.send(put.build(), BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    if (confirm) {
      service.complete(who.getId(), up.id());
    }
    return up.id();
  }

  private void attach(User who, Long inChannel, Long attachmentId) {
    messages.send(
        who.getId(),
        inChannel,
        new SendMessageRequest(UUID.randomUUID(), "", List.of(attachmentId)));
  }

  private HttpResponse<byte[]> fetch(DownloadResponse download) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(download.url())).GET().build(),
        BodyHandlers.ofByteArray());
  }

  @Test
  void aReaderOfTheChannelDownloadsTheBytes_asAnAttachmentForAPdf() throws Exception {
    byte[] bytes = "%PDF-1.4 falso".getBytes();
    Long id = upload(author, channel, "informe final.pdf", "application/pdf", bytes, true);
    attach(author, channel, id);

    HttpResponse<byte[]> res = fetch(service.download(reader.getId(), id));

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).isEqualTo(bytes);
    assertThat(res.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("attachment; filename=\"informe final.pdf\"");
  }

  @Test
  void anImageIsShownInline() throws Exception {
    Long id = upload(author, channel, "foto.png", "image/png", new byte[] {1, 2, 3}, true);
    attach(author, channel, id);

    HttpResponse<byte[]> res = fetch(service.download(reader.getId(), id));

    assertThat(res.headers().firstValue("Content-Disposition")).hasValue("inline");
  }

  @Test
  void theDownloadUrlExpiresInSecondsAndIsNotTheStorageKey() throws Exception {
    Long id = upload(author, channel, "a.txt", "text/plain", new byte[] {1}, true);
    attach(author, channel, id);

    DownloadResponse download = service.download(reader.getId(), id);

    assertThat(download.expiresAt()).isBefore(java.time.Instant.now().plusSeconds(61));
    assertThat(download.url())
        .contains("X-Amz-Expires=60")
        .contains("response-content-type=application%2Foctet-stream");
  }

  @Test
  void aStrangerGets404() throws Exception {
    Long id = upload(author, channel, "a.txt", "text/plain", new byte[] {1}, true);
    attach(author, channel, id);

    assertThatThrownBy(() -> service.download(outsider.getId(), id))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aMemberWhoCannotSeeThePrivateChannelGets404_butTheOneInsideDoesNot() throws Exception {
    Long id = upload(author, privateChannel, "a.txt", "text/plain", new byte[] {1}, true);
    attach(author, privateChannel, id);

    assertThatThrownBy(() -> service.download(reader.getId(), id))
        .isInstanceOf(NotFoundException.class);
    assertThat(service.download(author.getId(), id).url()).isNotBlank();
  }

  @Test
  void aKickedMemberCanNoLongerDownload_evenSomethingHeAlreadyKnew() throws Exception {
    Long id = upload(author, channel, "a.txt", "text/plain", new byte[] {1}, true);
    attach(author, channel, id);
    assertThat(service.download(reader.getId(), id).url()).isNotBlank();

    serverService.kick(owner.getId(), serverId, reader.getId());

    assertThatThrownBy(() -> service.download(reader.getId(), id))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void anAttachmentNotYetInAMessageIsOnlyForItsAuthor() throws Exception {
    Long id = upload(author, channel, "borrador.txt", "text/plain", new byte[] {1}, true);

    assertThat(service.download(author.getId(), id).url()).isNotBlank();
    assertThatThrownBy(() -> service.download(reader.getId(), id))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.download(owner.getId(), id))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void anUnconfirmedUploadCannotBeDownloaded_notEvenByItsAuthor() throws Exception {
    Long id = upload(author, channel, "a.txt", "text/plain", new byte[] {1}, false);

    assertThatThrownBy(() -> service.download(author.getId(), id))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void anAttachmentThatDoesNotExistIs404() {
    assertThatThrownBy(() -> service.download(reader.getId(), Long.MAX_VALUE))
        .isInstanceOf(NotFoundException.class);
  }
}
