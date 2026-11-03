package com.jsav.chat.attachment;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.attachment.AttachmentDtos.AttachmentInfo;
import com.jsav.chat.attachment.AttachmentDtos.UploadRequest;
import com.jsav.chat.attachment.AttachmentDtos.UploadResponse;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.config.StorageProperties;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentService {

  /** {@code tipo/subtipo} sin parámetros: lo que S3 guarda y devuelve tal cual. */
  private static final Pattern CONTENT_TYPE =
      Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");

  private final AttachmentRepository attachments;
  private final AccessPolicy policy;
  private final ObjectStorage storage;
  private final StorageProperties props;

  public AttachmentService(
      AttachmentRepository attachments,
      AccessPolicy policy,
      ObjectStorage storage,
      StorageProperties props) {
    this.attachments = attachments;
    this.policy = policy;
    this.storage = storage;
    this.props = props;
  }

  /**
   * Paso 1: el cliente declara nombre, tipo y tamaño. Se crea el adjunto pendiente y se firma un
   * PUT con ese tamaño y ese tipo dentro de la firma (ADR 0014).
   *
   * <p>Quien puede escribir en el canal puede subir; un extraño recibe 404 de {@link AccessPolicy}
   * antes de que se valide nada.
   */
  @Transactional
  public UploadResponse requestUpload(Long userId, Long channelId, UploadRequest req) {
    policy.requireChannel(userId, channelId, ChannelAction.WRITE);

    String filename = validFilename(req.filename());
    String contentType = validContentType(req.contentType());
    if (req.size() > props.maxSize().toBytes()) {
      throw new BadRequestException(
          "El archivo pesa más del máximo de " + props.maxSize().toMegabytes() + " MB");
    }

    // La clave no lleva el nombre del usuario: nada que escapar y nada que adivinar.
    String key = "channels/" + channelId + "/" + UUID.randomUUID();
    Attachment pending =
        attachments.insertPending(channelId, userId, key, filename, req.size(), contentType);
    var upload = storage.presignUpload(key, contentType, req.size(), props.uploadTtl());
    return new UploadResponse(
        pending.id(), upload.url(), upload.headers(), Instant.now().plus(props.uploadTtl()));
  }

  /**
   * Paso 2: el cliente avisa que ya subió el archivo. Solo el autor puede, y solo mientras pueda
   * escribir en el canal (un expulsado recibe 404). Se mira el objeto en S3 con un {@code HEAD}: si
   * no está es 409, y si su tamaño no es el declarado se borra y es 400. Es lo que hace cumplir el
   * tamaño aunque alguien se salte la firma (ADR 0014).
   *
   * <p>Repetirlo después de confirmado devuelve lo mismo. No lleva {@code @Transactional}: el
   * borrado de un adjunto inválido no debe revertirse al lanzar la excepción.
   */
  public AttachmentInfo complete(Long userId, Long attachmentId) {
    Attachment a = ownPending(userId, attachmentId);
    if (a.isUploaded()) {
      return AttachmentInfo.from(a);
    }

    var object = storage.head(a.s3Key());
    if (object.isEmpty()) {
      throw new ConflictException("El archivo todavía no se subió");
    }
    long actual = object.get().size();
    if (actual != a.size() || actual > props.maxSize().toBytes()) {
      // Un objeto que no es lo que se declaró no se conserva ni se puede reutilizar.
      storage.delete(a.s3Key());
      attachments.delete(a.id());
      throw new BadRequestException("El archivo subido no pesa lo que se declaró");
    }
    attachments.markUploaded(a.id());
    return AttachmentInfo.from(attachments.findById(a.id()).orElseThrow());
  }

  /** El adjunto es del usuario y él todavía puede escribir en su canal; si no, 404. */
  private Attachment ownPending(Long userId, Long attachmentId) {
    Attachment a =
        attachments
            .findById(attachmentId)
            .filter(found -> found.uploaderId().equals(userId))
            .orElseThrow(() -> new NotFoundException("Adjunto no encontrado"));
    policy.requireChannel(userId, a.channelId(), ChannelAction.WRITE);
    return a;
  }

  private static String validFilename(String raw) {
    String name = raw.strip();
    if (name.isEmpty() || name.length() > 255) {
      throw new BadRequestException("El nombre del archivo debe tener entre 1 y 255 caracteres");
    }
    if (name.equals(".") || name.equals("..")) {
      throw new BadRequestException("El nombre del archivo no es válido");
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '/' || c == '\\' || Character.isISOControl(c)) {
        throw new BadRequestException("El nombre del archivo no puede llevar barras ni control");
      }
    }
    return name;
  }

  private static String validContentType(String raw) {
    String type = raw.strip().toLowerCase();
    if (type.length() > 100 || !CONTENT_TYPE.matcher(type).matches()) {
      throw new BadRequestException("El tipo del archivo debe ser tipo/subtipo, como image/png");
    }
    return type;
  }
}
