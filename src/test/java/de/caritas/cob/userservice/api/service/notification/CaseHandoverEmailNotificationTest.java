package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.adapters.web.dto.ReassignmentNotificationDTO;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class CaseHandoverEmailNotificationTest {
  private final EmailNotificationFacade facade = mock(EmailNotificationFacade.class);
  private final CaseHandoverEmailNotification notification =
      new CaseHandoverEmailNotification(facade);
  private final TransactionTemplate transaction =
      new TransactionTemplate(new TestTransactionManager());
  private final TenantData tenant = new TenantData(40L, "springfield");
  private static final String ROOM = "!owned-room:example.test";

  @Test
  void dispatchesConsentOnlyAfterCommitWithAnIndependentTenantSnapshot() {
    transaction.executeWithoutResult(
        status -> {
          notification.takeoverConsentRequested(12L, ROOM, tenant);
          tenant.setTenantId(99L);
          tenant.setSubdomain("changed-after-scheduling");
          verifyNoInteractions(facade);
        });
    verify(facade).sendReassignRequestNotification(ROOM, new TenantData(40L, "springfield"));
  }

  @Test
  void rollbackDispatchesNeitherOutcome() {
    transaction.executeWithoutResult(
        status -> {
          notification.takeoverConsentRequested(12L, ROOM, tenant);
          notification.ownershipGranted(
              12L, ROOM, UUID.randomUUID(), "Previous consultant", tenant);
          status.setRollbackOnly();
        });
    verifyNoInteractions(facade);
  }

  @Test
  void confirmationUsesOnlyTheCommittedNewConsultantAndSafeExistingContract() {
    UUID recipient = UUID.randomUUID();
    transaction.executeWithoutResult(
        status -> {
          notification.ownershipGranted(12L, ROOM, recipient, "Previous consultant", tenant);
          verifyNoInteractions(facade);
        });
    var dto = ArgumentCaptor.forClass(ReassignmentNotificationDTO.class);
    var capturedTenant = ArgumentCaptor.forClass(TenantData.class);
    verify(facade).sendReassignConfirmationNotification(dto.capture(), capturedTenant.capture());
    assertThat(dto.getValue().getToConsultantId()).isEqualTo(recipient);
    assertThat(dto.getValue().getFromConsultantName()).isEqualTo("Previous consultant");
    assertThat(dto.getValue().getMatrixRoomId()).isEqualTo(ROOM);
    assertThat(capturedTenant.getValue()).isEqualTo(new TenantData(40L, "springfield"));
  }

  @Test
  void repeatedSchedulingWithinOneTransitionDoesNotDuplicateMail() {
    transaction.executeWithoutResult(
        status -> {
          notification.takeoverConsentRequested(12L, ROOM, tenant);
          notification.takeoverConsentRequested(12L, ROOM, tenant);
        });
    verify(facade, times(1)).sendReassignRequestNotification(ROOM, tenant);
  }

  @Test
  void aDifferentPersistedRequestIsNotSilentlyDeduplicated() {
    transaction.executeWithoutResult(
        status -> {
          notification.takeoverConsentRequested(12L, ROOM, tenant);
          notification.takeoverConsentRequested(13L, ROOM, tenant);
        });
    verify(facade, times(2)).sendReassignRequestNotification(ROOM, tenant);
  }

  @Test
  void missingTransactionCannotSendUncommittedMail() {
    assertThatThrownBy(() -> notification.takeoverConsentRequested(12L, ROOM, tenant))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(facade);
  }

  @Test
  void deliveryFailureDoesNotTurnCommittedOwnershipIntoAnErrorResponse() {
    doThrow(new IllegalStateException("provider detail must not be logged"))
        .when(facade)
        .sendReassignConfirmationNotification(any(), any());
    assertThatCode(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        notification.ownershipGranted(
                            12L, ROOM, UUID.randomUUID(), "Previous consultant", tenant)))
        .doesNotThrowAnyException();
    verify(facade).sendReassignConfirmationNotification(any(), any());
  }

  /**
   * Uses Spring's actual synchronization lifecycle without persistence or external side effects.
   */
  private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
