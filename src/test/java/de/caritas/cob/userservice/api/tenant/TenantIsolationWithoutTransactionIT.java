package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
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

/**
 * Tenant isolation without a caller transaction, on loads by id and on threads without a tenant.
 * Not {@code @Transactional} on purpose: controllers run without one in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
@Import(TenantFixtures.class)
@WithTenant(TenantIsolationWithoutTransactionIT.OWN_TENANT)
class TenantIsolationWithoutTransactionIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;
  private static final long OWN_AGENCY = 9701L;
  private static final long FOREIGN_AGENCY = 9801L;
  private static final String MATRIX_ROOM = "!tenant-isolation:synthetic.oriso.test";

  @Autowired private TenantFixtures fixtures;
  @Autowired private MockMvc mockMvc;
  @Autowired private AdminRepository adminRepository;
  @Autowired private SessionRepository sessionRepository;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository
      sessionSupervisorRepository;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.AdminAgencyRepository adminAgencyRepository;

  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
  @Autowired private org.springframework.scheduling.TaskScheduler taskScheduler;

  @Autowired
  private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("taskExecutor")
  private java.util.concurrent.Executor taskExecutor;

  @MockitoBean TenantService tenantService;
  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean AgencyAdminService agencyAdminService;
  @MockitoBean MatrixSynapseService matrixSynapseService;
  @MockitoBean GroupChatMembershipService groupChatMembershipService;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  private Admin ownAgencyAdmin;
  private Admin foreignAgencyAdmin;
  private Consultant ownConsultant;
  private Session ownSession;
  private Session foreignSession;

  @BeforeEach
  void seedOneAgencyAdminAndOneSessionPerTenant() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    ownAgencyAdmin = fixtures.admin(OWN_TENANT, AdminType.AGENCY);
    foreignAgencyAdmin = fixtures.admin(FOREIGN_TENANT, AdminType.AGENCY);

    ownConsultant = fixtures.consultant(OWN_TENANT);
    var foreignConsultant = fixtures.consultant(FOREIGN_TENANT);
    ownSession =
        fixtures.session(
            fixtures.adviceSeeker(OWN_TENANT), OWN_AGENCY, anotherConsultantOf(OWN_TENANT));
    foreignSession =
        fixtures.session(fixtures.adviceSeeker(FOREIGN_TENANT), FOREIGN_AGENCY, foreignConsultant);
  }

  @AfterEach
  void removeSeededRows() {
    fixtures.removeAll();
  }

  // --- a query without a surrounding transaction ------------------------------------------------

  @Test
  @WithMockUser(
      authorities = {
        AuthorityValue.USER_ADMIN,
        AuthorityValue.CONSULTANT_UPDATE,
        AuthorityValue.CONSULTANT_CREATE,
        AuthorityValue.TENANT_ADMIN
      })
  void getAgencyAdmins_Should_NotList_AgencyAdminsOfAnotherTenant_When_TenantAdminListsAll()
      throws Exception {
    Tenants.actAs(caller, "tenant-admin-1", OWN_TENANT, UserRole.TENANT_ADMIN, UserRole.USER_ADMIN);

    var result =
        mockMvc
            .perform(get("/useradmin/agencyadmins").param("page", "1").param("perPage", "5000"))
            .andReturn();

    assertStatus(result, 200);
    var body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain(foreignAgencyAdmin.getId());
    assertThat(body).contains(ownAgencyAdmin.getId());
  }

  // --- a load by primary key --------------------------------------------------------------------

  @Test
  @WithMockUser(authorities = {AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION})
  void removeFromSession_Should_NotFindSessionOfAnotherTenant_When_ConsultantPassesItsId()
      throws Exception {
    Tenants.actAs(caller, ownConsultant.getId(), OWN_TENANT, UserRole.CONSULTANT);
    givenConsultantIsInEveryRoom(ownConsultant);

    var result = mockMvc.perform(removeFromSession(foreignSession, ownConsultant)).andReturn();

    verify(groupChatMembershipService, never()).removeMemberFromRoom(anyString(), anyString());
    assertStatus(result, 404);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION})
  void removeFromSession_Should_RemoveFromOwnTenantSession_When_ConsultantPassesItsId()
      throws Exception {
    Tenants.actAs(caller, ownConsultant.getId(), OWN_TENANT, UserRole.CONSULTANT);
    givenConsultantIsInEveryRoom(ownConsultant);

    var result = mockMvc.perform(removeFromSession(ownSession, ownConsultant)).andReturn();

    assertStatus(result, 204);
    verify(groupChatMembershipService)
        .removeMemberFromRoom(MATRIX_ROOM, ownConsultant.getMatrixUserId());
  }

  @Test
  void getReference_Should_NotReachRowOfAnotherTenant() {
    Tenants.in(
        OWN_TENANT,
        () -> {
          String ownPostcode =
              transactionTemplate.execute(
                  status ->
                      entityManager.getReference(Session.class, ownSession.getId()).getPostcode());
          assertThat(ownPostcode).isEqualTo("12345");
          org.assertj.core.api.Assertions.assertThatThrownBy(
                  () ->
                      transactionTemplate.execute(
                          status ->
                              entityManager
                                  .getReference(Session.class, foreignSession.getId())
                                  .getPostcode()))
              .isInstanceOfAny(
                  jakarta.persistence.EntityNotFoundException.class,
                  org.springframework.orm.jpa.JpaObjectRetrievalFailureException.class);
        });
  }

  @Test
  void jpqlJoin_Should_NotReachFilteredRowOfAnotherTenant() {
    AdminAgency foreignRelation =
        Tenants.acrossAll(
            () -> {
              return adminAgencyRepository.save(
                  AdminAgency.builder().admin(foreignAgencyAdmin).agencyId(FOREIGN_AGENCY).build());
            });
    try {
      Tenants.in(
          OWN_TENANT,
          () -> {
            assertThat(adminAgencyRepository.findByAdminIdIn(Set.of(foreignAgencyAdmin.getId())))
                .isEmpty();
          });
    } finally {
      Tenants.acrossAll(() -> adminAgencyRepository.delete(foreignRelation));
    }
  }

  @Test
  void supervisionMarkers_Should_NotReachSessionOfAnotherTenant() {
    SessionSupervisor foreignSupervision =
        Tenants.acrossAll(
            () -> {
              var supervisor = fixtures.consultant(FOREIGN_TENANT);
              return sessionSupervisorRepository.save(
                  SessionSupervisor.builder()
                      .session(foreignSession)
                      .supervisorConsultant(supervisor)
                      .addedByConsultant(supervisor)
                      .addedDate(java.time.LocalDateTime.now())
                      .isActive(true)
                      .matrixRoomId("!foreign-supervision:synthetic.oriso.test")
                      .build());
            });
    try {
      Tenants.in(
          OWN_TENANT,
          () -> {
            assertThat(
                    sessionSupervisorRepository.findActiveMarkerRowsBySessionIdIn(
                        Set.of(foreignSession.getId())))
                .isEmpty();
          });
    } finally {
      Tenants.acrossAll(() -> sessionSupervisorRepository.delete(foreignSupervision));
    }
  }

  // --- threads without a tenant -----------------------------------------------------------------

  @Test
  void aThreadWithoutTenantReadsNoTenantRows() {
    TenantContext.clear();

    assertThat(
            adminRepository.findAllById(
                List.of(ownAgencyAdmin.getId(), foreignAgencyAdmin.getId())))
        .isEmpty();
    assertThat(sessionRepository.findById(foreignSession.getId())).isEmpty();
  }

  @Test
  void scheduledTasksRunInTheTechnicalTenant() throws Exception {
    var seen = new java.util.concurrent.CompletableFuture<Long>();

    taskScheduler.schedule(
        () -> seen.complete(TenantContext.getCurrentTenant()), java.time.Instant.now());

    assertThat(seen.get(10, java.util.concurrent.TimeUnit.SECONDS))
        .isEqualTo(TenantContext.TECHNICAL_TENANT_ID);
  }

  @Test
  void asyncTasksRunInTheTenantOfTheirCaller() throws Exception {
    var seen = new java.util.concurrent.CompletableFuture<Long>();
    Tenants.in(
        OWN_TENANT,
        () -> taskExecutor.execute(() -> seen.complete(TenantContext.getCurrentTenant())));

    assertThat(seen.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(OWN_TENANT);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      removeFromSession(Session session, Consultant consultant) {
    return delete("/users/sessions/" + session.getId() + "/consultant/" + consultant.getId())
        .cookie(CSRF_COOKIE)
        .header(CSRF_HEADER, CSRF_VALUE);
  }

  private void givenConsultantIsInEveryRoom(Consultant consultant) {
    when(groupChatMembershipService.resolveMatrixRoomId(any(Session.class)))
        .thenReturn(MATRIX_ROOM);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM))
        .thenReturn(
            List.of(
                new ResolvedRoomMember(
                    consultant.getMatrixUserId(),
                    consultant.getId(),
                    consultant.getUsername(),
                    "Synthetic",
                    true)));
  }

  private static void assertStatus(MvcResult result, int expected) {
    assertThat(result.getResponse().getStatus()).as("HTTP status").isEqualTo(expected);
  }

  /** The session's advisor: somebody other than the consultant who is removed from its room. */
  private Consultant anotherConsultantOf(long tenantId) {
    return fixtures.consultant(tenantId);
  }
}
