package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedInvitations;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.SendInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real public/JPA routing and local invalidation. The cancellation port is the explicit external
 * boundary double; its durable native intent/ACK and native cleanup are separate root-owned gates.
 */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=FIVE_ACTORS")
class ProtectedInvitationCancellationRoutingJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  @Autowired UnitQueue queue;
  @MockitoBean ProtectedInvitationCancellation cancellation;

  @Test
  void unknownRevokeCommitsLocalInvalidationButKeepsTheHeldReservationWithoutAcknowledgement() {
    var draft = service.createInvite(command());
    unknownCancellation(draft.getId(), Reason.REVOKED);

    assertThatThrownBy(() -> service.revokeInvite(draft.getId()))
        .isInstanceOf(KeycloakProtectedInvitations.RemoteFailure.class);

    var invalid = invites.findById(draft.getId()).orElseThrow();
    assertThat(invalid.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(invalid.getActiveRecipientKey()).isNull();
    assertThat(invalid.getTokenHash()).isNull();
    assertThat(invalid.getTenantIdReservationToken())
        .isEqualTo(draft.getTenantIdReservationToken());
    assertThat(releases.count()).isZero();
    verify(tenantIds, never()).release(17L);
    verifyNoInteractions(sender);
  }

  @Test
  void acknowledgedRevokeReleasesOnlyThroughTheOwnedAcknowledgementTransaction() {
    var draft = service.createInvite(command());
    var acknowledged = new AtomicBoolean();
    var withinAcknowledgement = new AtomicBoolean();
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              transaction().executeWithoutResult(tx -> ((Runnable) call.getArgument(2)).run());
              acknowledged.set(true);
              return null;
            })
        .when(cancellation)
        .cancel(eq(draft.getId()), eq(Reason.REVOKED), any());
    when(cancellation.withAcknowledged(eq(draft.getId()), any()))
        .thenAnswer(
            call -> {
              assertThat(acknowledged).isTrue();
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              withinAcknowledgement.set(true);
              try {
                return transaction().execute(tx -> ((Supplier<?>) call.getArgument(1)).get());
              } finally {
                withinAcknowledgement.set(false);
              }
            });
    when(tenantIds.release(17L))
        .thenAnswer(
            call -> {
              assertThat(acknowledged).isTrue();
              assertThat(withinAcknowledgement).isTrue();
              return true;
            });

    var revoked = assertDoesNotThrow(() -> service.revokeInvite(draft.getId()));

    assertThat(revoked.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    verify(cancellation).withAcknowledged(eq(draft.getId()), any());
    verify(tenantIds).release(17L);
    verifyNoInteractions(sender);
  }

  @Test
  void unknownResendCannotTransferTheReservationOrRestoreTheOldUsableLink() {
    var old = service.createInvite(command());
    old.setStatus(AccountInviteStatus.EMAIL_SENT);
    old.setTokenHash("b".repeat(64));
    invites.saveAndFlush(old);
    unknownCancellation(old.getId(), Reason.SUPERSEDED);

    assertThatThrownBy(
            () -> service.resendInvite(new SendInviteCommand(old.getId(), template.getId())))
        .isInstanceOf(KeycloakProtectedInvitations.RemoteFailure.class);

    var invalid = invites.findById(old.getId()).orElseThrow();
    assertThat(invalid.getStatus()).isEqualTo(AccountInviteStatus.SUPERSEDED);
    assertThat(invalid.getTokenHash()).isNull();
    assertThat(invalid.getActiveRecipientKey()).isNull();
    assertThat(invites.count()).isEqualTo(1);
    assertThat(invalid.getTenantIdReservationToken()).isEqualTo(old.getTenantIdReservationToken());
    assertThat(releases.count()).isZero();
    verify(tenantIds, never()).release(17L);
    verifyNoInteractions(sender, approvals);
  }

  @Test
  void localExpiryUsesReducingReasonAndRetriesUnknownAcknowledgementWithoutReleasing() {
    var expired = service.createInvite(command());
    expired.setExpiresAt(LocalDateTime.now().minusDays(1));
    invites.saveAndFlush(expired);
    unknownCancellation(expired.getId(), Reason.ISSUANCE_ABORTED);
    when(cancellation.pendingInviteIds(100)).thenAnswer(call -> invites.findById(expired.getId())
        .filter(current -> current.getStatus() == AccountInviteStatus.EXPIRED)
        .map(current -> List.of(current.getId())).orElse(List.of()));

    assertThatThrownBy(service::expireElapsedInvites)
        .isInstanceOf(KeycloakProtectedInvitations.RemoteFailure.class);
    assertThatThrownBy(service::expireElapsedInvites)
        .isInstanceOf(KeycloakProtectedInvitations.RemoteFailure.class);

    assertThat(invites.findById(expired.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInviteStatus.EXPIRED);
    assertThat(releases.count()).isZero();
    verify(cancellation, times(2)).cancel(eq(expired.getId()), eq(Reason.ISSUANCE_ABORTED), any());
    verify(tenantIds, never()).release(17L);
    verifyNoInteractions(sender, approvals);
  }

  @Test
  void scenarioCStillReleasesAndMailsAnActualOrdinaryAgencyAdminQueue() {
    service.createInvite(command()); // Actual pending tenant admin holds the future tenant17.
    String recipient = "queued-admin@example.test";
    when(identityEmailOwnerLookup.findByEmail(recipient)).thenReturn(Optional.empty());
    when(agencyIds.reserve(null, 17L)).thenReturn(23L);
    when(agencyIds.getAvailability(23L)).thenReturn(IdAllocationStatus.RESERVED);
    when(sender.send(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> new InviteMailSendReceipt(call.getArgument(0), Instant.now()));
    var queued =
        service
            .createAndSendInvite(
                new CreateAccountInviteCommand(
                    AccountInviteTargetRole.AGENCY_ADMIN,
                    17L,
                    recipient,
                    "Ada",
                    "Lovelace",
                    null,
                    null,
                    30L,
                    IdAllocationMode.MANUAL,
                    IdAllocationMode.AUTO),
                template.getId())
            .invite();
    assertThat(queued.getStatus()).isEqualTo(AccountInviteStatus.WAITING_FOR_UNIT);
    assertThat(queued.getTokenHash()).isNull();
    verifyNoInteractions(sender);

    var released = assertDoesNotThrow(() -> queue.release(InviteUnitType.TENANT, 17L, 17L));

    assertThat(released).containsExactly(queued.getId());
    var sent = invites.findById(queued.getId()).orElseThrow();
    assertThat(sent.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(sent.getAgencyId()).isEqualTo(23L);
    assertThat(sent.getTokenHash()).isNotBlank();
    verify(sender).send(any(), any(), any(), any(), any(), any(), any());
    verifyNoInteractions(approvals, cancellation);
  }

  @Test
  void anInconsistentProtectedQueueRowCannotBypassTheCommonApprovalGuard() {
    var inconsistent = service.createInvite(command());
    inconsistent.setStatus(AccountInviteStatus.WAITING_FOR_UNIT);
    inconsistent.setWaitingForUnit(InviteUnitType.TENANT);
    inconsistent.setQueuedExpiryDays(30L);
    inconsistent.setQueuedTemplateId(template.getId());
    inconsistent.setExpiresAt(null);
    invites.saveAndFlush(inconsistent);

    assertThat(queue.release(InviteUnitType.TENANT, 17L, 17L)).isEmpty();

    assertThat(invites.findById(inconsistent.getId()).orElseThrow().getTokenHash()).isNull();
    assertThat(deliveries.count()).isZero();
    verifyNoInteractions(sender, approvals, cancellation);
  }

  private void unknownCancellation(long inviteId, Reason reason) {
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              transaction().executeWithoutResult(tx -> ((Runnable) call.getArgument(2)).run());
              throw new KeycloakProtectedInvitations.RemoteFailure(0);
            })
        .when(cancellation)
        .cancel(eq(inviteId), eq(reason), any());
  }

  private TransactionTemplate transaction() {
    var transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction;
  }
}
