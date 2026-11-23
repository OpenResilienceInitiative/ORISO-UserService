package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.dpa.TenantDpaGateReadClient;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Mirrors TenantService's verified AVV milestone on the tenant's accepted invitations. */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
class DpaInviteReconciliation {

  private static final String TASK_NAME = "account-invite-dpa-reconciliation";
  private static final Duration CLAIM_DURATION = Duration.ofMinutes(5);
  private static final long START_WINDOW_NANOS = Duration.ofMinutes(2).toNanos();
  static final int BATCH_SIZE = 10;

  private final AccountInviteRepository invites;
  private final TenantDpaGateReadClient dpaOwner;
  private final ScheduledTaskClaimService claims;

  private long afterInviteId;
  private long throughInviteId;

  @Scheduled(fixedDelayString = "${account-invite.dpa-reconciliation.delay-ms:60000}")
  synchronized void reconcile() {
    var claim = claims.tryClaimLease(TASK_NAME, CLAIM_DURATION);
    if (claim.isEmpty()) {
      return;
    }
    try {
      reconcileBatch();
    } finally {
      claims.release(claim.get());
    }
  }

  private void reconcileBatch() {
    long started = System.nanoTime();
    if (throughInviteId == 0) {
      // New invitations belong to the next pass, so a busy queue cannot prevent retries.
      throughInviteId =
          TenantContext.supplyAcrossTenants(invites::findDpaReconciliationUpperBound).orElse(0L);
      if (throughInviteId == 0) {
        return;
      }
    }
    var candidates =
        TenantContext.supplyAcrossTenants(
            () ->
                invites.findDpaReconciliationCandidates(
                    afterInviteId, throughInviteId, PageRequest.of(0, BATCH_SIZE)));
    Map<Long, Optional<LocalDateTime>> signatures = new HashMap<>();
    for (var candidate : candidates) {
      // Reserve time for a final owner read, including a cold identity-key lookup.
      if (System.nanoTime() - started >= START_WINDOW_NANOS) {
        return;
      }
      Long tenantId = candidate.getTenantId();
      try {
        signatures
            .computeIfAbsent(tenantId, this::readCurrentSignature)
            .ifPresent(
                signedAt ->
                    TenantContext.runIn(
                        tenantId,
                        () ->
                            invites.reconcileDpaSigned(
                                candidate.getInviteId(),
                                tenantId,
                                candidate.getActorId(),
                                signedAt)));
      } catch (RuntimeException exception) {
        log.warn(
            "Could not reconcile DPA milestone for invite {} in tenant {} ({})",
            candidate.getInviteId(),
            tenantId,
            exception.getClass().getSimpleName());
      }
      // Unsigned tenants and failures must not hold up later rows in this pass.
      afterInviteId = candidate.getInviteId();
    }
    if (candidates.size() < BATCH_SIZE || afterInviteId == throughInviteId) {
      afterInviteId = 0;
      throughInviteId = 0;
    }
  }

  private Optional<LocalDateTime> readCurrentSignature(Long tenantId) {
    try {
      var gate = dpaOwner.read(tenantId);
      if (gate == null
          || !Boolean.TRUE.equals(gate.getDpaPublished())
          || !Boolean.TRUE.equals(gate.getDpaSigned())
          || !"VALID".equals(gate.getDpaStatus())
          || gate.getCurrentDpaVersion() == null
          || gate.getCurrentDpaVersion().isBlank()
          || gate.getDpaSignedAt() == null) {
        return Optional.empty();
      }
      Instant signature = Instant.parse(gate.getDpaSignedAt());
      // The version key is the owner's local wall time, not an instant to compare against UTC.
      if (!signature.isAfter(Instant.now())) {
        return Optional.of(LocalDateTime.ofInstant(signature, ZoneOffset.UTC));
      }
    } catch (DateTimeParseException exception) {
      log.warn("Invalid DPA signature date for tenant {}", tenantId);
    } catch (RuntimeException exception) {
      log.warn(
          "Could not verify DPA signature for tenant {} ({})",
          tenantId,
          exception.getClass().getSimpleName());
    }
    return Optional.empty();
  }
}
