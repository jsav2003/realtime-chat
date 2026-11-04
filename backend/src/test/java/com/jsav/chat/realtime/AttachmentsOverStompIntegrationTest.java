package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.jsav.chat.attachment.AttachmentDtos.UploadRequest;
import com.jsav.chat.attachment.AttachmentDtos.UploadResponse;
import com.jsav.chat.attachment.AttachmentService;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Lo que sale por el topic del canal lo ve cualquiera que esté suscrito, incluso quien después deja
 * de tener acceso. Por eso un mensaje con adjuntos publica solo su descripción (id, nombre, tamaño
 * y tipo): ninguna URL ni la clave de S3. Para bajar el archivo hay que pedir una URL por HTTP,
 * donde se comprueba el acceso de ese momento (ADR 0014).
 */
class AttachmentsOverStompIntegrationTest extends RealtimeIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired AttachmentService attachmentService;

  @Test
  void aMessageWithAttachmentsArrivesLiveWithTheirDescription_andNoUrlOrKey() throws Exception {
    UploadResponse up =
        attachmentService.requestUpload(
            member.getId(), general, new UploadRequest("plano.pdf", "application/pdf", 16));
    var put =
        HttpRequest.newBuilder(URI.create(up.uploadUrl()))
            .PUT(BodyPublishers.ofByteArray(new byte[16]));
    up.headers().forEach(put::header);
    assertThat(HttpClient.newHttpClient().send(put.build(), BodyHandlers.discarding()).statusCode())
        .isEqualTo(200);
    attachmentService.complete(member.getId(), up.id());

    try (StompTestClient listener = connect(owner)) {
      assertThat(listener.subscribe(ChannelTopic.of(general))).isNull();

      messages.send(
          member.getId(),
          general,
          new SendMessageRequest(UUID.randomUUID(), "mira el plano", List.of(up.id())));

      String frame = listener.nextMessage(WAIT);
      assertThat(frame).isNotNull();
      assertThat((Integer) JsonPath.read(frame, "$.attachments.length()")).isEqualTo(1);
      assertThat((String) JsonPath.read(frame, "$.attachments[0].filename")).isEqualTo("plano.pdf");
      assertThat((Integer) JsonPath.read(frame, "$.attachments[0].size")).isEqualTo(16);
      assertThat((String) JsonPath.read(frame, "$.attachments[0].contentType"))
          .isEqualTo("application/pdf");
      // Nada que sirva para bajar el archivo sin pasar por HTTP.
      assertThat(frame)
          .doesNotContain("http")
          .doesNotContain("channels/" + general + "/")
          .doesNotContain("X-Amz")
          .doesNotContain("s3Key")
          .doesNotContain("url");
    }
  }

  @Test
  void aTextOnlyMessageStillArrivesWithAnEmptyList() {
    try (StompTestClient listener = connect(owner)) {
      assertThat(listener.subscribe(ChannelTopic.of(general))).isNull();

      send(member, general, "solo texto");

      String frame = listener.nextMessage(WAIT);
      assertThat((Integer) JsonPath.read(frame, "$.attachments.length()")).isZero();
    }
  }
}
