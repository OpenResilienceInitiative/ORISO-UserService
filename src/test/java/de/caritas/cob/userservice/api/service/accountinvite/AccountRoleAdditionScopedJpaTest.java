package de.caritas.cob.userservice.api.service.accountinvite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.KeycloakHumanAdminMaintenance;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
@ActiveProfiles({"testing", "scenario-c-role-addition-jpa"})
@TestPropertySource(
    properties = {
      "multitenancy.enabled=true",
      "logging.level.org.springframework.transaction.support.TransactionTemplate=DEBUG",
      "spring.datasource.url=jdbc:h2:mem:role-addition-${random.uuid};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE,DAY"
    })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  JpaAuditingConfiguration.class,
  AccountRoleAddition.class,
  CounsellorAgencyAdminGrantService.class,
  AccountInviteAccessPolicy.class,
  AdminScope.class,
  AccountRoleAdditionScopedJpaTest.Configuration.class
})
class AccountRoleAdditionScopedJpaTest {
  private static final String ACCOUNT = "71000000-0000-4000-8000-000000000001";
  private static final String HUMAN = "71000000-0000-4000-8000-000000000002";
  private static final String OPERATION = "71000000-0000-4000-8000-000000000003";
  private static final String SUBJECT = "71000000-0000-4000-8000-000000000004";
  private static final String ISSUER = "https://identity.test/realms/fixture";
  private static final String ENDPOINT =
      ISSUER + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT;
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final Set<String> ORIGINAL = Set.of("consultant", "group-chat-consultant");
  private static final Set<String> DESIRED =
      Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin");
  private static final AccountRoleAddition.AddRoleCommand COMMAND =
      new AccountRoleAddition.AddRoleCommand(AccountInviteTargetRole.AGENCY_ADMIN, 71L);

  @Autowired private AccountRoleAddition service;
  @MockitoSpyBean private CounsellorAgencyAdminGrantService localGrant;
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

