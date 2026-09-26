package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.Status;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentMailOutboxRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Delivers due rows only after checking their current revision, membership and preference. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupAppointmentMailWorker {
  private final GroupAppointmentMailOutboxRepository outbox;
  private final GroupAppointmentMailEligibilityService eligibility;
  private final GroupAppointmentMailComposer composer;
  private final GroupAppointmentMailClaimService claims;
  private final TenantSystemEmailDelivery delivery;

  public void dispatchDue() {
    for (var mail :
        outbox.findTop100ByStatusAndDueAtUtcLessThanEqualOrderByDueAtUtcAsc(
            Status.PENDING, LocalDateTime.now(ZoneOffset.UTC))) {
      try {
        dispatch(mail);
      } catch (RuntimeException exception) {
        // Configuration errors remain visible to an operator, never replaced with a
        // platform SMTP or public-URL fallback. A failed terminal update may retain SENDING.
        log.error(
            "Self-help appointment mail {} could not be processed: {}",
            mail.getId(),
            exception.getClass().getSimpleName());
      }
    }
  }

  private void dispatch(GroupAppointmentMailOutbox mail) {
    var firstCheck = eligibility.resolve(mail);
    if (firstCheck.isEmpty()) {
      suppress(mail);
      return;
    }
    var composed = composer.compose(mail, firstCheck.get());
    if (composed.isEmpty()) {
      suppress(mail);
      return;
    }
    if (!claims.claim(mail.getId())) {
      return;
    }

    // A group may have changed between the first read and the committed claim. No stale
    // reminder, removed member, switched-off preference or old address can proceed to SMTP.
    java.util.Optional<GroupAppointmentMailEligibilityService.Eligible> current;
    try {
      current = eligibility.resolve(mail);
    } catch (RuntimeException exception) {
      claims.releaseBeforeHandoff(mail.getId());
      throw exception;
    }
    if (current.isEmpty()) {
      claims.finish(mail.getId(), Status.SUPPRESSED);
      return;
    }
    if (!firstCheck.get().recipient().equals(current.get().recipient())
        || current.get().series().getChatOwner() == null
        || !Long.valueOf(composed.get().tenantId())
            .equals(current.get().series().getChatOwner().getTenantId())) {
      claims.releaseBeforeHandoff(mail.getId());
      return;
    }
    boolean sent;
    try {
      var content = composed.get();
      sent =
          delivery.sendConfirmed(
              content.tenantId(),
              content.route(),
              content.purpose(),
              content.recipient(),
              content.email(),
              mail.getCorrelationId());
    } catch (RuntimeException exception) {
      // The handoff outcome may be unknown. Replaying automatically would risk two mails.
      claims.finish(mail.getId(), Status.UNCERTAIN);
      log.error(
          "Self-help appointment mail {} needs delivery reconciliation: {}",
          mail.getId(),
          exception.getClass().getSimpleName());
      return;
    }
    claims.finish(mail.getId(), sent ? Status.SENT : Status.UNCERTAIN);
  }

  private void suppress(GroupAppointmentMailOutbox mail) {
    if (claims.claim(mail.getId())) {
      claims.finish(mail.getId(), Status.SUPPRESSED);
    }
  }
}
