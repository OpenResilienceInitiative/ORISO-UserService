package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class TenantAdminUserServiceTest {

  @InjectMocks private TenantAdminUserService tenantAdminUserService;

  @Mock private SecurityHeaderSupplier securityHeaderSupplier;

  @Mock private TenantHeaderSupplier tenantHeaderSupplier;

  @Mock private RestTemplate restTemplate;

  @Mock private TenantService tenantService;

  @Mock private UpdateAdminService updateAdminService;

  @Mock private RetrieveAdminService retrieveAdminService;

  @Mock private CreateAdminService createAdminService;

  @Mock private DeleteAdminService deleteAdminService;

  @Mock private UserServiceMapper userServiceMapper;

  @Mock private AgencyService agencyService;

  @Mock private AuthenticatedUser authenticatedUser;

  @Test
  void createNewTenantAdmin_Should_AllowPlatformTenantId_WhenAuthenticatedUserIsPlatformAdmin() {
    // given
    CreateAdminDTO createAdminDTO = new CreateAdminDTO();
    createAdminDTO.setTenantId(0);
    Admin platformAdmin = tenantAdmin("platform-admin", 0L);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(createAdminService.createNewTenantAdmin(createAdminDTO)).thenReturn(platformAdmin);

    // when
    AdminResponseDTO response = tenantAdminUserService.createNewTenantAdmin(createAdminDTO);

    // then
    Mockito.verify(createAdminService).createNewTenantAdmin(createAdminDTO);
    assertThat(response.getEmbedded().getTenantId()).isEqualTo("0");
  }

  @Test
  void createNewTenantAdmin_Should_RejectPlatformTenantId_WhenAuthenticatedUserIsNotPlatformAdmin() {
    // given
    CreateAdminDTO createAdminDTO = new CreateAdminDTO();
    createAdminDTO.setTenantId(0);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);

    // when, then
    assertThatThrownBy(() -> tenantAdminUserService.createNewTenantAdmin(createAdminDTO))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Only platform admins can create platform admin accounts");
    Mockito.verifyNoInteractions(createAdminService);
  }

  @Test
  void updateTenantAdmin_Should_UpdateTenantAndEnrichResponseWithTenantSubdomain() {
    // given
    EasyRandom random = new EasyRandom();
    UpdateTenantAdminDTO tenantAdminDTO = random.nextObject(UpdateTenantAdminDTO.class);
    when(tenantService.getRestrictedTenantData(1L))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain"));
    Admin tenantAdmin = new Admin();
    tenantAdmin.setTenantId(1L);
    when(updateAdminService.updateTenantAdmin(
            Mockito.anyString(), Mockito.any(UpdateTenantAdminDTO.class)))
        .thenReturn(tenantAdmin);
    // when
    AdminResponseDTO adminResponseDTO =
        tenantAdminUserService.updateTenantAdmin("1", tenantAdminDTO);
    // then
    Mockito.verify(updateAdminService).updateTenantAdmin("1", tenantAdminDTO);
    Mockito.verify(tenantService).getRestrictedTenantData(1L);

    assertThat(adminResponseDTO.getEmbedded().getTenantSubdomain()).isEqualTo("subdomain");
  }

  private Admin tenantAdmin(String id, Long tenantId) {
    Admin admin = new Admin();
    admin.setId(id);
    admin.setType(Admin.AdminType.TENANT);
    admin.setTenantId(tenantId);
    return admin;
  }
}
