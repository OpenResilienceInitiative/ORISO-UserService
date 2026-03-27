package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves tenant-scoped admin recipients for inactivity alerts. */
@Service
@RequiredArgsConstructor
public class InactiveAccountNotificationRecipientResolver {

  private final @NonNull AdminRepository adminRepository;

  public List<Admin> resolveRecipients(Long tenantId) {
    List<Admin> recipients = new ArrayList<>();
    if (tenantId != null) {
      recipients.addAll(adminRepository.findByTenantIdAndType(tenantId, Admin.AdminType.TENANT));
    }
    recipients.addAll(adminRepository.findByType(Admin.AdminType.SUPER));
    return distinctById(recipients);
  }

  private List<Admin> distinctById(List<Admin> admins) {
    Map<String, Admin> byId = new LinkedHashMap<>();
    for (Admin admin : admins) {
      if (admin != null && admin.getId() != null) {
        byId.put(admin.getId(), admin);
      }
    }
    return new ArrayList<>(byId.values());
  }
}
