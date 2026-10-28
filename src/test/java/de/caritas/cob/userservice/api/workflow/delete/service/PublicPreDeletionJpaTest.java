package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.actions.chat.MatrixChatShutdownService;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationRepairService;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationSagaStore;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyDeletionValidationService;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.delete.ConsultantPreDeletionService;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.picture.ConsultantPictureAccess;
import de.caritas.cob.userservice.api.picture.ConsultantPictureStore;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyRevocation;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Actual public services, original repositories and JpaTM. No native acceptance is claimed here.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimWriter.class,
  de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.class,
  ConsultantDeletionContinuationJpaTest.TimeConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublicPreDeletionJpaTest {
  private static final String ACCOUNT = "81000000-0000-4000-8000-000000000011";
  private static final String SECOND = "81000000-0000-4000-8000-000000000015";
  private Clock ownerClock = Clock.systemUTC();
  private Runnable managementReadEffect = () -> {};
  private String expectedNativeAuthority;
  private String nativeCommand = "deactivate";
  private Set<String> nativeRoles;
  private Map<String, Set<String>> removalGroups = Map.of();
  private Map<
          String,
          List<
              de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ReviewedMaintenanceProfiles
                  .Profile>>
      removalProfiles = Map.of();

  private static final String ASKER = "81000000-0000-4000-8000-000000000013";
  private static final String SUCCESSOR = "81000000-0000-4000-8000-000000000014";
  private static final String HUMAN = "81000000-0000-4000-8000-000000000012";
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entities;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private AdminRepository admins;
  @Autowired private AdminAgencyRepository adminAgencies;
  @Autowired private ConsultantAgencyRepository consultantAgencies;
  private AgencyService preflightAgencies;
  @Autowired private UserAgencyRepository userAgencies;
  @Autowired private SessionRepository sessions;
  @Autowired private ChatRepository chats;
  @Autowired private InquiryAcceptanceNoticeRepository acceptanceNotices;
  @Autowired private CaseHandoverRequestRepository handovers;
  @Autowired private GroupChatParticipantRepository participants;
  @Autowired private SessionSupervisorRepository supervisions;
  @Autowired private ConsultantPictureRepository pictures;
  private final AtomicInteger genericAttempts = new AtomicInteger();
  private AuthenticatedUser actor;
  private IdentityClientConfig mode;
  private DeletionLifecycleService lifecycle;
  private UserService userService;
  private AdminScope scope;
  private JdbcTemplate jdbc;
  private static final String REALM = "82000000-0000-4000-8000-000000000011";
  private static final String MACHINE = "82000000-0000-4000-8000-000000000012";
  private static final String ISSUER = "https://identity.test/realms/fixture";
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private final String operation = UUID.randomUUID().toString();
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AtomicInteger nativeReads = new AtomicInteger();
  private final AtomicInteger nativeCalls = new AtomicInteger();
  private final AtomicInteger nativeEffects = new AtomicInteger();
  private final AtomicInteger outcomeReads = new AtomicInteger();
  private AccountLifecycleCodec codec;
  private TaskActorConfiguration actors;
  private TaskActorTokenBroker broker;
  private IdentityManaging passwords;
  private RestTemplate http;
  private MockRestServiceServer server;
  private String savedBody, savedReply;
  private int nativeTerminalStatus;
  private boolean loseReply,
      loseReadReply,
      forgedReceipt,
      unavailableNative,
      unknownOutcome,
      refuseNativeReach;

  private GroupChatMembershipService scopedRemote;
  private final MembersOnlyRevocation memberships = mock(MembersOnlyRevocation.class);
  private final StatisticsService statistics = mock(StatisticsService.class);

  /** The real generic adapter is deliberately closed in C; reaching it cannot prove delivery. */
  private final class ClosedGenericDeactivator implements IdentityDeactivator {
    @Override
    public void deactivateUser(String account) {
      genericAttempts.incrementAndGet();
      throw new ScopedIdentityCommandRequiredException();
    }
  }

  @BeforeEach
  void actualJpaPublicServiceFixtures() {
    assertInstanceOf(JpaTransactionManager.class, transactions);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    TenantContext.clear();
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop table if exists identity_account_operation_intent");
    jdbc.execute("drop table if exists general_admin_identity_intent");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261022_general_admin_identity_367/migrate.sql"))
        .execute(dataSource);
    jdbc.execute(
        "alter table general_admin_identity_intent add column if not exists matrix_result_json varchar(1024) null");
    jdbc.execute("drop table if exists sequential_admin_block");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20260923_user_temporary_account/migrate.sql"),
            new ClassPathResource("db/changelog/changeset/20261007_user_avatar_1240/migrate.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261021_identity_self_locale_367/migrate.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261023_existing_import_intent_367/migrate.sql"))
        .execute(dataSource);

    jdbc.update(
        "delete from session_supervisor where supervisor_consultant_id in (?,?) or session_id in (select id from session where user_id=?)",
        ACCOUNT,
        SUCCESSOR,
        ASKER);
    jdbc.update(
        "delete from group_chat_participant where consultant_id in (?,?)", ACCOUNT, SUCCESSOR);
    jdbc.update("delete from chat where consultant_id_owner in (?,?)", ACCOUNT, SUCCESSOR);
    jdbc.update("delete from session where user_id=?", ASKER);
    jdbc.update("delete from consultant_agency where consultant_id=?", ACCOUNT);
    users.findById(ASKER).ifPresent(users::delete);
    users.findById(SECOND).ifPresent(users::delete);
    consultants.findById(SUCCESSOR).ifPresent(consultants::delete);
    users.findById(ACCOUNT).ifPresent(users::delete);
    consultants.findById(ACCOUNT).ifPresent(consultants::delete);
    jdbc.update("delete from admin_agency where admin_id=?", ACCOUNT);
    admins.findById(ACCOUNT).ifPresent(admins::delete);
    admins.findById(HUMAN).ifPresent(admins::delete);
    actor =
        new AuthenticatedUser(
            HUMAN,
            "operator",
            Set.of("single-tenant-admin", "user-admin"),
            "fixture-human",
            Set.of(
                Authority.AuthorityValue.SINGLE_TENANT_ADMIN, Authority.AuthorityValue.USER_ADMIN));
    actor.setTenantId(7L);
    admins.save(
        Admin.builder()
            .id(HUMAN)
            .tenantId(7L)
            .username("operator")
            .firstName("Fixture")
            .lastName("Operator")
            .email("operator@fixture.invalid")
            .type(Admin.AdminType.TENANT)
            .build());
    preflightAgencies = mock(AgencyService.class);
    when(preflightAgencies.getAgencyWithoutCaching(anyLong()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return new AgencyDTO().offline(true);
            });
    mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    lifecycle = new DeletionLifecycleService();
    org.springframework.test.util.ReflectionTestUtils.setField(
        lifecycle, "globalReadOnlyHours", 48L);
    org.springframework.test.util.ReflectionTestUtils.setField(
        lifecycle, "tenantOverrideConfig", "");
    userService =
        new UserService(
            users,
            mock(UserMobileTokenRepository.class),
            mock(AuditingHandler.class),
            mock(AccountInactivityEnrollmentService.class));
    scope =
        new AdminScope(
            actor,
            admins,
            adminAgencies,
            consultants,
            consultantAgencies,
            mock(AgencyService.class),
            users,
            sessions,
            userAgencies);
    org.springframework.test.util.ReflectionTestUtils.setField(scope, "multitenancyEnabled", true);
    codec = new AccountLifecycleCodec(KEY);
    actors = mock(TaskActorConfiguration.class);
    var machine =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "fixture-secret", MACHINE);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(machine);
    broker = mock(TaskActorTokenBroker.class);
    var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
    when(checked.clientId()).thenReturn(machine.clientId());
    when(checked.subject()).thenReturn(MACHINE);
    when(checked.issuer()).thenReturn(ISSUER);
    when(checked.accessToken()).thenReturn("checked-machine");
    when(checked.validatedJwtIssuedAt())
        .thenAnswer(ignored -> Instant.now().minusSeconds(1).getEpochSecond());
    when(checked.validatedJwtExpiresAt())
        .thenAnswer(ignored -> Instant.now().plusSeconds(60).getEpochSecond());
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
    passwords = mock(IdentityManaging.class);
    when(passwords.validatePasswordIgnoring2fa(anyString(), eq("chosen-original-password")))
        .thenReturn(true);
    http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
  }

  @AfterEach
  void releaseTenant() {
    TenantContext.clear();
  }

  @Test
  void selfAskerStartUsesOwnedAcknowledgementBeforePersistingTheReadOnlyWindow() {
    asker();
    actor.setUserId(ACCOUNT);
    actor.setUsername("pre-deletion");
    actor.setRoles(Set.of("user"));
    actor.setGrantedAuthorities(Set.of("ROLE_USER"));
    authenticate(true, "/service/users/account");
    UserAccountService service = selfService();
    nativeHttp(false);

    assertDoesNotThrow(
        () -> service.deactivateAndFlagUserAccountForDeletion("chosen-original-password"));

    assertEquals(1, nativeEffects.get());
    assertEquals("APPLIED", status());
    assertEquals(0, genericAttempts.get(), "C must never fall back to the generic identity port");
    User saved = users.findById(ACCOUNT).orElseThrow();
    assertNotNull(saved.getDeleteDate());
    assertEquals(DeletionLifecycleState.READ_ONLY_SAFEGUARD, saved.getDeletionLifecycleState());
    assertNotNull(saved.getDeletionReadOnlyUntil());
    verify(statistics, times(1)).fireEvent(any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {403, 409})
  void verifiedCommittedRefusalRetiresOnlyItsOriginalBrowserOperation(int statusCode) {
    asker();
    actor.setUserId(ACCOUNT);
    actor.setUsername("pre-deletion");
    actor.setRoles(Set.of("user"));
    actor.setGrantedAuthorities(Set.of("ROLE_USER"));
    authenticate(true, "/service/users/account");
    var service = selfService();
    nativeTerminalStatus = statusCode;
    nativeHttp(false);
    var advice = new TerminalAdvice();
    var web = new org.springframework.web.context.request.ServletWebRequest(request);
    for (int retry = 0; retry < 2; retry++) {
      var refused =
          assertThrows(
              RuntimeException.class,
              () -> service.deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
      var response =
          statusCode == 403
              ? advice.handleForbidden(assertInstanceOf(ForbiddenException.class, refused), web)
              : advice.conflict(assertInstanceOf(ConflictException.class, refused), web);
      assertEquals(statusCode, response.getStatusCode().value());
      assertEquals("NO_EFFECT", response.getHeaders().getFirst("X-ORISO-Mutation-Outcome"));
      assertEquals(operation, response.getHeaders().getFirst("X-ORISO-Mutation-Operation"));
      assertEquals("no-store", response.getHeaders().getCacheControl());
      assertEquals("REFUSED", status());
      assertEquals(1, nativeCalls.get());
      assertEquals(0, nativeEffects.get());
      assertNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
      verifyNoInteractions(statistics);
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
      var foreignResponse =
          statusCode == 403
              ? advice.handleForbidden((ForbiddenException) refused, web)
              : advice.conflict((ConflictException) refused, web);
      assertNull(foreignResponse.getHeaders().getFirst("X-ORISO-Mutation-Outcome"));
      assertNull(foreignResponse.getHeaders().getFirst("X-ORISO-Mutation-Operation"));
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", operation);
    }
  }

  @Test
  void unsignedNativeRefusalRetainsFrozenOwnershipAndEveryLocalAccountRow() {
    asker();
    actor.setUserId(ACCOUNT);
    actor.setUsername("pre-deletion");
    actor.setRoles(Set.of("user"));
    actor.setGrantedAuthorities(Set.of("ROLE_USER"));
    authenticate(true, "/service/users/account");
    var service = selfService();
    refuseNativeReach = true;
    nativeHttp(false);
    assertThrows(
        ServiceUnavailableException.class,
        () -> service.deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
    assertEquals("FROZEN", status());
    assertEquals(0, nativeEffects.get());
    assertNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
    verifyNoInteractions(statistics);
  }

  private static final class TerminalAdvice
      extends de.caritas.cob.userservice.api.adapters.web.controller.interceptor
          .ApiResponseEntityExceptionHandler {
    org.springframework.http.ResponseEntity<Object> conflict(
        ConflictException refused, org.springframework.web.context.request.WebRequest web) {
      return super.handleCustomConflict(refused, web);
    }
  }

  @Test
  void adminAskerStartUsesActualScopeAndOwnedAcknowledgementBeforeSql() {
    asker();
    authenticate(false, "/service/useradmin/askers/" + ACCOUNT);
    var service =
        new AskerUserAdminFacade(
            new ClosedGenericDeactivator(),
            mock(IdentityReactivationSagaStore.class),
            mock(IdentityReactivationRepairService.class),
            userService,
            new UsernameTranscoder(),
            lifecycle,
            scope);
    service.setIdentityMode(mode);
    service.setScopedPreDeletion(owner());
    nativeHttp(false);

    assertDoesNotThrow(() -> service.markAskerForDeletion(ACCOUNT));

    assertEquals(1, nativeEffects.get());
    assertEquals("APPLIED", status());
    assertEquals(0, genericAttempts.get(), "C must never fall back to the generic identity port");
    User saved = users.findById(ACCOUNT).orElseThrow();
    assertNotNull(saved.getDeleteDate());
    assertEquals(DeletionLifecycleState.READ_ONLY_SAFEGUARD, saved.getDeletionLifecycleState());
    assertNotNull(saved.getDeletionReadOnlyUntil());
  }

  @Test
  void activeSessionPreflightRetiresOnlyOriginalNeverDispatchedConsultantIntent() {
    consultant();
    membershipFixture(true, Session.SessionStatus.IN_PROGRESS);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var advice = new TerminalAdvice();
    var web = new org.springframework.web.context.request.ServletWebRequest(request);
    for (int retry = 0; retry < 2; retry++) {
      var failure =
          assertThrows(
              de.caritas.cob.userservice.api.exception.httpresponses
                  .CustomValidationHttpStatusException.class,
              () ->
                  owner()
                      .startAdminConsultant(
                          ACCOUNT,
                          false,
                          ack -> {
                            throw new de.caritas.cob.userservice.api.exception.httpresponses
                                .CustomValidationHttpStatusException(
                                de.caritas.cob.userservice.api.exception.httpresponses.customheader
                                    .HttpStatusExceptionReason
                                    .CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS);
                          }));
      var response = advice.handleCustomBadRequest(failure, web);
      assertEquals(400, response.getStatusCode().value());
      assertEquals("NO_EFFECT", response.getHeaders().getFirst("X-ORISO-Mutation-Outcome"));
      assertEquals(operation, response.getHeaders().getFirst("X-ORISO-Mutation-Operation"));
      assertEquals("no-store", response.getHeaders().getCacheControl());
      assertEquals(
          "CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS", response.getHeaders().getFirst("X-Reason"));
      assertEquals(0, nativeEffects.get());
      assertEquals(0, nativeCalls.get());
      assertNull(savedBody);
      assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
      var foreign = advice.handleCustomBadRequest(failure, web);
      assertNull(foreign.getHeaders().getFirst("X-ORISO-Mutation-Outcome"));
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", operation);
    }
    String refusedOperation = operation;
    String forceOperation = UUID.randomUUID().toString();
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT, ACCOUNT, forceOperation);
    http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    nativeHttp(true, ACCOUNT, forceOperation);
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            owner()
                .startAdminConsultant(
                    ACCOUNT,
                    true,
                    ack -> {
                      throw new ServiceUnavailableException(
                          "Fixture stops after authorized force acknowledgement");
                    }));
    assertEquals("ACKNOWLEDGED", status(forceOperation));
    assertEquals("PREFLIGHT_REFUSED", status(refusedOperation));
    assertEquals(1, nativeEffects.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void lastAgencyEligibilityRefusesBeforeNativeEvenWithForce(boolean enquiries) {
    consultant();
    if (enquiries) {
      var membership = membershipFixture(true);
      var pending = sessions.findById(membership.session()).orElseThrow();
      pending.setConsultant(null);
      pending.setAgencyId(71L);
      sessions.save(pending);
    }
    consultantAgencies.save(
        ConsultantAgency.builder()
            .consultant(consultants.findById(ACCOUNT).orElseThrow())
            .agencyId(71L)
            .tenantId(7L)
            .createDate(LocalDateTime.of(2024, 1, 2, 3, 4))
            .build());
    when(preflightAgencies.getAgencyWithoutCaching(71L))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("PREPARED", status());
              return new AgencyDTO().offline(enquiries);
            });
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    for (int retry = 0; retry < 2; retry++) {
      var failure =
          assertThrows(
              de.caritas.cob.userservice.api.exception.httpresponses
                  .CustomValidationHttpStatusException.class,
              () ->
                  owner()
                      .startAdminConsultant(ACCOUNT, true, ack -> fail("Must refuse before ACK")));
      var response =
          new TerminalAdvice()
              .handleCustomBadRequest(
                  failure, new org.springframework.web.context.request.ServletWebRequest(request));
      assertEquals("NO_EFFECT", response.getHeaders().getFirst("X-ORISO-Mutation-Outcome"));
      assertEquals(operation, response.getHeaders().getFirst("X-ORISO-Mutation-Operation"));
      assertEquals(
          enquiries
              ? "CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_HAS_OPEN_ENQUIRIES"
              : "CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE",
          response.getHeaders().getFirst("X-Reason"));
      assertEquals(0, nativeEffects.get());
      assertEquals(0, nativeCalls.get());
      assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
    }
    verify(preflightAgencies, times(1)).getAgencyWithoutCaching(71L);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"source", "body"})
  void tamperedPreflightCannotIssueAnAuthenticatedTerminal(String changed) {
    consultant();
    membershipFixture(true, Session.SessionStatus.IN_ARCHIVE);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
            .class,
        () -> owner().startAdminConsultant(ACCOUNT, false, ack -> fail("No ACK")));
    if (changed.equals("body"))
      jdbc.update(
          "update identity_account_operation_intent set intent_body='{}' where operation_id=?",
          operation);
    else {
      String source =
          jdbc.queryForObject(
              "select source_json from identity_account_operation_intent where operation_id=?",
              String.class,
              operation);
      jdbc.update(
          "update identity_account_operation_intent set source_json=? where operation_id=?",
          source.replace(
              "CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS",
              "CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE"),
          operation);
    }
    var refused =
        assertThrows(
            RuntimeException.class,
            () -> owner().startAdminConsultant(ACCOUNT, false, ack -> fail("No ACK")));
    assertFalse(refused instanceof PublicPreDeletionMaintenance.VerifiedTerminal);
    assertEquals(0, nativeEffects.get());
    assertEquals(0, nativeCalls.get());
  }

  @Test
  void sessionValidationAfterPossibleDispatchCannotRetireOriginalIntent() {
    consultant();
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var failure =
        assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class,
            () ->
                owner()
                    .startAdminConsultant(
                        ACCOUNT,
                        false,
                        ack -> {
                          throw new de.caritas.cob.userservice.api.exception.httpresponses
                              .CustomValidationHttpStatusException(
                              de.caritas.cob.userservice.api.exception.httpresponses.customheader
                                  .HttpStatusExceptionReason
                                  .CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS);
                        }));
    var response =
        new TerminalAdvice()
            .handleCustomBadRequest(
                failure, new org.springframework.web.context.request.ServletWebRequest(request));
    assertEquals(400, response.getStatusCode().value());
    assertNull(response.getHeaders().getFirst("X-ORISO-Mutation-Outcome"));
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(1, nativeEffects.get());
  }

  @Test
  void adminConsultantStartUsesOwnedAcknowledgementBeforeTheOriginalPresteps() {
    consultant();
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    ConsultantAdminService service = consultantService();
    nativeHttp(true);

    assertDoesNotThrow(() -> service.markConsultantForDeletion(ACCOUNT, false));

    assertEquals(1, nativeEffects.get());
    assertEquals("APPLIED", status());
    assertEquals(0, genericAttempts.get(), "C must never fall back to the generic identity port");
    Consultant saved = consultants.findById(ACCOUNT).orElseThrow();
    assertNotNull(saved.getDeleteDate());
    assertEquals(DeletionLifecycleState.READ_ONLY_SAFEGUARD, saved.getDeletionLifecycleState());
    assertNotNull(saved.getDeletionReadOnlyUntil());
  }

  @Test
  void restrictedAgencySourceKeepsItsOrdinaryAuthorityThroughConsultantPreDeletion() {
    consultant();
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    actor.setRoles(Set.of("restricted-agency-admin", "user-admin"));
    actor.setGrantedAuthorities(Set.of(Authority.AuthorityValue.USER_ADMIN));
    var operator = admins.findById(HUMAN).orElseThrow();
    assertNotNull(operator.getCreateDate());
    operator.setType(Admin.AdminType.AGENCY);
    admins.save(operator);
    adminAgencies.save(
        de.caritas.cob.userservice.api.model.AdminAgency.builder()
            .admin(operator)
            .agencyId(991L)
            .build());
    consultantAgencies.save(
        ConsultantAgency.builder()
            .consultant(consultants.findById(ACCOUNT).orElseThrow())
            .agencyId(991L)
            .tenantId(7L)
            .build());
    expectedNativeAuthority = "ORDINARY_ACCOUNT_OPERATIONS_V1";
    ConsultantAdminService service = consultantService();
    nativeHttp(true);

    try {
      assertDoesNotThrow(() -> service.markConsultantForDeletion(ACCOUNT, false));
      assertEquals(1, nativeEffects.get());
      assertEquals("APPLIED", status());
      assertEquals(0, genericAttempts.get());
      assertNotNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
    } finally {
      adminAgencies.deleteByAdminId(HUMAN);
    }
  }

  @Test
  void consultantUnknownNativeOutcomeCannotBeginMembershipPictureOrSqlPresteps() {
    consultant();
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    ConsultantAdminService service = consultantService();
    unavailableNative = true;
    nativeHttp(true);

    assertThrows(
        ServiceUnavailableException.class, () -> service.markConsultantForDeletion(ACCOUNT, false));

    verifyNoInteractions(memberships);
    Consultant saved = consultants.findById(ACCOUNT).orElseThrow();
    assertNull(saved.getDeleteDate());
    assertEquals(DeletionLifecycleState.ACTIVE, saved.getDeletionLifecycleState());
  }

  @Test
  void adminAskerLostNativeReplyColdOwnerUsesTheSameFrozenBodyAndOneSqlCompletion() {
    asker();
    authenticate(false, "/service/useradmin/askers/" + ACCOUNT);
    nativeHttp(false);
    loseReply = true;
    var first = adminAskerService();
    assertThrows(ServiceUnavailableException.class, () -> first.markAskerForDeletion(ACCOUNT));
    assertNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertEquals("FROZEN", status());
    assertEquals(1, nativeEffects.get());
    var original = savedBody;
    assertDoesNotThrow(() -> adminAskerService().markAskerForDeletion(ACCOUNT));
    assertEquals(original, savedBody);
    assertEquals(1, nativeEffects.get());
    assertEquals("APPLIED", status());
    var committed = jdbc.queryForMap("select * from user where user_id=?", ACCOUNT);
    assertDoesNotThrow(() -> adminAskerService().markAskerForDeletion(ACCOUNT));
    assertEquals(committed, jdbc.queryForMap("select * from user where user_id=?", ACCOUNT));
  }

  @Test
  void forgedAcknowledgementCannotApplyLocalFlagsOrClearTheFrozenAttempt() {
    asker();
    authenticate(false, "/service/useradmin/askers/" + ACCOUNT);
    forgedReceipt = true;
    nativeHttp(false);
    assertThrows(
        ServiceUnavailableException.class, () -> adminAskerService().markAskerForDeletion(ACCOUNT));
    assertNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertEquals("FROZEN", status());
    assertEquals(1, nativeEffects.get());
  }

  @Test
  void lostReadCannotRebindPreparedCustodyToARecreatedSameUuidSqlIncarnation() {
    asker();
    authenticate(false, "/service/useradmin/askers/" + ACCOUNT);
    loseReadReply = true;
    nativeHttp(false);
    assertThrows(
        ServiceUnavailableException.class, () -> adminAskerService().markAskerForDeletion(ACCOUNT));
    assertEquals("PREPARED", status());
    assertNotNull(
        jdbc.queryForObject(
            "select source_json from identity_account_operation_intent where operation_id=?",
            String.class,
            operation));
    jdbc.update(
        "update user set create_date=DATEADD('DAY',-1,create_date) where user_id=?", ACCOUNT);
    assertThrows(ConflictException.class, () -> adminAskerService().markAskerForDeletion(ACCOUNT));
    assertEquals(1, nativeReads.get());
    assertEquals(0, nativeEffects.get());
    assertNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
  }

  @Test
  void expiredSelfHumanColdRecoveryCanOnlyConsumeExistingNativeOutcomeAndAppliesOnce() {
    asker();
    authenticate(true, "/service/users/account");
    nativeHttp(false);
    loseReply = true;
    var first = selfService();
    assertThrows(
        ServiceUnavailableException.class,
        () -> first.deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
    assertEquals(1, nativeEffects.get());
    assertNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
    request.setUserPrincipal(null);
    request.removeHeader("Authorization");
    actor.setAccessToken("expired-do-not-use");
    assertEquals(1, selfService().resumeSelfAskerDeletions(100));
    assertEquals(1, outcomeReads.get());
    assertEquals(1, nativeCalls.get());
    assertEquals("APPLIED", status());
    assertNotNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
    verify(statistics, times(1)).fireEvent(any());
    assertEquals(0, selfService().resumeSelfAskerDeletions(100));
    verify(statistics, times(1)).fireEvent(any());
  }

  @Test
  void savedOnlySelfOutcomeAbsentRetainsUnknownWithoutAnyFreshWriteOrLocalEffects() {
    asker();
    authenticate(true, "/service/users/account");
    nativeHttp(false);
    unavailableNative = true;
    assertThrows(
        ServiceUnavailableException.class,
        () -> selfService().deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
    unknownOutcome = true;
    request.setUserPrincipal(null);
    assertEquals(0, selfService().resumeSelfAskerDeletions(100));
    assertEquals(1, outcomeReads.get());
    assertEquals(1, nativeCalls.get());
    assertEquals(0, nativeEffects.get());
    assertEquals("FROZEN", status());
    assertNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
    verifyNoInteractions(statistics);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Admin.AdminType.class,
      names = {"AGENCY", "TENANT", "SUPER"})
  void combinedConsultantRemovalPreservesAdministratorAndOwnsOriginalReplay(Admin.AdminType type) {
    givenCombinedConsultantRemoval(type);
    var before = jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT);
    var beforeRelations =
        jdbc.queryForList("select * from admin_agency where admin_id=? order by id", ACCOUNT);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    assertDoesNotThrow(() -> consultantService().markConsultantForDeletion(ACCOUNT, true));
    assertEquals("APPLIED", status());
    assertEquals(before, jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT));
    assertEquals(
        beforeRelations,
        jdbc.queryForList("select * from admin_agency where admin_id=? order by id", ACCOUNT));
    assertNotNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertEquals(
        DeletionLifecycleState.READ_ONLY_SAFEGUARD,
        consultants.findById(ACCOUNT).orElseThrow().getDeletionLifecycleState());
    var expectedChange =
        type == Admin.AdminType.AGENCY
            ? Map.of(
                "transition", "REMOVE_CONSULTANT_IDENTITY", "group", "/human-roles/agency-admin")
            : Map.of(
                "transition",
                "REMOVE_CONSULTANT_IDENTITY",
                "profileId",
                "pure-admin",
                "profileVersion",
                1);
    assertEquals(
        codec.read(codec.canonical(expectedChange), JsonNode.class),
        codec.read(savedBody, JsonNode.class).path("change"));
    assertEquals(1, nativeEffects.get());
    String original = savedBody;
    assertDoesNotThrow(() -> consultantService().markConsultantForDeletion(ACCOUNT, true));
    assertEquals(original, savedBody);
    assertEquals(1, nativeEffects.get());
    assertEquals(0, genericAttempts.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void combinedConsultantRemovalResumesExactNativeOrLocalUnknownAfterRestart(boolean acknowledged) {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    if (acknowledged) {
      assertThrows(
          ServiceUnavailableException.class,
          () ->
              owner()
                  .startAdminConsultant(
                      ACCOUNT,
                      true,
                      ack -> {
                        throw new ServiceUnavailableException(
                            "Fixture process loss after native ACK");
                      }));
    } else {
      loseReply = true;
      assertThrows(
          ServiceUnavailableException.class,
          () -> consultantService().markConsultantForDeletion(ACCOUNT, true));
    }
    assertEquals(acknowledged ? "ACKNOWLEDGED" : "FROZEN", status());
    String original = savedBody;
    var admin = jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT);
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertDoesNotThrow(() -> consultantService().markConsultantForDeletion(ACCOUNT, true));
    assertEquals("APPLIED", status());
    assertEquals(original, savedBody);
    assertEquals(1, nativeEffects.get());
    assertEquals(admin, jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"admin", "admin-agency", "consultant-birth", "force", "human"})
  void combinedOriginalCannotAdoptChangedAdminSourceOrTarget(String changed) {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    loseReply = true;
    assertThrows(
        ServiceUnavailableException.class,
        () -> consultantService().markConsultantForDeletion(ACCOUNT, true));
    int before = nativeCalls.get();
    String original = savedBody;
    switch (changed) {
      case "admin" ->
          jdbc.update("update admin set first_name='Changed' where admin_id=?", ACCOUNT);
      case "admin-agency" ->
          jdbc.update("update admin_agency set agency_id=99 where admin_id=?", ACCOUNT);
      case "consultant-birth" ->
          jdbc.update(
              "update consultant set create_date='2025-01-02 03:04:05' where consultant_id=?",
              ACCOUNT);
      case "human" -> {
        actor.setUserId(SECOND);
        authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
      }
      default -> {}
    }
    assertThrows(
        RuntimeException.class,
        () -> consultantService().markConsultantForDeletion(ACCOUNT, !changed.equals("force")));
    assertEquals(before, nativeCalls.get());
    assertEquals(original, savedBody);
    assertEquals("FROZEN", status());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
  }

  @Autowired
  private de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService
      cleanupClaims;

  @Test
  void completedConsultantOnlyRemovalRetainsAdminThroughScheduledCleanup() {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    var admin = jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT);
    var relations = jdbc.queryForList("select * from admin_agency where admin_id=?", ACCOUNT);
    prepareRetainedCleanup();
    var cleanup = retainedCleanup();
    var callbacks = new AtomicInteger();
    cleanup.configureAcknowledgedCleanup(
        ack -> {
          callbacks.incrementAndGet();
          ack.runOwnedLocal(
              () -> jdbc.update("delete from consultant_agency where consultant_id=?", ACCOUNT));
          ack.completeOwnedLocalDeletion(
              () -> jdbc.update("delete from consultant where consultant_id=?", ACCOUNT));
        });
    cleanup.performDeletionWorkflow();
    assertEquals(1, callbacks.get());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Long.class, ACCOUNT));
    assertEquals(admin, jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT));
    assertEquals(
        relations, jdbc.queryForList("select * from admin_agency where admin_id=?", ACCOUNT));
    assertEquals(1, nativeEffects.get());
    assertEquals(
        "APPLIED",
        jdbc.queryForObject(
            "select status from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT));
  }

  private void prepareRetainedCleanup() {
    jdbc.execute("drop table if exists identity_maintenance_intent");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261021_identity_maintenance_367/migrate.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261022_identity_maintenance_retry_367/migrate.sql"))
        .execute(dataSource);
    jdbc.update("delete from scheduled_task_claim where task_name='account-deletion'");
    // The original delete date is immutable. Only the established retention policy expires.
    org.springframework.test.util.ReflectionTestUtils.setField(
        lifecycle, "globalReadOnlyHours", 0L);
    jdbc.update(
        "update consultant set deletion_read_only_until=delete_date where consultant_id=?",
        ACCOUNT);
  }

  private ScheduledConsultantMaintenance retainedCleanup() {
    var cleanup =
        new ScheduledConsultantMaintenance(
            jdbc,
            transactions,
            cleanupClaims,
            Clock.systemUTC(),
            lifecycle,
            actors,
            broker,
            codec,
            http,
            "https://identity.test",
            "fixture",
            REALM);
    cleanup.configureJpaCleanupOwner(entities);
    cleanup.configureConsultantIdentityRemoval(owner());
    return cleanup;
  }

  @Test
  void consultantOnlyCleanupResumesOriginalAfterLostExternalReplyAndRestart() {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    prepareRetainedCleanup();
    var originalAdmin = jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT);
    var first = retainedCleanup();
    var calls = new AtomicInteger();
    first.configureAcknowledgedCleanup(
        ack -> {
          ack.requireCurrent();
          calls.incrementAndGet();
          throw new IllegalStateException("External reply lost");
        });
    first.performDeletionWorkflow();
    var frozen =
        jdbc.queryForMap(
            "select operation_id,frozen_body,receipt_json from identity_maintenance_intent where subject_id=?",
            ACCOUNT);
    assertEquals(
        "ACKNOWLEDGED",
        jdbc.queryForObject(
            "select status from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT));
    var restarted = retainedCleanup();
    restarted.configureAcknowledgedCleanup(
        ack -> {
          ack.requireCurrent();
          calls.incrementAndGet();
          ack.runOwnedLocal(
              () -> jdbc.update("delete from consultant_agency where consultant_id=?", ACCOUNT));
          ack.completeOwnedLocalDeletion(
              () -> jdbc.update("delete from consultant where consultant_id=?", ACCOUNT));
        });
    restarted.performDeletionWorkflow();
    restarted.performDeletionWorkflow();
    assertEquals(2, calls.get());
    assertEquals(
        frozen,
        jdbc.queryForMap(
            "select operation_id,frozen_body,receipt_json from identity_maintenance_intent where subject_id=?",
            ACCOUNT));
    assertEquals(originalAdmin, jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT));
    assertEquals(1, nativeEffects.get());
    assertEquals(
        "APPLIED",
        jdbc.queryForObject(
            "select status from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"admin", "adminAgency", "consultantBirth", "deleteDate", "receipt", "applied"})
  void retainedCleanupRejectsDriftWithoutAnotherExternalOrNativeEffect(String drift) {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    prepareRetainedCleanup();
    var calls = new AtomicInteger();
    var first = retainedCleanup();
    first.configureAcknowledgedCleanup(
        ack -> {
          ack.requireCurrent();
          calls.incrementAndGet();
          throw new IllegalStateException("External reply lost");
        });
    first.performDeletionWorkflow();
    assertEquals(1, calls.get());
    var frozen =
        jdbc.queryForMap(
            "select operation_id,frozen_body,receipt_json from identity_maintenance_intent where subject_id=?",
            ACCOUNT);
    switch (drift) {
      case "admin" ->
          jdbc.update("update admin set first_name='changed' where admin_id=?", ACCOUNT);
      case "adminAgency" ->
          jdbc.update("update admin_agency set agency_id=72 where admin_id=?", ACCOUNT);
      case "consultantBirth" ->
          jdbc.update(
              "update consultant set create_date=dateadd('SECOND',1,create_date) where consultant_id=?",
              ACCOUNT);
      case "deleteDate" ->
          jdbc.update(
              "update consultant set delete_date=dateadd('SECOND',1,delete_date) where consultant_id=?",
              ACCOUNT);
      case "receipt" ->
          jdbc.update(
              "update identity_account_operation_intent set receipt='{}' where operation_id=?",
              operation);
      case "applied" ->
          jdbc.update(
              "update identity_account_operation_intent set applied_json='{}' where operation_id=?",
              operation);
      default -> fail("unknown test case");
    }
    var restarted = retainedCleanup();
    restarted.configureAcknowledgedCleanup(
        ack -> {
          calls.incrementAndGet();
          fail("Drift cannot reach cleanup");
        });
    restarted.performDeletionWorkflow();
    assertEquals(1, calls.get());
    assertEquals(1, nativeEffects.get());
    assertEquals(
        frozen,
        jdbc.queryForMap(
            "select operation_id,frozen_body,receipt_json from identity_maintenance_intent where subject_id=?",
            ACCOUNT));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Long.class, ACCOUNT));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Admin.AdminType.class,
      names = {"AGENCY", "TENANT"})
  void remainingAdministratorCanBeDeletedSeparatelyWithoutLosingRetainedConsultantCleanup(
      Admin.AdminType type) {
    completeConsultantFirstSequence(type);
  }

  private void completeConsultantFirstSequence(Admin.AdminType type) {
    givenCombinedConsultantRemoval(type);
    jdbc.update("update admin set last_name='Consultant' where admin_id=?", ACCOUNT);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    var retained = jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT);
    String publicOperation = operation;
    String adminOperation = UUID.randomUUID().toString();
    authenticate(
        false,
        "/service/useradmin/"
            + (type == Admin.AdminType.AGENCY ? "agencyadmins/" : "tenantadmins/")
            + ACCOUNT,
        ACCOUNT,
        adminOperation);
    var remove = remainingAdminService();
    remainingAdminHttp(type);
    assertDoesNotThrow(() -> removeRemainingAdmin(remove, type));
    assertEquals(
        0L,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Long.class, ACCOUNT));
    assertEquals(
        retained, jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT));
    assertEquals(
        "APPLIED",
        jdbc.queryForObject(
            "select status from identity_account_operation_intent where operation_id=?",
            String.class,
            publicOperation));
    assertDoesNotThrow(() -> removeRemainingAdmin(remainingAdminService(), type));
    assertEquals(2, nativeEffects.get());
    prepareRetainedCleanup();
    var cleanup = retainedCleanup();
    cleanup.configureAcknowledgedCleanup(
        ack -> {
          ack.runOwnedLocal(
              () -> jdbc.update("delete from consultant_agency where consultant_id=?", ACCOUNT));
          ack.completeOwnedLocalDeletion(
              () -> jdbc.update("delete from consultant where consultant_id=?", ACCOUNT));
        });
    cleanup.performDeletionWorkflow();
    assertEquals(
        0L,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Long.class, ACCOUNT));
    assertEquals(2, nativeEffects.get());
    assertDoesNotThrow(() -> removeRemainingAdmin(remainingAdminService(), type));
    assertEquals(
        2,
        nativeEffects.get(),
        "Exact second removal replay after legitimate purge has no new effect");
  }

  private de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService
      remainingAdminService() {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*)>0 from information_schema.tables where lower(table_name)='general_admin_identity_intent'",
            Boolean.class))) {
      new ResourceDatabasePopulator(
              new ClassPathResource(
                  "db/changelog/changeset/20261022_general_admin_identity_367/migrate.sql"))
          .execute(dataSource);
      jdbc.execute(
          "alter table general_admin_identity_intent add column if not exists matrix_result_json varchar(1024) null");
    }
    var maintenance =
        new de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance(
            mode,
            actor,
            scope,
            jdbc,
            transactions,
            request,
            new de.caritas.cob.userservice.api.adapters.keycloak.maintenance
                .KeycloakHumanAdminMaintenance(
                http, actors, broker, codec, "https://identity.test", "fixture", REALM),
            Clock.systemUTC(),
            ISSUER);
    var proofBeans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    proofBeans.addBean("publicPreDeletionMaintenance", owner());
    maintenance.configureRemovedConsultantProofs(
        proofBeans.getBeanProvider(PublicPreDeletionMaintenance.class));
    maintenance.configureReviewedOrdinaryGroups(removalGroups);
    var profiles =
        new TreeMap<
            String,
            List<
                de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance
                    .ProtectedProfileSelector>>();
    removalProfiles.forEach(
        (kind, values) ->
            profiles.put(
                kind,
                values.stream()
                    .map(
                        value ->
                            new de.caritas.cob.userservice.api.service.identity
                                .HumanAdminAccountMaintenance.ProtectedProfileSelector(
                                value.id(), value.version(), value.realmRoles()))
                    .toList()));
    maintenance.configureReviewedProtectedProfiles(profiles);
    var service =
        new de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService(
            admins,
            adminAgencies,
            mock(de.caritas.cob.userservice.api.port.out.IdentityAccountRemover.class));
    service.setScopedMaintenance(mode, maintenance);
    service.setPublicPreDeletion(owner());
    org.springframework.test.util.ReflectionTestUtils.setField(service, "entities", entities);
    return service;
  }

  private void removeRemainingAdmin(
      de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService service,
      Admin.AdminType type) {
    if (type == Admin.AdminType.AGENCY) service.deleteAgencyAdmin(ACCOUNT);
    else service.deleteTenantAdmin(ACCOUNT);
  }

  private void remainingAdminHttp() {
    remainingAdminHttp(Admin.AdminType.AGENCY);
  }

  private void remainingAdminHttp(Admin.AdminType type) {
    remainingAdminHttp(type, "ADMIN_ONLY");
  }

  private void remainingAdminHttp(Admin.AdminType type, String stage) {
    assertTrue(Set.of("ADMIN_ONLY", "ADMINISTRATOR_IDENTITY", "CONSULTANT_ONLY").contains(stage));
    server.reset();
    savedBody = null;
    savedReply = null;
    nativeCommand = stage.equals("ADMINISTRATOR_IDENTITY") ? "profile-transition" : "delete";
    server
        .expect(manyTimes(), ignored -> {})
        .andRespond(
            raw -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              var mock = (MockClientHttpRequest) raw;
              var body = codec.read(mock.getBodyAsString(), JsonNode.class);
              if (raw.getURI().getPath().endsWith("/read")) {
                var value = new TreeMap<String, Object>();
                value.put("id", ACCOUNT);
                value.put("username", "pre-deletion");
                value.put("email", "consultant@fixture.invalid");
                value.put("firstName", "Fixture");
                value.put("lastName", "Consultant");
                value.put("tenantId", "7");
                value.put("enabled", true);
                value.put("emailVerified", false);
                value.put("preferredLanguage", "en");
                value.put(
                    "effectiveRoles",
                    stage.equals("CONSULTANT_ONLY")
                            || stage.equals("ADMINISTRATOR_IDENTITY") && savedReply != null
                        ? List.of("consultant", "group-chat-consultant")
                        : stage.equals("ADMINISTRATOR_IDENTITY")
                            ? List.of(
                                "consultant",
                                "group-chat-consultant",
                                "restricted-agency-admin",
                                "user-admin")
                            : type == Admin.AdminType.AGENCY
                                ? List.of("restricted-agency-admin", "user-admin")
                                : List.of("tenant-admin"));
                value.put("passwordChangeRequired", false);
                value.put("state", "b".repeat(64));
                if ("TWO_FACTOR_STATUS_V1".equals(body.path("include").asText()))
                  value.put("twoFactor", Map.of("configured", true, "active", true));
                var effect = managementReadEffect;
                managementReadEffect = () -> {};
                effect.run();
                return withSuccess(codec.canonical(value), MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store")
                    .createResponse(raw);
              }
              assertTrue(raw.getURI().getPath().endsWith("/" + nativeCommand));
              nativeCalls.incrementAndGet();
              if (savedBody == null) {
                savedBody = mock.getBodyAsString();
                savedReply = signedReply(body);
                nativeEffects.incrementAndGet();
              } else assertEquals(savedBody, mock.getBodyAsString());
              if (loseReply) {
                loseReply = false;
                throw new ResourceAccessException("Original Admin delete reply lost");
              }
              return withSuccess(savedReply, MediaType.APPLICATION_JSON)
                  .header("Cache-Control", "no-store")
                  .createResponse(raw);
            });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void sequentialUnknownKeepsOriginalCleanupUntilSecondRemovalCommits(boolean localRollback) {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    jdbc.update("update admin set last_name='Consultant' where admin_id=?", ACCOUNT);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    prepareRetainedCleanup();
    var pending = retainedCleanup();
    var callbacks = new AtomicInteger();
    pending.configureAcknowledgedCleanup(
        ack -> {
          ack.requireCurrent();
          callbacks.incrementAndGet();
          throw new IllegalStateException("External cleanup reply lost");
        });
    pending.performDeletionWorkflow();
    assertEquals(1, callbacks.get());
    var originalCleanup =
        jdbc.queryForMap(
            "select operation_id,frozen_body,receipt_json from identity_maintenance_intent where subject_id=?",
            ACCOUNT);
    String adminOperation = UUID.randomUUID().toString();
    authenticate(false, "/service/useradmin/agencyadmins/" + ACCOUNT, ACCOUNT, adminOperation);
    remainingAdminHttp();
    if (localRollback) {
      jdbc.execute(
          "create table sequential_admin_block(admin_id varchar(255) references admin(admin_id))");
      jdbc.update("insert into sequential_admin_block(admin_id) values(?)", ACCOUNT);
    } else loseReply = true;
    assertThrows(RuntimeException.class, () -> remainingAdminService().deleteAgencyAdmin(ACCOUNT));
    assertEquals(
        localRollback ? "ACKNOWLEDGED" : "FROZEN",
        jdbc.queryForObject(
            "select status from general_admin_identity_intent where operation_id=?",
            String.class,
            adminOperation));
    assertEquals(2, nativeEffects.get());
    var originalAdminCommand = savedBody;
    var probe = retainedCleanup();
    probe.configureAcknowledgedCleanup(ack -> callbacks.incrementAndGet());
    probe.performDeletionWorkflow();
    assertEquals(
        1,
        callbacks.get(),
        "Unresolved second deletion must retain the first cleanup's original Consultant");
    assertEquals(
        1L,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Long.class, ACCOUNT));
    if (localRollback) jdbc.execute("drop table sequential_admin_block");
    assertDoesNotThrow(() -> remainingAdminService().deleteAgencyAdmin(ACCOUNT));
    assertEquals(originalAdminCommand, savedBody);
    assertEquals(2, nativeEffects.get());
    var restarted = retainedCleanup();
    restarted.configureAcknowledgedCleanup(
        ack -> {
          callbacks.incrementAndGet();
          ack.runOwnedLocal(
              () -> jdbc.update("delete from consultant_agency where consultant_id=?", ACCOUNT));
          ack.completeOwnedLocalDeletion(
              () -> jdbc.update("delete from consultant where consultant_id=?", ACCOUNT));
        });
    restarted.performDeletionWorkflow();
    assertEquals(2, callbacks.get());
    assertEquals(
        originalCleanup,
        jdbc.queryForMap(
            "select operation_id,frozen_body,receipt_json from identity_maintenance_intent where subject_id=?",
            ACCOUNT));
    assertEquals(
        "APPLIED",
        jdbc.queryForObject(
            "select status from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT));
    assertEquals(2, nativeEffects.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"human", "tenant", "consultantBirth", "administratorBirth", "originalProof"})
  void unknownSequentialAdministratorRemovalCannotBorrowChangedAuthorityOrIncarnation(
      String drift) {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    jdbc.update("update admin set last_name='Consultant' where admin_id=?", ACCOUNT);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    String next = UUID.randomUUID().toString();
    authenticate(false, "/service/useradmin/agencyadmins/" + ACCOUNT, ACCOUNT, next);
    remainingAdminHttp();
    loseReply = true;
    assertThrows(RuntimeException.class, () -> remainingAdminService().deleteAgencyAdmin(ACCOUNT));
    int beforeCalls = nativeCalls.get();
    String original = savedBody;
    switch (drift) {
      case "human" -> {
        actor.setUserId(SECOND);
        authenticate(false, "/service/useradmin/agencyadmins/" + ACCOUNT, ACCOUNT, next);
      }
      case "tenant" -> {
        actor.setTenantId(999L);
        authenticate(false, "/service/useradmin/agencyadmins/" + ACCOUNT, ACCOUNT, next);
      }
      case "consultantBirth" ->
          jdbc.update(
              "update consultant set create_date=dateadd('SECOND',1,create_date) where consultant_id=?",
              ACCOUNT);
      case "administratorBirth" ->
          jdbc.update(
              "update admin set create_date=dateadd('SECOND',1,create_date) where admin_id=?",
              ACCOUNT);
      case "originalProof" ->
          jdbc.update(
              "update identity_account_operation_intent set applied_json='{}' where operation_id=?",
              operation);
      default -> fail("unknown fixture");
    }
    assertThrows(RuntimeException.class, () -> remainingAdminService().deleteAgencyAdmin(ACCOUNT));
    assertEquals(beforeCalls, nativeCalls.get());
    assertEquals(original, savedBody);
    assertEquals(2, nativeEffects.get());
    assertEquals(
        "FROZEN",
        jdbc.queryForObject(
            "select status from general_admin_identity_intent where operation_id=?",
            String.class,
            next));
    assertEquals(
        1L,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Long.class, ACCOUNT));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "missingSecondReceipt",
        "tamperedSecondReceipt",
        "rebornAdmin",
        "rebornConsultant"
      })
  void scheduledAdministratorAbsenceRequiresExactSecondReceiptAndSameIncarnation(String drift) {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    jdbc.update("update admin set last_name='Consultant' where admin_id=?", ACCOUNT);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    authenticate(
        false, "/service/useradmin/agencyadmins/" + ACCOUNT, ACCOUNT, UUID.randomUUID().toString());
    remainingAdminHttp();
    remainingAdminService().deleteAgencyAdmin(ACCOUNT);
    prepareRetainedCleanup();
    if (drift.equals("rebornAdmin")) {
      admins.save(
          Admin.builder()
              .id(ACCOUNT)
              .tenantId(7L)
              .username("pre-deletion")
              .firstName("Fixture")
              .lastName("Consultant")
              .email("consultant@fixture.invalid")
              .type(Admin.AdminType.AGENCY)
              .build());
    } else if (drift.equals("rebornConsultant"))
      jdbc.update(
          "update consultant set create_date=dateadd('SECOND',1,create_date) where consultant_id=?",
          ACCOUNT);
    else {
      var applied =
          codec.read(
              jdbc.queryForObject(
                  "select applied_json from identity_account_operation_intent where operation_id=?",
                  String.class,
                  operation),
              com.fasterxml.jackson.databind.node.ObjectNode.class);
      if (drift.equals("missingSecondReceipt")) applied.remove("administratorDeletion");
      else
        ((com.fasterxml.jackson.databind.node.ObjectNode) applied.path("administratorDeletion"))
            .put("receipt", "{}");
      jdbc.update(
          "update identity_account_operation_intent set applied_json=? where operation_id=?",
          codec.canonical(applied),
          operation);
    }
    var cleanup = retainedCleanup();
    var callbacks = new AtomicInteger();
    cleanup.configureAcknowledgedCleanup(ack -> callbacks.incrementAndGet());
    cleanup.performDeletionWorkflow();
    assertEquals(0, callbacks.get());
    assertEquals(2, nativeEffects.get());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Long.class, ACCOUNT));
  }

  @Test
  void administratorFirstThenConsultantDeletionKeepsBothOriginalOperationsAndNativeCleanup() {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    jdbc.update("update admin set last_name='Consultant' where admin_id=?", ACCOUNT);
    String first = UUID.randomUUID().toString();
    authenticate(false, "/service/useradmin/agencyadmins/" + ACCOUNT, ACCOUNT, first);
    remainingAdminHttp(Admin.AdminType.AGENCY, "ADMINISTRATOR_IDENTITY");
    remainingAdminService().deleteAgencyAdmin(ACCOUNT);
    assertEquals(
        0L,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Long.class, ACCOUNT));
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertEquals(1, nativeEffects.get());
    server.reset();
    savedBody = null;
    savedReply = null;
    nativeCommand = "deactivate";
    nativeRoles = Set.of("consultant", "group-chat-consultant");
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    assertEquals(2, nativeEffects.get());
    assertEquals("APPLIED", status());
    prepareRetainedCleanup();
    remainingAdminHttp(Admin.AdminType.AGENCY, "CONSULTANT_ONLY");
    var cleanup = retainedCleanup();
    cleanup.configureAcknowledgedCleanup(
        ack -> {
          ack.runOwnedLocal(
              () -> jdbc.update("delete from consultant_agency where consultant_id=?", ACCOUNT));
          ack.completeOwnedLocalDeletion(
              () -> jdbc.update("delete from consultant where consultant_id=?", ACCOUNT));
        });
    cleanup.performDeletionWorkflow();
    assertEquals(3, nativeEffects.get());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Long.class, ACCOUNT));
    assertEquals(
        "APPLIED",
        jdbc.queryForObject(
            "select status from general_admin_identity_intent where operation_id=?",
            String.class,
            first));
    assertEquals(
        "APPLIED",
        jdbc.queryForObject(
            "select status from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "scheduledStatus",
        "scheduledBody",
        "scheduledReceipt",
        "secondReceipt",
        "rebornConsultant",
        "withdrawnAuthority",
        "differentOperation"
      })
  void postPurgeReplayCannotBorrowMissingProofRecreatedRowsOrWithdrawnAuthority(String change) {
    completeConsultantFirstSequence(Admin.AdminType.AGENCY);
    int calls = nativeCalls.get();
    switch (change) {
      case "scheduledStatus" ->
          jdbc.update(
              "update identity_maintenance_intent set status='ACKNOWLEDGED' where subject_id=?",
              ACCOUNT);
      case "scheduledBody" ->
          jdbc.update(
              "update identity_maintenance_intent set frozen_body='{}' where subject_id=?",
              ACCOUNT);
      case "scheduledReceipt" ->
          jdbc.update(
              "update identity_maintenance_intent set receipt_json='{}' where subject_id=?",
              ACCOUNT);
      case "secondReceipt" -> {
        var applied =
            codec.read(
                jdbc.queryForObject(
                    "select applied_json from identity_account_operation_intent where operation_id=?",
                    String.class,
                    operation),
                com.fasterxml.jackson.databind.node.ObjectNode.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) applied.path("administratorDeletion"))
            .put("receipt", "{}");
        jdbc.update(
            "update identity_account_operation_intent set applied_json=? where operation_id=?",
            codec.canonical(applied),
            operation);
      }
      case "rebornConsultant" -> {
        consultant();
        jdbc.update(
            "update consultant set create_date='2027-01-01 00:00:00' where consultant_id=?",
            ACCOUNT);
      }
      case "withdrawnAuthority" -> actor.setGrantedAuthorities(Set.of());
      case "differentOperation" -> {
        request.removeHeader("Idempotency-Key");
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
      }
      default -> fail("unknown fixture");
    }
    assertThrows(RuntimeException.class, () -> remainingAdminService().deleteAgencyAdmin(ACCOUNT));
    assertEquals(calls, nativeCalls.get());
    assertEquals(2, nativeEffects.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Admin.AdminType.class,
      names = {"AGENCY", "TENANT"})
  void remainingAdministratorRetainsAuthenticatedSelfOtpReadAfterConsultantRemoval(
      Admin.AdminType type) {
    var self = remainingAdminSelfFixture(type);
    var retained = jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT);
    var profile = assertDoesNotThrow(self::readCurrentProfile);
    assertEquals(ACCOUNT, profile.id());
    assertEquals("Fixture", profile.firstName());
    assertEquals("Admin", profile.lastName());
    assertTrue(assertDoesNotThrow(self::readCredential).setup());
    assertEquals(
        retained, jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT));
    assertEquals(1, nativeEffects.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "missing-proof",
        "consultant-birth",
        "delete-date",
        "admin-birth",
        "missing-admin",
        "native-combined",
        "foreign-human",
        "no-owner"
      })
  void remainingAdminMetadataRejectsUnprovedOrRecreatedIdentity(String changed) {
    var self = remainingAdminSelfFixture(Admin.AdminType.AGENCY);
    switch (changed) {
      case "missing-proof" ->
          jdbc.update("delete from identity_account_operation_intent where account_id=?", ACCOUNT);
      case "consultant-birth" ->
          jdbc.update(
              "update consultant set create_date=dateadd('SECOND',1,create_date) where consultant_id=?",
              ACCOUNT);
      case "delete-date" ->
          jdbc.update(
              "update consultant set delete_date=dateadd('SECOND',1,delete_date) where consultant_id=?",
              ACCOUNT);
      case "admin-birth" ->
          jdbc.update(
              "update admin set create_date=dateadd('SECOND',1,create_date) where admin_id=?",
              ACCOUNT);
      case "missing-admin" -> {
        jdbc.update("delete from admin_agency where admin_id=?", ACCOUNT);
        jdbc.update("delete from admin where admin_id=?", ACCOUNT);
      }
      case "native-combined" ->
          actor.setRoles(Set.of("consultant", "restricted-agency-admin", "user-admin"));
      case "foreign-human" -> actor.setUserId(HUMAN);
      case "no-owner" ->
          org.springframework.test.util.ReflectionTestUtils.setField(
              self, "removedConsultants", null);
      default -> throw new AssertionError(changed);
    }
    assertThrows(RuntimeException.class, self::readCurrentProfile);
    assertEquals(1, nativeEffects.get());
  }

  private de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAppOtpMaintenance
      remainingAdminSelfFixture(Admin.AdminType type) {
    return remainingAdminSelfFixture(type, false);
  }

  private de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAppOtpMaintenance
      remainingAdminSelfFixture(Admin.AdminType type, boolean passwordRequired) {
    givenCombinedConsultantRemoval(type);
    if (passwordRequired)
      jdbc.update(
          "update consultant set password_change_required=true where consultant_id=?", ACCOUNT);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    actor.setUserId(ACCOUNT);
    actor.setUsername("pre-deletion");
    actor.setTenantId(7L);
    actor.setRoles(
        type == Admin.AdminType.AGENCY
            ? Set.of("restricted-agency-admin", "user-admin")
            : Set.of("tenant-admin"));
    authenticate(false, "/service/users/data");
    request.setMethod("GET");
    var machine = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    when(actors.require(TaskPurpose.OTP)).thenReturn(machine);
    var token = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(token.purpose()).thenReturn(TaskPurpose.OTP);
    when(token.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(token.clientId()).thenReturn(machine.clientId());
    when(token.subject()).thenReturn(MACHINE);
    when(token.issuer()).thenReturn(ISSUER);
    when(token.accessToken()).thenReturn("checked-otp");
    when(token.validatedJwtExpiresAt())
        .thenAnswer(ignored -> Instant.now().plusSeconds(60).getEpochSecond());
    when(broker.issueCheckedMaintenance(TaskPurpose.OTP)).thenReturn(token);
    server.reset();
    server
        .expect(manyTimes(), ignored -> {})
        .andRespond(
            raw -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(
                  "Bearer fixture-human", raw.getHeaders().getFirst("X-ORISO-Human-Authorization"));
              if (raw.getMethod() == HttpMethod.GET) {
                assertTrue(raw.getURI().getPath().contains("/otp-config/fetch-otp-setup-info/"));
                assertEquals("Bearer checked-otp", raw.getHeaders().getFirst("Authorization"));
                return withSuccess(
                        "{\"otpSetup\":true,\"otpType\":\"APP\"}", MediaType.APPLICATION_JSON)
                    .createResponse(raw);
              }
              assertTrue(raw.getURI().getPath().endsWith("/read"));
              var value = new TreeMap<String, Object>();
              value.put("id", ACCOUNT);
              value.put("username", "pre-deletion");
              value.put("email", "consultant@fixture.invalid");
              value.put("firstName", "Fixture");
              value.put("lastName", "Admin");
              value.put("enabled", true);
              value.put("emailVerified", false);
              value.put("tenantId", "7");
              value.put("preferredLanguage", "en");
              value.put("effectiveRoles", actor.getRoles());
              value.put("passwordChangeRequired", false);
              value.put("state", "a".repeat(64));
              return withSuccess(codec.canonical(value), MediaType.APPLICATION_JSON)
                  .header("Cache-Control", "no-store")
                  .createResponse(raw);
            });
    var self =
        new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAppOtpMaintenance(
            mode,
            actor,
            request,
            jdbc,
            transactions,
            actors,
            broker,
            codec,
            http,
            Clock.systemUTC(),
            "https://identity.test",
            "fixture",
            REALM,
            ISSUER);
    self.configureRemovedConsultantProofs(owner());
    return self;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Admin.AdminType.class,
      names = {"AGENCY", "TENANT"})
  void remainingAdministratorKeepsExistingAuthorizedManagementRead(Admin.AdminType type) {
    givenCombinedConsultantRemoval(type);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    remainingAdminHttp(type);
    request.setMethod("GET");
    request.setRequestURI("/service/useradmin/accounts/" + ACCOUNT + "/status");
    var deletion = remainingAdminService();
    var maintenance =
        (de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance)
            org.springframework.test.util.ReflectionTestUtils.getField(deletion, "maintenance");
    var before = jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT);
    assertEquals(ACCOUNT, assertDoesNotThrow(() -> maintenance.read(ACCOUNT)).id());
    assertTrue(assertDoesNotThrow(() -> maintenance.factorStatus(ACCOUNT)).active());
    var status = remainingAdminStatus(maintenance);
    assertTrue(assertDoesNotThrow(() -> status.read(ACCOUNT)).active());
    assertTrue(status.activeForList(ACCOUNT, true, new java.util.HashMap<>()));
    assertFalse(status.read(ACCOUNT).lifecycleRestricted());
    assertFalse(status.read(ACCOUNT).absent());
    assertThrows(RuntimeException.class, () -> status.update(ACCOUNT, false));
    assertEquals(
        before, jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT));
    assertEquals(1, nativeEffects.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"admin-birth", "delete-date"})
  void survivingAdminReadRevalidatesInverseAfterNativeReply(String changed) {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    remainingAdminHttp();
    request.setMethod("GET");
    request.setRequestURI("/service/useradmin/accounts/" + ACCOUNT + "/status");
    var maintenance =
        (de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance)
            org.springframework.test.util.ReflectionTestUtils.getField(
                remainingAdminService(), "maintenance");
    var reads = new AtomicInteger();
    managementReadEffect =
        () -> {
          reads.incrementAndGet();
          if (changed.equals("admin-birth"))
            jdbc.update(
                "update admin set create_date=dateadd('SECOND',1,create_date) where admin_id=?",
                ACCOUNT);
          else
            jdbc.update(
                "update consultant set delete_date=dateadd('SECOND',1,delete_date) where consultant_id=?",
                ACCOUNT);
        };
    assertThrows(RuntimeException.class, () -> maintenance.read(ACCOUNT));
    assertEquals(1, reads.get());
    assertEquals(1, nativeEffects.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Admin.AdminType.class,
      names = {"AGENCY", "TENANT"})
  void survivingAdminCanChangeOwnPasswordWithoutClearingRetainedConsultant(Admin.AdminType type) {
    var password = remainingAdminPasswordFixture(type);
    var retained = jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT);
    var callbacks = new AtomicInteger();
    assertTrue(
        assertDoesNotThrow(
            () ->
                password.change(
                    "Synthetic-old-password",
                    "Synthetic-new-password",
                    () -> {
                      callbacks.incrementAndGet();
                      jdbc.update(
                          "update consultant set password_change_required=false where consultant_id=?",
                          ACCOUNT);
                    })));
    assertEquals(0, callbacks.get(), "Pure active Admin cannot clear the removed Consultant setup");
    assertEquals(
        retained, jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT));
    assertEquals(2, nativeEffects.get());
  }

  @Test
  void survivingAdminPasswordUnknownReplyRestartsOriginalAttemptAndNeverClearsRetainedData() {
    var password = remainingAdminPasswordFixture(Admin.AdminType.AGENCY);
    var retained = jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT);
    loseReply = true;
    assertThrows(
        RuntimeException.class,
        () ->
            password.change(
                "Synthetic-old-password",
                "Synthetic-new-password",
                () -> fail("Removed Consultant callback")));
    var original =
        jdbc.queryForMap(
            "select operation_id,request_digest,target_json,metadata_json,intent_envelope from identity_self_password_intent");
    String originalBody = savedBody;
    var restarted = restartedRemainingAdminPassword();
    assertTrue(
        restarted.change(
            "Synthetic-old-password",
            "Synthetic-new-password",
            () -> fail("Removed Consultant callback")));
    assertTrue(
        restartedRemainingAdminPassword()
            .change(
                "Synthetic-old-password",
                "Synthetic-new-password",
                () -> fail("Replayed callback")));
    assertEquals(originalBody, savedBody);
    assertEquals(
        original,
        jdbc.queryForMap(
            "select operation_id,request_digest,target_json,metadata_json,intent_envelope from identity_self_password_intent"));
    assertEquals(
        "ACKNOWLEDGED",
        jdbc.queryForObject("select status from identity_self_password_intent", String.class));
    assertEquals(
        retained, jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT));
    assertEquals(2, nativeEffects.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "missing-proof",
        "consultant-birth",
        "admin-birth",
        "delete-date",
        "foreign-human"
      })
  void survivingAdminPasswordRequiresOriginalRemovedIdentityProof(String changed) {
    var password = remainingAdminPasswordFixture(Admin.AdminType.AGENCY);
    switch (changed) {
      case "missing-proof" ->
          jdbc.update("delete from identity_account_operation_intent where account_id=?", ACCOUNT);
      case "consultant-birth" ->
          jdbc.update(
              "update consultant set create_date=dateadd('SECOND',1,create_date) where consultant_id=?",
              ACCOUNT);
      case "admin-birth" ->
          jdbc.update(
              "update admin set create_date=dateadd('SECOND',1,create_date) where admin_id=?",
              ACCOUNT);
      case "delete-date" ->
          jdbc.update(
              "update consultant set delete_date=dateadd('SECOND',1,delete_date) where consultant_id=?",
              ACCOUNT);
      case "foreign-human" -> actor.setUserId(HUMAN);
      default -> throw new AssertionError(changed);
    }
    assertThrows(
        RuntimeException.class,
        () ->
            password.change(
                "Synthetic-old-password", "Synthetic-new-password", () -> fail("No callback")));
    assertEquals(
        0L, jdbc.queryForObject("select count(*) from identity_self_password_intent", Long.class));
    assertEquals(1, nativeEffects.get());
  }

  @Test
  void survivingAdminPasswordUnknownKeepsCustodyWhenRemovedIdentityProofChanges() {
    var password = remainingAdminPasswordFixture(Admin.AdminType.AGENCY);
    loseReply = true;
    assertThrows(
        RuntimeException.class,
        () ->
            password.change(
                "Synthetic-old-password", "Synthetic-new-password", () -> fail("No callback")));
    var original = jdbc.queryForMap("select * from identity_self_password_intent");
    int calls = nativeCalls.get();
    jdbc.update(
        "update consultant set create_date=dateadd('SECOND',1,create_date) where consultant_id=?",
        ACCOUNT);
    assertThrows(
        RuntimeException.class,
        () ->
            restartedRemainingAdminPassword()
                .change(
                    "Synthetic-old-password", "Synthetic-new-password", () -> fail("No callback")));
    assertEquals(original, jdbc.queryForMap("select * from identity_self_password_intent"));
    assertEquals(calls, nativeCalls.get());
    assertEquals(2, nativeEffects.get());
  }

  private de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfPasswordMaintenance
      remainingAdminPasswordFixture(Admin.AdminType type) {
    remainingAdminSelfFixture(type, true);
    jdbc.execute("drop table if exists identity_self_password_intent");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261022_identity_self_password_367/migrate.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261023_identity_self_password_retention_367/migrate.sql"))
        .execute(dataSource);
    request.setMethod("PUT");
    request.setRequestURI("/service/users/password/change");
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
    remainingAdminHttp(type);
    nativeCommand = "self-password";
    return restartedRemainingAdminPassword();
  }

  private de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfPasswordMaintenance
      restartedRemainingAdminPassword() {
    var password =
        new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfPasswordMaintenance(
            mode,
            actor,
            request,
            jdbc,
            transactions,
            actors,
            broker,
            codec,
            http,
            Clock.systemUTC(),
            "https://identity.test",
            ISSUER,
            "fixture",
            REALM);
    password.configureRemovedConsultantProofs(owner());
    return password;
  }

  private de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService
      remainingAdminStatus(
          de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance
              maintenance) {
    var lifecycle =
        mock(
            de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
                .class);
    when(lifecycle.snapshot(ACCOUNT))
        .thenReturn(
            Optional.of(
                new de.caritas.cob.userservice.api.workflow.accountinactivity
                    .AccountInactivityService.Snapshot(
                    ACCOUNT,
                    7L,
                    6,
                    1L,
                    Instant.now(),
                    Instant.now().plusSeconds(999),
                    de.caritas.cob.userservice.api.workflow.accountinactivity
                        .AccountInactivityService.Status.ACTIVE,
                    0,
                    null)));
    var tenants = mock(de.caritas.cob.userservice.api.admin.service.tenant.TenantService.class);
    when(tenants.getRestrictedTenantDataFresh(7L))
        .thenReturn(
            new de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO()
                .active(true));
    var status =
        new de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService(
            actor,
            scope,
            admins,
            consultants,
            account -> Optional.of(maintenance.read(account).enabled()),
            mock(de.caritas.cob.userservice.api.port.out.IdentityProfileLookup.class),
            mock(de.caritas.cob.userservice.api.port.out.IdentitySecondFactor.class),
            lifecycle,
            tenants);
    status.setScopedLocalWrites(mode, jdbc, transactions);
    status.setHumanAdminMaintenance(maintenance);
    var proofs = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    proofs.addBean("publicPreDeletionMaintenance", owner());
    status.configureRemovedConsultantProofs(
        proofs.getBeanProvider(PublicPreDeletionMaintenance.class));
    return status;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "missing-proof",
        "consultant-birth",
        "admin-birth",
        "foreign-tenant",
        "native-combined"
      })
  void survivingAdminManagementReadDoesNotBorrowRemovedOrForeignIdentity(String changed) {
    givenCombinedConsultantRemoval(Admin.AdminType.AGENCY);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    consultantService().markConsultantForDeletion(ACCOUNT, true);
    remainingAdminHttp(
        Admin.AdminType.AGENCY,
        changed.equals("native-combined") ? "ADMINISTRATOR_IDENTITY" : "ADMIN_ONLY");
    request.setMethod("GET");
    request.setRequestURI("/service/useradmin/accounts/" + ACCOUNT + "/status");
    var maintenance =
        (de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance)
            org.springframework.test.util.ReflectionTestUtils.getField(
                remainingAdminService(), "maintenance");
    var status = remainingAdminStatus(maintenance);
    switch (changed) {
      case "missing-proof" ->
          jdbc.update("delete from identity_account_operation_intent where account_id=?", ACCOUNT);
      case "consultant-birth" ->
          jdbc.update(
              "update consultant set create_date=dateadd('SECOND',1,create_date) where consultant_id=?",
              ACCOUNT);
      case "admin-birth" ->
          jdbc.update(
              "update admin set create_date=dateadd('SECOND',1,create_date) where admin_id=?",
              ACCOUNT);
      case "foreign-tenant" ->
          jdbc.update("update admin set tenant_id=8 where admin_id=?", ACCOUNT);
      case "native-combined" -> {}
      default -> throw new AssertionError(changed);
    }
    assertThrows(RuntimeException.class, () -> maintenance.read(ACCOUNT));
    assertThrows(RuntimeException.class, () -> maintenance.factorStatus(ACCOUNT));
    if (!changed.equals("native-combined")) {
      assertThrows(RuntimeException.class, () -> status.read(ACCOUNT));
      assertThrows(
          RuntimeException.class,
          () -> status.activeForList(ACCOUNT, true, new java.util.HashMap<>()));
    } else assertNull(status.read(ACCOUNT).active());
    assertEquals(1, nativeEffects.get());
  }

  private void givenCombinedConsultantRemoval(Admin.AdminType type) {
    consultant();
    var admin =
        admins.save(
            Admin.builder()
                .id(ACCOUNT)
                .tenantId(7L)
                .username("pre-deletion")
                .firstName("Fixture")
                .lastName("Admin")
                .email("consultant@fixture.invalid")
                .type(type)
                .build());
    adminAgencies.save(
        de.caritas.cob.userservice.api.model.AdminAgency.builder()
            .admin(admin)
            .agencyId(71L)
            .build());
    nativeCommand = "profile-transition";
    Set<String> pureRoles =
        type == Admin.AdminType.AGENCY
            ? Set.of("restricted-agency-admin", "user-admin")
            : type == Admin.AdminType.TENANT
                ? Set.of("tenant-admin")
                : Set.of("tenant-admin", "agency-admin");
    var combined = new java.util.TreeSet<>(pureRoles);
    combined.add("consultant");
    combined.add("group-chat-consultant");
    nativeRoles = Set.copyOf(combined);
    if (type == Admin.AdminType.AGENCY) {
      removalGroups =
          Map.of(
              "/human-roles/agency-admin",
              pureRoles,
              "/human-roles/consultant-agency-admin",
              nativeRoles,
              "/human-roles/consultant",
              Set.of("consultant", "group-chat-consultant"));
    } else {
      String family = type == Admin.AdminType.TENANT ? "TENANT_ADMIN" : "PLATFORM_ADMIN";
      removalProfiles =
          Map.of(
              family,
              List.of(
                  new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle
                      .ReviewedMaintenanceProfiles.Profile("PROTECTED", "pure-admin", 1, pureRoles),
                  new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle
                      .ReviewedMaintenanceProfiles.Profile(
                      "PROTECTED", "combined-admin", 2, nativeRoles)));
    }
    actor.setTenantId(0L);
    actor.setRoles(Set.of("agency-admin", "tenant-admin"));
    actor.setGrantedAuthorities(
        Set.of(Authority.AuthorityValue.USER_ADMIN, Authority.AuthorityValue.TENANT_ADMIN));
    jdbc.update("update admin set tenant_id=0,type='SUPER' where admin_id=?", HUMAN);
  }

  @Test
  void mixedConsultantAdminIdentityIsClosedBeforeAnyReadOrPrestep() {
    consultant();
    admins.save(
        Admin.builder()
            .id(ACCOUNT)
            .tenantId(7L)
            .username("pre-deletion")
            .firstName("Fixture")
            .lastName("Admin")
            .email("consultant@fixture.invalid")
            .type(Admin.AdminType.AGENCY)
            .build());
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    assertThrows(
        ForbiddenException.class,
        () -> consultantService().markConsultantForDeletion(ACCOUNT, true));
    assertEquals(0, nativeReads.get());
    assertEquals(0, nativeEffects.get());
    assertEquals(0, genericAttempts.get());
    verifyNoInteractions(memberships);
  }

  @Test
  void newSelfWrongPlainPasswordAllocatesNothingAndUsesOnlyOneActualSpelling() {
    asker();
    jdbc.update("update user set username=? where user_id=?", "pre-deletion", ACCOUNT);
    authenticate(true, "/service/users/account");
    assertThrows(
        BadRequestException.class,
        () -> selfService().deactivateAndFlagUserAccountForDeletion("wrong-current-password"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from identity_account_operation_intent", Integer.class));
    verify(passwords, times(1))
        .validatePasswordIgnoring2fa("pre-deletion", "wrong-current-password");
    assertEquals(0, nativeReads.get());
    assertEquals(0, nativeCalls.get());
  }

  @Test
  void newSelfSqlDriftDuringActualPasswordCheckCannotAllocateOrReadNativeIdentity() {
    asker();
    authenticate(true, "/service/users/account");
    when(passwords.validatePasswordIgnoring2fa(anyString(), eq("chosen-original-password")))
        .thenAnswer(
            ignored -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(
                  0,
                  jdbc.queryForObject(
                      "select count(*) from identity_account_operation_intent", Integer.class));
              jdbc.update(
                  "update user set create_date=DATEADD('DAY',-1,create_date) where user_id=?",
                  ACCOUNT);
              return true;
            });
    assertThrows(
        ConflictException.class,
        () -> selfService().deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from identity_account_operation_intent", Integer.class));
    assertEquals(0, nativeReads.get());
    assertEquals(0, nativeCalls.get());
  }

  @Test
  void unknownNewSelfPasswordAuthenticationAllocatesNoMaintenanceAttemptOrEffect() {
    asker();
    authenticate(true, "/service/users/account");
    when(passwords.validatePasswordIgnoring2fa(anyString(), eq("chosen-original-password")))
        .thenThrow(new ResourceAccessException("Unknown password authentication response"));
    assertThrows(
        ServiceUnavailableException.class,
        () -> selfService().deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from identity_account_operation_intent", Integer.class));
    assertEquals(0, nativeReads.get());
    assertEquals(0, nativeCalls.get());
  }

  @Test
  void consultantFull53MacCoversLobTailWithoutSerializingItThroughTheBoundedParser() {
    consultant();
    String original = "L".repeat(100000);
    jdbc.update("update consultant set admin_remarks=? where consultant_id=?", original, ACCOUNT);
    var first =
        new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(ignored -> PreDeletionSqlSnapshot.capture(jdbc, codec, true, ACCOUNT, true));
    assertEquals(53, first.full().size());
    assertEquals(original, first.full().path("admin_remarks").textValue());
    assertEquals(8, first.core().size());
    assertTrue(codec.canonical(first.core()).length() < 4096);
    assertEquals(
        digest("public-pre-deletion-row/v1", Map.of("kind", "CONSULTANT", "row", first.full())),
        first.core().path("rowDigest").textValue());
    jdbc.update(
        "update consultant set admin_remarks=? where consultant_id=?",
        original.substring(0, 99999) + "R",
        ACCOUNT);
    var changed =
        new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(ignored -> PreDeletionSqlSnapshot.capture(jdbc, codec, true, ACCOUNT, true));
    assertTrue(PreDeletionSqlSnapshot.sameBirth(first.core(), changed.core()));
    assertNotEquals(first.core().path("rowDigest"), changed.core().path("rowDigest"));
  }

  @Test
  void consultantPreparedLobTailDriftRefusesBeforeAnySecondNativeRead() {
    consultant();
    String original = "L".repeat(100000);
    jdbc.update("update consultant set admin_remarks=? where consultant_id=?", original, ACCOUNT);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    loseReadReply = true;
    nativeHttp(true);
    assertThrows(
        ServiceUnavailableException.class,
        () -> consultantService().markConsultantForDeletion(ACCOUNT, false));
    assertEquals("PREPARED", status());
    String retained =
        jdbc.queryForObject(
            "select source_json from identity_account_operation_intent where operation_id=?",
            String.class,
            operation);
    assertTrue(retained.length() < 8192);
    assertFalse(retained.contains(original));
    jdbc.update(
        "update consultant set admin_remarks=? where consultant_id=?",
        original.substring(0, 99999) + "R",
        ACCOUNT);
    assertThrows(
        ConflictException.class,
        () -> consultantService().markConsultantForDeletion(ACCOUNT, false));
    assertEquals(1, nativeReads.get());
    assertEquals(0, nativeEffects.get());
    verifyNoInteractions(memberships);
  }

  @Test
  void boundedColdRecoveryDoesNotStarveTheNextRealOwnedOutcomeBehindAPoisonFirstRow() {
    proveRecoveryHintOrder(Instant.now());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 123456001, 123456789, 999999999})
  void boundedRecoveryHintMovesBehindTheNextAccountAtEverySubMicrosecondBoundary(int nanos) {
    proveRecoveryHintOrder(Instant.ofEpochSecond(Instant.now().getEpochSecond(), nanos));
  }

  private void proveRecoveryHintOrder(Instant exactClock) {
    asker();
    ownerClock = Clock.fixed(exactClock, ZoneOffset.UTC);
    authenticate(true, "/service/users/account");
    nativeHttp(false);
    loseReply = true;
    assertThrows(
        ServiceUnavailableException.class,
        () -> selfService().deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
    assertEquals("FROZEN", status());
    assertEquals(1, nativeEffects.get());
    var second =
        new User(
            SECOND,
            null,
            new UsernameTranscoder().encodeUsername("pre-deletion"),
            "asker@fixture.invalid",
            false);
    second.setTenantId(7L);
    second.setLanguageCode(LanguageCode.en);
    second.setEncourage2fa(false);
    second.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);
    users.save(second);
    String secondOperation = "ffffffff-ffff-4fff-bfff-ffffffffffff";
    assertTrue(operation.compareTo(secondOperation) < 0);
    authenticate(true, "/service/users/account", SECOND, secondOperation);
    http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    savedBody = null;
    savedReply = null;
    loseReply = true;
    nativeHttp(false, SECOND, secondOperation);
    assertThrows(
        ServiceUnavailableException.class,
        () -> selfService().deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
    assertEquals("FROZEN", status(secondOperation));
    assertEquals(2, nativeEffects.get());
    jdbc.update(
        "update identity_account_operation_intent set updated_at=? where operation_kind='PRE_DELETION'",
        java.sql.Timestamp.from(ownerClock.instant()));
    jdbc.update(
        "update identity_account_operation_intent set source_json='{}' where operation_id=?",
        operation);
    request.setUserPrincipal(null);
    request.removeHeader("Authorization");
    assertEquals(0, selfService().resumeSelfAskerDeletions(1));
    var poisonHint =
        jdbc.queryForObject(
            "select updated_at from identity_account_operation_intent where operation_id=?",
            java.sql.Timestamp.class,
            operation);
    var nextHint =
        jdbc.queryForObject(
            "select updated_at from identity_account_operation_intent where operation_id=?",
            java.sql.Timestamp.class,
            secondOperation);
    assertTrue(
        poisonHint.after(nextHint),
        "The poisoned hint must move strictly behind the untouched candidate");
    int secondResult = selfService().resumeSelfAskerDeletions(1);
    assertEquals(1, outcomeReads.get(), "The next owned outcome must be queried exactly once");
    assertEquals(1, secondResult);
    assertEquals("APPLIED", status(secondOperation));
    assertEquals("FROZEN", status());
    assertNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertNotNull(users.findById(SECOND).orElseThrow().getDeleteDate());
    assertEquals(1, outcomeReads.get());
    assertEquals(2, nativeEffects.get());
    verify(statistics, times(1)).fireEvent(any());
  }

  @Test
  void selfDeletionStatisticsRunsOnlyAfterTheActualOwningSqlCommit() {
    asker();
    authenticate(true, "/service/users/account");
    nativeHttp(false);
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("APPLIED", status());
              assertNotNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
              return null;
            })
        .when(statistics)
        .fireEvent(any());
    assertDoesNotThrow(
        () -> selfService().deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
    verify(statistics, times(1)).fireEvent(any());
    assertEquals("APPLIED", status());
  }

  @Test
  void actualLostFinalJpaCommitReplyRecoversTheOriginalAppliedResultWithoutAnotherCallback() {
    asker();
    authenticate(true, "/service/users/account");
    nativeHttp(false);
    var lost = new LostCommitReplyJpaManager(entities.getEntityManagerFactory(), dataSource);
    transactions = lost;
    userService = spy(userService);
    doAnswer(
            call -> {
              Object result = call.callRealMethod();
              lost.loseNextCommit = true;
              return result;
            })
        .when(userService)
        .saveUser(any());
    assertThrows(
        IllegalStateException.class,
        () -> selfService().deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
    assertEquals("APPLIED", status());
    assertNotNull(users.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertEquals(1, nativeEffects.get());
    assertDoesNotThrow(
        () -> selfService().deactivateAndFlagUserAccountForDeletion("chosen-original-password"));
    verify(userService, times(1)).saveUser(any());
    assertEquals(1, nativeEffects.get());
    assertEquals("APPLIED", status());
  }

  private static final class LostCommitReplyJpaManager extends JpaTransactionManager {
    private boolean loseNextCommit;

    private LostCommitReplyJpaManager(
        jakarta.persistence.EntityManagerFactory factory, DataSource source) {
      super(factory);
      setDataSource(source);
    }

    @Override
    protected void doCommit(
        org.springframework.transaction.support.DefaultTransactionStatus status) {
      super.doCommit(status);
      if (loseNextCommit) {
        loseNextCommit = false;
        throw new IllegalStateException("Lost fixture reply after actual commit");
      }
    }
  }

  @Test
  void acknowledgedMembershipPreparationLeavesSideAndMainRoomsOutsideSqlThenCommitsAllLocalRows() {
    consultant();
    var fixture = membershipFixture(true);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var remote = mock(GroupChatMembershipService.class);
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("ACKNOWLEDGED", status());
              assertTrue(participants.findById(fixture.participation()).isPresent());
              assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
              return null;
            })
        .when(remote)
        .removeMemberFromRoomOrFail(anyString(), anyString());
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("ACKNOWLEDGED", status());
              return null;
            })
        .when(remote)
        .removeParticipantFromRoom(any(), anyString());
    var memberOwner = realMemberships(remote, mock(MatrixChatShutdownService.class));
    assertDoesNotThrow(
        () ->
            owner()
                .startAdminConsultant(
                    ACCOUNT,
                    false,
                    ack -> {
                      var prepared = memberOwner.prepareAcknowledgedDeletion(ack, false);
                      ack.completeConsultant(
                          actual -> {
                            prepared.apply(actual);
                            lifecycle.beginConsultantDeletion(actual, HUMAN);
                            actual.setStatus(
                                de.caritas.cob.userservice.api.model.ConsultantStatus.IN_DELETION);
                            consultants.save(actual);
                          });
                    }));
    assertEquals("APPLIED", status());
    assertFalse(participants.findById(fixture.participation()).isPresent());
    assertEquals(
        SUCCESSOR, sessions.findById(fixture.session()).orElseThrow().getConsultant().getId());
    assertFalse(supervisions.findById(fixture.supervision()).orElseThrow().getIsActive());
    verify(remote)
        .removeMemberFromRoomOrFail("!side:fixture.invalid", "@pre-deletion:fixture.invalid");
    verify(remote).removeParticipantFromRoom(any(), eq("@pre-deletion:fixture.invalid"));
  }

  @Test
  void acknowledgedSoleOwnerPreparationPurgesOutsideSqlAndRetiresOnlyItsOriginalChat() {
    consultant();
    var fixture = membershipFixture(false, Session.SessionStatus.IN_PROGRESS);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var shutdown = mock(MatrixChatShutdownService.class);
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertTrue(chats.findById(fixture.chat()).isPresent());
              assertEquals("ACKNOWLEDGED", status());
              return null;
            })
        .when(shutdown)
        .shutdownRoom(any());
    var memberOwner = realMemberships(mock(GroupChatMembershipService.class), shutdown);
    assertDoesNotThrow(
        () ->
            owner()
                .startAdminConsultant(
                    ACCOUNT,
                    true,
                    ack -> {
                      var prepared = memberOwner.prepareAcknowledgedDeletion(ack, true);
                      ack.completeConsultant(
                          actual -> {
                            prepared.apply(actual);
                            lifecycle.beginConsultantDeletion(actual, HUMAN);
                            actual.setStatus(
                                de.caritas.cob.userservice.api.model.ConsultantStatus.IN_DELETION);
                            consultants.save(actual);
                          });
                    }));
    assertEquals("APPLIED", status());
    assertFalse(chats.findById(fixture.chat()).isPresent());
    assertFalse(participants.findById(fixture.participation()).isPresent());
    assertEquals(
        Session.SessionStatus.DONE, sessions.findById(fixture.session()).orElseThrow().getStatus());
    verify(shutdown).shutdownRoom(argThat(chat -> chat.getId().equals(fixture.chat())));
  }

  @Test
  void unknownMembershipReplyRetainsAckAndEverySqlRowForColdExactRetry() {
    consultant();
    var fixture = membershipFixture(true);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var remote = mock(GroupChatMembershipService.class);
    doThrow(new ServiceUnavailableException("Unknown fixture room leave"))
        .doNothing()
        .when(remote)
        .removeParticipantFromRoom(any(), anyString());
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    assertEquals("ACKNOWLEDGED", status());
    assertTrue(participants.findById(fixture.participation()).isPresent());
    assertTrue(supervisions.findById(fixture.supervision()).orElseThrow().getIsActive());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertDoesNotThrow(
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    assertEquals("APPLIED", status());
    assertEquals(1, nativeEffects.get());
    assertFalse(participants.findById(fixture.participation()).isPresent());
  }

  @Test
  void membershipRoomDriftDuringRemoteLeaveCannotAuthorizeChangedRelationsOrCompletion() {
    consultant();
    var fixture = membershipFixture(true);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var remote = mock(GroupChatMembershipService.class);
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              jdbc.update(
                  "update chat set matrix_room_id=? where id=?",
                  "!replacement:fixture.invalid",
                  fixture.chat());
              return null;
            })
        .when(remote)
        .removeParticipantFromRoom(any(), anyString());
    assertThrows(
        ConflictException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    assertEquals("ACKNOWLEDGED", status());
    assertTrue(participants.findById(fixture.participation()).isPresent());
    assertTrue(supervisions.findById(fixture.supervision()).orElseThrow().getIsActive());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
  }

  @Test
  void crashAfterStrictAckButBeforePreparationRetainsPendingCustodyAndColdOwnerCompletes() {
    consultant();
    membershipFixture(true);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            owner()
                .startAdminConsultant(
                    ACCOUNT,
                    false,
                    ack -> {
                      ack.requireCurrent();
                      throw new ServiceUnavailableException(
                          "Fixture process ended before preparation");
                    }));
    assertEquals("ACKNOWLEDGED", status());
    String retained = relatedCustody();
    assertNotNull(retained);
    assertEquals(
        "PENDING", codec.read(retained, JsonNode.class).path("plan").path("state").asText());
    assertDoesNotThrow(
        () ->
            completeMembershipDeletion(
                realMemberships(
                    mock(GroupChatMembershipService.class), mock(MatrixChatShutdownService.class)),
                false));
    assertEquals("APPLIED", status());
    assertEquals(1, nativeEffects.get());
  }

  @Test
  void missingCustodyAfterAnUnknownRoomAttemptCannotAllocateAReplacementPlan() {
    consultant();
    membershipFixture(true);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var remote = mock(GroupChatMembershipService.class);
    doThrow(new ServiceUnavailableException("Unknown fixture room reply"))
        .doNothing()
        .when(remote)
        .removeParticipantFromRoom(any(), anyString());
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    assertNotNull(relatedCustody());
    jdbc.update(
        "update identity_account_operation_intent set applied_json=null where operation_id=?",
        operation);
    clearInvocations(remote);
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    verifyNoInteractions(remote);
    assertNull(relatedCustody());
    assertEquals("ACKNOWLEDGED", status());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
  }

  @Test
  void firstRelatedPlanIsCommittedBeforeAnyRoomRpcAndContainsOnlyCompleteRowDigests() {
    consultant();
    var fixture = membershipFixture(true);
    String fullLob = "Related private source ".repeat(6000);
    jdbc.update("update consultant set admin_remarks=? where consultant_id=?", fullLob, ACCOUNT);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var remote = mock(GroupChatMembershipService.class);
    var witnessed = new java.util.concurrent.atomic.AtomicReference<String>();
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              String retained = relatedCustody();
              assertNotNull(retained, "The original plan must commit before the first room RPC");
              var node = codec.read(retained, JsonNode.class);
              assertEquals("PRE_DELETION_RELATED_V1", node.path("kind").asText());
              assertEquals(operation, node.path("binding").path("operationId").asText());
              assertEquals(ACCOUNT, node.path("binding").path("accountId").asText());
              assertEquals("FROZEN", node.path("plan").path("state").asText());
              assertEquals(2, node.path("plan").path("remotes").path("count").asInt());
              assertEquals(
                  digest(
                      "public-pre-deletion-related/v1",
                      Map.of(
                          "remotes",
                          List.of(
                              relatedRemote(
                                  "SIDE",
                                  null,
                                  "!side:fixture.invalid",
                                  "@pre-deletion:fixture.invalid"),
                              relatedRemote(
                                  "MAIN",
                                  fixture.chat(),
                                  "!main:fixture.invalid",
                                  "@pre-deletion:fixture.invalid")))),
                  node.path("plan").path("remotes").path("digest").asText());
              assertFalse(retained.contains("!side:fixture.invalid"));
              assertFalse(retained.contains("!main:fixture.invalid"));
              assertFalse(retained.contains(fullLob));
              assertTrue(retained.getBytes(StandardCharsets.UTF_8).length <= 65536);
              if (witnessed.get() == null) witnessed.set(retained);
              else assertEquals(witnessed.get(), retained);
              return null;
            })
        .when(remote)
        .removeMemberFromRoomOrFail(anyString(), anyString());
    assertDoesNotThrow(
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    assertNotNull(witnessed.get());
    assertEquals("APPLIED", status());
    assertEquals(1, nativeEffects.get());
  }

  @Test
  void actualPublicConsultantWithMoreThanFiveHundredOwnedRowsHasCompactCompleteCustody() {
    consultant();
    var fixture = membershipFixture(true);
    var memberSession = sessions.findById(fixture.session()).orElseThrow();
    memberSession.setStatus(Session.SessionStatus.DONE);
    sessions.save(memberSession);
    var asker = users.findById(ASKER).orElseThrow();
    var leaving = consultants.findById(ACCOUNT).orElseThrow();
    var batch = new java.util.ArrayList<Session>();
    for (int i = 0; i < 600; i++)
      batch.add(
          Session.builder()
              .user(asker)
              .consultant(leaving)
              .postcode("12345")
              .languageCode(LanguageCode.en)
              .registrationType(Session.RegistrationType.REGISTERED)
              .status(Session.SessionStatus.DONE)
              .isConsultantDirectlySet(false)
              .tenantId(7L)
              .createDate(LocalDateTime.of(2024, 1, 2, 3, 4))
              .build());
    sessions.saveAll(batch);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    scopedRemote = mock(GroupChatMembershipService.class);
    var observed = new AtomicInteger();
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              String retained = relatedCustody();
              assertNotNull(retained);
              assertEquals(
                  601,
                  codec
                      .read(retained, JsonNode.class)
                      .path("plan")
                      .path("rows")
                      .path("session")
                      .path("count")
                      .asInt());
              assertTrue(retained.getBytes(StandardCharsets.UTF_8).length < 8192);
              observed.incrementAndGet();
              return null;
            })
        .when(scopedRemote)
        .removeMemberFromRoomOrFail(anyString(), anyString());
    assertDoesNotThrow(() -> consultantService().markConsultantForDeletion(ACCOUNT, false));
    assertEquals(1, observed.get());
    assertEquals("APPLIED", status());
    assertEquals(
        601,
        jdbc.queryForObject("select count(*) from session where user_id=?", Integer.class, ASKER));
    assertEquals(1, nativeEffects.get());
  }

  private Map<String, Object> relatedRemote(
      String kind, Long chatId, String roomId, String memberId) {
    var value = new TreeMap<String, Object>();
    value.put("kind", kind);
    value.put("chatId", chatId);
    value.put("roomId", roomId);
    value.put("memberId", memberId);
    return value;
  }

  @Test
  void unknownRoomReplyThenColdChangedRoomCannotRebindTheOriginalDurablePlan() {
    consultant();
    var fixture = membershipFixture(true);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var remote = mock(GroupChatMembershipService.class);
    doThrow(new ServiceUnavailableException("Unknown fixture room reply"))
        .doNothing()
        .when(remote)
        .removeParticipantFromRoom(any(), anyString());
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    String original = relatedCustody();
    assertNotNull(original);
    jdbc.update(
        "update chat set matrix_room_id=? where id=?",
        "!new-current:fixture.invalid",
        fixture.chat());
    clearInvocations(remote);
    assertThrows(
        ConflictException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    verifyNoInteractions(remote);
    assertEquals(original, relatedCustody());
    assertEquals("ACKNOWLEDGED", status());
    assertTrue(participants.findById(fixture.participation()).isPresent());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertEquals(1, nativeEffects.get());
  }

  @Test
  void unknownRoomReplyThenColdAgencyRelationDriftCannotBeAdoptedByThePublicCallback() {
    consultant();
    var fixture = membershipFixture(true);
    var session = sessions.findById(fixture.session()).orElseThrow();
    session.setStatus(Session.SessionStatus.DONE);
    sessions.save(session);
    var relation =
        consultantAgencies.save(
            ConsultantAgency.builder()
                .consultant(consultants.findById(ACCOUNT).orElseThrow())
                .agencyId(71L)
                .tenantId(7L)
                .createDate(LocalDateTime.of(2024, 1, 2, 3, 4))
                .build());
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    scopedRemote = mock(GroupChatMembershipService.class);
    doThrow(new ServiceUnavailableException("Unknown fixture room reply"))
        .doNothing()
        .when(scopedRemote)
        .removeParticipantFromRoom(any(), anyString());
    assertThrows(
        ServiceUnavailableException.class,
        () -> consultantService().markConsultantForDeletion(ACCOUNT, false));
    String retained = relatedCustody();
    assertNotNull(retained);
    jdbc.update("update consultant_agency set agency_id=? where id=?", 72L, relation.getId());
    clearInvocations(scopedRemote);
    assertThrows(
        ConflictException.class,
        () -> consultantService().markConsultantForDeletion(ACCOUNT, false));
    verifyNoInteractions(scopedRemote);
    assertEquals(retained, relatedCustody());
    assertEquals("ACKNOWLEDGED", status());
    assertNull(consultantAgencies.findById(relation.getId()).orElseThrow().getDeleteDate());
    assertEquals(72L, consultantAgencies.findById(relation.getId()).orElseThrow().getAgencyId());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
  }

  @Test
  void foreignOrCorruptDurableRelatedPlanCannotReachAnyFurtherRoomRpc() {
    consultant();
    membershipFixture(true);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var remote = mock(GroupChatMembershipService.class);
    doThrow(new ServiceUnavailableException("Unknown fixture room reply"))
        .doNothing()
        .when(remote)
        .removeParticipantFromRoom(any(), anyString());
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    String original = relatedCustody();
    assertNotNull(original);
    var corrupt = codec.read(original, com.fasterxml.jackson.databind.node.ObjectNode.class);
    corrupt.put("mac", "0".repeat(64));
    jdbc.update(
        "update identity_account_operation_intent set applied_json=? where operation_id=?",
        codec.canonical(corrupt),
        operation);
    clearInvocations(remote);
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    verifyNoInteractions(remote);
    var foreign = codec.read(original, com.fasterxml.jackson.databind.node.ObjectNode.class);
    ((com.fasterxml.jackson.databind.node.ObjectNode) foreign.path("binding"))
        .put("operationId", "ffffffff-ffff-4fff-bfff-ffffffffffff");
    foreign.remove("mac");
    foreign.put("mac", digest("public-pre-deletion-related/v1", foreign));
    jdbc.update(
        "update identity_account_operation_intent set applied_json=? where operation_id=?",
        codec.canonical(foreign),
        operation);
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    verifyNoInteractions(remote);
    assertEquals("ACKNOWLEDGED", status());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertEquals(1, nativeEffects.get());
  }

  @Test
  void coldAcknowledgedAdminContinuationRejoinsNativeCurrentReachBeforeAnyFurtherCallback() {
    consultant();
    var fixture = membershipFixture(true);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var remote = mock(GroupChatMembershipService.class);
    doThrow(new ServiceUnavailableException("Unknown fixture room reply"))
        .doNothing()
        .when(remote)
        .removeParticipantFromRoom(any(), anyString());
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    String original = relatedCustody();
    clearInvocations(remote);
    refuseNativeReach = true;
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            completeMembershipDeletion(
                realMemberships(remote, mock(MatrixChatShutdownService.class)), false));
    verifyNoInteractions(remote);
    assertEquals(original, relatedCustody());
    assertEquals("ACKNOWLEDGED", status());
    assertTrue(participants.findById(fixture.participation()).isPresent());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertEquals(1, nativeEffects.get());
  }

  @Test
  void privateRelatedPreparationCannotApplyThroughANonfinalOwnedCallback() {
    consultant();
    var fixture = membershipFixture(true);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var memberOwner =
        realMemberships(
            mock(GroupChatMembershipService.class), mock(MatrixChatShutdownService.class));
    assertThrows(
        ServiceUnavailableException.class,
        () ->
            owner()
                .startAdminConsultant(
                    ACCOUNT,
                    false,
                    ack -> {
                      var prepared = memberOwner.prepareAcknowledgedDeletion(ack, false);
                      ack.runOwnedLocal(
                          () -> prepared.apply(consultants.findById(ACCOUNT).orElseThrow()));
                    }));
    assertEquals("ACKNOWLEDGED", status());
    assertTrue(participants.findById(fixture.participation()).isPresent());
    assertTrue(supervisions.findById(fixture.supervision()).orElseThrow().getIsActive());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
  }

  @Test
  void privateRelatedPreparationCannotChangeTheOriginalForceRequest() {
    consultant();
    var fixture = membershipFixture(true);
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var remote = mock(GroupChatMembershipService.class);
    var memberOwner = realMemberships(remote, mock(MatrixChatShutdownService.class));
    assertThrows(
        ConflictException.class,
        () ->
            owner()
                .startAdminConsultant(
                    ACCOUNT, false, ack -> memberOwner.prepareAcknowledgedDeletion(ack, true)));
    verifyNoInteractions(remote);
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(
        "PENDING",
        codec.read(relatedCustody(), JsonNode.class).path("plan").path("state").asText());
    assertTrue(participants.findById(fixture.participation()).isPresent());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
  }

  private String relatedCustody() {
    return jdbc.queryForObject(
        "select applied_json from identity_account_operation_intent where operation_id=?",
        String.class,
        operation);
  }

  @Test
  void acknowledgedAgencyValidationReadsRemoteOutsideSqlAndMarksRelationOnlyInFinalJpa() {
    consultant();
    var relation =
        consultantAgencies.save(
            ConsultantAgency.builder()
                .consultant(consultants.findById(ACCOUNT).orElseThrow())
                .agencyId(71L)
                .tenantId(7L)
                .createDate(LocalDateTime.of(2024, 1, 2, 3, 4))
                .build());
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var agency = mock(AgencyService.class);
    when(agency.getAgencyWithoutCaching(71L))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("ACKNOWLEDGED", status());
              assertNull(
                  consultantAgencies.findById(relation.getId()).orElseThrow().getDeleteDate());
              return new AgencyDTO().offline(true);
            });
    var presteps =
        new ConsultantPreDeletionService(
            new ConsultantAgencyDeletionValidationService(consultantAgencies, agency, sessions),
            sessions,
            new ClosedGenericDeactivator());
    presteps.configurePreDeletionConsultants(consultants);
    assertDoesNotThrow(
        () ->
            owner()
                .startAdminConsultant(
                    ACCOUNT,
                    false,
                    ack -> {
                      var prepared = presteps.prepareAcknowledgedDeletion(ack, false);
                      var memberships =
                          realMemberships(
                                  mock(GroupChatMembershipService.class),
                                  mock(MatrixChatShutdownService.class))
                              .prepareAcknowledgedDeletion(ack, false);
                      ack.completeConsultant(
                          actual -> {
                            var deletedAt = LocalDateTime.now(ZoneOffset.UTC);
                            prepared.apply(actual, deletedAt);
                            memberships.apply(actual);
                            actual.setDeleteDate(deletedAt);
                            lifecycle.beginConsultantDeletion(actual, HUMAN);
                            actual.setStatus(
                                de.caritas.cob.userservice.api.model.ConsultantStatus.IN_DELETION);
                            consultants.save(actual);
                          });
                    }));
    var saved = consultants.findById(ACCOUNT).orElseThrow();
    assertEquals("APPLIED", status());
    assertEquals(
        saved.getDeleteDate(),
        consultantAgencies.findById(relation.getId()).orElseThrow().getDeleteDate());
    verify(agency).getAgencyWithoutCaching(71L);
  }

  @Test
  void activeLastAgencyRefusalAfterNativeAckCannotMarkEitherLocalRow() {
    consultant();
    var relation =
        consultantAgencies.save(
            ConsultantAgency.builder()
                .consultant(consultants.findById(ACCOUNT).orElseThrow())
                .agencyId(71L)
                .tenantId(7L)
                .createDate(LocalDateTime.of(2024, 1, 2, 3, 4))
                .build());
    authenticate(false, "/service/useradmin/consultants/" + ACCOUNT);
    nativeHttp(true);
    var agency = mock(AgencyService.class);
    when(agency.getAgencyWithoutCaching(71L)).thenReturn(new AgencyDTO().offline(false));
    var presteps =
        new ConsultantPreDeletionService(
            new ConsultantAgencyDeletionValidationService(consultantAgencies, agency, sessions),
            sessions,
            new ClosedGenericDeactivator());
    presteps.configurePreDeletionConsultants(consultants);
    assertThrows(
        CustomValidationHttpStatusException.class,
        () ->
            owner()
                .startAdminConsultant(
                    ACCOUNT,
                    false,
                    ack -> {
                      presteps.prepareAcknowledgedDeletion(ack, false);
                      fail("Active agency must veto before final completion");
                    }));
    assertEquals("ACKNOWLEDGED", status());
    assertNull(consultantAgencies.findById(relation.getId()).orElseThrow().getDeleteDate());
    assertNull(consultants.findById(ACCOUNT).orElseThrow().getDeleteDate());
    assertEquals(1, nativeEffects.get());
  }

  private void completeMembershipDeletion(MembersOnlyRevocation memberOwner, boolean force) {
    owner()
        .startAdminConsultant(
            ACCOUNT,
            force,
            ack -> {
              var prepared = memberOwner.prepareAcknowledgedDeletion(ack, force);
              ack.completeConsultant(
                  actual -> {
                    prepared.apply(actual);
                    lifecycle.beginConsultantDeletion(actual, HUMAN);
                    actual.setStatus(
                        de.caritas.cob.userservice.api.model.ConsultantStatus.IN_DELETION);
                    consultants.save(actual);
                  });
            });
  }

  private MembersOnlyRevocation realMemberships(
      GroupChatMembershipService remote, MatrixChatShutdownService shutdown) {
    var result =
        new MembersOnlyRevocation(
            sessions, consultants, participants, supervisions, remote, chats, shutdown);
    result.configurePreDeletionJdbc(jdbc);
    return result;
  }

  private record MembershipFixture(Long chat, Long session, Long participation, Long supervision) {}

  private MembershipFixture membershipFixture(boolean remaining) {
    return membershipFixture(remaining, Session.SessionStatus.NEW);
  }

  private MembershipFixture membershipFixture(boolean remaining, Session.SessionStatus status) {
    var leaving = consultants.findById(ACCOUNT).orElseThrow();
    leaving.setMatrixUserId("@pre-deletion:fixture.invalid");
    consultants.save(leaving);
    var successor =
        consultants.save(
            Consultant.builder()
                .id(SUCCESSOR)
                .username("successor")
                .firstName("Fixture")
                .lastName("Successor")
                .email("successor@fixture.invalid")
                .tenantId(7L)
                .matrixUserId("@successor:fixture.invalid")
                .languageCode(LanguageCode.en)
                .encourage2fa(false)
                .magicLinkLoginEnabled(false)
                .notifyEnquiriesRepeating(true)
                .notifyNewChatMessageFromAdviceSeeker(true)
                .createDate(LocalDateTime.of(2024, 1, 2, 3, 4))
                .build());
    var asker = new User(ASKER, null, "fixture-asker", "asker@fixture.invalid", false);
    asker.setTenantId(7L);
    asker.setLanguageCode(LanguageCode.en);
    asker.setEncourage2fa(false);
    asker = users.save(asker);
    var session =
        sessions.save(
            Session.builder()
                .user(asker)
                .consultant(leaving)
                .postcode("12345")
                .languageCode(LanguageCode.de)
                .registrationType(Session.RegistrationType.REGISTERED)
                .status(status)
                .matrixRoomId("!main:fixture.invalid")
                .isConsultantDirectlySet(false)
                .tenantId(7L)
                .createDate(LocalDateTime.of(2024, 1, 2, 3, 4))
                .build());
    var chat =
        chats.save(
            Chat.builder()
                .topic("Private fixture")
                .initialStartDate(LocalDateTime.of(2024, 1, 2, 3, 4))
                .startDate(LocalDateTime.of(2024, 1, 2, 3, 4))
                .duration(30)
                .chatOwner(leaving)
                .membersOnly(true)
                .matrixRoomId("!main:fixture.invalid")
                .createDate(LocalDateTime.of(2024, 1, 2, 3, 4))
                .build());
    var participation =
        participants.save(
            GroupChatParticipant.builder()
                .chatId(session.getId())
                .seriesId(chat.getId())
                .consultantId(ACCOUNT)
                .role(GroupChatParticipant.ParticipantRole.OWNER)
                .build());
    if (remaining)
      participants.save(
          GroupChatParticipant.builder()
              .chatId(session.getId())
              .seriesId(chat.getId())
              .consultantId(SUCCESSOR)
              .role(GroupChatParticipant.ParticipantRole.PARTICIPANT)
              .build());
    var supervision =
        supervisions.save(
            SessionSupervisor.builder()
                .session(session)
                .supervisorConsultant(leaving)
                .addedByConsultant(successor)
                .addedDate(LocalDateTime.of(2024, 1, 2, 3, 4))
                .isActive(true)
                .matrixRoomId("!side:fixture.invalid")
                .build());
    return new MembershipFixture(
        chat.getId(), session.getId(), participation.getId(), supervision.getId());
  }

  private AskerUserAdminFacade adminAskerService() {
    var service =
        new AskerUserAdminFacade(
            new ClosedGenericDeactivator(),
            mock(IdentityReactivationSagaStore.class),
            mock(IdentityReactivationRepairService.class),
            userService,
            new UsernameTranscoder(),
            lifecycle,
            scope);
    service.setIdentityMode(mode);
    service.setScopedPreDeletion(owner());
    return service;
  }

  private PublicPreDeletionMaintenance owner() {
    var owner =
        new PublicPreDeletionMaintenance(
            mode,
            actor,
            scope,
            request,
            jdbc,
            transactions,
            entities,
            actors,
            broker,
            codec,
            http,
            passwords,
            ownerClock,
            "https://identity.test",
            ISSUER,
            "fixture",
            REALM);
    owner.configureConsultantEligibility(
        new ConsultantAgencyDeletionValidationService(
            consultantAgencies, preflightAgencies, sessions));
    owner.configureConsultantRemovalProfiles(removalGroups, removalProfiles);
    return owner;
  }

  private void authenticate(boolean self, String route) {
    authenticate(self, route, ACCOUNT, operation);
  }

  private void authenticate(
      boolean self, String route, String boundAccount, String boundOperation) {
    request.setMethod("DELETE");
    request.setRequestURI(route);
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", boundOperation);
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer fixture-human");
    if (self) {
      actor.setUserId(boundAccount);
      actor.setUsername("pre-deletion");
      actor.setRoles(Set.of("user"));
      actor.setGrantedAuthorities(Set.of(Authority.AuthorityValue.USER_DEFAULT));
    }
    var jwt =
        Jwt.withTokenValue("fixture-human")
            .header("alg", "RS256")
            .subject(actor.getUserId())
            .issuer(ISSUER)
            .claim("azp", self ? "app" : "admin")
            .claim("tenantId", actor.getTenantId().toString())
            .issuedAt(Instant.now().minusSeconds(10))
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    request.setUserPrincipal(
        new JwtAuthenticationToken(
            jwt,
            List.of(
                new SimpleGrantedAuthority(
                    self
                        ? Authority.AuthorityValue.USER_DEFAULT
                        : Authority.AuthorityValue.USER_ADMIN))));
  }

  private String status() {
    return status(operation);
  }

  private String status(String selectedOperation) {
    return jdbc.queryForObject(
        "select status from identity_account_operation_intent where operation_id=? and operation_kind='PRE_DELETION'",
        String.class,
        selectedOperation);
  }

  private void nativeHttp(boolean consultant) {
    nativeHttp(consultant, ACCOUNT, operation);
  }

  private void nativeHttp(boolean consultant, String boundAccount, String boundOperation) {
    server
        .expect(manyTimes(), ignored -> {})
        .andRespond(
            raw -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              var mock = (MockClientHttpRequest) raw;
              assertEquals(HttpMethod.POST, raw.getMethod());
              assertEquals("Bearer checked-machine", raw.getHeaders().getFirst("Authorization"));
              assertNotNull(raw.getHeaders().getFirst("X-ORISO-Task-Context"));
              var body = codec.read(mock.getBodyAsString(), JsonNode.class);
              assertEquals(boundAccount, body.path("target").path("accountId").textValue());
              if (expectedNativeAuthority != null)
                assertEquals(
                    expectedNativeAuthority,
                    body.path("target").path("origin").path("humanAuthority").textValue());
              String path = raw.getURI().getPath();
              if (refuseNativeReach
                  && (path.endsWith("/" + nativeCommand) || path.endsWith("/deactivate/outcome")))
                return org.springframework.test.web.client.response.MockRestResponseCreators
                    .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body("{\"error\":\"Maintenance request rejected\"}")
                    .header("Cache-Control", "no-store")
                    .createResponse(raw);
              if (path.endsWith("/read")) {
                nativeReads.incrementAndGet();
                assertNotNull(
                    jdbc.queryForObject(
                        "select source_json from identity_account_operation_intent where operation_id=?",
                        String.class,
                        boundOperation));
                if (loseReadReply) {
                  loseReadReply = false;
                  throw new ResourceAccessException("Lost fixture read");
                }
                var projection = new TreeMap<String, Object>();
                projection.put("id", boundAccount);
                projection.put("username", "pre-deletion");
                projection.put(
                    "email", consultant ? "consultant@fixture.invalid" : "asker@fixture.invalid");
                projection.put("firstName", consultant ? "Fixture" : null);
                projection.put("lastName", consultant ? "Consultant" : null);
                projection.put("tenantId", "7");
                projection.put("enabled", true);
                projection.put("emailVerified", false);
                projection.put("preferredLanguage", "en");
                projection.put(
                    "effectiveRoles",
                    nativeRoles != null
                        ? nativeRoles
                        : consultant ? List.of("consultant") : List.of("user"));
                projection.put("passwordChangeRequired", false);
                projection.put("state", "a".repeat(64));
                return withSuccess(codec.canonical(projection), MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store")
                    .createResponse(raw);
              }
              if (path.endsWith("/deactivate/outcome")) {
                outcomeReads.incrementAndGet();
                assertNull(raw.getHeaders().getFirst("X-ORISO-Human-Authorization"));
                if (unknownOutcome || savedReply == null)
                  return org.springframework.test.web.client.response.MockRestResponseCreators
                      .withStatus(org.springframework.http.HttpStatus.CONFLICT)
                      .body("{\"error\":\"Maintenance request rejected\"}")
                      .header("Cache-Control", "no-store")
                      .createResponse(raw);
                assertEquals(savedBody, mock.getBodyAsString());
                return withSuccess(savedReply, MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store")
                    .createResponse(raw);
              }
              assertTrue(path.endsWith("/" + nativeCommand));
              assertEquals(boundOperation, body.path("operationId").textValue());
              nativeCalls.incrementAndGet();
              if (nativeTerminalStatus != 0)
                return org.springframework.test.web.client.response.MockRestResponseCreators
                    .withStatus(org.springframework.http.HttpStatus.valueOf(nativeTerminalStatus))
                    .body(signedReply(body, nativeTerminalStatus))
                    .header("Cache-Control", "no-store")
                    .createResponse(raw);
              if (savedBody == null) {
                assertNull(
                    jdbc.queryForObject(
                        "select delete_date from "
                            + (consultant
                                ? "consultant where consultant_id=?"
                                : "user where user_id=?"),
                        java.sql.Timestamp.class,
                        boundAccount));
                verifyNoInteractions(memberships);
              }
              if (unavailableNative)
                throw new ResourceAccessException("Unknown fixture native result");
              assertEquals(
                  Set.of("target", "operationId", "expectedState", "change"), fields(body));
              if (savedBody == null) {
                savedBody = mock.getBodyAsString();
                nativeEffects.incrementAndGet();
                savedReply = signedReply(body);
              } else assertEquals(savedBody, mock.getBodyAsString());
              if (loseReply) {
                loseReply = false;
                throw new ResourceAccessException(
                    "Actual fixture effect committed before lost reply");
              }
              String reply = forgedReceipt ? savedReply.replace("UPDATED", "FORGED") : savedReply;
              return withSuccess(reply, MediaType.APPLICATION_JSON)
                  .header("Cache-Control", "no-store")
                  .createResponse(raw);
            });
  }

  private Set<String> fields(JsonNode body) {
    var fields = new java.util.HashSet<String>();
    body.fieldNames().forEachRemaining(fields::add);
    return fields;
  }

  private String signedReply(JsonNode body) throws java.io.IOException {
    return signedReply(body, null);
  }

  private String signedReply(JsonNode body, Integer terminalStatus) throws java.io.IOException {
    String result =
        terminalStatus == null
            ? (nativeCommand.equals("delete") ? "DELETED" : "UPDATED")
            : "NO_EFFECT";
    var payload = new TreeMap<String, Object>();
    payload.put("realmId", REALM);
    payload.put("accountId", body.path("target").path("accountId").textValue());
    payload.put("operationId", body.path("operationId").textValue());
    payload.put("command", nativeCommand);
    payload.put("ownerClient", "backend-account-admin");
    payload.put("ownerSubject", MACHINE);
    payload.put("intentDigest", digest("maintenance-intent", body));
    payload.put("previousState", body.path("expectedState").textValue());
    payload.put("tenantId", "7");
    payload.put("originDigest", digest("maintenance-origin", body.path("target").path("origin")));
    payload.put("result", result);
    if (terminalStatus != null) payload.put("status", terminalStatus);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
    String domain =
        terminalStatus == null
            ? "maintenance-operation-receipt"
            : "maintenance-no-effect-receipt/v1";
    String receipt = encoded + "." + HexFormat.of().formatHex(hmac(domain + "\n" + encoded));
    var reply =
        new TreeMap<String, Object>(
            Map.of(
                "operationId",
                body.path("operationId").textValue(),
                "accountId",
                body.path("target").path("accountId").textValue(),
                "result",
                result,
                "receipt",
                receipt));
    if (terminalStatus != null) reply.put("status", terminalStatus);
    return codec.canonical(reply);
  }

  private String digest(String domain, Object value) {
    return HexFormat.of().formatHex(hmac(domain + "\n" + codec.canonical(value)));
  }

  private byte[] hmac(String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private void asker() {
    User user =
        new User(
            ACCOUNT,
            null,
            new UsernameTranscoder().encodeUsername("pre-deletion"),
            "asker@fixture.invalid",
            false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setEncourage2fa(false);
    user.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);
    users.save(user);
  }

  private void consultant() {
    Consultant consultant =
        Consultant.builder()
            .id(ACCOUNT)
            .tenantId(7L)
            .username(new UsernameTranscoder().encodeUsername("pre-deletion"))
            .firstName("Fixture")
            .lastName("Consultant")
            .email("consultant@fixture.invalid")
            .languageCode(LanguageCode.en)
            .encourage2fa(false)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .createDate(LocalDateTime.now(ZoneOffset.UTC).minusYears(1))
            .status(de.caritas.cob.userservice.api.model.ConsultantStatus.CREATED)
            .deletionLifecycleState(DeletionLifecycleState.ACTIVE)
            .build();
    consultants.save(consultant);
  }

  private UserAccountService selfService() {
    var service =
        new UserAccountService(
            userService,
            new ConsultantService(consultants, mock(ConsultantMobileTokenRepository.class)),
            mock(AppointmentService.class),
            actor,
            mock(IdentityClient.class),
            mock(IdentityEmailAddressUpdater.class),
            new ClosedGenericDeactivator(),
            mock(UserHelper.class),
            mode,
            statistics,
            mock(SupervisorAddedEmailNotificationService.class),
            lifecycle);
    service.setScopedPreDeletion(owner());
    return service;
  }

  private ConsultantAdminService consultantService() {
    var store =
        new ConsultantPictureStore(
            consultants,
            pictures,
            mock(ConsultantPictureAccess.class),
            entities,
            mock(
                de.caritas.cob.userservice.api.service.accountinvite.onboarding
                    .CounsellorOnboardingService.class));
    var presteps =
        new ConsultantPreDeletionService(
            new ConsultantAgencyDeletionValidationService(
                consultantAgencies, mock(AgencyService.class), sessions),
            sessions,
            new ClosedGenericDeactivator());
    presteps.configurePreDeletionConsultants(consultants);
    var target =
        new ConsultantAdminService(
            consultants,
            store,
            mock(CreateConsultantSaga.class),
            mock(ConsultantUpdateService.class),
            presteps,
            sessions,
            new MembersOnlyChatAccess(chats, participants),
            realMemberships(
                scopedRemote == null ? mock(GroupChatMembershipService.class) : scopedRemote,
                mock(MatrixChatShutdownService.class)),
            new SessionOwnershipService(
                sessions,
                entities,
                new de.caritas.cob.userservice.api.service.matrix.InquiryAcceptanceNoticeStore(
                    sessions,
                    acceptanceNotices,
                    new de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver())),
            handovers,
            supervisions,
            actor,
            mock(AccountManager.class),
            mock(AppointmentService.class),
            lifecycle,
            mock(ConsultantTopicRepository.class),
            mock(TopicService.class));
    target.setScopedPreDeletion(mode, owner());
    // The initial implementation owns one real transaction around its public legacy method.
    // C will need an outer transaction-free entry and explicit owned local callbacks instead.
    var interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(transactions);
    interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(interceptor);
    return (ConsultantAdminService) proxy.getProxy();
  }
}
