package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.IdReservationLock;
import de.caritas.cob.userservice.api.model.IdReservationReleaseTask;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdReservationLockRepository;
import de.caritas.cob.userservice.api.port.out.IdReservationReleaseTaskRepository;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseType;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The Träger and Beratungsstelle IDs our invites hold in TenantService and AgencyService: reserve
 * or share them, and give them back once no pending invite needs them any more.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationLedger {

  static final List<IdAllocationMode> RESERVING_MODES =
      List.of(IdAllocationMode.AUTO, IdAllocationMode.MANUAL);

  /** A unit-admin invite waiting for its Träger can still create the unit; expired ones cannot. */
  static final List<AccountInviteStatus> PENDING_STATUSES =
      List.of(
          AccountInviteStatus.WAITING_FOR_UNIT,
          AccountInviteStatus.DRAFT,
          AccountInviteStatus.EMAIL_SENT);

  private static final List<AccountInviteStatus> ACTIVE_TENANT_INVITE_STATUSES =
      List.of(AccountInviteStatus.DRAFT, AccountInviteStatus.EMAIL_SENT);

  private final @NonNull TenantService tenantService;
  private final @NonNull TenantIdAllocationClient tenantIdAllocationClient;
  private final @NonNull AgencyIdAllocationClient agencyIdAllocationClient;
  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull IdReservationReleaseTaskRepository releaseTaskRepository;
  private final @NonNull IdReservationReleaseProcessor releaseProcessor;
  private final @NonNull IdReservationLockRepository lockRepository;
  private final @NonNull PlatformTransactionManager transactionManager;

  /** The IDs one new invite holds; {@link #undo} gives back only what this call reserved. */
  public static final class Held {
    private final Long tenantId;
    private final String tenantToken;
    private final Long agencyId;
    private final boolean reservedTenant;
    private final boolean reservedAgency;
    private final AtomicBoolean undone = new AtomicBoolean();

    private Held(
        Long tenantId,
        String tenantToken,
        Long agencyId,
        boolean reservedTenant,
        boolean reservedAgency) {
      this.tenantId = tenantId;
      this.tenantToken = tenantToken;
      this.agencyId = agencyId;
      this.reservedTenant = reservedTenant;
      this.reservedAgency = reservedAgency;
    }

    public Long tenantId() {
      return tenantId;
    }

    public String tenantToken() {
      return tenantToken;
    }

    public Long agencyId() {
      return agencyId;
    }
  }

  /** Whether the Träger or Beratungsstelle exists, i.e. is more than a reservation. */
  public boolean unitExists(InviteUnitType type, Long unitId) {
    if (unitId == null) {
      return false;
    }
    if (type == InviteUnitType.TENANT) {
      try {
        return tenantService.getRestrictedTenantData(unitId) != null;
      } catch (HttpClientErrorException exception) {
        if (HttpStatus.NOT_FOUND.equals(exception.getStatusCode())) {
          return false;
        }
        throw exception;
      }
    }
    return agencyIdAllocationClient.getAvailability(unitId) == IdAllocationStatus.ASSIGNED;
  }

  /**
   * Reserves or shares the new IDs and re-checks them, so a stale UI state never duplicates an ID.
   * A rollback of the surrounding transaction gives back what this call reserved.
   */
  public Held reserve(InviteTarget target) {
    TenantIdReservation tenant = null;
    boolean reservedTenant = false;
    if (target.reservation().newTenant()) {
      Optional<TenantIdReservation> shared = Optional.empty();
      if (target.tenantId() != null) {
        if (unitExists(InviteUnitType.TENANT, target.tenantId())) {
          // The admin frontend maps a bare 409 to its "tenant id taken" message.
          throw new ConflictException("tenantId " + target.tenantId() + " is already taken");
        }
        shared = sharedTenantReservation(target.tenantId());
        if (shared.isEmpty() && pendingTenantAdminExists(target.tenantId())) {
          throw new ConflictException("tenantId " + target.tenantId() + " is already taken");
        }
      }
      tenant = shared.orElse(null);
      if (tenant == null) {
        tenant = reserveTenant(target);
        reservedTenant = tenant != null;
      }
    }
    Long tenantId = tenant != null ? Long.valueOf(tenant.tenantId()) : target.tenantId();
    Held held =
        new Held(
            tenantId,
            tenant != null ? tenant.token() : null,
            target.agencyId(),
            reservedTenant,
            false);
    try {
      if (target.reservation().newAgency()) {
        boolean shares =
            target.role() == AccountInviteTargetRole.AGENCY_ADMIN
                && sharesAgencyReservation(target.agencyId(), tenantId, null);
        if (!shares) {
          Long agencyId = agencyIdAllocationClient.reserve(target.agencyId(), tenantId);
          held = new Held(tenantId, held.tenantToken, agencyId, reservedTenant, true);
        }
      }
      revalidate(held);
    } catch (RuntimeException failure) {
      undo(held);
      throw failure;
    }
    undoOnRollback(held);
    return held;
  }

  /** Gives back what {@link #reserve} reserved itself; safe to call more than once. */
  public void undo(Held held) {
    if (held == null || !held.undone.compareAndSet(false, true)) {
      return;
    }
    if (held.reservedAgency && held.agencyId != null) {
      releaseQuietly("agency", () -> agencyIdAllocationClient.release(held.agencyId));
    }
    if (held.reservedTenant && held.tenantId != null) {
      releaseQuietly("tenant", () -> tenantIdAllocationClient.release(held.tenantId));
    }
  }

  /** AgencyService cannot reserve under a Träger that did not exist while the invite waited. */
  public Long reserveAgencyOnRelease(AccountInvite invite) {
    if (!IdAllocationMode.reservesAnId(invite.getAgencyIdAllocationMode())
        || sharesAgencyReservation(invite.getAgencyId(), invite.getTenantId(), invite.getId())) {
      return invite.getAgencyId();
    }
    return agencyIdAllocationClient.reserve(invite.getAgencyId(), invite.getTenantId());
  }

  /**
   * Gives back numbers no other pending invite needs, only while still reserved and only if one of
   * our invites reserved them. Durable tasks, run after commit and retried by the scheduler.
   *
   * <p>Two revokes of invites sharing a number would each see the other as pending and keep it
   * forever, so the decision is serialized on the number's lock row and taken in a new transaction,
   * which sees what the previous holder of that lock committed.
   */
  public void releaseUnneeded(AccountInvite invite, LocalDateTime now) {
    Long tenantId = invite.getTenantId();
    Long agencyId = invite.getAgencyId();
    boolean tenantCandidate = tenantId != null && holdsOrWaitsForTenantReservation(invite);
    boolean agencyCandidate =
        agencyId != null && IdAllocationMode.reservesAnId(invite.getAgencyIdAllocationMode());
    // Always tenant before agency, so two releasers cannot wait for each other.
    if (tenantCandidate) {
      lockNumber(IdReservationReleaseType.TENANT, tenantId);
    }
    if (agencyCandidate) {
      lockNumber(IdReservationReleaseType.AGENCY, agencyId);
    }
    boolean[] unneeded =
        newTransaction()
            .execute(
                transaction ->
                    new boolean[] {
                      tenantCandidate && tenantUnneeded(tenantId, invite.getId(), now),
                      agencyCandidate && agencyUnneeded(agencyId, invite.getId(), now)
                    });
    List<Long> taskIds = new ArrayList<>();
    if (unneeded != null
        && unneeded[0]
        && stillReserved("tenant", () -> tenantIdAllocationClient.getAvailability(tenantId))) {
      taskIds.add(saveReleaseTask(IdReservationReleaseType.TENANT, tenantId, tenantId, now));
    }
    if (unneeded != null
        && unneeded[1]
        && stillReserved("agency", () -> agencyIdAllocationClient.getAvailability(agencyId))) {
      taskIds.add(saveReleaseTask(IdReservationReleaseType.AGENCY, agencyId, tenantId, now));
    }
    processAfterCommit(taskIds);
  }

  private boolean tenantUnneeded(Long tenantId, Long releasingInviteId, LocalDateTime now) {
    return !accountInviteRepository.existsPendingInviteOnTenantNumber(
            tenantId, releasingInviteId, PENDING_STATUSES, now)
        && !releaseTaskRepository.existsByAllocationTypeAndReservedId(
            IdReservationReleaseType.TENANT, tenantId);
  }

  private boolean agencyUnneeded(Long agencyId, Long releasingInviteId, LocalDateTime now) {
    return accountInviteRepository.existsReservationHolderForAgency(agencyId, RESERVING_MODES)
        && !accountInviteRepository.existsPendingInviteOnAgencyNumber(
            agencyId, releasingInviteId, RESERVING_MODES, PENDING_STATUSES, now)
        && !releaseTaskRepository.existsByAllocationTypeAndReservedId(
            IdReservationReleaseType.AGENCY, agencyId);
  }

  /** Held until commit. The row is created first, in its own transaction, never locked absent. */
  private void lockNumber(IdReservationReleaseType type, Long reservedId) {
    try {
      newTransaction()
          .executeWithoutResult(
              transaction -> {
                if (!lockRepository.existsById(new IdReservationLock.Key(type, reservedId))) {
                  lockRepository.saveAndFlush(new IdReservationLock(type, reservedId));
                }
              });
    } catch (DataIntegrityViolationException createdMeanwhile) {
      // Another releaser created the same row a moment ago; locking it below is all we need.
    }
    InviteRowHold.orBusy(() -> lockRepository.findForUpdate(type, reservedId));
  }

  private TransactionTemplate newTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction;
  }

  /**
   * A TenantService without the allocation endpoints answers 404. A legacy request (no mode) then
   * goes on without a reservation; one that demanded AUTO/MANUAL fails loudly.
   */
  private TenantIdReservation reserveTenant(InviteTarget target) {
    try {
      return tenantIdAllocationClient.reserve(target.tenantId());
    } catch (HttpClientErrorException.NotFound exception) {
      if (target.reservation().legacyTenantMode()) {
        log.warn(
            "TenantService does not expose the tenant-ID allocation endpoints yet; creating a"
                + " legacy invite without an authoritative reservation");
        return null;
      }
      throw new InternalServerErrorException(
          "Tenant-ID allocation was requested but TenantService does not expose the allocation"
              + " endpoints (deployment-order gap: deploy TEN-INV-U1 before U3)");
    }
  }

  private void revalidate(Held held) {
    if (held.tenantToken != null
        && releaseTaskRepository.existsByAllocationTypeAndReservedId(
            IdReservationReleaseType.TENANT, held.tenantId)) {
      throw new ConflictException(
          "tenantId " + held.tenantId + " still has a pending reservation cleanup");
    }
    if (held.reservedAgency
        && releaseTaskRepository.existsByAllocationTypeAndReservedId(
            IdReservationReleaseType.AGENCY, held.agencyId)) {
      throw new ConflictException(
          "agencyId " + held.agencyId + " still has a pending reservation cleanup");
    }
    if (held.tenantToken != null
        && tenantIdAllocationClient.getAvailability(held.tenantId) != IdAllocationStatus.RESERVED) {
      throw new ConflictException(
          "tenantId " + held.tenantId + " is no longer reserved for this invite");
    }
    if (held.reservedAgency
        && agencyIdAllocationClient.getAvailability(held.agencyId) != IdAllocationStatus.RESERVED) {
      throw new ConflictException(
          "agencyId " + held.agencyId + " is no longer reserved for this invite");
    }
  }

  private void undoOnRollback(Held held) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) {
              undo(held);
            }
          }
        });
  }

  /** Further admins of the same new Träger share the token; whoever registers first creates it. */
  private Optional<TenantIdReservation> sharedTenantReservation(Long tenantId) {
    return accountInviteRepository
        .findFirstByTargetRoleAndTenantIdAndTenantIdReservationTokenIsNotNullOrderByCreateDateDesc(
            AccountInviteTargetRole.TENANT_ADMIN, tenantId)
        .filter(
            earlier ->
                tenantIdAllocationClient.getAvailability(tenantId) == IdAllocationStatus.RESERVED)
        .map(earlier -> new TenantIdReservation(tenantId, earlier.getTenantIdReservationToken()));
  }

  private boolean pendingTenantAdminExists(Long tenantId) {
    return accountInviteRepository.existsByTenantIdAndTargetRoleAndStatusIn(
        tenantId, AccountInviteTargetRole.TENANT_ADMIN, ACTIVE_TENANT_INVITE_STATUSES);
  }

  /** A further or replacement admin of a new Beratungsstelle shares the earlier reservation. */
  private boolean sharesAgencyReservation(Long agencyId, Long tenantId, Long excludedInviteId) {
    return agencyId != null
        && accountInviteRepository.existsAgencyAdminReservation(
            agencyId, tenantId, excludedInviteId, RESERVING_MODES)
        && agencyIdAllocationClient.getAvailability(agencyId) == IdAllocationStatus.RESERVED;
  }

  /** The invite reserved a new Träger itself, or waited for one an admin invite reserved. */
  private boolean holdsOrWaitsForTenantReservation(AccountInvite invite) {
    if (invite.getTenantIdReservationToken() != null) {
      return true;
    }
    return invite.getWaitingForUnit() == InviteUnitType.TENANT
        && accountInviteRepository
            .findFirstByTargetRoleAndTenantIdAndTenantIdReservationTokenIsNotNullOrderByCreateDateDesc(
                AccountInviteTargetRole.TENANT_ADMIN, invite.getTenantId())
            .isPresent();
  }

  /** An unreachable ledger keeps the number: its unit may exist by now. */
  private boolean stillReserved(String type, Supplier<IdAllocationStatus> availability) {
    try {
      return availability.get() == IdAllocationStatus.RESERVED;
    } catch (RuntimeException ledgerFailure) {
      log.warn(
          "Could not check the {} number before releasing it ({}); it stays reserved",
          type,
          ledgerFailure.getClass().getSimpleName());
      return false;
    }
  }

  private Long saveReleaseTask(
      IdReservationReleaseType type, Long reservedId, Long tenantContextId, LocalDateTime now) {
    return releaseTaskRepository
        .saveAndFlush(
            IdReservationReleaseTask.builder()
                .allocationType(type)
                .reservedId(reservedId)
                .tenantContextId(tenantContextId)
                .createDate(now)
                .build())
        .getId();
  }

  private void processAfterCommit(List<Long> taskIds) {
    if (taskIds.isEmpty()) {
      return;
    }
    Runnable release =
        () -> {
          for (Long taskId : taskIds) {
            try {
              releaseProcessor.process(taskId);
            } catch (RuntimeException releaseFailure) {
              // The durable task stays for the reservation-release scheduler.
              logReleaseFailure("scheduled", releaseFailure);
            }
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              release.run();
            }
          });
    } else {
      release.run();
    }
  }

  private static void releaseQuietly(String type, Supplier<Boolean> release) {
    try {
      if (!Boolean.TRUE.equals(release.get())) {
        logReleaseFailure(type, new IllegalStateException("release pending"));
      }
    } catch (RuntimeException releaseFailure) {
      logReleaseFailure(type, releaseFailure);
    }
  }

  private static void logReleaseFailure(String type, RuntimeException exception) {
    log.warn(
        "Could not release a {} ID reservation ({})", type, exception.getClass().getSimpleName());
  }
}
