package com.jsav.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * El almacén de objetos (S3) de los adjuntos (hito 7).
 *
 * <p>{@code endpoint} es el que usa el backend para hablar con S3 ({@code http://s3:9090} dentro de
 * Docker). {@code publicEndpoint} es el que se firma en las URLs que recibe el navegador ({@code
 * http://localhost:9090}): la firma incluye el host, así que no se pueden mezclar.
 *
 * <p>{@code maxSize} es el tope por archivo y {@code maxPerMessage} cuántos caben en un mensaje.
 * {@code orphanAge} es cuánto vive un adjunto que ningún mensaje reclamó antes de que el limpiador
 * lo borre; tiene que ser mayor que {@code uploadTtl}, o se borraría una subida todavía válida.
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
    String endpoint,
    String publicEndpoint,
    String bucket,
    String region,
    String accessKey,
    String secretKey,
    Duration uploadTtl,
    Duration downloadTtl,
    DataSize maxSize,
    int maxPerMessage,
    Duration orphanAge) {

  public StorageProperties {
    require(endpoint, "endpoint");
    require(publicEndpoint, "public-endpoint");
    require(bucket, "bucket");
    require(region, "region");
    require(accessKey, "access-key");
    require(secretKey, "secret-key");
    if (uploadTtl.isZero() || uploadTtl.isNegative()) {
      throw new IllegalArgumentException("app.storage.upload-ttl debe ser positivo");
    }
    if (downloadTtl.isZero() || downloadTtl.isNegative()) {
      throw new IllegalArgumentException("app.storage.download-ttl debe ser positivo");
    }
    if (maxSize.toBytes() < 1) {
      throw new IllegalArgumentException("app.storage.max-size debe ser positivo");
    }
    if (maxPerMessage < 1) {
      throw new IllegalArgumentException("app.storage.max-per-message debe ser al menos 1");
    }
    if (orphanAge.compareTo(uploadTtl) <= 0) {
      throw new IllegalArgumentException(
          "app.storage.orphan-age debe ser mayor que app.storage.upload-ttl");
    }
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("app.storage." + name + " es obligatorio");
    }
  }
}
