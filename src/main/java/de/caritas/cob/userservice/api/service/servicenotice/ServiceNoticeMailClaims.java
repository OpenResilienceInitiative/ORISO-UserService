package de.caritas.cob.userservice.api.service.servicenotice;

import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient;
import de.caritas.cob.userservice.api.model.ServiceNoticeRecipient.MailStatus;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeRecipientRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Each state change commits on its own, so a claim is durable before any mail handoff. */
@Service
@RequiredArgsConstructor
public class ServiceNoticeMailClaims {

  private static final EnumSet<MailStatus> TERMINAL =
      EnumSet.of(MailStatus.SENT, MailStatus.SUPPRESSED, MailStatus.UNCERTAIN);

  private final ServiceNoticeRecipientRepository recipients;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(long rowId) {
    return recipients.claim(rowId, MailStatus.PENDING, MailStatus.SENDING, nowUtc()) == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(long rowId, MailStatus terminal) {
    if (!TERMINAL.contains(terminal)) {
      throw new IllegalArgumentException("Only a terminal service-notice mail state is allowed");
    }
    var row = held(rowId);
    row.setMailStatus(terminal);
    if (terminal == MailStatus.SENT) {
      row.setSentAt(nowUtc());
    }
    recipients.save(row);
  }

  /** Only for a failure known to have happened before any transport handoff. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void releaseBeforeHandoff(long rowId) {
    var row = held(rowId);
    row.setMailStatus(MailStatus.PENDING);
    row.setClaimedAt(null);
    recipients.save(row);
  }

  /**
   * Counts one failed attempt that never reached a transport. Returns true when the row reached the
   * retry limit and is given up as {@code FAILED}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean recordFailedAttempt(long rowId, LocalDateTime nextAttemptUtc, int maxAttempts) {
    var row = recipients.findById(rowId).orElse(null);
    if (row == null || row.getMailStatus() != MailStatus.PENDING) {
      return false;
    }
    row.setFailureCount(row.getFailureCount() + 1);
    boolean givenUp = row.getFailureCount() >= maxAttempts;
    if (givenUp) {
      row.setMailStatus(MailStatus.FAILED);
    } else {
      row.setNextAttemptAtUtc(nextAttemptUtc);
    }
    recipients.save(row);
    return givenUp;
  }

  private ServiceNoticeRecipient held(long rowId) {
    var row =
        recipients
            .findById(rowId)
            .orElseThrow(() -> new IllegalStateException("Service-notice mail claim is missing"));
    if (row.getMailStatus() != MailStatus.SENDING) {
      throw new IllegalStateException("Service-notice mail claim is no longer held");
    }
    return row;
  }

  private static LocalDateTime nowUtc() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
