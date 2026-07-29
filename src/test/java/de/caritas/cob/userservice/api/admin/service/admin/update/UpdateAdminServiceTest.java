package de.caritas.cob.userservice.api.admin.service.admin.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateAdminServiceTest {

  @Mock private IdentityProfileUpdater identityProfileUpdater;
  @Mock private UserAccountInputValidator userAccountInputValidator;
  @Mock private AdminRepository adminRepository;
  @Mock private RetrieveAdminService retrieveAdminService;
  @Captor private ArgumentCaptor<IdentityProfileUpdate> profileUpdateCaptor;

  private UpdateAdminService updateAdminService;

  @BeforeEach
  void setUp() {
    updateAdminService =
        new UpdateAdminService(
            identityProfileUpdater,
            userAccountInputValidator,
            adminRepository,
            retrieveAdminService);
  }

  @Test
  void updateAgencyAdmin_Should_notUpdateAdmin_When_adminEntityHasTenantIdEqualZero() {
    // given
    Admin admin = mock(Admin.class);
    when(admin.getTenantId()).thenReturn(0L);
    when(retrieveAdminService.findAdmin(anyString(), eq(AdminType.AGENCY))).thenReturn(admin);

    // when, then
    Exception exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> updateAdminService.updateAgencyAdmin("adminId", null));

    assertEquals("Admin has tenant id 0", exception.getMessage());
    verify(identityProfileUpdater, never()).updateProfile(any(), any());
    verify(adminRepository, never()).save(any());
  }

  @Test
  void updateAgencyAdmin_Should_updateAdmin_When_adminEntityHasTenantIdNull() {
    // given
    UpdateAgencyAdminDTO updateAgencyAdminDTO = mock(UpdateAgencyAdminDTO.class);
    when(updateAgencyAdminDTO.getEmail()).thenReturn("mail@example.com");
    Admin admin = mock(Admin.class);
    when(admin.getId()).thenReturn("identity-id");
    when(admin.getTenantId()).thenReturn(null);
    when(retrieveAdminService.findAdmin(anyString(), eq(AdminType.AGENCY))).thenReturn(admin);

    // when
    updateAdminService.updateAgencyAdmin("adminId", updateAgencyAdminDTO);

    // then
    verify(identityProfileUpdater).updateProfile(eq("identity-id"), profileUpdateCaptor.capture());
    assertNull(profileUpdateCaptor.getValue().tenantId());
    assertEquals("mail@example.com", profileUpdateCaptor.getValue().email());
    verify(adminRepository).save(admin);
  }

  @Test
  void updateAgencyAdmin_Should_updateAdmin_When_adminEntityHasTenantDifferentFromZero() {
    // given
    UpdateAgencyAdminDTO updateAgencyAdminDTO = mock(UpdateAgencyAdminDTO.class);
    when(updateAgencyAdminDTO.getEmail()).thenReturn("mail@example.com");
    Admin admin = mock(Admin.class);
    when(admin.getId()).thenReturn("identity-id");
    when(admin.getTenantId()).thenReturn(2L);
    when(retrieveAdminService.findAdmin(anyString(), eq(AdminType.AGENCY))).thenReturn(admin);

    // when
    updateAdminService.updateAgencyAdmin("adminId", updateAgencyAdminDTO);

    // then
    verify(identityProfileUpdater).updateProfile(eq("identity-id"), profileUpdateCaptor.capture());
    assertEquals(2, profileUpdateCaptor.getValue().tenantId());
    assertEquals("mail@example.com", profileUpdateCaptor.getValue().email());
    verify(adminRepository).save(admin);
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-06
  // ---------------------------------------------------------------------------

  @Test
  void updateTenantAdmin_Should_updateAdmin_When_validDataIsGiven() {
    // given
    UpdateTenantAdminDTO updateTenantAdminDTO = mock(UpdateTenantAdminDTO.class);
    when(updateTenantAdminDTO.getTenantId()).thenReturn(5);
    when(updateTenantAdminDTO.getFirstname()).thenReturn("Firstname");
    when(updateTenantAdminDTO.getLastname()).thenReturn("Lastname");
    when(updateTenantAdminDTO.getEmail()).thenReturn("mail@example.com");
    Admin admin = mock(Admin.class);
    when(admin.getId()).thenReturn("identity-id");
    when(retrieveAdminService.findAdmin(anyString(), eq(AdminType.TENANT))).thenReturn(admin);

    // when
    updateAdminService.updateTenantAdmin("adminId", updateTenantAdminDTO);

    // then
    verify(identityProfileUpdater).updateProfile(eq("identity-id"), profileUpdateCaptor.capture());
    assertEquals(5L, profileUpdateCaptor.getValue().tenantId());
    assertEquals("Firstname", profileUpdateCaptor.getValue().firstName());
    assertEquals("Lastname", profileUpdateCaptor.getValue().lastName());
    assertEquals("mail@example.com", profileUpdateCaptor.getValue().email());
    verify(admin).setTenantId(5L);
    verify(admin).setFirstName("Firstname");
    verify(admin).setLastName("Lastname");
    verify(admin).setEmail("mail@example.com");
    verify(adminRepository).save(admin);
  }

  @Test
  void patchAgencyAdmin_Should_notPatchAdmin_When_adminEntityHasTenantIdEqualZero() {
    // given
    Admin admin = mock(Admin.class);
    when(admin.getTenantId()).thenReturn(0L);
    when(retrieveAdminService.findAdmin(anyString(), eq(AdminType.AGENCY))).thenReturn(admin);

    // when, then
    Exception exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> updateAdminService.patchAgencyAdmin("adminId", mock(PatchAdminDTO.class)));

    assertEquals("Admin has tenant id 0", exception.getMessage());
    verify(identityProfileUpdater, never()).updateProfile(any(), any());
    verify(adminRepository, never()).save(any());
  }

  @Test
  void patchAgencyAdmin_Should_patchAdmin_When_validDataIsGiven() {
    // given
    PatchAdminDTO patchAdminDTO = mock(PatchAdminDTO.class);
    when(patchAdminDTO.getFirstname()).thenReturn("Firstname");
    when(patchAdminDTO.getLastname()).thenReturn("Lastname");
    when(patchAdminDTO.getEmail()).thenReturn("mail@example.com");
    Admin admin = mock(Admin.class);
    when(admin.getId()).thenReturn("identity-id");
    when(admin.getTenantId()).thenReturn(3L);
    when(retrieveAdminService.findAdmin(anyString(), eq(AdminType.AGENCY))).thenReturn(admin);

    // when
    updateAdminService.patchAgencyAdmin("adminId", patchAdminDTO);

    // then
    verify(identityProfileUpdater).updateProfile(eq("identity-id"), profileUpdateCaptor.capture());
    assertEquals(3, profileUpdateCaptor.getValue().tenantId());
    assertEquals("Firstname", profileUpdateCaptor.getValue().firstName());
    assertEquals("Lastname", profileUpdateCaptor.getValue().lastName());
    assertEquals("mail@example.com", profileUpdateCaptor.getValue().email());
    verify(admin).setFirstName("Firstname");
    verify(admin).setLastName("Lastname");
    verify(admin).setEmail("mail@example.com");
    verify(adminRepository).save(admin);
  }

  @Test
  void patchTenantAdmin_Should_patchAdmin_When_validDataIsGiven() {
    // given
    PatchAdminDTO patchAdminDTO = mock(PatchAdminDTO.class);
    when(patchAdminDTO.getFirstname()).thenReturn("Firstname");
    when(patchAdminDTO.getLastname()).thenReturn("Lastname");
    when(patchAdminDTO.getEmail()).thenReturn("mail@example.com");
    Admin admin = mock(Admin.class);
    when(admin.getId()).thenReturn("identity-id");
    when(admin.getTenantId()).thenReturn(7L);
    when(retrieveAdminService.findAdmin(anyString(), eq(AdminType.TENANT))).thenReturn(admin);

    // when
    updateAdminService.patchTenantAdmin("adminId", patchAdminDTO);

    // then
    verify(identityProfileUpdater).updateProfile(eq("identity-id"), profileUpdateCaptor.capture());
    assertEquals(7, profileUpdateCaptor.getValue().tenantId());
    assertEquals("Firstname", profileUpdateCaptor.getValue().firstName());
    assertEquals("Lastname", profileUpdateCaptor.getValue().lastName());
    assertEquals("mail@example.com", profileUpdateCaptor.getValue().email());
    verify(admin).setFirstName("Firstname");
    verify(admin).setLastName("Lastname");
    verify(admin).setEmail("mail@example.com");
    verify(adminRepository).save(admin);
  }
}
