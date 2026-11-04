package com.jsav.chat.attachment;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Lo que el backend necesita de S3, y nada más. Es una interfaz para poder romper el almacén a
 * propósito en las pruebas.
 */
public interface ObjectStorage {

  /**
   * Una URL para subir un objeto con un PUT. {@code headers} son los encabezados que el cliente
   * tiene que mandar tal cual: están dentro de la firma.
   */
  record PresignedUpload(String url, Map<String, String> headers) {}

  /** Lo que S3 dice de un objeto que existe. */
  record ObjectInfo(long size) {}

  /**
   * Prepara la subida de exactamente {@code size} bytes de tipo {@code contentType}. El tamaño va
   * firmado: S3 rechaza un cuerpo de otra longitud.
   */
  PresignedUpload presignUpload(String key, String contentType, long size, Duration ttl);

  /**
   * Una URL temporal para bajar el objeto. {@code contentDisposition} se firma en la URL ({@code
   * attachment; filename="…"} o {@code inline}).
   */
  String presignDownload(String key, String contentDisposition, String contentType, Duration ttl);

  /** El objeto, o vacío si no existe. */
  Optional<ObjectInfo> head(String key);

  /** Borra el objeto; no falla si no existe. */
  void delete(String key);
}
