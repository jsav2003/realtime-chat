package com.jsav.chat.attachment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Llama al limpiador de huérfanos cada {@code app.storage.cleaner.interval}. Se apaga con {@code
 * app.storage.cleaner.enabled=false}; las pruebas lo apagan y manejan {@link AttachmentCleaner} a
 * mano.
 */
@Component
@ConditionalOnProperty(
    name = "app.storage.cleaner.enabled",
    havingValue = "true",
    matchIfMissing = true)
class AttachmentCleanerJob {

  private final AttachmentCleaner cleaner;

  AttachmentCleanerJob(AttachmentCleaner cleaner) {
    this.cleaner = cleaner;
  }

  @Scheduled(
      initialDelayString = "${app.storage.cleaner.interval}",
      fixedDelayString = "${app.storage.cleaner.interval}")
  void tick() {
    cleaner.cleanAll();
  }
}
