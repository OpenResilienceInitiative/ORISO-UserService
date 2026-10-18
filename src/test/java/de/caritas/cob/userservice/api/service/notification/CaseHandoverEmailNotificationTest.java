package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.mailservice.generated.web.model.Dialect;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class CaseHandoverEmailNotificationTest {
  private final CaseHandoverMailSender sender = mock(CaseHandoverMailSender.class);
  private final ReleaseToggleService toggles = mock(ReleaseToggleService.class);
  private final IdentityClientConfig identity = mock(IdentityClientConfig.class);
  private final CaseHandoverEmailNotification notification =
      new CaseHandoverEmailNotification(sender, toggles, identity);
  private final TransactionTemplate transaction =
      new TransactionTemplate(new TestTransactionManager());

  @Test
  void consentUsesTheCommittedRequestAndImmutableRecipientTenantSnapshot() {
    CaseHandoverRequest request = request(Status.PENDING_CLIENT_CONSENT, AccessType.TAKEOVER);
    transaction.executeWithoutResult(
        status -> {
          notification.consentRequested(request);
          request.getSession().getUser().setEmail("later@example.test");
          request.setTenantId(99L);
          verifyNoInteractions(sender);
        });
    var sent = ArgumentCaptor.forClass(CaseHandoverEmailNotification.Mail.class);
    verify(sender).send(sent.capture());
    assertThat(sent.getValue().outcome())
        .isEqualTo(CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED);
    assertThat(sent.getValue().tenantId()).isEqualTo(40L);
    assertThat(sent.getValue().recipient()).isEqualTo("asker@example.test");
    assertThat(sent.getValue().language()).isEqualTo(LanguageCode.en);
  }

  @Test
  void rolledBackTakeoverSendsNothing() {
    CaseHandoverRequest request = request(Status.PENDING_CLIENT_CONSENT, AccessType.TAKEOVER);
    transaction.executeWithoutResult(
        status -> {
          notification.consentRequested(request);
          status.setRollbackOnly();
        });
    verifyNoInteractions(sender);
  }

  @Test
  void repeatedSchedulingOfOneOutcomeSendsOnceButOtherRequestsAreIndependent() {
    CaseHandoverRequest first = request(Status.GRANTED, AccessType.TAKEOVER);
    CaseHandoverRequest second = request(Status.GRANTED, AccessType.TAKEOVER);
    second.setId(13L);
    transaction.executeWithoutResult(
        status -> {
          notification.ownershipGranted(first);
          notification.ownershipGranted(first);
          notification.ownershipGranted(second);
        });
    verify(sender, times(2)).send(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void coAccessConsentSendsButOnlyTakeoverOwnershipSends() {
    CaseHandoverRequest pendingCoAccess =
        request(Status.PENDING_CLIENT_CONSENT, AccessType.CO_ACCESS);
    CaseHandoverRequest grantedCoAccess = request(Status.GRANTED, AccessType.CO_ACCESS);
    grantedCoAccess.setId(13L);
    CaseHandoverRequest nonPendingTakeover = request(Status.GRANTED, AccessType.TAKEOVER);
    nonPendingTakeover.setId(14L);
    transaction.executeWithoutResult(
        status -> {
          notification.consentRequested(pendingCoAccess);
          notification.ownershipGranted(grantedCoAccess);
          notification.consentRequested(nonPendingTakeover);
        });
    var sent = ArgumentCaptor.forClass(CaseHandoverEmailNotification.Mail.class);
    verify(sender).send(sent.capture());
    assertThat(sent.getValue().requestId()).isEqualTo(pendingCoAccess.getId());
    assertThat(sent.getValue().outcome())
        .isEqualTo(CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED);
    assertThat(sent.getValue().accessType()).isEqualTo(AccessType.CO_ACCESS);
    verifyNoMoreInteractions(sender);
  }

  @Test
  void grantedMailTargetsTheIncomingConsultantOnly() {
    CaseHandoverRequest request = request(Status.GRANTED, AccessType.TAKEOVER);
    transaction.executeWithoutResult(status -> notification.ownershipGranted(request));
    var sent = ArgumentCaptor.forClass(CaseHandoverEmailNotification.Mail.class);
    verify(sender).send(sent.capture());
    assertThat(sent.getValue().outcome()).isEqualTo(CaseHandoverEmailNotification.Outcome.GRANTED);
    assertThat(sent.getValue().recipient()).isEqualTo("incoming@example.test");
    assertThat(sent.getValue().dialect()).isEqualTo(Dialect.INFORMAL);
  }

  @Test
  void mismatchedTenantCannotQueueMail() {
    CaseHandoverRequest request = request(Status.GRANTED, AccessType.TAKEOVER);
    request.setTenantId(41L);
    transaction.executeWithoutResult(
        status ->
            assertThatThrownBy(() -> notification.ownershipGranted(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching tenant"));
    verifyNoInteractions(sender);
  }

  @Test
  void noTransactionCannotSendMailForAnUncommittedRequest() {
    assertThatThrownBy(
            () ->
                notification.consentRequested(
                    request(Status.PENDING_CLIENT_CONSENT, AccessType.TAKEOVER)))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(sender);
  }

  @Test
  void dispatchFailureCannotTurnACommittedTakeoverIntoAnErrorResponse() {
    doThrow(new IllegalStateException("provider response contains case details"))
        .when(sender)
        .send(org.mockito.ArgumentMatchers.any());
    transaction.executeWithoutResult(
        status -> notification.ownershipGranted(request(Status.GRANTED, AccessType.TAKEOVER)));
    verify(sender).send(org.mockito.ArgumentMatchers.any());
  }

  private CaseHandoverRequest request(Status status, AccessType type) {
    User asker =
        User.builder()
            .userId("asker-id")
            .username("asker")
            .email("asker@example.test")
            .languageCode(LanguageCode.en)
            .build();
    Consultant incoming =
        Consultant.builder()
            .id("incoming-id")
            .username("incoming")
            .firstName("Incoming")
            .lastName("Counsellor")
            .email("incoming@example.test")
            .languageCode(LanguageCode.de)
            .build();
    Session session =
        Session.builder()
            .id(77L)
            .tenantId(40L)
            .matrixRoomId("!room:example.test")
            .user(asker)
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("12345")
            .status(Session.SessionStatus.IN_PROGRESS)
            .build();
    return CaseHandoverRequest.builder()
        .id(12L)
        .tenantId(40L)
        .session(session)
        .requesterConsultant(incoming)
        .status(status)
        .accessType(type)
        .build();
  }

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
