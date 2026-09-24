package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
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
import de.caritas.cob.userservice.api.service.agency.AgencyService;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Not {@code @Transactional}: the chat-identity repair reads the counsellor in a {@code
 * REQUIRES_NEW} transaction, which cannot see rows seeded inside a test transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Import(TenantFixtures.class)
@WithTenant(UserAdminListAndChatIdentityScopeIT.OWN_TENANT)
class UserAdminListAndChatIdentityScopeIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final String ALL = "5000";

  static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;
  private static final long OWN_AGENCY = 9501L;
  private static final long OTHER_AGENCY_OF_OWN_TENANT = 9502L;
  private static final long FOREIGN_TENANT_AGENCY = 9601L;

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
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
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean AgencyAdminService agencyAdminService;
  @MockitoBean ConsultingTypeManager consultingTypeManager;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

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

  private Admin callingAgencyAdmin;
  private Consultant ownConsultant;
  private Consultant otherAgencyConsultant;
  private Consultant foreignTenantConsultant;
  private Consultant ownChatlessConsultant;
  private Consultant otherAgencyChatlessConsultant;
  private Consultant foreignTenantChatlessConsultant;
  private Session ownSession;
  private Session otherAgencySession;
  private Session foreignTenantSession;
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
    ownConsultant = persistConsultant(OWN_TENANT, true, OWN_AGENCY);
    otherAgencyConsultant = persistConsultant(OWN_TENANT, true, OTHER_AGENCY_OF_OWN_TENANT);
    foreignTenantConsultant = persistConsultant(FOREIGN_TENANT, true, FOREIGN_TENANT_AGENCY);
    ownChatlessConsultant = persistConsultant(OWN_TENANT, false, OWN_AGENCY);
    otherAgencyChatlessConsultant =
        persistConsultant(OWN_TENANT, false, OTHER_AGENCY_OF_OWN_TENANT);
    foreignTenantChatlessConsultant =
        persistConsultant(FOREIGN_TENANT, false, FOREIGN_TENANT_AGENCY);

    ownSession = fixtures.session(fixtures.adviceSeeker(OWN_TENANT), OWN_AGENCY, ownConsultant);
    otherAgencySession =
        fixtures.session(
            fixtures.adviceSeeker(OWN_TENANT), OTHER_AGENCY_OF_OWN_TENANT, otherAgencyConsultant);
    otherAgencyAsker = otherAgencySession.getUser();
    foreignTenantSession =
        fixtures.session(
            fixtures.adviceSeeker(FOREIGN_TENANT), FOREIGN_TENANT_AGENCY, foreignTenantConsultant);
  }

  @AfterEach
  void removeSeededRows() {
    fixtures.removeAll();
  }

  // --- GET /useradmin/consultants -----------------------------------------------------------

  @Test
  @AsTenantAdmin
  void getConsultants_Should_NotList_ConsultantsOfAnotherTenant_When_TenantAdminListsAll()
      throws Exception {
    actAsTenantAdmin();

    var body = contentOf(consultantList().param("perPage", ALL));

    assertThat(body).doesNotContain(foreignTenantConsultant.getId());
    assertThat(body).contains(ownConsultant.getId(), otherAgencyConsultant.getId());
  }

  @Test
  @AsTenantAdmin
  void getConsultants_Should_NotList_ConsultantsOfAnotherTenant_When_TenantAdminFiltersByAgency()
      throws Exception {
    actAsTenantAdmin();

    var body =
        contentOf(
            consultantList()
                .param("perPage", ALL)
                .param("agencyId", String.valueOf(FOREIGN_TENANT_AGENCY)));

    assertThat(body).doesNotContain(foreignTenantConsultant.getId());
  }

  @Test
  @AsAgencyAdmin
  void getConsultants_Should_NotList_ConsultantsOfOtherAgencies_When_AgencyAdminListsAll()
      throws Exception {
    actAsAgencyAdmin();

    var body = contentOf(consultantList().param("perPage", ALL));

    assertThat(body)
        .doesNotContain(otherAgencyConsultant.getId())
        .doesNotContain(foreignTenantConsultant.getId());
    assertThat(body).contains(ownConsultant.getId());
  }

  @Test
  @AsAgencyAdmin
  void getConsultants_Should_NotList_ConsultantsOfOtherAgencies_When_AgencyAdminFiltersByThem()
      throws Exception {
    actAsAgencyAdmin();

    var byAgency =
        contentOf(
            consultantList()
                .param("perPage", ALL)
                .param("agencyId", String.valueOf(OTHER_AGENCY_OF_OWN_TENANT)));
    var byLastName =
        contentOf(
            consultantList()
                .param("perPage", ALL)
                .param("lastname", otherAgencyConsultant.getLastName()));

    assertThat(byAgency).doesNotContain(otherAgencyConsultant.getId());
    assertThat(byLastName).doesNotContain(otherAgencyConsultant.getId());
  }

  @Test
  @AsAgencyAdmin
  void getConsultants_Should_List_OwnAgencyConsultant_When_AgencyAdminFiltersByOwnAgency()
      throws Exception {
    actAsAgencyAdmin();

    var body =
        contentOf(
            consultantList().param("perPage", ALL).param("agencyId", String.valueOf(OWN_AGENCY)));

    assertThat(body).contains(ownConsultant.getId());
  }

  // --- GET /useradmin/sessions --------------------------------------------------------------

  @Test
  @AsTenantAdmin
  void getSessions_Should_NotList_SessionsOfAnotherTenant_When_TenantAdminListsAll()
      throws Exception {
    actAsTenantAdmin();

    var body = contentOf(sessionList().param("perPage", ALL));

    assertThat(body).doesNotContain(foreignTenantSession.getUser().getUserId());
    assertThat(body).contains(ownSession.getUser().getUserId(), otherAgencyAsker.getUserId());
  }

  @Test
  @AsTenantAdmin
  void getSessions_Should_NotList_SessionsOfAnotherTenant_When_TenantAdminFiltersByAgency()
      throws Exception {
    actAsTenantAdmin();

    var body =
        contentOf(
            sessionList()
                .param("perPage", ALL)
                .param("agency", String.valueOf(FOREIGN_TENANT_AGENCY)));

    assertThat(body).doesNotContain(foreignTenantSession.getUser().getUserId());
  }

  @Test
  @AsAgencyAdmin
  void getSessions_Should_NotList_SessionsOfOtherAgencies_When_AgencyAdminListsAll()
      throws Exception {
    actAsAgencyAdmin();

    var body = contentOf(sessionList().param("perPage", ALL));

    assertThat(body)
        .doesNotContain(otherAgencyAsker.getUserId())
        .doesNotContain(foreignTenantSession.getUser().getUserId());
    assertThat(body).contains(ownSession.getUser().getUserId());
  }

  @Test
  @AsAgencyAdmin
  void getSessions_Should_NotList_SessionsOfOtherAgencies_When_AgencyAdminFiltersByThem()
      throws Exception {
    actAsAgencyAdmin();

    var byAgency =
        contentOf(
            sessionList()
                .param("perPage", ALL)
                .param("agency", String.valueOf(OTHER_AGENCY_OF_OWN_TENANT)));
    var byAsker =
        contentOf(sessionList().param("perPage", ALL).param("asker", otherAgencyAsker.getUserId()));
    var byConsultant =
        contentOf(
            sessionList().param("perPage", ALL).param("consultant", otherAgencyConsultant.getId()));
    var byConsultingType =
        contentOf(sessionList().param("perPage", ALL).param("consultingType", "1"));

    assertThat(byAgency).doesNotContain(otherAgencyAsker.getUserId());
    assertThat(byAsker).doesNotContain(otherAgencyAsker.getUserId());
    assertThat(byConsultant).doesNotContain(otherAgencyAsker.getUserId());
    assertThat(byConsultingType).doesNotContain(otherAgencyAsker.getUserId());
  }

  @Test
  @AsAgencyAdmin
  void getSessions_Should_List_OwnAgencySession_When_AgencyAdminFilters() throws Exception {
    actAsAgencyAdmin();

    var byAgency =
        contentOf(sessionList().param("perPage", ALL).param("agency", String.valueOf(OWN_AGENCY)));
    var byConsultant =
        contentOf(sessionList().param("perPage", ALL).param("consultant", ownConsultant.getId()));

    assertThat(byAgency).contains(ownSession.getUser().getUserId());
    assertThat(byConsultant).contains(ownSession.getUser().getUserId());
  }

  // --- POST /useradmin/consultants/{id}/chat-identity ---------------------------------------

  @Test
  @AsAgencyAdmin
  void repairChatIdentity_Should_Refuse_When_AgencyAdminRepairsCounsellorOfAnotherAgency()
      throws Exception {
    actAsAgencyAdmin();
    givenChatServerProvisions();

    var result = mockMvc.perform(repairChatIdentity(otherAgencyChatlessConsultant)).andReturn();

    assertThat(matrixUserIdOf(otherAgencyChatlessConsultant)).isNull();
    verify(matrixSynapseService, never())
        .createUserIdWithoutReactivation(
            eq(otherAgencyChatlessConsultant.getUsername()), anyString(), any());
    assertStatus(result, 403);
  }

  @Test
  @AsTenantAdmin
  void repairChatIdentity_Should_Refuse_When_TenantAdminRepairsCounsellorOfAnotherTenant()
      throws Exception {
    actAsTenantAdmin();
    givenChatServerProvisions();

    var result = mockMvc.perform(repairChatIdentity(foreignTenantChatlessConsultant)).andReturn();

    assertThat(matrixUserIdOf(foreignTenantChatlessConsultant)).isNull();
    verify(matrixSynapseService, never())
        .createUserIdWithoutReactivation(
            eq(foreignTenantChatlessConsultant.getUsername()), anyString(), any());
    assertStatus(result, 403);
  }

  @Test
  @AsAgencyAdmin
  void repairChatIdentity_Should_Repair_When_AgencyAdminRepairsOwnCounsellor() throws Exception {
    actAsAgencyAdmin();
    givenChatServerProvisions();

    mockMvc.perform(repairChatIdentity(ownChatlessConsultant)).andExpect(status().isOk());

    assertThat(matrixUserIdOf(ownChatlessConsultant)).isNotNull();
  }

  // --- helpers ------------------------------------------------------------------------------

  private String contentOf(MockHttpServletRequestBuilder request) throws Exception {
    var result = mockMvc.perform(request).andReturn();
    assertStatus(result, 200);
    return result.getResponse().getContentAsString();
  }

  private static MockHttpServletRequestBuilder consultantList() {
    return get("/useradmin/consultants").param("page", "1");
  }

  private static MockHttpServletRequestBuilder sessionList() {
    return get("/useradmin/sessions").param("page", "1");
  }

  private static MockHttpServletRequestBuilder repairChatIdentity(Consultant consultant) {
    return post("/useradmin/consultants/" + consultant.getId() + "/chat-identity")
        .cookie(CSRF_COOKIE)
        .header(CSRF_HEADER, CSRF_VALUE);
  }

  private static void assertStatus(MvcResult result, int expected) {
    assertThat(result.getResponse().getStatus()).as("HTTP status").isEqualTo(expected);
  }

  private void givenChatServerProvisions() throws Exception {
    when(matrixSynapseService.createUserIdWithoutReactivation(anyString(), anyString(), any()))
        .thenAnswer(call -> "@" + call.getArgument(0) + ":synthetic.oriso.test");
  }

  private String matrixUserIdOf(Consultant consultant) {
    return Tenants.acrossAll(
        () -> consultantRepository.findById(consultant.getId()).orElseThrow().getMatrixUserId());
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

  private Consultant persistConsultant(long tenantId, boolean withChatIdentity, Long... agencyIds) {
    var consultant = fixtures.consultant(tenantId, agencyIds);
    if (!withChatIdentity) {
      consultant.setMatrixUserId(null);
      Tenants.in(tenantId, () -> consultantRepository.save(consultant));
    }
    return consultant;
  }
}
