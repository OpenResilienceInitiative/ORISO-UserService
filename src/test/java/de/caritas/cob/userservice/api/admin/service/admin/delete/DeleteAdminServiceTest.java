package de.caritas.cob.userservice.api.admin.service.admin.delete;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
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
}
