package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
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
import de.caritas.cob.userservice.api.model.Consultant;
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
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublicPreDeletionJpaTest {
  private static final String ACCOUNT = "81000000-0000-4000-8000-000000000011";
  private static final String HUMAN = "81000000-0000-4000-8000-000000000012";
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entities;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private AdminRepository admins;
  @Autowired private AdminAgencyRepository adminAgencies;
  @Autowired private ConsultantAgencyRepository consultantAgencies;
  @Autowired private UserAgencyRepository userAgencies;
  @Autowired private SessionRepository sessions;
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
  private boolean loseReply, loseReadReply, forgedReceipt, unavailableNative, unknownOutcome;

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
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261021_identity_self_locale_367/migrate.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261023_existing_import_intent_367/migrate.sql"))
        .execute(dataSource);

    users.findById(ACCOUNT).ifPresent(users::delete);
    consultants.findById(ACCOUNT).ifPresent(consultants::delete);
    admins.findById(ACCOUNT).ifPresent(admins::delete);
    admins.findById(HUMAN).ifPresent(admins::delete);
    actor =
        new AuthenticatedUser(
            HUMAN,
            "operator",
            Set.of("single-tenant-admin", "user-admin"),
            "fixture-human",
            Set.of("ROLE_SINGLE_TENANT_ADMIN", "ROLE_USER_ADMIN"));
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

  @Test
  void adminAskerStartUsesActualScopeAndOwnedAcknowledgementBeforeSql() {
    asker();
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
    return new PublicPreDeletionMaintenance(
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
        Clock.systemUTC(),
        "https://identity.test",
        ISSUER,
        "fixture",
        REALM);
  }

  private void authenticate(boolean self, String route) {
    request.setMethod("DELETE");
    request.setRequestURI(route);
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", operation);
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer fixture-human");
    if (self) {
      actor.setUserId(ACCOUNT);
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
            .claim("tenantId", "7")
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
    return jdbc.queryForObject(
        "select status from identity_account_operation_intent where operation_id=? and operation_kind='PRE_DELETION'",
        String.class,
        operation);
  }

  private void nativeHttp(boolean consultant) {
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
              assertEquals(ACCOUNT, body.path("target").path("accountId").textValue());
              String path = raw.getURI().getPath();
              if (path.endsWith("/read")) {
                nativeReads.incrementAndGet();
                assertNotNull(
                    jdbc.queryForObject(
                        "select source_json from identity_account_operation_intent where operation_id=?",
                        String.class,
                        operation));
                if (loseReadReply) {
                  loseReadReply = false;
                  throw new ResourceAccessException("Lost fixture read");
                }
                var projection = new TreeMap<String, Object>();
                projection.put("id", ACCOUNT);
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
                    "effectiveRoles", consultant ? List.of("consultant") : List.of("user"));
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
              assertTrue(path.endsWith("/deactivate"));
              nativeCalls.incrementAndGet();
              assertNull(
                  jdbc.queryForObject(
                      "select delete_date from "
                          + (consultant
                              ? "consultant where consultant_id=?"
                              : "user where user_id=?"),
                      java.sql.Timestamp.class,
                      ACCOUNT));
              verifyNoInteractions(memberships);
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
    var payload = new TreeMap<String, Object>();
    payload.put("realmId", REALM);
    payload.put("accountId", ACCOUNT);
    payload.put("operationId", operation);
    payload.put("command", "deactivate");
    payload.put("ownerClient", "backend-account-admin");
    payload.put("ownerSubject", MACHINE);
    payload.put("intentDigest", digest("maintenance-intent", body));
    payload.put("previousState", body.path("expectedState").textValue());
    payload.put("tenantId", "7");
    payload.put("originDigest", digest("maintenance-origin", body.path("target").path("origin")));
    payload.put("result", "UPDATED");
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
    String receipt =
        encoded + "." + HexFormat.of().formatHex(hmac("maintenance-operation-receipt\n" + encoded));
    return codec.canonical(
        Map.of(
            "operationId",
            operation,
            "accountId",
            ACCOUNT,
            "result",
            "UPDATED",
            "receipt",
            receipt));
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
    var target =
        new ConsultantAdminService(
            consultants,
            store,
            mock(CreateConsultantSaga.class),
            mock(ConsultantUpdateService.class),
            presteps,
            sessions,
            mock(MembersOnlyChatAccess.class),
            memberships,
            mock(SessionOwnershipService.class),
            mock(CaseHandoverRequestRepository.class),
            mock(SessionSupervisorRepository.class),
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
