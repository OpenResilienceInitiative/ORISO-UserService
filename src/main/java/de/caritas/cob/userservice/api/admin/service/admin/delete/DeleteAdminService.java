package de.caritas.cob.userservice.api.admin.service.admin.delete;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionAuditService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Deletes agency and tenant admins. Admins have no safeguarding window: the deletion is executed at
 * once, and leaves an {@code EXECUTED} entry in the deletion log.
 */
@Service
@RequiredArgsConstructor
public class DeleteAdminService {

  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull IdentityAccountRemover identityAccountRemover;
  private final @NonNull DeletionAuditService deletionAuditService;

  public void deleteAgencyAdmin(String adminId) {
    var tenantId = tenantIdOf(adminId);
    this.adminAgencyRepository.deleteByAdminId(adminId);
    this.identityAccountRemover.deleteUser(adminId);
    this.adminRepository.deleteById(adminId);
    logExecuted(DeletionAuditTargetType.AGENCY_ADMIN, adminId, tenantId);
  }

  public void deleteTenantAdmin(String adminId) {
    var tenantId = tenantIdOf(adminId);
    this.identityAccountRemover.deleteUser(adminId);
    this.adminRepository.deleteById(adminId);
    logExecuted(DeletionAuditTargetType.TENANT_ADMIN, adminId, tenantId);
  }

  /** Read before the row is gone; only the tenant id is kept, no personal data. */
  private Long tenantIdOf(String adminId) {
    return this.adminRepository.findById(adminId).map(Admin::getTenantId).orElse(null);
  }

  private void logExecuted(DeletionAuditTargetType type, String adminId, Long tenantId) {
    this.deletionAuditService.recordAsCurrentActor(
        type, adminId, tenantId, DeletionAuditOutcome.EXECUTED);
  }
}
