package de.caritas.cob.userservice.api.model;

/**
 * The one tenant filter, auto-enabled in every Hibernate session (declared in {@code
 * package-info.java}); its argument comes from {@code TenantFilterParameterResolver}.
 */
public final class TenantFilter {

  public static final String NAME = "tenantFilter";
  public static final String PARAMETER = "tenantId";

  /** Tenant 0 is the technical tenant and sees every row. */
  public static final String CONDITION = "(:tenantId = 0 OR tenant_id = :tenantId)";

  /** Tenant 1 also owns the legacy rows written before tenants existed. */
  public static final String CONDITION_WITH_LEGACY_ROWS_OF_TENANT_ONE =
      "(:tenantId = 0 OR tenant_id = :tenantId OR (:tenantId = 1 AND tenant_id IS NULL))";

  private TenantFilter() {}
}
