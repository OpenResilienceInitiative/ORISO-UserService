package de.caritas.cob.userservice.api.admin.service.admin;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Ownership rule for the admin-creating endpoints (<code>POST /useradmin/tenantadmins</code> and
 * <code>POST /useradmin/agencyadmins</code>).
 *
 * <p>Both endpoints take the target tenant from the request body. Role checks alone do not bound
 * that value: every tenant admin holds {@code tenant-admin}, so a role-only guard such as {@link
 * AuthenticatedUser#isTenantSuperAdmin()} lets a tenant-scoped caller attribute a new admin account
 * to a tenant they do not own. Nothing downstream repairs it — the Hibernate tenant filter is
 * read-only, the tenant interceptor only backfills a missing tenant id, and there is no constraint
 * on {@code admin.tenant_id}. This is the write-side counterpart of the read-side scoping in {@code
 * AgencyAdminUserService.findScopedAgencyAdminsByInfix}.
 *
 * <p>The caller's own tenant is taken from the authenticated principal, i.e. the {@code tenantId}
 * claim of the Keycloak access token. With multitenancy enabled that claim is also what {@code
 * TenantResolverService} resolves the request context from, so a tenant-scoped caller can neither
 * omit nor influence it.
 *
 * <p>The check bounds callers that <em>belong to</em> a tenant. It deliberately does not turn the
 * platform context (caller tenant {@code 0}) into a tenant-bound caller: tenant 0 is the technical
 * tenant nobody belongs to, and provisioning a tenant's admins from it is established, test-covered
 * behaviour ({@code CreateAdminServiceIT}, {@code UserAdminControllerE2EIT}). Narrowing that
 * exemption further — so that a tenant-0 principal without the full platform-admin role combination
 * is also bounded — is a separate, behaviour-changing decision that would have to update those
 * tests; it is not needed to close the tenant-scoped bypass this class exists for.
 */
@Slf4j
public final class AdminTenantOwnershipValidator {

  static final String CROSS_TENANT_MESSAGE =
      "Admin accounts can only be created for the tenant of the calling admin";

  private AdminTenantOwnershipValidator() {}

  /**
   * Rejects a create-admin request that attributes the new admin to a tenant the caller does not
   * own.
   *
   * @param authenticatedUser the calling admin
   * @param requestedTenantId the tenant id taken from the create-admin request body
   * @throws ForbiddenException if the caller is bound to a tenant and asks for a different one
   */
  public static void assertCallerMayCreateAdminForTenant(
      AuthenticatedUser authenticatedUser, Integer requestedTenantId) {
    if (requestedTenantId == null) {
      // Whether a tenant id is required at all is decided per endpoint; there is no tenant to own.
      return;
    }
    if (authenticatedUser.isPlatformAdmin()) {
      // Platform admins legitimately administer every tenant, including the technical tenant 0.
      return;
    }
    Long callerTenantId = authenticatedUser.getTenantId();
    if (callerTenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(callerTenantId)) {
      // No tenant-bound caller, so there is no tenant boundary to cross:
      // - null: a single-tenant deployment. With multitenancy enabled the tenant resolver
      //   rejects a request whose principal carries no resolvable tenant, so a tenant-scoped
      //   caller never reaches this branch.
      // - 0: the platform/technical context. Tenant 0 is not a tenant one can belong to; the
      //   whole codebase treats it as "no concrete tenant" (TenantContext#TECHNICAL_TENANT_ID,
      //   HttpTenantFilter#resolveSubdomain), and provisioning a tenant's first admin from that
      //   context is an established, test-covered operation.
      return;
    }
    if (!callerTenantId.equals(Long.valueOf(requestedTenantId))) {
      log.warn(
          "Admin {} of tenant {} attempted to create an admin account for tenant {}",
          authenticatedUser.getUserId(),
          callerTenantId,
          requestedTenantId);
      throw new ForbiddenException(CROSS_TENANT_MESSAGE);
    }
  }
}
