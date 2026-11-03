package de.caritas.cob.userservice.api.admin.service.admin.delete;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
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
  @Mock private org.springframework.jdbc.core.JdbcTemplate legacyIdentityRows;
  @Mock private de.caritas.cob.userservice.api.helper.AuthenticatedUser legacyRemovalHuman;

  @org.junit.jupiter.api.BeforeEach
  void installActualLegacyReadDependencies() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        deleteAdminService, "legacyIdentityRows", legacyIdentityRows);
    org.springframework.test.util.ReflectionTestUtils.setField(
        deleteAdminService, "legacyRemovalHuman", legacyRemovalHuman);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void legacyCombinedAdministratorRemovalRefusesBeforeAnyEffect(boolean tenant) {
    org.mockito.Mockito.when(
            legacyIdentityRows.queryForList(
                "select consultant_id from consultant where consultant_id=?", VALID_ADMIN_ID))
        .thenReturn(java.util.List.of(java.util.Map.of("consultant_id", VALID_ADMIN_ID)));
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> {
          if (tenant) deleteAdminService.deleteTenantAdmin(VALID_ADMIN_ID);
          else deleteAdminService.deleteAgencyAdmin(VALID_ADMIN_ID);
        });
    verifyNoInteractions(identityAccountRemover, adminAgencyRepository, adminRepository);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void legacySelfAdministratorRemovalRequiresAnotherAdministrator(boolean tenant) {
    org.mockito.Mockito.when(legacyRemovalHuman.getUserId()).thenReturn(VALID_ADMIN_ID);
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> {
          if (tenant) deleteAdminService.deleteTenantAdmin(VALID_ADMIN_ID);
          else deleteAdminService.deleteAgencyAdmin(VALID_ADMIN_ID);
        });
    verifyNoInteractions(
        identityAccountRemover, adminAgencyRepository, adminRepository, legacyIdentityRows);
  }

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
  public void deleteAgencyAdmin_Should_keepSqlOwnership_When_scopedNativeDeletionIsRequired() {
    var refusal = new ScopedIdentityCommandRequiredException();
    doThrow(refusal).when(identityAccountRemover).deleteUser(VALID_ADMIN_ID);

    var actual =
        assertThrows(
            ScopedIdentityCommandRequiredException.class,
            () -> deleteAdminService.deleteAgencyAdmin(VALID_ADMIN_ID));

    assertSame(refusal, actual);
    verifyNoInteractions(adminAgencyRepository, adminRepository);
  }

  @Test
  public void deleteTenantAdmin_Should_keepSqlOwnership_When_scopedNativeDeletionIsRequired() {
    var refusal = new ScopedIdentityCommandRequiredException();
    doThrow(refusal).when(identityAccountRemover).deleteUser(VALID_ADMIN_ID);

    var actual =
        assertThrows(
            ScopedIdentityCommandRequiredException.class,
            () -> deleteAdminService.deleteTenantAdmin(VALID_ADMIN_ID));

    assertSame(refusal, actual);
    verifyNoInteractions(adminAgencyRepository, adminRepository);
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

  /**
   * Order pinned after the dev/PR merge: identity first, then relations, then the row, then the
   * log.
   */
  @Test
  void deleteAgencyAdmin_Should_RemoveIdentityThenRelationsThenRowThenLog() {
    when(adminRepository.findById(VALID_ADMIN_ID))
        .thenReturn(Optional.of(adminOfTenant(7L, Admin.AdminType.AGENCY)));

    deleteAdminService.deleteAgencyAdmin(VALID_ADMIN_ID);

    var order =
        inOrder(
            identityAccountRemover, adminAgencyRepository, adminRepository, deletionAuditService);
    order.verify(identityAccountRemover).deleteUser(VALID_ADMIN_ID);
    order.verify(adminAgencyRepository).deleteByAdminId(VALID_ADMIN_ID);
    order.verify(adminRepository).deleteById(VALID_ADMIN_ID);
    order
        .verify(deletionAuditService)
        .recordAsCurrentActor(
            DeletionAuditTargetType.AGENCY_ADMIN,
            VALID_ADMIN_ID,
            7L,
            DeletionAuditOutcome.EXECUTED);
    verify(adminAgencyRepository, org.mockito.Mockito.times(1)).deleteByAdminId(VALID_ADMIN_ID);
  }

  @Test
  void deleteTenantAdmin_Should_RemoveIdentityThenRowThenLog_AndNeverTouchAgencyRelations() {
    when(adminRepository.findById(VALID_ADMIN_ID))
        .thenReturn(Optional.of(adminOfTenant(3L, Admin.AdminType.TENANT)));

    deleteAdminService.deleteTenantAdmin(VALID_ADMIN_ID);

    var order = inOrder(identityAccountRemover, adminRepository, deletionAuditService);
    order.verify(identityAccountRemover).deleteUser(VALID_ADMIN_ID);
    order.verify(adminRepository).deleteById(VALID_ADMIN_ID);
    order
        .verify(deletionAuditService)
        .recordAsCurrentActor(
            DeletionAuditTargetType.TENANT_ADMIN,
            VALID_ADMIN_ID,
            3L,
            DeletionAuditOutcome.EXECUTED);
    verifyNoInteractions(adminAgencyRepository);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void scopedAdministratorRemoval_Should_LeaveAnExecutedLogEntry_LikeTheLegacyPath(boolean tenant)
      throws Exception {
    var mode =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    var maintenance =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance.class);
    when(maintenance.isFiveActorMode()).thenReturn(true);
    var entities = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
    when(entities.isJoinedToTransaction()).thenReturn(true);
    deleteAdminService.setScopedMaintenance(mode, maintenance);
    org.springframework.test.util.ReflectionTestUtils.setField(
        deleteAdminService, "entities", entities);
    var type = tenant ? Admin.AdminType.TENANT : Admin.AdminType.AGENCY;
    when(adminRepository.findById(VALID_ADMIN_ID)).thenReturn(Optional.of(adminOfTenant(9L, type)));
    org.mockito.Mockito.doAnswer(
            call -> {
              DeleteAdminService.ScopedRemoval removal = call.getArgument(0);
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .bindResource(removal, removal);
              try {
                removal.apply();
              } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .unbindResource(removal);
              }
              return null;
            })
        .when(maintenance)
        .removeAdministrator(any());

    if (tenant) deleteAdminService.deleteTenantAdmin(VALID_ADMIN_ID);
    else deleteAdminService.deleteAgencyAdmin(VALID_ADMIN_ID);

    // The native command owns the identity: the legacy remover must stay untouched.
    verifyNoInteractions(identityAccountRemover);
    verify(adminRepository).delete(any(Admin.class));
    verify(deletionAuditService)
        .recordAsCurrentActor(
            tenant ? DeletionAuditTargetType.TENANT_ADMIN : DeletionAuditTargetType.AGENCY_ADMIN,
            VALID_ADMIN_ID,
            9L,
            DeletionAuditOutcome.EXECUTED);
  }

  private static Admin adminOfTenant(Long tenantId, Admin.AdminType type) {
    var admin = new Admin();
    admin.setTenantId(tenantId);
    admin.setType(type);
    return admin;
  }
}
