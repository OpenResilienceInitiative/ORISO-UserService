package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  @Mock private de.caritas.cob.userservice.api.port.out.ConsultantRepository consultantRepository;

  @Mock private AuthenticatedUser authenticatedUser;

  @Test
  void findAgencyAdminShouldExposeActiveConsultantIdentity() {
    var admin = agencyAdmin("agency-admin", 1L);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(retrieveAdminService.findAdmin("agency-admin", Admin.AdminType.AGENCY)).thenReturn(admin);
    when(consultantRepository.findActiveIdsByIdIn(Set.of("agency-admin")))
        .thenReturn(Set.of("agency-admin"));

    var response = agencyAdminUserService.findAgencyAdmin("agency-admin");

    assertThat(response.getEmbedded().getHasOtherIdentity()).isTrue();
  }

  @Test
  void findAgencyAdminShouldReportNoActiveConsultantIdentity() {
    var admin = agencyAdmin("agency-admin", 1L);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(retrieveAdminService.findAdmin("agency-admin", Admin.AdminType.AGENCY)).thenReturn(admin);
    when(consultantRepository.findActiveIdsByIdIn(Set.of("agency-admin")))
        .thenReturn(Collections.emptySet());

    var response = agencyAdminUserService.findAgencyAdmin("agency-admin");

    assertThat(response.getEmbedded().getHasOtherIdentity()).isFalse();
  }

  @Test
  void findAgencyAdminsByInfix_Should_ScopeToCallerAgencies_WhenRestrictedAgencyAdmin() {
    // given
    PageRequest pageRequest = PageRequest.of(0, 10);
    List<Long> callerAgencyIds = Arrays.asList(5L, 6L);
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("caller-admin");
    when(retrieveAdminService.findAgencyIdsOfAdmin("caller-admin")).thenReturn(callerAgencyIds);
    when(retrieveAdminService.findAllByInfixScopedToAgencies(
            "*", Admin.AdminType.AGENCY, callerAgencyIds, pageRequest))
        .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(Collections.emptyList());
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(new HashMap<>());

    // when
    agencyAdminUserService.findAgencyAdminsByInfix("*", pageRequest);

    // then: scoped query is used, the unscoped one is never called
    Mockito.verify(retrieveAdminService)
        .findAllByInfixScopedToAgencies("*", Admin.AdminType.AGENCY, callerAgencyIds, pageRequest);
    Mockito.verify(retrieveAdminService, Mockito.never())
        .findAllByInfix(Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  void deleteAgencyAdmin_Should_ThrowForbidden_WhenRestrictedAgencyAdminTargetsForeignAgency() {
    // given
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("caller-admin");
    when(retrieveAdminService.findAgencyIdsOfAdmin("caller-admin"))
        .thenReturn(Collections.singletonList(5L));
    when(retrieveAdminService.findAgencyIdsOfAdmin("foreign-admin"))
        .thenReturn(Collections.singletonList(9L));

    // when / then
    Assertions.assertThrows(
        ForbiddenException.class, () -> agencyAdminUserService.deleteAgencyAdmin("foreign-admin"));
    Mockito.verify(deleteAdminService, Mockito.never()).deleteAgencyAdmin(Mockito.any());
  }

  @Test
  void deleteAgencyAdmin_Should_Delete_WhenRestrictedAgencyAdminSharesAgency() {
    // given
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("caller-admin");
    when(retrieveAdminService.findAgencyIdsOfAdmin("caller-admin"))
        .thenReturn(Arrays.asList(5L, 7L));
    when(retrieveAdminService.findAgencyIdsOfAdmin("peer-admin"))
        .thenReturn(Collections.singletonList(7L));

    // when
    agencyAdminUserService.deleteAgencyAdmin("peer-admin");

    // then
    Mockito.verify(deleteAdminService).deleteAgencyAdmin("peer-admin");
  }

  @Test
  void deleteAgencyAdmin_Should_NotScope_WhenCallerIsPlatformAdmin() {
    // given a platform admin (unscoped access)
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    // when
    agencyAdminUserService.deleteAgencyAdmin("any-admin");

    // then no agency lookup happens and deletion proceeds
    Mockito.verify(retrieveAdminService, Mockito.never()).findAgencyIdsOfAdmin(Mockito.any());
    Mockito.verify(retrieveAdminService, Mockito.never()).findAdmin(Mockito.any(), Mockito.any());
    Mockito.verify(deleteAdminService).deleteAgencyAdmin("any-admin");
  }

  /**
   * #968: a tenant admin (not restricted agency admin, not platform admin) must not act on agency
   * admins of other tenants via the by-id endpoints — mirrors the search-side tenant scoping.
   */
  @Test
  void deleteAgencyAdmin_Should_ThrowForbidden_WhenTenantAdminTargetsForeignTenant() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(false);
    when(authenticatedUser.getTenantId()).thenReturn(9L);
    Admin foreignAgencyAdmin = agencyAdmin("foreign-agency-admin", 1L);
    when(retrieveAdminService.findAdmin("foreign-agency-admin", Admin.AdminType.AGENCY))
        .thenReturn(foreignAgencyAdmin);

    Assertions.assertThrows(
        ForbiddenException.class,
        () -> agencyAdminUserService.deleteAgencyAdmin("foreign-agency-admin"));
    Mockito.verify(deleteAdminService, Mockito.never()).deleteAgencyAdmin(Mockito.any());
  }

  @Test
  void deleteAgencyAdmin_Should_Delete_WhenTenantAdminTargetsOwnTenant() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(false);
    when(authenticatedUser.getTenantId()).thenReturn(9L);
    Admin ownAgencyAdmin = agencyAdmin("own-agency-admin", 9L);
    when(retrieveAdminService.findAdmin("own-agency-admin", Admin.AdminType.AGENCY))
        .thenReturn(ownAgencyAdmin);

    agencyAdminUserService.deleteAgencyAdmin("own-agency-admin");

    Mockito.verify(deleteAdminService).deleteAgencyAdmin("own-agency-admin");
  }

  @Test
  void findAgencyAdminsByInfix_Should_NotUsePerTenantLookups() {
    // given
    PageRequest pageRequest = PageRequest.of(0, 10);
    Admin.AdminBase firstAdminBase = adminBase("agency-admin-1", 1L);
    Admin.AdminBase secondAdminBase = adminBase("agency-admin-2", 1L);
    Admin.AdminBase thirdAdminBase = adminBase("agency-admin-3", 2L);
    Page<Admin.AdminBase> adminsPage =
        new PageImpl<>(
            Arrays.asList(firstAdminBase, secondAdminBase, thirdAdminBase), pageRequest, 3);
    Admin firstAgencyAdmin = agencyAdmin("agency-admin-1", 1L);
    Admin secondAgencyAdmin = agencyAdmin("agency-admin-2", 1L);
    Admin thirdAgencyAdmin = agencyAdmin("agency-admin-3", 2L);
    List<Admin> fullAdmins = Arrays.asList(firstAgencyAdmin, secondAgencyAdmin, thirdAgencyAdmin);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(retrieveAdminService.findAllByInfix("*", Admin.AdminType.AGENCY, pageRequest))
        .thenReturn(adminsPage);
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(fullAdmins);
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
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
    agencyAdminUserService.findAgencyAdminsByInfix("*", pageRequest);

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

  /**
   * #968: a plain tenant admin (no restricted-agency privileges, not platform admin) querying
   * /useradmin/agencyadmins/search must only see agency admins of their own tenant. Before the fix
   * the same call returned agency admins of every tenant, mirroring the tenant-admin leak.
   */
  @Test
  void findAgencyAdminsByInfix_Should_ScopeToCallerTenant_ForTenantAdmin() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(false);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.getTenantId()).thenReturn(9L);
    when(retrieveAdminService.findAllByInfixScopedToTenant(
            "*", Admin.AdminType.AGENCY, 9L, pageRequest))
        .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(Collections.emptyList());
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(new HashMap<>());

    agencyAdminUserService.findAgencyAdminsByInfix("*", pageRequest);

    Mockito.verify(retrieveAdminService)
        .findAllByInfixScopedToTenant("*", Admin.AdminType.AGENCY, 9L, pageRequest);
    Mockito.verify(retrieveAdminService, Mockito.never())
        .findAllByInfix(Mockito.anyString(), Mockito.any(), Mockito.any(PageRequest.class));
    Mockito.verify(retrieveAdminService, Mockito.never())
        .findAllByInfixScopedToAgencies(
            Mockito.anyString(), Mockito.any(), Mockito.anyCollection(), Mockito.any());
  }

  /**
   * Fail-closed mirror of the tenant-admin search: if a tenant-bound caller has no resolvable
   * tenant, the agency-admin search still routes through the scoped call with a null tenant and
   * returns an empty page — never the unscoped list (#968).
   */
  @Test
  void findAgencyAdminsByInfix_Should_FailClosedToEmpty_WhenTenantAdminHasNullTenant() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(false);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.getTenantId()).thenReturn(null);
    when(retrieveAdminService.findAllByInfixScopedToTenant(
            "*", Admin.AdminType.AGENCY, null, pageRequest))
        .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(Collections.emptyList());
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(new HashMap<>());

    agencyAdminUserService.findAgencyAdminsByInfix("*", pageRequest);

    Mockito.verify(retrieveAdminService)
        .findAllByInfixScopedToTenant("*", Admin.AdminType.AGENCY, null, pageRequest);
    Mockito.verify(retrieveAdminService, Mockito.never())
        .findAllByInfix(Mockito.anyString(), Mockito.any(), Mockito.any(PageRequest.class));
  }

  @Test
  void findAgencyAdminsByInfix_Should_NotScope_ForPlatformAdmin() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(false);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(retrieveAdminService.findAllByInfix("*", Admin.AdminType.AGENCY, pageRequest))
        .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(Collections.emptyList());
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(new HashMap<>());

    agencyAdminUserService.findAgencyAdminsByInfix("*", pageRequest);

    Mockito.verify(retrieveAdminService).findAllByInfix("*", Admin.AdminType.AGENCY, pageRequest);
    Mockito.verify(retrieveAdminService, Mockito.never())
        .findAllByInfixScopedToTenant(
            Mockito.anyString(), Mockito.any(), Mockito.anyLong(), Mockito.any(PageRequest.class));
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

      @Override
      public LocalDateTime getUpdateDate() {
        return LocalDateTime.now();
      }
    };
  }
}
