package de.caritas.cob.userservice.api.service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates unique-key races so a duplicate cannot mark the caller's transaction rollback-only. */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventNotificationDeduplicationWriter {

  private final @NonNull EventNotificationRepository eventNotificationRepository;
  private final ObjectMapper paramsObjectMapper = new ObjectMapper();

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void persistInNewTransaction(EventNotification eventNotification) {
    eventNotificationRepository.saveAndFlush(eventNotification);
  }

  /**
   * Marks an already written message row as a mention (ORISO-UserService#1510). The Matrix listener
   * often writes the row first and knows nothing of mentions: {@code m.mentions} sits inside the
   * encrypted content. Its own transaction, so a row committed moments ago is visible here.
   *
   * @return true when the row changed, so the recipient's client needs to hear about it
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markMentionedInNewTransaction(String recipientUserId, String deduplicationKey) {
    var event =
        eventNotificationRepository
            .findByRecipientUserIdAndDeduplicationKey(recipientUserId, deduplicationKey)
            .orElse(null);
    if (event == null) {
      return false;
    }
    ObjectNode params = readParams(event.getParams());
    if (params.path("mentioned").asBoolean(false)) {
      return false;
    }
    params.put("mentioned", true);
    event.setParams(params.toString());
    eventNotificationRepository.saveAndFlush(event);
    return true;
  }

  private ObjectNode readParams(String params) {
    if (params != null) {
      try {
        if (paramsObjectMapper.readTree(params) instanceof ObjectNode parsed) {
          return parsed;
        }
      } catch (JsonProcessingException unreadable) {
        log.warn("Replacing unreadable event notification params: {}", unreadable.getMessage());
      }
    }
    return paramsObjectMapper.createObjectNode();
  }

  /** Removes one deduplicated event; its own transaction, as callers run after a commit. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deleteInNewTransaction(String recipientUserId, String deduplicationKey) {
    eventNotificationRepository.deleteByRecipientUserIdAndDeduplicationKey(
        recipientUserId, deduplicationKey);
  }
}
