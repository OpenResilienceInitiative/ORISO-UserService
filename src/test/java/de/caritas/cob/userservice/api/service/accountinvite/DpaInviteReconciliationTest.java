package de.caritas.cob.userservice.api.service.accountinvite;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository.DpaReconciliationCandidate;
import de.caritas.cob.userservice.api.service.dpa.TenantDpaGateReadClient;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.ClaimLease;
import de.caritas.cob.userservice.tenantservice.generated.web.model.DpaGateStatusDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;

class DpaInviteReconciliationTest {

  private final AccountInviteRepository invites = mock(AccountInviteRepository.class);
  private final TenantDpaGateReadClient dpaOwner = mock(TenantDpaGateReadClient.class);
  private final ScheduledTaskClaimService claims = mock(ScheduledTaskClaimService.class);
  private final DpaInviteReconciliation reconciliation =
      new DpaInviteReconciliation(invites, dpaOwner, claims);

  private static final LocalDateTime SIGNED_AT = LocalDateTime.parse("2026-08-03T10:11:12");

  @Test
  void reconcilesHistoricalInviteFromTheOwnersActualSignatureWithoutANewHint() {
    var lease = claim();
    doReturn(List.of(candidate(18L, 2L), candidate(19L, 2L)))
        .when(invites)
        .findDpaReconciliationCandidates(eq(0L), eq(100L), any());
    // A local version key can have a wall time later than its signature's UTC clock.
    when(dpaOwner.read(2L)).thenReturn(validGate().currentDpaVersion("2026-08-03T11:00:00"));

    reconciliation.reconcile();

    verify(dpaOwner, times(1)).read(2L);
    verify(invites).reconcileDpaSigned(18L, 2L, "existing-tenant-admin", SIGNED_AT);
    verify(invites).reconcileDpaSigned(19L, 2L, "existing-tenant-admin", SIGNED_AT);
    verify(claims).release(lease);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"not-a-date", "2026-08-03T10:11:12", "2099-01-01T00:00:00Z"})
  void leavesTheInvitePendingWithoutACoherentOwnerTimestamp(String signedAt) {
    claim();
    doReturn(List.of(candidate(18L, 2L)))
        .when(invites)
        .findDpaReconciliationCandidates(eq(0L), eq(100L), any());
    when(dpaOwner.read(2L)).thenReturn(validGate().dpaSignedAt(signedAt));

    reconciliation.reconcile();

    verify(invites, never()).reconcileDpaSigned(any(), any(), any(), any());
  }

  @Test
  void ignoresASignatureForAnOutdatedAgreement() {
    claim();
    doReturn(List.of(candidate(18L, 2L)))
        .when(invites)
        .findDpaReconciliationCandidates(eq(0L), eq(100L), any());
    when(dpaOwner.read(2L)).thenReturn(validGate().dpaStatus("OUTDATED").dpaSigned(false));

    reconciliation.reconcile();

    verify(invites, never()).reconcileDpaSigned(any(), any(), any(), any());
  }

  @Test
  void advancesPastFailingRowsAndRetriesThemOnTheNextPass() {
    claim();
    var batch = PageRequest.of(0, DpaInviteReconciliation.BATCH_SIZE);
    long lastId = DpaInviteReconciliation.BATCH_SIZE;
    when(invites.findDpaReconciliationUpperBound())
        .thenReturn(Optional.of(lastId + 1))
        .thenReturn(Optional.of(100L));
    var pendingRows =
        IntStream.rangeClosed(1, (int) lastId).mapToObj(id -> candidate(id, 2L)).toList();
    doReturn(pendingRows).when(invites).findDpaReconciliationCandidates(0L, lastId + 1, batch);
    doReturn(pendingRows).when(invites).findDpaReconciliationCandidates(0L, 100L, batch);
    doReturn(List.of(candidate(lastId + 1, 3L)))
        .when(invites)
        .findDpaReconciliationCandidates(lastId, lastId + 1, batch);
    when(dpaOwner.read(2L)).thenThrow(new IllegalStateException()).thenReturn(validGate());
    when(dpaOwner.read(3L)).thenReturn(validGate());

    reconciliation.reconcile();
    reconciliation.reconcile();
    reconciliation.reconcile();

    var passes = inOrder(invites, dpaOwner);
    passes.verify(invites).findDpaReconciliationCandidates(0L, lastId + 1, batch);
    passes.verify(dpaOwner).read(2L);
    passes.verify(invites).findDpaReconciliationCandidates(lastId, lastId + 1, batch);
    passes.verify(invites).reconcileDpaSigned(lastId + 1, 3L, "existing-tenant-admin", SIGNED_AT);
    passes.verify(invites).findDpaReconciliationCandidates(0L, 100L, batch);
    passes.verify(dpaOwner).read(2L);
    passes.verify(invites).reconcileDpaSigned(1L, 2L, "existing-tenant-admin", SIGNED_AT);
  }

  private ClaimLease claim() {
    when(invites.findDpaReconciliationUpperBound()).thenReturn(Optional.of(100L));
    var lease = new ClaimLease("account-invite-dpa-reconciliation", LocalDateTime.now());
    when(claims.tryClaimLease(eq(lease.taskName()), any())).thenReturn(Optional.of(lease));
    return lease;
  }

  private static DpaReconciliationCandidate candidate(long inviteId, long tenantId) {
    var candidate = mock(DpaReconciliationCandidate.class);
    when(candidate.getInviteId()).thenReturn(inviteId);
    when(candidate.getTenantId()).thenReturn(tenantId);
    when(candidate.getActorId()).thenReturn("existing-tenant-admin");
    return candidate;
  }

  private static DpaGateStatusDTO validGate() {
    return new DpaGateStatusDTO()
        .dpaPublished(true)
        .dpaSigned(true)
        .dpaStatus("VALID")
        .currentDpaVersion("2026-08-01T08:00:00")
        .dpaSignedAt("2026-08-03T10:11:12Z");
  }
}
