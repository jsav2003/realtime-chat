package com.jsav.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** El almacén contra un S3 de verdad (Adobe S3Mock): subir con una URL firmada, mirar y bajar. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class S3ObjectStorageIntegrationTest {

  private static final Duration TTL = Duration.ofMinutes(1);

  @Autowired ObjectStorage storage;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void uploadWithPresignedUrlThenHeadAndDownload() throws Exception {
    String key = "test/" + UUID.randomUUID();
    byte[] bytes = "hola adjunto".getBytes();

    var upload = storage.presignUpload(key, "text/plain", bytes.length, TTL);
    var put =
        HttpRequest.newBuilder(URI.create(upload.url())).PUT(BodyPublishers.ofByteArray(bytes));
    upload.headers().forEach(put::header);
    assertThat(http.send(put.build(), BodyHandlers.discarding()).statusCode()).isEqualTo(200);

    assertThat(storage.head(key)).hasValueSatisfying(info -> assertThat(info.size()).isEqualTo(12));

    String url =
        storage.presignDownload(key, "attachment; filename=\"nota.txt\"", "text/plain", TTL);
    var get =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofByteArray());
    assertThat(get.statusCode()).isEqualTo(200);
    assertThat(get.body()).isEqualTo(bytes);
    assertThat(get.headers().firstValue("Content-Disposition"))
        .hasValue("attachment; filename=\"nota.txt\"");
  }

  @Test
  void headOfMissingObjectIsEmptyAndDeleteIsIdempotent() {
    String key = "test/" + UUID.randomUUID();
    assertThat(storage.head(key)).isEmpty();
    storage.delete(key);
    storage.delete(key);
  }

  @Test
  void uploadUrlSignsContentLengthAndContentType() {
    var upload = storage.presignUpload("test/" + UUID.randomUUID(), "image/png", 1234, TTL);

    // El tamaño y el tipo están dentro de la firma: sin esto, el límite sería solo del cliente.
    assertThat(upload.url()).contains("X-Amz-SignedHeaders=");
    String signed = URI.create(upload.url()).getQuery().toLowerCase();
    assertThat(signed).contains("content-length").contains("content-type");
    assertThat(upload.headers()).containsEntry("content-type", "image/png");
  }
}
