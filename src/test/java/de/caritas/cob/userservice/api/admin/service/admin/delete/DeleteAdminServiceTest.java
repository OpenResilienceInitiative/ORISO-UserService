package de.caritas.cob.userservice.api.admin.service.admin.delete;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionAuditService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DeleteAdminServiceTest {

  private final String VALID_ADMIN_ID = "164be67d-4d1b-4d80-bb6b-0ee057a1c59e";

  @InjectMocks private DeleteAdminService deleteAdminService;
  @Mock private AdminRepository adminRepository;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private IdentityAccountRemover identityAccountRemover;
  @Mock private DeletionAuditService deletionAuditService;

  @Test
  public void deleteAgencyAdmin_Should_deleteAdminAndRelation_When_adminIdIsProvided() {
    // given
    // when
    deleteAdminService.deleteAgencyAdmin(VALID_ADMIN_ID);

    // then
    verify(adminAgencyRepository).deleteByAdminId(VALID_ADMIN_ID);
    verify(identityAccountRemover).deleteUser(VALID_ADMIN_ID);
    verify(adminRepository).deleteById(VALID_ADMIN_ID);
  }

  @Test
  void deleteAgencyAdmin_Should_LeaveAnExecutedDeletionLogEntry_AfterTheAdminIsGone() {
    when(adminRepository.findById(VALID_ADMIN_ID))
        .thenReturn(Optional.of(adminOfTenant(7L, Admin.AdminType.AGENCY)));

    deleteAdminService.deleteAgencyAdmin(VALID_ADMIN_ID);

    var order = inOrder(adminRepository, deletionAuditService);
    order.verify(adminRepository).deleteById(VALID_ADMIN_ID);
    order
        .verify(deletionAuditService)
        .recordAsCurrentActor(
            DeletionAuditTargetType.AGENCY_ADMIN,
            VALID_ADMIN_ID,
            7L,
            DeletionAuditOutcome.EXECUTED);
  }

  @Test
  void deleteTenantAdmin_Should_LeaveAnExecutedDeletionLogEntry_AfterTheAdminIsGone() {
    when(adminRepository.findById(VALID_ADMIN_ID))
        .thenReturn(Optional.of(adminOfTenant(3L, Admin.AdminType.TENANT)));

    deleteAdminService.deleteTenantAdmin(VALID_ADMIN_ID);

    var order = inOrder(adminRepository, deletionAuditService);
    order.verify(adminRepository).deleteById(VALID_ADMIN_ID);
    order
        .verify(deletionAuditService)
        .recordAsCurrentActor(
            DeletionAuditTargetType.TENANT_ADMIN,
            VALID_ADMIN_ID,
            3L,
            DeletionAuditOutcome.EXECUTED);
  }

  @Test
  void deleteTenantAdmin_Should_StillLogTheDeletion_When_TheAdminRowIsAlreadyUnknown() {
    deleteAdminService.deleteTenantAdmin(VALID_ADMIN_ID);

    verify(deletionAuditService)
        .recordAsCurrentActor(
            DeletionAuditTargetType.TENANT_ADMIN,
            VALID_ADMIN_ID,
            null,
            DeletionAuditOutcome.EXECUTED);
  }

  @Test
  void deleteAgencyAdmin_Should_NotLog_When_TheIdentityRemovalFails() {
    org.mockito.Mockito.doThrow(new IllegalStateException("keycloak"))
        .when(identityAccountRemover)
        .deleteUser(VALID_ADMIN_ID);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> deleteAdminService.deleteAgencyAdmin(VALID_ADMIN_ID));

    verify(deletionAuditService, never()).recordAsCurrentActor(any(), any(), any(), any());
  }

  private static Admin adminOfTenant(Long tenantId, Admin.AdminType type) {
    var admin = new Admin();
    admin.setTenantId(tenantId);
    admin.setType(type);
    return admin;
  }
}
