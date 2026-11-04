package com.jsav.chat.attachment;

import com.jsav.chat.config.StorageProperties;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Borra los adjuntos huérfanos (ADR 0014): los que pidieron una URL, o hasta subieron el archivo, y
 * nunca llegaron a un mensaje. Sin esto, cada subida abandonada dejaría un objeto en S3 para
 * siempre.
 *
 * <p>Cada vuelta es una transacción, como la del publicador de la outbox: bloquea un lote con
 * {@code FOR UPDATE SKIP LOCKED}, <b>borra primero los objetos</b> y recién después las filas. Si
 * el proceso muere en medio, las filas siguen ahí y la próxima vuelta repite el borrado (borrar un
 * objeto que ya no está no falla). Al revés se perderían las claves y quedarían objetos sin dueño.
 *
 * <p>Es seguro con dos instancias: lo que una tiene bloqueado, la otra lo salta. Y es seguro contra
 * un envío que reclama el adjunto justo ahora: el {@code UPDATE} del envío espera el lock, ve la
 * fila borrada, cuenta menos filas y el envío se revierte con un 409.
 *
 * <p>Quien lo llama cada tanto es {@link AttachmentCleanerJob}, aparte para poder apagarlo y
 * manejar este a mano en las pruebas.
 */
@Component
public class AttachmentCleaner {

  private static final Logger log = LoggerFactory.getLogger(AttachmentCleaner.class);
  static final int BATCH = 100;

  private final AttachmentRepository attachments;
  private final ObjectStorage storage;
  private final TransactionTemplate tx;
  private final StorageProperties props;

  public AttachmentCleaner(
      AttachmentRepository attachments,
      ObjectStorage storage,
      TransactionTemplate tx,
      StorageProperties props) {
    this.attachments = attachments;
    this.storage = storage;
    this.tx = tx;
    this.props = props;
  }

  /**
   * Una vuelta: borra un lote de huérfanos y devuelve cuántos. Si S3 falla, la transacción se
   * revierte y las filas quedan para la próxima. Público para manejarlo desde las pruebas.
   */
  public int cleanOnce() {
    Integer deleted =
        tx.execute(
            status -> {
              List<Attachment> orphans =
                  attachments.lockOrphans(Instant.now().minus(props.orphanAge()), BATCH);
              for (Attachment a : orphans) {
                storage.delete(a.s3Key());
              }
              attachments.deleteAll(orphans.stream().map(Attachment::id).toList());
              return orphans.size();
            });
    return deleted == null ? 0 : deleted;
  }

  /** Las vueltas necesarias hasta que no queden huérfanos viejos. Nunca lanza. */
  void cleanAll() {
    try {
      while (cleanOnce() >= BATCH) {
        // puede haber más esperando
      }
    } catch (RuntimeException e) {
      log.warn("No se pudieron limpiar los adjuntos huérfanos en esta vuelta; se reintenta", e);
    }
  }
}
