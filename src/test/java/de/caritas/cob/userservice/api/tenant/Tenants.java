package de.caritas.cob.userservice.api.tenant;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Puts a test into production's tenant model. With the tenant filter on, a thread without a tenant
 * sees no row and writes rows without one, so a test must say which Träger it acts in, seeds in and
 * reads in. A request clears the tenant of the calling thread, as in production: read back with
 * {@link #acrossAll} or {@link #in}.
 */
public final class Tenants {

  // Tests run one at a time; a request may resolve the tenant on another thread than the test's.
  private static volatile Long acting;

  private Tenants() {}

  /**
   * From now on the test acts as a caller of this Träger: its thread runs in the tenant, and its
   * requests resolve to it.
   */
  public static void actIn(long tenantId) {
    acting = tenantId;
    TenantContext.setCurrentTenant(tenantId);
  }

  /** Acts as this caller: the authenticated user of every request, in its own Träger. */
  public static void actAs(
      AuthenticatedUser caller, String userId, long tenantId, UserRole... roles) {
    caller.setUserId(userId);
    caller.setUsername(userId);
    caller.setTenantId(tenantId);
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
    caller.setGrantedAuthorities(Set.of());
    actIn(tenantId);
  }

  /** The Träger the test acts in, {@code null} before it says. */
  public static Long acting() {
    return acting;
  }

  /** Runs {@code work} in this Träger, e.g. to seed or read another one, then returns. */
  public static <T> T in(long tenantId, Supplier<T> work) {
    var result = new java.util.concurrent.atomic.AtomicReference<T>();
    TenantContext.runWith(new TenantData(tenantId, null), () -> result.set(work.get()));
    return result.get();
  }

  public static void in(long tenantId, Runnable work) {
    TenantContext.runWith(new TenantData(tenantId, null), work);
  }

  /** Runs {@code work} in the technical tenant, which sees the rows of every Träger. */
  public static <T> T acrossAll(Supplier<T> work) {
    return in(TenantContext.TECHNICAL_TENANT_ID, work);
  }

  public static void acrossAll(Runnable work) {
    in(TenantContext.TECHNICAL_TENANT_ID, work);
  }

  static void reset() {
    acting = null;
    TenantContext.clear();
  }
}
