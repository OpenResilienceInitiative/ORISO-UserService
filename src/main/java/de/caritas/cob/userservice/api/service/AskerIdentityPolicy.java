package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.Objects;

/** Account collection and session alias rules are distinct from notification channel rules. */
public final class AskerIdentityPolicy {
  private AskerIdentityPolicy() {}

  public static boolean customNamesAllowed(TenantService tenants, Long tenantId) {
    return !Boolean.FALSE.equals(
        currentSettings(tenants, tenantId).getFeatureDisplayNameEditable());
  }

  public static boolean customNamesAllowed(
      TenantService tenants, Long tenantId, ConversationType type) {
    Settings settings = currentSettings(tenants, tenantId);
    if (Boolean.FALSE.equals(settings.getFeatureDisplayNameEditable())) return false;
    return switch (type) {
      case AGENCY_COUNSELLING ->
          !Boolean.FALSE.equals(settings.getFeatureDisplayNameEditableAgencyCounsellingEnabled());
      case LIVE_CHAT ->
          !Boolean.FALSE.equals(settings.getFeatureDisplayNameEditableLiveChatEnabled());
      case SELF_HELP ->
          !Boolean.FALSE.equals(settings.getFeatureDisplayNameEditableSelfHelpEnabled());
      case INTERNAL_GROUP -> false;
    };
  }

  public static void requireEmailCollectionAllowed(TenantService tenants, Long tenantId) {
    if (!emailCollectionAllowed(tenants, tenantId)) {
      throw new ForbiddenException("The tenant does not allow collecting an asker email address");
    }
  }

  public static boolean emailCollectionAllowed(TenantService tenants, Long tenantId) {
    return !Boolean.FALSE.equals(currentSettings(tenants, tenantId).getFeatureAskerEmailEnabled());
  }

  static Settings currentSettings(TenantService tenants, Long tenantId) {
    return currentTenant(tenants, tenantId).getSettings();
  }

  /** One fresh, identity-validated projection for callers that also need its resolved tenant ID. */
  static RestrictedTenantDTO currentTenant(TenantService tenants, Long tenantId) {
    try {
      if ((tenantId == null && tenants.isMultitenancyEnabled())
          || (tenantId != null && tenantId <= 0)) {
        throw new IllegalStateException("Concrete tenant required");
      }
      var tenant =
          tenantId == null
              ? tenants.getSingleTenancyTenantDataFresh()
              : tenants.getRestrictedTenantDataFresh(tenantId);
      if (tenant == null
          || tenant.getId() == null
          || tenant.getId() <= 0
          || (tenantId != null && !Objects.equals(tenantId, tenant.getId()))
          || tenant.getSettings() == null) {
        throw new IllegalStateException("Tenant identity settings unavailable");
      }
      return tenant;
    } catch (RuntimeException unavailable) {
      // Do not relay upstream bodies, credentials, or stale policy snapshots to callers/logs.
      throw new ServiceUnavailableException("Current tenant identity settings are unavailable");
    }
  }
}
