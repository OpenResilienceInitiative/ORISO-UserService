package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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

  static final String TEMPLATE_DENIED_MESSAGE =
      "Invite e-mail template is outside the caller's scope";

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
  public CreateAccountInviteCommand authorizeCreate(CreateAccountInviteCommand command) {
    if (command == null || command.targetRole() == null) {
      // Missing fields are answered as 400 by the service's own validation.
      return command;
    }
    return switch (adminScope.current()) {
      case AdminScope.Platform platform -> command;
      case AdminScope.Tenant tenant -> authorizeTenantAdminCreate(command, tenant.tenantId());
      case AdminScope.Agencies agencies -> authorizeAgencyAdminCreate(command, agencies.tenantId());
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

  /**
   * A scoped admin gets 403 for a missing invite as for a foreign one, so the status never tells
   * that a foreign invite exists; the platform sees every invite and keeps 404.
   */
  public void authorizeMissing(Long inviteId) {
    if (!(adminScope.current() instanceof AdminScope.Platform)) {
      throw deny("act on invite " + inviteId);
    }
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

  /**
   * The Träger a template the caller creates belongs to, or {@code null} when the caller is the
   * platform operator and the template is offered to everyone.
   *
   * <p>Creating a template is open to every admin who may send invites (ORISO-Admin#1026 Q30/Q31).
   * That is only safe because the row gets an owner here: without one, a Beratungsstellen admin of
   * Träger A would write a text Träger B sees in its list and sends to its own people.
   */
  public Long templateOwnerTenantId() {
    return templateReach().tenantId();
  }

  /** Whether the caller sees every Träger's templates, not just their own and the platform's. */
  public boolean seesEveryTemplate() {
    return templateReach() instanceof AdminScope.Platform;
  }

  /**
   * Whether the caller may see and send with a template owned by {@code templateTenantId}. Everyone
   * may use a platform template ({@code null}); a Träger's own template is theirs alone.
   */
  public boolean canUseTemplate(Long templateTenantId) {
    var reach = templateReach();
    return reach instanceof AdminScope.Platform
        || templateTenantId == null
        || templateTenantId.equals(reach.tenantId());
  }

  /**
   * Guards reading, previewing and <b>sending with</b> a template. Hiding a foreign template from
   * the list is not enough: its id travels in the send request, so the id has to be refused too.
   *
   * @throws ForbiddenException if the template belongs to another Träger
   */
  public void authorizeTemplateUse(Long templateTenantId) {
    if (!canUseTemplate(templateTenantId)) {
      throw denyTemplate("use invite e-mail template of tenant " + templateTenantId);
    }
  }

  /**
   * Guards changing a stored template. A Träger may change its own; a <b>platform template</b>
   * ({@code tenantId == null}) is the text every other Träger sends, so only the platform operator
   * may change that one (ORISO-Admin#1026, and what dev #1052 already enforces in the Admin).
   *
   * @throws ForbiddenException if the template is not the caller's to change
   */
  public void authorizeTemplateUpdate(Long templateTenantId) {
    if (canChangeTemplate(templateTenantId)) {
      return;
    }
    throw denyTemplate(
        templateTenantId == null
            ? "change the shared platform invite e-mail template"
            : "change the invite e-mail template of tenant " + templateTenantId);
  }

  /**
   * The same rule as {@link #authorizeTemplateUpdate(Long)} as a question, so the API can tell the
   * Admin which templates are the caller's to change. The Admin greys the others out rather than
   * hiding them (house rule "disable, don't hide").
   */
  public boolean canChangeTemplate(Long templateTenantId) {
    var reach = templateReach();
    return reach instanceof AdminScope.Platform
        || (templateTenantId != null && templateTenantId.equals(reach.tenantId()));
  }

  private CreateAccountInviteCommand authorizeAgencyAdminCreate(
      CreateAccountInviteCommand command, Long callerTenantId) {
    if (command.targetRole() != AccountInviteTargetRole.COUNSELLOR) {
      throw deny("invite a " + command.targetRole());
    }
    if (command.agencyIdAllocationMode() != null || command.agencyId() == null) {
      throw deny("invite a counsellor into agency " + command.agencyId());
    }
    adminScope.assertMay(Target.agencies(List.of(command.agencyId())));
    assertTenantIsOwn(command.tenantId(), callerTenantId);
    return withCallerTenant(command, callerTenantId);
  }

  private CreateAccountInviteCommand authorizeTenantAdminCreate(
      CreateAccountInviteCommand command, Long callerTenantId) {
    Set<AccountInviteTargetRole> invitable =
        authenticatedUser.hasTenantLevelAdminRole()
            ? TENANT_ADMIN_INVITABLE_ROLES
            : USER_ADMIN_INVITABLE_ROLES;
    if (!invitable.contains(command.targetRole())) {
      throw deny("invite a " + command.targetRole());
    }
    if (command.tenantIdAllocationMode() != null) {
      throw deny("allocate a new tenant");
    }
    assertTenantIsOwn(command.tenantId(), callerTenantId);
    if (command.agencyId() != null && command.agencyIdAllocationMode() == null) {
      // The accepted invite would attach the new account to that agency.
      adminScope.assertMay(Target.agencies(List.of(command.agencyId())));
    }
    return withCallerTenant(command, callerTenantId);
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
    return new CreateAccountInviteCommand(
        command.targetRole(),
        callerTenantId,
        command.recipientEmail(),
        command.firstName(),
        command.lastName(),
        command.agencyId(),
        command.departmentId(),
        command.expiresInDays(),
        command.tenantIdAllocationMode(),
        command.agencyIdAllocationMode());
  }

  /**
   * The caller's kind and Träger for templates, which never need the agency ids. Unlike {@link
   * AdminScope#current()}, a missing tenant reads as a single-tenant deployment.
   */
  private AdminScope.Reach templateReach() {
    if (authenticatedUser.hasRestrictedAgencyPriviliges()) {
      return new AdminScope.Agencies(adminScope.ownTenantId(), Set.of());
    }
    if (authenticatedUser.getTenantId() == null) {
      return new AdminScope.Platform();
    }
    return adminScope
        .tenantReach()
        .orElseThrow(
            () ->
                denyTemplate(
                    "use invite e-mail templates from tenant 0 without platform-admin roles"));
  }

  private ForbiddenException denyTemplate(String attempt) {
    log.warn(
        "Admin {} (tenant {}) may not {}",
        authenticatedUser.getUserId(),
        authenticatedUser.getTenantId(),
        attempt);
    return new ForbiddenException(TEMPLATE_DENIED_MESSAGE);
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
