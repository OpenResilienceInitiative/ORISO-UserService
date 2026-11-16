package de.caritas.cob.userservice.api.service.notification;

import java.time.Instant;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells a counsellor by e-mail that a colleague proposes to move one of their appointments
 * (ADR-032, move proposals v1), once the proposal is committed. A proposal that is rolled back
 * sends nothing, and a mail that cannot be sent leaves the stored proposal alone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalendarMoveProposalEmailNotification {

  /**
   * What the mail says, taken when the proposal is made.
   *
   * @param approverId the counsellor asked, who receives the mail
   * @param proposerName the name colleagues see of the counsellor who proposes
   * @param currentStart when the appointment takes place now
   * @param proposedStart when it would take place
   */
  public record Mail(
      UUID proposalId,
      String approverId,
      String proposerName,
      Instant currentStart,
      Instant proposedStart) {

    @Override
    public String toString() {
      return "CalendarMoveProposalEmailNotification.Mail[" + proposalId + "]";
    }
  }

  private final @NonNull CalendarMoveProposalMailSender sender;

  /** Sends {@code mail} after the surrounding transaction commits. */
  public void proposed(Mail mail) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("A move proposal mail requires an active transaction");
    }
    TransactionSynchronizationManager.registerSynchronization(new Delivery(mail));
  }

  private final class Delivery implements TransactionSynchronization {
    private final Mail mail;

    private Delivery(Mail mail) {
      this.mail = mail;
    }

    @Override
    public void afterCommit() {
      // The sender reports its own failures: a stored proposal is not undone, and the request
      // does not fail, because a mail could not be sent.
      try {
        sender.send(mail);
      } catch (RuntimeException failure) {
        log.error(
            "Calendar move proposal mail could not be dispatched after commit: {}",
            failure.getClass().getSimpleName());
      }
    }
  }
}
