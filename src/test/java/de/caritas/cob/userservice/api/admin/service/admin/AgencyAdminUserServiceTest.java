package de.caritas.cob.userservice.api.admin.service.admin;

import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class AgencyAdminUserServiceTest {

  @InjectMocks private AgencyAdminUserService agencyAdminUserService;

  @Mock private RetrieveAdminService retrieveAdminService;

  @Mock private CreateAdminService createAdminService;

  @Mock private UpdateAdminService updateAdminService;

  @Mock private DeleteAdminService deleteAdminService;

  @Mock private UserServiceMapper userServiceMapper;

  @Mock private AgencyService agencyService;

  @Mock private TenantService tenantService;

  @Test
  void findAgencyAdminsByInfix_Should_NotFail_WhenTenantServiceReturnsNotFound() {
    // given
    PageRequest pageRequest = PageRequest.of(0, 10);
    Admin.AdminBase firstAdminBase = adminBase("agency-admin-1", 1L);
    Admin.AdminBase secondAdminBase = adminBase("agency-admin-2", 2L);
    Page<Admin.AdminBase> adminsPage =
        new PageImpl<>(Arrays.asList(firstAdminBase, secondAdminBase), pageRequest, 2);
    Admin firstAgencyAdmin = agencyAdmin("agency-admin-1", 1L);
    Admin secondAgencyAdmin = agencyAdmin("agency-admin-2", 2L);
    List<Admin> fullAdmins = Arrays.asList(firstAgencyAdmin, secondAgencyAdmin);
    when(retrieveAdminService.findAllByInfix("*", Admin.AdminType.AGENCY, pageRequest))
        .thenReturn(adminsPage);
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(fullAdmins);
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(tenantService.getRestrictedTenantData(1L))
        .thenReturn(new RestrictedTenantDTO().name("Known tenant"));
    when(tenantService.getRestrictedTenantData(2L))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(), Mockito.anyList(), Mockito.anyList(), Mockito.anyList(), Mockito.any()))
        .thenReturn(new HashMap<>());

    // when
    agencyAdminUserService.findAgencyAdminsByInfix("*", pageRequest);

    // then
    ArgumentCaptor<Map<Long, String>> tenantNameMapCaptor = ArgumentCaptor.forClass(Map.class);
    Mockito.verify(userServiceMapper)
        .mapOfAdmin(
            Mockito.eq(adminsPage),
            Mockito.eq(fullAdmins),
            Mockito.anyList(),
            Mockito.anyList(),
            tenantNameMapCaptor.capture());
    Assertions.assertEquals("Known tenant", tenantNameMapCaptor.getValue().get(1L));
    Assertions.assertFalse(tenantNameMapCaptor.getValue().containsKey(2L));
  }

  private Admin agencyAdmin(String id, Long tenantId) {
    Admin admin = new Admin();
    admin.setId(id);
    admin.setType(Admin.AdminType.AGENCY);
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
        return Admin.AdminType.AGENCY;
      }
    };
  }
}
