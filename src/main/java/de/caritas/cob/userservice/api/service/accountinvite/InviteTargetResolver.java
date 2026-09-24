package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.InviteTarget.Reservation;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Turns the allocation modes of a create request into one {@link InviteTarget}. */
@Component
@RequiredArgsConstructor
public class InviteTargetResolver {

  private final @NonNull ReservationLedger ledger;

  /** {@code agency} is the request's single lookup, called only when a rule needs it. */
  public InviteTarget resolve(
      CreateAccountInviteCommand command, Supplier<Optional<AgencyFacts.Agency>> agency) {
    validateModes(command);
    validateAgencyAdmin(command);
    IdAllocationMode tenantMode = command.tenantIdAllocationMode();
    IdAllocationMode agencyMode = command.agencyIdAllocationMode();
    if (tenantMode == IdAllocationMode.EXISTING
        && !ledger.unitExists(InviteUnitType.TENANT, command.tenantId())) {
      throw new NotFoundException("tenantId " + command.tenantId() + " does not exist");
    }
    Long tenantId = command.tenantId();
    Long departmentId = command.departmentId();
    if (agencyMode == IdAllocationMode.EXISTING) {
      AgencyFacts.Agency existing = existingAgency(command, agency);
      tenantId = tenantId != null ? tenantId : existing.tenantId();
      departmentId = bindDepartment(command, existing);
    }
    InviteUnitType waitsFor = waitsFor(command);
    boolean tenantAdmin = command.targetRole() == AccountInviteTargetRole.TENANT_ADMIN;
    Reservation reservation =
        waitsFor != null
            ? Reservation.NONE
            : new Reservation(
                tenantAdmin && tenantMode != IdAllocationMode.EXISTING,
                IdAllocationMode.reservesAnId(agencyMode),
                tenantMode == null);
    return new InviteTarget(
        command.targetRole(), tenantId, command.agencyId(), departmentId, waitsFor, reservation);
  }

  /** A unit's own admin invites never wait: they create the unit. */
  private static InviteUnitType waitsFor(CreateAccountInviteCommand command) {
    if (command.targetRole() == AccountInviteTargetRole.COUNSELLOR
        && IdAllocationMode.reservesAnId(command.agencyIdAllocationMode())) {
      return InviteUnitType.AGENCY;
    }
    if (command.targetRole() == AccountInviteTargetRole.AGENCY_ADMIN
        && IdAllocationMode.reservesAnId(command.tenantIdAllocationMode())) {
      return InviteUnitType.TENANT;
    }
    return null;
  }

  private static AgencyFacts.Agency existingAgency(
      CreateAccountInviteCommand command, Supplier<Optional<AgencyFacts.Agency>> agency) {
    AgencyFacts.Agency existing =
        agency
            .get()
            .filter(found -> !found.deleted())
            .orElseThrow(
                () -> new NotFoundException("agencyId " + command.agencyId() + " does not exist"));
    // Only the platform admin can get here with a foreign tenant; the policy stamps the others'.
    if (command.tenantId() != null && !command.tenantId().equals(existing.tenantId())) {
      throw new BadRequestException(
          "agencyId " + command.agencyId() + " does not belong to tenant " + command.tenantId());
    }
    return existing;
  }

  /** The agency's only topic when none is named, so the counsellor always has one. */
  private static Long bindDepartment(
      CreateAccountInviteCommand command, AgencyFacts.Agency existing) {
    List<Long> topicIds = existing.topicIds();
    Long departmentId = command.departmentId();
    if (departmentId != null && !topicIds.isEmpty() && !topicIds.contains(departmentId)) {
      throw new BadRequestException(
          "departmentId " + departmentId + " is not a topic of agency " + command.agencyId());
    }
    return departmentId == null && topicIds.size() == 1 ? topicIds.get(0) : departmentId;
  }

