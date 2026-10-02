package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
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

  private static final Set<InviteEmailTemplateKind> TENANT_TEMPLATE_KINDS =
      EnumSet.of(InviteEmailTemplateKind.COUNSELLOR_INVITE, InviteEmailTemplateKind.TENANT_INVITE);

  private static final Set<InviteEmailTemplateKind> AGENCY_TEMPLATE_KINDS =
      EnumSet.of(InviteEmailTemplateKind.COUNSELLOR_INVITE);

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

  /**
   * The Träger a template the caller creates belongs to, or {@code null} when the caller is the
   * platform operator and the template is offered to everyone.
   *
   * <p>Creating a template is open to every admin who may send invites (ORISO-Admin#1026 Q30/Q31).
   * That is only safe because the row gets an owner here: without one, a Beratungsstellen admin of
   * Träger A would write a text Träger B sees in its list and sends to its own people.
   */
  public Long templateOwnerTenantId() {
    return adminScope.ownerReach().tenantId();
  }

  /** Whether the caller sees every Träger's templates, not just their own and the platform's. */
  public boolean seesEveryTemplate() {
    return adminScope.ownerReach() instanceof AdminScope.Platform;
  }

  /**
   * The template kinds the caller may create, change, list and use. A Beratungsstellen admin
   * invites counsellors only; a Träger admin also invites Träger admins but never forwards the
   * contract (DPA_FORWARD); the platform admin may use every kind.
   */
  public Set<InviteEmailTemplateKind> templateKindsInReach() {
    return switch (adminScope.ownerReach()) {
      case AdminScope.Platform platform -> EnumSet.allOf(InviteEmailTemplateKind.class);
      case AdminScope.Tenant tenant -> EnumSet.copyOf(TENANT_TEMPLATE_KINDS);
      case AdminScope.Agencies agencies -> EnumSet.copyOf(AGENCY_TEMPLATE_KINDS);
    };
  }

  /** Whether {@code kind} is one of {@link #templateKindsInReach()}. */
  public boolean mayUseTemplateKind(InviteEmailTemplateKind kind) {
    return kind != null && templateKindsInReach().contains(kind);
  }

  /**
   * Guards creating a template of {@code kind}, and changing a template <b>to</b> that kind.
   *
   * @throws ForbiddenException if the kind is out of the caller's reach
   */
  public void authorizeTemplateKind(InviteEmailTemplateKind kind) {
    if (!mayUseTemplateKind(kind)) {
      throw denyTemplate("write an invite e-mail template of kind " + kind);
    }
  }

  /**
   * Whether the caller may see and send with a template owned by {@code templateTenantId}. Everyone
   * may use a platform template ({@code null}); a Träger's own template is theirs alone. The owner
   * rule only; {@link #canUseTemplate(Long, InviteEmailTemplateKind)} adds the kind rule.
   */
  boolean canUseTemplate(Long templateTenantId) {
    var reach = adminScope.ownerReach();
    return reach instanceof AdminScope.Platform
        || templateTenantId == null
        || templateTenantId.equals(reach.tenantId());
  }

  /** The owner rule and the kind rule together: may the caller see and send with this template? */
  public boolean canUseTemplate(Long templateTenantId, InviteEmailTemplateKind kind) {
    return canUseTemplate(templateTenantId) && mayUseTemplateKind(kind);
  }

  /**
   * Guards reading, previewing and <b>sending with</b> a template. Hiding a foreign template from
   * the list is not enough: its id travels in the send request, so the id has to be refused too.
   * The same holds for a kind out of reach, e.g. a Beratungsstellen admin who learns the id of a
   * Träger invite.
   *
   * @throws ForbiddenException if the template belongs to another Träger or its kind is out of
   *     reach
   */
  public void authorizeTemplateUse(Long templateTenantId, InviteEmailTemplateKind kind) {
    if (!canUseTemplate(templateTenantId)) {
      throw denyTemplate("use invite e-mail template of tenant " + templateTenantId);
    }
    if (!mayUseTemplateKind(kind)) {
      throw denyTemplate("use an invite e-mail template of kind " + kind);
    }
  }

  /**
   * Guards changing a stored template. A Träger may change its own; a <b>platform template</b>
   * ({@code tenantId == null}, including the built-in system defaults) is the text every other
   * Träger sends, so only the platform operator may change that one (ORISO-Admin#1026, and what dev
   * #1052 already enforces in the Admin). The stored kind has to be in reach as well.
   *
   * @throws ForbiddenException if the template is not the caller's to change
   */
  public void authorizeTemplateUpdate(Long templateTenantId, InviteEmailTemplateKind kind) {
    if (!canChangeTemplate(templateTenantId)) {
      throw denyTemplate(
          templateTenantId == null
              ? "change the shared platform invite e-mail template"
              : "change the invite e-mail template of tenant " + templateTenantId);
    }
    if (!mayUseTemplateKind(kind)) {
      throw denyTemplate("change an invite e-mail template of kind " + kind);
    }
  }

  /**
   * The owner part of {@link #authorizeTemplateUpdate(Long, InviteEmailTemplateKind)} as a
   * question.
   */
  boolean canChangeTemplate(Long templateTenantId) {
    var reach = adminScope.ownerReach();
    return reach instanceof AdminScope.Platform
        || (templateTenantId != null && templateTenantId.equals(reach.tenantId()));
  }

  /**
   * The same rule as {@link #authorizeTemplateUpdate(Long, InviteEmailTemplateKind)} as a question,
   * so the API can tell the Admin which templates are the caller's to change. The Admin greys the
   * others out rather than hiding them (house rule "disable, don't hide").
   */
  public boolean canChangeTemplate(Long templateTenantId, InviteEmailTemplateKind kind) {
    return canChangeTemplate(templateTenantId) && mayUseTemplateKind(kind);
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
      CreateAccountInviteCommand command, Long callerTenantId) {
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
        && !IdAllocationMode.reservesAnId(command.agencyIdAllocationMode())) {
      // The accepted invite would attach the new account to that agency.
      adminScope.assertMay(Target.agencies(List.of(command.agencyId())));
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
