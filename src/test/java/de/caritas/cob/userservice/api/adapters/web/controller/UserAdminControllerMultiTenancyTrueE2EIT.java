package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.adapters.web.controller.UserAdminControllerIT.AGENCY_ADMIN_PATH;
import static de.caritas.cob.userservice.api.adapters.web.controller.UserAdminControllerIT.TENANT_ADMIN_PATH;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailAddressUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Transactional
class UserAdminControllerMultiTenancyTrueE2EIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private IdentityConfig identityConfig;

  @MockitoBean AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;

  @MockitoBean(
      extraInterfaces = {
        IdentityAccountRemover.class,
        IdentityAuthentication.class,
        IdentityDeactivator.class,
        IdentityDummyEmailUpdater.class,
        IdentityEmailAddressUpdater.class,
        IdentityEmailOwnerLookup.class,
        IdentityLocaleLookup.class,
        IdentityPasswordUpdater.class,
        IdentityProfileLookup.class,
        IdentityProfileUpdater.class,
        IdentityRoleLookup.class,
        IdentityRoleUpdater.class,
        IdentitySecondFactor.class,
        IdentityUsernameAvailability.class
      })
  IdentityClient identityClient;

  @MockitoBean TenantService tenantService;

  @MockitoBean TenantResolverService tenantResolverService;

  @MockitoBean AuthenticatedUser authenticatedUser;

  @MockitoBean SessionTopicEnrichmentService sessionTopicEnrichmentService;

  @AfterEach
  void reset() {
    identityConfig.setDisplayNameAllowedForConsultants(false);
  }

  @BeforeEach
  public void setUp() {

    CreatedIdentity keycloakResponse = new CreatedIdentity();
    keycloakResponse.setUserId(new EasyRandom().nextObject(String.class));
    when(identityClient.createUser(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(keycloakResponse);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_returnOk_When_requiredCreateAgencyAdminIsGiven()
      throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(95);
    givenTenant();
    givenTenantSuperAdmin();
    givenPlatformAdmin();

    // when

    MvcResult mvcResult =
        this.mockMvc
            .perform(
                post(AGENCY_ADMIN_PATH)
                    .cookie(CSRF_COOKIE)
                    .header(CSRF_HEADER, CSRF_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createAdminDTO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("_embedded.id", notNullValue()))
            .andExpect(jsonPath("_embedded.username", notNullValue()))
            .andExpect(jsonPath("_embedded.lastname", notNullValue()))
            .andExpect(jsonPath("_embedded.email", is("agencyadmin@email.com")))
            .andExpect(jsonPath("_embedded.tenantId", is("95")))
            .andReturn();
    String content = mvcResult.getResponse().getContentAsString();
    JsonPath.read(content, "_embedded.id");
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_return500_When_platformAdminOmitsTargetTenantId()
      throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(null);
    givenTenant();
    givenTenantSuperAdmin();
    givenPlatformAdmin();

    // when

    this.mockMvc
        .perform(
            post(AGENCY_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isInternalServerError())
        .andReturn();
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_returnForbidden_When_tenantSuperAdminHasNoTenant()
      throws Exception {
    // given a tenant super admin whose token carries no tenant
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(95);
    givenTenant();
    givenTenantSuperAdmin();
    givenCallerBelongsToTenant(null);

    // when, then
    this.mockMvc
        .perform(
            post(AGENCY_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_returnForbidden_When_tenantAdminTargetsForeignTenant()
      throws Exception {
    // given a tenant admin of tenant 95 posting an agency admin for tenant 7
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(7);
    givenTenant();
    givenTenantSuperAdmin();
    givenCallerBelongsToTenant(95L);

    // when, then
    this.mockMvc
        .perform(
            post(AGENCY_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_returnOk_When_tenantAdminTargetsOwnTenant() throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(95);
    givenTenant();
    givenTenantSuperAdmin();
    givenCallerBelongsToTenant(95L);

    // when, then
    this.mockMvc
        .perform(
            post(AGENCY_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.tenantId", is("95")));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void createNewTenantAdmin_Should_returnForbidden_When_tenantAdminTargetsForeignTenant()
      throws Exception {
    // given a tenant admin of tenant 95 posting a tenant admin for tenant 7
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("tenantadmin@email.com");
    createAdminDTO.setTenantId(7);
    givenTenant();
    givenCallerBelongsToTenant(95L);

    // when, then
    this.mockMvc
        .perform(
            post(TENANT_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void createNewTenantAdmin_Should_returnOk_When_tenantAdminTargetsOwnTenant() throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("tenantadmin@email.com");
    createAdminDTO.setTenantId(95);
    givenTenant();
    givenCallerBelongsToTenant(95L);

    // when, then
    this.mockMvc
        .perform(
            post(TENANT_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.tenantId", is("95")));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void createNewTenantAdmin_Should_returnOk_When_platformAdminCreatesPlatformAdmin()
      throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("platformadmin@email.com");
    createAdminDTO.setTenantId(0);
    givenTenant();
    givenPlatformAdmin();

    // when, then
    this.mockMvc
        .perform(
            post(TENANT_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.tenantId", is("0")));
  }

  // Production only builds a platform admin from tenant 0 plus both super-admin roles: the real
  // predicates run on that state, so a broken rule fails here instead of being stubbed to true.
  private void givenPlatformAdmin() {
    givenCaller(0L, UserRole.AGENCY_ADMIN, UserRole.TENANT_ADMIN);
  }

  private void givenCaller(Long tenantId, UserRole... roles) {
    Mockito.doCallRealMethod().when(authenticatedUser).setRoles(any());
    Mockito.doCallRealMethod().when(authenticatedUser).setTenantId(any());
    authenticatedUser.setRoles(
        Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
    authenticatedUser.setTenantId(tenantId);
    Mockito.doCallRealMethod().when(authenticatedUser).getRoles();
    Mockito.doCallRealMethod().when(authenticatedUser).isAgencySuperAdmin();
    Mockito.doCallRealMethod().when(authenticatedUser).isTenantSuperAdmin();
    Mockito.doCallRealMethod().when(authenticatedUser).isPlatformAdmin();
    when(authenticatedUser.getTenantId()).thenReturn(tenantId);
  }

  private void givenCallerBelongsToTenant(Long tenantId) {
    when(authenticatedUser.getTenantId()).thenReturn(tenantId);
  }

  private void givenTenantSuperAdmin() {
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);
  }

  private void givenTenant() {
    when(tenantResolverService.resolve(any())).thenReturn(95L);
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain"));
  }
}
