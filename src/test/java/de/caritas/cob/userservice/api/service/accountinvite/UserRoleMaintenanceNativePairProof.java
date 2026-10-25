package de.caritas.cob.userservice.api.service.accountinvite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.KeycloakHumanAdminMaintenance;
import de.caritas.cob.userservice.api.adapters.web.controller.ConsultantRoleController;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

/**
 * One existing public role producer paired with actual disposable native Keycloak and actual JPA.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing",
      "spring.datasource.url=jdbc:h2:mem:role-native-${random.uuid};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE,DAY"
    })
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserRoleMaintenanceNativePairProof {
  @Autowired private DataSource source;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entities;
  @Autowired private AdminRepository admins;
  @Autowired private AdminAgencyRepository adminAgencies;
  @Autowired private ConsultantRepository consultants;
  @Autowired private ConsultantAgencyRepository consultantAgencies;
  @Autowired private UserRepository users;
  @Autowired private SessionRepository sessions;
  @Autowired private UserAgencyRepository userAgencies;

  private static final Set<String> COMBINED =
      Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin");
  private static final Set<String> FLAGS =
      Set.of(
          "actualJpa",
          "actualPublicRoleCaller",
          "actualCurrentHuman",
          "canonicalAdmin",
          "preparedBeforeRead",
          "nativeReplyLostAfter200",
          "sameFrozenCommand",
          "nativeAcknowledgedBeforeLocal",
          "oneLocalCallback",
          "combinedRolesPreserved",
          "wholeSqlPreserved",
          "savedReplay200",
          "readAfterWithdrawal200",
          "savedAfterWithdrawal403",
          "sourceDriftBeforeRpcDenied",
          "coldRestartSameReceipt",
          "restoredReplay200");

  @Test
  void publicCombinedAgencyRoleKeepsNativeAndSqlOwnershipAcrossLossRevocationAndRestart()
      throws Exception {
    assertEquals("true", required("GITHUB_ACTIONS"));
    assertEquals("Linux", System.getProperty("os.name"));
    assertEquals("role-agency", required("PAIR_KIND"));
    var facts = new TreeMap<String, Object>();
    facts.put("kind", "role-agency");
    FLAGS.forEach(flag -> facts.put(flag, false));
    var codec =
        new AccountLifecycleCodec(Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64")));
    try {
      assertInstanceOf(JpaTransactionManager.class, transactions);
      assertSame(source, ((JpaTransactionManager) transactions).getDataSource());
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      String account = required("PAIR_ACCOUNT_ID");
      String username = required("PAIR_USERNAME");
      String realm = required("PAIR_REALM");
      String realmId = required("PAIR_REALM_ID");
      String issuer = required("PAIR_ISSUER");
      assertEquals("http://protected-fixture.invalid/realms/" + realm, issuer);
      String logical = "http://protected-fixture.invalid";
      Long tenant = Long.valueOf(required("PAIR_TENANT"));
      assertTrue(tenant > 0);
      String operation = UUID.randomUUID().toString();
      var jdbc = new JdbcTemplate(source);
      jdbc.execute("drop table if exists general_admin_identity_intent");
      new ResourceDatabasePopulator(
              new ClassPathResource(
                  "db/changelog/changeset/20261022_general_admin_identity_367/migrate.sql"))
          .execute(source);
      try (var input =
          new ClassPathResource("db/changelog/changeset/20260916_account_inactivity/migrate.sql")
              .getInputStream()) {
        String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(sql.substring(0, sql.indexOf(';') + 1));
      }
      var local = new TransactionTemplate(transactions);
      local.executeWithoutResult(
          ignored -> {
            jdbc.update("delete from admin_agency where admin_id=?", account);
            jdbc.update("delete from admin where admin_id=?", account);
            jdbc.update("delete from consultant_agency where consultant_id=?", account);
            jdbc.update("delete from consultant_topic where consultant_id=?", account);
            jdbc.update("delete from language where consultant_id=?", account);
            jdbc.update("delete from consultant where consultant_id=?", account);
            entities.flush();
            entities.clear();
          });
      assertEquals(
          0,
          jdbc.queryForObject(
              "select count(*) from consultant where consultant_id=?", Integer.class, account));
      local.executeWithoutResult(
          ignored -> {
            var admin =
                Admin.builder()
                    .id(account)
                    .type(Admin.AdminType.AGENCY)
                    .tenantId(tenant)
                    .username(username)
                    .firstName(required("PAIR_FIRST_NAME"))
                    .lastName(required("PAIR_LAST_NAME"))
                    .email(required("PAIR_EMAIL"))
                    .createDate(LocalDateTime.now(ZoneOffset.UTC))
                    .updateDate(LocalDateTime.now(ZoneOffset.UTC))
                    .build();
            entities.persist(admin);
            var consultant =
                Consultant.builder()
                    .id(account)
                    .tenantId(tenant)
                    .username(new UsernameTranscoder().encodeUsername(username))
                    .firstName(admin.getFirstName())
                    .lastName(admin.getLastName())
                    .email(admin.getEmail())
                    .languageCode(LanguageCode.en)
                    .status(ConsultantStatus.CREATED)
                    .encourage2fa(true)
                    .magicLinkLoginEnabled(false)
                    .notifyEnquiriesRepeating(true)
                    .notifyNewChatMessageFromAdviceSeeker(true)
                    .createDate(LocalDateTime.now(ZoneOffset.UTC))
                    .updateDate(LocalDateTime.now(ZoneOffset.UTC))
                    .build();
            entities.persist(consultant);
            entities.flush();
            adminAgencies.save(
                AdminAgency.builder()
                    .admin(admin)
                    .agencyId(70L)
                    .createDate(LocalDateTime.now(ZoneOffset.UTC))
                    .updateDate(LocalDateTime.now(ZoneOffset.UTC))
                    .build());
            consultantAgencies.save(
                ConsultantAgency.builder()
                    .consultant(consultant)
                    .agencyId(71L)
                    .tenantId(tenant)
                    .createDate(LocalDateTime.now(ZoneOffset.UTC))
                    .build());
            entities.flush();
            entities.clear();
          });
      var originalAdmin =
          new TreeMap<>(jdbc.queryForMap("select * from admin where admin_id=?", account));
      var originalConsultant =
          new TreeMap<>(
              jdbc.queryForMap("select * from consultant where consultant_id=?", account));
      facts.put("actualJpa", true);
      var base = new AtomicReference<>(required("PAIR_NATIVE_BASE"));
      assertTrue(base.get().matches("http://127\\.0\\.0\\.1:[0-9]+"));
      var http = new RestTemplate();
      http.setRequestFactory(new BufferingClientHttpRequestFactory(http.getRequestFactory()));
      var frozen = new AtomicReference<String>();
      var receipt = new AtomicReference<String>();
      var mutations = new AtomicInteger();
      var reads = new AtomicInteger();
      var lastStatus = new AtomicInteger();
      var discardFirstReply = new AtomicBoolean(true);
      http.getInterceptors()
          .add(
              (outbound, bytes, execution) -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                var uri = outbound.getURI();
                assertEquals("protected-fixture.invalid", uri.getHost());
                var mapped =
                    new HttpRequestWrapper(outbound) {
                      @Override
                      public URI getURI() {
                        return URI.create(
                            base.get()
                                + uri.getRawPath()
                                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
                      }
                    };
                boolean mutation = uri.getPath().endsWith("/profile-transition");
                boolean read =
                    uri.getPath()
                        .endsWith("/oriso-account-maintenance/v1/accounts/" + account + "/read");
                if (read || mutation)
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if (read) {
                  reads.incrementAndGet();
                  if (frozen.get() == null) {
                    var held =
                        jdbc.queryForMap(
                            "select status,original_json,native_body,native_receipt from general_admin_identity_intent where operation_id=?",
                            operation);
                    assertEquals("PREPARED", held.get("status"));
                    assertNull(held.get("native_body"));
                    assertNull(held.get("native_receipt"));
                    var captured = codec.read((String) held.get("original_json"), JsonNode.class);
                    assertTrue(
                        codec
                            .read(captured.path("sql").textValue(), JsonNode.class)
                            .path("admin")
                            .isArray());
                    facts.put("preparedBeforeRead", true);
                  }
                }
                if (mutation) {
                  String body = new String(bytes, StandardCharsets.UTF_8);
                  var parsed = codec.read(body, JsonNode.class);
                  assertEquals(
                      Map.of(
                          "transition",
                          "ADD_AGENCY_ADMIN_IDENTITY",
                          "group",
                          "/human-roles/consultant-agency-admin"),
                      codec.read(codec.canonical(parsed.path("change")), Map.class));
                  if (frozen.get() == null) frozen.set(body);
                  else assertEquals(frozen.get(), body);
                  assertEquals(operation, parsed.path("operationId").textValue());
                  mutations.incrementAndGet();
                }
                var response = execution.execute(mapped, bytes);
                if (mutation) {
                  lastStatus.set(response.getStatusCode().value());
                  if (lastStatus.get() == 200) {
                    String originalReply =
                        codec.canonical(
                            codec.read(
                                new String(
                                    response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                                JsonNode.class));
                    if (receipt.get() == null) receipt.set(originalReply);
                    else assertEquals(receipt.get(), originalReply);
                    if (discardFirstReply.compareAndSet(true, false)) {
                      facts.put("nativeReplyLostAfter200", true);
                      response.close();
                      throw new ResourceAccessException(
                          "Synthetic discarded actual native role acknowledgement");
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
        environment.setProperty(
            prefix + "_CLIENT_ID", required("PAIR_" + actor.name() + "_CLIENT"));
        environment.setProperty(
            prefix + "_CLIENT_SECRET", required("PAIR_" + actor.name() + "_SECRET"));
        environment.setProperty(
            prefix + "_SERVICE_SUBJECT", required("PAIR_" + actor.name() + "_SUBJECT"));
      }
      var actors = new TaskActorConfiguration(environment, new KeycloakCustomConfig(), identity);
      var keycloak = new KeycloakConfig();
      keycloak.setAuthServerUrl(logical);
      keycloak.setRealm(realm);
      var decoder =
          NimbusJwtDecoder.withJwkSetUri(
                  base.get() + "/realms/" + realm + "/protocol/openid-connect/certs")
              .build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
      var mode = mock(IdentityClientConfig.class);
      when(mode.isFiveActorMode()).thenReturn(true);
      var legacy = mock(IdentityClient.class);
      var callbacks = new AtomicInteger();
      String bearer = login(http, logical, realm);
      var current =
          context(
              bearer, account, operation, tenant, realm, realmId, logical, issuer, identity,
              keycloak, actors, decoder, mode, codec, http, jdbc, legacy, callbacks, facts);
      var admin = current.broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
      assertEquals(
          actors.require(TaskPurpose.ACCOUNT_MAINTENANCE).serviceSubject(), admin.subject());
      assertEquals(
          Set.of("userservice", "oriso-account-lifecycle", "oriso-otp"),
          Set.copyOf(decoder.decode(admin.accessToken()).getAudience()));
      try {
        var headers = new HttpHeaders();
        headers.setBearerAuth(admin.accessToken());
        http.exchange(
            logical + "/admin/realms/" + realm + "/users/" + account,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);
        fail("Canonical ADMIN must not query native users");
      } catch (HttpClientErrorException denied) {
        assertEquals(403, denied.getStatusCode().value());
      }
      facts.put("canonicalAdmin", true);
      assertThrows(RuntimeException.class, () -> invoke(current, account));
      assertEquals(200, lastStatus.get());
      assertEquals(1, mutations.get());
      facts.put("actualPublicRoleCaller", true);
      assertEquals("FROZEN", phase(jdbc, operation));
      assertEquals(0, callbacks.get());
      assertEquals(List.of(70L), agencyIds(jdbc, account));
      assertEquals(
          originalAdmin,
          new TreeMap<>(jdbc.queryForMap("select * from admin where admin_id=?", account)));
      assertEquals(
          originalConsultant,
          new TreeMap<>(
              jdbc.queryForMap("select * from consultant where consultant_id=?", account)));
      invoke(current, account);
      assertEquals(200, lastStatus.get());
      assertEquals(2, mutations.get());
      assertEquals(1, callbacks.get());
      assertEquals("APPLIED", phase(jdbc, operation));
      assertEquals(List.of(70L, 71L), agencyIds(jdbc, account));
      assertEquals(COMBINED, Set.copyOf(current.owner.read(account).effectiveRoles()));
      facts.put("combinedRolesPreserved", true);
      assertEquals(
          originalAdmin,
          new TreeMap<>(jdbc.queryForMap("select * from admin where admin_id=?", account)));
      assertEquals(
          originalConsultant,
          new TreeMap<>(
              jdbc.queryForMap("select * from consultant where consultant_id=?", account)));
      facts.put("wholeSqlPreserved", true);
      var saved =
          new TreeMap<>(
              jdbc.queryForMap(
                  "select * from general_admin_identity_intent where operation_id=?", operation));
      invoke(current, account);
      assertEquals(3, mutations.get());
      assertEquals(1, callbacks.get());
      facts.put("savedReplay200", true);
      java.sql.Timestamp birth =
          jdbc.queryForObject(
              "select create_date from consultant where consultant_id=?",
              java.sql.Timestamp.class,
              account);
      assertNotNull(birth);
      assertEquals(
          1,
          jdbc.update(
              "update consultant set create_date=? where consultant_id=?",
              java.sql.Timestamp.valueOf(birth.toLocalDateTime().plusSeconds(1)),
              account));
      int beforeDrift = mutations.get();
      int readsBeforeDrift = reads.get();
      assertThrows(RuntimeException.class, () -> invoke(current, account));
      assertEquals(beforeDrift, mutations.get());
      assertEquals(readsBeforeDrift, reads.get());
      assertEquals(
          1,
          jdbc.update("update consultant set create_date=? where consultant_id=?", birth, account));
      facts.put("sourceDriftBeforeRpcDenied", true);
      control(base, "withdraw");
      assertEquals(COMBINED, Set.copyOf(current.owner.read(account).effectiveRoles()));
      facts.put("readAfterWithdrawal200", true);
      assertThrows(RuntimeException.class, () -> invoke(current, account));
      assertEquals(403, lastStatus.get());
      assertEquals(4, mutations.get());
      assertEquals(
          saved,
          new TreeMap<>(
              jdbc.queryForMap(
                  "select * from general_admin_identity_intent where operation_id=?", operation)));
      assertEquals(1, callbacks.get());
      facts.put("savedAfterWithdrawal403", true);
      control(base, "restore");
      entities.clear();
      var restarted =
          context(
              login(http, logical, realm),
              account,
              operation,
              tenant,
              realm,
              realmId,
              logical,
              issuer,
              identity,
              keycloak,
              actors,
              decoder,
              mode,
              codec,
              http,
              jdbc,
              legacy,
              callbacks,
              facts);
      invoke(restarted, account);
      assertEquals(200, lastStatus.get());
      assertEquals(5, mutations.get());
      assertEquals(
          saved,
          new TreeMap<>(
              jdbc.queryForMap(
                  "select * from general_admin_identity_intent where operation_id=?", operation)));
      assertEquals(receipt.get(), saved.get("native_receipt"));
      assertEquals(1, callbacks.get());
      facts.put("coldRestartSameReceipt", true);
      facts.put("restoredReplay200", true);
      facts.put("sameFrozenCommand", true);
      facts.put("oneLocalCallback", true);
      verifyNoInteractions(legacy);
    } finally {
      SecurityContextHolder.clearContext();
      TenantContext.clear();
      Files.writeString(Path.of(required("PAIR_WITNESS")), codec.canonical(facts));
    }
  }

  private PairContext context(
      String bearer,
      String account,
      String operation,
      Long tenant,
      String realm,
      String realmId,
      String logical,
      String issuer,
      IdentityConfig identity,
      KeycloakConfig keycloak,
      TaskActorConfiguration actors,
      JwtDecoder decoder,
      IdentityClientConfig mode,
      AccountLifecycleCodec codec,
      RestTemplate http,
      JdbcTemplate jdbc,
      IdentityClient legacy,
      AtomicInteger callbacks,
      Map<String, Object> facts) {
    var jwt = decoder.decode(bearer);
    assertEquals(required("PAIR_HUMAN_SUBJECT"), jwt.getSubject());
    assertEquals("protected-human-web", jwt.getClaimAsString("azp"));
    assertEquals("0", jwt.getClaimAsString("tenantId"));
    assertEquals(Set.of("userservice"), Set.copyOf(jwt.getAudience()));
    var roles =
        new TreeSet<String>(
            (List<String>) ((Map<?, ?>) jwt.getClaims().get("realm_access")).get("roles"));
    assertEquals(Set.of("agency-admin", "tenant-admin"), roles);
    var mapped =
        new RoleAuthorizationAuthorityMapper()
            .mapAuthorities(roles.stream().map(SimpleGrantedAuthority::new).toList());
    var authorityNames =
        mapped.stream()
            .map(org.springframework.security.core.GrantedAuthority::getAuthority)
            .collect(java.util.stream.Collectors.toSet());
    assertTrue(authorityNames.contains(Authority.AuthorityValue.TENANT_ADMIN));
    var human =
        new AuthenticatedUser(
            jwt.getSubject(), required("PAIR_HUMAN_USERNAME"), roles, bearer, authorityNames);
    human.setTenantId(0L);
    var authentication = new JwtAuthenticationToken(jwt, mapped);
    assertTrue(authentication.isAuthenticated());
    var request =
        new MockHttpServletRequest("POST", "/useradmin/consultants/" + account + "/roles");
    request.setUserPrincipal(authentication);
    request.addHeader("Authorization", "Bearer " + bearer);
    request.addHeader("Idempotency-Key", operation);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    facts.put("actualCurrentHuman", true);
    var broker =
        new TaskActorTokenBroker(
            actors, new KeycloakAuthClient(http, human, identity), decoder, keycloak);
    var agencies = mock(AgencyService.class);
    var scope =
        new AdminScope(
            human,
            admins,
            adminAgencies,
            consultants,
            consultantAgencies,
            agencies,
            users,
            sessions,
            userAgencies);
    ReflectionTestUtils.setField(scope, "multitenancyEnabled", true);
    assertInstanceOf(AdminScope.Platform.class, scope.current());
    var owner =
        new HumanAdminAccountMaintenance(
            mode,
            human,
            scope,
            jdbc,
            transactions,
            request,
            new KeycloakHumanAdminMaintenance(http, actors, broker, codec, logical, realm, realmId),
            Clock.systemUTC(),
            issuer);
    owner.configureReviewedOrdinaryGroups(
        Map.of(
            "/human-roles/consultant",
            Set.of("consultant", "group-chat-consultant"),
            "/human-roles/consultant-agency-admin",
            COMBINED));
    var grant = spy(new CounsellorAgencyAdminGrantService(legacy, admins, adminAgencies));
    doAnswer(
            invocation -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("ACKNOWLEDGED", phase(jdbc, operation));
              assertNotNull(
                  jdbc.queryForObject(
                      "select native_receipt from general_admin_identity_intent where operation_id=?",
                      String.class,
                      operation));
              facts.put("nativeAcknowledgedBeforeLocal", true);
              callbacks.incrementAndGet();
              return invocation.callRealMethod();
            })
        .when(grant)
        .bindCheckedAgencyAdmin(any(AccountRoleAddition.ScopedAddition.class));
    var service =
        new AccountRoleAddition(
            consultants,
            consultantAgencies,
            admins,
            adminAgencies,
            scope,
            new AccountInviteAccessPolicy(human, scope),
            grant,
            human);
    service.setScopedMaintenance(mode, owner);
    service.setLocalTransactionManager(transactions);
    ReflectionTestUtils.setField(service, "entities", entities);
    AgencyFacts sourceAgency =
        id -> {
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          assertEquals(71L, id);
          return Optional.of(new AgencyFacts.Agency(id, tenant, false, List.of()));
        };
    ReflectionTestUtils.setField(service, "agencyFacts", sourceAgency);
    return new PairContext(new ConsultantRoleController(service), owner, broker);
  }

  private static void invoke(PairContext current, String account) {
    var command = new ConsultantRoleController.AddRoleRequestDTO();
    command.role = "AGENCY_ADMIN";
    command.agencyId = 71L;
    var result = current.controller.addRole(account, command);
    assertEquals(200, result.getStatusCode().value());
    assertEquals(account, Objects.requireNonNull(result.getBody()).consultantId);
    assertEquals("AGENCY_ADMIN", result.getBody().role);
    assertEquals(List.of(70L, 71L), result.getBody().agencyIds);
  }

  private static String login(RestTemplate http, String logical, String realm) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("client_id", "protected-human-web");
    form.add("grant_type", "password");
    form.add("username", required("PAIR_HUMAN_USERNAME"));
    form.add("password", required("PAIR_HUMAN_PASSWORD"));
    form.add("scope", "openid");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    var reply =
        http.postForEntity(
            logical + "/realms/" + realm + "/protocol/openid-connect/token",
            new HttpEntity<>(form, headers),
            Map.class);
    assertEquals(200, reply.getStatusCode().value());
    return (String) Objects.requireNonNull(reply.getBody()).get("access_token");
  }

  private static void control(AtomicReference<String> base, String action) {
    String control = required("PAIR_CONTROL_BASE");
    assertTrue(control.matches("http://127\\.0\\.0\\.1:[0-9]+"));
    var response =
        new RestTemplate().postForEntity(control + "/" + action, HttpEntity.EMPTY, Map.class);
    assertEquals(200, response.getStatusCode().value());
    var body = Objects.requireNonNull(response.getBody());
    assertEquals(Set.of("nativeBase", "stage"), body.keySet());
    assertEquals(action.equals("withdraw") ? "WITHDRAWN" : "RESTORED", body.get("stage"));
    String next = (String) body.get("nativeBase");
    assertTrue(next.matches("http://127\\.0\\.0\\.1:[0-9]+"));
    base.set(next);
  }

  private static List<Long> agencyIds(JdbcTemplate jdbc, String account) {
    return jdbc.queryForList(
        "select agency_id from admin_agency where admin_id=? order by agency_id",
        Long.class,
        account);
  }

  private static String phase(JdbcTemplate jdbc, String operation) {
    return jdbc.queryForObject(
        "select status from general_admin_identity_intent where operation_id=?",
        String.class,
        operation);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(value != null && !value.isBlank(), "Required private native role input absent");
    return value;
  }

  private record PairContext(
      ConsultantRoleController controller,
      HumanAdminAccountMaintenance owner,
      TaskActorTokenBroker broker) {}
}
