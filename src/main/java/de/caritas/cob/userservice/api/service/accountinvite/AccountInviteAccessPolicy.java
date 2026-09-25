package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Who may see and act on which account invite ("cross-Träger" isolation of the invite API).
 *
 * <p>The invite rows carry the target tenant and agency, but the HTTP layer only checks roles: any
 * caller holding {@code user-admin} — every Träger admin and every Beratungsstellen admin — reaches
 * {@code /useradmin/account-invites}. Without this policy a Träger admin could list, create,
 * resend, revoke and waive invites of every other Träger, and invite a platform admin.
 *
 * <ul>
 *   <li><b>Platform admin</b> (tenant {@code 0}) and callers without a tenant (single-tenant
 *       deployment): unrestricted — the same boundary as {@code AdminTenantOwnershipValidator}.
 *   <li><b>Träger admin</b> (bound to a tenant): only invites of their own tenant; may invite
 *       Träger admins, agency admins and counsellors, never a platform admin or an advice seeker,
 *       and never let the server allocate a new tenant (onboarding a new Träger is the platform's
 *       job). An invite into an existing agency must name an agency of their own tenant.
 *   <li><b>Beratungsstellen admin</b> (restricted agency admin): only counsellor invites into the
 *       agencies they administer.
 * </ul>
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
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AgencyService agencyService;

  /** The filter a listing has to apply for the calling admin. */
  public record InviteListScope(
      Long tenantId, AccountInviteTargetRole targetRole, Set<Long> agencyIds, boolean empty) {

    /** {@code agencyIds == null} means "not restricted to agencies". */
    public boolean restrictedToAgencies() {
      return agencyIds != null;
    }
  }

  /**
   * Checks a create request against the caller's scope.
   *
   * @return the command to execute — for a tenant-bound caller that named no tenant, the same
   *     command stamped with the caller's own tenant
   * @throws ForbiddenException if the invite would leave the caller's scope
   */
  public CreateAccountInviteCommand authorizeCreate(CreateAccountInviteCommand command) {
    if (command == null || command.targetRole() == null) {
      // Missing fields are answered as 400 by the service's own validation.
      return command;
    }
    Scope scope = callerScope();
    switch (scope.kind()) {
      case AGENCY:
        return authorizeAgencyAdminCreate(command, scope);
      case TENANT:
        return authorizeTenantAdminCreate(command, scope);
      default:
        return command;
    }
  }

  /**
   * Narrows an invite listing to the caller's scope.
   *
   * @param requestedTenantId the {@code tenant_id} filter the caller asked for, may be null
   * @param requestedTargetRole the {@code target_role} filter the caller asked for, may be null
   * @throws ForbiddenException if the caller explicitly asks for another tenant
   */
  public InviteListScope scopeForListing(
      Long requestedTenantId, AccountInviteTargetRole requestedTargetRole) {
    Scope scope = callerScope();
    if (scope.kind() == Kind.UNRESTRICTED) {
      return new InviteListScope(requestedTenantId, requestedTargetRole, null, false);
    }
    if (requestedTenantId != null
        && scope.tenantId() != null
        && !scope.tenantId().equals(requestedTenantId)) {
      throw deny("list the invites of tenant " + requestedTenantId);
    }
    Long tenantId = scope.tenantId() != null ? scope.tenantId() : requestedTenantId;
    if (scope.kind() == Kind.TENANT) {
      return new InviteListScope(tenantId, requestedTargetRole, null, false);
    }
    // Beratungsstellen admin: counsellor invites of their own agencies only.
    boolean empty =
        scope.agencyIds().isEmpty()
            || (requestedTargetRole != null
                && requestedTargetRole != AccountInviteTargetRole.COUNSELLOR);
    return new InviteListScope(
        tenantId, AccountInviteTargetRole.COUNSELLOR, scope.agencyIds(), empty);
  }

  /**
   * Checks that the caller may act on an existing invite (send, resend, revoke, waive 2FA).
   *
   * @throws ForbiddenException if the invite lies outside the caller's scope
   */
  public void authorizeAccess(AccountInvite invite) {
    if (invite == null) {
      return;
    }
    Scope scope = callerScope();
    switch (scope.kind()) {
      case TENANT:
        if (!scope.tenantId().equals(invite.getTenantId())) {
          throw deny("act on invite " + invite.getId() + " of tenant " + invite.getTenantId());
        }
        return;
      case AGENCY:
        if (invite.getTargetRole() != AccountInviteTargetRole.COUNSELLOR
            || invite.getAgencyId() == null
            || !scope.agencyIds().contains(invite.getAgencyId())
            || (scope.tenantId() != null && !scope.tenantId().equals(invite.getTenantId()))) {
          throw deny("act on invite " + invite.getId());
        }
        return;
      default:
        return;
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
    return templateScope().tenantId();
  }

  /** Whether the caller sees every Träger's templates, not just their own and the platform's. */
  public boolean seesEveryTemplate() {
    return templateScope().kind() == Kind.UNRESTRICTED;
  }

  /**
   * Whether the caller may see and send with a template owned by {@code templateTenantId}. Everyone
   * may use a platform template ({@code null}); a Träger's own template is theirs alone.
   */
  public boolean canUseTemplate(Long templateTenantId) {
    Scope scope = templateScope();
    return scope.kind() == Kind.UNRESTRICTED
        || templateTenantId == null
        || templateTenantId.equals(scope.tenantId());
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
    Scope scope = templateScope();
    return scope.kind() == Kind.UNRESTRICTED
        || (templateTenantId != null && templateTenantId.equals(scope.tenantId()));
  }

  private CreateAccountInviteCommand authorizeAgencyAdminCreate(
      CreateAccountInviteCommand command, Scope scope) {
    if (command.targetRole() != AccountInviteTargetRole.COUNSELLOR) {
      throw deny("invite a " + command.targetRole());
    }
    if (command.agencyIdAllocationMode() != null
        || command.agencyId() == null
        || !scope.agencyIds().contains(command.agencyId())) {
      throw deny("invite a counsellor into agency " + command.agencyId());
    }
    assertTenantMatchesScope(command.tenantId(), scope);
    return withCallerTenant(command, scope);
  }

  /**
   * A caller-supplied tenant must be the caller's own: {@link #withCallerTenant} keeps a non-null
   * tenant, so a foreign one would otherwise be stored on the invite.
   */
  private void assertTenantMatchesScope(Long requestedTenantId, Scope scope) {
    if (requestedTenantId != null
        && scope.tenantId() != null
        && !scope.tenantId().equals(requestedTenantId)) {
      throw deny("invite into tenant " + requestedTenantId);
    }
  }

  private CreateAccountInviteCommand authorizeTenantAdminCreate(
      CreateAccountInviteCommand command, Scope scope) {
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
    assertTenantMatchesScope(command.tenantId(), scope);
    if (command.agencyId() != null && command.agencyIdAllocationMode() == null) {
      assertAgencyBelongsToTenant(command.agencyId(), scope.tenantId());
    }
    return withCallerTenant(command, scope);
  }

  /**
   * An invite into an existing agency must not name another Träger's agency: the accepted invite
   * would otherwise attach the new account to that agency. With an allocation mode the agency ID is
   * a fresh reservation made under the invite's (caller's) tenant, so there is nothing to look up.
   * An agency that cannot be found is refused — its tenant cannot be proven.
   */
  private void assertAgencyBelongsToTenant(Long agencyId, Long tenantId) {
    AgencyDTO agency = agencyService.getAgencyWithoutCaching(agencyId);
    if (agency == null || !tenantId.equals(agency.getTenantId())) {
      throw deny("invite into agency " + agencyId);
    }
  }

  private static CreateAccountInviteCommand withCallerTenant(
      CreateAccountInviteCommand command, Scope scope) {
    if (command.tenantId() != null || scope.tenantId() == null) {
      return command;
    }
    return new CreateAccountInviteCommand(
        command.targetRole(),
        scope.tenantId(),
        command.recipientEmail(),
        command.firstName(),
        command.lastName(),
        command.agencyId(),
        command.departmentId(),
        command.expiresInDays(),
        command.tenantIdAllocationMode(),
        command.agencyIdAllocationMode());
  }

  private Scope callerScope() {
    Long callerTenantId = boundTenantId();
    if (authenticatedUser.hasRestrictedAgencyPriviliges()) {
      Set<Long> agencyIds =
          adminAgencyRepository.findByAdminId(authenticatedUser.getUserId()).stream()
              .map(AdminAgency::getAgencyId)
              .filter(Objects::nonNull)
              .collect(Collectors.toUnmodifiableSet());
      return new Scope(Kind.AGENCY, callerTenantId, agencyIds);
    }
    if (authenticatedUser.isPlatformAdmin() || callerTenantId == null) {
      return new Scope(Kind.UNRESTRICTED, null, null);
    }
    return new Scope(Kind.TENANT, callerTenantId, null);
  }

  /** The caller's own tenant, or {@code null} for the platform (0) and single-tenant contexts. */
  /** Same kind and Träger as {@link #callerScope()}; templates never need the agency ids. */
  private Scope templateScope() {
    Long callerTenantId = boundTenantId();
    if (authenticatedUser.hasRestrictedAgencyPriviliges()) {
      return new Scope(Kind.AGENCY, callerTenantId, Set.of());
    }
    if (authenticatedUser.isPlatformAdmin()
        || isTechnicalUser()
        || authenticatedUser.getTenantId() == null) {
      return new Scope(Kind.UNRESTRICTED, null, null);
    }
    // Tenant 0 is nobody's Träger: without the platform-admin roles it reaches no template.
    if (callerTenantId == null) {
      throw denyTemplate("use invite e-mail templates from tenant 0 without platform-admin roles");
    }
    return new Scope(Kind.TENANT, callerTenantId, null);
  }

  private boolean isTechnicalUser() {
    var roles = authenticatedUser.getRoles();
    return roles != null && roles.contains(UserRole.TECHNICAL.getValue());
  }

  private Long boundTenantId() {
    Long tenantId = authenticatedUser.getTenantId();
    if (tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
      return null;
    }
    return tenantId;
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

  private enum Kind {
    UNRESTRICTED,
    TENANT,
    AGENCY
  }

  private record Scope(Kind kind, Long tenantId, Set<Long> agencyIds) {}
}
