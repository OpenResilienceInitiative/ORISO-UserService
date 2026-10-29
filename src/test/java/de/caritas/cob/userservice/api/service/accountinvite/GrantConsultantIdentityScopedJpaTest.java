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
  de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService.class,
  de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService.class,
  de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService.class,
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
  AdminSelfAssignmentService.class,
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

  @Autowired
  private de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService removeAdmin;

  @Autowired
  private de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService updateAdmin;

  @MockitoBean private IdentityProfileUpdater genericProfile;

  @MockitoBean
  private de.caritas.cob.userservice.api.admin.service.consultant.validation
          .UserAccountInputValidator
      adminInputValidator;

  @MockitoBean private IdentityAccountRemover genericRemoval;
  @Autowired private AdminSelfAssignmentService selfAssignments;
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
  private String operation = OPERATION;
  private String nativeCommand = "profile-transition";
  private String nativeOutcome = "UPDATED";
  private final AtomicInteger reads = new AtomicInteger();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger effects = new AtomicInteger();
  private Set<String> nativeRoles = ORIGINAL;
  private Set<String> nativeDesiredRoles = DESIRED;
  private Map<String, Object> expectedRoleChange =
      Map.of(
          "transition",
          "ADD_CONSULTANT_IDENTITY",
          "group",
          "/human-roles/consultant-agency-admin-chat");
  private String nativeBody, nativeReply;
  private boolean lostReply, revoked;
  private boolean lostReadReply;
  private boolean importing;
  private boolean movingTenant;
  private String loseTenantReply, refuseTenantCommand;
  private boolean signedTenantRefusal, plainTenantProfile;
  private long nativeTenant = 7L;
  private String nativeFirst = "Ada",
      nativeLast = "Lovelace",
      nativeEmail = "ada@fixture.invalid",
      nativeState = "a".repeat(64);
  private final Map<String, String> tenantBodies = new TreeMap<>(), tenantReplies = new TreeMap<>();
  private boolean importNoEffect;
  private boolean tamperImportNoEffect;
  private Runnable afterImportRead = () -> {};
  private boolean staleSelfToken;
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
    human.setRoles(null);
    human.setUserId(HUMAN);
    human.setTenantId(7L);
    human.setAccessToken("fixture-human");
    human.setGrantedAuthorities(
        Set.of(Authority.AuthorityValue.USER_ADMIN, Authority.AuthorityValue.TENANT_ADMIN));
    request.setMethod("POST");
    request.setRequestURI("/useradmin/admins/" + ACCOUNT + "/grant-consultant-identity");
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", operation);
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
                    Set.of(
                            ENDPOINT + "/read",
                            ENDPOINT + "/profile-transition",
                            ENDPOINT + "/ordinary-profile",
                            ENDPOINT + "/delete",
                            ENDPOINT + "/reassign-tenant",
                            ENDPOINT + "/profile")
                        .contains(outbound.getURI().toString())))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(HttpMethod.POST, outbound.getMethod());
              assertEquals(
                  "Bearer fixture-machine", outbound.getHeaders().getFirst("Authorization"));
              assertEquals(
                  importing ? null : "Bearer fixture-human",
                  outbound.getHeaders().getFirst("X-ORISO-Human-Authorization"));
              JsonNode body =
                  codec.read(((MockClientHttpRequest) outbound).getBodyAsString(), JsonNode.class);
              if (importing) operation = body.path("workflow").path("workId").textValue();
              String command =
                  movingTenant
                      ? outbound
                          .getURI()
                          .getPath()
                          .substring(outbound.getURI().getPath().lastIndexOf('/') + 1)
                      : outbound.getURI().getPath().endsWith("/read") ? "read" : nativeCommand;
              verifyProof(outbound.getHeaders().getFirst("X-ORISO-Task-Context"), command, body);
              if (movingTenant) return tenantEditResponse(outbound, body, command);
              if ("read".equals(command)) {
                if (nativeBody == null) {
                  var held =
                      jdbc.queryForMap(
                          "select status,original_json,native_body,native_receipt from general_admin_identity_intent where operation_id=?",
                          operation);
                  assertEquals("PREPARED", held.get("status"));
                  assertNull(held.get("native_body"));
                  assertNull(held.get("native_receipt"));
                  var source = codec.read((String) held.get("original_json"), JsonNode.class);
                  var tables =
                      importing
                          ? source.path("originalSql")
                          : codec.read(source.path("sql").textValue(), JsonNode.class);
                  if (importing) {
                    assertEquals("EXISTING_CONSULTANT_IMPORT", source.path("kind").textValue());
                    assertEquals(1L, source.path("recordNumber").longValue());
                    assertEquals(ACCOUNT, source.path("record").path("consultantId").textValue());
                  }
                  var row =
                      codec.read(
                          tables.path(importing ? "consultant" : "admin").get(0).textValue(),
                          JsonNode.class);
                  assertEquals(
                      ACCOUNT, row.path(importing ? "consultant_id" : "admin_id").textValue());
                  assertNotNull(row.path("create_date").textValue());
                }
                reads.incrementAndGet();
                if (importing) afterImportRead.run();
                if (nativeBody != null && staleSelfToken)
                  return org.springframework.test.web.client.response.MockRestResponseCreators
                      .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                      .createResponse(outbound);
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
              assertEquals(operation, body.path("operationId").textValue());
              assertEquals(
                  codec.read(codec.canonical(expectedRoleChange), JsonNode.class),
                  body.path("change"));
              if (importing && importNoEffect) return importRefusal(body, outbound);
              if (revoked || nativeBody != null && staleSelfToken)
                return org.springframework.test.web.client.response.MockRestResponseCreators
                    .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                    .createResponse(outbound);
              if (nativeBody == null) {
                nativeBody = codec.canonical(body);
                nativeRoles = nativeDesiredRoles;
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
  void installedGrantReadinessRequiresItsActualMatrixNameAndJpaOwnerWithoutEffects() {
    server.reset(); // Readiness must not request the token expected by mutation fixtures.
    var mode =
        (de.caritas.cob.userservice.api.port.out.IdentityClientConfig)
            ReflectionTestUtils.getField(service, "identityMode");
    var manager = (org.springframework.orm.jpa.JpaTransactionManager) transactions;
    assertTrue(service.hasScopedAccountBindings(mode, maintenance, manager));
    var missingName = new MatrixConfig();
    try {
      service.setMatrixConfiguration(missingName);
      assertFalse(service.hasScopedAccountBindings(mode, maintenance, manager));
      missingName.setServerName(" ");
      service.setMatrixConfiguration(missingName);
      assertFalse(service.hasScopedAccountBindings(mode, maintenance, manager));
    } finally {
      service.setMatrixConfiguration(configuredMatrix);
    }
    assertTrue(service.hasScopedAccountBindings(mode, maintenance, manager));
    assertFalse(service.hasScopedAccountBindings(mode, null, manager));
    assertFalse(
        service.hasScopedAccountBindings(
            mode, maintenance, new org.springframework.orm.jpa.JpaTransactionManager()));
    verifyNoInteractions(generic, genericRemoval, genericProfile);
    server.verify();
  }

  @Test
  void actualCombinedAdministratorRemovalPreservesAllConsultantDataAndExactReplay() {
    givenAdministratorRemoval(true);
    var before = consultantSql();
    var relations =
        jdbc.queryForList("select * from consultant_agency where consultant_id=?", ACCOUNT);
    var topics = jdbc.queryForList("select * from consultant_topic where consultant_id=?", ACCOUNT);
    assertDoesNotThrow(() -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals(before, consultantSql());
    assertEquals(
        relations,
        jdbc.queryForList("select * from consultant_agency where consultant_id=?", ACCOUNT));
    assertEquals(
        topics, jdbc.queryForList("select * from consultant_topic where consultant_id=?", ACCOUNT));
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Integer.class, ACCOUNT));
    assertEquals("APPLIED", phase());
    String frozen = nativeBody;
    assertDoesNotThrow(() -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals(frozen, nativeBody);
    assertEquals(before, consultantSql());
    assertEquals(2, effects.get());
    assertEquals(1, matrixCalls.get());
    verifyNoInteractions(genericRemoval);
  }

  @Test
  void actualPureAdministratorRemovalRequiresDeletedReceiptAndReplaysAfterRowDisappears() {
    givenAdministratorRemoval(false);
    assertDoesNotThrow(() -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Integer.class, ACCOUNT));
    assertEquals("APPLIED", phase());
    String frozen = nativeBody;
    assertDoesNotThrow(() -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals(frozen, nativeBody);
    assertEquals(1, effects.get());
    assertEquals(0, matrixCalls.get());
    verifyNoInteractions(genericRemoval);
  }

  @Test
  void administratorRemovalLostReplyKeepsAdminUntilExactOriginalRetry() {
    givenAdministratorRemoval(true);
    var before = consultantSql();
    lostReply = true;
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals("FROZEN", phase());
    assertEquals(
        1,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Integer.class, ACCOUNT));
    assertEquals(before, consultantSql());
    String frozen = nativeBody;
    revoked = true;
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals("FROZEN", phase());
    revoked = false;
    assertDoesNotThrow(() -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals(frozen, nativeBody);
    assertEquals("APPLIED", phase());
    assertEquals(before, consultantSql());
    assertEquals(2, effects.get());
  }

  @Test
  void administratorRemovalSqlFailureRollsBackEveryAdminRelationAndRetriesOriginalReceipt() {
    givenAdministratorRemoval(true);
    var before = consultantSql();
    var admin = adminSql();
    var relations = jdbc.queryForList("select * from admin_agency where admin_id=?", ACCOUNT);
    jdbc.execute(
        "create table retained_admin_remove_fault (admin_id varchar(36) references admin(admin_id))");
    try {
      jdbc.update("insert into retained_admin_remove_fault(admin_id) values(?)", ACCOUNT);
      assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
      assertEquals("ACKNOWLEDGED", phase());
      assertEquals(admin, adminSql());
      assertEquals(
          relations, jdbc.queryForList("select * from admin_agency where admin_id=?", ACCOUNT));
      assertEquals(before, consultantSql());
      String frozen = nativeBody;
      jdbc.update("delete from retained_admin_remove_fault");
      assertDoesNotThrow(() -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
      assertEquals(frozen, nativeBody);
      assertEquals("APPLIED", phase());
      assertEquals(before, consultantSql());
      assertEquals(2, effects.get());
    } finally {
      jdbc.execute("drop table retained_admin_remove_fault");
    }
  }

  @Test
  void pureAdministratorRemovalRejectsUpdatedInsteadOfDeletedReceipt() {
    givenAdministratorRemoval(false);
    nativeOutcome = "UPDATED";
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals("FROZEN", phase());
    assertEquals(
        1,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Integer.class, ACCOUNT));
  }

  @Test
  void administratorRemovalRejectsTargetRebirthBeforeAnotherHttpCommand() {
    givenAdministratorRemoval(true);
    lostReply = true;
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    int attempted = calls.get();
    jdbc.update(
        "update admin set create_date=dateadd('SECOND',1,create_date) where admin_id=?", ACCOUNT);
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals(attempted, calls.get());
    assertEquals("FROZEN", phase());
  }

  @Test
  void protectedCombinedAdministratorRemovalKeepsAllConsultantData() {
    givenProtectedTenant();
    maintenance.configureReviewedProtectedProfiles(protectedCatalog(true));
    givenAdministratorRemoval(true);
    var before = consultantSql();
    assertDoesNotThrow(() -> removeAdmin.deleteTenantAdmin(ACCOUNT));
    assertEquals(before, consultantSql());
    assertEquals(Set.of("consultant", "group-chat-consultant"), nativeRoles);
    assertEquals("APPLIED", phase());
    assertDoesNotThrow(() -> removeAdmin.deleteTenantAdmin(ACCOUNT));
    assertEquals(before, consultantSql());
    assertEquals(2, effects.get());
  }

  @Test
  void protectedPureAdministratorDeletionUsesExactOriginalDeletedReceipt() {
    givenProtectedTenant();
    maintenance.configureReviewedProtectedProfiles(protectedCatalog(true));
    givenAdministratorRemoval(false);
    assertDoesNotThrow(() -> removeAdmin.deleteTenantAdmin(ACCOUNT));
    assertEquals("APPLIED", phase());
    assertDoesNotThrow(() -> removeAdmin.deleteTenantAdmin(ACCOUNT));
    assertEquals(1, effects.get());
  }

  @Test
  void administratorRemovalRestartsFromPersistedOriginalCommandWithoutANewNativeEffect() {
    givenAdministratorRemoval(true);
    lostReply = true;
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    String frozen = nativeBody;
    var mode = (IdentityClientConfig) ReflectionTestUtils.getField(removeAdmin, "mode");
    var resumed =
        new HumanAdminAccountMaintenance(
            mode,
            human,
            (AdminScope) ReflectionTestUtils.getField(maintenance, "scope"),
            jdbc,
            transactions,
            request,
            new KeycloakHumanAdminMaintenance(
                http, actors, broker, codec, "https://identity.test", "fixture", "fixture-realm"),
            Clock.systemUTC(),
            ISSUER);
    resumed.configureReviewedOrdinaryGroups(
        Map.of(
            "/human-roles/agency-admin",
            ORIGINAL,
            "/human-roles/consultant-agency-admin-chat",
            DESIRED,
            "/human-roles/consultant",
            Set.of("consultant", "group-chat-consultant")));
    removeAdmin.setScopedMaintenance(mode, resumed);
    try {
      assertDoesNotThrow(() -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
      assertEquals(frozen, nativeBody);
      assertEquals("APPLIED", phase());
      assertEquals(2, effects.get());
      assertDoesNotThrow(() -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
      assertEquals(2, effects.get());
    } finally {
      removeAdmin.setScopedMaintenance(mode, maintenance);
    }
  }

  @Test
  void administratorRemovalRejectsAlteredFrozenExpectedStateBeforeAnotherHttpCommand() {
    givenAdministratorRemoval(true);
    lostReply = true;
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    int attempted = calls.get();
    var frozen =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            codec.read(
                jdbc.queryForObject(
                    "select native_body from general_admin_identity_intent where operation_id=?",
                    String.class,
                    operation),
                JsonNode.class);
    var body =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            codec.read(frozen.path("body").textValue(), JsonNode.class);
    body.put("expectedState", "b".repeat(64));
    frozen.put("body", codec.canonical(body));
    jdbc.update(
        "update general_admin_identity_intent set native_body=? where operation_id=?",
        codec.canonical(frozen),
        operation);
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals(attempted, calls.get());
    assertEquals("FROZEN", phase());
    assertEquals(
        1,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Integer.class, ACCOUNT));
  }

  @Test
  void administratorRemovalCannotTransferThePendingActionToAnotherHuman() {
    givenAdministratorRemoval(true);
    lostReply = true;
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    int attempted = calls.get();
    human.setUserId("72000000-0000-4000-8000-000000000099");
    request.setUserPrincipal(authentication(7L));
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals(attempted, calls.get());
    assertEquals("FROZEN", phase());
  }

  @Test
  void administratorRemovalRefusesForeignTenantBeforeIssuingNativeAuthority() {
    givenAdministratorRemoval(false);
    human.setTenantId(8L);
    request.setUserPrincipal(authentication(8L));
    assertThrows(RuntimeException.class, () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertEquals(0, calls.get());
    assertEquals(0, reads.get());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from general_admin_identity_intent where operation_id=?",
            Integer.class,
            operation));
    verifyNoInteractions(genericRemoval);
  }

  @Test
  void publicAgencyAdminEntryResumesAfterItsTargetAdminRowDisappears() {
    givenAdministratorRemoval(false);
    var entry =
        new de.caritas.cob.userservice.api.admin.service.admin.AgencyAdminUserService(
            mock(
                de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService
                    .class),
            mock(
                de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService.class),
            mock(
                de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService.class),
            removeAdmin,
            mock(de.caritas.cob.userservice.api.UserServiceMapper.class),
            agencies,
            tenants,
            consultants,
            (AdminScope) ReflectionTestUtils.getField(maintenance, "scope"));
    entry.setRemovalIdentityMode(
        (IdentityClientConfig) ReflectionTestUtils.getField(removeAdmin, "mode"));
    assertDoesNotThrow(() -> entry.deleteAgencyAdmin(ACCOUNT));
    assertDoesNotThrow(() -> entry.deleteAgencyAdmin(ACCOUNT));
    assertEquals("APPLIED", phase());
    assertEquals(1, effects.get());
  }

  @Test
  void publicTenantAdminEntryResumesAfterItsTargetAdminRowDisappears() {
    givenProtectedTenant();
    maintenance.configureReviewedProtectedProfiles(protectedCatalog(true));
    givenAdministratorRemoval(false);
    var entry =
        new de.caritas.cob.userservice.api.admin.service.admin.TenantAdminUserService(
            mock(
                de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService
                    .class),
            mock(
                de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService.class),
            mock(
                de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService.class),
            removeAdmin,
            mock(de.caritas.cob.userservice.api.UserServiceMapper.class),
            tenants,
            (AdminScope) ReflectionTestUtils.getField(maintenance, "scope"),
            consultants);
    entry.setRemovalIdentityMode(
        (IdentityClientConfig) ReflectionTestUtils.getField(removeAdmin, "mode"));
    assertDoesNotThrow(() -> entry.deleteTenantAdmin(ACCOUNT));
    assertDoesNotThrow(() -> entry.deleteTenantAdmin(ACCOUNT));
    assertEquals("APPLIED", phase());
    assertEquals(1, effects.get());
  }

  @Test
  void administratorRemovalRefusesAmbientSqlBeforeAnyNativeOrGenericMutation() {
    givenAdministratorRemoval(false);
    assertThrows(
        RuntimeException.class,
        () ->
            new TransactionTemplate(transactions)
                .execute(
                    ignored -> {
                      removeAdmin.deleteAgencyAdmin(ACCOUNT);
                      return null;
                    }));
    assertEquals(0, reads.get());
    assertEquals(0, calls.get());
    assertEquals(
        1,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Integer.class, ACCOUNT));
    verifyNoInteractions(genericRemoval);
  }

  private void givenAdministratorRemoval(boolean combined) {
    if (combined)
      assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    doThrow(new ScopedIdentityCommandRequiredException())
        .when(genericRemoval)
        .deleteUser(anyString());
    TenantContext.setCurrentTenant(7L);
    request.setMethod("DELETE");
    String adminType = (String) adminSql().get("type");
    request.setRequestURI(
        "/useradmin/" + ("AGENCY".equals(adminType) ? "agencyadmins/" : "tenantadmins/") + ACCOUNT);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              entities.persist(
                  de.caritas.cob.userservice.api.model.AdminAgency.builder()
                      .admin(admins.findById(ACCOUNT).orElseThrow())
                      .agencyId(71L)
                      .build());
              entities.flush();
              entities.clear();
            });
    if (combined) {
      jdbc.update(
          "update consultant set admin_remarks=?,is_absent=true,absence_message=? where consultant_id=?",
          "retain remarks",
          "retain absence",
          ACCOUNT);
      jdbc.update("insert into language(consultant_id,language_code) values(?,?)", ACCOUNT, "de");
    }
    operation = "72000000-0000-4000-8000-000000000010";
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", operation);
    nativeBody = null;
    nativeReply = null;
    nativeCommand = combined ? "profile-transition" : "delete";
    nativeOutcome = combined ? "UPDATED" : "DELETED";
    nativeDesiredRoles = combined ? Set.of("consultant", "group-chat-consultant") : Set.of();
    expectedRoleChange =
        combined
            ? Map.of(
                "transition", "REMOVE_ADMINISTRATOR_IDENTITY", "group", "/human-roles/consultant")
            : Map.of();
    maintenance.configureReviewedOrdinaryGroups(
        Map.of(
            "/human-roles/agency-admin",
            ORIGINAL,
            "/human-roles/consultant-agency-admin-chat",
            DESIRED,
            "/human-roles/consultant",
            Set.of("consultant", "group-chat-consultant")));
  }

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path importDirectory;
  private java.nio.file.Path importSource;
  private de.caritas.cob.userservice.api.service.ConsultantImportService importer;

  private void givenConfiguredExistingImport() throws Exception {
    givenExistingSelfAssignment();
    var settings =
        new de.caritas.cob.userservice.consultingtypeservice.generated.web.model
            .ExtendedConsultingTypeResponseDTO();
    var required = new de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant();
    required.setRoleSets(
        new java.util.LinkedHashMap<>(
            Map.of("ordinary", List.of("consultant", "group-chat-consultant"))));
    settings.setRoles(
        new de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RolesDTO()
            .consultant(required));
    when(consultingTypes.getConsultingTypeSettings(1)).thenReturn(settings);
    settings.setLanguageFormal(true);
    var identities = (IdentityClientConfig) ReflectionTestUtils.getField(service, "identityMode");
    when(identities.taskAccessToken(TaskPurpose.RUNTIME_POLICY))
        .thenReturn("fixture-import-runtime");
    var importAgency = agencies.getAgency(72L);
    when(agencies.getAgenciesForOrdinaryCreation(List.of(72L), "fixture-import-runtime"))
        .thenReturn(List.of(importAgency));
    var userHelper = mock(de.caritas.cob.userservice.api.helper.UserHelper.class);
    when(userHelper.isValidEmail("ada@fixture.invalid")).thenReturn(true);
    importer =
        new de.caritas.cob.userservice.api.service.ConsultantImportService(
            mock(IdentityUsernameAvailability.class),
            localConsultants,
            consultingTypes,
            agencies,
            userHelper,
            mock(
                de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga
                    .class),
            (de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation
                    .ConsultantAgencyRelationCreatorService)
                ReflectionTestUtils.getField(service, "consultantAgencyRelationCreatorService"));
    var directory = importDirectory;
    importSource = directory.resolve("configured.csv");
    java.nio.file.Files.writeString(
        importSource,
        ACCOUNT + ",,owned-counsellor,Ada,Lovelace,ada@fixture.invalid,nein,,72;ordinary,7\n");
    ReflectionTestUtils.setField(importer, "importFilename", importSource.toString());
    ReflectionTestUtils.setField(
        importer, "protocolFilename", directory.resolve("protocol").toString());
    ReflectionTestUtils.setField(importer, "multiTenancyEnabled", true);
    ReflectionTestUtils.setField(
        importer,
        "lifecycleEnvironment",
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS"));
    ReflectionTestUtils.setField(importer, "lifecycleIdentities", identities);
    ReflectionTestUtils.setField(importer, "importEntities", entities);
    ReflectionTestUtils.setField(importer, "existingImportRelations", consultantAgencies);
    ReflectionTestUtils.setField(importer, "existingImports", newImportOwner());
    importing = true;
    expectedRoleChange = Map.of("group", "/human-roles/consultant-agency-admin-chat");
  }

  private de.caritas.cob.userservice.api.service.identity.ExistingConsultantImportMaintenance
      newImportOwner() {
    return new de.caritas.cob.userservice.api.service.identity.ExistingConsultantImportMaintenance(
        jdbc,
        transactions,
        new de.caritas.cob.userservice.api.adapters.keycloak.maintenance
            .KeycloakExistingConsultantImport(
            http,
            actors,
            broker,
            codec,
            "https://identity.test",
            "fixture",
            "fixture-realm",
            ISSUER),
        Map.of(
            "/human-roles/consultant-agency-admin-chat",
            DESIRED,
            "/human-roles/consultant-chat",
            Set.of("consultant", "group-chat-consultant")),
        java.time.Clock.systemUTC());
  }

  private void runConfiguredImport() throws Exception {
    try (var trigger =
        de.caritas.cob.userservice.api.config.auth.ConfiguredImportTriggerFixture.verified()) {
      importer.startImport(trigger);
    }
  }

  @Test
  void configuredExistingImportUsesOnlyVerifiedSourceAndPreservesOriginalIdentity()
      throws Exception {
    givenConfiguredExistingImport();
    var before = consultantSql();
    runConfiguredImport();
    assertEquals(List.of(71L, 72L), assignedAgencies());
    before.remove("update_date");
    var after = consultantSql();
    after.remove("update_date");
    assertEquals(before, after);
    String frozen = nativeBody;
    String originalOperation = operation;
    runConfiguredImport();
    assertEquals(frozen, nativeBody);
    assertEquals(originalOperation, operation);
    assertEquals("APPLIED", phase());
    assertEquals(2, effects.get());
    assertEquals(1, matrixCalls.get());
    verifyNoInteractions(generic, genericRoles, genericRoleLookup);
  }

  @Test
  void configuredImportLostReplyResumesOriginalCommandAfterOwnerRestart() throws Exception {
    givenConfiguredExistingImport();
    lostReply = true;
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals("FROZEN", phase());
    assertEquals(List.of(71L), assignedAgencies());
    String frozen = nativeBody;
    String original = operation;
    ReflectionTestUtils.setField(importer, "existingImports", newImportOwner());
    runConfiguredImport();
    assertEquals(frozen, nativeBody);
    assertEquals(original, operation);
    assertEquals("APPLIED", phase());
    assertEquals(List.of(71L, 72L), assignedAgencies());
    assertEquals(2, effects.get());
    assertEquals(1, matrixCalls.get());
  }

  @Test
  void configuredImportUnsignedRefusalKeepsTheSamePendingIntent() throws Exception {
    givenConfiguredExistingImport();
    revoked = true;
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals("FROZEN", phase());
    String original = operation;
    assertEquals(List.of(71L), assignedAgencies());
    assertEquals(1, effects.get());
    revoked = false;
    runConfiguredImport();
    assertEquals(original, operation);
    assertEquals("APPLIED", phase());
  }

  @Test
  void configuredImportRejectsChangedOriginalSqlBeforeAnotherNativeCall() throws Exception {
    givenConfiguredExistingImport();
    lostReply = true;
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    int attempted = calls.get();
    jdbc.update("update consultant set first_name='Changed' where consultant_id=?", ACCOUNT);
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals(attempted, calls.get());
    assertEquals("FROZEN", phase());
    assertEquals(List.of(71L), assignedAgencies());
  }

  @Test
  void configuredImportChangedFileCannotReplaceUnknownOriginalAction() throws Exception {
    givenConfiguredExistingImport();
    lostReply = true;
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    int attempted = calls.get();
    String original = operation;
    java.nio.file.Files.writeString(
        importSource, java.nio.file.Files.readString(importSource).replace("Ada", "Grace"));
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals(attempted, calls.get());
    assertEquals(original, operation);
    assertEquals("FROZEN", phase());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "username,false", "missing-row,false", "invalid-record,false",
    "username,true", "missing-row,true", "invalid-record,true"
  })
  void configuredImportEarlySkipCannotHidePendingCustody(String change, boolean acknowledged)
      throws Exception {
    givenConfiguredExistingImport();
    if (acknowledged) {
      doAnswer(
              call -> {
                var saved = (de.caritas.cob.userservice.api.model.Consultant) call.callRealMethod();
                saved.setSupervisor(true);
                return saved;
              })
          .when(localConsultants)
          .saveConsultant(any(de.caritas.cob.userservice.api.model.Consultant.class));
    } else lostReply = true;
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    int attempted = calls.get();
    var original =
        jdbc.queryForMap(
            "select * from general_admin_identity_intent where operation_id=?", operation);
    var before = consultantSql();
    if (change.equals("missing-row")) {
      org.mockito.Mockito.doReturn(java.util.Optional.empty())
          .when(localConsultants)
          .getConsultant(ACCOUNT);
    } else {
      java.nio.file.Files.writeString(
          importSource,
          java.nio.file.Files.readString(importSource)
              .replace(
                  change.equals("username") ? "owned-counsellor" : "72;ordinary",
                  change.equals("username") ? "changed-name" : "invalid-role-set"));
    }
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals(attempted, calls.get());
    assertEquals(before, consultantSql());
    assertEquals(
        original,
        jdbc.queryForMap(
            "select * from general_admin_identity_intent where operation_id=?", operation));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"username", "missing-row"})
  void configuredImportOrdinarySkipsRemainSuccessfulWithoutPendingCustody(String change)
      throws Exception {
    givenConfiguredExistingImport();
    int before = calls.get();
    if (change.equals("missing-row")) {
      org.mockito.Mockito.doReturn(java.util.Optional.empty())
          .when(localConsultants)
          .getConsultant(ACCOUNT);
    } else {
      java.nio.file.Files.writeString(
          importSource,
          java.nio.file.Files.readString(importSource).replace("owned-counsellor", "changed-name"));
    }
    runConfiguredImport();
    assertEquals(before, calls.get());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from general_admin_identity_intent where human_subject='CONFIGURED_IMPORT'",
            Integer.class));
  }

  @Test
  void configuredImportRejectsChangedRoleSetForTheOriginalFileRecord() throws Exception {
    givenConfiguredExistingImport();
    lostReply = true;
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    int attempted = calls.get();
    consultingTypes
        .getConsultingTypeSettings(1)
        .getRoles()
        .getConsultant()
        .getRoleSets()
        .put("ordinary", List.of("consultant"));
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals(attempted, calls.get());
    assertEquals("FROZEN", phase());
  }

  @Test
  void configuredImportRejectsForgedFrozenBindingBeforeAnotherNativeCall() throws Exception {
    givenConfiguredExistingImport();
    lostReply = true;
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    int attempted = calls.get();
    String frozen =
        jdbc.queryForObject(
            "select native_body from general_admin_identity_intent where operation_id=?",
            String.class,
            operation);
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) codec.read(frozen, JsonNode.class);
    body.put("bindingDigest", "0".repeat(64));
    jdbc.update(
        "update general_admin_identity_intent set native_body=? where operation_id=?",
        codec.canonical(body),
        operation);
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals(attempted, calls.get());
    assertEquals("FROZEN", phase());
  }

  @Test
  void configuredImportRejectsAmbientSqlAndUnverifiedTrigger() throws Exception {
    givenConfiguredExistingImport();
    int attempted = calls.get();
    clearInvocations(agencies);
    assertThrows(
        RuntimeException.class,
        () ->
            new TransactionTemplate(transactions)
                .executeWithoutResult(
                    status -> {
                      try {
                        runConfiguredImport();
                      } catch (Exception e) {
                        throw new IllegalStateException(e);
                      }
                    }));
    assertThrows(RuntimeException.class, () -> importer.startImport());
    assertEquals(attempted, calls.get());
    verify(agencies, never()).getAgenciesForOrdinaryCreation(anyList(), anyString());
    assertEquals(List.of(71L), assignedAgencies());
  }

  @Test
  void configuredImportDetectsFileDriftAfterReadBeforeAnyNativeMutation() throws Exception {
    givenConfiguredExistingImport();
    int attempted = calls.get();
    afterImportRead =
        () -> {
          try {
            java.nio.file.Files.writeString(importSource, "changed configured source");
          } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
          }
        };
    try {
      runConfiguredImport();
    } catch (RuntimeException refused) {
      /* The configured importer may report a bounded source refusal. */
    }
    assertEquals(attempted, calls.get());
    assertEquals(1, effects.get());
    assertEquals(List.of(71L), assignedAgencies());
  }

  @Test
  void configuredImportLocalRollbackRetainsAcknowledgementAndOriginalCommand() throws Exception {
    givenConfiguredExistingImport();
    var before = consultantSql();
    var fault = new java.util.concurrent.atomic.AtomicBoolean(true);
    doAnswer(
            call -> {
              var saved = (de.caritas.cob.userservice.api.model.Consultant) call.callRealMethod();
              if (fault.getAndSet(false)) saved.setSupervisor(true);
              return saved;
            })
        .when(localConsultants)
        .saveConsultant(any(de.caritas.cob.userservice.api.model.Consultant.class));
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals("ACKNOWLEDGED", phase());
    assertEquals(before, consultantSql());
    assertEquals(List.of(71L), assignedAgencies());
    String frozen = nativeBody;
    int events = joinedEvents.get();
    runConfiguredImport();
    assertEquals(frozen, nativeBody);
    assertEquals("APPLIED", phase());
    assertEquals(2, effects.get());
    assertEquals(events + 1, joinedEvents.get());
  }

  @Test
  void configuredImportPureConsultantPreservesExistingChatRoleBeyondSelectedRoles()
      throws Exception {
    givenConfiguredExistingImport();
    jdbc.update("delete from admin_agency where admin_id=?", ACCOUNT);
    jdbc.update("delete from admin where admin_id=?", ACCOUNT);
    nativeRoles = Set.of("consultant", "group-chat-consultant");
    nativeDesiredRoles = nativeRoles;
    consultingTypes
        .getConsultingTypeSettings(1)
        .getRoles()
        .getConsultant()
        .getRoleSets()
        .put("ordinary", List.of("consultant"));
    expectedRoleChange = Map.of("group", "/human-roles/consultant-chat");
    runConfiguredImport();
    assertEquals("APPLIED", phase());
    assertEquals(Set.of("consultant", "group-chat-consultant"), nativeRoles);
    assertEquals(List.of(71L, 72L), assignedAgencies());
    assertEquals(1, matrixCalls.get());
  }

  @Test
  void configuredImportRefusesProtectedFamilyBeforeNativeMutation() throws Exception {
    givenConfiguredExistingImport();
    jdbc.update("update admin set type='TENANT' where admin_id=?", ACCOUNT);
    int attempted = calls.get();
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals(attempted, calls.get());
    assertEquals(List.of(71L), assignedAgencies());
  }

  @Test
  void configuredImportClosedVerifiedTriggerCannotDispatch() throws Exception {
    givenConfiguredExistingImport();
    var trigger =
        de.caritas.cob.userservice.api.config.auth.ConfiguredImportTriggerFixture.verified();
    trigger.close();
    int attempted = calls.get();
    assertThrows(RuntimeException.class, () -> importer.startImport(trigger));
    assertEquals(attempted, calls.get());
    assertEquals(List.of(71L), assignedAgencies());
  }

  @Test
  void configuredImportOnlyStrictNoEffectRetiresTheOriginalAction() throws Exception {
    givenConfiguredExistingImport();
    importNoEffect = true;
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals("NO_EFFECT", phase());
    String original = operation;
    int attempted = calls.get();
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals(attempted, calls.get());
    assertEquals(original, operation);
    java.nio.file.Files.writeString(
        importSource, java.nio.file.Files.readString(importSource) + "\n");
    importNoEffect = false;
    runConfiguredImport();
    assertNotEquals(original, operation);
    assertEquals("APPLIED", phase());
    assertEquals(List.of(71L, 72L), assignedAgencies());
    assertEquals(2, effects.get());
  }

  @Test
  void configuredImportUnverifiedTerminalReceiptCannotRetirePendingAction() throws Exception {
    givenConfiguredExistingImport();
    importNoEffect = true;
    tamperImportNoEffect = true;
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals("FROZEN", phase());
    assertEquals(1, effects.get());
    assertEquals(List.of(71L), assignedAgencies());
  }

  private org.springframework.http.client.ClientHttpResponse importRefusal(
      JsonNode body, org.springframework.http.client.ClientHttpRequest outbound)
      throws java.io.IOException {
    var outcome = new TreeMap<String, Object>();
    outcome.put("realmId", "fixture-realm");
    outcome.put("accountId", ACCOUNT);
    outcome.put("operationId", operation);
    outcome.put("command", nativeCommand);
    outcome.put("ownerClient", "backend-account-admin");
    outcome.put("ownerSubject", SUBJECT);
    outcome.put("intentDigest", digest("maintenance-intent", body));
    outcome.put("previousState", body.path("expectedState").textValue());
    outcome.put("tenantId", "7");
    outcome.put("originDigest", digest("maintenance-origin", body.path("target").path("origin")));
    outcome.put("result", "NO_EFFECT");
    outcome.put("status", 403);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
    String proof =
        encoded
            + "."
            + (tamperImportNoEffect
                ? "0".repeat(64)
                : mac("maintenance-no-effect-receipt/v1\n" + encoded));
    var response =
        org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                org.springframework.http.HttpStatus.FORBIDDEN)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                codec.canonical(
                    Map.of(
                        "operationId",
                        operation,
                        "accountId",
                        ACCOUNT,
                        "result",
                        "NO_EFFECT",
                        "status",
                        403,
                        "receipt",
                        proof)))
            .createResponse(outbound);
    response.getHeaders().setCacheControl("no-store");
    return response;
  }

  @Test
  void strictImportNoEffectDoesNotBlockAFreshCurrentHumanSelfAssignment() throws Exception {
    givenConfiguredExistingImport();
    importNoEffect = true;
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals("NO_EFFECT", phase());
    importing = false;
    importNoEffect = false;
    operation = "72000000-0000-4000-8000-000000000020";
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", operation);
    expectedRoleChange =
        Map.of(
            "group",
            "/human-roles/consultant-agency-admin-chat",
            "profile",
            Map.of("firstName", "Ada", "lastName", "Lovelace", "email", "ada@fixture.invalid"));
    assertFalse(selfAssignments.assign(existingInput()).consultantIdentityCreated());
    assertEquals("APPLIED", phase());
    assertEquals(List.of(71L, 72L), assignedAgencies());
    assertEquals(2, effects.get());
    assertEquals(1, matrixCalls.get());
  }

  @Test
  void configuredImportSourceChangeAfterAcknowledgementRemainsVisiblyPending() throws Exception {
    givenConfiguredExistingImport();
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    var before = consultantSql();
    afterImportRead =
        () -> {
          if (checks.incrementAndGet() == 2)
            try {
              java.nio.file.Files.writeString(
                  importSource, "changed source after native acknowledgement");
            } catch (java.io.IOException error) {
              throw new IllegalStateException(error);
            }
        };
    assertThrows(RuntimeException.class, this::runConfiguredImport);
    assertEquals("ACKNOWLEDGED", phase());
    assertEquals(before, consultantSql());
    assertEquals(List.of(71L), assignedAgencies());
    assertEquals(2, effects.get());
  }

  private de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO tenantEditInput() {
    return new de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO()
        .tenantId(8)
        .firstname("Grace")
        .lastname("Hopper")
        .email("grace@fixture.invalid");
  }

  private void givenPureTenantEdit() {
    givenProtectedTenant();
    maintenance.configureReviewedProtectedProfiles(protectedCatalog(true));
    human.setTenantId(0L);
    human.setRoles(Set.of("tenant-admin", "agency-admin"));
    human.setGrantedAuthorities(
        Set.of(Authority.AuthorityValue.USER_ADMIN, Authority.AuthorityValue.TENANT_ADMIN));
    request.setUserPrincipal(authentication(0L));
    TenantContext.setCurrentTenant(0L);
    request.setMethod("PUT");
    request.setRequestURI("/useradmin/tenantadmins/" + ACCOUNT);
    movingTenant = true;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"combined", "unreviewed", "group-only"})
  void pureTenantMoveRefusesNonPureOrUnreviewedNativeIdentityWithoutConsultantSql(String variant) {
    givenPureTenantEdit();
    nativeRoles =
        switch (variant) {
          case "combined" -> nativeDesiredRoles;
          case "group-only" -> Set.of("single-tenant-admin", "group-chat-consultant");
          default -> Set.of("single-tenant-admin", "unreviewed-role");
        };
    var before = adminSql();
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals(0, effects.get());
    assertEquals(7L, nativeTenant);
    assertEquals(before, adminSql());
    assertNoLocalConsultant();
  }

  @Test
  void pureTenantAdministratorFullDtoCommitsOnlyAfterBothOriginalNativeCommands() {
    givenPureTenantEdit();
    var before = new TreeMap<>(adminSql());
    var result =
        assertDoesNotThrow(() -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals(8L, result.getTenantId());
    assertEquals("Grace", result.getFirstName());
    assertEquals("Hopper", result.getLastName());
    assertEquals("grace@fixture.invalid", result.getEmail());
    assertEquals(8L, nativeTenant);
    assertEquals("Grace", nativeFirst);
    assertEquals(2, effects.get());
    assertEquals("APPLIED", phase());
    var after = new TreeMap<>(adminSql());
    for (String f : List.of("tenant_id", "first_name", "last_name", "email", "update_date")) {
      before.remove(f);
      after.remove(f);
    }
    assertEquals(before, after);
    assertNoLocalConsultant();
    verifyNoInteractions(genericProfile, generic, genericRoles, genericRemoval);
    var bytes = Map.copyOf(tenantBodies);
    assertDoesNotThrow(() -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals(bytes, tenantBodies);
    assertEquals(2, effects.get());
  }

  private org.springframework.http.client.ClientHttpResponse tenantEditResponse(
      org.springframework.http.client.ClientHttpRequest request, JsonNode body, String command)
      throws java.io.IOException {
    assertTrue(
        jdbc.queryForObject(
                "select count(*) from general_admin_identity_intent where operation_id=?",
                Integer.class,
                operation)
            > 0);
    if (command.equals("read")) {
      assertEquals(Long.toString(nativeTenant), body.path("target").path("tenantId").textValue());
      var projection = new TreeMap<String, Object>();
      projection.put("id", ACCOUNT);
      projection.put("username", "owned-counsellor");
      projection.put("firstName", nativeFirst);
      projection.put("lastName", nativeLast);
      projection.put("email", nativeEmail);
      projection.put("enabled", true);
      projection.put("emailVerified", true);
      projection.put("tenantId", Long.toString(nativeTenant));
      projection.put("preferredLanguage", "en");
      projection.put("effectiveRoles", nativeRoles.stream().sorted().toList());
      projection.put("passwordChangeRequired", false);
      projection.put("state", nativeState);
      var response =
          withSuccess(codec.canonical(projection), MediaType.APPLICATION_JSON)
              .createResponse(request);
      response.getHeaders().setCacheControl("no-store");
      return response;
    }
    assertTrue(Set.of("reassign-tenant", "profile").contains(command));
    calls.incrementAndGet();
    if (command.equals(refuseTenantCommand)) {
      if (signedTenantRefusal) return tenantNoEffectResponse(request, body, command);
      return org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
              org.springframework.http.HttpStatus.FORBIDDEN)
          .createResponse(request);
    }
    String original = tenantBodies.get(command);
    if (original != null) assertEquals(original, codec.canonical(body));
    else {
      assertEquals(Long.toString(nativeTenant), body.path("target").path("tenantId").textValue());
      assertEquals(nativeState, body.path("expectedState").textValue());
      if (!plainTenantProfile) assertNotEquals(operation, body.path("operationId").textValue());
      assertEquals(body.path("operationId"), body.path("target").path("origin").path("workflowId"));
      if (command.equals("reassign-tenant")) {
        assertEquals(codec.read("{\"tenantId\":\"8\"}", JsonNode.class), body.path("change"));
        nativeTenant = 8L;
      } else {
        assertEquals(8L, nativeTenant);
        assertEquals(
            codec.read(
                "{\"firstName\":\"Grace\",\"lastName\":\"Hopper\",\"email\":\"grace@fixture.invalid\"}",
                JsonNode.class),
            body.path("change"));
        nativeFirst = "Grace";
        nativeLast = "Hopper";
        nativeEmail = "grace@fixture.invalid";
      }
      tenantBodies.put(command, codec.canonical(body));
      tenantReplies.put(command, tenantEditReceipt(body, command));
      effects.incrementAndGet();
      nativeState = (command.equals("reassign-tenant") ? "b" : "c").repeat(64);
    }
    if (command.equals(loseTenantReply)) {
      loseTenantReply = null;
      throw new ResourceAccessException("Lost original tenant edit child reply");
    }
    var response =
        withSuccess(tenantReplies.get(command), MediaType.APPLICATION_JSON).createResponse(request);
    response.getHeaders().setCacheControl("no-store");
    return response;
  }

  private String tenantEditReceipt(JsonNode body, String command) {
    var value = new TreeMap<String, Object>();
    value.put("realmId", "fixture-realm");
    value.put("accountId", ACCOUNT);
    value.put("operationId", body.path("operationId").textValue());
    value.put("command", command);
    value.put("ownerClient", "backend-account-admin");
    value.put("ownerSubject", SUBJECT);
    value.put("intentDigest", digest("maintenance-intent", body));
    value.put("previousState", body.path("expectedState").textValue());
    value.put("tenantId", body.path("target").path("tenantId").textValue());
    value.put("originDigest", digest("maintenance-origin", body.path("target").path("origin")));
    value.put("result", "UPDATED");
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(value).getBytes(StandardCharsets.UTF_8));
    return codec.canonical(
        Map.of(
            "operationId",
            body.path("operationId").textValue(),
            "accountId",
            ACCOUNT,
            "result",
            "UPDATED",
            "receipt",
            encoded + "." + mac("maintenance-operation-receipt\n" + encoded)));
  }

  @Test
  void tenantEditLostFirstReplyRetainsParentAndChildThenCompletesFullDto() {
    givenPureTenantEdit();
    var before = adminSql();
    loseTenantReply = "reassign-tenant";
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals("FROZEN", phase());
    assertEquals(before, adminSql());
    assertEquals(8L, nativeTenant);
    assertEquals("Ada", nativeFirst);
    assertEquals(1, effects.get());
    String child = tenantBodies.get("reassign-tenant");
    assertDoesNotThrow(() -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals(child, tenantBodies.get("reassign-tenant"));
    assertEquals("APPLIED", phase());
    assertEquals(2, effects.get());
  }

  @Test
  void tenantEditProfileUnknownKeepsVisiblePartialStateAndOriginalBytes() {
    givenPureTenantEdit();
    var before = adminSql();
    loseTenantReply = "profile";
    var failure =
        assertThrows(
            RuntimeException.class,
            () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertFalse(failure instanceof HumanAdminAccountMaintenance.DefiniteProfileNoEffect);
    assertEquals("PROFILE_FROZEN", phase());
    assertEquals(before, adminSql());
    assertEquals(8L, nativeTenant);
    assertEquals("Grace", nativeFirst);
    var bytes = Map.copyOf(tenantBodies);
    assertDoesNotThrow(() -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals(bytes, tenantBodies);
    assertEquals("APPLIED", phase());
    assertEquals(2, effects.get());
  }

  @Test
  void tenantEditUnsignedProfileRefusalNeverRetiresWholeDto() {
    givenPureTenantEdit();
    var before = adminSql();
    refuseTenantCommand = "profile";
    var failure =
        assertThrows(
            RuntimeException.class,
            () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertFalse(failure instanceof HumanAdminAccountMaintenance.DefiniteProfileNoEffect);
    assertEquals("PROFILE_FROZEN", phase());
    assertEquals(before, adminSql());
    assertEquals(8L, nativeTenant);
    assertEquals("Ada", nativeFirst);
    assertEquals(1, effects.get());
    String original =
        jdbc.queryForObject(
            "select native_body from general_admin_identity_intent where operation_id=?",
            String.class,
            operation);
    refuseTenantCommand = null;
    updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput());
    assertEquals(
        original,
        jdbc.queryForObject(
            "select native_body from general_admin_identity_intent where operation_id=?",
            String.class,
            operation));
    assertEquals("APPLIED", phase());
  }

  @Test
  void tenantEditSignedProfileRefusalRemainsPartialPendingOnExactRetry() {
    givenPureTenantEdit();
    var before = adminSql();
    refuseTenantCommand = "profile";
    signedTenantRefusal = true;
    for (int retry = 0; retry < 2; retry++) {
      var failure =
          assertThrows(
              RuntimeException.class,
              () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
      assertFalse(failure instanceof HumanAdminAccountMaintenance.DefiniteProfileNoEffect);
      assertEquals("PROFILE_REFUSED", phase());
      assertEquals(before, adminSql());
      assertEquals(8L, nativeTenant);
      assertEquals(1, effects.get());
    }
  }

  @Test
  void tenantEditLocalCallbackRollbackResumesOnlyOriginalChildren() {
    givenPureTenantEdit();
    var before = adminSql();
    jdbc.execute(
        "alter table admin add constraint tenant_edit_callback_failure check(first_name <> 'Grace')");
    try {
      assertThrows(
          RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
      assertEquals("ACKNOWLEDGED", phase());
      assertEquals(before, adminSql());
      assertEquals(2, effects.get());
    } finally {
      jdbc.execute("alter table admin drop constraint tenant_edit_callback_failure");
    }
    var bytes = Map.copyOf(tenantBodies);
    updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput());
    assertEquals(bytes, tenantBodies);
    assertEquals("APPLIED", phase());
    assertEquals(2, effects.get());
  }

  @Test
  void tenantEditRestartsFromThePersistedOriginalParent() {
    givenPureTenantEdit();
    loseTenantReply = "profile";
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    var bytes = Map.copyOf(tenantBodies);
    var mode = (IdentityClientConfig) ReflectionTestUtils.getField(updateAdmin, "identityMode");
    var restarted =
        new HumanAdminAccountMaintenance(
            mode,
            human,
            (AdminScope) ReflectionTestUtils.getField(maintenance, "scope"),
            jdbc,
            transactions,
            request,
            new KeycloakHumanAdminMaintenance(
                http, actors, broker, codec, "https://identity.test", "fixture", "fixture-realm"),
            Clock.systemUTC(),
            ISSUER);
    restarted.configureReviewedProtectedProfiles(protectedCatalog(true));
    updateAdmin.setScopedAdminMaintenance(mode, restarted);
    try {
      updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput());
      assertEquals("APPLIED", phase());
      assertEquals(bytes, tenantBodies);
      assertEquals(2, effects.get());
    } finally {
      updateAdmin.setScopedAdminMaintenance(mode, maintenance);
    }
  }

  @Test
  void tenantEditCannotChangeOriginalDtoAfterFirstNativeEffect() {
    givenPureTenantEdit();
    loseTenantReply = "reassign-tenant";
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    int attempted = calls.get();
    assertThrows(
        RuntimeException.class,
        () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput().firstname("Changed")));
    assertEquals(attempted, calls.get());
    assertEquals("FROZEN", phase());
    assertEquals(7L, ((Number) adminSql().get("tenant_id")).longValue());
  }

  @Test
  void tenantEditRejectsRecreatedTargetAndForeignHumanBeforeAnotherCommand() {
    givenPureTenantEdit();
    loseTenantReply = "reassign-tenant";
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    int attempted = calls.get();
    human.setUserId("72000000-0000-4000-8000-000000000090");
    request.setUserPrincipal(authentication(0L));
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals(attempted, calls.get());
    human.setUserId(HUMAN);
    request.setUserPrincipal(authentication(0L));
    jdbc.update(
        "update admin set create_date=dateadd('SECOND',1,create_date) where admin_id=?", ACCOUNT);
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals(attempted, calls.get());
    assertEquals("FROZEN", phase());
  }

  @Test
  void tenantEditRejectsSourceAndDestinationOutsideCurrentScopeAndAmbientSql() {
    givenPureTenantEdit();
    human.setRoles(Set.of("single-tenant-admin"));
    human.setTenantId(7L);
    request.setUserPrincipal(authentication(7L));
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals(0, calls.get());
    human.setTenantId(8L);
    request.setUserPrincipal(authentication(8L));
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals(0, calls.get());
    human.setRoles(Set.of("tenant-admin", "agency-admin"));
    human.setTenantId(0L);
    request.setUserPrincipal(authentication(0L));
    assertThrows(
        RuntimeException.class,
        () ->
            new TransactionTemplate(transactions)
                .executeWithoutResult(
                    tx -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput())));
    assertEquals(0, calls.get());
  }

  @Test
  void tenantEditOriginalGrantWithdrawalPreventsSecondChildOnRetry() {
    givenPureTenantEdit();
    refuseTenantCommand = "profile";
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals("PROFILE_FROZEN", phase());
    refuseTenantCommand = "reassign-tenant";
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals("PROFILE_FROZEN", phase());
    assertEquals(1, effects.get());
    assertEquals("Ada", nativeFirst);
    assertEquals(7L, ((Number) adminSql().get("tenant_id")).longValue());
  }

  @Test
  void authenticatedInitialTenantRefusalReleasesOnlyItsOldSlotAfterLegitimateMove() {
    givenPureTenantEdit();
    refuseTenantCommand = "reassign-tenant";
    signedTenantRefusal = true;
    var failure =
        assertThrows(
            RuntimeException.class,
            () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertInstanceOf(HumanAdminAccountMaintenance.DefiniteProfileNoEffect.class, failure);
    assertEquals("NO_EFFECT", phase());
    assertEquals(0, effects.get());
    operation = "72000000-0000-4000-8000-000000000070";
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", operation);
    refuseTenantCommand = null;
    updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput());
    assertEquals("APPLIED", phase());
    assertEquals(2, effects.get());
    operation = "72000000-0000-4000-8000-000000000071";
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", operation);
    tenantBodies.clear();
    tenantReplies.clear();
    plainTenantProfile = true;
    assertDoesNotThrow(() -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals("APPLIED", phase());
    assertEquals(8L, nativeTenant);
    assertEquals(3, effects.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"human", "target", "birth", "original-tenant"})
  void oldTenantTerminalNeverAuthorizesForeignHumanTargetOrRecreatedBirth(String mutation) {
    givenPureTenantEdit();
    refuseTenantCommand = "reassign-tenant";
    signedTenantRefusal = true;
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals("NO_EFFECT", phase());
    int attempted = calls.get();
    String oldOperation = operation;
    if (mutation.equals("human")) {
      human.setUserId("72000000-0000-4000-8000-000000000099");
      request.setUserPrincipal(authentication(0L));
    } else if (mutation.equals("birth")) {
      jdbc.update(
          "update admin set create_date=? where admin_id=?",
          java.sql.Timestamp.valueOf("2025-01-01 00:00:00"),
          ACCOUNT);
    } else {
      String raw =
          jdbc.queryForObject(
              "select original_json from general_admin_identity_intent where operation_id=?",
              String.class,
              oldOperation);
      var original =
          (com.fasterxml.jackson.databind.node.ObjectNode) codec.read(raw, JsonNode.class);
      var row = (com.fasterxml.jackson.databind.node.ObjectNode) original.path("row");
      if (mutation.equals("target")) row.put("account", "72000000-0000-4000-8000-000000000099");
      else row.put("tenant", 9L);
      jdbc.update(
          "update general_admin_identity_intent set original_json=? where operation_id=?",
          codec.canonical(original),
          oldOperation);
    }
    operation = "72000000-0000-4000-8000-000000000072";
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", operation);
    refuseTenantCommand = null;
    assertThrows(
        RuntimeException.class, () -> updateAdmin.updateTenantAdmin(ACCOUNT, tenantEditInput()));
    assertEquals(attempted, calls.get());
    assertEquals(0, effects.get());
    assertEquals(7L, nativeTenant);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void ownAdministratorRemovalRequiresAnotherAuthorizedAdministrator(boolean combined) {
    givenAdministratorRemoval(combined);
    var before = adminSql();
    var consultant = combined ? consultantSql() : null;
    int attempted = calls.get();
    human.setUserId(ACCOUNT);
    request.setUserPrincipal(authentication(7L));
    var refusal =
        assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
            () -> removeAdmin.deleteAgencyAdmin(ACCOUNT));
    assertTrue(refusal.getMessage().contains("Another authorized administrator"));
    assertEquals(attempted, calls.get());
    assertEquals(before, adminSql());
    if (combined) assertEquals(consultant, consultantSql());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from general_admin_identity_intent where operation_id=?",
            Integer.class,
            operation));
    verifyNoInteractions(genericRemoval);
  }

  private org.springframework.http.client.ClientHttpResponse tenantNoEffectResponse(
      org.springframework.http.client.ClientHttpRequest request, JsonNode body, String command)
      throws java.io.IOException {
    var value = new TreeMap<String, Object>();
    value.put("realmId", "fixture-realm");
    value.put("accountId", ACCOUNT);
    value.put("operationId", body.path("operationId").textValue());
    value.put("command", command);
    value.put("ownerClient", "backend-account-admin");
    value.put("ownerSubject", SUBJECT);
    value.put("intentDigest", digest("maintenance-intent", body));
    value.put("previousState", body.path("expectedState").textValue());
    value.put("tenantId", body.path("target").path("tenantId").textValue());
    value.put("originDigest", digest("maintenance-origin", body.path("target").path("origin")));
    value.put("result", "NO_EFFECT");
    value.put("status", 403);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(value).getBytes(StandardCharsets.UTF_8));
    String receipt = encoded + "." + mac("maintenance-no-effect-receipt/v1\n" + encoded);
    var response =
        org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                org.springframework.http.HttpStatus.FORBIDDEN)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                codec.canonical(
                    Map.of(
                        "operationId",
                        body.path("operationId").textValue(),
                        "accountId",
                        ACCOUNT,
                        "result",
                        "NO_EFFECT",
                        "status",
                        403,
                        "receipt",
                        receipt)))
            .createResponse(request);
    response.getHeaders().setCacheControl("no-store");
    return response;
  }

  @Test
  void ownGrantCommitsTheVerifiedOriginalUpdatedReceiptWithoutAStaleTokenRead() {
    human.setUserId(ACCOUNT);
    request.setUserPrincipal(authentication(7L));
    staleSelfToken = true;
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals("APPLIED", phase());
    assertEquals(1, reads.get());
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals("APPLIED", phase());
    staleSelfToken = false;
    request.setUserPrincipal(authentication(7L));
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
  }

  @Test
  void lostOwnGrantKeepsTheOriginalFrozenActionUntilTheSameHumanRenewsTheirToken() {
    human.setUserId(ACCOUNT);
    request.setUserPrincipal(authentication(7L));
    staleSelfToken = true;
    lostReply = true;
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals("FROZEN", phase());
    String body = nativeBody;
    assertEquals(1, effects.get());
    assertNoLocalConsultant();
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals("FROZEN", phase());
    assertEquals(body, nativeBody);
    assertEquals(0, matrixCalls.get());
    staleSelfToken = false;
    request.setUserPrincipal(authentication(7L));
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals("APPLIED", phase());
    assertEquals(body, nativeBody);
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
  }

  @Test
  void actualSelfAssignmentReplaysItsOriginalGrantAfterTheConsultantRowAppears() {
    TenantContext.setCurrentTenant(7L);
    agencies.getAgency(71L).setTopicIds(List.of(81L));
    human.setRoles(Set.of("single-tenant-admin"));
    human.setGrantedAuthorities(
        Set.of(Authority.AuthorityValue.USER_ADMIN, Authority.AuthorityValue.SINGLE_TENANT_ADMIN));
    human.setUserId(ACCOUNT);
    request.setUserPrincipal(authentication(7L));
    request.setRequestURI("/useradmin/self-assignments");
    nativeDesiredRoles = Set.of("consultant", "restricted-agency-admin", "user-admin");
    expectedRoleChange =
        Map.of(
            "transition",
            "ADD_CONSULTANT_IDENTITY",
            "group",
            "/human-roles/consultant-agency-admin");
    maintenance.configureReviewedOrdinaryGroups(
        Map.of(
            "/human-roles/agency-admin",
            ORIGINAL,
            "/human-roles/consultant-agency-admin",
            nativeDesiredRoles));
    var input =
        new AdminSelfAssignmentService.SelfAssignmentCommand(
            AdminSelfAssignmentService.SelfAssignmentRole.COUNSELLOR, 71L, List.of(81L));
    assertDoesNotThrow(() -> selfAssignments.assign(input));
    assertEquals("APPLIED", phase());
    String body = nativeBody;
    var sql = consultantSql();
    assertTrue(consultants.findByIdAndDeleteDateIsNull(ACCOUNT).isPresent());
    var result = assertDoesNotThrow(() -> selfAssignments.assign(input));
    assertTrue(result.consultantIdentityCreated());
    assertEquals(body, nativeBody);
    assertEquals(sql, consultantSql());
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals(1, joinedEvents.get());
  }

  @Test
  void actualExistingSelfAssignmentAddsOnlyItsAgencyTopicsAndReplaysWithoutAnotherMatrixCreate() {
    givenExistingSelfAssignment();
    var beforeAdmin = adminSql();
    var beforeConsultant = consultantSql();
    var input =
        new AdminSelfAssignmentService.SelfAssignmentCommand(
            AdminSelfAssignmentService.SelfAssignmentRole.COUNSELLOR, 72L, List.of(82L));
    var result = assertDoesNotThrow(() -> selfAssignments.assign(input));
    assertFalse(result.consultantIdentityCreated());
    assertEquals("APPLIED", phase());
    assertEquals(beforeAdmin, adminSql());
    var afterConsultant = consultantSql();
    beforeConsultant.remove("update_date");
    afterConsultant.remove("update_date");
    assertEquals(beforeConsultant, afterConsultant);
    assertEquals(
        List.of(71L, 72L),
        jdbc.queryForList(
            "select agency_id from consultant_agency where consultant_id=? order by agency_id",
            Long.class,
            ACCOUNT));
    assertEquals(
        List.of(82L),
        jdbc.queryForList(
            "select topic_id from consultant_topic where consultant_id=? and agency_id=72",
            Long.class,
            ACCOUNT));
    assertEquals(1, matrixCalls.get());
    assertEquals(2, joinedEvents.get());
    String body = nativeBody;
    assertFalse(
        assertDoesNotThrow(() -> selfAssignments.assign(input)).consultantIdentityCreated());
    assertEquals(body, nativeBody);
    assertEquals(2, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals(2, joinedEvents.get());
  }

  @Test
  void existingSelfAssignmentLostReplyRetainsOriginalBytesAndAllOriginalSqlUntilExactRetry() {
    givenExistingSelfAssignment();
    var before = consultantSql();
    lostReply = true;
    assertThrows(RuntimeException.class, () -> selfAssignments.assign(existingInput()));
    assertEquals("FROZEN", phase());
    assertEquals(before, consultantSql());
    assertEquals(List.of(71L), assignedAgencies());
    assertEquals(1, joinedEvents.get());
    assertEquals(1, matrixCalls.get());
    String frozen = nativeBody;
    staleSelfToken = true;
    assertThrows(RuntimeException.class, () -> selfAssignments.assign(existingInput()));
    assertEquals("FROZEN", phase());
    assertEquals(frozen, nativeBody);
    assertEquals(before, consultantSql());
    staleSelfToken = false;
    request.setUserPrincipal(authentication(7L));
    assertFalse(selfAssignments.assign(existingInput()).consultantIdentityCreated());
    assertEquals("APPLIED", phase());
    assertEquals(frozen, nativeBody);
    assertEquals(2, effects.get());
    assertEquals(2, joinedEvents.get());
    assertEquals(1, matrixCalls.get());
    assertEquals(List.of(71L, 72L), assignedAgencies());
  }

  @Test
  void existingSelfAssignmentRejectsFrozenConsultantDriftBeforeAnotherNativeCommand() {
    givenExistingSelfAssignment();
    lostReply = true;
    assertThrows(RuntimeException.class, () -> selfAssignments.assign(existingInput()));
    int attempted = calls.get();
    jdbc.update(
        "update consultant set admin_remarks=? where consultant_id=?", "changed locally", ACCOUNT);
    assertThrows(RuntimeException.class, () -> selfAssignments.assign(existingInput()));
    assertEquals(attempted, calls.get());
    assertEquals("FROZEN", phase());
    assertEquals(List.of(71L), assignedAgencies());
    assertEquals("changed locally", consultantSql().get("admin_remarks"));
    assertEquals(1, joinedEvents.get());
  }

  @Test
  void existingSelfAssignmentRejectsChangedFrozenTopicSourceWithoutSwitchingOperation() {
    givenExistingSelfAssignment();
    lostReply = true;
    assertThrows(RuntimeException.class, () -> selfAssignments.assign(existingInput()));
    int attempted = calls.get();
    when(selectedAgency.find(72L))
        .thenReturn(Optional.of(new AgencyFacts.Agency(72L, 7L, false, List.of(83L))));
    assertThrows(RuntimeException.class, () -> selfAssignments.assign(existingInput()));
    assertEquals(attempted, calls.get());
    assertEquals("FROZEN", phase());
    assertEquals(List.of(71L), assignedAgencies());
    assertEquals(1, joinedEvents.get());
  }

  @Test
  void existingSelfAssignmentRequiresSelectedDefaultRolesWithoutDroppingOriginalRoles() {
    nativeDesiredRoles = Set.of("consultant", "restricted-agency-admin", "user-admin");
    expectedRoleChange =
        Map.of(
            "transition",
            "ADD_CONSULTANT_IDENTITY",
            "group",
            "/human-roles/consultant-agency-admin");
    maintenance.configureReviewedOrdinaryGroups(
        Map.of(
            "/human-roles/agency-admin",
            ORIGINAL,
            "/human-roles/consultant-agency-admin",
            nativeDesiredRoles,
            "/human-roles/consultant-agency-admin-chat",
            DESIRED));
    givenExistingSelfAssignment(false);
    human.setRoles(nativeRoles);
    nativeDesiredRoles = DESIRED;
    var required = new java.util.LinkedHashMap<String, java.util.List<String>>();
    required.put(null, List.of("group-chat-consultant"));
    required.put("unselected", List.of("technical"));
    var consultantRoles =
        new de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant();
    consultantRoles.setRoleSets(required);
    var settings =
        new de.caritas.cob.userservice.consultingtypeservice.generated.web.model
            .ExtendedConsultingTypeResponseDTO();
    settings.setRoles(
        new de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RolesDTO()
            .consultant(consultantRoles));
    when(consultingTypes.getConsultingTypeSettings(1)).thenReturn(settings);
    assertFalse(selfAssignments.assign(existingInput()).consultantIdentityCreated());
    assertEquals(DESIRED, nativeRoles);
    assertEquals("APPLIED", phase());
    assertEquals(1, matrixCalls.get());
    assertEquals(2, joinedEvents.get());
  }

  @Test
  void protectedExistingSelfAssignmentKeepsExactReviewedFamilyAndAllExistingData() {
    givenProtectedTenant();
    maintenance.configureReviewedProtectedProfiles(protectedCatalog(true));
    givenExistingSelfAssignment();
    human.setRoles(nativeRoles);
    human.setGrantedAuthorities(
        Set.of(Authority.AuthorityValue.USER_ADMIN, Authority.AuthorityValue.TENANT_ADMIN));
    request.setUserPrincipal(authentication(7L));
    nativeCommand = "profile-transition";
    expectedRoleChange =
        Map.of(
            "transition",
            "ADD_CONSULTANT_IDENTITY",
            "profileId",
            "tenant-dual-v2",
            "profileVersion",
            2147483648L);
    var before = adminSql();
    assertFalse(selfAssignments.assign(existingInput()).consultantIdentityCreated());
    assertEquals(before, adminSql());
    assertEquals("APPLIED", phase());
    assertEquals(nativeDesiredRoles, nativeRoles);
    assertEquals(List.of(71L, 72L), assignedAgencies());
    assertEquals(1, matrixCalls.get());
    assertEquals(2, joinedEvents.get());
    assertFalse(selfAssignments.assign(existingInput()).consultantIdentityCreated());
    assertEquals(2, effects.get());
    assertEquals(2, joinedEvents.get());
  }

  private void givenExistingSelfAssignment() {
    givenExistingSelfAssignment(true);
  }

  private void givenExistingSelfAssignment(boolean chat) {
    var first = command();
    first.setGroupchatConsultant(chat);
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, first));
    TenantContext.setCurrentTenant(7L);
    human.setUserId(ACCOUNT);
    human.setRoles(
        Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin"));
    human.setGrantedAuthorities(Set.of(Authority.AuthorityValue.USER_ADMIN));
    request.setUserPrincipal(authentication(7L));
    request.setRequestURI("/useradmin/self-assignments");
    operation = "72000000-0000-4000-8000-000000000009";
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", operation);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              for (Long id : List.of(71L, 72L)) {
                entities.persist(
                    de.caritas.cob.userservice.api.model.AdminAgency.builder()
                        .admin(admins.findById(ACCOUNT).orElseThrow())
                        .agencyId(id)
                        .build());
              }
              entities.flush();
              entities.clear();
            });
    var nextAgency =
        new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
            .id(72L)
            .tenantId(7L)
            .consultingType(1)
            .teamAgency(false)
            .topicIds(List.of(82L));
    when(selectedAgency.find(72L))
        .thenReturn(Optional.of(new AgencyFacts.Agency(72L, 7L, false, List.of(82L))));
    when(agencies.getAgency(72L)).thenReturn(nextAgency);
    when(agencies.getAgenciesWithoutCaching(anyList()))
        .thenAnswer(
            invocation -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              List<Long> ids = invocation.getArgument(0);
              return ids.stream()
                  .map(id -> id == 72L ? nextAgency : agencies.getAgency(71L))
                  .toList();
            });
    nativeBody = null;
    nativeReply = null;
    nativeCommand = "ordinary-profile";
    expectedRoleChange =
        Map.of(
            "group",
            "/human-roles/consultant-agency-admin-chat",
            "profile",
            Map.of("firstName", "Ada", "lastName", "Lovelace", "email", "ada@fixture.invalid"));
  }

  private AdminSelfAssignmentService.SelfAssignmentCommand existingInput() {
    return new AdminSelfAssignmentService.SelfAssignmentCommand(
        AdminSelfAssignmentService.SelfAssignmentRole.COUNSELLOR, 72L, List.of(82L));
  }

  private List<Long> assignedAgencies() {
    return jdbc.queryForList(
        "select agency_id from consultant_agency where consultant_id=? order by agency_id",
        Long.class,
        ACCOUNT);
  }

  @Test
  void nonUtcHostPreservesTheOriginalCreationInstantThroughTheWholeGrantAndReplay() {
    var previousZone = java.util.TimeZone.getDefault();
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"));
    try {
      var before = adminSql();
      assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
      var original =
          codec.read(
              jdbc.queryForObject(
                  "select original_json from general_admin_identity_intent where operation_id=?",
                  String.class,
                  OPERATION),
              JsonNode.class);
      var created =
          jdbc.queryForObject(
              "select created_at from general_admin_identity_intent where operation_id=?",
              (row, index) -> row.getObject(1, LocalDateTime.class),
              operation);
      assertNotNull(created);
      assertEquals(
          original.path("creation").textValue(), created.toInstant(ZoneOffset.UTC).toString());
      assertEquals("APPLIED", phase());
      assertEquals(before, adminSql());
      assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
      assertEquals(1, effects.get());
      assertEquals(1, matrixCalls.get());
    } finally {
      java.util.TimeZone.setDefault(previousZone);
    }
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
            operation));
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
            operation);
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
            operation));
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
            operation);
    var tampered =
        (com.fasterxml.jackson.databind.node.ObjectNode) codec.read(original, JsonNode.class);
    tampered.put("phase", "NO_EFFECT");
    tampered.putNull("result");
    jdbc.update(
        "update general_admin_identity_intent set matrix_result_json=? where operation_id=?",
        codec.canonical(tampered),
        operation);
    assertThrows(
        RuntimeException.class, () -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, matrixCalls.get());
    assertEquals(0, joinedEvents.get());
    assertNoLocalConsultant();
    jdbc.update(
        "update general_admin_identity_intent set matrix_result_json=? where operation_id=?",
        original,
        operation);
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
            operation);
    var original =
        (com.fasterxml.jackson.databind.node.ObjectNode) codec.read(originalJson, JsonNode.class);
    var changed = java.time.Instant.parse(original.path("creation").textValue()).plusSeconds(1);
    original.put("creation", changed.toString());
    jdbc.update(
        "update general_admin_identity_intent set original_json=?,created_at=? where operation_id=?",
        codec.canonical(original),
        java.sql.Timestamp.from(changed),
        operation);
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

  @Test
  void reviewedSingleTenantAdminGrantKeepsItsProtectedFamilyAndUsesTheExplicitLongVersion() {
    givenProtectedTenant();
    var before = adminSql();
    assertDoesNotThrow(
        () -> {
          maintenance.configureReviewedOrdinaryGroups(Map.of());
          var catalog = new java.util.HashMap<>(protectedCatalog(true));
          var entries = new java.util.ArrayList<>(catalog.get("TENANT_ADMIN"));
          var mutableRoles = new java.util.HashSet<>(nativeDesiredRoles);
          entries.set(
              1,
              new HumanAdminAccountMaintenance.ProtectedProfileSelector(
                  "tenant-dual-v2", 2147483648L, mutableRoles));
          catalog.put("TENANT_ADMIN", entries);
          maintenance.configureReviewedProtectedProfiles(catalog);
          catalog.clear();
          entries.clear();
          mutableRoles.clear();
          service.grantConsultantIdentityToAdmin(ACCOUNT, command());
        });
    assertEquals("APPLIED", phase());
    assertEquals(before, adminSql());
    assertEquals(nativeDesiredRoles, nativeRoles);
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals(1, joinedEvents.get());
    assertDoesNotThrow(() -> service.grantConsultantIdentityToAdmin(ACCOUNT, command()));
    assertEquals(1, effects.get());
    assertEquals(1, matrixCalls.get());
    assertEquals(1, joinedEvents.get());
    verifyNoInteractions(generic, genericRoles, genericRoleLookup);
  }

  @Test
  void protectedGrantWithoutItsExactDesiredReviewedRoleUnionCannotMutateOrCreateMatrix() {
    givenProtectedTenant();
    assertThrows(
        RuntimeException.class,
        () -> {
          maintenance.configureReviewedProtectedProfiles(protectedCatalog(false));
          service.grantConsultantIdentityToAdmin(ACCOUNT, command());
        });
    assertEquals(1, reads.get());
    assertEquals("PREPARED", phase());
    assertEquals(0, calls.get());
    assertEquals(0, matrixCalls.get());
    assertNoLocalConsultant();
  }

  @Test
  void protectedGrantRequiresTheActualCompleteCurrentProfileInTheSameSqlOwnerKind() {
    givenProtectedTenant();
    nativeRoles = Set.of("single-tenant-admin", "tenant-admin");
    assertThrows(
        RuntimeException.class,
        () -> {
          maintenance.configureReviewedProtectedProfiles(protectedCatalog(true));
          service.grantConsultantIdentityToAdmin(ACCOUNT, command());
        });
    assertEquals(1, reads.get());
    assertEquals("PREPARED", phase());
    assertEquals(0, calls.get());
    assertEquals(0, matrixCalls.get());
    assertNoLocalConsultant();
  }

  @Test
  void reviewedProtectedCatalogRejectsUnknownKindsAmbiguousRolesIdsAndInvalidVersions() {
    var current =
        new HumanAdminAccountMaintenance.ProtectedProfileSelector(
            "tenant-current-v1", 17L, Set.of("single-tenant-admin"));
    var other =
        new HumanAdminAccountMaintenance.ProtectedProfileSelector(
            "tenant-other-v1", 18L, Set.of("single-tenant-admin"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            maintenance.configureReviewedProtectedProfiles(Map.of("CONSULTANT", List.of(current))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            maintenance.configureReviewedProtectedProfiles(
                Map.of("TENANT_ADMIN", List.of(current, current))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            maintenance.configureReviewedProtectedProfiles(
                Map.of("TENANT_ADMIN", List.of(current, other))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            maintenance.configureReviewedProtectedProfiles(
                Map.of(
                    "TENANT_ADMIN",
                    List.of(
                        new HumanAdminAccountMaintenance.ProtectedProfileSelector(
                            "tenant-invalid", 0L, Set.of("single-tenant-admin"))))));
  }

  private void givenProtectedTenant() {
    TenantContext.runIn(
        7L,
        () ->
            new TransactionTemplate(transactions)
                .executeWithoutResult(
                    ignored -> {
                      var admin = admins.findById(ACCOUNT).orElseThrow();
                      admin.setType(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT);
                      entities.flush();
                      entities.clear();
                    }));
    nativeRoles = Set.of("single-tenant-admin");
    nativeDesiredRoles = Set.of("single-tenant-admin", "consultant", "group-chat-consultant");
    expectedRoleChange =
        Map.of(
            "transition",
            "ADD_CONSULTANT_IDENTITY",
            "profileId",
            "tenant-dual-v2",
            "profileVersion",
            2147483648L);
  }

  private Map<String, List<HumanAdminAccountMaintenance.ProtectedProfileSelector>> protectedCatalog(
      boolean withDesired) {
    var current =
        new HumanAdminAccountMaintenance.ProtectedProfileSelector(
            "tenant-current-v1", 17L, Set.of("single-tenant-admin"));
    var desired =
        new HumanAdminAccountMaintenance.ProtectedProfileSelector(
            "tenant-dual-v2", 2147483648L, nativeDesiredRoles);
    return Map.of("TENANT_ADMIN", withDesired ? List.of(current, desired) : List.of(current));
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
            operation);
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
            .subject(human.getUserId())
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
        operation);
  }

  private Map<String, Object> consultantSql() {
    return new TreeMap<>(
        jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT));
  }

  private String receipt(JsonNode body) {
    var value = new TreeMap<String, Object>();
    value.put("realmId", "fixture-realm");
    value.put("accountId", ACCOUNT);
    value.put("operationId", operation);
    value.put("command", nativeCommand);
    value.put("ownerClient", "backend-account-admin");
    value.put("ownerSubject", SUBJECT);
    value.put("intentDigest", digest("maintenance-intent", body));
    value.put("previousState", "a".repeat(64));
    value.put("tenantId", "7");
    value.put("originDigest", digest("maintenance-origin", body.path("target").path("origin")));
    value.put("result", nativeOutcome);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(value).getBytes(StandardCharsets.UTF_8));
    return codec.canonical(
        Map.of(
            "operationId",
            operation,
            "accountId",
            ACCOUNT,
            "result",
            nativeOutcome,
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
