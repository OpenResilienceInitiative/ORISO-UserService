package de.caritas.cob.userservice.api.tenant;

/** Holds the tenant_id variable for ongoing thread assigned for HTTP request. */
public class TenantContext {

  public static final Long TECHNICAL_TENANT_ID = 0L;

  private TenantContext() {}

  private static final ThreadLocal<TenantData> CURRENT_TENANT_DATA = new ThreadLocal<>();

  public static Long getCurrentTenant() {
    return CURRENT_TENANT_DATA.get() != null ? CURRENT_TENANT_DATA.get().getTenantId() : null;
  }

  public static TenantData getCurrentTenantData() {
    return CURRENT_TENANT_DATA.get();
  }

  public static void setCurrentTenantData(TenantData tenantData) {
    CURRENT_TENANT_DATA.set(tenantData);
  }

  public static void setCurrentTenant(Long tenantId) {
    initializeCurrentTenantDataIfNotExist();
    CURRENT_TENANT_DATA.get().setTenantId(tenantId);
  }

  public static void setCurrentSubdomain(String subdomain) {
    initializeCurrentTenantDataIfNotExist();
    CURRENT_TENANT_DATA.get().setSubdomain(subdomain);
  }

  private static void initializeCurrentTenantDataIfNotExist() {
    if (CURRENT_TENANT_DATA.get() == null) {
      CURRENT_TENANT_DATA.set(new TenantData());
    }
  }

  public static void clear() {
    CURRENT_TENANT_DATA.remove();
  }

  public static boolean contextIsSet() {
    return getCurrentTenant() != null;
  }

  public static boolean isTechnicalOrSuperAdminContext() {
    return TECHNICAL_TENANT_ID.equals(getCurrentTenant());
  }

  /**
   * Runs {@code lookup} in the technical tenant context, so neither the tenant filter nor the
   * tenant check on loads by id hides rows of other tenants, and restores the caller's context
   * afterwards. Only for scope checks that must <i>see</i> a foreign row in order to refuse it.
   */
  public static <T> T supplyAcrossTenants(java.util.function.Supplier<T> lookup) {
    var callerTenantData = CURRENT_TENANT_DATA.get();
    CURRENT_TENANT_DATA.set(
        new TenantData(
            TECHNICAL_TENANT_ID,
            callerTenantData == null ? null : callerTenantData.getSubdomain()));
    try {
      return lookup.get();
    } finally {
      if (callerTenantData == null) {
        CURRENT_TENANT_DATA.remove();
      } else {
        CURRENT_TENANT_DATA.set(callerTenantData);
      }
    }
  }
}
