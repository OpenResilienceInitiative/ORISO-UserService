package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists FAILED delivery audit rows in an independent transaction so they survive the rollback
 * that a failed SMTP handover triggers in the surrounding send transaction (TEN-INV-U6, #890).
 * Callers treat this as best-effort — auditing must never mask the original send failure.
 */
@Service
@RequiredArgsConstructor
public class InviteEmailDeliveryFailureRecorder {

  private static final int MAX_FAILURE_REASON_LENGTH = 1024;

  private final @NonNull InviteEmailDeliveryRepository deliveryRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(
      Long accountInviteId,
      InviteEmailTemplate template,
      String recipient,
      String subject,
      String body,
      String failureReason) {
    LocalDateTime now = LocalDateTime.now();
    deliveryRepository.save(
        InviteEmailDelivery.builder()
            .accountInviteId(accountInviteId)
            .templateId(template.getId())
            .templateKind(template.getKind())
            .recipientSnapshot(recipient)
            .subjectSnapshot(subject == null ? "" : subject)
            .bodySnapshot(body == null ? "" : body)
            .status(InviteEmailDeliveryStatus.FAILED)
            .failureReason(truncate(failureReason))
            .createDate(now)
            .build());
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= MAX_FAILURE_REASON_LENGTH
        ? value
        : value.substring(0, MAX_FAILURE_REASON_LENGTH);
  }
}
