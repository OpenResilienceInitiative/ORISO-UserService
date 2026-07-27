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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

  @Mock private de.caritas.cob.userservice.api.port.out.ConsultantRepository consultantRepository;

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
  void
      createNewTenantAdmin_Should_RejectPlatformTenantId_WhenAuthenticatedUserIsNotPlatformAdmin() {
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

  @Test
  void findTenantAdmins_Should_ReturnAllAdminsForSameTenant() {
    // given
    Long tenantId = 42L;
    Admin firstTenantAdmin = new Admin();
    firstTenantAdmin.setId("tenant-admin-1");
    firstTenantAdmin.setType(Admin.AdminType.TENANT);
    firstTenantAdmin.setTenantId(tenantId);
    Admin secondTenantAdmin = new Admin();
    secondTenantAdmin.setId("tenant-admin-2");
    secondTenantAdmin.setType(Admin.AdminType.TENANT);
    secondTenantAdmin.setTenantId(tenantId);
    when(retrieveAdminService.findTenantAdminsByTenantId(tenantId))
        .thenReturn(Arrays.asList(firstTenantAdmin, secondTenantAdmin));

    // when
    List<AdminResponseDTO> tenantAdmins = tenantAdminUserService.findTenantAdmins(tenantId);

    // then
    assertThat(tenantAdmins).hasSize(2);
    assertThat(tenantAdmins)
        .extracting(admin -> admin.getEmbedded().getId())
        .containsExactly("tenant-admin-1", "tenant-admin-2");
    assertThat(tenantAdmins)
        .extracting(admin -> admin.getEmbedded().getTenantId())
        .containsExactly("42", "42");
  }

  @Test
  void findTenantAdmin_Should_SetHasOtherIdentityTrue_When_ConsultantIdentityExists() {
    // given
    Admin admin = new Admin();
    admin.setId("tenant-admin-1");
    admin.setType(Admin.AdminType.TENANT);
    when(retrieveAdminService.findAdmin("tenant-admin-1", Admin.AdminType.TENANT))
        .thenReturn(admin);
    when(consultantRepository.findActiveIdsByIdIn(java.util.Set.of("tenant-admin-1")))
        .thenReturn(java.util.Set.of("tenant-admin-1"));

    // when
    AdminResponseDTO response = tenantAdminUserService.findTenantAdmin("tenant-admin-1");

    // then
    assertThat(response.getEmbedded().getHasOtherIdentity()).isTrue();
  }

  @Test
  void findTenantAdmin_Should_SetHasOtherIdentityFalse_When_NoConsultantIdentityExists() {
    // given
    Admin admin = new Admin();
    admin.setId("tenant-admin-1");
    admin.setType(Admin.AdminType.TENANT);
    when(retrieveAdminService.findAdmin("tenant-admin-1", Admin.AdminType.TENANT))
        .thenReturn(admin);
    when(consultantRepository.findActiveIdsByIdIn(java.util.Set.of("tenant-admin-1")))
        .thenReturn(java.util.Collections.emptySet());

    // when
    AdminResponseDTO response = tenantAdminUserService.findTenantAdmin("tenant-admin-1");

    // then
    assertThat(response.getEmbedded().getHasOtherIdentity()).isFalse();
  }

  @Test
  void findTenantAdminsByInfix_Should_NotUsePerTenantLookups() {
    // given
    PageRequest pageRequest = PageRequest.of(0, 10);
    Admin.AdminBase firstAdminBase = adminBase("tenant-admin-1", 1L);
    Admin.AdminBase secondAdminBase = adminBase("tenant-admin-2", 1L);
    Admin.AdminBase thirdAdminBase = adminBase("tenant-admin-3", 2L);
    Page<Admin.AdminBase> adminsPage =
        new PageImpl<>(
            Arrays.asList(firstAdminBase, secondAdminBase, thirdAdminBase), pageRequest, 3);
    Admin firstTenantAdmin = tenantAdmin("tenant-admin-1", 1L);
    Admin secondTenantAdmin = tenantAdmin("tenant-admin-2", 1L);
    Admin thirdTenantAdmin = tenantAdmin("tenant-admin-3", 2L);
    List<Admin> fullAdmins = Arrays.asList(firstTenantAdmin, secondTenantAdmin, thirdTenantAdmin);
    when(retrieveAdminService.findAllByInfix("*", Admin.AdminType.TENANT, pageRequest))
        .thenReturn(adminsPage);
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(fullAdmins);
    when(tenantService.getRestrictedTenantData(Set.of(1L, 2L)))
        .thenReturn(List.of(new RestrictedTenantDTO().id(1L).name("Known tenant")));
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(new HashMap<>());

    // when
    tenantAdminUserService.findTenantAdminsByInfix("*", pageRequest);

    // then
    ArgumentCaptor<Map<Long, String>> tenantNameMapCaptor = ArgumentCaptor.forClass(Map.class);
    Mockito.verify(userServiceMapper)
        .mapOfAdmin(
            Mockito.eq(adminsPage),
            Mockito.eq(fullAdmins),
            Mockito.anyList(),
            Mockito.anyList(),
            tenantNameMapCaptor.capture(),
            Mockito.any());
    Assertions.assertEquals("Known tenant", tenantNameMapCaptor.getValue().get(1L));
    Assertions.assertFalse(tenantNameMapCaptor.getValue().containsKey(2L));
    Mockito.verify(tenantService).getRestrictedTenantData(Set.of(1L, 2L));
    Mockito.verify(tenantService, Mockito.never()).getRestrictedTenantData(Mockito.anyLong());
  }

  private Admin tenantAdmin(String id, Long tenantId) {
    Admin admin = new Admin();
    admin.setId(id);
    admin.setType(Admin.AdminType.TENANT);
    admin.setTenantId(tenantId);
    return admin;
  }

  private Admin.AdminBase adminBase(String id, Long tenantId) {
    return new Admin.AdminBase() {
      @Override
      public String getId() {
        return id;
      }

      @Override
      public String getFirstName() {
        return "First";
      }

      @Override
      public String getLastName() {
        return "Last";
      }

      @Override
      public String getEmail() {
        return id + "@example.org";
      }

      @Override
      public Long getTenantId() {
        return tenantId;
      }

      @Override
      public Admin.AdminType getType() {
        return Admin.AdminType.TENANT;
      }

      @Override
      public LocalDateTime getUpdateDate() {
        return LocalDateTime.now();
      }
    };
  }
}
