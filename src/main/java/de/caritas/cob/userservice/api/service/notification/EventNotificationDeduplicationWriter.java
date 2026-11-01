package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates unique-key races so a duplicate cannot mark the caller's transaction rollback-only. */
@Service
@RequiredArgsConstructor
public class EventNotificationDeduplicationWriter {

  private final @NonNull EventNotificationRepository eventNotificationRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void persistInNewTransaction(EventNotification eventNotification) {
    eventNotificationRepository.saveAndFlush(eventNotification);
  }

  /** Removes one deduplicated event; its own transaction, as callers run after a commit. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deleteInNewTransaction(String recipientUserId, String deduplicationKey) {
    eventNotificationRepository.deleteByRecipientUserIdAndDeduplicationKey(
        recipientUserId, deduplicationKey);
  }
}