  @BeforeEach
  void actualCurrentHumanSqlAndScopedReceiver() {
    TenantContext.clear();
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop table if exists general_admin_identity_intent");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261022_general_admin_identity_367/migrate.sql"))
        .execute(dataSource);
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
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              Consultant consultant =
                  Consultant.builder()
                      .id(ACCOUNT)
                      .tenantId(7L)
                      .createDate(born)
                      .updateDate(born)
                      .username(new UsernameTranscoder().encodeUsername("owned-counsellor"))
                      .firstName("Ada")
                      .lastName("Lovelace")
                      .email("ada@fixture.invalid")
                      .encourage2fa(true)
                      .magicLinkLoginEnabled(false)
                      .notifyEnquiriesRepeating(true)
                      .notifyNewChatMessageFromAdviceSeeker(true)
                      .languageCode(LanguageCode.en)
                      .status(ConsultantStatus.IN_PROGRESS)
                      .build();
              entities.persist(consultant);
              entities.flush();
              consultantAgencies.save(
                  ConsultantAgency.builder()
                      .consultant(consultant)
                      .agencyId(71L)
                      .tenantId(7L)
                      .createDate(LocalDateTime.now(ZoneOffset.UTC))
                      .build());
              entities.flush();
              entities.clear();
            });
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertEquals(
        born,
        jdbc.queryForObject(
                "select create_date from consultant where consultant_id=?",
                java.sql.Timestamp.class,
                ACCOUNT)
            .toLocalDateTime());
    human.setUserId(HUMAN);
    human.setTenantId(7L);
    human.setAccessToken("fixture-human");
    human.setGrantedAuthorities(
        Set.of(Authority.AuthorityValue.USER_ADMIN, Authority.AuthorityValue.TENANT_ADMIN));
    request.setMethod("POST");
    request.setRequestURI("/useradmin/consultants/" + ACCOUNT + "/roles");
    request.removeHeader("Idempotency-Key");
    request.addHeader("Idempotency-Key", OPERATION);
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer fixture-human");
    request.setUserPrincipal(authentication(7L));
    assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
    maintenance.configureReviewedOrdinaryGroups(
        Map.of(
            "/human-roles/consultant-chat",
            ORIGINAL,
            "/human-roles/consultant-agency-admin-chat",
            DESIRED));
    doThrow(new ScopedIdentityCommandRequiredException())
        .when(generic)
        .updateRole(anyString(), any(UserRole.class));
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
                  var row =
                      codec.read(tables.path("consultant").get(0).textValue(), JsonNode.class);
                  assertEquals(ACCOUNT, row.path("consultant_id").textValue());
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
                      "ADD_AGENCY_ADMIN_IDENTITY",
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
  void actualRoleRouteAppliesOnePreservingTransitionBeforeTheCoherentLocalAdminAndAgency() {
    var original = consultantSql();
    assertDoesNotThrow(() -> service.add(ACCOUNT, COMMAND));
    assertEquals(DESIRED, nativeRoles);
    assertEquals(1, effects.get());
    assertEquals("APPLIED", phase());
    assertEquals(original, consultantSql());
    assertEquals(
        "AGENCY",
        jdbc.queryForObject("select type from admin where admin_id=?", String.class, ACCOUNT));
    assertEquals(
        "owned-counsellor",
        jdbc.queryForObject("select username from admin where admin_id=?", String.class, ACCOUNT));
    assertEquals(
        List.of(71L),
        jdbc.queryForList(
            "select agency_id from admin_agency where admin_id=?", Long.class, ACCOUNT));
    verifyNoInteractions(generic);
  }

  @Test
  void unknownNativeReplyKeepsTheExactHeldCommandAndNoLocalAdminUntilItsStrictAck() {
    lostReply = true;
    assertThrows(RuntimeException.class, () -> service.add(ACCOUNT, COMMAND));
    assertEquals(1, effects.get(), "The actual native operation must have reached the lost reply");
    assertEquals("FROZEN", phase());
    assertNoLocalAdmin();
    String originalBody = nativeBody;
    assertDoesNotThrow(() -> service.add(ACCOUNT, COMMAND));
    assertEquals(originalBody, nativeBody);
    assertEquals(1, effects.get());
    assertEquals("APPLIED", phase());
    verifyNoInteractions(generic);
  }

  @Test
  void appliedReplayRechecksTheOriginalCurrentNativeGrantWithoutRepeatingTheLocalRelation() {
    assertDoesNotThrow(() -> service.add(ACCOUNT, COMMAND));
    String applied =
        jdbc.queryForObject(
            "select applied_json from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION);
    int priorCalls = calls.get();
    int priorReads = reads.get();
    revoked = true;
    assertThrows(ForbiddenException.class, () -> service.add(ACCOUNT, COMMAND));
    assertEquals(priorCalls + 1, calls.get());
    assertEquals(priorReads, reads.get());
    assertEquals("APPLIED", phase());
    assertEquals(
        applied,
        jdbc.queryForObject(
            "select applied_json from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION));
    revoked = false;
    assertDoesNotThrow(() -> service.add(ACCOUNT, COMMAND));
    assertEquals(1, effects.get());
    assertEquals(priorReads, reads.get());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from admin_agency where admin_id=?", Integer.class, ACCOUNT));
  }

  @Test
  void frozenOriginalSqlBirthCannotBeReplacedBeforeTheUnknownNativeCommandReplays() {
    lostReply = true;
    assertThrows(RuntimeException.class, () -> service.add(ACCOUNT, COMMAND));
    assertEquals(1, effects.get());
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  "update consultant set create_date=? where consultant_id=?",
                  java.sql.Timestamp.from(Instant.now().plusSeconds(10)),
                  ACCOUNT);
              entities.clear();
            });
    int priorCalls = calls.get(), priorReads = reads.get();
    assertThrows(RuntimeException.class, () -> service.add(ACCOUNT, COMMAND));
    assertEquals(priorCalls, calls.get());
    assertEquals(priorReads, reads.get());
    assertNoLocalAdmin();
  }

  @Test
  void actualCurrentScopeRefusesForeignTenantBeforeNativeOrLocalGrant() {
    human.setTenantId(8L);
    request.setUserPrincipal(authentication(8L));
    assertThrows(ForbiddenException.class, () -> service.add(ACCOUNT, COMMAND));
    assertEquals(0, reads.get());
    assertEquals(0, effects.get());
    assertNoLocalAdmin();
    verifyNoInteractions(generic);
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

  @Test
  void anExistingLocalRelationCannotAuthorizeAnAgencyCurrentlyOwnedByAnotherTenant() {
    when(selectedAgency.find(71L))
        .thenReturn(Optional.of(new AgencyFacts.Agency(71L, 8L, false, List.of())));
    assertThrows(ForbiddenException.class, () -> service.add(ACCOUNT, COMMAND));
    assertEquals(0, reads.get());
    assertEquals(0, effects.get());
    assertNoLocalAdmin();
    verifyNoInteractions(generic);
  }

  @Test
  void theActualLocalCallbackCannotChangeUnrequestedConsultantStateAndItsAckRemainsRetryable() {
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              jdbc.update("update consultant set is_absent=true where consultant_id=?", ACCOUNT);
              return null;
            })
        .when(localGrant)
        .bindCheckedAgencyAdmin(any(AccountRoleAddition.ScopedAddition.class));
    assertThrows(RuntimeException.class, () -> service.add(ACCOUNT, COMMAND));
    assertEquals(1, effects.get());
    assertEquals("ACKNOWLEDGED", phase());
    assertNoLocalAdmin();
    assertFalse(
        jdbc.queryForObject(
            "select is_absent from consultant where consultant_id=?", Boolean.class, ACCOUNT));
    doCallRealMethod()
        .when(localGrant)
        .bindCheckedAgencyAdmin(any(AccountRoleAddition.ScopedAddition.class));
    assertDoesNotThrow(() -> service.add(ACCOUNT, COMMAND));
    assertEquals(1, effects.get());
    assertEquals("APPLIED", phase());
  }

  @Test
  void anAlreadyCombinedIdentityCanAddItsNewAgencyWithoutChangingExistingRolesOrAdmin() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              admins.saveAndFlush(
                  de.caritas.cob.userservice.api.model.Admin.builder()
                      .id(ACCOUNT)
                      .tenantId(7L)
                      .type(de.caritas.cob.userservice.api.model.Admin.AdminType.AGENCY)
                      .username("owned-counsellor")
                      .firstName("Ada")
                      .lastName("Lovelace")
                      .email("ada@fixture.invalid")
                      .createDate(LocalDateTime.now(ZoneOffset.UTC))
                      .build());
              entities.clear();
            });
    var originalAdmin =
        new TreeMap<>(jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT));
    nativeRoles = DESIRED;
    var callbackFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var callbackAttempts = new AtomicInteger();
    doAnswer(
            invocation -> {
              callbackAttempts.incrementAndGet();
              try {
                return invocation.callRealMethod();
              } catch (Throwable failure) {
                callbackFailure.set(failure);
                throw failure;
              }
            })
        .when(localGrant)
        .bindCheckedAgencyAdmin(any(AccountRoleAddition.ScopedAddition.class));
    assertDoesNotThrow(
        () -> service.add(ACCOUNT, COMMAND),
        () ->
            "phase="
                + phase()
                + ";callbackAttempts="
                + callbackAttempts.get()
                + ";callbackCause="
                + callbackFailure.get());
    assertEquals(DESIRED, nativeRoles);
    assertEquals(
        originalAdmin,
        new TreeMap<>(jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT)));
    assertEquals(
        List.of(71L),
        jdbc.queryForList(
            "select agency_id from admin_agency where admin_id=?", Long.class, ACCOUNT));
    assertEquals("APPLIED", phase());
    verifyNoInteractions(generic);
  }

  @Test
  void aLostFirstReadKeepsTheOriginalPreparedSqlAndCannotRecaptureChangedBirth() {
    lostReadReply = true;
    assertThrows(RuntimeException.class, () -> service.add(ACCOUNT, COMMAND));
    assertEquals("PREPARED", phase());
    assertEquals(1, reads.get());
    assertEquals(0, effects.get());
    String original =
        jdbc.queryForObject(
            "select original_json from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  "update consultant set create_date=? where consultant_id=?",
                  java.sql.Timestamp.from(Instant.now().plusSeconds(10)),
                  ACCOUNT);
              entities.clear();
            });
    assertThrows(RuntimeException.class, () -> service.add(ACCOUNT, COMMAND));
    assertEquals(1, reads.get());
    assertEquals(0, calls.get());
    assertEquals(
        original,
        jdbc.queryForObject(
            "select original_json from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION));
    assertNull(
        jdbc.queryForObject(
            "select native_body from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION));
    assertNoLocalAdmin();
    verifyNoInteractions(generic);
  }

  @Test
  void theActualCallbackCannotChangeUnrequestedNewAdminFieldsAndItsAckRemainsRetryable() {
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              entities.flush();
              assertEquals(
                  1,
                  jdbc.update(
                      "update admin set managed_paused=true,id_old=17 where admin_id=?", ACCOUNT));
              return null;
            })
        .when(localGrant)
        .bindCheckedAgencyAdmin(any(AccountRoleAddition.ScopedAddition.class));
    assertThrows(RuntimeException.class, () -> service.add(ACCOUNT, COMMAND));
    assertEquals(1, effects.get());
    verify(localGrant, times(1))
        .bindCheckedAgencyAdmin(any(AccountRoleAddition.ScopedAddition.class));
    assertEquals("ACKNOWLEDGED", phase());
    assertNoLocalAdmin();
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from admin_agency where admin_id=?", Integer.class, ACCOUNT));
    doCallRealMethod()
        .when(localGrant)
        .bindCheckedAgencyAdmin(any(AccountRoleAddition.ScopedAddition.class));
    assertDoesNotThrow(() -> service.add(ACCOUNT, COMMAND));
    assertEquals(1, effects.get());
    assertEquals("APPLIED", phase());
    assertFalse(
        jdbc.queryForObject(
            "select managed_paused from admin where admin_id=?", Boolean.class, ACCOUNT));
    assertNull(
        jdbc.queryForObject("select id_old from admin where admin_id=?", Long.class, ACCOUNT));
    verifyNoInteractions(generic);
  }

  private void assertNoLocalAdmin() {
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Integer.class, ACCOUNT));
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
  @Profile("scenario-c-role-addition-jpa")
  static class Configuration {
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
