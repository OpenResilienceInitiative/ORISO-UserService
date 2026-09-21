package de.caritas.cob.userservice.api.model;

/**
 * The one Hibernate tenant filter of this service.
 *
 * <p>It is declared once, in {@code package-info.java} of this package, as <b>auto-enabled</b>:
 * every Hibernate session has it switched on from the moment it is opened, whether or not a
 * transaction is running. Its only parameter, {@code tenantId}, is not set by anybody; Hibernate
 * asks {@link de.caritas.cob.userservice.api.tenant.TenantFilterParameterResolver} for it each time
 * a query is rendered, so the value always follows the current {@code TenantContext}.
 *
 * <p>Before this, an aspect switched the filter on for "the current session" before each repository
 * call. Outside a transaction that session is a throw-away one, so the query ran in another session
 * without the filter, and a Träger admin saw rows of every Träger.
 *
 * <p>Parameter {@code 0} lifts the restriction: the platform admin and technical tenant, and every
 * context the resolver treats as unrestricted.
 */
public final class TenantFilter {

  public static final String NAME = "tenantFilter";
  public static final String PARAMETER = "tenantId";

  /** Rows of the current tenant only. */
  public static final String CONDITION = "(:tenantId = 0 OR tenant_id = :tenantId)";

  /** Rows of the current tenant; tenant 1 also owns legacy rows written before tenants existed. */
  public static final String CONDITION_WITH_LEGACY_ROWS_OF_TENANT_ONE =
      "(:tenantId = 0 OR tenant_id = :tenantId OR (:tenantId = 1 AND tenant_id IS NULL))";

  private TenantFilter() {}
}
