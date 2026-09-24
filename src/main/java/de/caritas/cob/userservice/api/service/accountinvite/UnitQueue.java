package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.InviteSendResult;
import de.caritas.cob.userservice.api.service.accountinvite.InviteDelivery.Prepared;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invites into a Träger or Beratungsstelle that does not exist yet. They wait unsent, without a
 * link and without a running expiry clock, until the unit's first admin has onboarded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnitQueue {

  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull InviteEmailTemplateRepository templateRepository;
  private final @NonNull ReservationLedger ledger;
  private final @NonNull InviteDelivery delivery;
  private final @NonNull PlatformTransactionManager transactionManager;

  /** Without a pending admin invite for the unit the invite would never leave: 409 instead. */
  public AccountInvite enqueue(AccountInvite invite, InviteTarget target, Long expiresInDays) {
    InviteUnitType unit = target.waitsFor();
    Long unitId = target.waitedForUnitId();
    if (unitId == null) {
      // AUTO: no admin invite can hold an ID nobody knows yet.
      throw noPendingUnitAdmin();
    }
    if (ledger.unitExists(unit, unitId)) {
      throw new ConflictException(
          (unit == InviteUnitType.AGENCY
                  ? "agencyId " + unitId + " already exists; invite with agencyIdAllocationMode"
                  : "tenantId " + unitId + " already exists; invite with tenantIdAllocationMode")
              + " EXISTING");
    }
    AccountInvite admin =
        pendingUnitAdmins(unit, unitId, target.tenantId(), null).stream()
            .findFirst()
            .orElseThrow(UnitQueue::noPendingUnitAdmin);
    if (invite.getTenantId() == null) {
      invite.setTenantId(admin.getTenantId());
    }
    invite.setWaitingForUnit(unit);
    invite.setQueuedExpiryDays(AccountInviteService.validExpiryDays(expiresInDays));
    invite.setExpiresAt(null);
    invite.setStatus(AccountInviteStatus.WAITING_FOR_UNIT);
    return invite;
  }

  /** Derived on read, so the problem clears once a new admin invite exists. */
  public InviteQueueProblem problemOf(AccountInvite invite) {
    if (invite == null
        || invite.getStatus() != AccountInviteStatus.WAITING_FOR_UNIT
        || invite.getWaitingForUnit() == null) {
      return null;
    }
    Long unitId =
        invite.getWaitingForUnit() == InviteUnitType.AGENCY
            ? invite.getAgencyId()
            : invite.getTenantId();
    if (unitId == null
        || pendingUnitAdmins(
                invite.getWaitingForUnit(), unitId, invite.getTenantId(), invite.getId())
            .isEmpty()) {
      return InviteQueueProblem.NO_UNIT_ADMIN;
    }
    return null;
  }

  /**
   * Sends each invite waiting for the now existing unit with its queued template, or leaves a
   * DRAFT. One failure does not hold up the others. Returns the IDs this call released.
   */
  public List<Long> release(InviteUnitType unitType, Long unitId) {
    if (unitType == null || unitId == null) {
      return List.of();
    }
    List<Long> waitingIds =
        newTransaction()
            .execute(
                transaction ->
                    unitType == InviteUnitType.AGENCY
                        ? accountInviteRepository.findIdsWaitingForAgency(
                            AccountInviteStatus.WAITING_FOR_UNIT, unitId)
                        : accountInviteRepository.findIdsWaitingForTenant(
                            AccountInviteStatus.WAITING_FOR_UNIT, unitId));
    List<Long> released = new ArrayList<>();
    for (Long inviteId : waitingIds == null ? List.<Long>of() : waitingIds) {
      try {
        if (releaseOne(inviteId, null, false).isPresent()) {
          released.add(inviteId);
        }
      } catch (RuntimeException exception) {
        log.warn(
            "Waiting invite {} could not be released for {} {} ({}); it stays waiting and can be"
                + " sent by hand",
            inviteId,
            unitType,
            unitId,
            exception.getClass().getSimpleName());
      }
    }
    log.info(
        "Released {} of {} invites waiting for {} {}",
        released.size(),
        waitingIds == null ? 0 : waitingIds.size(),
        unitType,
        unitId);
    return released;
  }

  /** A send by hand is the release, but only once the unit exists (409 UNIT_NOT_CREATED). */
  public InviteSendResult sendByHand(AccountInvite invite, InviteEmailTemplate template) {
    InviteUnitType unit =
        invite.getWaitingForUnit() == InviteUnitType.TENANT
            ? InviteUnitType.TENANT
            : InviteUnitType.AGENCY;
    Long unitId = unit == InviteUnitType.TENANT ? invite.getTenantId() : invite.getAgencyId();
    if (!ledger.unitExists(unit, unitId)) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.UNIT_NOT_CREATED, HttpStatus.CONFLICT);
    }
    return releaseOne(invite.getId(), template, true)
        .orElseGet(() -> new InviteSendResult(findInvite(invite.getId()), null, null, null));
  }

  /**
   * Claims, commits and only then mails: of two concurrent releases one wins, and the mailed link
   * is the stored one. Empty when another release claimed the invite first.
   */
  private Optional<InviteSendResult> releaseOne(
      Long inviteId, InviteEmailTemplate manualTemplate, boolean rethrowSendFailure) {
    Prepared prepared = newTransaction().execute(transaction -> claim(inviteId, manualTemplate));
    if (prepared == null) {
      return Optional.empty();
    }
    if (prepared.template() == null) {
      return Optional.of(new InviteSendResult(prepared.invite(), null, null, null));
    }
    try {
      return Optional.of(
          delivery.deliver(
              prepared, inviteId, true, sendFailure -> returnToDraft(inviteId, sendFailure)));
    } catch (SmtpSendException sendFailure) {
      log.warn(
          "Released invite {} could not be mailed ({})",
          inviteId,
          sendFailure.getClass().getSimpleName());
      if (rethrowSendFailure) {
        throw sendFailure;
      }
      return Optional.of(new InviteSendResult(findInvite(inviteId), null, null, null));
    }
  }

  private Prepared claim(Long inviteId, InviteEmailTemplate manualTemplate) {
    AccountInvite waiting = findInvite(inviteId);
    if (waiting.getStatus() != AccountInviteStatus.WAITING_FOR_UNIT) {
      return null;
    }
    InviteUnitType unit = waiting.getWaitingForUnit();
    LocalDateTime now = LocalDateTime.now();
    long days =
        waiting.getQueuedExpiryDays() == null
            ? AccountInviteService.DEFAULT_EXPIRY_DAYS
            : waiting.getQueuedExpiryDays();
    if (accountInviteRepository.claimWaitingInvite(inviteId, now.plusDays(days), now) != 1) {
      return null;
    }
    AccountInvite invite = findInvite(inviteId);
    if (unit == InviteUnitType.TENANT) {
      invite.setAgencyId(ledger.reserveAgencyOnRelease(invite));
    }
    InviteEmailTemplate template =
        manualTemplate != null
            ? manualTemplate
            : invite.getQueuedTemplateId() == null
                ? null
                : templateRepository.findById(invite.getQueuedTemplateId()).orElse(null);
    if (template == null) {
      return new Prepared(
          accountInviteRepository.saveAndFlush(invite), null, null, null, null, null, now);
    }
    Prepared prepared = delivery.prepare(invite, template, now);
    invite.setTokenHash(prepared.tokenHash());
    invite.setStatus(AccountInviteStatus.EMAIL_SENT);
    invite.setUpdateDate(now);
    return prepared.withInvite(accountInviteRepository.saveAndFlush(invite));
  }

  /** SMTP confirmed the mail was not sent: a DRAFT without a link, to be sent by hand. */
  private void returnToDraft(Long inviteId, SmtpSendException sendFailure) {
    try {
      newTransaction()
          .executeWithoutResult(
              transaction -> {
                AccountInvite invite = findInvite(inviteId);
                invite.setStatus(AccountInviteStatus.DRAFT);
                invite.setTokenHash(null);
                invite.setUpdateDate(LocalDateTime.now());
                accountInviteRepository.saveAndFlush(invite);
              });
    } catch (RuntimeException compensationFailure) {
      sendFailure.addSuppressed(compensationFailure);
    }
  }

  private List<AccountInvite> pendingUnitAdmins(
      InviteUnitType unit, Long unitId, Long tenantId, Long excludedInviteId) {
    LocalDateTime now = LocalDateTime.now();
    return unit == InviteUnitType.AGENCY
        ? accountInviteRepository.findPendingAgencyAdmins(
            unitId, tenantId, excludedInviteId, ReservationLedger.PENDING_STATUSES, now)
        : accountInviteRepository.findPendingTenantAdmins(
            unitId, excludedInviteId, ReservationLedger.PENDING_STATUSES, now);
  }

  private AccountInvite findInvite(Long inviteId) {
    return accountInviteRepository
        .findById(inviteId)
        .orElseThrow(() -> new NotFoundException("Account invite not found"));
  }

  private TransactionTemplate newTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction;
  }

  private static CustomValidationHttpStatusException noPendingUnitAdmin() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.NO_PENDING_UNIT_ADMIN, HttpStatus.CONFLICT);
  }
}
