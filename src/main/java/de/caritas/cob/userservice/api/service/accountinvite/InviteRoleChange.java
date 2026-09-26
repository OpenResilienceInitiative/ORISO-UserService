package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.ReservationLedger.Held;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes the role of an invite that nobody has accepted yet (ORISO-Admin#1026). The caller may
 * only set roles they could invite, and the unit queue stays consistent: a new agency keeps a
 * pending admin invite, and an invite waits exactly when a new invite of that role would.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InviteRoleChange {

  /** Both run the same onboarding wizard, so even a sent link stays right. */
  private static final Set<AccountInviteTargetRole> AGENCY_LEVEL =
      EnumSet.of(AccountInviteTargetRole.AGENCY_ADMIN, AccountInviteTargetRole.COUNSELLOR);

  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull InviteEmailDeliveryRepository deliveryRepository;
  private final @NonNull AccountInviteAccessPolicy accessPolicy;
  private final @NonNull AgencyFacts agencyFacts;
  private final @NonNull InviteTargetResolver targets;
  private final @NonNull ReservationLedger ledger;
  private final @NonNull UnitQueue unitQueue;
  private final @NonNull AuthenticatedUser authenticatedUser;

  /** {@code alsoCounsellor}: AGENCY_ADMIN only; null keeps it, or means true for a new admin. */
  public record ChangeRoleCommand(AccountInviteTargetRole targetRole, Boolean alsoCounsellor) {}

  /**
   * @throws CustomValidationHttpStatusException 409 with INVITE_ALREADY_ACCEPTED,
   *     INVITE_NOT_PENDING, ROLE_CHANGE_NEEDS_NEW_INVITE, ONLY_UNIT_ADMIN or NO_PENDING_UNIT_ADMIN
   */
  @Transactional
  public AccountInvite change(Long inviteId, ChangeRoleCommand command) {
    if (command == null || command.targetRole() == null) {
      throw new BadRequestException("targetRole is required");
    }
    AccountInvite invite =
        InviteRowHold.lock(accountInviteRepository, inviteId)
            .orElseThrow(() -> new NotFoundException("Account invite not found"));
    accessPolicy.authorizeAccess(invite);
    LocalDateTime now = LocalDateTime.now();
    requirePending(invite, now);
    InviteRowHold.hold(accountInviteRepository, invite, now);

    AccountInviteTargetRole from = invite.getTargetRole();
    AccountInviteTargetRole to = command.targetRole();
    Supplier<Optional<AgencyFacts.Agency>> agency = oneLookupOf(invite);
    CreateAccountInviteCommand asNewInvite = asNewInvite(invite, command);
    accessPolicy.authorizeCreate(asNewInvite);
    if (from == to) {
      return keepRole(invite, command.alsoCounsellor(), now);
    }
    if (!AGENCY_LEVEL.contains(from) || !AGENCY_LEVEL.contains(to)) {
      throw conflict(HttpStatusExceptionReason.ROLE_CHANGE_NEEDS_NEW_INVITE);
    }
    if (foundsANewAgency(invite) && !anotherPendingAdminOfItsAgency(invite, now)) {
      throw conflict(HttpStatusExceptionReason.ONLY_UNIT_ADMIN);
    }

    InviteTarget target = targets.resolve(asNewInvite, agency);
    InviteUnitType waitsNow =
        invite.getStatus() == AccountInviteStatus.WAITING_FOR_UNIT
            ? invite.getWaitingForUnit()
            : null;
    // Reserve before touching the row, so the ledger's queries still see the old role.
    Held held = target.waitsFor() == null && waitsNow != null ? ledger.reserve(target) : null;
    try {
      applyRole(invite, to, command.alsoCounsellor(), target, agency);
      if (target.waitsFor() != null && target.waitsFor() != waitsNow) {
        requeue(invite, target);
      } else if (held != null) {
        leaveQueue(invite, held, now);
      }
      invite.setUpdateDate(now);
      AccountInvite saved = accountInviteRepository.saveAndFlush(invite);
      log.info(
          "Admin {} changed the role of account invite {} from {} to {}",
          authenticatedUser.getUserId(),
          inviteId,
          from,
          to);
      return saved;
    } catch (RuntimeException failure) {
      ledger.undo(held);
      throw failure;
    }
  }

  private static void requirePending(AccountInvite invite, LocalDateTime now) {
    switch (invite.getStatus()) {
      case ACCEPTED -> throw conflict(HttpStatusExceptionReason.INVITE_ALREADY_ACCEPTED);
      case REVOKED, SUPERSEDED, EXPIRED ->
          throw conflict(HttpStatusExceptionReason.INVITE_NOT_PENDING);
      default -> {
        if (invite.getExpiresAt() != null && !invite.getExpiresAt().isAfter(now)) {
          throw conflict(HttpStatusExceptionReason.INVITE_NOT_PENDING);
        }
      }
    }
  }

  private AccountInvite keepRole(AccountInvite invite, Boolean alsoCounsellor, LocalDateTime now) {
    if (alsoCounsellor == null) {
      return invite;
    }
    if (invite.getTargetRole() != AccountInviteTargetRole.AGENCY_ADMIN) {
      throw new BadRequestException("alsoCounsellor is only supported for AGENCY_ADMIN invites");
    }
    invite.setAlsoCounsellor(alsoCounsellor);
    invite.setUpdateDate(now);
    return accountInviteRepository.saveAndFlush(invite);
  }

  /** The first admin of a new agency creates it; the counsellors of that agency wait for them. */
  private static boolean foundsANewAgency(AccountInvite invite) {
    return invite.getTargetRole() == AccountInviteTargetRole.AGENCY_ADMIN
        && IdAllocationMode.reservesAnId(invite.getAgencyIdAllocationMode());
  }

  private boolean anotherPendingAdminOfItsAgency(AccountInvite invite, LocalDateTime now) {
    return invite.getAgencyId() != null
        && !accountInviteRepository
            .findPendingAgencyAdmins(
                invite.getAgencyId(),
                invite.getTenantId(),
                invite.getId(),
                ReservationLedger.PENDING_STATUSES,
                now)
            .isEmpty();
  }

  private static void applyRole(
      AccountInvite invite,
      AccountInviteTargetRole to,
      Boolean alsoCounsellor,
      InviteTarget target,
      Supplier<Optional<AgencyFacts.Agency>> agency) {
    invite.setTargetRole(to);
    invite.setDepartmentId(target.departmentId());
    // Upgraded from counsellor: they were meant to counsel, unless the caller says otherwise.
    invite.setAlsoCounsellor(
        to == AccountInviteTargetRole.AGENCY_ADMIN ? !Boolean.FALSE.equals(alsoCounsellor) : null);
    AgencyFacts.Agency topicsFrom =
        to == AccountInviteTargetRole.COUNSELLOR && !target.newAgency()
            ? agency.get().orElse(null)
            : null;
    invite.setTopicPermission(
        TopicPermissionPolicy.decide(
            to, topicsFrom, target.newAgency(), target.departmentId(), null));
  }

  /** Its unit does not exist yet: the sent link dies and the same template goes out on release. */
  private void requeue(AccountInvite invite, InviteTarget target) {
    Long templateId =
        invite.getStatus() == AccountInviteStatus.EMAIL_SENT
            ? deliveryRepository
                .findFirstByAccountInviteIdOrderByCreateDateDesc(invite.getId())
                .map(InviteEmailDelivery::getTemplateId)
                .orElse(null)
            : invite.getQueuedTemplateId();
    unitQueue.enqueue(invite, target, null);
    invite.setTokenHash(null);
    invite.setQueuedTemplateId(templateId);
  }

  /** Now a founding admin of the new agency: a draft sharing (or taking) its reservation. */
  private static void leaveQueue(AccountInvite invite, Held held, LocalDateTime now) {
    invite.setTenantId(held.tenantId());
    if (held.tenantToken() != null) {
      invite.setTenantIdReservationToken(held.tenantToken());
    }
    invite.setAgencyId(held.agencyId());
    long days =
        invite.getQueuedExpiryDays() == null
            ? AccountInviteService.DEFAULT_EXPIRY_DAYS
            : invite.getQueuedExpiryDays();
    invite.setExpiresAt(now.plusDays(days));
    invite.setWaitingForUnit(null);
    invite.setQueuedTemplateId(null);
    invite.setStatus(AccountInviteStatus.DRAFT);
  }

  private static CreateAccountInviteCommand asNewInvite(
      AccountInvite invite, ChangeRoleCommand command) {
    return new CreateAccountInviteCommand(
        command.targetRole(),
        invite.getTenantId(),
        invite.getRecipientEmail(),
        invite.getFirstName(),
        invite.getLastName(),
        invite.getAgencyId(),
        invite.getDepartmentId(),
        null,
        invite.getTenantIdAllocationMode(),
        invite.getAgencyIdAllocationMode(),
        command.alsoCounsellor(),
        null);
  }

  private Supplier<Optional<AgencyFacts.Agency>> oneLookupOf(AccountInvite invite) {
    if (invite.getAgencyId() == null
        || IdAllocationMode.reservesAnId(invite.getAgencyIdAllocationMode())) {
      return Optional::empty;
    }
    Optional<AgencyFacts.Agency>[] cached = new Optional[1];
    return () -> {
      if (cached[0] == null) {
        cached[0] = agencyFacts.find(invite.getAgencyId());
      }
      return cached[0];
    };
  }

  private static CustomValidationHttpStatusException conflict(HttpStatusExceptionReason reason) {
    return new CustomValidationHttpStatusException(reason, HttpStatus.CONFLICT);
  }
}
