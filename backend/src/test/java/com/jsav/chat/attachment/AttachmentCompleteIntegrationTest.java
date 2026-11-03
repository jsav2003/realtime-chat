package com.jsav.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestUsers;
import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.attachment.AttachmentDtos.AttachmentInfo;
import com.jsav.chat.attachment.AttachmentDtos.UploadRequest;
import com.jsav.chat.attachment.AttachmentDtos.UploadResponse;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.common.NotFoundException;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Paso 2 de un adjunto: confirmar la subida con un HEAD (ADR 0014). Contra un S3 de verdad. Adobe
 * S3Mock no valida la firma de las URLs, así que aquí se sube sin respetar el tamaño firmado a
 * propósito: es justo lo que el HEAD tiene que atrapar.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AttachmentCompleteIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired AttachmentService service;
  @Autowired AttachmentRepository attachments;
  @Autowired ObjectStorage storage;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;

  private final HttpClient http = HttpClient.newHttpClient();

  User owner;
  User member;
  User colleague;
  Long serverId;
  Long channel;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(TestUsers.user("cmp" + run + "-owner@x.test", "x", "Owner"));
    member = users.save(TestUsers.user("cmp" + run + "-member@x.test", "x", "Member"));
    colleague = users.save(TestUsers.user("cmp" + run + "-col@x.test", "x", "Colleague"));
    serverId = serverService.create(owner.getId(), new CreateServerRequest("Cmp" + run)).id();
    serverMembers.save(new ServerMember(serverId, member.getId(), Role.MEMBER));
    serverMembers.save(new ServerMember(serverId, colleague.getId(), Role.MEMBER));
    channel = channelService.list(owner.getId(), serverId).get(0).id();
  }

  private UploadResponse request(User who, long declared) {
    return service.requestUpload(
        who.getId(), channel, new UploadRequest("doc.txt", "text/plain", declared));
  }

  private void put(UploadResponse upload, int bytes) throws Exception {
    var put =
        HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
            .PUT(BodyPublishers.ofByteArray(new byte[bytes]));
    upload.headers().forEach(put::header);
    assertThat(http.send(put.build(), BodyHandlers.discarding()).statusCode()).isEqualTo(200);
  }

  @Test
  void complete_afterTheUpload_confirmsIt_andRepeatingItChangesNothing() throws Exception {
    UploadResponse upload = request(member, 25);
    put(upload, 25);

    AttachmentInfo first = service.complete(member.getId(), upload.id());
    var confirmedAt = attachments.findById(upload.id()).orElseThrow().uploadedAt();
    AttachmentInfo second = service.complete(member.getId(), upload.id());

    assertThat(first).isEqualTo(new AttachmentInfo(upload.id(), "doc.txt", 25, "text/plain"));
    assertThat(second).isEqualTo(first);
    assertThat(confirmedAt).isNotNull();
    assertThat(attachments.findById(upload.id()).orElseThrow().uploadedAt()).isEqualTo(confirmedAt);
  }

  @Test
  void complete_beforeTheUpload_is409_andTheAttachmentStaysPending() {
    UploadResponse upload = request(member, 25);

    assertThatThrownBy(() -> service.complete(member.getId(), upload.id()))
        .isInstanceOf(ConflictException.class);
    assertThat(attachments.findById(upload.id()).orElseThrow().isUploaded()).isFalse();
  }

  @Test
  void complete_withAnObjectOfAnotherSize_isRejected_andObjectAndRowAreDeleted() throws Exception {
    UploadResponse upload = request(member, 25);
    String key = attachments.findById(upload.id()).orElseThrow().s3Key();
    put(upload, 26); // un cliente que se saltó el tamaño firmado

    assertThatThrownBy(() -> service.complete(member.getId(), upload.id()))
        .isInstanceOf(BadRequestException.class);

    assertThat(storage.head(key)).isEmpty();
    assertThat(attachments.findById(upload.id())).isEmpty();
  }

  @Test
  void complete_byAnotherMemberOfTheChannel_is404_andNothingChanges() throws Exception {
    UploadResponse upload = request(member, 25);
    put(upload, 25);

    assertThatThrownBy(() -> service.complete(colleague.getId(), upload.id()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.complete(owner.getId(), upload.id()))
        .isInstanceOf(NotFoundException.class);
    assertThat(attachments.findById(upload.id()).orElseThrow().isUploaded()).isFalse();
  }

  @Test
  void complete_byTheAuthorAfterBeingKicked_is404() throws Exception {
    UploadResponse upload = request(member, 25);
    put(upload, 25);
    serverService.kick(owner.getId(), serverId, member.getId());

    assertThatThrownBy(() -> service.complete(member.getId(), upload.id()))
        .isInstanceOf(NotFoundException.class);
    assertThat(attachments.findById(upload.id()).orElseThrow().isUploaded()).isFalse();
  }

  @Test
  void complete_ofAnAttachmentThatDoesNotExist_is404() {
    assertThatThrownBy(() -> service.complete(member.getId(), Long.MAX_VALUE))
        .isInstanceOf(NotFoundException.class);
  }
}
