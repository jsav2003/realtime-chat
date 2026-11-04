package com.jsav.chat.attachment;

import com.jsav.chat.config.StorageProperties;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 con el SDK v2. Hay dos conexiones a propósito: el {@link S3Client} habla con el {@code
 * endpoint} interno, y el {@link S3Presigner} firma contra el {@code publicEndpoint}, que es el que
 * el navegador alcanza.
 */
@Component
public class S3ObjectStorage implements ObjectStorage {

  private final StorageProperties props;
  private final S3Client client;
  private final S3Presigner presigner;

  public S3ObjectStorage(StorageProperties props) {
    this.props = props;
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    // Path-style (http://host/bucket/key): los emuladores no resuelven bucket.host.
    var s3Config = S3Configuration.builder().pathStyleAccessEnabled(true).build();
    this.client =
        S3Client.builder()
            .endpointOverride(URI.create(props.endpoint()))
            .region(Region.of(props.region()))
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Config)
            .build();
    this.presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(props.publicEndpoint()))
            .region(Region.of(props.region()))
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Config)
            .build();
  }

  @Override
  public PresignedUpload presignUpload(String key, String contentType, long size, Duration ttl) {
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(props.bucket())
            .key(key)
            .contentType(contentType)
            .contentLength(size)
            .build();
    var presigned =
        presigner.presignPutObject(
            PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(put).build());
    // Los encabezados firmados que el cliente debe repetir. Host lo pone el navegador y
    // Content-Length lo pone el propio navegador al mandar el cuerpo.
    Map<String, String> headers = new LinkedHashMap<>();
    presigned
        .signedHeaders()
        .forEach(
            (name, values) -> {
              String lower = name.toLowerCase();
              if (!lower.equals("host") && !lower.equals("content-length")) {
                headers.put(name, String.join(",", values));
              }
            });
    return new PresignedUpload(presigned.url().toString(), headers);
  }

  @Override
  public String presignDownload(
      String key, String contentDisposition, String contentType, Duration ttl) {
    GetObjectRequest get =
        GetObjectRequest.builder()
            .bucket(props.bucket())
            .key(key)
            .responseContentDisposition(contentDisposition)
            .responseContentType(contentType)
            .build();
    return presigner
        .presignGetObject(
            GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build())
        .url()
        .toString();
  }

  @Override
  public Optional<ObjectInfo> head(String key) {
    try {
      var response =
          client.headObject(HeadObjectRequest.builder().bucket(props.bucket()).key(key).build());
      return Optional.of(new ObjectInfo(response.contentLength()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      // HEAD no trae cuerpo, así que un 404 llega como S3Exception genérica.
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public void delete(String key) {
    client.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket()).key(key).build());
  }

  @PreDestroy
  void close() {
    presigner.close();
    client.close();
  }
}
