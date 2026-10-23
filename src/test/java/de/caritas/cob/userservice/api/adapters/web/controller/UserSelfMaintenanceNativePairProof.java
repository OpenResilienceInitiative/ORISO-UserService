package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.*;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfPasswordMaintenance;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.SelfLocaleMaintenance;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchUserDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.*;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.facade.userdata.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.DecryptionService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.support.*;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

/** Hosted-only real JPA/controller/native pair. The fixture never signs a human JWT or receipt. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserSelfMaintenanceNativePairProof {
  @Autowired private DataSource source;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;

  @Test
  void publicPasswordSavedReplayRequiresCurrentNativeGrantWithoutRepeatingJpaCallback()
      throws Exception {
    runPair("password");
  }

  @Test
  void publicLocaleSavedReplayRequiresCurrentNativeGrantWithoutRepeatingJpaCallback()
      throws Exception {
    runPair("locale");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(value != null && !value.isBlank(), "Required private native pair input absent");
    return value;
  }

  private void runPair(String kind) throws Exception {
    assertEquals(kind, required("PAIR_KIND"));
    assertEquals("true", required("GITHUB_ACTIONS"));
    assertEquals("Linux", System.getProperty("os.name"));
    var facts = new TreeMap<String, Object>();
    facts.put("kind", kind);
    for (String flag : List.of("actualJpa", "actualCurrentSelf", "canonicalAdmin", "firstNative200",
        "savedBeforeWithdrawal200", "sameFrozenCommand", "sameHumanAfterRestart", "readAfterWithdrawal200",
        "savedAfterWithdrawal403", "sqlAcknowledgementUnchanged", "oneLocalCallback",
        "freshHumanAfterRestoration", "restoredReplay200", "originalReceiptRestored",
        "nativeLogicalEffectVerified")) facts.put(flag, false);
    var codec = new AccountLifecycleCodec(Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64")));
    try {
      assertInstanceOf(JpaTransactionManager.class, transactions);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      var jdbc = new JdbcTemplate(source);
      jdbc.execute("drop table if exists identity_self_password_intent");
      jdbc.execute("drop table if exists identity_account_operation_intent");
      new ResourceDatabasePopulator(
          new ClassPathResource("db/changelog/changeset/20261022_identity_self_password_367/migrate.sql"),
          new ClassPathResource("db/changelog/changeset/20261023_identity_self_password_retention_367/migrate.sql"),
          new ClassPathResource("db/changelog/changeset/20261021_identity_self_locale_367/migrate.sql"),
          new ClassPathResource("db/changelog/changeset/20261023_existing_import_intent_367/migrate.sql"))
          .execute(source);
      String account = required("PAIR_ACCOUNT_ID");
      String username = required("PAIR_USERNAME");
      String realm = required("PAIR_REALM");
      String realmId = required("PAIR_REALM_ID");
      String logical = "http://maintenance-human-fixture.invalid";
      String issuer = logical + "/realms/" + realm;
      String oldPassword = required("PAIR_PASSWORD");
      String newPassword = "Chosen-native-pair-" + UUID.randomUUID();
      String operation = UUID.randomUUID().toString();
      var base = new AtomicReference<>(required("PAIR_NATIVE_BASE"));
      assertTrue(base.get().matches("http://127\\.0\\.0\\.1:[0-9]+"));
      if (kind.equals("password")) {
        var consultant = new Consultant();
        consultant.setId(account);
        consultant.setTenantId(11L);
        consultant.setUsername(username);
        consultant.setFirstName("Native");
        consultant.setLastName("SelfPair");
        consultant.setEmail(required("PAIR_EMAIL"));
        consultant.setLanguageCode(LanguageCode.en);
        consultant.setEncourage2fa(false);
        consultant.setMagicLinkLoginEnabled(false);
        consultant.setNotifyEnquiriesRepeating(false);
        consultant.setNotifyNewChatMessageFromAdviceSeeker(false);
        consultant.setPasswordChangeRequired(true);
        consultant.setCreateDate(LocalDateTime.now(ZoneOffset.UTC));
        consultant.setUpdateDate(LocalDateTime.now(ZoneOffset.UTC));
        consultants.saveAndFlush(consultant);
        assertTrue(consultants.findById(account).orElseThrow().getPasswordChangeRequired());
      } else {
        var user = new User(account, null, username, required("PAIR_EMAIL"), false);
        user.setTenantId(11L);
        user.setLanguageCode(LanguageCode.en);
        user.setEncourage2fa(false);
        users.save(user);
        assertEquals(LanguageCode.en, users.findById(account).orElseThrow().getLanguageCode());
      }
      facts.put("actualJpa", true);
      var http = new RestTemplate();
      http.setRequestFactory(new BufferingClientHttpRequestFactory(http.getRequestFactory()));
      var frozenBody = new AtomicReference<String>();
      var firstReceipt = new AtomicReference<String>();
      var lastMutation = new AtomicInteger();
      var mutationCalls = new AtomicInteger();
      String command = kind.equals("password") ? "self-password" : "locale";
      String table = kind.equals("password") ? "identity_self_password_intent" : "identity_account_operation_intent";
      http.getInterceptors().add((request, bytes, execution) -> {
        URI uri = request.getURI();
        assertEquals("maintenance-human-fixture.invalid", uri.getHost());
        var physical = URI.create(base.get() + uri.getRawPath()
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        var mapped = new HttpRequestWrapper(request) {
          @Override public URI getURI() { return physical; }
        };
        boolean mutation = uri.getPath().endsWith("/" + command);
        if (mutation) {
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          String body = new String(bytes, StandardCharsets.UTF_8);
          if (frozenBody.get() == null) frozenBody.set(body);
          else assertEquals(frozenBody.get(), body, "Original native command must remain frozen");
          mutationCalls.incrementAndGet();
          assertTrue(Set.of("FROZEN", "ACKNOWLEDGED").contains(
              jdbc.queryForObject("select status from " + table + " where operation_id=?", String.class, operation)));
        }
        var response = execution.execute(mapped, bytes);
        if (mutation) {
          lastMutation.set(response.getStatusCode().value());
          if (response.getStatusCode().value() == 200) {
            var reply = codec.read(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
            assertEquals("UPDATED", reply.path("result").textValue());
            if (firstReceipt.get() == null) firstReceipt.set(codec.canonical(reply));
            else assertEquals(firstReceipt.get(), codec.canonical(reply));
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
      var actors = new TaskActorConfiguration(environment, new KeycloakCustomConfig(), identity);
      var keycloak = new KeycloakConfig();
      keycloak.setAuthServerUrl(logical);
      keycloak.setRealm(realm);
      var decoder = NimbusJwtDecoder.withJwkSetUri(base.get() + "/realms/" + realm + "/protocol/openid-connect/certs").build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
      var consultantService = spy(new ConsultantService(consultants, mock(ConsultantMobileTokenRepository.class)));
      var accounts = new AtomicReference<AccountManager>();
      var legacy = mock(IdentityManaging.class);
      var mode = mock(IdentityClientConfig.class);
      when(mode.isFiveActorMode()).thenReturn(true);
      String initial = login(http, logical, realm, username, oldPassword);
      var current = context(kind, initial, operation, account, username, realm, realmId, logical,
          issuer, http, decoder, identity, actors, keycloak, mode, codec, jdbc,
          consultantService, accounts, legacy);
      facts.put("actualCurrentSelf", true);
      var checked = current.broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
      assertEquals(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE).serviceSubject(), checked.subject());
      assertEquals(Set.of("userservice", "oriso-account-lifecycle", "oriso-otp"),
          Set.copyOf(decoder.decode(checked.accessToken()).getAudience()));
      try {
        http.exchange(logical + "/admin/realms/" + realm + "/users/" + account, HttpMethod.GET,
            new HttpEntity<>(bearer(checked.accessToken())), String.class);
        fail("Canonical ADMIN must not have native user query powers");
      } catch (HttpClientErrorException denied) { assertEquals(403, denied.getStatusCode().value()); }
      facts.put("canonicalAdmin", true);
      invoke(current.controller, kind, oldPassword, newPassword);
      assertEquals(200, lastMutation.get());
      assertEquals(1, mutationCalls.get());
      assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from " + table + " where operation_id=?", String.class, operation));
      assertEquals(firstReceipt.get(), jdbc.queryForObject("select receipt from " + table + " where operation_id=?", String.class, operation));
      facts.put("firstNative200", true);
      String effectivePassword = kind.equals("password") ? newPassword : oldPassword;
      String actual = login(http, logical, realm, username, effectivePassword);
      if (kind.equals("password")) {
        try { login(http, logical, realm, username, oldPassword); fail("Old password remained valid"); }
        catch (HttpClientErrorException rejected) { assertEquals(400, rejected.getStatusCode().value()); }
        assertFalse(consultants.findById(account).orElseThrow().getPasswordChangeRequired());
      } else {
        assertEquals(LanguageCode.de, users.findById(account).orElseThrow().getLanguageCode());
        assertEquals("de", readOriginalTarget(http, logical, realm, realmId, account, actors,
            current.broker, codec, frozenBody.get(), actual).path("preferredLanguage").textValue());
      }
      facts.put("nativeLogicalEffectVerified", true);
      current = context(kind, actual, operation, account, username, realm, realmId, logical,
          issuer, http, decoder, identity, actors, keycloak, mode, codec, jdbc,
          consultantService, accounts, legacy);
      var originalSql = snapshotRow(jdbc, "select * from " + table + " where operation_id=?", operation);
      var originalDomain = domain(jdbc, kind, account);
      invoke(current.controller, kind, oldPassword, newPassword);
      assertEquals(2, mutationCalls.get());
      assertEquals(200, lastMutation.get());
      facts.put("savedBeforeWithdrawal200", true);
      assertEquals(originalSql, snapshotRow(jdbc, "select * from " + table + " where operation_id=?", operation));
      control(base, "withdraw");
      var sameHuman = decoder.decode(actual);
      assertEquals(account, sameHuman.getSubject());
      assertEquals(required("PAIR_HUMAN_CLIENT"), sameHuman.getClaimAsString("azp"));
      facts.put("sameHumanAfterRestart", true);
      readOriginalTarget(http, logical, realm, realmId, account, actors, current.broker, codec, frozenBody.get(), actual);
      facts.put("readAfterWithdrawal200", true);
      var deniedContext = current;
      assertThrows(ServiceUnavailableException.class,
          () -> invoke(deniedContext.controller, kind, oldPassword, newPassword));
      assertEquals(3, mutationCalls.get());
      assertEquals(403, lastMutation.get());
      facts.put("savedAfterWithdrawal403", true);
      assertEquals(originalSql, snapshotRow(jdbc, "select * from " + table + " where operation_id=?", operation));
      assertEquals(originalDomain, domain(jdbc, kind, account));
      facts.put("sqlAcknowledgementUnchanged", true);
      control(base, "restore");
      String fresh = login(http, logical, realm, username, effectivePassword);
      assertNotEquals(actual, fresh);
      current = context(kind, fresh, operation, account, username, realm, realmId, logical,
          issuer, http, decoder, identity, actors, keycloak, mode, codec, jdbc,
          consultantService, accounts, legacy);
      facts.put("freshHumanAfterRestoration", true);
      invoke(current.controller, kind, oldPassword, newPassword);
      assertEquals(4, mutationCalls.get());
      assertEquals(200, lastMutation.get());
      facts.put("restoredReplay200", true);
      assertEquals(originalSql, snapshotRow(jdbc, "select * from " + table + " where operation_id=?", operation));
      assertEquals(originalDomain, domain(jdbc, kind, account));
      assertEquals(firstReceipt.get(), jdbc.queryForObject("select receipt from " + table + " where operation_id=?", String.class, operation));
      facts.put("originalReceiptRestored", true);
      facts.put("sameFrozenCommand", true);
      if (kind.equals("password")) verify(consultantService, times(1)).saveConsultant(any(Consultant.class));
      else verify(accounts.get(), times(1)).patchUser(anyMap());
      facts.put("oneLocalCallback", true);
      verifyNoInteractions(legacy);
      String saved = codec.canonical(originalSql);
      assertFalse(saved.contains(oldPassword));
      assertFalse(saved.contains(newPassword));
    } finally {
      SecurityContextHolder.clearContext();
      Files.writeString(Path.of(required("PAIR_WITNESS")), codec.canonical(facts));
    }
  }

  private PairContext context(String kind, String bearer, String operation, String account,
      String username, String realm, String realmId, String logical, String issuer, RestTemplate http,
      JwtDecoder decoder, IdentityConfig identity, TaskActorConfiguration actors, KeycloakConfig keycloak,
      IdentityClientConfig mode, AccountLifecycleCodec codec, JdbcTemplate jdbc,
      ConsultantService consultantService, AtomicReference<AccountManager> accounts,
      IdentityManaging legacy) {
    var jwt = decoder.decode(bearer);
    assertEquals(account, jwt.getSubject());
    assertEquals(required("PAIR_HUMAN_CLIENT"), jwt.getClaimAsString("azp"));
    assertEquals("11", jwt.getClaimAsString("tenantId"));
    assertEquals(Set.of("userservice"), Set.copyOf(jwt.getAudience()));
    var roles = new TreeSet<String>((List<String>) ((Map<?, ?>) jwt.getClaims().get("realm_access")).get("roles"));
    assertEquals(kind.equals("password") ? Set.of("consultant", "group-chat-consultant") : Set.of("user"), roles);
    var human = new AuthenticatedUser(account, username, roles, bearer, Set.of(kind.equals("password") ? "ROLE_CONSULTANT" : "ROLE_USER"));
    human.setTenantId(11L);
    var request = new MockHttpServletRequest(kind.equals("password") ? "PUT" : "PATCH",
        kind.equals("password") ? "/service/users/password/change" : "/service/users/data");
    var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(kind.equals("password") ? "ROLE_CONSULTANT" : "ROLE_USER")));
    request.setUserPrincipal(auth);
    request.addHeader("Authorization", "Bearer " + bearer);
    request.addHeader("Idempotency-Key", operation);
    SecurityContextHolder.getContext().setAuthentication(auth);
    var broker = new TaskActorTokenBroker(actors, new KeycloakAuthClient(http, human, identity), decoder, keycloak);
    if (accounts.get() == null) {
      var transcoder = new UsernameTranscoder();
      accounts.set(spy(new AccountManager(consultants, mock(AdminRepository.class), users,
          new UserServiceMapper(transcoder), transcoder, mock(AgencyService.class), mock(TenantService.class),
          mock(ConsultantAgencyRepository.class), human, mock(SessionRepository.class),
          mock(AppointmentService.class), mock(PatchConsultantSaga.class))));
    }
    var delegate = new UserAccountControllerDelegate(mock(UserAccountService.class), human,
        mock(DecryptionService.class), mock(ConsultantDataFacade.class), identity, legacy, accounts.get(),
        mock(Messaging.class), mock(ConsultantDtoMapper.class), new UserDtoMapper(identity), consultantService,
        mock(ConsultantUpdateService.class), mock(ConsultantDataProvider.class), mock(AskerDataProvider.class),
        mock(VideoChatConfig.class), mock(KeycloakUserDataProvider.class), mock(AgencyAdminDataProvider.class), new UsernameTranscoder());
    delegate.setPasswordIdentityConfig(mode);
    delegate.setSelfPasswordMaintenance(new SelfPasswordMaintenance(mode, human, request, jdbc,
        transactions, actors, broker, codec, http, Clock.systemUTC(), logical, issuer, realm, realmId));
    delegate.setSelfLocaleMaintenance(new SelfLocaleMaintenance(mode, human, request, jdbc,
        transactions, actors, broker, codec, http, Clock.systemUTC(), logical, realm, realmId));
    var controller = mock(UserController.class, CALLS_REAL_METHODS);
    ReflectionTestUtils.setField(controller, "userAccountControllerDelegate", delegate);
    return new PairContext(controller, broker);
  }

  private static void invoke(UserController controller, String kind, String oldPassword, String chosen) {
    if (kind.equals("password")) {
      var password = new PasswordDTO(); password.setOldPassword(oldPassword); password.setNewPassword(chosen);
      assertEquals(200, controller.updatePassword(password).getStatusCode().value());
    } else {
      var patch = new PatchUserDTO();
      patch.setPreferredLanguage(de.caritas.cob.userservice.api.adapters.web.dto.LanguageCode.DE);
      assertEquals(204, controller.patchUser(patch).getStatusCode().value());
    }
  }

  private static String login(RestTemplate http, String logical, String realm, String username, String password) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("client_id", required("PAIR_HUMAN_CLIENT")); form.add("grant_type", "password");
    form.add("username", username); form.add("password", password); form.add("scope", "openid");
    var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    var reply = http.postForEntity(logical + "/realms/" + realm + "/protocol/openid-connect/token", new HttpEntity<>(form, headers), Map.class);
    assertEquals(200, reply.getStatusCode().value());
    return (String) Objects.requireNonNull(reply.getBody()).get("access_token");
  }

  private static HttpHeaders bearer(String value) {
    var headers = new HttpHeaders(); headers.setBearerAuth(value); return headers;
  }

  private static void control(AtomicReference<String> base, String action) {
    String control = required("PAIR_CONTROL_BASE");
    assertTrue(control.matches("http://127\\.0\\.0\\.1:[0-9]+"));
    var result = new RestTemplate().postForEntity(control + "/" + action, HttpEntity.EMPTY, Map.class);
    assertEquals(200, result.getStatusCode().value());
    var body = Objects.requireNonNull(result.getBody());
    assertEquals(Set.of("nativeBase", "stage"), body.keySet());
    assertEquals(action.equals("withdraw") ? "WITHDRAWN" : "RESTORED", body.get("stage"));
    String next = (String) body.get("nativeBase");
    assertTrue(next.matches("http://127\\.0\\.0\\.1:[0-9]+")); base.set(next);
  }

  private static JsonNode readOriginalTarget(RestTemplate http, String logical, String realm, String realmId,
      String account, TaskActorConfiguration actors, TaskActorTokenBroker broker, AccountLifecycleCodec codec,
      String frozen, String human) {
    // Probe only the target captured from the actual production command, using actual checked ADMIN.
    var body = Map.of("target", codec.read(frozen, JsonNode.class).path("target"));
    var checked = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    var headers = bearer(checked.accessToken()); headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-ORISO-Human-Authorization", "Bearer " + human);
    headers.set("X-ORISO-Task-Context", codec.maintenanceProof("read", realm, realmId, account,
        actors.require(TaskPurpose.ACCOUNT_MAINTENANCE), body));
    var reply = http.postForEntity(logical + "/realms/" + realm + "/oriso-account-maintenance/v1/accounts/" + account + "/read",
        new HttpEntity<>(codec.canonical(body), headers), String.class);
    assertEquals(200, reply.getStatusCode().value());
    var projection = codec.read(reply.getBody(), JsonNode.class);
    assertEquals(account, projection.path("id").textValue());
    return projection;
  }

  private static Map<String, Object> domain(JdbcTemplate jdbc, String kind, String account) {
    return snapshotRow(jdbc, kind.equals("password")
        ? "select * from consultant where consultant_id=?"
        : "select * from user where user_id=?", account);
  }

  private static Map<String, Object> snapshotRow(JdbcTemplate jdbc, String sql, String key) {
    return jdbc.queryForObject(sql, (row, index) -> {
      var result = new TreeMap<String, Object>();
      for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
        result.put(row.getMetaData().getColumnLabel(column), row.getString(column));
      }
      return result;
    }, key);
  }

  private record PairContext(UserController controller, TaskActorTokenBroker broker) {}
}
