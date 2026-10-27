package de.caritas.cob.userservice.api.service.accountinvite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.KeycloakHumanAdminMaintenance;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** Actual public role producer, JPA and signed HTTP boundary; no native acceptance is inferred. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles({"testing", "scenario-c-consultant-grant-jpa"})
@TestPropertySource(
    properties = {
      "multitenancy.enabled=true",
      "matrix.serverName=fixture.invalid",
      "logging.level.org.springframework.transaction.support.TransactionTemplate=DEBUG",
      "spring.datasource.url=jdbc:h2:mem:consultant-grant-${random.uuid};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE,DAY"
    })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  JpaAuditingConfiguration.class,
  de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService
      .class,
  de.caritas.cob.userservice.api.service.ConsultantService.class,
  de.caritas.cob.userservice.api.service.ConsultantAgencyService.class,
  de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.class,
  de.caritas.cob.userservice.api.helper.UserHelper.class,
  de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver.class,
  de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation
      .ConsultantAgencyRelationCreatorService.class,
  de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation
      .ConsultantAgencyRelationFinalizer.class,
  de.caritas.cob.userservice.api.admin.service.consultant.validation
      .ConsultantTopicAgencyCompatibilityValidator.class,
  AccountInviteAccessPolicy.class,
  AdminScope.class,
  GrantConsultantIdentityScopedJpaTest.Configuration.class
})
class GrantConsultantIdentityScopedJpaTest {
  private static final String ACCOUNT = "72000000-0000-4000-8000-000000000001";
  private static final String HUMAN = "72000000-0000-4000-8000-000000000002";
  private static final String OPERATION = "72000000-0000-4000-8000-000000000003";
  private static final String SUBJECT = "72000000-0000-4000-8000-000000000004";
  private static final String ISSUER = "https://identity.test/realms/fixture";
  private static final String ENDPOINT =
      ISSUER + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT;
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final Set<String> ORIGINAL = Set.of("restricted-agency-admin", "user-admin");
  private static final Set<String> DESIRED =
      Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin");
  private static final String EXPECTED_MATRIX = "@owned-counsellor:fixture.invalid";

  @Autowired
  private de.caritas.cob.userservice.api.admin.service.consultant.create
          .GrantConsultantIdentityService
      service;

  @Autowired private HumanAdminAccountMaintenance maintenance;
  @Autowired private ConsultantRepository consultants;
  @Autowired private ConsultantAgencyRepository consultantAgencies;
  @Autowired private AdminRepository admins;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entities;
  @Autowired private AuthenticatedUser human;
  @Autowired private MockHttpServletRequest request;
  @Autowired private RestTemplate http;
  @Autowired private TaskActorConfiguration actors;
  @Autowired private TaskActorTokenBroker broker;
  @Autowired private AccountLifecycleCodec codec;
  @Autowired private MatrixConfig configuredMatrix;
  @MockitoBean private IdentityClient generic;
  @MockitoBean private AgencyService agencies;
  @MockitoBean private AgencyFacts selectedAgency;
  private JdbcTemplate jdbc;
  private MockRestServiceServer server;
  private final AtomicInteger reads = new AtomicInteger();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger effects = new AtomicInteger();
  private Set<String> nativeRoles = ORIGINAL;
  private String nativeBody, nativeReply;
  private boolean lostReply, revoked;
  private boolean lostReadReply;
  @MockitoBean private IdentityRoleUpdater genericRoles;
  @MockitoBean private IdentityRoleLookup genericRoleLookup;
  @MockitoBean private MatrixUserClient matrix;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private de.caritas.cob.userservice.api.service.ConsultantService localConsultants;

  @MockitoBean private de.caritas.cob.userservice.api.admin.service.tenant.TenantService tenants;

  @MockitoBean
  private de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager
      consultingTypes;

  @MockitoBean private de.caritas.cob.userservice.api.port.in.AccountManaging unusedAccounts;

  @MockitoBean
  private de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper unusedUserMapper;

