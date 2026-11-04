package com.jsav.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestUsers;
import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.attachment.AttachmentDtos.AttachmentInfo;
import com.jsav.chat.attachment.AttachmentDtos.UploadRequest;
import com.jsav.chat.attachment.AttachmentDtos.UploadResponse;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.message.MessageDtos.MessageResponse;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Paso 3 de un adjunto: enviar el mensaje que lo lleva (ADR 0014). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AttachmentSendIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired AttachmentService attachmentService;
  @Autowired MessageService messages;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  User owner;
  User member;
  Long channel;
  Long otherChannel;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(TestUsers.user("snd" + run + "-owner@x.test", "x", "Owner"));
    member = users.save(TestUsers.user("snd" + run + "-member@x.test", "x", "Member"));
    Long serverId = serverService.create(owner.getId(), new CreateServerRequest("Snd" + run)).id();
    serverMembers.save(new ServerMember(serverId, member.getId(), Role.MEMBER));
    channel = channelService.list(owner.getId(), serverId).get(0).id();
    otherChannel =
        channelService
            .create(owner.getId(), serverId, new CreateChannelRequest("otro", "text"))
            .id();
  }

  /** Pide, sube y confirma: un adjunto listo para enviarse. */
  private Long ready(User who, Long inChannel, String name, int bytes) throws Exception {
    UploadResponse up =
        attachmentService.requestUpload(
            who.getId(), inChannel, new UploadRequest(name, "text/plain", bytes));
    var put =
        HttpRequest.newBuilder(URI.create(up.uploadUrl()))
            .PUT(BodyPublishers.ofByteArray(new byte[bytes]));
    up.headers().forEach(put::header);
    assertThat(http.send(put.build(), BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    attachmentService.complete(who.getId(), up.id());
    return up.id();
  }

  private Long pending(User who, Long inChannel) {
    return attachmentService
        .requestUpload(who.getId(), inChannel, new UploadRequest("p.txt", "text/plain", 5))
        .id();
  }

  private long lastSeq(Long channelId) {
    return jdbc.sql("select last_seq from channels where id = :id")
        .param("id", channelId)
        .query(Long.class)
        .single();
  }

  private Sent send(User who, Long inChannel, UUID key, String body, List<Long> ids) {
    return messages.send(who.getId(), inChannel, new SendMessageRequest(key, body, ids));
  }

  @Test
  void send_withAttachments_returnsThemAndListsThemInOrder() throws Exception {
    Long a = ready(member, channel, "uno.txt", 10);
    Long b = ready(member, channel, "dos.txt", 20);

    Sent sent = send(member, channel, UUID.randomUUID(), "mira", List.of(b, a));

    assertThat(sent.message().attachments())
        .containsExactly(
            new AttachmentInfo(a, "uno.txt", 10, "text/plain"),
            new AttachmentInfo(b, "dos.txt", 20, "text/plain"));
    MessageResponse listed =
        messages.list(owner.getId(), channel, null, null, 50).messages().get(0);
    assertThat(listed.attachments()).isEqualTo(sent.message().attachments());
  }

  @Test
  void send_onlyAttachments_withNoText_isAMessage() throws Exception {
    Long a = ready(member, channel, "solo.txt", 10);

    Sent sent = send(member, channel, UUID.randomUUID(), "", List.of(a));

    assertThat(sent.message().body()).isEmpty();
    assertThat(sent.message().seq()).isEqualTo(1);
    assertThat(sent.message().attachments()).hasSize(1);
  }

  @Test
  void send_retryWithTheSameKey_returnsTheSameMessageWithItsAttachments() throws Exception {
    Long a = ready(member, channel, "uno.txt", 10);
    UUID key = UUID.randomUUID();

    Sent first = send(member, channel, key, "mira", List.of(a));
    Sent retry = send(member, channel, key, "mira", List.of(a));

    assertThat(first.replayed()).isFalse();
    assertThat(retry.replayed()).isTrue();
    assertThat(retry.message().id()).isEqualTo(first.message().id());
    assertThat(retry.message().attachments()).isEqualTo(first.message().attachments());
    assertThat(lastSeq(channel)).isEqualTo(1);
  }

  @Test
  void send_withMoreThanTheMaximum_is400_andLeavesNoGapInTheSequence() throws Exception {
    List<Long> five =
        List.of(
            ready(member, channel, "1", 5),
            ready(member, channel, "2", 5),
            ready(member, channel, "3", 5),
            ready(member, channel, "4", 5),
            ready(member, channel, "5", 5));

    assertThatThrownBy(() -> send(member, channel, UUID.randomUUID(), "x", five))
        .isInstanceOf(BadRequestException.class);

    assertThat(lastSeq(channel)).isZero();
  }

  @Test
  void send_rejectsRepeatedStrangeAndForeignAttachments_withoutMovingTheSequence()
      throws Exception {
    Long mine = ready(member, channel, "mio.txt", 5);
    Long ownersFile = ready(owner, channel, "ajeno.txt", 5);
    Long elsewhere = ready(member, otherChannel, "otro-canal.txt", 5);

    assertThatThrownBy(() -> send(member, channel, UUID.randomUUID(), "x", List.of(mine, mine)))
        .as("repetido")
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> send(member, channel, UUID.randomUUID(), "x", List.of(ownersFile)))
        .as("de otro usuario")
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> send(member, channel, UUID.randomUUID(), "x", List.of(elsewhere)))
        .as("de otro canal")
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> send(member, channel, UUID.randomUUID(), "x", List.of(Long.MAX_VALUE)))
        .as("inexistente")
        .isInstanceOf(BadRequestException.class);

    assertThat(lastSeq(channel)).isZero();
  }

  @Test
  void send_withAPendingOrAlreadyUsedAttachment_is409_andLeavesNoGap() throws Exception {
    Long notConfirmed = pending(member, channel);
    Long used = ready(member, channel, "usado.txt", 5);
    send(member, channel, UUID.randomUUID(), "primero", List.of(used));
    long afterFirst = lastSeq(channel);

    assertThatThrownBy(() -> send(member, channel, UUID.randomUUID(), "x", List.of(notConfirmed)))
        .as("sin confirmar")
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> send(member, channel, UUID.randomUUID(), "x", List.of(used)))
        .as("ya usado")
        .isInstanceOf(ConflictException.class);

    assertThat(lastSeq(channel)).isEqualTo(afterFirst);
  }
}
