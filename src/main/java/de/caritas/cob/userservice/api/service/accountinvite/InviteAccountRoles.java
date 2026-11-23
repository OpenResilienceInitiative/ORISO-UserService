package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The roles the account behind each accepted invite holds now, so the Admin's role chip shows "+
 * BST-Admin" after a reload too. One query per table for a whole page.
 */
@Component
@RequiredArgsConstructor
public class InviteAccountRoles {

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull AdminRepository adminRepository;

  /** By invite id; invites without an account are left out. */
  public Map<Long, List<AccountInviteTargetRole>> of(Collection<AccountInvite> invites) {
    Map<Long, String> accountOf = new HashMap<>();
    for (AccountInvite invite : invites) {
      String account = accountId(invite);
      if (invite.getId() != null && account != null) {
        accountOf.put(invite.getId(), account);
      }
    }
    if (accountOf.isEmpty()) {
      return Map.of();
    }
    Set<String> ids = new HashSet<>(accountOf.values());
    // The invites are already scoped to the caller; their accounts may sit in another tenant.
    Set<String> counsellors =
        TenantContext.supplyAcrossTenants(() -> consultantRepository.findAllById(ids)).stream()
            .filter(consultant -> consultant.getDeleteDate() == null)
            .map(Consultant::getId)
            .collect(Collectors.toSet());
    Map<String, Admin.AdminType> adminTypes =
        TenantContext.supplyAcrossTenants(() -> adminRepository.findAllByIdIn(ids)).stream()
            .filter(admin -> admin.getType() != null)
            .collect(Collectors.toMap(Admin::getId, Admin::getType, (first, second) -> first));
    Map<Long, List<AccountInviteTargetRole>> roles = new HashMap<>();
    accountOf.forEach(
        (inviteId, account) -> {
          Set<AccountInviteTargetRole> held = EnumSet.noneOf(AccountInviteTargetRole.class);
          Admin.AdminType type = adminTypes.get(account);
          if (type == Admin.AdminType.TENANT) {
            held.add(AccountInviteTargetRole.TENANT_ADMIN);
          }
          if (type == Admin.AdminType.AGENCY) {
            held.add(AccountInviteTargetRole.AGENCY_ADMIN);
          }
          if (counsellors.contains(account)) {
            held.add(AccountInviteTargetRole.COUNSELLOR);
          }
          roles.put(inviteId, List.copyOf(held));
        });
    return roles;
  }

  private static String accountId(AccountInvite invite) {
    if (invite.getStatus() != AccountInviteStatus.ACCEPTED) {
      return null;
    }
    return invite.getProvisionedUserId() != null
        ? invite.getProvisionedUserId()
        : invite.getAcceptedByUserId();
  }
}
