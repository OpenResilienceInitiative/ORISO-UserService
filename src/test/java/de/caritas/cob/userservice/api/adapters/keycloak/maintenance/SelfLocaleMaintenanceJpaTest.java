package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** Actual JPA/UserRepository/AccountManager; separate from public and native pairing proof. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SelfLocaleMaintenanceJpaTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM = "22222222-2222-4222-8222-222222222222";
  private static final String ENDPOINT =
      "https://identity.test/realms/fixture/oriso-account-maintenance/v1/accounts/" + ACCOUNT;
  @Autowired private DataSource dataSource;
  @Autowired private UserRepository users;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entityManager;
  private JdbcTemplate jdbc;
  private SelfLocaleMaintenance maintenance;
  private AccountManager accounts;
  private MockRestServiceServer server;
  private final String operation = UUID.randomUUID().toString();
  private final AtomicInteger nativeMutations = new AtomicInteger();

  @BeforeEach
  void actualJpaOwnerWithNoOuterTestTransaction() {
    assertInstanceOf(JpaTransactionManager.class, transactions);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop table if exists identity_account_operation_intent");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261021_identity_self_locale_367/migrate.sql"))
        .execute(dataSource);
    users.findById(ACCOUNT).ifPresent(users::delete);
    var user = new User(ACCOUNT, null, "ordinary-jpa", "ordinary@fixture.invalid", false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setEncourage2fa(false);
    users.save(user);
    var human =
        new AuthenticatedUser(
            ACCOUNT, "ordinary-jpa", Set.of("user"), "fixture-human", Set.of("ROLE_USER"));
    human.setTenantId(7L);
    var request = new MockHttpServletRequest("PATCH", "/service/users/data");
    var jwt =
        Jwt.withTokenValue("fixture-human")
            .header("alg", "RS256")
            .subject(ACCOUNT)
            .issuer("https://identity.test/realms/fixture")
            .claim("azp", "app")
            .claim("tenantId", "7")
            .issuedAt(Instant.now().minusSeconds(10))
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
    request.setUserPrincipal(
        new JwtAuthenticationToken(
            jwt,
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_USER"))));
    request.addHeader("Authorization", "Bearer fixture-human");
    request.addHeader("Idempotency-Key", operation);
    var mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    var actors = mock(TaskActorConfiguration.class);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.ACCOUNT_ADMIN,
                "backend-account-admin",
                "fixture-secret",
                "33333333-3333-4333-8333-333333333333"));
    var broker = mock(TaskActorTokenBroker.class);
    when(broker.issue(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new TaskActorTokenBroker.TaskToken(TaskActor.ACCOUNT_ADMIN, "fixture-admin", 60));
    var http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    maintenance =
        new SelfLocaleMaintenance(
            mode,
            human,
            request,
            jdbc,
            transactions,
            actors,
            broker,
            new AccountLifecycleCodec(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)),
            http,
            Clock.systemUTC(),
            "https://identity.test",
            "fixture",
            REALM);
    var transcoder = new UsernameTranscoder();
    accounts =
        new AccountManager(
            mock(ConsultantRepository.class),
            mock(AdminRepository.class),
            users,
            new UserServiceMapper(transcoder),
            transcoder,
            mock(AgencyService.class),
            mock(TenantService.class),
            mock(ConsultantAgencyRepository.class),
            human,
            mock(SessionRepository.class),
            mock(AppointmentService.class),
            mock(PatchConsultantSaga.class));
  }

  @Test
  void actualJpaCommitPrecedesNativeDispatchEvenWhenReplyIsUnknown() {
    expectRead();
    server
        .expect(requestTo(ENDPOINT + "/locale"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer fixture-admin"))
        .andExpect(header("X-ORISO-Human-Authorization", "Bearer fixture-human"))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(
                  LanguageCode.de, users.findById(ACCOUNT).orElseThrow().getLanguageCode());
              assertTrue(users.findById(ACCOUNT).orElseThrow().getEncourage2fa());
              assertEquals(
                  1,
                  jdbc.queryForObject(
                      "select count(*) from identity_account_operation_intent where operation_id=? and status='FROZEN' and domain_applied=true",
                      Integer.class,
                      operation));
              nativeMutations.incrementAndGet();
              throw new ResourceAccessException("Fixture lost a native reply");
            });
    var patch =
        Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de", "encourage2fa", true);
    assertThrows(
        RuntimeException.class,
        () -> maintenance.patch("de", patch, () -> accounts.patchUser(patch)));
    assertEquals("FROZEN", status());
    assertEquals(1, nativeMutations.get());
    server.verify();
  }

  @Test
  void actualJpaFlushFailureRollsBackDomainAndPreventsDispatchableIntent() {
    expectRead();
    var patch =
        Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de", "encourage2fa", true);
    assertThrows(
        RuntimeException.class,
        () ->
            maintenance.patch(
                "de",
                patch,
                () -> {
                  assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  accounts.patchUser(patch);
                  entityManager.flush();
                  assertEquals(
                      "de",
                      jdbc.queryForObject(
                          "select language_code from user where user_id=?", String.class, ACCOUNT));
                  throw new IllegalStateException("Fixture failed after actual JPA flush");
                }));
    assertEquals(LanguageCode.en, users.findById(ACCOUNT).orElseThrow().getLanguageCode());
    assertFalse(users.findById(ACCOUNT).orElseThrow().getEncourage2fa());
    assertEquals("PREPARED", status());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from identity_account_operation_intent where operation_id=? and status='PREPARED' and domain_applied=false and intent_body is null and receipt is null",
            Integer.class,
            operation));
    assertEquals(0, nativeMutations.get());
    server.verify();
  }

  @Test
  void anIdenticalSavedImportRowCanNeverResumeAsASelfLocaleOperation() {
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261023_existing_import_intent_367/migrate.sql"))
        .execute(dataSource);
    expectRead();
    server
        .expect(requestTo(ENDPOINT + "/locale"))
        .andRespond(
            outbound -> {
              throw new ResourceAccessException("Fixture lost reply");
            });
    var patch =
        Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de", "encourage2fa", true);
    assertThrows(
        RuntimeException.class,
        () -> maintenance.patch("de", patch, () -> accounts.patchUser(patch)));
    assertEquals("FROZEN", status());
    assertEquals(
        "SELF_LOCALE",
        jdbc.queryForObject(
            "select operation_kind from identity_account_operation_intent where operation_id=?",
            String.class,
            operation));
    jdbc.update(
        "update identity_account_operation_intent set operation_kind='EXISTING_CONSULTANT_IMPORT',status='ACKNOWLEDGED',receipt='synthetic-other-namespace' where operation_id=?",
        operation);
    var frozen =
        jdbc.queryForMap(
            "select * from identity_account_operation_intent where operation_id=?", operation);
    assertThrows(
        RuntimeException.class,
        () ->
            maintenance.patch(
                "de",
                patch,
                () -> {
                  fail("Foreign operation cannot invoke domain callback");
                  return java.util.Optional.empty();
                }));
    assertEquals(
        frozen,
        jdbc.queryForMap(
            "select * from identity_account_operation_intent where operation_id=?", operation));
    assertEquals(LanguageCode.de, users.findById(ACCOUNT).orElseThrow().getLanguageCode());
    server.verify();
  }

  private void expectRead() {
    server
        .expect(requestTo(ENDPOINT + "/read"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("PREPARED", status());
              var result = new LinkedHashMap<String, Object>();
              result.put("id", ACCOUNT);
              result.put("username", "ordinary-jpa");
              result.put("email", null);
              result.put("firstName", null);
              result.put("lastName", null);
              result.put("enabled", true);
              result.put("emailVerified", false);
              result.put("tenantId", "7");
              result.put("preferredLanguage", "en");
              result.put("effectiveRoles", List.of("user"));
              result.put("passwordChangeRequired", false);
              result.put("state", "a".repeat(64));
              return withSuccess(
                      new AccountLifecycleCodec(
                              "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                          .canonical(result),
                      MediaType.APPLICATION_JSON)
                  .createResponse(outbound);
            });
  }

  private String status() {
    return jdbc.queryForObject(
        "select status from identity_account_operation_intent where operation_id=?",
        String.class,
        operation);
  }
}
