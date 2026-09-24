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

import com.google.common.collect.Lists;
import com.neovisionaries.i18n.LanguageCode;
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
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
class TenantIsolationWithoutTransactionIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;
  private static final long OWN_AGENCY = 9701L;
  private static final long FOREIGN_AGENCY = 9801L;
  private static final String MATRIX_ROOM = "!tenant-isolation:synthetic.oriso.test";

  @Autowired private MockMvc mockMvc;
  @Autowired private AdminRepository adminRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private UserRepository userRepository;
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

  private final List<Session> seededSessions = new ArrayList<>();
  private final List<User> seededAskers = new ArrayList<>();
  private final List<Consultant> seededConsultants = new ArrayList<>();
  private final List<Admin> seededAdmins = new ArrayList<>();

  private Admin ownAgencyAdmin;
  private Admin foreignAgencyAdmin;
  private Consultant ownConsultant;
  private Session ownSession;
  private Session foreignSession;

  @BeforeEach
  void seedOneAgencyAdminAndOneSessionPerTenant() {
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    try {
      ownAgencyAdmin = persistAgencyAdmin(OWN_TENANT);
      foreignAgencyAdmin = persistAgencyAdmin(FOREIGN_TENANT);

      ownConsultant = persistConsultant(OWN_TENANT);
      var foreignConsultant = persistConsultant(FOREIGN_TENANT);
      ownSession = persistAskerWithSession(OWN_TENANT, OWN_AGENCY, anotherConsultantOf(OWN_TENANT));
      foreignSession = persistAskerWithSession(FOREIGN_TENANT, FOREIGN_AGENCY, foreignConsultant);
    } finally {
      TenantContext.clear();
    }
  }

  @AfterEach
  void removeSeededRows() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    try {
      seededSessions.forEach(session -> sessionRepository.deleteById(session.getId()));
      seededAskers.forEach(asker -> userRepository.deleteById(asker.getUserId()));
      seededConsultants.forEach(consultant -> consultantRepository.deleteById(consultant.getId()));
      seededAdmins.forEach(admin -> adminRepository.deleteById(admin.getId()));
    } finally {
      TenantContext.clear();
    }
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
    actAs("tenant-admin-1", OWN_TENANT, UserRole.TENANT_ADMIN, UserRole.USER_ADMIN);

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
    actAs(ownConsultant.getId(), OWN_TENANT, UserRole.CONSULTANT);
    givenConsultantIsInEveryRoom(ownConsultant);

    var result = mockMvc.perform(removeFromSession(foreignSession, ownConsultant)).andReturn();

    verify(groupChatMembershipService, never()).removeMemberFromRoom(anyString(), anyString());
    assertStatus(result, 404);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION})
  void removeFromSession_Should_RemoveFromOwnTenantSession_When_ConsultantPassesItsId()
      throws Exception {
    actAs(ownConsultant.getId(), OWN_TENANT, UserRole.CONSULTANT);
    givenConsultantIsInEveryRoom(ownConsultant);

    var result = mockMvc.perform(removeFromSession(ownSession, ownConsultant)).andReturn();

    assertStatus(result, 204);
    verify(groupChatMembershipService)
        .removeMemberFromRoom(MATRIX_ROOM, ownConsultant.getMatrixUserId());
  }

  @Test
  void getReference_Should_NotReachRowOfAnotherTenant() {
    TenantContext.setCurrentTenant(OWN_TENANT);
    try {
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
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void jpqlJoin_Should_NotReachFilteredRowOfAnotherTenant() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    AdminAgency foreignRelation;
    try {
      foreignRelation =
          adminAgencyRepository.save(
              AdminAgency.builder().admin(foreignAgencyAdmin).agencyId(FOREIGN_AGENCY).build());
    } finally {
      TenantContext.clear();
    }
    TenantContext.setCurrentTenant(OWN_TENANT);
    try {
      assertThat(adminAgencyRepository.findByAdminIdIn(Set.of(foreignAgencyAdmin.getId())))
          .isEmpty();
    } finally {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      adminAgencyRepository.delete(foreignRelation);
      TenantContext.clear();
    }
  }

  @Test
  void supervisionMarkers_Should_NotReachSessionOfAnotherTenant() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    SessionSupervisor foreignSupervision;
    try {
      var supervisor = persistConsultant(FOREIGN_TENANT);
      foreignSupervision =
          sessionSupervisorRepository.save(
              SessionSupervisor.builder()
                  .session(foreignSession)
                  .supervisorConsultant(supervisor)
                  .addedByConsultant(supervisor)
                  .addedDate(java.time.LocalDateTime.now())
                  .isActive(true)
                  .matrixRoomId("!foreign-supervision:synthetic.oriso.test")
                  .build());
    } finally {
      TenantContext.clear();
    }
    TenantContext.setCurrentTenant(OWN_TENANT);
    try {
      assertThat(
              sessionSupervisorRepository.findActiveMarkerRowsBySessionIdIn(
                  Set.of(foreignSession.getId())))
          .isEmpty();
    } finally {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      sessionSupervisorRepository.delete(foreignSupervision);
      TenantContext.clear();
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
    TenantContext.setCurrentTenant(OWN_TENANT);
    try {
      taskExecutor.execute(() -> seen.complete(TenantContext.getCurrentTenant()));
    } finally {
      TenantContext.clear();
    }

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

  private void actAs(String userId, Long tenantId, UserRole... roles) {
    when(tenantResolverService.resolve(any())).thenReturn(tenantId);
    caller.setUserId(userId);
    caller.setUsername(userId);
    caller.setTenantId(tenantId);
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
    caller.setGrantedAuthorities(Set.of());
  }

  /** The session's advisor: somebody other than the consultant who is removed from its room. */
  private Consultant anotherConsultantOf(long tenantId) {
    return persistConsultant(tenantId);
  }

  private Admin persistAgencyAdmin(long tenantId) {
    var id = UUID.randomUUID().toString();
    var admin =
        adminRepository.save(
            Admin.builder()
                .id(id)
                .tenantId(tenantId)
                .username("isolation-" + id.substring(0, 8))
                .firstName("Synthetic")
                .lastName(id.substring(0, 8))
                .email(id.substring(0, 8) + "@synthetic.oriso.test")
                .type(AdminType.AGENCY)
                .build());
    seededAdmins.add(admin);
    return admin;
  }

  private Consultant persistConsultant(long tenantId) {
    var id = UUID.randomUUID().toString();
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(tenantId);
    consultant.setUsername("isolation-" + id.substring(0, 8));
    consultant.setFirstName("Synthetic");
    consultant.setLastName("I" + id.substring(0, 8));
    consultant.setEmail(id.substring(0, 8) + "@synthetic.oriso.test");
    consultant.setAppointments(null);
    consultant.setConsultantAgencies(new HashSet<>());
    consultant.setConsultantMobileTokens(new HashSet<>());
    consultant.setEncourage2fa(true);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(true);
    consultant.setTeamConsultant(false);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setMatrixUserId("@isolation-" + id.substring(0, 8) + ":synthetic.oriso.test");
    var saved = consultantRepository.save(consultant);
    seededConsultants.add(saved);
    return saved;
  }

  private Session persistAskerWithSession(long tenantId, long agencyId, Consultant advisor) {
    var id = UUID.randomUUID().toString();
    var user =
        new User(
            id,
            null,
            "isolation-asker-" + id.substring(0, 8),
            id.substring(0, 8) + "@synthetic.oriso.test",
            false);
    user.setTenantId(tenantId);
    user.setLanguageCode(LanguageCode.de);
    user.setEncourage2fa(true);
    var savedUser = userRepository.save(user);
    seededAskers.add(savedUser);

    var session = new Session();
    session.setUser(savedUser);
    session.setConsultant(advisor);
    session.setTenantId(tenantId);
    session.setAgencyId(agencyId);
    session.setConsultingTypeId(1);
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setPostcode("12345");
    session.setLanguageCode(LanguageCode.de);
    session.setTeamSession(false);
    session.setSessionTopics(Lists.newArrayList());
    session.setIsConsultantDirectlySet(false);
    var savedSession = sessionRepository.save(session);
    seededSessions.add(savedSession);
    return savedSession;
  }
}
