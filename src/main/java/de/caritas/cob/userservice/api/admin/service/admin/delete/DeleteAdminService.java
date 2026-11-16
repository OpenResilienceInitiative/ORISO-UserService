package de.caritas.cob.userservice.api.admin.service.admin.delete;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.service.contactperson.ContactPersonReferences;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionAuditService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes agency and tenant admins. Admins have no safeguarding window: the deletion is executed at
 * once, and leaves an {@code EXECUTED} entry in the deletion log. The row deletion, the
 * contact-person cleanup and the audit entry share one transaction; the identity-provider call
 * cannot be rolled back.
 */
@Service
@RequiredArgsConstructor
public class DeleteAdminService {

  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull IdentityAccountRemover identityAccountRemover;
  private final @NonNull DeletionAuditService deletionAuditService;
  private final @NonNull ContactPersonReferences contactPersonReferences;

  @Transactional
  public void deleteAgencyAdmin(String adminId) {
    var tenantId = tenantIdOf(adminId);
    this.adminAgencyRepository.deleteByAdminId(adminId);
    this.identityAccountRemover.deleteUser(adminId);
    this.adminRepository.deleteById(adminId);
    this.contactPersonReferences.forgetAdmin(adminId);
    logExecuted(DeletionAuditTargetType.AGENCY_ADMIN, adminId, tenantId);
  }

  @Transactional
  public void deleteTenantAdmin(String adminId) {
    var tenantId = tenantIdOf(adminId);
    this.identityAccountRemover.deleteUser(adminId);
    this.adminRepository.deleteById(adminId);
    this.contactPersonReferences.forgetAdmin(adminId);
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
