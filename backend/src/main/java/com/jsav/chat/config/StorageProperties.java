package com.jsav.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * El almacén de objetos (S3) de los adjuntos (hito 7).
 *
 * <p>{@code endpoint} es el que usa el backend para hablar con S3 ({@code http://s3mock:9090}
 * dentro de Docker). {@code publicEndpoint} es el que se firma en las URLs que recibe el navegador
 * ({@code http://localhost:9090}): la firma incluye el host, así que no se pueden mezclar.
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
    Duration downloadTtl) {

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
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("app.storage." + name + " es obligatorio");
    }
  }
}
