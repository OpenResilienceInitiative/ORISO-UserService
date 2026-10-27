package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.IdentityManager;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.adapters.keycloak.config.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.*;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.*;

/** Actual creators/public starts/JPA/native receipts; no HTTP-filter or external delivery claim. */
class PublicPreDeletionNativePairProof extends PublicPreDeletionJpaTest {
  private static final String LOGICAL = "http://protected-fixture.invalid";
  private static final long TENANT = 11L;
  private static final String USERNAME = "owned-public-pre-deletion";
  private static final String MATRIX = "@owned-public-pre-deletion:fixture.invalid";
  private final String chosenPassword = UUID.randomUUID() + "-C-private-public-start";
  private String nativeRealm, nativeRealmId, issuer;

  @Test
  void selfAskerRealCreatorDisableLostReplyExpiryAndColdOwnedCompletion() throws Exception {
    runNativePair("self-asker");
  }

  @Test
  void adminAskerRealCreatorDisableAndPublicOwnedCompletion() throws Exception {
    runNativePair("admin-asker");
  }

  @Test
  void adminConsultantRealHumanCreatorDisableAndPublicOwnedCompletion() throws Exception {
    runNativePair("admin-consultant");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(value != null && !value.isBlank(), "Private native pair input absent");
    return value;
  }

  @Override
  protected PublicPreDeletionMaintenance owner() {
    return new PublicPreDeletionMaintenance(mode, actor, scope, request, jdbc, transactions, entities,
        actors, broker, codec, http, passwords, ownerClock, LOGICAL, issuer, nativeRealm, nativeRealmId);
  }