  @Autowired private java.util.concurrent.atomic.AtomicInteger joinedEvents;
  private final AtomicInteger matrixCalls = new AtomicInteger();
  private boolean matrixLostReply, matrixInUse;
  private String matrixResult = EXPECTED_MATRIX;

  @BeforeEach
  void actualCurrentHumanSqlAndScopedReceiver() throws Exception {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    // The real configuration binder runs after @Bean initialization; a setter on the fixture
    // alone does not override application.properties' empty MATRIX_SERVER_NAME default.
    assertEquals("fixture.invalid", configuredMatrix.getServerName());
    assertEquals("fixture.invalid", ReflectionTestUtils.getField(service, "matrixServer"));
    assertSame(maintenance, ReflectionTestUtils.getField(service, "maintenance"));
    assertTrue(maintenance.isFiveActorMode());
    var actualJpaOwner = (EntityManager) ReflectionTestUtils.getField(service, "entities");
    assertNotNull(actualJpaOwner);
    assertSame(entities.getEntityManagerFactory(), actualJpaOwner.getEntityManagerFactory());
    TenantContext.clear();
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop table if exists general_admin_identity_intent");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261022_general_admin_identity_367/migrate.sql"))
        .execute(dataSource);
    jdbc.execute(
        "alter table general_admin_identity_intent add column if not exists matrix_result_json varchar(1024) null");
    // The original table DDL is portable; its separate MySQL DATE_ADD rollout is not this test.
    try (var migration =
        new ClassPathResource("db/changelog/changeset/20260916_account_inactivity/migrate.sql")
            .getInputStream()) {
      String actualSql = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
      jdbc.execute(actualSql.substring(0, actualSql.indexOf(';') + 1));
    } catch (java.io.IOException invalid) {
      throw new AssertionError(invalid);
    }
    jdbc.update("delete from account_inactivity where identity_id=?", ACCOUNT);
    when(selectedAgency.find(71L))
        .thenAnswer(
            ignored -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return Optional.of(new AgencyFacts.Agency(71L, 7L, false, List.of()));
            });
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              entities.clear();
              jdbc.update("delete from admin_agency where admin_id=?", ACCOUNT);
              jdbc.update("delete from admin where admin_id=?", ACCOUNT);
              jdbc.update("delete from consultant_agency where consultant_id=?", ACCOUNT);
              jdbc.update("delete from consultant_topic where consultant_id=?", ACCOUNT);
              jdbc.update("delete from language where consultant_id=?", ACCOUNT);
              jdbc.update("delete from consultant where consultant_id=?", ACCOUNT);
              entities.flush();
              entities.clear();
            });
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Integer.class, ACCOUNT));
    var born = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var persistedBorn = new java.util.concurrent.atomic.AtomicReference<LocalDateTime>();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              var admin =
                  de.caritas.cob.userservice.api.model.Admin.builder()
                      .id(ACCOUNT)
                      .type(de.caritas.cob.userservice.api.model.Admin.AdminType.AGENCY)
                      .tenantId(7L)
                      .createDate(born)
                      .updateDate(born)
                      .username("owned-counsellor")
                      .firstName("Ada")
                      .lastName("Lovelace")
                      .email("ada@fixture.invalid")
                      .build();
              entities.persist(admin);
              entities.flush();
              entities.refresh(admin);
              persistedBorn.set(admin.getCreateDate());
              entities.clear();
            });
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertNotNull(persistedBorn.get());
    assertEquals(
        persistedBorn.get(),
        jdbc.queryForObject(
                "select create_date from admin where admin_id=?", java.sql.Timestamp.class, ACCOUNT)
            .toLocalDateTime());
    human.setUserId(HUMAN);
    human.setTenantId(7L);
    human.setAccessToken("fixture-human");
    human.setGrantedAuthorities(
        Set.of(Authority.AuthorityValue.USER_ADMIN, Authority.AuthorityValue.TENANT_ADMIN));
    request.setMethod("POST");
    request.setRequestURI("/useradmin/admins/" + ACCOUNT + "/grant-consultant-identity");
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", OPERATION);
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer fixture-human");
    request.setUserPrincipal(authentication(7L));
    assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
    maintenance.configureReviewedOrdinaryGroups(
        Map.of(
            "/human-roles/agency-admin",
            ORIGINAL,
            "/human-roles/consultant-agency-admin-chat",
            DESIRED));
    doThrow(new ScopedIdentityCommandRequiredException())
        .when(generic)
        .updateRole(anyString(), any(UserRole.class));
    joinedEvents.set(0);
    doThrow(new ScopedIdentityCommandRequiredException())
        .when(genericRoles)
        .ensureRoles(anyString(), anySet());
    var agency =
        new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
            .id(71L)
            .tenantId(7L)
            .consultingType(1)
            .teamAgency(false)
            .topicIds(List.of());
    when(agencies.getAgency(71L)).thenReturn(agency);
    when(agencies.getAgenciesWithoutCaching(anyList()))
        .thenAnswer(
            invocation -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return List.of(agency);
            });
    when(consultingTypes.getConsultingTypeSettings(1))
        .thenReturn(
            new de.caritas.cob.userservice.consultingtypeservice.generated.web.model
                .ExtendedConsultingTypeResponseDTO());
    when(tenants.getRestrictedTenantDataFresh(7L))
        .thenAnswer(
            invocation -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return new de.caritas.cob.userservice.tenantservice.generated.web.model
                      .RestrictedTenantDTO()
                  .id(7L)
                  .settings(
                      new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                          .tenantAdminControls(
                              new de.caritas.cob.userservice.tenantservice.generated.web.model
                                      .TenantAdminControls()
                                  .chatRecoverySettings(
                                      new de.caritas.cob.userservice.tenantservice.generated.web
                                              .model.ChatRecoverySettings()
                                          .asker(
                                              de.caritas.cob.userservice.tenantservice.generated.web
                                                  .model.ChatRecoveryMode.RECOVERY_KEY)
                                          .consultant(
                                              de.caritas.cob.userservice.tenantservice.generated.web
                                                  .model.ChatRecoveryMode.RECOVERY_KEY)
                                          .revision(0L))));
            });
    when(matrix.createUserIdWithoutReactivation(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("ACKNOWLEDGED", phase());
              assertEquals("STARTED", matrixPhase());
              assertEquals("owned-counsellor", invocation.getArgument(0));
              matrixCalls.incrementAndGet();
              if (matrixInUse)
                throw new de.caritas.cob.userservice.api.exception.matrix
                    .MatrixUserInUseException();
              if (matrixLostReply)
                throw new ResourceAccessException("Synthetic lost original Matrix reply");
              return matrixResult;
            });
    // Independently preflight the actual uncached source facts before entering the public producer.
    assertEquals(List.of(agency), agencies.getAgenciesWithoutCaching(List.of(71L)));
    assertEquals(7L, tenants.getRestrictedTenantDataFresh(7L).getId());
    var actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "fixture-secret", SUBJECT);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
    var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
    when(checked.clientId()).thenReturn(actor.clientId());
    when(checked.subject()).thenReturn(SUBJECT);
    when(checked.issuer()).thenReturn(ISSUER);
    when(checked.accessToken()).thenReturn("fixture-machine");
    when(checked.validatedJwtIssuedAt()).thenReturn(Instant.now().minusSeconds(2).getEpochSecond());
    when(checked.validatedJwtExpiresAt())
        .thenReturn(Instant.now().plusSeconds(300).getEpochSecond());
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
    server = MockRestServiceServer.bindTo(http).build();
    server
        .expect(
            manyTimes(),
            outbound ->
                assertTrue(
                    Set.of(ENDPOINT + "/read", ENDPOINT + "/profile-transition")
                        .contains(outbound.getURI().toString())))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(HttpMethod.POST, outbound.getMethod());
              assertEquals(
                  "Bearer fixture-machine", outbound.getHeaders().getFirst("Authorization"));
              assertEquals(
                  "Bearer fixture-human",
                  outbound.getHeaders().getFirst("X-ORISO-Human-Authorization"));
              JsonNode body =
                  codec.read(((MockClientHttpRequest) outbound).getBodyAsString(), JsonNode.class);
              String command =
                  outbound.getURI().getPath().endsWith("/read") ? "read" : "profile-transition";
              verifyProof(outbound.getHeaders().getFirst("X-ORISO-Task-Context"), command, body);
              if ("read".equals(command)) {
                if (nativeBody == null) {
                  var held =
                      jdbc.queryForMap(
                          "select status,original_json,native_body,native_receipt from general_admin_identity_intent where operation_id=?",
                          OPERATION);
                  assertEquals("PREPARED", held.get("status"));
                  assertNull(held.get("native_body"));
                  assertNull(held.get("native_receipt"));
                  var source = codec.read((String) held.get("original_json"), JsonNode.class);
                  var tables = codec.read(source.path("sql").textValue(), JsonNode.class);
                  var row = codec.read(tables.path("admin").get(0).textValue(), JsonNode.class);
                  assertEquals(ACCOUNT, row.path("admin_id").textValue());
                  assertNotNull(row.path("create_date").textValue());
                }
                reads.incrementAndGet();
                if (lostReadReply) {
                  lostReadReply = false;
                  throw new ResourceAccessException("Synthetic lost first role read");
                }
                var projection = new TreeMap<String, Object>();
                projection.put("id", ACCOUNT);
                projection.put("username", "owned-counsellor");
                projection.put("firstName", "Ada");
                projection.put("lastName", "Lovelace");
                projection.put("email", "ada@fixture.invalid");
                projection.put("enabled", true);
                projection.put("emailVerified", true);
                projection.put("tenantId", "7");
                projection.put("preferredLanguage", "en");
                projection.put("effectiveRoles", nativeRoles.stream().sorted().toList());
                projection.put("passwordChangeRequired", false);
                projection.put("state", "a".repeat(64));
                var response =
                    withSuccess(codec.canonical(projection), MediaType.APPLICATION_JSON)
                        .createResponse(outbound);
                response.getHeaders().setCacheControl("no-store");
                return response;
              }
              calls.incrementAndGet();
              assertEquals(OPERATION, body.path("operationId").textValue());
              assertEquals(
                  Map.of(
                      "transition",
                      "ADD_CONSULTANT_IDENTITY",
                      "group",
                      "/human-roles/consultant-agency-admin-chat"),
                  codec.read(codec.canonical(body.path("change")), Map.class));
              if (revoked)
                return org.springframework.test.web.client.response.MockRestResponseCreators
                    .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                    .createResponse(outbound);
              if (nativeBody == null) {
                nativeBody = codec.canonical(body);
                nativeRoles = DESIRED;
                effects.incrementAndGet();
                nativeReply = receipt(body);
              } else assertEquals(nativeBody, codec.canonical(body));
              if (lostReply) {
                lostReply = false;
                throw new ResourceAccessException("Synthetic lost native reply");
              }
              var response =
                  withSuccess(nativeReply, MediaType.APPLICATION_JSON).createResponse(outbound);
              response.getHeaders().setCacheControl("no-store");
              return response;
            });
  }

  @Test
  void theActualAdminGrantAcknowledgesNativeAndOwnedMatrixBeforeItsWholeSqlCallback() {
    var before = adminSql();
    var originalTenant = TenantContext.getCurrentTenantData();
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertSame(originalTenant, TenantContext.getCurrentTenantData());
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals("APPLIED", phase());
    assertEquals(before, adminSql());
    assertEquals(DESIRED, nativeRoles);
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Integer.class, ACCOUNT));
    assertEquals(
        EXPECTED_MATRIX,
        jdbc.queryForObject(
            "select matrix_user_id from consultant where consultant_id=?", String.class, ACCOUNT));
    assertEquals(
        List.of(71L),
        jdbc.queryForList(
            "select agency_id from consultant_agency where consultant_id=?", Long.class, ACCOUNT));
    assertEquals("ACKNOWLEDGED", matrixPhase());
    assertEquals(1, joinedEvents.get());
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals(1, joinedEvents.get());
    verifyNoInteractions(generic, genericRoles, genericRoleLookup);
  }

  @Test
  void aLostNativeReplyCannotCreateMatrixOrConsultantAndExactRetryUsesTheSameEffect() {
    lostReply = true;
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, effects.get());
    assertEquals(0, matrixCalls.get());
    assertEquals("FROZEN", phase());
    assertNoLocalConsultant();
    String original = nativeBody;
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(original, nativeBody);
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals("APPLIED", phase());
  }

  @Test
  void anUnknownMatrixCreateRemainsStartedAndRetryNeverReemitsOrAdopts() throws Exception {
    matrixLostReply = true;
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals("ACKNOWLEDGED", phase());
    assertEquals("STARTED", matrixPhase());
    assertNoLocalConsultant();
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertNoLocalConsultant();
    verify(matrix, never()).findUserId(anyString());
    verify(matrix, never()).createUserId(anyString(), anyString(), anyString());
    verify(matrix, never()).userExistsStrict(anyString());
  }

  @Test
  void onlyTheOriginalDefiniteMatrixUserInUseCanFinishWithoutAMatrixIdentity() throws Exception {
    matrixInUse = true;
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals("APPLIED", phase());
    assertEquals("NO_EFFECT", matrixPhase());
    assertNull(
        jdbc.queryForObject(
            "select matrix_user_id from consultant where consultant_id=?", String.class, ACCOUNT));
    assertNotNull(
        jdbc.queryForObject(
            "select native_receipt from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION));
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, matrixCalls.get());
    verify(matrix, never()).findUserId(anyString());
    verify(matrix, never()).createUserId(anyString(), anyString(), anyString());
  }

  @Test
  void aForeignReturnedMatrixIdCannotBecomeSqlOwnershipOrBeRetried() {
    matrixResult = "@somebody-else:fixture.invalid";
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals("STARTED", matrixPhase());
    assertNoLocalConsultant();
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, matrixCalls.get());
    assertNoLocalConsultant();
  }

  @Test
  void theActualForeignTenantRefusalPrecedesNativeMatrixAndAllLocalGrantWrites() {
    human.setTenantId(8L);
    request.setUserPrincipal(authentication(8L));
    assertThrows(
        ForbiddenException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(0, reads.get());
    assertEquals(0, calls.get());
    assertEquals(0, matrixCalls.get());
    assertNoLocalConsultant();
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from general_admin_identity_intent where operation_id=?",
            Integer.class,
            OPERATION));
  }

  @Test
  void firstReadLossKeepsOriginalGrantSourceAndRejectsRecreatedAdminBeforeAnotherRead() {
    lostReadReply = true;
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals("PREPARED", phase());
    assertEquals(1, reads.get());
    assertEquals(0, effects.get());
    assertEquals(0, matrixCalls.get());
    var birth =
        jdbc.queryForObject(
            "select create_date from admin where admin_id=?", java.sql.Timestamp.class, ACCOUNT);
    jdbc.update(
        "update admin set create_date=? where admin_id=?",
        java.sql.Timestamp.valueOf(birth.toLocalDateTime().plusSeconds(1)),
        ACCOUNT);
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, reads.get());
    assertEquals(0, calls.get());
    assertEquals(0, matrixCalls.get());
    assertNoLocalConsultant();
  }

  @Test
  void appliedGrantReplayNeedsCurrentReceivingGrantAndNeverRepeatsMatrixOrSql() {
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    int originalReads = reads.get();
    String originalReceipt =
        jdbc.queryForObject(
            "select native_receipt from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION);
    var originalSql = jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT);
    revoked = true;
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals("APPLIED", phase());
    assertEquals(originalReads, reads.get());
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals(1, joinedEvents.get());
    assertEquals(
        originalSql, jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT));
    revoked = false;
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(originalReads, reads.get());
    assertEquals(
        originalReceipt,
        jdbc.queryForObject(
            "select native_receipt from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION));
    assertEquals(1, matrixCalls.get());
    assertEquals(1, joinedEvents.get());
    assertEquals(
        originalSql, jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT));
  }

  @Test
  void acknowledgedMatrixCannotBeReplacedByAnUnboundNoEffectBeforeLocalRetry() {
    var inject = new java.util.concurrent.atomic.AtomicBoolean(true);
    doAnswer(
            invocation -> {
              var result =
                  (de.caritas.cob.userservice.api.model.Consultant) invocation.callRealMethod();
              if (inject.getAndSet(false)) result.setSupervisor(true);
              return result;
            })
        .when(localConsultants)
        .saveConsultant(any(de.caritas.cob.userservice.api.model.Consultant.class));
    var originalAdmin = adminSql();
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals("ACKNOWLEDGED", phase());
    assertEquals("ACKNOWLEDGED", matrixPhase());
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals(0, joinedEvents.get());
    assertEquals(originalAdmin, adminSql());
    assertNoLocalConsultant();
    String original =
        jdbc.queryForObject(
            "select matrix_result_json from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION);
    var tampered =
        (com.fasterxml.jackson.databind.node.ObjectNode) codec.read(original, JsonNode.class);
    tampered.put("phase", "NO_EFFECT");
    tampered.putNull("result");
    jdbc.update(
        "update general_admin_identity_intent set matrix_result_json=? where operation_id=?",
        codec.canonical(tampered),
        OPERATION);
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, matrixCalls.get());
    assertEquals(0, joinedEvents.get());
    assertNoLocalConsultant();
    jdbc.update(
        "update general_admin_identity_intent set matrix_result_json=? where operation_id=?",
        original,
        OPERATION);
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals("APPLIED", phase());
    assertEquals(
        EXPECTED_MATRIX,
        jdbc.queryForObject(
            "select matrix_user_id from consultant where consultant_id=?", String.class, ACCOUNT));
    assertEquals(1, matrixCalls.get());
    assertEquals(1, joinedEvents.get());
  }

  @Test
  void jointGrantCreationColumnAndOriginalTamperCannotRebindTheFirstReadAttempt() {
    lostReadReply = true;
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals("PREPARED", phase());
    assertEquals(1, reads.get());
    String originalJson =
        jdbc.queryForObject(
            "select original_json from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION);
    var original =
        (com.fasterxml.jackson.databind.node.ObjectNode) codec.read(originalJson, JsonNode.class);
    var changed = java.time.Instant.parse(original.path("creation").textValue()).plusSeconds(1);
    original.put("creation", changed.toString());
    jdbc.update(
        "update general_admin_identity_intent set original_json=?,created_at=? where operation_id=?",
        codec.canonical(original),
        java.sql.Timestamp.from(changed),
        OPERATION);
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, reads.get());
    assertEquals(0, calls.get());
    assertEquals(0, matrixCalls.get());
    assertNoLocalConsultant();
  }

  @Test
  void missingConsultingTypeSettingsCannotAuthorizeAnAssumedEmptyRequiredRoleSet() {
    when(consultingTypes.getConsultingTypeSettings(1)).thenReturn(null);
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(0, reads.get());
    assertEquals(0, calls.get());
    assertEquals(0, matrixCalls.get());
    assertNoLocalConsultant();
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from general_admin_identity_intent where operation_id=?",
            Integer.class,
            OPERATION));
  }

  @Test
  void onlyTheActualSourceSelectedConsultingRoleSetEntersTheFrozenGrantUnion() {
    var required = new java.util.LinkedHashMap<String, java.util.List<String>>();
    required.put(null, java.util.List.of("group-chat-consultant"));
    required.put("unselected", java.util.List.of("technical"));
    var consultantRoles =
        new de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant();
    consultantRoles.setRoleSets(required);
    var roles = new de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RolesDTO();
    roles.setConsultant(consultantRoles);
    var settings =
        new de.caritas.cob.userservice.consultingtypeservice.generated.web.model
            .ExtendedConsultingTypeResponseDTO();
    settings.setRoles(roles);
    when(consultingTypes.getConsultingTypeSettings(1)).thenReturn(settings);
    var input = command();
    input.setGroupchatConsultant(false);
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, input));
    assertEquals(DESIRED, nativeRoles);
    assertEquals("APPLIED", phase());
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals(1, joinedEvents.get());
  }

  private de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO command() {
    var command = new de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO();
    command.setAgencyIds(List.of(71L));
    command.setTopicIds(List.of());
    command.setGroupchatConsultant(true);
    command.setFormalLanguage(true);
    command.setAbsent(false);
    return command;
  }

  private Map<String, Object> adminSql() {
    return new TreeMap<>(jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT));
  }

  private String matrixPhase() {
    String raw =
        jdbc.queryForObject(
            "select matrix_result_json from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION);
    assertNotNull(raw);
    return codec.read(raw, JsonNode.class).path("phase").textValue();
  }

  private void assertNoLocalConsultant() {
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Integer.class, ACCOUNT));
  }

  private JwtAuthenticationToken authentication(long tenant) {
    var jwt =
        Jwt.withTokenValue("fixture-human")
            .header("alg", "RS256")
            .subject(HUMAN)
            .issuer(ISSUER)
            .issuedAt(Instant.now().minusSeconds(2))
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("azp", "admin")
            .claim("tenantId", Long.toString(tenant))
            .build();
    return new JwtAuthenticationToken(jwt, List.of());
  }

  private String phase() {
    return jdbc.queryForObject(
        "select status from general_admin_identity_intent where operation_id=?",
        String.class,
        OPERATION);
  }

  private Map<String, Object> consultantSql() {
    return new TreeMap<>(
        jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT));
  }

  private String receipt(JsonNode body) {
    var value = new TreeMap<String, Object>();
    value.put("realmId", "fixture-realm");
    value.put("accountId", ACCOUNT);
    value.put("operationId", OPERATION);
    value.put("command", "profile-transition");
    value.put("ownerClient", "backend-account-admin");
    value.put("ownerSubject", SUBJECT);
    value.put("intentDigest", digest("maintenance-intent", body));
    value.put("previousState", "a".repeat(64));
    value.put("tenantId", "7");
    value.put("originDigest", digest("maintenance-origin", body.path("target").path("origin")));
    value.put("result", "UPDATED");
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(value).getBytes(StandardCharsets.UTF_8));
    return codec.canonical(
        Map.of(
            "operationId",
            OPERATION,
            "accountId",
            ACCOUNT,
            "result",
            "UPDATED",
            "receipt",
            encoded + "." + mac("maintenance-operation-receipt\n" + encoded)));
  }

  private void verifyProof(String proof, String command, JsonNode body) {
    String[] parts = proof.split("\\.", -1);
    assertEquals(2, parts.length);
    byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
    assertEquals(32, signature.length);
    assertEquals(parts[1], Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
    assertArrayEquals(HexFormat.of().parseHex(mac("proof\n" + parts[0])), signature);
    var claims =
        codec.read(
            new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
            JsonNode.class);
    var fields = new java.util.HashSet<String>();
    claims.fieldNames().forEachRemaining(fields::add);
    assertEquals(
        Set.of(
            "iss",
            "aud",
            "purpose",
            "operation",
            "method",
            "actor",
            "realm",
            "target",
            "taskClient",
            "taskSubject",
            "payloadDigest",
            "jti",
            "nonce",
            "iat",
            "exp"),
        fields);
    assertEquals("userservice", claims.path("iss").textValue());
    assertEquals("oriso-account-maintenance", claims.path("aud").textValue());
    assertEquals("POST", claims.path("method").textValue());
    assertEquals("fixture", claims.path("realm").textValue());
    assertEquals(
        claims.path("jti").textValue(),
        java.util.UUID.fromString(claims.path("jti").textValue()).toString());
    assertTrue(claims.path("iat").isIntegralNumber());
    assertTrue(claims.path("exp").isIntegralNumber());
    assertTrue(claims.path("iat").longValue() > 0);
    assertTrue(claims.path("iat").longValue() <= Instant.now().getEpochSecond() + 5);
    assertTrue(claims.path("exp").longValue() > Instant.now().getEpochSecond());
    assertEquals(claims.path("iat").longValue() + 60, claims.path("exp").longValue());
    assertEquals("account." + command, claims.path("operation").textValue());
    assertEquals("ACCOUNT_ADMIN", claims.path("actor").textValue());
    assertEquals("ACCOUNT_MAINTENANCE", claims.path("purpose").textValue());
    assertEquals("backend-account-admin", claims.path("taskClient").textValue());
    assertEquals(SUBJECT, claims.path("taskSubject").textValue());
    assertEquals(digest("payload", body), claims.path("payloadDigest").textValue());
    assertEquals("realm/fixture-realm/account/" + ACCOUNT, claims.path("target").textValue());
    assertTrue(claims.path("nonce").textValue().matches("[0-9a-f]{32}"));
  }

  private String digest(String domain, Object value) {
    return mac(domain + "\n" + codec.canonical(value));
  }

  private String mac(String value) {
    try {
      var hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      return HexFormat.of().formatHex(hmac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception invalid) {
      throw new AssertionError(invalid);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Profile("scenario-c-consultant-grant-jpa")
  static class Configuration {
    @Bean
    UsernameTranscoder grantUsernameTranscoder() {
      return new UsernameTranscoder();
    }

    @Bean
    de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig grantMatrixConfiguration() {
      var config = new de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig();
      config.setServerName("fixture.invalid");
      return config;
    }

    @Bean
    java.util.concurrent.atomic.AtomicInteger grantJoinedEvents() {
      return new java.util.concurrent.atomic.AtomicInteger();
    }

    @Bean
    Object grantEventRecorder(java.util.concurrent.atomic.AtomicInteger events) {
      return new Object() {
        @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
        public void joined(
            de.caritas.cob.userservice.api.service.session.ConsultantJoinedAgencyEvent event) {
          events.incrementAndGet();
        }
      };
    }

    @Bean
    AuthenticatedUser authenticatedUser() {
      return new AuthenticatedUser();
    }

    @Bean
    MockHttpServletRequest currentRoleRequest() {
      return new MockHttpServletRequest();
    }

    @Bean
    RestTemplate roleHttp() {
      return new RestTemplate();
    }

    @Bean
    AccountLifecycleCodec roleCodec() {
      return new AccountLifecycleCodec(KEY);
    }

    @Bean
    TaskActorConfiguration roleActors() {
      return mock(TaskActorConfiguration.class);
    }

    @Bean
    TaskActorTokenBroker roleBroker() {
      return mock(TaskActorTokenBroker.class);
    }

    @Bean
    IdentityClientConfig roleMode() {
      var mode = mock(IdentityClientConfig.class);
      when(mode.isFiveActorMode()).thenReturn(true);
      return mode;
    }

    @Bean
    HumanAdminAccountMaintenance roleMaintenance(
        IdentityClientConfig mode,
        AuthenticatedUser actor,
        AdminScope scope,
        DataSource source,
        PlatformTransactionManager manager,
        MockHttpServletRequest request,
        RestTemplate http,
        TaskActorConfiguration actors,
        TaskActorTokenBroker broker,
        AccountLifecycleCodec codec) {
      return new HumanAdminAccountMaintenance(
          mode,
          actor,
          scope,
          new JdbcTemplate(source),
          manager,
          request,
          new KeycloakHumanAdminMaintenance(
              http, actors, broker, codec, "https://identity.test", "fixture", "fixture-realm"),
          Clock.systemUTC(),
          ISSUER);
    }
  }
}
