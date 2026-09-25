package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The invite rules: which roles a caller may invite and which allocation modes they may use. How
 * far the caller reaches is {@link AdminScope}'s answer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountInviteAccessPolicy {

  static final String OUT_OF_SCOPE_MESSAGE = "Account invite is outside the caller's scope";

  private static final Set<AccountInviteTargetRole> TENANT_ADMIN_INVITABLE_ROLES =
      EnumSet.of(
          AccountInviteTargetRole.TENANT_ADMIN,
          AccountInviteTargetRole.AGENCY_ADMIN,
          AccountInviteTargetRole.COUNSELLOR);

  private static final Set<AccountInviteTargetRole> USER_ADMIN_INVITABLE_ROLES =
      EnumSet.of(AccountInviteTargetRole.AGENCY_ADMIN, AccountInviteTargetRole.COUNSELLOR);

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull AdminScope adminScope;

  /** The filter a listing has to apply for the calling admin. */
  public record InviteListScope(
      Long tenantId, AccountInviteTargetRole targetRole, Set<Long> agencyIds, boolean empty) {

    /** {@code agencyIds == null} means "not restricted to agencies". */
    public boolean restrictedToAgencies() {
      return agencyIds != null;
    }
  }

  /** Returns the command, stamped with the caller's tenant if it named none. */
  public CreateAccountInviteCommand authorizeCreate(
      CreateAccountInviteCommand command, Supplier<Optional<AgencyFacts.Agency>> agency) {
    if (command == null || command.targetRole() == null) {
      // Missing fields are answered as 400 by the service's own validation.
      return command;
    }
    return switch (adminScope.current()) {
      case AdminScope.Platform platform -> command;
      case AdminScope.Tenant tenant ->
          authorizeTenantAdminCreate(command, tenant.tenantId(), agency);
      case AdminScope.Agencies agencies -> authorizeAgencyAdminCreate(command, agencies);
    };
  }

  /** Both filters may be null; explicitly asking for another tenant is refused. */
  public InviteListScope scopeForListing(
      Long requestedTenantId, AccountInviteTargetRole requestedTargetRole) {
    var reach = adminScope.current();
    if (reach instanceof AdminScope.Platform) {
      return new InviteListScope(requestedTenantId, requestedTargetRole, null, false);
    }
    assertTenantIsOwn(requestedTenantId, reach.tenantId());
    Long tenantId = reach.tenantId() != null ? reach.tenantId() : requestedTenantId;
    if (!(reach instanceof AdminScope.Agencies agencies)) {
      return new InviteListScope(tenantId, requestedTargetRole, null, false);
    }
    // Agency admins see counsellor invites of their own agencies only.
    boolean empty =
        agencies.ids().isEmpty()
            || (requestedTargetRole != null
                && requestedTargetRole != AccountInviteTargetRole.COUNSELLOR);
    return new InviteListScope(tenantId, AccountInviteTargetRole.COUNSELLOR, agencies.ids(), empty);
  }

  /** Covers every action on an existing invite: send, resend, revoke, waive 2FA. */
  public void authorizeAccess(AccountInvite invite) {
    if (invite == null) {
      return;
    }
    if (adminScope.current() instanceof AdminScope.Agencies
        && invite.getTargetRole() != AccountInviteTargetRole.COUNSELLOR) {
      throw deny("act on invite " + invite.getId());
    }
    adminScope.assertMay(Target.placedIn(invite.getTenantId(), invite.getAgencyId()));
  }

  /** "Higher invites lower": may the caller give anybody this role, by invite or to an account? */
  public void assertMayInvite(AccountInviteTargetRole role) {
    boolean allowed =
        switch (adminScope.current()) {
          case AdminScope.Platform platform -> true;
          case AdminScope.Tenant tenant -> invitableByTenantReach().contains(role);
          case AdminScope.Agencies agencies -> role == AccountInviteTargetRole.COUNSELLOR;
        };
    if (!allowed) {
      throw deny("give the role " + role);
    }
  }

  /** May the caller add their own account as counsellor of this agency ("higher assigns lower")? */
  public void authorizeSelfAssignment(long agencyId, Long agencyTenantId) {
    switch (adminScope.current()) {
      case AdminScope.Platform platform -> {}
      case AdminScope.Tenant tenant -> {
        if (!authenticatedUser.hasTenantLevelAdminRole()
            || !tenant.tenantId().equals(agencyTenantId)) {
          throw deny("assign themselves in agency " + agencyId);
        }
      }
      case AdminScope.Agencies agencies -> {
        if (!agencies.ids().contains(agencyId)) {
          throw deny("assign themselves in agency " + agencyId);
        }
      }
    }
  }

  private CreateAccountInviteCommand authorizeAgencyAdminCreate(
      CreateAccountInviteCommand command, AdminScope.Agencies agencies) {
    if (command.targetRole() != AccountInviteTargetRole.COUNSELLOR) {
      throw deny("invite a " + command.targetRole());
    }
    if (IdAllocationMode.reservesAnId(command.tenantIdAllocationMode())) {
      throw deny("invite into tenant " + command.tenantId());
    }
    if (IdAllocationMode.reservesAnId(command.agencyIdAllocationMode())
        || command.agencyId() == null
        || !agencies.ids().contains(command.agencyId())) {
      throw deny("invite a counsellor into agency " + command.agencyId());
    }
    assertTenantIsOwn(command.tenantId(), agencies.tenantId());
    return withCallerTenant(command, agencies.tenantId());
  }

  private CreateAccountInviteCommand authorizeTenantAdminCreate(
      CreateAccountInviteCommand command,
      Long callerTenantId,
      Supplier<Optional<AgencyFacts.Agency>> agency) {
    if (!invitableByTenantReach().contains(command.targetRole())) {
      throw deny("invite a " + command.targetRole());
    }
    if (command.tenantIdAllocationMode() != null
        && command.tenantIdAllocationMode() != IdAllocationMode.EXISTING) {
      // Onboarding a new Träger is the platform's job; EXISTING (their own Träger) is fine.
      throw deny("allocate a new tenant");
    }
    assertTenantIsOwn(command.tenantId(), callerTenantId);
    if (command.agencyId() != null
        && !IdAllocationMode.reservesAnId(command.agencyIdAllocationMode())
        && !agency.get().map(found -> callerTenantId.equals(found.tenantId())).orElse(false)) {
      // The accepted invite would attach the new account to that agency.
      throw deny("invite into agency " + command.agencyId());
    }
    return withCallerTenant(command, callerTenantId);
  }

  private Set<AccountInviteTargetRole> invitableByTenantReach() {
    return authenticatedUser.hasTenantLevelAdminRole()
        ? TENANT_ADMIN_INVITABLE_ROLES
        : USER_ADMIN_INVITABLE_ROLES;
  }

  /** A named tenant is stored on the invite, so it must be the caller's own. */
  private void assertTenantIsOwn(Long requestedTenantId, Long callerTenantId) {
    if (requestedTenantId != null
        && callerTenantId != null
        && !callerTenantId.equals(requestedTenantId)) {
      throw deny("use tenant " + requestedTenantId);
    }
  }

  private static CreateAccountInviteCommand withCallerTenant(
      CreateAccountInviteCommand command, Long callerTenantId) {
    if (command.tenantId() != null || callerTenantId == null) {
      return command;
    }
    return command.withTenantId(callerTenantId);
  }

  private ForbiddenException deny(String attempt) {
    log.warn(
        "Admin {} (tenant {}) may not {}",
        authenticatedUser.getUserId(),
        authenticatedUser.getTenantId(),
        attempt);
    return new ForbiddenException(OUT_OF_SCOPE_MESSAGE);
  }
}
