package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
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
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-Träger isolation of the {@code /useradmin/**} endpoints that take an admin or user ID from
 * the path, through the real security chain and database with multitenancy enabled.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Transactional
@Import(TenantFixtures.class)
@WithTenant(UserAdminIdScopeIT.OWN_TENANT)
class UserAdminIdScopeIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;
  private static final long OWN_AGENCY = 9101L;
  private static final long OTHER_AGENCY_OF_OWN_TENANT = 9102L;
  private static final long FOREIGN_TENANT_AGENCY = 9201L;

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private AdminRepository adminRepository;
  @Autowired private AdminAgencyRepository adminAgencyRepository;

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
  @MockitoBean SessionTopicEnrichmentService sessionTopicEnrichmentService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean AgencyService agencyService;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Admin ownTenantAdmin;
  private Admin foreignTenantAgencyAdmin;
  private Admin callingAgencyAdmin;
  private Admin ownAgencyAdmin;
  private Admin otherAgencyAdmin;

  private final Map<Long, AgencyDTO> agencies = new HashMap<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void seedAdminsOfTwoTenants() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    when(((IdentityRoleLookup) identityClient).findAllByUserId(anyString()))
        .thenReturn(List.of("tenant-admin", "user-admin"));
    givenAgency(OWN_AGENCY, OWN_TENANT);
    givenAgency(OTHER_AGENCY_OF_OWN_TENANT, OWN_TENANT);
    givenAgency(FOREIGN_TENANT_AGENCY, FOREIGN_TENANT);
    when(agencyService.getAgenciesWithoutCaching(any()))
        .thenAnswer(
            call ->
                ((List<Long>) call.getArgument(0))
                    .stream().map(agencies::get).filter(Objects::nonNull).toList());

    ownTenantAdmin = fixtures.admin(OWN_TENANT, AdminType.TENANT);
    foreignTenantAgencyAdmin =
        fixtures.admin(FOREIGN_TENANT, AdminType.AGENCY, FOREIGN_TENANT_AGENCY);
    callingAgencyAdmin = fixtures.admin(OWN_TENANT, AdminType.AGENCY, OWN_AGENCY);
    ownAgencyAdmin = fixtures.admin(OWN_TENANT, AdminType.AGENCY, OWN_AGENCY);
    otherAgencyAdmin = fixtures.admin(OWN_TENANT, AdminType.AGENCY, OTHER_AGENCY_OF_OWN_TENANT);
  }

  // --- PUT /useradmin/tenantadmins/{adminId} -------------------------------------------------

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void updateTenantAdmin_Should_Refuse_When_TenantAdminMovesAdminIntoAnotherTenant()
      throws Exception {
    actAsTenantAdmin();

    mockMvc
        .perform(
            withCsrf(put("/useradmin/tenantadmins/" + ownTenantAdmin.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tenantAdminUpdate(ownTenantAdmin, FOREIGN_TENANT)))
        .andExpect(status().isForbidden());

    assertThat(tenantOf(ownTenantAdmin)).isEqualTo(OWN_TENANT);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void updateTenantAdmin_Should_Succeed_When_TenantAdminKeepsOwnTenant() throws Exception {
    actAsTenantAdmin();

    mockMvc
        .perform(
            withCsrf(put("/useradmin/tenantadmins/" + ownTenantAdmin.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tenantAdminUpdate(ownTenantAdmin, OWN_TENANT)))
        .andExpect(status().isOk());
  }

  // --- POST/PUT/DELETE /useradmin/agencyadmins/{adminId}/agencies ------------------------------

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void addAgency_Should_Refuse_When_AgencyAdminAddsThemselvesToAnotherAgency() throws Exception {
    actAsAgencyAdmin();

    addAgency(callingAgencyAdmin, OTHER_AGENCY_OF_OWN_TENANT).andExpect(status().isForbidden());

    assertThat(agenciesOf(callingAgencyAdmin)).containsOnly(OWN_AGENCY);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void setAgencies_Should_Refuse_When_AgencyAdminSetsAnotherAgency() throws Exception {
    actAsAgencyAdmin();

    mockMvc
        .perform(
            withCsrf(put("/useradmin/agencyadmins/" + callingAgencyAdmin.getId() + "/agencies"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "[{\"agencyId\":"
                        + OWN_AGENCY
                        + "},{\"agencyId\":"
                        + OTHER_AGENCY_OF_OWN_TENANT
                        + "}]"))
        .andExpect(status().isForbidden());

    assertThat(agenciesOf(callingAgencyAdmin)).containsOnly(OWN_AGENCY);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void removeAgency_Should_Refuse_When_AgencyAdminRemovesAdminOfAnotherAgency() throws Exception {
    actAsAgencyAdmin();

    removeAgency(otherAgencyAdmin, OTHER_AGENCY_OF_OWN_TENANT).andExpect(status().isForbidden());

    assertThat(agenciesOf(otherAgencyAdmin)).containsOnly(OTHER_AGENCY_OF_OWN_TENANT);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void addAgency_Should_Succeed_When_AgencyAdminAddsOwnAgencyToAdminOfOwnAgency() throws Exception {
    actAsAgencyAdmin();
    adminAgencyRepository.deleteByAdminIdAndAgencyId(ownAgencyAdmin.getId(), OWN_AGENCY);
    adminAgencyRepository.save(
        AdminAgency.builder().admin(ownAgencyAdmin).agencyId(OTHER_AGENCY_OF_OWN_TENANT).build());
    adminAgencyRepository.save(
        AdminAgency.builder()
            .admin(callingAgencyAdmin)
            .agencyId(OTHER_AGENCY_OF_OWN_TENANT)
            .build());

    addAgency(ownAgencyAdmin, OWN_AGENCY).andExpect(status().isCreated());

    assertThat(agenciesOf(ownAgencyAdmin)).containsOnly(OWN_AGENCY, OTHER_AGENCY_OF_OWN_TENANT);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void setAgencies_Should_Refuse_When_AgencyAdminEmptiesAdminOfAnotherAgency() throws Exception {
    actAsAgencyAdmin();

    setAgencies(otherAgencyAdmin).andExpect(status().isForbidden());

    assertThat(agenciesOf(otherAgencyAdmin)).containsOnly(OTHER_AGENCY_OF_OWN_TENANT);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void setAgencies_Should_Refuse_When_AgencyAdminDropsForeignAgencyOfSharedAdmin()
      throws Exception {
    actAsAgencyAdmin();
    adminAgencyRepository.save(
        AdminAgency.builder().admin(ownAgencyAdmin).agencyId(OTHER_AGENCY_OF_OWN_TENANT).build());

    setAgencies(ownAgencyAdmin, OWN_AGENCY).andExpect(status().isForbidden());

    assertThat(agenciesOf(ownAgencyAdmin)).containsOnly(OWN_AGENCY, OTHER_AGENCY_OF_OWN_TENANT);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void addAgency_Should_Refuse_When_TenantAdminAddsAnAgencyOfAnotherTenant() throws Exception {
    actAsTenantAdmin();

    addAgency(ownAgencyAdmin, FOREIGN_TENANT_AGENCY).andExpect(status().isForbidden());

    assertThat(agenciesOf(ownAgencyAdmin)).containsOnly(OWN_AGENCY);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void removeAgency_Should_Refuse_When_TenantAdminRemovesAgencyOfAnotherTenantsAdmin()
      throws Exception {
    actAsTenantAdmin();

    removeAgency(foreignTenantAgencyAdmin, FOREIGN_TENANT_AGENCY).andExpect(status().isForbidden());

    assertThat(agenciesOf(foreignTenantAgencyAdmin)).containsOnly(FOREIGN_TENANT_AGENCY);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void addAgency_Should_Succeed_When_TenantAdminAddsOwnAgencyToOwnTenantAdmin() throws Exception {
    actAsTenantAdmin();

    addAgency(ownAgencyAdmin, OTHER_AGENCY_OF_OWN_TENANT).andExpect(status().isCreated());

    assertThat(agenciesOf(ownAgencyAdmin)).contains(OWN_AGENCY, OTHER_AGENCY_OF_OWN_TENANT);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void removeAgency_Should_Succeed_When_PlatformAdminActsInAnyTenant() throws Exception {
    actAsPlatformAdmin();

    removeAgency(foreignTenantAgencyAdmin, FOREIGN_TENANT_AGENCY).andExpect(status().isOk());

    assertThat(agenciesOf(foreignTenantAgencyAdmin)).isEmpty();
  }

  // --- GET /useradmin/agencyadmins/{adminId}/agencies ---------------------------------------

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void getAdminAgencies_Should_Refuse_When_TenantAdminReadsAdminOfAnotherTenant() throws Exception {
    actAsTenantAdmin();

    mockMvc
        .perform(get("/useradmin/agencyadmins/" + foreignTenantAgencyAdmin.getId() + "/agencies"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void getAdminAgencies_Should_Succeed_When_AgencyAdminReadsAdminOfOwnAgency() throws Exception {
    actAsAgencyAdmin();

    mockMvc
        .perform(get("/useradmin/agencyadmins/" + ownAgencyAdmin.getId() + "/agencies"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", contains((int) OWN_AGENCY)));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void getAdminAgencies_Should_Refuse_When_AgencyAdminReadsAdminOfAnotherAgency() throws Exception {
    actAsAgencyAdmin();

    mockMvc
        .perform(get("/useradmin/agencyadmins/" + otherAgencyAdmin.getId() + "/agencies"))
        .andExpect(status().isForbidden());
  }

  // --- GET /useradmin/users/{userId}/identities --------------------------------------------

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void getUserIdentities_Should_Refuse_When_TenantAdminReadsUserOfAnotherTenant() throws Exception {
    actAsTenantAdmin();

    mockMvc
        .perform(get("/useradmin/users/" + foreignTenantAgencyAdmin.getId() + "/identities"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void getUserIdentities_Should_Succeed_When_TenantAdminReadsOwnTenantAdmin() throws Exception {
    actAsTenantAdmin();

    mockMvc
        .perform(get("/useradmin/users/" + ownAgencyAdmin.getId() + "/identities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasAdminIdentity").value(true))
        .andExpect(jsonPath("$.hasConsultantIdentity").value(false));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private ResultActions addAgency(Admin target, long agencyId) throws Exception {
    return mockMvc.perform(
        withCsrf(post("/useradmin/agencyadmins/" + target.getId() + "/agencies"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"agencyId\":" + agencyId + "}"));
  }

  private ResultActions setAgencies(Admin target, long... agencyIds) throws Exception {
    return mockMvc.perform(
        withCsrf(put("/useradmin/agencyadmins/" + target.getId() + "/agencies"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                Arrays.stream(agencyIds)
                    .mapToObj(id -> "{\"agencyId\":" + id + "}")
                    .collect(Collectors.joining(",", "[", "]"))));
  }

  private ResultActions removeAgency(Admin target, long agencyId) throws Exception {
    return mockMvc.perform(
        withCsrf(delete("/useradmin/agencyadmins/" + target.getId() + "/agencies/" + agencyId)));
  }

  // --- #1263 B3: the Träger/Beratungsstelle search filter only narrows the caller's reach ------

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void searchTenantAdmins_Should_FindNothing_When_TenantAdminFiltersForeignTenant()
      throws Exception {
    actAsTenantAdmin();

    assertThat(searchIds("/useradmin/tenantadmins/search", "&tenantId=" + FOREIGN_TENANT))
        .isEmpty();
    assertThat(searchIds("/useradmin/tenantadmins/search", "&tenantId=" + OWN_TENANT))
        .contains(ownTenantAdmin.getId());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void searchAgencyAdmins_Should_NotWiden_When_AgencyAdminFiltersForeignAgencyOrTenant()
      throws Exception {
    actAsAgencyAdmin();

    assertThat(searchIds("/useradmin/agencyadmins/search", "&tenantId=" + FOREIGN_TENANT))
        .isEmpty();
    assertThat(
            searchIds("/useradmin/agencyadmins/search", "&agencyId=" + OTHER_AGENCY_OF_OWN_TENANT))
        .isEmpty();
    assertThat(searchIds("/useradmin/agencyadmins/search", "&agencyId=" + FOREIGN_TENANT_AGENCY))
        .isEmpty();
    assertThat(
            searchIds(
                "/useradmin/agencyadmins/search",
                "&tenantId=" + OWN_TENANT + "&agencyId=" + OWN_AGENCY))
        .containsExactlyInAnyOrder(callingAgencyAdmin.getId(), ownAgencyAdmin.getId());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void searchAgencyAdmins_Should_KeepOwnTenant_When_TenantAdminFiltersForeignAgency()
      throws Exception {
    actAsTenantAdmin();

    assertThat(searchIds("/useradmin/agencyadmins/search", "&agencyId=" + FOREIGN_TENANT_AGENCY))
        .isEmpty();
    assertThat(
            searchIds("/useradmin/agencyadmins/search", "&agencyId=" + OTHER_AGENCY_OF_OWN_TENANT))
        .containsExactly(otherAgencyAdmin.getId());
  }

  private List<String> searchIds(String path, String filter) throws Exception {
    var body =
        mockMvc
            .perform(
                withCsrf(
                    get(path + "?query=*&page=1&perPage=100&field=FIRSTNAME&order=ASC" + filter)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return com.jayway.jsonpath.JsonPath.read(body, "$._embedded[*]._embedded.id");
  }

  private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
    return request.cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  private static String tenantAdminUpdate(Admin admin, long tenantId) {
    return "{\"firstname\":\"Synthetic\",\"lastname\":\"Admin\",\"email\":\""
        + admin.getEmail()
        + "\",\"tenantId\":"
        + tenantId
        + "}";
  }

  /** Reads in the technical context, so the tenant filter hides nothing. */
  private Long tenantOf(Admin admin) {
    return Tenants.acrossAll(
        () -> adminRepository.findById(admin.getId()).orElseThrow().getTenantId());
  }

  private Set<Long> agenciesOf(Admin admin) {
    return Tenants.acrossAll(
        () ->
            adminAgencyRepository.findByAdminId(admin.getId()).stream()
                .map(AdminAgency::getAgencyId)
                .collect(Collectors.toSet()));
  }

  private void actAsTenantAdmin() {
    Tenants.actAs(
        caller,
        "tenant-admin-1",
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdmin() {
    Tenants.actAs(
        caller,
        callingAgencyAdmin.getId(),
        OWN_TENANT,
        UserRole.RESTRICTED_AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsPlatformAdmin() {
    Tenants.actAs(
        caller,
        "platform-admin",
        0L,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void givenAgency(long agencyId, long tenantId) {
    var agency = new AgencyDTO().id(agencyId).tenantId(tenantId).consultingType(1);
    when(agencyService.getAgency(agencyId)).thenReturn(agency);
    when(agencyService.getAgencyWithoutCaching(agencyId)).thenReturn(agency);
    agencies.put(agencyId, agency);
  }
}
