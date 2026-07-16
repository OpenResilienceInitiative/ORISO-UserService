package de.caritas.cob.userservice.api.conversation.service.user.anonymous;

import static java.lang.Integer.parseInt;
import static java.util.Collections.sort;
import static org.apache.commons.lang3.StringUtils.substringAfter;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.LinkedList;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Registry to generate, hold and handle all current anonymous usernames. */
@Component
@RequiredArgsConstructor
public class AnonymousUsernameRegistry {

  private final @NonNull UserService userService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull IdentityClient identityClient;
  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  @Value("${anonymous.username.prefix}")
  private String usernamePrefix;

  private static final LinkedList<Integer> ID_REGISTRY = new LinkedList<>();

  /**
   * Generates an unique anonymous username.
   *
   * @return encoded unique anonymous username
   */
  public synchronized String generateUniqueUsername() {

    String username;
    do {
      username = generateUsername();
      ID_REGISTRY.add(obtainUsernameId(username));
    } while (isUsernameOccupied(username));

    return usernameTranscoder.encodeUsername(username);
  }

  private String generateUsername() {
    return usernamePrefix + obtainSmallestPossibleId();
  }

  private int obtainSmallestPossibleId() {

    var smallestId = 1;
    sort(ID_REGISTRY);

    for (int i : ID_REGISTRY) {
      if (smallestId < i) {
        return smallestId;
      }
      smallestId = i + 1;
    }

    return smallestId;
  }

  private boolean isUsernameOccupied(String username) {
    // The in-memory ID_REGISTRY resets on every restart, so after a restart the generator hands
    // out low ids again. Besides the local DB we must therefore also consult Keycloak: a username
    // that still exists there (e.g. a previous anonymous user) would otherwise trigger a 409
    // "username already exists" when creating the Keycloak account during invite-link redeem.
    //
    // The anonymous username namespace is GLOBAL, not per-tenant: Matrix user IDs are global
    // (@anon_18:<server>) and the anonymous live-chat queue is deliberately cross-tenant. The DB
    // lookups below are tenant-filtered, so without the technical-context bypass a caller in
    // tenant 83 cannot see an anon user of tenant 1, hands the name out again, and Matrix rejects
    // it with M_USER_IN_USE -> 500 on every redeem (self-perpetuating: the same id is picked
    // again on each retry). Keycloak is already global and needs no bypass.
    return runCrossTenant(
            () ->
                userService.findUserByUsername(username).isPresent()
                    || consultantService.getConsultantByUsername(username).isPresent())
        || !identityClient.isUsernameAvailable(username);
  }

  /**
   * Runs a lookup in technical tenant context so {@code TenantAspect} disables the Hibernate {@code
   * tenantFilter}; the caller's tenant is restored afterwards so no other query in the same request
   * leaks across tenants.
   */
  private boolean runCrossTenant(java.util.function.BooleanSupplier lookup) {
    var callerTenant = TenantContext.getCurrentTenant();
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      return lookup.getAsBoolean();
    } finally {
      if (callerTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenant(callerTenant);
      }
    }
  }

  private int obtainUsernameId(String username) {
    return parseInt(substringAfter(username, usernamePrefix));
  }

  /**
   * Removes a name from the registry.
   *
   * @param encodedUsername the encoded username to remove from the registry
   */
  public synchronized void removeRegistryIdByUsername(String encodedUsername) {
    try {
      var decodedUsername = usernameTranscoder.decodeUsername(encodedUsername);
      Integer usernameId = obtainUsernameId(decodedUsername);
      ID_REGISTRY.remove(usernameId);
    } catch (Exception ex) {
      // do nothing
    }
  }
}
