package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import de.caritas.cob.userservice.api.model.AccountInvite;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Public sweep/JPA boundary; native restriction acknowledgement is the explicit external double. */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=FIVE_ACTORS")
class ProtectedInvitationSupersededExpiryJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  @MockitoBean ProtectedInvitationCancellation cancellation;

  @Test
  void anExpiredAcknowledgedSupersessionWithoutReplacementReleasesOnceInsideTheOwnedAck() {
    var retired = superseded(LocalDateTime.now().minusDays(1));
    var held = retired.getTenantIdReservationToken();
    assertThat(held).isNotBlank();
    assertThat(retired.getSupersededByInviteId()).isNull();
    var inAcknowledgement = acknowledge(retired.getId(), () -> {});
    when(tenantIds.release(17L))
        .thenAnswer(
            call -> {
              assertThat(inAcknowledgement).isTrue();
              return true;
            });
    // Root's durable pending selector supplies this exact expired ACK/no-replacement holder.
    // A repeated stale page additionally proves that public retry cannot release twice.
    when(cancellation.pendingInviteIds(100)).thenReturn(List.of(retired.getId()));

    assertDoesNotThrow(service::expireElapsedInvites);
    assertDoesNotThrow(service::expireElapsedInvites);

    var saved = invites.findById(retired.getId()).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(AccountInviteStatus.SUPERSEDED);
    assertThat(saved.getExpiresAt()).isEqualTo(retired.getExpiresAt());
    assertThat(saved.getSupersededByInviteId()).isNull();
    assertThat(saved.getTenantIdReservationToken()).isNull();
    assertThat(saved.getTokenHash()).isNull();
    assertThat(saved.getActiveRecipientKey()).isNull();
    assertThat(invites.count()).isEqualTo(1);
    verify(cancellation, times(2)).cancel(eq(retired.getId()), eq(Reason.SUPERSEDED), any());
    verify(cancellation, times(2)).withAcknowledged(eq(retired.getId()), any());
    verify(tenantIds).release(17L);
    verifyNoInteractions(sender, approvals);
  }

  @Test
  void staleDiscoveryCannotReleaseASupersededCapabilityBeforeItsOriginalDeadline() {
    var retired = superseded(LocalDateTime.now().plusDays(1));
    var held = retired.getTenantIdReservationToken();
    acknowledge(retired.getId(), () -> {});
    when(cancellation.pendingInviteIds(100)).thenReturn(List.of(retired.getId()));

    assertDoesNotThrow(service::expireElapsedInvites);

    var saved = invites.findById(retired.getId()).orElseThrow();
    assertThat(saved.getTenantIdReservationToken()).isEqualTo(held);
    assertThat(saved.getStatus()).isEqualTo(AccountInviteStatus.SUPERSEDED);
    assertThat(saved.getSupersededByInviteId()).isNull();
    assertThat(releases.count()).isZero();
    verify(tenantIds, never()).release(17L);
    verifyNoInteractions(sender, approvals);
  }

  @Test
  void aReplacementRecordedBeforeTheOwnedAckPreventsReleasingTheRetiredCapability() {
    var retired = superseded(LocalDateTime.now().minusDays(1));
    var held = retired.getTenantIdReservationToken();
    // A real current replacement row, not merely a guessed foreign numeric pointer.
    var replacement = service.createInvite(command());
    acknowledge(
        retired.getId(),
        () -> {
          var current = invites.findById(retired.getId()).orElseThrow();
          current.setSupersededByInviteId(replacement.getId());
          invites.saveAndFlush(current);
        });
    when(cancellation.pendingInviteIds(100)).thenReturn(List.of(retired.getId()));

    assertDoesNotThrow(service::expireElapsedInvites);

    var saved = invites.findById(retired.getId()).orElseThrow();
    assertThat(saved.getSupersededByInviteId()).isEqualTo(replacement.getId());
    assertThat(saved.getTenantIdReservationToken()).isEqualTo(held);
    assertThat(invites.findById(replacement.getId()).orElseThrow().getTenantIdReservationToken())
        .isEqualTo(replacement.getTenantIdReservationToken());
    assertThat(releases.count()).isZero();
    verify(tenantIds, never()).release(17L);
    verifyNoInteractions(sender, approvals);
  }

  private AccountInvite superseded(LocalDateTime deadline) {
    var retired = service.createInvite(command());
    retired.setStatus(AccountInviteStatus.SUPERSEDED);
    retired.setSupersededAt(LocalDateTime.now().minusDays(2));
    retired.setExpiresAt(deadline.withNano(0));
    retired.setTokenHash(null);
    retired.setActiveRecipientKey(null);
    return invites.saveAndFlush(retired);
  }

  private AtomicBoolean acknowledge(long inviteId, Runnable beforeAcknowledgement) {
    var acknowledged = new AtomicBoolean();
    var inAcknowledgement = new AtomicBoolean();
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              transaction().executeWithoutResult(tx -> ((Runnable) call.getArgument(2)).run());
              transaction().executeWithoutResult(tx -> beforeAcknowledgement.run());
              acknowledged.set(true);
              return null;
            })
        .when(cancellation)
        .cancel(eq(inviteId), eq(Reason.SUPERSEDED), any());
    when(cancellation.withAcknowledged(eq(inviteId), any()))
        .thenAnswer(
            call -> {
              assertThat(acknowledged).isTrue();
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              inAcknowledgement.set(true);
              try {
                return transaction().execute(
                    tx -> {
                      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                      assertThat(TransactionSynchronizationManager.hasResource(source)).isTrue();
                      return ((Supplier<?>) call.getArgument(1)).get();
                    });
              } finally {
                inAcknowledgement.set(false);
              }
            });
    return inAcknowledgement;
  }

  private TransactionTemplate transaction() {
    var transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction;
  }
}
