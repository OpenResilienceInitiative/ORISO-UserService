package de.caritas.cob.userservice.api.admin.service.asker;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The technical {@code group-chat-system-<tenantId>} users that own a Träger's group-chat sessions
 * (see {@code CreateChatFacade}). They sit in the advice-seeker table but are no person: deleting
 * one while its Träger exists would make the nightly job delete every group chat of that Träger. So
 * one may only go on its own once its Träger is gone; otherwise it goes with the Träger
 * (ORISO-Admin#1521, #1525).
 */
@Component
@RequiredArgsConstructor
public class GroupChatSystemAccounts {

  static final String PREFIX = "group-chat-system";

  private final @NonNull TenantService tenantService;

  public static boolean isTechnical(String userId) {
    return userId != null && userId.startsWith(PREFIX);
  }

  /**
   * @return whether an admin may mark this account for deletion now; always true for a person
   * @throws RuntimeException when the TenantService cannot be asked (callers fail closed)
   */
  public boolean mayDelete(String userId, Long tenantId) {
    if (!isTechnical(userId)) {
      return true;
    }
    if (isPlatformTenant(tenantId)) {
      return false;
    }
    return !tenantsStillPresent(Set.of(tenantId)).contains(tenantId);
  }

  /** Which of the given Träger still exist; the platform's own counts as always present. */
  public Set<Long> tenantsStillPresent(Collection<Long> tenantIds) {
    var concrete =
        tenantIds.stream()
            .filter(id -> !isPlatformTenant(id))
            .collect(Collectors.toUnmodifiableSet());
    if (concrete.isEmpty()) {
      return Set.of();
    }
    return tenantService.getRestrictedTenantData(concrete).stream()
        .filter(Objects::nonNull)
        .map(RestrictedTenantDTO::getId)
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** No Träger (single-tenant) or the technical tenant 0: the account belongs to the platform. */
  static boolean isPlatformTenant(Long tenantId) {
    return tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId);
  }
}
