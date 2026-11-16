package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CalendarMoveProposalEmailNotificationTest {

  private final CalendarMoveProposalMailSender sender = mock(CalendarMoveProposalMailSender.class);
  private final CalendarMoveProposalEmailNotification notification =
      new CalendarMoveProposalEmailNotification(sender);
  private final CalendarMoveProposalEmailNotification.Mail mail =
      new CalendarMoveProposalEmailNotification.Mail(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          "owner",
          "Proposer Name",
          Instant.parse("2026-11-18T09:00:00Z"),
          Instant.parse("2026-11-19T13:00:00Z"));

  @BeforeEach
  void openTransaction() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void closeTransaction() {
    TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void proposed_Should_SendNothingBeforeTheCommit_And_TheMailAfterIt() {
    notification.proposed(mail);

    verifyNoInteractions(sender);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(sender).send(mail);
  }

  @Test
  void proposed_Should_SendNothing_When_TheTransactionRollsBack() {
    notification.proposed(mail);

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    verifyNoInteractions(sender);
  }

  @Test
  void afterCommit_Should_SwallowAFailingSender_SoTheProposalStands() {
    doThrow(new IllegalStateException("smtp down")).when(sender).send(any());
    notification.proposed(mail);

    assertThatCode(
            () ->
                TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit))
        .doesNotThrowAnyException();
    verify(sender).send(mail);
  }

  @Test
  void proposed_Should_RequireATransaction() {
    closeTransaction();

    assertThatThrownBy(() -> notification.proposed(mail)).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(sender);
    openTransaction();
  }

  @Test
  void mail_Should_KeepNamesAndTimesOutOfItsTextForm() {
    assertThat(mail.toString()).doesNotContain("Proposer Name", "owner", "2026");
  }
}
