package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
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
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-tenant ("cross-Träger") isolation of {@code POST
 * /useradmin/admins/{adminId}/grant-consultant-identity}, run through the real security chain,
 * controller, service and database with multitenancy enabled.
 *
 * <p>Rules: the platform admin (tenant 0) may grant to every admin; a Träger admin only to admins
 * of their own tenant and only into agencies of that tenant; a Beratungsstellen admin (restricted
 * agency admin) only to admins of their own agencies and only into their own agencies.
 *
 * <p>The caller is a Mockito mock that calls the real methods, so the role helpers the scoping
 * relies on ({@code isPlatformAdmin}, {@code hasRestrictedAgencyPriviliges}) run unchanged on the
 * roles set here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Transactional
class GrantConsultantIdentityTenantScopeIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;
  private static final long OWN_AGENCY = 9101L;
  private static final long OTHER_AGENCY_OF_OWN_TENANT = 9102L;
  private static final long FOREIGN_TENANT_AGENCY = 9201L;

  @Autowired private MockMvc mockMvc;
  @Autowired private AdminRepository adminRepository;
  @Autowired private AdminAgencyRepository adminAgencyRepository;
  @Autowired private ConsultantRepository consultantRepository;

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
  @MockitoBean MatrixSynapseService matrixUserClient;
  @MockitoBean AgencyService agencyService;
  @MockitoBean ChatRecoveryEnrollmentPolicyService chatRecoveryEnrollmentPolicyService;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private final java.util.Map<Long, AgencyDTO> agencies = new java.util.HashMap<>();

  private Admin ownTenantAdmin;
  private Admin foreignTenantAdmin;
  private Admin ownAgencyAdmin;
  private Admin otherAgencyAdmin;
  private Admin callingAgencyAdmin;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void seedAdminsOfTwoTenants() throws Exception {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    when(matrixUserClient.createUserId(anyString(), anyString(), anyString()))
        .thenReturn("@synthetic:matrix.test");
    when(chatRecoveryEnrollmentPolicyService.forNewConsultant(any()))
        .thenReturn(new RecoveryPolicySnapshot("RECOVERY_KEY", 1L));
    givenAgency(OWN_AGENCY, OWN_TENANT);
    givenAgency(OTHER_AGENCY_OF_OWN_TENANT, OWN_TENANT);
    givenAgency(FOREIGN_TENANT_AGENCY, FOREIGN_TENANT);
    when(agencyService.getAgenciesWithoutCaching(any()))
        .thenAnswer(
            call ->
                ((List<Long>) call.getArgument(0))
                    .stream().map(agencies::get).filter(java.util.Objects::nonNull).toList());

    ownTenantAdmin = persistAdmin(OWN_TENANT, AdminType.TENANT);
    foreignTenantAdmin = persistAdmin(FOREIGN_TENANT, AdminType.TENANT);
    callingAgencyAdmin = persistAdmin(OWN_TENANT, AdminType.AGENCY, OWN_AGENCY);
    ownAgencyAdmin = persistAdmin(OWN_TENANT, AdminType.AGENCY, OWN_AGENCY);
    otherAgencyAdmin = persistAdmin(OWN_TENANT, AdminType.AGENCY, OTHER_AGENCY_OF_OWN_TENANT);
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  // --- Träger admin (tenant admin of tenant 1) ---------------------------------------------

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void grant_Should_Refuse_When_TenantAdminTargetsAdminOfAnotherTenant() throws Exception {
    actAsTenantAdmin();

    grant(foreignTenantAdmin, List.of()).andExpect(status().isForbidden());

    assertNoConsultantIdentityGranted(foreignTenantAdmin);
  }

  /**
   * Not a leak on unchanged code: the topic/agency validator already refused agencies outside the
   * target's tenant (400). The caller scope now refuses them first (403).
   */
  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void grant_Should_Refuse_When_TenantAdminAssignsAnAgencyOfAnotherTenant() throws Exception {
    actAsTenantAdmin();

    grant(ownTenantAdmin, List.of(FOREIGN_TENANT_AGENCY)).andExpect(status().isForbidden());

    assertNoConsultantIdentityGranted(ownTenantAdmin);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void grant_Should_Succeed_When_TenantAdminTargetsOwnTenantAdmin() throws Exception {
    actAsTenantAdmin();

    grant(ownTenantAdmin, List.of()).andExpect(status().isOk());

    assertThat(consultantOf(ownTenantAdmin.getId())).isPresent();
  }

  // --- Beratungsstellen admin (restricted agency admin of agency OWN_AGENCY) -------------------

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void grant_Should_Refuse_When_AgencyAdminTargetsAdminOfAnotherAgency() throws Exception {
    actAsAgencyAdmin();

    grant(otherAgencyAdmin, List.of()).andExpect(status().isForbidden());

    assertNoConsultantIdentityGranted(otherAgencyAdmin);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void grant_Should_Refuse_When_AgencyAdminTargetsTraegerAdminWithoutOwnAgency() throws Exception {
    actAsAgencyAdmin();

    grant(ownTenantAdmin, List.of()).andExpect(status().isForbidden());

    assertNoConsultantIdentityGranted(ownTenantAdmin);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void grant_Should_Refuse_When_AgencyAdminAssignsAnotherAgency() throws Exception {
    actAsAgencyAdmin();

    grant(ownAgencyAdmin, List.of(OTHER_AGENCY_OF_OWN_TENANT)).andExpect(status().isForbidden());

    assertNoConsultantIdentityGranted(ownAgencyAdmin);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void grant_Should_Succeed_When_AgencyAdminTargetsAdminOfOwnAgency() throws Exception {
    actAsAgencyAdmin();

    grant(ownAgencyAdmin, List.of()).andExpect(status().isOk());

    assertThat(consultantOf(ownAgencyAdmin.getId())).isPresent();
  }

  // --- Platform admin (tenant 0) ---------------------------------------------------------------

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void grant_Should_Succeed_When_PlatformAdminTargetsAdminOfAnyTenant() throws Exception {
    actAsPlatformAdmin();

    grant(foreignTenantAdmin, List.of()).andExpect(status().isOk());

    assertThat(consultantOf(foreignTenantAdmin.getId())).isPresent();
  }

  private void assertNoConsultantIdentityGranted(Admin target) {
    verify((IdentityRoleUpdater) identityClient, never()).ensureRoles(any(), any());
    assertThat(consultantOf(target.getId())).isEmpty();
  }

  /** Reads the consultant row in the technical context, so the tenant filter hides nothing. */
  private java.util.Optional<de.caritas.cob.userservice.api.model.Consultant> consultantOf(
      String id) {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    return consultantRepository.findByIdAndDeleteDateIsNull(id);
  }

  private ResultActions grant(Admin target, List<Long> agencyIds) throws Exception {
    return mockMvc.perform(
        post("/useradmin/admins/" + target.getId() + "/grant-consultant-identity")
            .cookie(CSRF_COOKIE)
            .header(CSRF_HEADER, CSRF_VALUE)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"agencyIds\":"
                    + agencyIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(",", "[", "]"))
                    + "}"));
  }

  private void actAsTenantAdmin() {
    actAs(
        "tenant-admin-1",
        OWN_TENANT,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdmin() {
    actAs(
        callingAgencyAdmin.getId(),
        OWN_TENANT,
        UserRole.RESTRICTED_AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsPlatformAdmin() {
    actAs("platform-admin", 0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAs(String userId, Long tenantId, UserRole... roles) {
    when(tenantResolverService.resolve(any())).thenReturn(tenantId);
    caller.setUserId(userId);
    caller.setUsername(userId);
    caller.setTenantId(tenantId);
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
    caller.setGrantedAuthorities(Set.of());
  }

  private void givenAgency(long agencyId, long tenantId) {
    var agency = new AgencyDTO().id(agencyId).tenantId(tenantId).consultingType(1);
    when(agencyService.getAgency(agencyId)).thenReturn(agency);
    when(agencyService.getAgencyWithoutCaching(agencyId)).thenReturn(agency);
    agencies.put(agencyId, agency);
  }

  private Admin persistAdmin(long tenantId, AdminType type, Long... agencyIds) {
    var id = UUID.randomUUID().toString();
    var admin =
        adminRepository.save(
            Admin.builder()
                .id(id)
                .tenantId(tenantId)
                .username("grant-scope-" + id.substring(0, 8))
                .firstName("Synthetic")
                .lastName(id.substring(0, 8))
                .email(id.substring(0, 8) + "@synthetic.oriso.test")
                .type(type)
                .build());
    for (Long agencyId : agencyIds) {
      adminAgencyRepository.save(AdminAgency.builder().admin(admin).agencyId(agencyId).build());
    }
    return admin;
  }
}
