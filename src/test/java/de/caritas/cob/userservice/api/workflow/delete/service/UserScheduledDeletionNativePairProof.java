package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.IdentityConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.KeycloakInactivityMaintenance;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.*;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.*;
import de.caritas.cob.userservice.api.workflow.scheduling.*;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.client.*;

/** Hosted-only actual private creators/native commands/JPA and owned cleanup. No receipt double. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  ScheduledTaskClaimWriter.class,
  ScheduledTaskClaimService.class,
  UserScheduledDeletionNativePairProof.TimeConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserScheduledDeletionNativePairProof {
  private static final String LOGICAL = "http://maintenance-human-fixture.invalid";
  private static final long TENANT = 11L;
  private static final String USERNAME = "owned-cleanup";
  private static final String MATRIX = "@owned-cleanup:fixture.invalid";
  @Autowired private DataSource source;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entities;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private AdminRepository admins;
  @Autowired private AdminAgencyRepository adminAgencies;
  @Autowired private ConsultantAgencyRepository consultantAgencies;
  @Autowired private SessionRepository sessions;
  @Autowired private UserAgencyRepository userAgencies;
  @Autowired private DraftMessageRepository drafts;
  @Autowired private IdentityTombstoneRepository tombstones;
  @Autowired private ScheduledTaskClaimService claims;
  @Autowired private AdjustableClock clock;

  @Test
  void scheduledAskerRealCreatorDeleteLostReplyAndFinalJpa() throws Exception {
    runPair("asker");
  }

  @Test
  void scheduledConsultantRealHumanCreatorDeleteAndFinalJpa() throws Exception {
    runPair("consultant");
  }

  @Test
  void freshInactivityRealCreatorDeleteAndFinalJpa() throws Exception {
    runPair("inactivity");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(value != null && !value.isBlank(), "Private native pair input absent");
    return value;
  }

  private void runPair(String kind) throws Exception {
    assertEquals(kind, required("PAIR_KIND"));
    assertEquals("true", required("GITHUB_ACTIONS"));
    assertEquals("Linux", System.getProperty("os.name"));
    var facts = new TreeMap<String, Object>();
    facts.put("kind", kind);
    for (String flag :
        List.of(
            "actualJpa",
            "actualCreatorCommitted",
            "claimedOriginalSqlBirthPreserved",
            "canonicalAdmin",
            "realNativeRead",
            "realNativeDelete",
            "strictRealReceipt",
            "nativeAbsent",
            "sqlAbsent",
            "intentApplied",
            "oneLocalCallback",
            "sameFrozenCommand",
            "lostReplyRetainedBeforeRetry",
            "realCreatorProofRetained")) facts.put(flag, false);
    var codec =
        new AccountLifecycleCodec(Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64")));
    var jdbc = new JdbcTemplate(source);
    var protocol = new TreeMap<String, Integer>();
    for (String counter :
        List.of(
            "creatorCreate201",
            "creatorOwnedRead200",
            "creatorCommit200",
            "cleanupRead200",
            "cleanupDelete200",
            "cleanupDeleteAttempts")) protocol.put(counter, 0);
    try {
      assertInstanceOf(JpaTransactionManager.class, transactions);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      TenantContext.clear();
      facts.put("actualJpa", true);
      prepareSchema(jdbc);
      String realm = required("PAIR_REALM"), realmId = required("PAIR_REALM_ID");
      String issuer = LOGICAL + "/realms/" + realm;
      var http = new RestTemplate();
      http.setRequestFactory(new BufferingClientHttpRequestFactory(http.getRequestFactory()));
      var frozen = new AtomicReference<String>();
      var originalReceipt = new AtomicReference<String>();
      var deleteCalls = new AtomicInteger();
      var lose = new AtomicBoolean(kind.equals("asker"));
      http.getInterceptors()
          .add(
              (request, bytes, execution) -> {
                URI logical = request.getURI();
                assertEquals("maintenance-human-fixture.invalid", logical.getHost());
                var physical =
                    URI.create(
                        required("PAIR_NATIVE_BASE")
                            + logical.getRawPath()
                            + (logical.getRawQuery() == null ? "" : "?" + logical.getRawQuery()));
                var mapped =
                    new HttpRequestWrapper(request) {
                      @Override
                      public URI getURI() {
                        return physical;
                      }
                    };
                boolean maintenance = logical.getPath().contains("/oriso-account-maintenance/");
                boolean delete = maintenance && logical.getPath().endsWith("/delete");
                if (maintenance)
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if (delete) {
                  String body = new String(bytes, StandardCharsets.UTF_8);
                  if (frozen.get() == null) frozen.set(body);
                  else
                    assertEquals(
                        frozen.get(), body, "Unknown must retain exact original native body");
                  deleteCalls.incrementAndGet();
                  protocol.compute("cleanupDeleteAttempts", (key, count) -> count + 1);
                }
                var response = execution.execute(mapped, bytes);
                if (logical.getPath().contains("/oriso-account-lifecycle/")) {
                  String counter = null;
                  if (request.getMethod() == HttpMethod.PUT
                      && response.getStatusCode().value() == 201) counter = "creatorCreate201";
                  if (logical.getPath().endsWith("/owned-read")
                      && response.getStatusCode().value() == 200) counter = "creatorOwnedRead200";
                  if (logical.getPath().endsWith("/commit")
                      && response.getStatusCode().value() == 200) counter = "creatorCommit200";
                  if (counter != null) protocol.compute(counter, (key, count) -> count + 1);
                }
                if (maintenance
                    && logical.getPath().endsWith("/read")
                    && response.getStatusCode().value() == 200) {
                  facts.put("realNativeRead", true);
                  protocol.compute("cleanupRead200", (key, count) -> count + 1);
                }
                if (delete && response.getStatusCode().value() == 200) {
                  protocol.compute("cleanupDelete200", (key, count) -> count + 1);
                  String reply =
                      codec.canonical(
                          codec.read(
                              new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                              JsonNode.class));
                  if (originalReceipt.get() == null) originalReceipt.set(reply);
                  else
                    assertEquals(
                        originalReceipt.get(),
                        reply,
                        "Native exact retry returns original real receipt");
                  facts.put("realNativeDelete", true);
                  if (lose.getAndSet(false)) {
                    response.close();
                    throw new ResourceAccessException(
                        "Diagnostic loses actual committed delete response");
                  }
                }
                return response;
              });
      var identity = new IdentityConfig();
      identity.setOpenidConnectUrl(issuer + "/protocol/openid-connect");
      var env = new MockEnvironment().withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS");
      for (var actor : TaskActor.values()) {
        String prefix = "IDENTITY_" + actor.name();
        env.setProperty(prefix + "_CLIENT_ID", required("PAIR_" + actor.name() + "_CLIENT"));
        env.setProperty(prefix + "_CLIENT_SECRET", required("PAIR_" + actor.name() + "_SECRET"));
        env.setProperty(prefix + "_SERVICE_SUBJECT", required("PAIR_" + actor.name() + "_SUBJECT"));
      }
      var actors = new TaskActorConfiguration(env, new KeycloakCustomConfig(), identity);
      var keycloak = new KeycloakConfig();
      keycloak.setAuthServerUrl(LOGICAL);
      keycloak.setRealm(realm);
      var decoder =
          NimbusJwtDecoder.withJwkSetUri(
                  required("PAIR_NATIVE_BASE")
                      + "/realms/"
                      + realm
                      + "/protocol/openid-connect/certs")
              .build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
      String rawHuman = required("PAIR_HUMAN_TOKEN");
      var jwt = decoder.decode(rawHuman);
      assertEquals(required("PAIR_HUMAN_ID"), jwt.getSubject());
      assertEquals(required("PAIR_HUMAN_CLIENT"), jwt.getClaimAsString("azp"));
      assertEquals("11", jwt.getClaimAsString("tenantId"));
      assertEquals(Set.of("userservice"), Set.copyOf(jwt.getAudience()));
      @SuppressWarnings("unchecked")
      var roles =
          Set.copyOf((List<String>) ((Map<?, ?>) jwt.getClaim("realm_access")).get("roles"));
      assertEquals(Set.of("restricted-agency-admin", "user-admin"), roles);
      var human =
          new AuthenticatedUser(
              jwt.getSubject(),
              required("PAIR_HUMAN_USERNAME"),
              roles,
              rawHuman,
              Set.of("ROLE_USER_ADMIN"));
      human.setTenantId(TENANT);
      SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
      var broker =
          new TaskActorTokenBroker(
              actors, new KeycloakAuthClient(http, human, identity), decoder, keycloak);
      var checked = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
      assertEquals(TaskActor.ACCOUNT_ADMIN, checked.actor());
      assertEquals(
          actors.require(TaskPurpose.ACCOUNT_MAINTENANCE).serviceSubject(), checked.subject());
      assertEquals(
          Set.of("oriso-account-lifecycle", "oriso-otp", "userservice"),
          Set.copyOf(decoder.decode(checked.accessToken()).getAudience()));
      facts.put("canonicalAdmin", true);
      var commands =
          new KeycloakAccountLifecycleCommands(
              http, actors, broker, codec, LOGICAL, realm, realmId);
      var journal =
          new JdbcAccountLifecycleJournal(jdbc, transactions, codec, Clock.systemUTC(), 300);
      var effects =
          new JdbcOwnedLifecycleEffects(
              jdbc,
              transactions,
              effect -> {
                throw new AssertionError("No creation compensation expected");
              },
              codec);
      var dispatcher = new AccountLifecycleDispatcher(commands, journal, effects, codec);
      var coordinator = new AccountLifecycleCoordinator(commands, journal, dispatcher, codec);
      var matrix = mock(MatrixSynapseService.class);
      when(matrix.createUserIdWithoutReactivation(eq(USERNAME), anyString(), anyString()))
          .thenReturn(MATRIX);
      var matrixConfig = new MatrixConfig();
      matrixConfig.setServerName("fixture.invalid");
      String account =
          kind.equals("consultant")
              ? createConsultant(
                  human,
                  issuer,
                  coordinator,
                  journal,
                  commands,
                  effects,
                  codec,
                  matrix,
                  matrixConfig)
              : createAsker(coordinator, journal, commands, effects, matrix, matrixConfig);
      var created = creatorCustody(jdbc, account);
      assertEquals("COMMITTED", created.get("status"));
      assertNotNull(created.get("creation_proof"));
      assertNotNull(created.get("terminal_receipt"));
      String creatorCustody = codec.canonical(created);
      facts.put("actualCreatorCommitted", true);
      var observed = observe(account, codec);
      assertTrue(observed.path("exists").booleanValue());
      assertTrue(observed.path("exactOriginalProjection").booleanValue());
      assertEquals(0, observed.path("deleteEvents").intValue());
      Timestamp birth =
          jdbc.queryForObject(
              kind.equals("consultant")
                  ? "select create_date from consultant where consultant_id=?"
                  : "select create_date from user where user_id=?",
              Timestamp.class,
              account);
      assertNotNull(birth);
      Timestamp claimedBirth = birth;
      if (kind.equals("inactivity")) {
        assertEquals(
            0,
            jdbc.queryForObject("select count(*) from identity_maintenance_intent", Integer.class));
        assertEquals(
            0, jdbc.queryForObject("select count(*) from account_inactivity", Integer.class));
        claimedBirth = Timestamp.from(birth.toInstant().minusSeconds(800L * 86400));
        assertEquals(
            1,
            jdbc.update(
                "update user set create_date=? where user_id=? and create_date=?",
                claimedBirth,
                account,
                birth));
        Files.writeString(
            Path.of(required("PAIR_TIME_WITNESS")),
            codec.canonical(
                Map.of(
                    "mode", "EXPLICIT_ELAPSED_TIME_FIXTURE_BEFORE_ANY_CLAIM",
                    "userCreateDateBefore", birth.toInstant().toString(),
                    "userCreateDateAfter", claimedBirth.toInstant().toString(),
                    "lastActivity", claimedBirth.toInstant().toString(),
                    "dueAt",
                        claimedBirth
                            .toInstant()
                            .atZone(ZoneOffset.UTC)
                            .plusMonths(24)
                            .toInstant()
                            .toString())));
      }
      var lifecycle = new DeletionLifecycleService();
      ReflectionTestUtils.setField(
          lifecycle, "globalReadOnlyHours", 0L); // Explicit diagnostic policy, no 48h timing claim.
      if (!kind.equals("inactivity")) {
        new TransactionTemplate(transactions)
            .executeWithoutResult(
                status -> {
                  if (kind.equals("consultant")) {
                    var target = consultants.findById(account).orElseThrow();
                    lifecycle.beginConsultantDeletion(target, human.getUserId());
                    consultants.saveAndFlush(target);
                  } else {
                    var target = users.findById(account).orElseThrow();
                    lifecycle.beginUserDeletion(target, human.getUserId());
                    users.save(target);
                    entities.flush();
                  }
                });
      }
      Timestamp beforeCleanupBirth =
          jdbc.queryForObject(
              kind.equals("consultant")
                  ? "select create_date from consultant where consultant_id=?"
                  : "select create_date from user where user_id=?",
              Timestamp.class,
              account);
      assertEquals(claimedBirth, beforeCleanupBirth);
      facts.put("claimedOriginalSqlBirthPreserved", true);
      var callbacks = new AtomicInteger();
      TenantContext.setCurrentTenant(93L);
      if (kind.equals("consultant")) {
        try (var actions =
            new NativePairConsultantActionsFixture(
                account, consultants, tombstones, drafts, transactions, entities)) {
          var owner = consultantOwner(jdbc, lifecycle, actors, broker, codec, http, realm, realmId);
          owner.configureAcknowledgedCleanup(
              frame -> {
                callbacks.incrementAndGet();
                actions.service.continueAcknowledgedConsultantDeletion(frame);
              });
          owner.performDeletionWorkflow();
          actions.assertAllNine();
          assertEquals(1, actions.serviceEntries.get());
          verifyNoInteractions(actions.forbiddenNative);
        }
      } else {
        try (var actions =
            new NativePairAskerActionsFixture(
                account, users, tombstones, drafts, transactions, entities, true)) {
          var owner = askerOwner(jdbc, lifecycle, actors, broker, codec, http, realm, realmId);
          owner.configureAcknowledgedCleanup(
              frame -> {
                callbacks.incrementAndGet();
                actions.service.continueAcknowledgedDeletion(frame);
              });
          if (kind.equals("inactivity")) {
            runInactivity(
                account,
                claimedBirth.toInstant(),
                owner,
                jdbc,
                actors,
                broker,
                codec,
                http,
                issuer,
                realm,
                realmId);
            assertTrue(actions.events.contains("finalUserDelete"));
            assertFalse(actions.events.contains("replies"));
            assertEquals(
                "DELETED",
                jdbc.queryForObject(
                    "select status from account_inactivity where identity_id=?",
                    String.class,
                    account));
          } else {
            owner.performDeletionWorkflow();
            assertEquals("FROZEN", status(jdbc, account));
            assertTrue(users.findById(account).isPresent());
            assertEquals(0, callbacks.get());
            assertTrue(actions.events.isEmpty());
            assertFalse(observe(account, codec).path("exists").booleanValue());
            String originalEnvelope =
                jdbc.queryForObject(
                    "select frozen_body from identity_maintenance_intent where subject_id=?",
                    String.class,
                    account);
            assertNotNull(originalEnvelope);
            facts.put("lostReplyRetainedBeforeRetry", true);
            var restarted =
                askerOwner(jdbc, lifecycle, actors, broker, codec, http, realm, realmId);
            restarted.configureAcknowledgedCleanup(
                frame -> {
                  callbacks.incrementAndGet();
                  actions.service.continueAcknowledgedDeletion(frame);
                });
            restarted.performDeletionWorkflow();
            assertEquals(
                originalEnvelope,
                jdbc.queryForObject(
                    "select frozen_body from identity_maintenance_intent where subject_id=?",
                    String.class,
                    account));
            assertEquals(2, deleteCalls.get());
            actions.assertAllNineActionBoundaries();
          }
          assertEquals(1, actions.serviceEntries.get());
          verifyNoInteractions(actions.forbiddenNative);
        }
      }
      assertEquals(93L, TenantContext.getCurrentTenant());
      assertEquals(1, callbacks.get());
      facts.put("oneLocalCallback", true);
      assertEquals("APPLIED", status(jdbc, account));
      facts.put("intentApplied", true);
      assertTrue(users.findById(account).isEmpty());
      assertTrue(consultants.findById(account).isEmpty());
      assertEquals(
          0,
          jdbc.queryForObject("select count(*) from user where user_id=?", Integer.class, account));
      assertEquals(
          0,
          jdbc.queryForObject(
              "select count(*) from consultant where consultant_id=?", Integer.class, account));
      facts.put("sqlAbsent", true);
      var finalNative = observe(account, codec);
      assertFalse(finalNative.path("exists").booleanValue());
      assertEquals(1, finalNative.path("deleteEvents").intValue());
      assertTrue(finalNative.path("noRepresentation").booleanValue());
      facts.put("nativeAbsent", true);
      String saved =
          jdbc.queryForObject(
              "select receipt_json from identity_maintenance_intent where subject_id=?",
              String.class,
              account);
      assertNotNull(saved);
      assertNotNull(frozen.get());
      assertNotNull(originalReceipt.get());
      assertEquals(originalReceipt.get(), codec.canonical(codec.read(saved, JsonNode.class)));
      codec.verifyMaintenanceDeleteReceipt(
          saved,
          realmId,
          actors.require(TaskPurpose.ACCOUNT_MAINTENANCE),
          codec.read(frozen.get(), JsonNode.class));
      facts.put("strictRealReceipt", true);
      facts.put("sameFrozenCommand", true);
      assertEquals(creatorCustody, codec.canonical(creatorCustody(jdbc, account)));
      facts.put("realCreatorProofRetained", true);
    } finally {
      TenantContext.clear();
      SecurityContextHolder.clearContext();
      Files.writeString(Path.of(required("PAIR_PROTOCOL_WITNESS")), codec.canonical(protocol));
      Files.writeString(Path.of(required("PAIR_WITNESS")), codec.canonical(facts));
    }
  }

  private String createAsker(
      AccountLifecycleCoordinator coordinator,
      AccountLifecycleJournal journal,
      IdentityProvisioningCommands commands,
      JdbcOwnedLifecycleEffects effects,
      MatrixSynapseService matrix,
      MatrixConfig config) {
    var flow =
        new OrdinaryRegistrationLifecycle(
            coordinator, journal, commands, effects, matrix, config, "/human-roles/user");
    var dto = new UserDTO();
    dto.setUsername(new UsernameTranscoder().encodeUsername(USERNAME));
    dto.setEmail("owned-cleanup@oriso.org");
    dto.setPassword(UUID.randomUUID() + "-C-private-creation");
    dto.setTenantId(TENANT);
    return flow.register(
        dto,
        TENANT,
        session -> {
          effects.capture(session.attempt()).requireOwner(session.projection().id(), TENANT);
          var user =
              new User(session.projection().id(), null, dto.getUsername(), dto.getEmail(), false);
          user.setTenantId(TENANT);
          user.setLanguageCode(LanguageCode.de);
          user.setEncourage2fa(false);
          user.setMatrixUserId(flow.createMatrixUser(session, USERNAME));
          users.save(user);
          entities.flush();
          return user.getUserId();
        });
  }

  private String createConsultant(
      AuthenticatedUser human,
      String issuer,
      AccountLifecycleCoordinator coordinator,
      AccountLifecycleJournal journal,
      IdentityProvisioningCommands commands,
      JdbcOwnedLifecycleEffects effects,
      AccountLifecycleCodec codec,
      MatrixSynapseService matrix,
      MatrixConfig config) {
    var admin =
        Admin.builder()
            .id(human.getUserId())
            .username(new UsernameTranscoder().encodeUsername(human.getUsername()))
            .firstName("Native")
            .lastName("Operator")
            .email(required("PAIR_HUMAN_EMAIL"))
            .tenantId(TENANT)
            .type(Admin.AdminType.AGENCY)
            .build();
    admins.saveAndFlush(admin);
    adminAgencies.save(AdminAgency.builder().admin(admin).agencyId(27L).build());
    var scope =
        new AdminScope(
            human,
            admins,
            adminAgencies,
            consultants,
            consultantAgencies,
            mock(AgencyService.class),
            users,
            sessions,
            userAgencies);
    ReflectionTestUtils.setField(scope, "multitenancyEnabled", true);
    var reach = assertInstanceOf(AdminScope.Agencies.class, scope.current());
    assertEquals(TENANT, reach.tenantId());
    assertEquals(Set.of(27L), reach.ids());
    var mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    var flow =
        new OrdinaryAccountCreation(
            coordinator,
            journal,
            commands,
            effects,
            codec,
            matrix,
            config,
            mock(AppointmentService.class),
            human,
            issuer,
            Set.of(required("PAIR_HUMAN_CLIENT")),
            Map.of("/human-roles/consultant", Set.of("consultant", "group-chat-consultant")),
            new OrdinaryCreationTenantResolver(
                mock(TenantService.class), mock(AgencyService.class), mode));
    var checked =
        flow.humanScope(
            scope,
            TENANT,
            List.of(27L),
            "CONSULTANT",
            Set.of("consultant", "group-chat-consultant"));
    assertEquals(human.getUserId(), checked.origin().humanSubject());
    return flow.create(
        checked,
        new AccountCreation(
            USERNAME,
            "owned-cleanup@oriso.org",
            "Owned",
            "Consultant",
            "de",
            UUID.randomUUID() + "-C-private-creation",
            false),
        pending -> {
          pending.owned().requireOwner(pending.accountId(), TENANT);
          var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
          var consultant =
              Consultant.builder()
                  .id(pending.accountId())
                  .username(new UsernameTranscoder().encodeUsername(USERNAME))
                  .firstName("Owned")
                  .lastName("Consultant")
                  .email("owned-cleanup@oriso.org")
                  .tenantId(TENANT)
                  .languageCode(LanguageCode.de)
                  .encourage2fa(false)
                  .magicLinkLoginEnabled(false)
                  .notifyEnquiriesRepeating(true)
                  .notifyNewChatMessageFromAdviceSeeker(true)
                  .createDate(now)
                  .updateDate(now)
                  .matrixUserId(flow.createChatAccount(pending, USERNAME, "Owned Consultant"))
                  .build();
          consultants.saveAndFlush(consultant);
          return consultant.getId();
        });
  }

  private ScheduledAskerMaintenance askerOwner(
      JdbcTemplate jdbc,
      DeletionLifecycleService lifecycle,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      RestTemplate http,
      String realm,
      String realmId) {
    var owner =
        new ScheduledAskerMaintenance(
            jdbc,
            transactions,
            claims,
            clock,
            lifecycle,
            actors,
            broker,
            codec,
            http,
            LOGICAL,
            realm,
            realmId);
    owner.configureJpaCleanupOwner(entities);
    return owner;
  }

  private ScheduledConsultantMaintenance consultantOwner(
      JdbcTemplate jdbc,
      DeletionLifecycleService lifecycle,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      RestTemplate http,
      String realm,
      String realmId) {
    var owner =
        new ScheduledConsultantMaintenance(
            jdbc,
            transactions,
            claims,
            clock,
            lifecycle,
            actors,
            broker,
            codec,
            http,
            LOGICAL,
            realm,
            realmId);
    owner.configureJpaCleanupOwner(entities);
    return owner;
  }

  private void runInactivity(
      String account,
      Instant birth,
      ScheduledAskerMaintenance owner,
      JdbcTemplate jdbc,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      RestTemplate http,
      String issuer,
      String realm,
      String realmId) {
    var mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    var sdk = mock(KeycloakClient.class);
    var matrix = mock(MatrixSynapseService.class);
    when(matrix.getAccountLocked(MATRIX)).thenReturn(Optional.of(false));
    var media = mock(AccountInactivityMediaClient.class);
    var effects =
        new DefaultAccountInactivityEffects(
            jdbc,
            transactions,
            sdk,
            matrix,
            media,
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(InactiveAskerDeletionService.class));
    effects.setIdentityMode(mode);
    effects.setScopedDeletionMaintenance(owner);
    var service = new AccountInactivityService(jdbc, transactions, clock, effects);
    service.setIdentityMode(mode);
    service.configureScopedInactivity(
        new KeycloakInactivityMaintenance(
            http, actors, broker, codec, LOGICAL, realm, realmId, issuer),
        new AuthenticatedUser(),
        new MockHttpServletRequest(),
        issuer,
        Set.of(required("PAIR_HUMAN_CLIENT")));
    service.assignAtCreation(account, TENANT, 24, 0, birth);
    assertFalse(birth.atZone(ZoneOffset.UTC).plusMonths(24).toInstant().isAfter(clock.instant()));
    service.scan(false);
    verifyNoInteractions(sdk);
    verify(media).forget(List.of(MATRIX));
  }

  private static JsonNode observe(String account, AccountLifecycleCodec codec) {
    var http = new RestTemplate();
    var response =
        http.postForEntity(
            required("PAIR_CONTROL_BASE") + "/observe",
            new HttpEntity<>(codec.canonical(Map.of("accountId", account)), jsonHeaders()),
            String.class);
    assertEquals(200, response.getStatusCode().value());
    return codec.read(response.getBody(), JsonNode.class);
  }

  private static HttpHeaders jsonHeaders() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private static Map<String, String> creatorCustody(JdbcTemplate jdbc, String account) {
    return jdbc.queryForObject(
        "select id,status,creation_proof,terminal_receipt from identity_lifecycle_attempt where account_id=?",
        (row, index) ->
            Map.of(
                "id",
                row.getString("id"),
                "status",
                row.getString("status"),
                "creation_proof",
                row.getString("creation_proof"),
                "terminal_receipt",
                row.getString("terminal_receipt")),
        account);
  }

  private static String status(JdbcTemplate jdbc, String account) {
    return jdbc.queryForObject(
        "select status from identity_maintenance_intent where subject_id=?", String.class, account);
  }

  private void prepareSchema(JdbcTemplate jdbc) throws Exception {
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261021_identity_lifecycle_367/migrate.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261021_identity_lifecycle_367/retry-fairness.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261021_identity_maintenance_367/migrate.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261022_identity_maintenance_retry_367/migrate.sql"))
        .execute(source);
    jdbc.execute(
        "create table account_inactivity(identity_id varchar(36) primary key,tenant_id bigint,assigned_months int not null,revision bigint not null,last_activity timestamp(6) not null,due_at timestamp(6) not null,status varchar(20) not null,last_error varchar(1000),attempts int default 0 not null)");
    jdbc.execute(
        "create table account_inactivity_journal(journal_id bigint generated by default as identity primary key,identity_id varchar(36),action varchar(30),target varchar(30),outcome varchar(40),created_at timestamp(6))");
    try (var stream =
        new ClassPathResource(
                "db/changelog/changeset/20261023_inactivity_claimed_work_367/migrate.sql")
            .getInputStream()) {
      String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      String[] columns =
          migration
              .replaceFirst("(?s)^.*?ALTER TABLE account_inactivity\\s+ADD COLUMN\\s+", "")
              .replaceFirst(";\\s*$", "")
              .split(",\\s*ADD COLUMN\\s+");
      assertEquals(12, columns.length);
      for (String column : columns)
        jdbc.execute("ALTER TABLE account_inactivity ADD COLUMN " + column);
    }
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20260916_account_inactivity_access/migrate.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261022_general_admin_identity_367/migrate.sql"))
        .execute(source);
  }

  static final class AdjustableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      assertEquals(ZoneOffset.UTC, zone);
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    AdjustableClock pairedClock() {
      return new AdjustableClock();
    }
  }
}