  private void installHuman(Jwt jwt, String username, String raw) {
    assertEquals(issuer, jwt.getIssuer().toString());
    assertEquals(required("PAIR_HUMAN_CLIENT"), jwt.getClaimAsString("azp"));
    assertEquals(Set.of("userservice"), Set.copyOf(jwt.getAudience()));
    var roles = KeycloakRoles.of(jwt.getClaims());
    var authentication = new SecurityConfig(new CsrfSecurityProperties(), null, null)
        .jwtAuthenticationConverter(new RoleAuthorizationAuthorityMapper()).convert(jwt);
    assertNotNull(authentication);
    assertTrue(authentication.isAuthenticated());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    request.setUserPrincipal(authentication);
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer " + raw);
    actor = new AuthenticatedUser(jwt.getSubject(), username, roles, raw,
        authentication.getAuthorities().stream().map(value -> value.getAuthority())
            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    actor.setTenantId(Long.valueOf(jwt.getClaimAsString("tenantId")));
    scope = new AdminScope(actor, admins, adminAgencies, consultants, consultantAgencies,
        mock(AgencyService.class), users, sessions, userAgencies);
    ReflectionTestUtils.setField(scope, "multitenancyEnabled", true);
  }

  private Map<String, String> creatorCustody(String account) {
    return jdbc.queryForObject(
        "select id,status,creation_proof,terminal_receipt from identity_lifecycle_attempt where account_id=?",
        (row, index) -> Map.of("id", row.getString("id"), "status", row.getString("status"),
            "creation_proof", row.getString("creation_proof"), "terminal_receipt", row.getString("terminal_receipt")), account);
  }

  private String intentValue(String column, String account) {
    assertTrue(Set.of("status", "source_json", "intent_body", "receipt", "applied_json").contains(column));
    return jdbc.queryForObject("select " + column + " from identity_account_operation_intent where operation_kind='PRE_DELETION' and account_id=?", String.class, account);
  }

  private JsonNode observe(String account) {
    var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
    return codec.read(new RestTemplate().postForObject(required("PAIR_CONTROL_BASE") + "/observe",
        new HttpEntity<>(codec.canonical(Map.of("accountId", account)), headers), String.class), JsonNode.class);
  }

  private void selfWindow(boolean open) {
    var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
    new RestTemplate().postForEntity(required("PAIR_CONTROL_BASE") + (open ? "/self-window/open" : "/self-window/close"),
        new HttpEntity<>("{}", headers), Void.class);
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
    dto.setPassword(chosenPassword);
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
            .tenantId(0L)
            .type(Admin.AdminType.SUPER)
            .build();
    admins.saveAndFlush(admin);

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
    assertInstanceOf(AdminScope.Platform.class, scope.current());
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
            chosenPassword,
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


  private void runNativePair(String kind) throws Exception {
    assertEquals(kind, required("PAIR_KIND"));
    assertEquals("true", required("GITHUB_ACTIONS"));
    assertEquals("Linux", System.getProperty("os.name"));
    var facts = new TreeMap<String, Object>(); facts.put("kind", kind);
    for (String flag : List.of("actualJpa", "actualCreatorCommitted", "creatorProofRetained",
        "originalSqlBirthPreserved", "actualCurrentPrincipal", "realNativeRead",
        "realNativeDeactivate", "strictRealReceipt", "nativeDisabled", "sqlReadOnly",
        "intentApplied", "oneLocalCallback", "sameFrozenCommand", "lostReplyExpiredHumanRecovered"))
      facts.put(flag, false);
    var protocol = new TreeMap<String, Integer>();
    for (String counter : List.of("creatorCreate201", "creatorOwnedRead200", "creatorCommit200",
        "maintenanceRead200", "deactivateAttempts", "deactivate200", "outcomeRead200", "humanPasswordGrants200"))
      protocol.put(counter, 0);
    var frozen = new AtomicReference<String>();
    var realReceipt = new AtomicReference<String>();
    var lose = new AtomicBoolean(kind.equals("self-asker"));
    codec = new AccountLifecycleCodec(Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64")));
    try {
      assertInstanceOf(JpaTransactionManager.class, transactions);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      facts.put("actualJpa", true);
      new ResourceDatabasePopulator(
          new ClassPathResource("db/changelog/changeset/20261021_identity_lifecycle_367/migrate.sql"),
          new ClassPathResource("db/changelog/changeset/20261021_identity_lifecycle_367/retry-fairness.sql"))
          .execute(dataSource);
      nativeRealm = required("PAIR_REALM"); nativeRealmId = required("PAIR_REALM_ID");
      issuer = LOGICAL + "/realms/" + nativeRealm;
      http = new RestTemplate();
      http.setRequestFactory(new BufferingClientHttpRequestFactory(http.getRequestFactory()));
      http.getInterceptors().add((request, bytes, execution) -> {
        var logical = request.getURI();
        assertEquals("protected-fixture.invalid", logical.getHost());
        var physical = URI.create(required("PAIR_NATIVE_BASE") + logical.getRawPath()
            + (logical.getRawQuery() == null ? "" : "?" + logical.getRawQuery()));
        var mapped = new HttpRequestWrapper(request) {
          @Override public URI getURI() { return physical; }
        };
        boolean maintenance = logical.getPath().contains("/oriso-account-maintenance/");
        boolean deactivate = maintenance && logical.getPath().endsWith("/deactivate");
        boolean outcome = maintenance && logical.getPath().endsWith("/deactivate/outcome");
        if (maintenance) assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        if (deactivate || outcome) {
          String body = new String(bytes, StandardCharsets.UTF_8);
          if (frozen.get() == null) frozen.set(body); else assertEquals(frozen.get(), body);
          if (deactivate) protocol.compute("deactivateAttempts", (key, value) -> value + 1);
          if (outcome) assertNull(request.getHeaders().getFirst("X-ORISO-Human-Authorization"));
        }
        var response = execution.execute(mapped, bytes);
        int status = response.getStatusCode().value();
        if (logical.getPath().contains("/oriso-account-lifecycle/")) {
          String counter = request.getMethod() == HttpMethod.PUT && status == 201 ? "creatorCreate201"
              : logical.getPath().endsWith("/owned-read") && status == 200 ? "creatorOwnedRead200"
              : logical.getPath().endsWith("/commit") && status == 200 ? "creatorCommit200" : null;
          if (counter != null) protocol.compute(counter, (key, value) -> value + 1);
        }
        if (logical.getPath().endsWith("/token") && status == 200
            && new String(bytes, StandardCharsets.UTF_8).contains("grant_type=password"))
          protocol.compute("humanPasswordGrants200", (key, value) -> value + 1);
        if (maintenance && logical.getPath().endsWith("/read") && status == 200) {
          facts.put("realNativeRead", true);
          protocol.compute("maintenanceRead200", (key, value) -> value + 1);
        }
        if ((deactivate || outcome) && status == 200) {
          String reply = codec.canonical(codec.read(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class));
          if (realReceipt.get() == null) realReceipt.set(reply); else assertEquals(realReceipt.get(), reply);
          protocol.compute(outcome ? "outcomeRead200" : "deactivate200", (key, value) -> value + 1);
          if (deactivate) {
            facts.put("realNativeDeactivate", true);
            if (lose.getAndSet(false)) {
              response.close();
              throw new ResourceAccessException("Diagnostic loses only an actual native committed response");
            }
          }
        }
        return response;
      });
      var identity = new IdentityConfig();
      identity.setOpenidConnectUrl(issuer + "/protocol/openid-connect");
      var environment = new MockEnvironment().withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS");
      for (var actor : TaskActor.values()) {
        String prefix = "IDENTITY_" + actor.name();
        environment.setProperty(prefix + "_CLIENT_ID", required("PAIR_" + actor.name() + "_CLIENT"));
        environment.setProperty(prefix + "_CLIENT_SECRET", required("PAIR_" + actor.name() + "_SECRET"));
        environment.setProperty(prefix + "_SERVICE_SUBJECT", required("PAIR_" + actor.name() + "_SUBJECT"));
      }
      actors = new TaskActorConfiguration(environment, new KeycloakCustomConfig(), identity);
      var keycloak = new KeycloakConfig(); keycloak.setAuthServerUrl(LOGICAL); keycloak.setRealm(nativeRealm);
      var decoder = NimbusJwtDecoder.withJwkSetUri(required("PAIR_NATIVE_BASE") + "/realms/" + nativeRealm + "/protocol/openid-connect/certs").build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
      String rawHuman = required("PAIR_HUMAN_TOKEN"); var adminJwt = decoder.decode(rawHuman);
      assertEquals(required("PAIR_HUMAN_ID"), adminJwt.getSubject());
      installHuman(adminJwt, required("PAIR_HUMAN_USERNAME"), rawHuman);
      assertEquals(Set.of("agency-admin", "tenant-admin", "user-admin"), actor.getRoles());
      assertEquals(0L, actor.getTenantId());
      assertTrue(actor.getGrantedAuthorities().contains(Authority.AuthorityValue.USER_ADMIN));
      assertTrue(actor.getGrantedAuthorities().contains(Authority.AuthorityValue.TENANT_ADMIN));
      var authClient = new KeycloakAuthClient(http, actor, identity);
      broker = new TaskActorTokenBroker(actors, authClient, decoder, keycloak);
      mode = new TaskAwareIdentityClientConfig(identity, environment,
          new StaticListableBeanFactory(Map.of("actualBroker", broker)).getBeanProvider(TaskActorTokenBroker.class));
      assertTrue(mode.isFiveActorMode());
      assertEquals(TaskActor.ACCOUNT_ADMIN, broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE).actor());
      var commands = new KeycloakAccountLifecycleCommands(http, actors, broker, codec, LOGICAL, nativeRealm, nativeRealmId);
      var journal = new JdbcAccountLifecycleJournal(jdbc, transactions, codec, Clock.systemUTC(), 300);
      var effects = new JdbcOwnedLifecycleEffects(jdbc, transactions, effect -> {
        throw new AssertionError("No creation compensation expected");
      }, codec);
      var dispatcher = new AccountLifecycleDispatcher(commands, journal, effects, codec);
      var coordinator = new AccountLifecycleCoordinator(commands, journal, dispatcher, codec);
      var matrix = mock(MatrixSynapseService.class);
      when(matrix.createUserIdWithoutReactivation(eq(USERNAME), anyString(), anyString())).thenReturn(MATRIX);
      var matrixConfig = new MatrixConfig(); matrixConfig.setServerName("fixture.invalid");
      String account = kind.equals("admin-consultant")
          ? createConsultant(actor, issuer, coordinator, journal, commands, effects, codec, matrix, matrixConfig)
          : createAsker(coordinator, journal, commands, effects, matrix, matrixConfig);
      var creation = creatorCustody(account);
      assertEquals("COMMITTED", creation.get("status"));
      assertNotNull(creation.get("creation_proof")); assertNotNull(creation.get("terminal_receipt"));
      String originalCreation = codec.canonical(creation); facts.put("actualCreatorCommitted", true);
      var beforeNative = observe(account);
      assertTrue(beforeNative.path("exists").asBoolean()); assertTrue(beforeNative.path("enabled").asBoolean());
      assertTrue(beforeNative.path("exactOriginalProjection").asBoolean());
      String birthColumn = kind.equals("admin-consultant") ? "consultant where consultant_id=?" : "user where user_id=?";
      var birth = jdbc.queryForObject("select create_date from " + birthColumn, java.sql.Timestamp.class, account);
      assertNotNull(birth);
      if (kind.equals("admin-asker")) {
        admins.saveAndFlush(Admin.builder().id(actor.getUserId()).tenantId(0L).username(new UsernameTranscoder().encodeUsername(actor.getUsername()))
            .firstName("Native").lastName("Operator").email(required("PAIR_HUMAN_EMAIL")).type(Admin.AdminType.SUPER).build());
      }
      if (!kind.equals("self-asker")) assertInstanceOf(AdminScope.Platform.class, scope.current());
      Jwt selfJwt = null;
      if (kind.equals("self-asker")) {
        selfWindow(true);
        try {
          ReflectionTestUtils.setField(authClient, "keycloakClientId", required("PAIR_HUMAN_CLIENT"));
          var login = authClient.loginUser(USERNAME, chosenPassword);
          selfJwt = decoder.decode(login.getAccessToken());
          assertEquals(account, selfJwt.getSubject());
          assertTrue(selfJwt.getExpiresAt().getEpochSecond() - selfJwt.getIssuedAt().getEpochSecond() <= 15);
          installHuman(selfJwt, USERNAME, login.getAccessToken());
        } finally { selfWindow(false); }
        assertEquals(Set.of("user"), actor.getRoles());
      }
      assertInstanceOf(JwtAuthenticationToken.class, request.getUserPrincipal());
      facts.put("actualCurrentPrincipal", true);
      var actualAuth = new KeycloakAuthClient(http, actor, mode);
      ReflectionTestUtils.setField(actualAuth, "keycloakClientId", required("PAIR_HUMAN_CLIENT"));
      var actualProvider = new KeycloakService(actor, mock(UserAccountInputValidator.class), mode,
          mock(KeycloakClient.class), mock(KeycloakMapper.class), mock(UserHelper.class), actualAuth);
      passwords = new IdentityManager(actualProvider, actualProvider, actualProvider, actualProvider,
          actualProvider, actualProvider, actualProvider, new UsernameTranscoder());
      request.setMethod("DELETE");
      request.setRequestURI(kind.equals("self-asker") ? "/service/users/account"
          : "/service/useradmin/" + (kind.equals("admin-consultant") ? "consultants/" : "askers/") + account);
      request.removeHeader("Idempotency-Key"); request.addHeader("Idempotency-Key", operation);
      var sqlStats = entities.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
      sqlStats.setStatisticsEnabled(true); sqlStats.clear();
      Runnable publicStart = kind.equals("self-asker") ? () -> selfService().deactivateAndFlagUserAccountForDeletion(chosenPassword)
          : kind.equals("admin-consultant") ? () -> consultantService().markConsultantForDeletion(account, false)
          : () -> adminAskerService().markAskerForDeletion(account);
      if (kind.equals("self-asker")) {
        assertThrows(de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class, publicStart::run);
        assertEquals("FROZEN", intentValue("status", account)); assertEquals(0, sqlStats.getEntityUpdateCount());
        String originalBody = intentValue("intent_body", account), originalSource = intentValue("source_json", account);
        assertFalse(originalSource.contains(chosenPassword)); assertFalse(originalSource.contains(actor.getAccessToken()));
        assertFalse(observe(account).path("enabled").asBoolean());
        assertNotNull(selfJwt);
        long expiry = selfJwt.getExpiresAt().getEpochSecond();
        assertTrue(expiry - Instant.now().getEpochSecond() <= 15);
        while (Instant.now().getEpochSecond() <= expiry) Thread.sleep(100);
        request.setUserPrincipal(null); request.removeHeader("Authorization"); SecurityContextHolder.clearContext();
        assertEquals(1, selfService().resumeSelfAskerDeletions(1));
        assertEquals(0, selfService().resumeSelfAskerDeletions(1));
        assertEquals(originalBody, intentValue("intent_body", account)); assertEquals(originalSource, intentValue("source_json", account));
        facts.put("lostReplyExpiredHumanRecovered", true);
      } else {
        assertDoesNotThrow(publicStart::run);
        assertEquals(1, sqlStats.getEntityUpdateCount());
        String originalPost = intentValue("applied_json", account);
        assertDoesNotThrow(publicStart::run);
        assertEquals(originalPost, intentValue("applied_json", account));
      }
      assertEquals(1, sqlStats.getEntityUpdateCount()); facts.put("oneLocalCallback", true);
      assertEquals("APPLIED", intentValue("status", account)); facts.put("intentApplied", true);
      assertNotNull(jdbc.queryForObject("select delete_date from " + birthColumn, java.sql.Timestamp.class, account));
      assertEquals("READ_ONLY_SAFEGUARD", jdbc.queryForObject("select deletion_lifecycle_state from " + birthColumn, String.class, account));
      facts.put("sqlReadOnly", true);
      assertEquals(birth, jdbc.queryForObject("select create_date from " + birthColumn, java.sql.Timestamp.class, account));
      facts.put("originalSqlBirthPreserved", true);
      var afterNative = observe(account); assertTrue(afterNative.path("exists").asBoolean());
      assertFalse(afterNative.path("enabled").asBoolean());
      assertEquals(1, afterNative.path("updateEvents").asInt() - beforeNative.path("updateEvents").asInt());
      assertTrue(afterNative.path("noRepresentation").asBoolean()); facts.put("nativeDisabled", true);
      String saved = intentValue("receipt", account);
      assertEquals(realReceipt.get(), codec.canonical(codec.read(saved, JsonNode.class)));
      codec.verifyMaintenancePreDeletionReceipt(saved, nativeRealmId, actors.require(TaskPurpose.ACCOUNT_MAINTENANCE), codec.read(frozen.get(), JsonNode.class));
      facts.put("strictRealReceipt", true); facts.put("sameFrozenCommand", true);
      assertEquals(originalCreation, codec.canonical(creatorCustody(account))); facts.put("creatorProofRetained", true);
    } finally {
      TenantContext.clear(); SecurityContextHolder.clearContext();
      Files.writeString(Path.of(required("PAIR_PROTOCOL_WITNESS")), codec.canonical(protocol));
      Files.writeString(Path.of(required("PAIR_WITNESS")), codec.canonical(facts));
    }
  }

}
