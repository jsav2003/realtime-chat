package com.jsav.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestUsers;
import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.attachment.AttachmentDtos.UploadRequest;
import com.jsav.chat.attachment.AttachmentDtos.UploadResponse;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.BadRequestException;
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
import org.springframework.jdbc.core.simple.JdbcClient;

/** Paso 1 de un adjunto: pedir la URL de subida (ADR 0014). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AttachmentUploadIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();
  private static final long MAX = 10L * 1024 * 1024;

  @Autowired AttachmentService service;
  @Autowired AttachmentRepository attachments;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired JdbcClient jdbc;

  User member;
  User outsider;
  Long channel;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    User owner = users.save(TestUsers.user("att" + run + "-owner@x.test", "x", "Owner"));
    member = users.save(TestUsers.user("att" + run + "-member@x.test", "x", "Member"));
    outsider = users.save(TestUsers.user("att" + run + "-out@x.test", "x", "Outsider"));
    Long serverId = serverService.create(owner.getId(), new CreateServerRequest("Att" + run)).id();
    serverMembers.save(new ServerMember(serverId, member.getId(), Role.MEMBER));
    channel = channelService.list(owner.getId(), serverId).get(0).id();
  }

  private long rows() {
    return jdbc.sql("select count(*) from attachments where channel_id = :c")
        .param("c", channel)
        .query(Long.class)
        .single();
  }

  private static UploadRequest req(String name, String type, long size) {
    return new UploadRequest(name, type, size);
  }

  @Test
  void requestUpload_createsAPendingAttachmentAndASignedUrl() throws Exception {
    byte[] bytes = "contenido".getBytes();

    UploadResponse res =
        service.requestUpload(member.getId(), channel, req("Nota.TXT", "text/plain", bytes.length));

    Attachment saved = attachments.findById(res.id()).orElseThrow();
    assertThat(saved.isUploaded()).isFalse();
    assertThat(saved.isAttached()).isFalse();
    assertThat(saved.uploaderId()).isEqualTo(member.getId());
    assertThat(saved.channelId()).isEqualTo(channel);
    assertThat(saved.filename()).isEqualTo("Nota.TXT");
    assertThat(saved.size()).isEqualTo(bytes.length);
    // El nombre del usuario no entra en la clave del objeto.
    assertThat(saved.s3Key()).startsWith("channels/" + channel + "/").doesNotContain("Nota");
    assertThat(res.expiresAt()).isAfter(java.time.Instant.now());

    var put =
        HttpRequest.newBuilder(URI.create(res.uploadUrl())).PUT(BodyPublishers.ofByteArray(bytes));
    res.headers().forEach(put::header);
    assertThat(HttpClient.newHttpClient().send(put.build(), BodyHandlers.discarding()).statusCode())
        .isEqualTo(200);
  }

  @Test
  void requestUpload_normalizesTheContentType() {
    UploadResponse res =
        service.requestUpload(member.getId(), channel, req("a.png", " Image/PNG ", 10));

    assertThat(attachments.findById(res.id()).orElseThrow().contentType()).isEqualTo("image/png");
  }

  @Test
  void requestUpload_acceptsExactlyTheMaximum_andRejectsOneByteMore() {
    service.requestUpload(member.getId(), channel, req("max.bin", "application/octet-stream", MAX));
    long before = rows();

    assertThatThrownBy(
            () ->
                service.requestUpload(
                    member.getId(), channel, req("big.bin", "application/octet-stream", MAX + 1)))
        .isInstanceOf(BadRequestException.class);
    assertThat(rows()).isEqualTo(before);
  }

  @Test
  void requestUpload_rejectsBadNamesAndTypes_withoutCreatingARow() {
    long before = rows();
    String[][] bad = {
      {"../secreto", "text/plain"},
      {"a/b.txt", "text/plain"},
      {"a\\b.txt", "text/plain"},
      {"..", "text/plain"},
      {"a\nb.txt", "text/plain"},
      {"ok.txt", "texto"},
      {"ok.txt", "text/plain; charset=utf-8"},
      {"ok.txt", "text/"},
    };
    for (String[] b : bad) {
      assertThatThrownBy(() -> service.requestUpload(member.getId(), channel, req(b[0], b[1], 5)))
          .as("%s %s", b[0], b[1])
          .isInstanceOf(BadRequestException.class);
    }
    assertThat(rows()).isEqualTo(before);
  }

  @Test
  void requestUpload_forAStranger_is404_andCreatesNothing() {
    long before = rows();

    assertThatThrownBy(
            () -> service.requestUpload(outsider.getId(), channel, req("a.txt", "text/plain", 5)))
        .isInstanceOf(NotFoundException.class);
    assertThat(rows()).isEqualTo(before);
  }
}
