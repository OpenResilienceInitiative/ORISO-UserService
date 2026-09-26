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
    var now = LocalDateTime.now(ZoneOffset.UTC);
    for (var mail :
        outbox
            .findTop100ByStatusAndDueAtUtcLessThanEqualAndNextAttemptAtUtcLessThanEqualOrderByDueAtUtcAsc(
                Status.PENDING, now, now)) {
      try {
        dispatch(mail);
      } catch (RuntimeException exception) {
        // Configuration errors remain visible to an operator, never replaced with a
        // platform SMTP or public-URL fallback. A failed terminal update may retain SENDING.
        try {
          if (claims.deferConfigurationFailure(
              mail.getId(), now.plusSeconds(backoffSeconds(mail.getFailureCount())))) {
            log.error(
                "Self-help appointment mail {} could not be processed: {}",
                mail.getId(),
                exception.getClass().getSimpleName());
          } else {
            log.error(
                "Self-help appointment mail {} could not finish its committed claim: {}",
                mail.getId(),
                exception.getClass().getSimpleName());
          }
        } catch (RuntimeException deferException) {
          log.error(
              "Self-help appointment mail {} could not defer a failed row: {}",
              mail.getId(),
              deferException.getClass().getSimpleName());
        }
      }
    }
  }

  private static long backoffSeconds(int failures) {
    return Math.min(3600L, 300L << Math.min(Math.max(failures, 0), 4));
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

    // Last eligibility read before handoff. A concurrent change after this read can still race
    // with SMTP; no sender can revoke a message that is already in flight.
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
