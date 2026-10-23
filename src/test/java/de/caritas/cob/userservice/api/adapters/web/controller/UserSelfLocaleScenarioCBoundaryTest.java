package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.PatchConsultantSaga;
import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.SelfLocaleMaintenance;
import de.caritas.cob.userservice.api.adapters.web.dto.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchUserDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.facade.userdata.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.DecryptionService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Real public controller/delegate/mapper/domain code; H2 and controlled HTTP, not native JWT proof.
 */
class UserSelfLocaleScenarioCBoundaryTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM_ID = "22222222-2222-4222-8222-222222222222";
  private static final String ADMIN = "33333333-3333-4333-8333-333333333333";
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  @AfterEach
  void releaseThreadSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void publicPatchSeparatesInternalRpcFromVerifiedPublicIssuerAndUsesCheckedMachineToken() {
    var fixture = new Fixture(true, true);
    fixture.expectNative(false);
    assertDoesNotThrow(
        () ->
            assertEquals(
                204, fixture.controller.patchUser(fixture.patch(false)).getStatusCode().value()));
    assertEquals("ACKNOWLEDGED", fixture.status());
    assertEquals(1, fixture.domainCalls());
    assertEquals(1, fixture.nativeEffects.get());
    verify(fixture.broker, never()).issue(any());
    verify(fixture.broker, atLeastOnce()).issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    fixture.server.verify();
  }

  @Test
  void publicPatchCapturesIntentAndDomainCommitBeforeScopedLocaleRpc() {
    var fixture = new Fixture(true);
    fixture.expectNative(false);
    assertDoesNotThrow(
        () ->
            assertEquals(
                204, fixture.controller.patchUser(fixture.patch(false)).getStatusCode().value()));
    assertEquals(1, fixture.domainCalls());
    assertEquals(1, fixture.nativeEffects.get());
    assertEquals("ACKNOWLEDGED", fixture.status());
    fixture.server.verify();
  }

  @Test
  void publicPatchNewOwnerReplaysFrozenLocaleAfterLostReplyWithoutDomainReapply() {
    var fixture = new Fixture(true);
    fixture.expectNative(true);
    assertThrows(RuntimeException.class, () -> fixture.controller.patchUser(fixture.patch(false)));
    assertEquals(1, fixture.domainCalls());
    assertEquals("FROZEN", fixture.status());
    fixture.installOwner();
    assertDoesNotThrow(
        () ->
            assertEquals(
                204, fixture.controller.patchUser(fixture.patch(false)).getStatusCode().value()));
    assertDoesNotThrow(() -> fixture.controller.patchUser(fixture.patch(false)));
    assertEquals(1, fixture.domainCalls());
    assertEquals(1, fixture.nativeEffects.get());
    assertEquals("ACKNOWLEDGED", fixture.status());
    fixture.server.verify();
  }

  @Test
  void publicTenantConsultantPairRetainsProtectedOriginAcrossLostReplyAndRestart() {
    var fixture = new Fixture(true);
    fixture.installTenantConsultantPair();
    fixture.expectNative(true);
    assertThrows(RuntimeException.class, () -> fixture.controller.patchUser(fixture.patch(false)));
    assertEquals(1, fixture.domainCalls());
    assertEquals(1, fixture.nativeEffects.get());
    var target =
        fixture.codec.read(
            fixture.jdbc.queryForObject(
                "select target_json from identity_account_operation_intent where operation_id=?",
                String.class,
                fixture.operation),
            JsonNode.class);
    assertEquals(
        "PROTECTED_ACCOUNT_OPERATIONS_V1",
        target.path("origin").path("humanAuthority").textValue());
    fixture.installOwner();
    assertDoesNotThrow(() -> fixture.controller.patchUser(fixture.patch(false)));
    assertEquals("ACKNOWLEDGED", fixture.status());
    assertEquals(1, fixture.domainCalls());
    assertEquals(1, fixture.nativeEffects.get());
    fixture.server.verify();
  }

  @Test
  void publicPatchSameIdCannotSubstituteTheCompleteMixedRequest() {
    var fixture = new Fixture(true);
    fixture.expectNative(false);
    assertDoesNotThrow(() -> fixture.controller.patchUser(fixture.patch(false)));
    assertThrows(ConflictException.class, () -> fixture.controller.patchUser(fixture.patch(true)));
    assertEquals(1, fixture.domainCalls());
    assertFalse(
        fixture.jdbc.queryForObject(
            "select encourage2fa from user where user_id=?", Boolean.class, ACCOUNT));
    assertEquals(1, fixture.nativeEffects.get());
    fixture.server.verify();
  }

  @Test
  void publicPatchDomainFailureCannotCommitDispatchableIntentOrPartialDomainChange() {
    var fixture = new Fixture(true);
    fixture.failDomainAfterWrite = true;
    fixture.expectRead();
    assertThrows(RuntimeException.class, () -> fixture.controller.patchUser(fixture.patch(true)));
    assertEquals(0, fixture.domainCalls());
    assertEquals(
        "en",
        fixture.jdbc.queryForObject(
            "select language_code from user where user_id=?", String.class, ACCOUNT));
    assertFalse(
        fixture.jdbc.queryForObject(
            "select encourage2fa from user where user_id=?", Boolean.class, ACCOUNT));
    assertEquals("PREPARED", fixture.status());
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "select count(*) from identity_account_operation_intent where operation_id=? and status='PREPARED' and domain_applied=false and intent_body is null and receipt is null",
            Integer.class,
            fixture.operation));
    assertEquals(0, fixture.nativeEffects.get());
    fixture.server.verify();
  }

  @Test
  void publicPatchRejectsUnverifiedReceiptAndRetainsFrozenIntentForExactRetry() {
    var fixture = new Fixture(true);
    fixture.tamperReceipt = true;
    fixture.expectNative(false);
    assertThrows(RuntimeException.class, () -> fixture.controller.patchUser(fixture.patch(false)));
    assertEquals(1, fixture.domainCalls());
    assertEquals(1, fixture.nativeEffects.get());
    assertEquals("FROZEN", fixture.status());
    assertNull(
        fixture.jdbc.queryForObject(
            "select receipt from identity_account_operation_intent where operation_id=?",
            String.class,
            fixture.operation));
    fixture.server.verify();
  }

  @Test
  void publicPatchCurrentDomainTenantMismatchRefusesBeforeIntentOrRpc() {
    var fixture = new Fixture(true);
    fixture.jdbc.update("update user set tenant_id=8 where user_id=?", ACCOUNT);
    assertThrows(
        ForbiddenException.class, () -> fixture.controller.patchUser(fixture.patch(false)));
    assertEquals(0, fixture.domainCalls());
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "select count(*) from identity_account_operation_intent", Integer.class));
    assertEquals(0, fixture.nativeEffects.get());
    fixture.server.verify();
  }

  @Test
  void publicPatchMissingIntentRefusesBeforeAnyDomainMutation() {
    var fixture = new Fixture(true);
    fixture.request.removeHeader("Idempotency-Key");
    assertThrows(
        BadRequestException.class, () -> fixture.controller.patchUser(fixture.patch(false)));
    assertEquals(0, fixture.domainCalls());
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "select count(*) from identity_account_operation_intent", Integer.class));
    fixture.server.verify();
  }

  @Test
  void domainOnlyPublicPatchKeepsItsWorkingNoHeaderContract() {
    var fixture = new Fixture(true);
    fixture.request.removeHeader("Idempotency-Key");
    var patch = new PatchUserDTO();
    patch.setEncourage2fa(true);
    assertEquals(204, fixture.controller.patchUser(patch).getStatusCode().value());
    assertEquals(1, fixture.domainCalls());
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "select count(*) from identity_account_operation_intent", Integer.class));
    verifyNoInteractions(fixture.legacyIdentity);
    fixture.server.verify();
  }

  @Test
  void legacyLocalePublicPatchKeepsTheExistingNoHeaderIdentityCall() {
    var fixture = new Fixture(false);
    fixture.request.removeHeader("Idempotency-Key");
    assertEquals(204, fixture.controller.patchUser(fixture.patch(false)).getStatusCode().value());
    assertEquals(1, fixture.domainCalls());
    verify(fixture.legacyIdentity).changeLanguage(ACCOUNT, "de");
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "select count(*) from identity_account_operation_intent", Integer.class));
    fixture.server.verify();
  }

  private static final class Fixture {
    final IdentityClientConfig mode = mock(IdentityClientConfig.class);
    final AuthenticatedUser human =
        new AuthenticatedUser(
            ACCOUNT, "ordinary", Set.of("user"), "fixture-human", Set.of("ROLE_USER"));
    final MockHttpServletRequest request =
        new MockHttpServletRequest("PATCH", "/service/users/data");
    final DriverManagerDataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:self-locale-"
                + UUID.randomUUID()
                + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
    final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
    final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
    final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
    final RestTemplate http = new RestTemplate();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    final IdentityManaging legacyIdentity = mock(IdentityManaging.class);
    final UserController controller = mock(UserController.class, CALLS_REAL_METHODS);
    final UserAccountControllerDelegate delegate;
    final AtomicInteger nativeEffects = new AtomicInteger();
    final boolean splitIssuer;
    boolean failDomainAfterWrite;
    boolean tamperReceipt;
    final String operation = UUID.randomUUID().toString();
    final TaskActorCredentials actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "fixture-secret", ADMIN);

    Fixture(boolean fiveActors) {
      this(fiveActors, false);
    }

    Fixture(boolean fiveActors, boolean splitIssuer) {
      this.splitIssuer = splitIssuer;
      when(mode.isFiveActorMode()).thenReturn(fiveActors);
      human.setTenantId(7L);
      var jwt =
          Jwt.withTokenValue("fixture-human")
              .header("alg", "RS256")
              .subject(ACCOUNT)
              .issuer(
                  splitIssuer
                      ? "https://public.identity.test/realms/fixture"
                      : "https://identity.test/realms/fixture")
              .claim("azp", "app")
              .claim("tenantId", "7")
              .issuedAt(Instant.now().minusSeconds(10))
              .expiresAt(Instant.now().plusSeconds(600))
              .build();
      var authentication =
          new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
      request.setUserPrincipal(authentication);
      request.addHeader("Authorization", "Bearer fixture-human");
      request.addHeader("Idempotency-Key", operation);
      SecurityContextHolder.getContext().setAuthentication(authentication);
      jdbc.execute(
          "create table user(user_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,language_code varchar(8),encourage2fa boolean,patch_count int,email varchar(320),create_date timestamp(6) default '2026-10-20 10:11:12.123456')");
      jdbc.execute(
          "create table consultant(consultant_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,email varchar(320),create_date timestamp(6),language_code varchar(8),encourage2fa boolean,patch_count int)");
      jdbc.execute(
          "create table admin(admin_id varchar(36) primary key,tenant_id bigint,type varchar(32),email varchar(320),create_date timestamp(6))");
      jdbc.update(
          "insert into user(user_id,tenant_id,delete_date,language_code,encourage2fa,patch_count) values(?,?,null,'en',false,0)",
          ACCOUNT,
          7L);
      new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
              new org.springframework.core.io.ClassPathResource(
                  "db/changelog/changeset/20261021_identity_self_locale_367/migrate.sql"),
              new org.springframework.core.io.ClassPathResource(
                  "db/changelog/changeset/20261023_existing_import_intent_367/migrate.sql"))
          .execute(source);
      when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
      var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
      when(checked.accessToken()).thenReturn("fixture-admin");
      when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
      when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
      when(checked.clientId()).thenReturn(actor.clientId());
      when(checked.subject()).thenReturn(actor.serviceSubject());
      when(checked.issuer())
          .thenReturn(
              splitIssuer
                  ? "https://public.identity.test/realms/fixture"
                  : "https://identity.test/realms/fixture");
      when(checked.validatedJwtIssuedAt())
          .thenReturn(Instant.now().minusSeconds(1).getEpochSecond());
      when(checked.validatedJwtExpiresAt())
          .thenReturn(Instant.now().plusSeconds(60).getEpochSecond());
      when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
      if (!splitIssuer)
        when(broker.issue(TaskPurpose.ACCOUNT_MAINTENANCE))
            .thenReturn(
                new TaskActorTokenBroker.TaskToken(TaskActor.ACCOUNT_ADMIN, "fixture-admin", 60));
      var users = mock(UserRepository.class);
      when(users.findByUserIdAndDeleteDateIsNull(ACCOUNT))
          .thenAnswer(
              ignored -> {
                if (jdbc.queryForObject(
                        "select count(*) from user where user_id=?", Integer.class, ACCOUNT)
                    == 0) return Optional.empty();
                var user = new User();
                user.setUserId(ACCOUNT);
                user.setUsername("ordinary");
                user.setTenantId(7L);
                user.setLanguageCode(
                    com.neovisionaries.i18n.LanguageCode.valueOf(
                        jdbc.queryForObject(
                            "select language_code from user where user_id=?",
                            String.class,
                            ACCOUNT)));
                user.setEncourage2fa(
                    jdbc.queryForObject(
                        "select encourage2fa from user where user_id=?", Boolean.class, ACCOUNT));
                return Optional.of(user);
              });
      when(users.save(any(User.class)))
          .thenAnswer(
              invocation -> {
                User saved = invocation.getArgument(0);
                jdbc.update(
                    "update user set language_code=?,encourage2fa=?,patch_count=patch_count+1 where user_id=?",
                    saved.getLanguageCode().toString(),
                    saved.getEncourage2fa(),
                    ACCOUNT);
                if (failDomainAfterWrite)
                  throw new IllegalStateException("Fixture domain write failed");
                return saved;
              });
      var consultants = mock(ConsultantRepository.class);
      when(consultants.findByIdAndDeleteDateIsNull(ACCOUNT))
          .thenAnswer(
              ignored -> {
                if (jdbc.queryForObject(
                        "select count(*) from consultant where consultant_id=?",
                        Integer.class,
                        ACCOUNT)
                    == 0) return Optional.empty();
                var consultant = new de.caritas.cob.userservice.api.model.Consultant();
                consultant.setId(ACCOUNT);
                consultant.setTenantId(7L);
                consultant.setUsername("ordinary");
                consultant.setEmail("ordinary@fixture.invalid");
                consultant.setLanguageCode(
                    com.neovisionaries.i18n.LanguageCode.valueOf(
                        jdbc.queryForObject(
                            "select language_code from consultant where consultant_id=?",
                            String.class,
                            ACCOUNT)));
                return Optional.of(consultant);
              });
      when(consultants.save(any(de.caritas.cob.userservice.api.model.Consultant.class)))
          .thenAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                de.caritas.cob.userservice.api.model.Consultant changed = invocation.getArgument(0);
                jdbc.update(
                    "update consultant set language_code=?,encourage2fa=?,patch_count=patch_count+1 where consultant_id=?",
                    changed.getLanguageCode().toString(),
                    changed.getEncourage2fa(),
                    ACCOUNT);
                return changed;
              });
      var transcoder = new UsernameTranscoder();
      var consultantPatch =
          new PatchConsultantSaga(
              consultants, new UserServiceMapper(transcoder), mock(AppointmentService.class));
      var accounts =
          new AccountManager(
              consultants,
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
              consultantPatch);
      var policy = mock(IdentityPolicy.class);
      delegate =
          new UserAccountControllerDelegate(
              mock(UserAccountService.class),
              human,
              mock(DecryptionService.class),
              mock(ConsultantDataFacade.class),
              policy,
              legacyIdentity,
              accounts,
              mock(Messaging.class),
              mock(ConsultantDtoMapper.class),
              new UserDtoMapper(policy),
              mock(ConsultantService.class),
              mock(ConsultantUpdateService.class),
              mock(ConsultantDataProvider.class),
              mock(AskerDataProvider.class),
              mock(VideoChatConfig.class),
              mock(KeycloakUserDataProvider.class),
              mock(AgencyAdminDataProvider.class),
              transcoder);
      ReflectionTestUtils.setField(controller, "userAccountControllerDelegate", delegate);
      installOwner();
    }

    void installTenantConsultantPair() {
      jdbc.update("delete from user where user_id=?", ACCOUNT);
      jdbc.update(
          "insert into consultant values(?,7,null,'ordinary@fixture.invalid','2026-10-20 10:11:12.123456','en',false,0)",
          ACCOUNT);
      jdbc.update(
          "insert into admin values(?,7,'TENANT','ordinary@fixture.invalid','2026-10-20 10:11:12.123456')",
          ACCOUNT);
    }

    void installOwner() {
      delegate.setSelfLocaleMaintenance(
          new SelfLocaleMaintenance(
              mode,
              human,
              request,
              jdbc,
              transactions,
              actors,
              broker,
              codec,
              http,
              Clock.systemUTC(),
              splitIssuer ? "https://internal.identity.test" : "https://identity.test",
              splitIssuer
                  ? "https://public.identity.test/realms/fixture"
                  : "https://identity.test/realms/fixture",
              "fixture",
              REALM_ID));
    }

    PatchUserDTO patch(boolean encourage) {
      var patch = new PatchUserDTO();
      patch.setPreferredLanguage(LanguageCode.DE);
      patch.setEncourage2fa(encourage);
      return patch;
    }

    int domainCalls() {
      return jdbc.queryForObject(
          "select coalesce((select sum(patch_count) from user),0)+coalesce((select sum(patch_count) from consultant),0)",
          Integer.class);
    }

    String status() {
      return jdbc.queryForObject(
          "select status from identity_account_operation_intent where operation_id=?",
          String.class,
          operation);
    }

    void expectRead() {
      String endpoint =
          (splitIssuer ? "https://internal.identity.test" : "https://identity.test")
              + "/realms/fixture/oriso-account-maintenance/v1/accounts/"
              + ACCOUNT;
      server
          .expect(requestTo(endpoint + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("Authorization", "Bearer fixture-admin"))
          .andExpect(header("X-ORISO-Human-Authorization", "Bearer fixture-human"))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(0, domainCalls());
                assertEquals("PREPARED", status());
                return withSuccess(
                        "{\"id\":\""
                            + ACCOUNT
                            + "\",\"username\":\"ordinary\",\"email\":null,\"firstName\":null,\"lastName\":null,\"enabled\":true,\"emailVerified\":false,\"tenantId\":\"7\",\"preferredLanguage\":\"en\",\"effectiveRoles\":[\"user\"],\"passwordChangeRequired\":false,\"state\":\""
                            + "a".repeat(64)
                            + "\"}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
    }

    void expectNative(boolean lostReply) {
      expectRead();
      String endpoint =
          (splitIssuer ? "https://internal.identity.test" : "https://identity.test")
              + "/realms/fixture/oriso-account-maintenance/v1/accounts/"
              + ACCOUNT;
      var frozen = new AtomicReference<String>();
      var reply = new AtomicReference<String>();
      server
          .expect(
              lostReply ? ExpectedCount.twice() : ExpectedCount.once(),
              requestTo(endpoint + "/locale"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("Authorization", "Bearer fixture-admin"))
          .andExpect(header("X-ORISO-Human-Authorization", "Bearer fixture-human"))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(1, domainCalls());
                assertEquals(
                    1,
                    jdbc.queryForObject(
                        "select count(*) from identity_account_operation_intent where operation_id=? and status='FROZEN' and domain_applied=true",
                        Integer.class,
                        operation));
                String wire = ((MockClientHttpRequest) outbound).getBodyAsString();
                if (frozen.get() == null) {
                  frozen.set(wire);
                  var body = codec.read(wire, JsonNode.class);
                  assertEquals(operation, body.path("operationId").asText());
                  assertEquals(
                      ACCOUNT, body.path("target").path("origin").path("humanSubject").asText());
                  assertEquals(
                      "app", body.path("target").path("origin").path("humanClient").asText());
                  var payload =
                      codec.read("{}", com.fasterxml.jackson.databind.node.ObjectNode.class);
                  payload.put("realmId", REALM_ID);
                  payload.put("accountId", ACCOUNT);
                  payload.put("operationId", operation);
                  payload.put("command", "locale");
                  payload.put("ownerClient", actor.clientId());
                  payload.put("ownerSubject", actor.serviceSubject());
                  payload.put("intentDigest", codec.digest("maintenance-intent", body));
                  payload.put("previousState", body.path("expectedState").asText());
                  payload.put("tenantId", "7");
                  payload.put(
                      "originDigest",
                      codec.digest("maintenance-origin", body.path("target").path("origin")));
                  payload.put("result", "UPDATED");
                  String encoded =
                      Base64.getUrlEncoder()
                          .withoutPadding()
                          .encodeToString(
                              codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
                  try {
                    var mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
                    String receipt =
                        encoded
                            + "."
                            + HexFormat.of()
                                .formatHex(
                                    mac.doFinal(
                                        ("maintenance-operation-receipt\n" + encoded)
                                            .getBytes(StandardCharsets.UTF_8)));
                    reply.set(
                        codec.canonical(
                            Map.of(
                                "operationId",
                                operation,
                                "accountId",
                                ACCOUNT,
                                "result",
                                "UPDATED",
                                "receipt",
                                receipt)));
                  } catch (java.security.GeneralSecurityException impossible) {
                    throw new IllegalStateException(impossible);
                  }
                  nativeEffects.incrementAndGet();
                  if (lostReply)
                    throw new ResourceAccessException("Fixture lost a committed reply");
                } else assertEquals(frozen.get(), wire);
                String response = reply.get();
                if (tamperReceipt) {
                  var changed =
                      codec.read(response, com.fasterxml.jackson.databind.node.ObjectNode.class);
                  changed.put("receipt", changed.path("receipt").asText() + "x");
                  response = codec.canonical(changed);
                }
                return withSuccess(response, MediaType.APPLICATION_JSON).createResponse(outbound);
              });
    }
  }
}