  private static void validateModes(CreateAccountInviteCommand command) {
    IdAllocationMode tenantMode = command.tenantIdAllocationMode();
    IdAllocationMode agencyMode = command.agencyIdAllocationMode();
    if (tenantMode == IdAllocationMode.MANUAL && command.tenantId() == null) {
      throw new BadRequestException("tenantId is required in MANUAL tenant allocation mode");
    }
    if (tenantMode == IdAllocationMode.AUTO && command.tenantId() != null) {
      throw new BadRequestException("tenantId must be omitted in AUTO tenant allocation mode");
    }
    if (tenantMode == IdAllocationMode.EXISTING) {
      validateExistingTenant(command);
    } else if (tenantMode != null
        && command.targetRole() != AccountInviteTargetRole.TENANT_ADMIN
        && !waitsInANewTenant(command)) {
      throw new BadRequestException(
          "tenantIdAllocationMode AUTO/MANUAL is only supported for TENANT_ADMIN invites and, as"
              + " queued invites, for AGENCY_ADMIN / COUNSELLOR invites into a new agency");
    }
    if (agencyMode == IdAllocationMode.EXISTING && IdAllocationMode.reservesAnId(tenantMode)) {
      throw new BadRequestException("An existing agency cannot belong to a new tenant");
    }
    if (agencyMode == IdAllocationMode.MANUAL && command.agencyId() == null) {
      throw new BadRequestException("agencyId is required in MANUAL agency allocation mode");
    }
    if (agencyMode == IdAllocationMode.AUTO && command.agencyId() != null) {
      throw new BadRequestException("agencyId must be omitted in AUTO agency allocation mode");
    }
    if (agencyMode == IdAllocationMode.EXISTING) {
      if (command.agencyId() == null) {
        throw new BadRequestException("agencyId is required in EXISTING agency allocation mode");
      }
      if (command.targetRole() != AccountInviteTargetRole.COUNSELLOR
          && command.targetRole() != AccountInviteTargetRole.AGENCY_ADMIN) {
        throw new BadRequestException(
            "EXISTING agency allocation mode is only supported for COUNSELLOR and AGENCY_ADMIN"
                + " invites");
      }
    }
  }

  /** A Träger-bound caller that named no tenant already got its own stamped by the policy. */
  private static void validateExistingTenant(CreateAccountInviteCommand command) {
    if (command.targetRole() != AccountInviteTargetRole.TENANT_ADMIN
        && command.targetRole() != AccountInviteTargetRole.AGENCY_ADMIN
        && command.targetRole() != AccountInviteTargetRole.COUNSELLOR) {
      throw new BadRequestException(
          "EXISTING tenant allocation mode is only supported for TENANT_ADMIN, AGENCY_ADMIN and"
              + " COUNSELLOR invites");
    }
    if (command.tenantId() == null) {
      throw new BadRequestException("tenantId is required in EXISTING tenant allocation mode");
    }
    if (TenantContext.TECHNICAL_TENANT_ID.equals(command.tenantId())) {
      throw new BadRequestException("The platform tenant cannot be the target of an invite");
    }
  }

  /** An agency admin always administers an agency: an existing one or a new AUTO/MANUAL one. */
  private static void validateAgencyAdmin(CreateAccountInviteCommand command) {
    boolean agencyAdmin = command.targetRole() == AccountInviteTargetRole.AGENCY_ADMIN;
    if (!agencyAdmin && command.alsoCounsellor() != null) {
      throw new BadRequestException("alsoCounsellor is only supported for AGENCY_ADMIN invites");
    }
    if (agencyAdmin
        && command.agencyId() == null
        && command.agencyIdAllocationMode() != IdAllocationMode.AUTO) {
      throw new BadRequestException("An AGENCY_ADMIN invite requires an agency");
    }
  }

  private static boolean waitsInANewTenant(CreateAccountInviteCommand command) {
    return (command.targetRole() == AccountInviteTargetRole.AGENCY_ADMIN
            || command.targetRole() == AccountInviteTargetRole.COUNSELLOR)
        && IdAllocationMode.reservesAnId(command.agencyIdAllocationMode());
  }
}
