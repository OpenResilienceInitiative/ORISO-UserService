package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
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
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refusal tests check the data before the status, so a failure tells whether the endpoint did harm
 * or only answered too politely.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Transactional
@Import(TenantFixtures.class)
@WithTenant(UserAdminAgencyScopeIT.OWN_TENANT)
class UserAdminAgencyScopeIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;
  private static final long OWN_AGENCY = 9301L;
  private static final long OTHER_AGENCY_OF_OWN_TENANT = 9302L;
  private static final long FOREIGN_TENANT_AGENCY = 9401L;
  // Deleted in AgencyService; its relations are orphans (#86).
  private static final long ORPHANED_AGENCY = 9999L;

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private AdminAgencyRepository adminAgencyRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private AgencyInviteLinkRepository agencyInviteLinkRepository;
  @MockitoBean TopicService topicService;

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
  @MockitoBean AgencyAdminService agencyAdminService;
  @MockitoBean ConsultingTypeManager consultingTypeManager;

  /** The authorities a Beratungsstellen admin's realm roles map to (user-admin + restricted). */
  @Retention(RetentionPolicy.RUNTIME)
  @WithMockUser(
      authorities = {
        AuthorityValue.USER_ADMIN,
        AuthorityValue.CONSULTANT_UPDATE,
        AuthorityValue.CONSULTANT_CREATE,
        AuthorityValue.RESTRICTED_AGENCY_ADMIN
      })
  @interface AsAgencyAdmin {}

  /** The authorities a Träger admin's realm roles map to (user-admin + tenant-admin). */
  @Retention(RetentionPolicy.RUNTIME)
  @WithMockUser(
      authorities = {
        AuthorityValue.USER_ADMIN,
        AuthorityValue.CONSULTANT_UPDATE,
        AuthorityValue.CONSULTANT_CREATE,
        AuthorityValue.TENANT_ADMIN
      })
  @interface AsTenantAdmin {}

  private final List<AgencyDTO> knownAgencies = new ArrayList<>();

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Admin callingAgencyAdmin;
  private Consultant ownConsultant;
  private Consultant sharedConsultant;
  private Consultant otherAgencyConsultant;
  private Consultant foreignTenantConsultant;
  private User ownAsker;
  private User otherAgencyAsker;

  @BeforeEach
  void seedTwoAgenciesOfOneTenantAndOneOfAnother() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    when(((IdentityRoleLookup) identityClient).findAllByUserId(anyString()))
        .thenReturn(List.of("user-admin"));
    givenAgency(OWN_AGENCY, OWN_TENANT);
    givenAgency(OTHER_AGENCY_OF_OWN_TENANT, OWN_TENANT);
    givenAgency(FOREIGN_TENANT_AGENCY, FOREIGN_TENANT);
    when(consultingTypeManager.getConsultingTypeSettings(anyInt()))
        .thenReturn(new ExtendedConsultingTypeResponseDTO());
    when(agencyService.getAgenciesWithoutCaching(anyList()))
        .thenAnswer(
            call ->
                knownAgencies.stream()
                    .filter(agency -> ((List<?>) call.getArgument(0)).contains(agency.getId()))
                    .toList());
    when(agencyAdminService.retrieveAllAgencies())
        .thenReturn(
            knownAgencies.stream()
                .map(
                    agency ->
                        new AgencyAdminResponseDTO()
                            .id(agency.getId())
                            .tenantId(agency.getTenantId())
                            .name("Synthetic agency " + agency.getId()))
                .toList());

    callingAgencyAdmin = fixtures.admin(OWN_TENANT, AdminType.AGENCY, OWN_AGENCY);
    ownConsultant = fixtures.consultant(OWN_TENANT, OWN_AGENCY);
    sharedConsultant = fixtures.consultant(OWN_TENANT, OWN_AGENCY, OTHER_AGENCY_OF_OWN_TENANT);
    otherAgencyConsultant = fixtures.consultant(OWN_TENANT, OTHER_AGENCY_OF_OWN_TENANT);
    foreignTenantConsultant = fixtures.consultant(FOREIGN_TENANT, FOREIGN_TENANT_AGENCY);
    ownAsker = fixtures.session(fixtures.adviceSeeker(OWN_TENANT), OWN_AGENCY, null).getUser();
    otherAgencyAsker =
        fixtures
            .session(fixtures.adviceSeeker(OWN_TENANT), OTHER_AGENCY_OF_OWN_TENANT, null)
            .getUser();
  }

  // --- POST /useradmin/agency/{agencyId}/changetype ------------------------------

  @Test
  @AsTenantAdmin
  void changeAgencyType_Should_Refuse_When_TenantAdminChangesAgencyOfAnotherTenant()
      throws Exception {
    actAsTenantAdmin();

    var result = mockMvc.perform(changeTypeToTeam(FOREIGN_TENANT_AGENCY)).andReturn();

    assertThat(isTeamConsultant(foreignTenantConsultant)).isFalse();
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void changeAgencyType_Should_Refuse_When_AgencyAdminChangesAnotherAgencyOfOwnTenant()
      throws Exception {
    actAsAgencyAdmin();

    var result = mockMvc.perform(changeTypeToTeam(OTHER_AGENCY_OF_OWN_TENANT)).andReturn();

    assertThat(isTeamConsultant(otherAgencyConsultant)).isFalse();
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void changeAgencyType_Should_Succeed_When_AgencyAdminChangesOwnAgency() throws Exception {
    actAsAgencyAdmin();

    mockMvc.perform(changeTypeToTeam(OWN_AGENCY)).andExpect(status().isOk());

    assertThat(isTeamConsultant(ownConsultant)).isTrue();
  }

  // --- GET /useradmin/agencies/{agencyId}/consultants ----------------------------

  @Test
  @AsTenantAdmin
  void getAgencyConsultants_Should_Refuse_When_TenantAdminReadsAgencyOfAnotherTenant()
      throws Exception {
    actAsTenantAdmin();

    var result =
        mockMvc
            .perform(get("/useradmin/agencies/" + FOREIGN_TENANT_AGENCY + "/consultants"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(foreignTenantConsultant.getId());
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void getAgencyConsultants_Should_Refuse_When_AgencyAdminReadsAnotherAgencyOfOwnTenant()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(get("/useradmin/agencies/" + OTHER_AGENCY_OF_OWN_TENANT + "/consultants"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(otherAgencyConsultant.getId());
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void getAgencyConsultants_Should_ListConsultants_When_AgencyAdminReadsOwnAgency()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(get("/useradmin/agencies/" + OWN_AGENCY + "/consultants"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).contains(ownConsultant.getId());
  }

  // --- DELETE /useradmin/consultants/{consultantId}/agencies/{agencyId} -----------

  @Test
  @AsAgencyAdmin
  void removeConsultantAgency_Should_Refuse_When_AgencyAdminRemovesAnotherAgency()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(
                withCsrf(
                    delete(
                        "/useradmin/consultants/"
                            + sharedConsultant.getId()
                            + "/agencies/"
                            + OTHER_AGENCY_OF_OWN_TENANT)))
            .andReturn();

    assertThat(activeAgenciesOf(sharedConsultant)).contains(OTHER_AGENCY_OF_OWN_TENANT);
    assertStatus(result, 403);
  }

  @Test
  @AsTenantAdmin
  void removeConsultantAgency_Should_Refuse_When_TenantAdminRemovesAgencyOfAnotherTenant()
      throws Exception {
    actAsTenantAdmin();

    var result =
        mockMvc
            .perform(
                withCsrf(
                    delete(
                        "/useradmin/consultants/"
                            + foreignTenantConsultant.getId()
                            + "/agencies/"
                            + FOREIGN_TENANT_AGENCY)))
            .andReturn();

    assertThat(activeAgenciesOf(foreignTenantConsultant)).contains(FOREIGN_TENANT_AGENCY);
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void removeConsultantAgency_Should_Succeed_When_AgencyAdminRemovesOwnAgency() throws Exception {
    actAsAgencyAdmin();

    mockMvc
        .perform(
            withCsrf(
                delete(
                    "/useradmin/consultants/"
                        + sharedConsultant.getId()
                        + "/agencies/"
                        + OWN_AGENCY)))
        .andExpect(status().isOk());

    assertThat(activeAgenciesOf(sharedConsultant)).containsOnly(OTHER_AGENCY_OF_OWN_TENANT);
  }

  // --- orphaned relations: the agency is gone from AgencyService ------------------------

  @Test
  @AsTenantAdmin
  void removeConsultantAgency_Should_Succeed_When_TenantAdminRemovesAnOrphanedAgency()
      throws Exception {
    actAsTenantAdmin();
    var counsellor = fixtures.consultant(OWN_TENANT, OWN_AGENCY, ORPHANED_AGENCY);

    mockMvc
        .perform(
            withCsrf(
                delete(
                    "/useradmin/consultants/"
                        + counsellor.getId()
                        + "/agencies/"
                        + ORPHANED_AGENCY)))
        .andExpect(status().isOk());

    assertThat(activeAgenciesOf(counsellor)).containsOnly(OWN_AGENCY);
  }

  @Test
  @AsTenantAdmin
  void setConsultantAgencies_Should_Succeed_When_TenantAdminDropsAnOrphanedAgency()
      throws Exception {
    actAsTenantAdmin();
    var counsellor = fixtures.consultant(OWN_TENANT, OWN_AGENCY, ORPHANED_AGENCY);

    mockMvc.perform(setAgencies(counsellor, OWN_AGENCY)).andExpect(status().isOk());

    assertThat(activeAgenciesOf(counsellor)).containsOnly(OWN_AGENCY);
  }

  @Test
  @AsTenantAdmin
  void setConsultantAgencies_Should_Refuse_When_TenantAdminAddsAnOrphanedAgency() throws Exception {
    actAsTenantAdmin();

    var result =
        mockMvc.perform(setAgencies(ownConsultant, OWN_AGENCY, ORPHANED_AGENCY)).andReturn();

    assertThat(activeAgenciesOf(ownConsultant)).containsOnly(OWN_AGENCY);
    assertStatus(result, 403);
  }

  @Test
  @AsTenantAdmin
  void removeConsultantAgency_Should_Refuse_When_TenantAdminRemovesAnExistingForeignAgency()
      throws Exception {
    actAsTenantAdmin();
    var counsellor = fixtures.consultant(OWN_TENANT, OWN_AGENCY, FOREIGN_TENANT_AGENCY);

    var result =
        mockMvc
            .perform(
                withCsrf(
                    delete(
                        "/useradmin/consultants/"
                            + counsellor.getId()
                            + "/agencies/"
                            + FOREIGN_TENANT_AGENCY)))
            .andReturn();

    assertThat(activeAgenciesOf(counsellor)).contains(FOREIGN_TENANT_AGENCY);
    assertStatus(result, 403);
  }

  @Test
  @AsTenantAdmin
  void deleteAdminAgencyRelation_Should_Succeed_When_TenantAdminRemovesAnOrphanedAgency()
      throws Exception {
    actAsTenantAdmin();
    var agencyAdmin = fixtures.admin(OWN_TENANT, AdminType.AGENCY, OWN_AGENCY, ORPHANED_AGENCY);

    mockMvc
        .perform(
            withCsrf(
                delete(
                    "/useradmin/agencyadmins/"
                        + agencyAdmin.getId()
                        + "/agencies/"
                        + ORPHANED_AGENCY)))
        .andExpect(status().isOk());

    assertThat(
            Tenants.acrossAll(
                () ->
                    adminAgencyRepository.findByAdminIdAndAgencyId(
                        agencyAdmin.getId(), ORPHANED_AGENCY)))
        .isEmpty();
  }

  // --- counsellor routes, agency admin vs. a counsellor of another agency ----------

  @Test
  @AsAgencyAdmin
  void setConsultantAgencies_Should_Refuse_When_AgencyAdminReplacesAgenciesOfOtherCounsellor()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(
                withCsrf(
                        put(
                            "/useradmin/consultants/"
                                + otherAgencyConsultant.getId()
                                + "/agencies"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("[{\"agencyId\":" + OWN_AGENCY + "}]"))
            .andReturn();

    assertThat(activeAgenciesOf(otherAgencyConsultant)).containsOnly(OTHER_AGENCY_OF_OWN_TENANT);
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void setConsultantAgencies_Should_Refuse_When_AgencyAdminDropsAnotherAgencyOfSharedCounsellor()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(
                withCsrf(put("/useradmin/consultants/" + sharedConsultant.getId() + "/agencies"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("[{\"agencyId\":" + OWN_AGENCY + "}]"))
            .andReturn();

    assertThat(activeAgenciesOf(sharedConsultant)).contains(OTHER_AGENCY_OF_OWN_TENANT);
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void addConsultantAgency_Should_Refuse_When_AgencyAdminPullsCounsellorOfAnotherAgency()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(
                withCsrf(
                        post(
                            "/useradmin/consultants/"
                                + otherAgencyConsultant.getId()
                                + "/agencies"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"agencyId\":" + OWN_AGENCY + ",\"roleSetKey\":\"main-consultant\"}"))
            .andReturn();

    assertThat(activeAgenciesOf(otherAgencyConsultant)).containsOnly(OTHER_AGENCY_OF_OWN_TENANT);
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void setConsultantAgencies_Should_Succeed_When_AgencyAdminKeepsOtherAgencyOfSharedCounsellor()
      throws Exception {
    actAsAgencyAdmin();

    mockMvc
        .perform(setAgencies(sharedConsultant, OWN_AGENCY, OTHER_AGENCY_OF_OWN_TENANT))
        .andExpect(status().isOk());

    assertThat(activeAgenciesOf(sharedConsultant))
        .containsOnly(OWN_AGENCY, OTHER_AGENCY_OF_OWN_TENANT);
  }

  @Test
  @AsTenantAdmin
  void setConsultantAgencies_Should_Refuse_When_TenantAdminAddsAgencyOfAnotherTenant()
      throws Exception {
    actAsTenantAdmin();

    var result =
        mockMvc.perform(setAgencies(ownConsultant, OWN_AGENCY, FOREIGN_TENANT_AGENCY)).andReturn();

    assertThat(activeAgenciesOf(ownConsultant)).containsOnly(OWN_AGENCY);
    assertStatus(result, 403);
  }

  @Test
  @AsTenantAdmin
  void addConsultantAgency_Should_Refuse_When_TenantAdminAddsAgencyOfAnotherTenant()
      throws Exception {
    actAsTenantAdmin();

    var result =
        mockMvc
            .perform(
                withCsrf(post("/useradmin/consultants/" + ownConsultant.getId() + "/agencies"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"agencyId\":"
                            + FOREIGN_TENANT_AGENCY
                            + ",\"roleSetKey\":\"main-consultant\"}"))
            .andReturn();

    assertThat(activeAgenciesOf(ownConsultant)).containsOnly(OWN_AGENCY);
    assertStatus(result, 403);
  }

  // --- Every /useradmin/consultants/{consultantId}/** route, Träger admin vs. another tenant ---

  static Stream<Arguments> consultantRoutes() {
    return Stream.of(
        route("GET consultant", id -> get("/useradmin/consultants/" + id)),
        route(
            "PUT consultant",
            id ->
                withCsrf(put("/useradmin/consultants/" + id))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"firstname\":\"Renamed\",\"lastname\":\"Counsellor\","
                            + "\"email\":\"renamed@synthetic.oriso.test\","
                            + "\"formalLanguage\":true,\"absent\":false}")),
        route("DELETE consultant", id -> withCsrf(delete("/useradmin/consultants/" + id))),
        route(
            "POST chat-identity",
            id -> withCsrf(post("/useradmin/consultants/" + id + "/chat-identity"))),
        route("GET agencies", id -> get("/useradmin/consultants/" + id + "/agencies")),
        route(
            "POST agencies",
            id ->
                withCsrf(post("/useradmin/consultants/" + id + "/agencies"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"agencyId\":" + OWN_AGENCY + ",\"roleSetKey\":\"main-consultant\"}")),
        route(
            "PUT agencies",
            id ->
                withCsrf(put("/useradmin/consultants/" + id + "/agencies"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("[]")),
        route(
            "DELETE agency",
            id ->
                withCsrf(
                    delete("/useradmin/consultants/" + id + "/agencies/" + FOREIGN_TENANT_AGENCY))),
        route("POST deletion/pause", id -> pauseDeletion("consultants", id)));
  }

  /** The picture routes check access in their own module and hide a foreign counsellor (404). */
  static Stream<Arguments> pictureRoutes() {
    return Stream.of(
        route("GET picture", id -> get("/useradmin/consultants/" + id + "/picture")),
        route(
            "PUT picture",
            id ->
                withCsrf(put("/useradmin/consultants/" + id + "/picture"))
                    .contentType(MediaType.IMAGE_PNG)
                    .content(new byte[] {(byte) 0x89, 'P', 'N', 'G'})),
        route(
            "DELETE picture", id -> withCsrf(delete("/useradmin/consultants/" + id + "/picture"))),
        route(
            "GET picture visibility",
            id -> get("/useradmin/consultants/" + id + "/picture/visibility")),
        route(
            "PUT picture visibility",
            id ->
                withCsrf(put("/useradmin/consultants/" + id + "/picture/visibility"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"internalOnly\":false}")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("consultantRoutes")
  @AsTenantAdmin
  void consultantRoute_Should_Refuse_When_TenantAdminTargetsCounsellorOfAnotherTenant(
      String route, Function<String, MockHttpServletRequestBuilder> request) throws Exception {
    actAsTenantAdmin();
    caller.setGrantedAuthorities(
        Set.of(
            AuthorityValue.USER_ADMIN,
            AuthorityValue.CONSULTANT_UPDATE,
            AuthorityValue.TENANT_ADMIN));
    var before = stateOf(foreignTenantConsultant);

    var result = mockMvc.perform(request.apply(foreignTenantConsultant.getId())).andReturn();

    assertStatus(result, 403);
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(foreignTenantConsultant.getEmail());
    assertThat(stateOf(foreignTenantConsultant)).isEqualTo(before);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pictureRoutes")
  @AsTenantAdmin
  void pictureRoute_Should_Refuse_When_TenantAdminTargetsCounsellorOfAnotherTenant(
      String route, Function<String, MockHttpServletRequestBuilder> request) throws Exception {
    actAsTenantAdmin();
    caller.setGrantedAuthorities(
        Set.of(
            AuthorityValue.USER_ADMIN,
            AuthorityValue.CONSULTANT_UPDATE,
            AuthorityValue.TENANT_ADMIN));
    var before = stateOf(foreignTenantConsultant);

    var result = mockMvc.perform(request.apply(foreignTenantConsultant.getId())).andReturn();

    assertThat(result.getResponse().getStatus()).as("HTTP status").isIn(403, 404);
    assertThat(stateOf(foreignTenantConsultant)).isEqualTo(before);
  }

  @Test
  @AsAgencyAdmin
  void repairChatIdentity_Should_Refuse_When_AgencyAdminTargetsCounsellorOfAnotherAgency()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(
                withCsrf(
                    post(
                        "/useradmin/consultants/"
                            + otherAgencyConsultant.getId()
                            + "/chat-identity")))
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(otherAgencyConsultant.getEmail());
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void getConsultant_Should_Refuse_When_AgencyAdminReadsCounsellorOfAnotherAgency()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc.perform(get("/useradmin/consultants/" + otherAgencyConsultant.getId())).andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(otherAgencyConsultant.getEmail());
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void getConsultant_Should_Succeed_When_AgencyAdminReadsCounsellorOfOwnAgency() throws Exception {
    actAsAgencyAdmin();

    mockMvc
        .perform(get("/useradmin/consultants/" + ownConsultant.getId()))
        .andExpect(status().isOk());
  }

  @Test
  @AsAgencyAdmin
  void updateConsultant_Should_Refuse_When_AgencyAdminUpdatesCounsellorOfAnotherAgency()
      throws Exception {
    actAsAgencyAdmin();

    var result = mockMvc.perform(updateFirstName(otherAgencyConsultant, "Renamed")).andReturn();

    assertThat(firstNameOf(otherAgencyConsultant)).isNotEqualTo("Renamed");
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void getConsultantAgencies_Should_Refuse_When_AgencyAdminReadsCounsellorOfAnotherAgency()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(get("/useradmin/consultants/" + otherAgencyConsultant.getId() + "/agencies"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("Synthetic agency " + OTHER_AGENCY_OF_OWN_TENANT);
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void getConsultantAgencies_Should_Succeed_When_AgencyAdminReadsCounsellorOfOwnAgency()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(get("/useradmin/consultants/" + ownConsultant.getId() + "/agencies"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .contains("Synthetic agency " + OWN_AGENCY);
  }

  @Test
  @AsAgencyAdmin
  void updateConsultant_Should_Succeed_When_AgencyAdminUpdatesCounsellorOfOwnAgency()
      throws Exception {
    actAsAgencyAdmin();

    mockMvc.perform(updateFirstName(ownConsultant, "Renamed")).andExpect(status().isOk());

    assertThat(firstNameOf(ownConsultant)).isEqualTo("Renamed");
  }

  @Test
  @AsAgencyAdmin
  void pauseConsultantDeletion_Should_Refuse_When_AgencyAdminPausesCounsellorOfAnotherAgency()
      throws Exception {
    actAsAgencyAdmin();
    markDeleted(otherAgencyConsultant);

    var result =
        mockMvc.perform(pauseDeletion("consultants", otherAgencyConsultant.getId())).andReturn();

    assertThat(pausedBy(otherAgencyConsultant)).isNull();
    assertStatus(result, 403);
  }

  @Test
  @AsTenantAdmin
  void pauseConsultantDeletion_Should_Refuse_When_TenantAdminPausesCounsellorOfAnotherTenant()
      throws Exception {
    actAsTenantAdmin();
    markDeleted(foreignTenantConsultant);

    var result =
        mockMvc.perform(pauseDeletion("consultants", foreignTenantConsultant.getId())).andReturn();

    assertThat(pausedBy(foreignTenantConsultant)).isNull();
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void pauseConsultantDeletion_Should_Succeed_When_AgencyAdminPausesCounsellorOfOwnAgency()
      throws Exception {
    actAsAgencyAdmin();
    markDeleted(ownConsultant);

    mockMvc.perform(pauseDeletion("consultants", ownConsultant.getId())).andExpect(status().isOk());

    assertThat(pausedBy(ownConsultant)).isEqualTo(callingAgencyAdmin.getId());
  }

  @Test
  @AsAgencyAdmin
  void pauseConsultantDeletion_Should_Refuse_When_DeletedCounsellorHadLeftTheAgencyBefore()
      throws Exception {
    actAsAgencyAdmin();
    markLeft(sharedConsultant, OWN_AGENCY);
    markDeleted(sharedConsultant);

    var result =
        mockMvc.perform(pauseDeletion("consultants", sharedConsultant.getId())).andReturn();

    assertThat(pausedBy(sharedConsultant)).isNull();
    assertStatus(result, 403);
  }

  // --- advice-seeker routes, agency admin vs. an asker of another agency -----------

  @Test
  @AsAgencyAdmin
  void getAsker_Should_Refuse_When_AgencyAdminReadsAskerOfAnotherAgency() throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc.perform(get("/useradmin/askers/" + otherAgencyAsker.getUserId())).andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(otherAgencyAsker.getEmail());
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void getAsker_Should_Succeed_When_AgencyAdminReadsAskerOfOwnAgency() throws Exception {
    actAsAgencyAdmin();

    mockMvc.perform(get("/useradmin/askers/" + ownAsker.getUserId())).andExpect(status().isOk());
  }

  @Test
  @AsAgencyAdmin
  void deleteAsker_Should_Refuse_When_AgencyAdminDeletesAskerOfAnotherAgency() throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(withCsrf(delete("/useradmin/askers/" + otherAgencyAsker.getUserId())))
            .andReturn();

    assertThat(deleteDateOf(otherAgencyAsker)).isNull();
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void pauseAskerDeletion_Should_Refuse_When_AgencyAdminPausesAskerOfAnotherAgency()
      throws Exception {
    actAsAgencyAdmin();
    markDeleted(otherAgencyAsker);

    var result = mockMvc.perform(pauseDeletion("askers", otherAgencyAsker.getUserId())).andReturn();

    assertThat(pausedBy(otherAgencyAsker)).isNull();
    assertStatus(result, 403);
  }

  @Test
  @AsTenantAdmin
  void pauseAskerDeletion_Should_Refuse_When_TenantAdminPausesAskerOfAnotherTenant()
      throws Exception {
    var foreignAsker =
        fixtures
            .session(fixtures.adviceSeeker(FOREIGN_TENANT), FOREIGN_TENANT_AGENCY, null)
            .getUser();
    markDeleted(foreignAsker);
    actAsTenantAdmin();

    var result = mockMvc.perform(pauseDeletion("askers", foreignAsker.getUserId())).andReturn();

    assertThat(pausedBy(foreignAsker)).isNull();
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void pauseAskerDeletion_Should_Succeed_When_AgencyAdminPausesAskerOfOwnAgency() throws Exception {
    actAsAgencyAdmin();
    markDeleted(ownAsker);

    mockMvc.perform(pauseDeletion("askers", ownAsker.getUserId())).andExpect(status().isOk());

    assertThat(pausedBy(ownAsker)).isEqualTo(callingAgencyAdmin.getId());
  }

  // --- who counts as platform ---------------------------------------------------------------

  @Test
  @AsTenantAdmin
  void getConsultants_Should_Refuse_When_Tenant0AdminIsNoPlatformAdmin() throws Exception {
    Tenants.actAs(caller, "tenant-zero-admin", 0L, UserRole.TENANT_ADMIN, UserRole.USER_ADMIN);

    // A list, not one counsellor: tenant 0 lifts the tenant filter, so only the guard stops it.
    var result =
        mockMvc
            .perform(get("/useradmin/consultants").param("page", "1").param("perPage", "5000"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(foreignTenantConsultant.getEmail());
    assertStatus(result, 403);
  }

  @Test
  @AsTenantAdmin
  void getConsultant_Should_Refuse_When_CallerHasNoTenantClaim() throws Exception {
    Tenants.actAs(caller, "tenant-admin-1", OWN_TENANT, UserRole.TENANT_ADMIN, UserRole.USER_ADMIN);
    caller.setTenantId(null);

    var result =
        mockMvc.perform(get("/useradmin/consultants/" + ownConsultant.getId())).andReturn();

    assertThat(result.getResponse().getContentAsString()).doesNotContain(ownConsultant.getEmail());
    assertStatus(result, 403);
  }

  @Test
  @AsTenantAdmin
  void getConsultant_Should_Succeed_When_PlatformAdminReadsCounsellorOfAnyTenant()
      throws Exception {
    Tenants.actAs(
        caller,
        "platform-admin",
        0L,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);

    var result =
        mockMvc
            .perform(get("/useradmin/consultants/" + foreignTenantConsultant.getId()))
            .andReturn();

    assertStatus(result, 200);
  }

  // --- GET /useradmin/agencyadmins ---------------------------------------------------------------

  @Test
  @AsAgencyAdmin
  void getAgencyAdmins_Should_ListOnlyAdminsOfOwnAgencies_When_AgencyAdminListsAll()
      throws Exception {
    actAsAgencyAdmin();
    var colleague = fixtures.admin(OWN_TENANT, AdminType.AGENCY, OWN_AGENCY);
    var otherAgencyAdmin = fixtures.admin(OWN_TENANT, AdminType.AGENCY, OTHER_AGENCY_OF_OWN_TENANT);

    var result =
        mockMvc
            .perform(get("/useradmin/agencyadmins").param("page", "1").param("perPage", "5000"))
            .andReturn();

    var body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain(otherAgencyAdmin.getId());
    assertThat(body).contains(colleague.getId());
    assertStatus(result, 200);
  }

  // --- POST /useradmin/agencyadmins --------------------------------------------------------------

  @Test
  @AsAgencyAdmin
  void createAgencyAdmin_Should_Refuse_When_AgencyAdminCreatesAnotherAgencyAdmin()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(
                withCsrf(
                    post("/useradmin/agencyadmins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"username\":\"peer-admin\",\"firstname\":\"Peer\","
                                + "\"lastname\":\"Admin\",\"email\":\"peer@synthetic.oriso.test\"}")))
            .andReturn();

    verify(identityClient, never()).createUser(any(), any(), any());
    assertStatus(result, 403);
  }

  // --- /useradmin/invitelinks --------------------------------------------------------------------

  @Test
  @AsAgencyAdmin
  void createInviteLink_Should_Refuse_When_AgencyAdminNamesAnotherAgencyOfOwnTenant()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(createInviteLink("{\"agencyId\":" + OTHER_AGENCY_OF_OWN_TENANT + "}"))
            .andReturn();

    assertThat(inviteLinksOf(OTHER_AGENCY_OF_OWN_TENANT)).isEmpty();
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void createInviteLink_Should_Refuse_When_AgencyAdminCreatesLinkForWholeTraeger()
      throws Exception {
    actAsAgencyAdmin();

    var result = mockMvc.perform(createInviteLink("{}")).andReturn();

    assertThat(agencyInviteLinkRepository.findAll()).isEmpty();
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void createInviteLink_Should_Refuse_When_AgencyAdminRoutesToCounsellorOfAnotherAgency()
      throws Exception {
    actAsAgencyAdmin();

    var result =
        mockMvc
            .perform(
                createInviteLink(
                    "{\"agencyId\":"
                        + OWN_AGENCY
                        + ",\"linkKind\":\"COUNSELLOR\",\"consultantId\":\""
                        + otherAgencyConsultant.getId()
                        + "\"}"))
            .andReturn();

    assertThat(agencyInviteLinkRepository.findAll()).isEmpty();
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void createInviteLink_Should_Succeed_When_AgencyAdminNamesOwnAgency() throws Exception {
    actAsAgencyAdmin();

    var result = mockMvc.perform(createInviteLink("{\"agencyId\":" + OWN_AGENCY + "}")).andReturn();

    assertStatus(result, 201);
    assertThat(inviteLinksOf(OWN_AGENCY)).hasSize(1);
  }

  @Test
  @AsAgencyAdmin
  void listInviteLinks_Should_ListOnlyLinksOfOwnAgencies_When_AgencyAdminListsAll()
      throws Exception {
    actAsAgencyAdmin();
    var ownLink = persistInviteLink(OWN_AGENCY);
    var otherAgencyLink = persistInviteLink(OTHER_AGENCY_OF_OWN_TENANT);

    var result = mockMvc.perform(get("/useradmin/invitelinks")).andReturn();

    var body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain(otherAgencyLink.getToken());
    assertThat(body).contains(ownLink.getToken());
    assertStatus(result, 200);
  }

  @Test
  @AsTenantAdmin
  void listInviteLinks_Should_ListLinksOfEveryAgency_When_TenantAdminListsAll() throws Exception {
    actAsTenantAdmin();
    var ownLink = persistInviteLink(OWN_AGENCY);
    var otherAgencyLink = persistInviteLink(OTHER_AGENCY_OF_OWN_TENANT);

    var result = mockMvc.perform(get("/useradmin/invitelinks")).andReturn();

    var body = result.getResponse().getContentAsString();
    assertThat(body).contains(ownLink.getToken(), otherAgencyLink.getToken());
    assertStatus(result, 200);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static void assertStatus(MvcResult result, int expected) {
    assertThat(result.getResponse().getStatus()).as("HTTP status").isEqualTo(expected);
  }

  private static MockHttpServletRequestBuilder changeTypeToTeam(long agencyId) {
    return withCsrf(post("/useradmin/agency/" + agencyId + "/changetype"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"agencyType\":\"TEAM_AGENCY\"}");
  }

  private static MockHttpServletRequestBuilder updateFirstName(
      Consultant consultant, String firstName) {
    return withCsrf(put("/useradmin/consultants/" + consultant.getId()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"firstname\":\""
                + firstName
                + "\",\"lastname\":\"Counsellor\",\"email\":\""
                + consultant.getEmail()
                + "\",\"formalLanguage\":true,\"absent\":false}");
  }

  private static MockHttpServletRequestBuilder pauseDeletion(String kind, String id) {
    return withCsrf(post("/useradmin/" + kind + "/" + id + "/deletion/pause"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"reason\":\"Synthetic legal hold\",\"months\":1}");
  }

  private static Arguments route(
      String name, Function<String, MockHttpServletRequestBuilder> request) {
    return Arguments.of(name, request);
  }

  private static MockHttpServletRequestBuilder setAgencies(
      Consultant consultant, long... agencyIds) {
    return withCsrf(put("/useradmin/consultants/" + consultant.getId() + "/agencies"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            Arrays.stream(agencyIds)
                .mapToObj(id -> "{\"agencyId\":" + id + "}")
                .collect(Collectors.joining(",", "[", "]")));
  }

  /** What a route may change on a counsellor: profile, deletion, chat identity and agencies. */
  private List<Object> stateOf(Consultant consultant) {
    var reloaded = reload(consultant);
    return Arrays.asList(
        reloaded.getFirstName(),
        reloaded.getEmail(),
        reloaded.getDeleteDate(),
        reloaded.getStatus(),
        reloaded.getMatrixUserId(),
        reloaded.getDeletionPausedBy(),
        activeAgenciesOf(consultant));
  }

  private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
    return request.cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  /** Reads in the technical context, so the tenant filter hides nothing. */
  private Consultant reload(Consultant consultant) {
    return Tenants.acrossAll(() -> consultantRepository.findById(consultant.getId()).orElseThrow());
  }

  private User reload(User user) {
    return Tenants.acrossAll(() -> userRepository.findById(user.getUserId()).orElseThrow());
  }

  private boolean isTeamConsultant(Consultant consultant) {
    return reload(consultant).isTeamConsultant();
  }

  private String firstNameOf(Consultant consultant) {
    return reload(consultant).getFirstName();
  }

  private String pausedBy(Consultant consultant) {
    return reload(consultant).getDeletionPausedBy();
  }

  private String pausedBy(User user) {
    return reload(user).getDeletionPausedBy();
  }

  private LocalDateTime deleteDateOf(User user) {
    return reload(user).getDeleteDate();
  }

  private Set<Long> activeAgenciesOf(Consultant consultant) {
    return Tenants.acrossAll(
        () ->
            consultantAgencyRepository
                .findByConsultantIdAndDeleteDateIsNull(consultant.getId())
                .stream()
                .map(ConsultantAgency::getAgencyId)
                .collect(Collectors.toSet()));
  }

  /**
   * A deleted counsellor keeps its agency relations only as soft-deleted rows, stamped with the
   * counsellor's own delete date as the deletion flow does.
   */
  private void markDeleted(Consultant consultant) {
    var deletedAt = LocalDateTime.now().withNano(0);
    Tenants.acrossAll(
        () -> {
          var reloaded = consultantRepository.findById(consultant.getId()).orElseThrow();
          reloaded.setDeleteDate(deletedAt);
          consultantRepository.save(reloaded);
          consultantAgencyRepository
              .findByConsultantIdAndDeleteDateIsNull(consultant.getId())
              .forEach(
                  relation -> {
                    relation.setDeleteDate(deletedAt);
                    consultantAgencyRepository.save(relation);
                  });
        });
  }

  /** The counsellor left the agency some time before being deleted. */
  private void markLeft(Consultant consultant, long agencyId) {
    Tenants.acrossAll(
        () ->
            consultantAgencyRepository
                .findByConsultantIdAndAgencyIdAndDeleteDateIsNull(consultant.getId(), agencyId)
                .forEach(
                    relation -> {
                      relation.setDeleteDate(LocalDateTime.now().minusDays(30).withNano(0));
                      consultantAgencyRepository.save(relation);
                    }));
  }

  private void markDeleted(User user) {
    Tenants.acrossAll(
        () -> {
          var reloaded = userRepository.findById(user.getUserId()).orElseThrow();
          reloaded.setDeleteDate(LocalDateTime.now());
          userRepository.save(reloaded);
        });
  }

  private static MockHttpServletRequestBuilder createInviteLink(String body) {
    return withCsrf(
        post("/useradmin/invitelinks").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private List<AgencyInviteLink> inviteLinksOf(long agencyId) {
    return agencyInviteLinkRepository.findAll().stream()
        .filter(link -> Long.valueOf(agencyId).equals(link.getAgencyId()))
        .toList();
  }

  private AgencyInviteLink persistInviteLink(long agencyId) {
    return agencyInviteLinkRepository.save(
        AgencyInviteLink.builder()
            .token("scope-link-" + UUID.randomUUID())
            .tenantId(OWN_TENANT)
            .agencyId(agencyId)
            .linkKind("TENANT")
            .chatType("LIVE_CHAT")
            .anonymity("FULL")
            .createdByUserId("tenant-admin-1")
            .createDate(LocalDateTime.now())
            .status("ACTIVE")
            .build());
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

  private void givenAgency(long agencyId, long tenantId) {
    var agency =
        new AgencyDTO()
            .id(agencyId)
            .tenantId(tenantId)
            .consultingType(1)
            .teamAgency(false)
            .offline(true);
    when(agencyService.getAgency(agencyId)).thenReturn(agency);
    when(agencyService.getAgencyWithoutCaching(agencyId)).thenReturn(agency);
    knownAgencies.add(agency);
  }
}
