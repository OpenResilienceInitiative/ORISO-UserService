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
   * Runs {@code lookup} in the technical tenant, so tenant rows of every Träger are visible, and
   * restores the caller's context exactly. Only for reads that must cross Träger on purpose.
   */
  public static <T> T supplyAcrossTenants(java.util.function.Supplier<T> lookup) {
    var callerTenantData = CURRENT_TENANT_DATA.get();
    var technical =
        new TenantData(
            TECHNICAL_TENANT_ID, callerTenantData == null ? null : callerTenantData.getSubdomain());
    var result = new java.util.concurrent.atomic.AtomicReference<T>();
    runWith(technical, () -> result.set(lookup.get()));
    return result.get();
  }

  /** Runs {@code task} in {@code tenantId} and restores the previous context. */
  public static void runIn(Long tenantId, Runnable task) {
    runWith(new TenantData(tenantId, null), task);
  }

  /** Runs {@code task} with {@code tenantData} (none if null) and restores the previous context. */
  static void runWith(TenantData tenantData, Runnable task) {
    var previous = CURRENT_TENANT_DATA.get();
    CURRENT_TENANT_DATA.set(tenantData);
    try {
      task.run();
    } finally {
      if (previous == null) {
        CURRENT_TENANT_DATA.remove();
      } else {
        CURRENT_TENANT_DATA.set(previous);
      }
    }
  }
}
