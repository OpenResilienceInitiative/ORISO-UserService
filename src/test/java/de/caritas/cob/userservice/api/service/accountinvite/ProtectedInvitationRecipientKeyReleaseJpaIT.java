package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedInvitations;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/**
 * Real JPA public release/email reuse; independent final native proof is the explicit port double.
 */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=FIVE_ACTORS")
class ProtectedInvitationRecipientKeyReleaseJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  @MockitoBean ProtectedInvitationCancellation cancellation;

  @Test
  void pendingAcceptedEmailKeyStaysHeldUntilVerifiedReleaseThenActualEmailCanBeInvitedAgain() {
    when(tenantIds.reserve(null)).thenReturn(
        new TenantIdReservation(17L, "9eb4c868-16d4-4be5-8c0b-5fb9d2b866b3"),
        new TenantIdReservation(18L, "59ba921a-849d-4f32-a492-98d03ff582b0"),
        new TenantIdReservation(17L, "99d529dd-ef5c-46c2-92ed-56c005c8c026"));
    when(tenantIds.getAvailability(18L)).thenReturn(IdAllocationStatus.RESERVED);
    var original = service.createInvite(command());
    String held = original.getActiveRecipientKey();
    original.setStatus(AccountInviteStatus.EXPIRED);
    original.setExpiresAt(LocalDateTime.now().minusDays(1).withNano(0));
    original.setProvisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS);
    original.setAcceptedByUserId("bf75ecf1-e612-414a-9e20-fd0858957f68");
    original.setTokenHash(null);
    invites.saveAndFlush(original);
    assertThat(held).isNotBlank();
    var finalProof = new AtomicBoolean();
    var insideAck = new AtomicBoolean();
    when(cancellation.pendingInviteIds(100)).thenReturn(List.of(original.getId()));
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              transaction().executeWithoutResult(tx -> ((Runnable) call.getArgument(2)).run());
              return null;
            })
        .when(cancellation)
        .cancel(
            eq(original.getId()),
            eq(ProtectedInvitationCancellation.Reason.ISSUANCE_ABORTED),
            any());
    when(cancellation.withAcknowledged(eq(original.getId()), any()))
        .thenAnswer(
            call -> {
              if (!finalProof.get()) throw new KeycloakProtectedInvitations.RemoteFailure(0);
              insideAck.set(true);
              try {
                return transaction().execute(tx -> ((Supplier<?>) call.getArgument(1)).get());
              } finally {
                insideAck.set(false);
              }
            });
    when(tenantIds.release(17L))
        .thenAnswer(
            call -> {
              assertTrue(insideAck.get());
              return true;
            });
    assertThrows(KeycloakProtectedInvitations.RemoteFailure.class, service::expireElapsedInvites);
    assertThat(invites.findById(original.getId()).orElseThrow().getActiveRecipientKey())
        .isEqualTo(held);
    assertThatThrownBy(() -> service.createInvite(command())).isInstanceOf(RuntimeException.class);
    verify(tenantIds, never()).release(17L);
    assertThat(invites.count()).isEqualTo(1);

    finalProof.set(true);
    assertDoesNotThrow(service::expireElapsedInvites);
    var released = invites.findById(original.getId()).orElseThrow();
    assertThat(released.getTenantIdReservationToken()).isNull();
    assertThat(released.getActiveRecipientKey()).isNull();
    assertThat(released.getTokenHash()).isNull();
    var next = assertDoesNotThrow(() -> service.createInvite(command()));
    assertThat(next.getId()).isNotEqualTo(original.getId());
    assertThat(next.getActiveRecipientKey()).isEqualTo(held);
    assertThat(invites.count()).isEqualTo(2);
    verify(tenantIds).release(17L);
    verifyNoInteractions(sender, approvals);
  }

  @Test
  void stalePendingDiscoveryNeverReleasesTheEmailKeyOfAnUnexpiredProtectedInvite() {
    var live = service.createInvite(command());
    when(cancellation.pendingInviteIds(100)).thenReturn(List.of(live.getId()));
    assertDoesNotThrow(service::expireElapsedInvites);
    assertThat(invites.findById(live.getId()).orElseThrow().getActiveRecipientKey())
        .isEqualTo(live.getActiveRecipientKey());
    assertThatThrownBy(() -> service.createInvite(command())).isInstanceOf(RuntimeException.class);
    verify(cancellation, never()).withAcknowledged(anyLong(), any());
    verify(tenantIds, never()).release(17L);
  }

  private TransactionTemplate transaction() {
    var tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx;
  }
}
