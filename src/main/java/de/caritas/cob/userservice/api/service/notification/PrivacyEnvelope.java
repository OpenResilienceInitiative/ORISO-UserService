package de.caritas.cob.userservice.api.service.notification;

import lombok.Builder;
import lombok.Value;

/**
 * Metadata-only envelope for notifications. Intentionally excludes plaintext message body.
 */
@Value
@Builder
public class PrivacyEnvelope {
  String messageId;
  String roomId;
  String senderId;
  Long timestamp;
  boolean hasAttachment;
  String contentClass;
}
