package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfPasswordMaintenance;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.*;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.facade.userdata.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.in.*;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.*;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** Actual public controller, private SQL owner and fixed native HTTP seam; no native JWT credit. */
class UserSelfPasswordScenarioCBoundaryTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM = "22222222-2222-4222-8222-222222222222";
  private static final String SUBJECT = "33333333-3333-4333-8333-333333333333";
  private static final String CURRENT = "Synthetic-current-password";
  private static final String CHOSEN = "Synthetic-chosen-password";
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  @Test
  void publicPasswordFreezesEncryptedIntentAndClearsSetupOnlyAfterVerifiedNativeOldPasswordReceipt()
      throws Exception {
    try (var f = new Fixture(true, true)) {
      f.expectNative();
      assertDoesNotThrow(
          () ->
              assertEquals(200, f.controller.updatePassword(f.password()).getStatusCode().value()));
      assertEquals("ACKNOWLEDGED", f.status());
      assertEquals(1, f.effects.get());
      assertEquals(1, f.clears());
      String stored = f.jdbc.queryForList("select * from identity_self_password_intent").toString();
      for (String secret : List.of(CURRENT, CHOSEN, "fixture-human", "fixture-admin"))
        assertFalse(stored.contains(secret));
      assertEquals(200, f.controller.updatePassword(f.password()).getStatusCode().value());
      assertEquals(1, f.clears());
      verifyNoInteractions(f.legacy);
      f.server.verify();
    }
  }

  @Test
  void missingScopedPasswordOwnerRefusesBeforeLegacyOrSetupMutation() {
    try (var f = new Fixture(true, false)) {
      assertThrows(
          ServiceUnavailableException.class, () -> f.controller.updatePassword(f.password()));
      assertEquals(0, f.clears());
      verifyNoInteractions(f.legacy);
    }
  }

  @Test
  void samePasswordConflictKeepsExistingSetupRequirementAndDoesNotCallIdentity() {
    try (var f = new Fixture(true, true)) {
      var dto = f.password();
      dto.setNewPassword(CURRENT);
      assertThrows(ConflictException.class, () -> f.controller.updatePassword(dto));
      assertEquals(0, f.clears());
      verifyNoInteractions(f.legacy);
    }
  }

  @Test
  void legacyPasswordRetainsCurrentPasswordValidationAndKnownSuccessCleanup() {
    try (var f = new Fixture(false, false)) {
      when(f.legacy.validatePasswordIgnoring2fa(anyString(), eq(CURRENT))).thenReturn(true);
      when(f.legacy.changePassword(ACCOUNT, CHOSEN)).thenReturn(true);
      assertEquals(200, f.controller.updatePassword(f.password()).getStatusCode().value());
      verify(f.legacy).validatePasswordIgnoring2fa(anyString(), eq(CURRENT));
      verify(f.legacy).changePassword(ACCOUNT, CHOSEN);
      assertEquals(1, f.clears());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final UserController controller = mock(UserController.class, CALLS_REAL_METHODS);
    final IdentityManaging legacy = mock(IdentityManaging.class);
    final JdbcTemplate jdbc;
    final RestTemplate http = new RestTemplate();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    final AtomicInteger effects = new AtomicInteger();
    final String operation = UUID.randomUUID().toString();
    final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
    final TaskActorCredentials actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "fixture-secret", SUBJECT);

    Fixture(boolean five, boolean installed) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:password-"
                  + UUID.randomUUID()
                  + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      jdbc = new JdbcTemplate(source);
      jdbc.execute(
          "create table consultant(consultant_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,username varchar(512),password_change_required boolean,update_date timestamp,clear_count int)");
      jdbc.update(
          "insert into consultant values(?,?,null,'ordinary',true,?,0)",
          ACCOUNT,
          7L,
          java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
      jdbc.execute(
          "create table user(user_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,username varchar(512))");
      jdbc.execute(
          "create table identity_self_password_intent(operation_id varchar(36) primary key,realm_id varchar(512),account_id varchar(36),tenant_id bigint,owner_client varchar(255),owner_subject varchar(36),request_digest varchar(64),target_json clob,metadata_json clob,intent_envelope clob,status varchar(32),receipt clob,updated_at timestamp)");
      var human =
          new AuthenticatedUser(
              ACCOUNT,
              "ordinary",
              Set.of("consultant"),
              "fixture-human",
              Set.of("ROLE_CONSULTANT"));
      human.setTenantId(7L);
      var request = new MockHttpServletRequest("PUT", "/service/users/password/change");
      var jwt =
          Jwt.withTokenValue("fixture-human")
              .header("alg", "RS256")
              .subject(ACCOUNT)
              .issuer("https://public.identity.test/realms/fixture")
              .claim("azp", "app")
              .claim("tenantId", "7")
              .issuedAt(Instant.now().minusSeconds(10))
              .expiresAt(Instant.now().plusSeconds(600))
              .build();
      request.setUserPrincipal(
          new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CONSULTANT"))));
      request.addHeader("Authorization", "Bearer fixture-human");
      request.addHeader("Idempotency-Key", operation);
      var mode = mock(IdentityClientConfig.class);
      when(mode.isFiveActorMode()).thenReturn(five);
      var actors = mock(TaskActorConfiguration.class);
      when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
      var broker = mock(TaskActorTokenBroker.class);
      var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
      when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
      when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
      when(checked.accessToken()).thenReturn("fixture-admin");
      when(checked.issuer()).thenReturn("https://public.identity.test/realms/fixture");
      when(checked.clientId()).thenReturn(actor.clientId());
      when(checked.subject()).thenReturn(SUBJECT);
      when(checked.validatedJwtIssuedAt())
          .thenReturn(Instant.now().minusSeconds(1).getEpochSecond());
      when(checked.validatedJwtExpiresAt())
          .thenReturn(Instant.now().plusSeconds(300).getEpochSecond());
      when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
      var consultants = mock(ConsultantService.class);
      when(consultants.getConsultant(ACCOUNT))
          .thenAnswer(
              ignored -> {
                var c = new Consultant();
                c.setId(ACCOUNT);
                c.setPasswordChangeRequired(
                    jdbc.queryForObject(
                        "select password_change_required from consultant where consultant_id=?",
                        Boolean.class,
                        ACCOUNT));
                return Optional.of(c);
              });
      when(consultants.saveConsultant(any(Consultant.class)))
          .thenAnswer(
              call -> {
                jdbc.update(
                    "update consultant set password_change_required=false,clear_count=clear_count+1 where consultant_id=?",
                    ACCOUNT);
                return call.getArgument(0);
              });
      var policy = mock(IdentityPolicy.class);
      var delegate =
          new UserAccountControllerDelegate(
              mock(UserAccountService.class),
              human,
              mock(DecryptionService.class),
              mock(ConsultantDataFacade.class),
              policy,
              legacy,
              mock(AccountManaging.class),
              mock(Messaging.class),
              mock(ConsultantDtoMapper.class),
              new UserDtoMapper(policy),
              consultants,
              mock(ConsultantUpdateService.class),
              mock(ConsultantDataProvider.class),
              mock(AskerDataProvider.class),
              mock(VideoChatConfig.class),
              mock(KeycloakUserDataProvider.class),
              mock(AgencyAdminDataProvider.class),
              new UsernameTranscoder());
      delegate.setPasswordIdentityConfig(mode);
      if (installed)
        delegate.setSelfPasswordMaintenance(
            new SelfPasswordMaintenance(
                mode,
                human,
                request,
                jdbc,
                new DataSourceTransactionManager(source),
                actors,
                broker,
                codec,
                http,
                Clock.systemUTC(),
                "https://internal.identity.test",
                "https://public.identity.test/realms/fixture",
                "fixture",
                REALM));
      ReflectionTestUtils.setField(controller, "userAccountControllerDelegate", delegate);
    }

    PasswordDTO password() {
      var dto = new PasswordDTO();
      dto.setOldPassword(CURRENT);
      dto.setNewPassword(CHOSEN);
      return dto;
    }

    int clears() {
      return jdbc.queryForObject(
          "select clear_count from consultant where consultant_id=?", Integer.class, ACCOUNT);
    }

    String status() {
      return jdbc.queryForObject(
          "select status from identity_self_password_intent where operation_id=?",
          String.class,
          operation);
    }

    void expectNative() {
      String endpoint =
          "https://internal.identity.test/realms/fixture/oriso-account-maintenance/v1/accounts/"
              + ACCOUNT;
      server
          .expect(requestTo(endpoint + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("Authorization", "Bearer fixture-admin"))
          .andExpect(header("X-ORISO-Human-Authorization", "Bearer fixture-human"))
          .andRespond(
              out -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("PREPARED", status());
                assertEquals(0, clears());
                var read = new TreeMap<String, Object>();
                read.put("id", ACCOUNT);
                read.put("username", "ordinary");
                read.put("email", null);
                read.put("firstName", null);
                read.put("lastName", null);
                read.put("enabled", true);
                read.put("emailVerified", false);
                read.put("tenantId", "7");
                read.put("preferredLanguage", null);
                read.put("effectiveRoles", List.of("consultant"));
                read.put("passwordChangeRequired", true);
                read.put("state", "a".repeat(64));
                return withSuccess(codec.canonical(read), MediaType.APPLICATION_JSON)
                    .createResponse(out);
              });
      server
          .expect(requestTo(endpoint + "/self-password"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("Authorization", "Bearer fixture-admin"))
          .andRespond(
              out -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("FROZEN", status());
                assertEquals(0, clears());
                effects.incrementAndGet();
                JsonNode body =
                    codec.read(((MockClientHttpRequest) out).getBodyAsString(), JsonNode.class);
                assertEquals(CURRENT, body.path("change").path("currentPassword").textValue());
                assertEquals(CHOSEN, body.path("change").path("password").textValue());
                var facts = new TreeMap<String, Object>();
                facts.put("realmId", REALM);
                facts.put("accountId", ACCOUNT);
                facts.put("operationId", operation);
                facts.put("command", "self-password");
                facts.put("ownerClient", actor.clientId());
                facts.put("ownerSubject", SUBJECT);
                facts.put("intentDigest", codec.digest("maintenance-intent", body));
                facts.put("previousState", body.path("expectedState").textValue());
                facts.put("tenantId", "7");
                facts.put(
                    "originDigest",
                    codec.digest("maintenance-origin", body.path("target").path("origin")));
                facts.put("result", "UPDATED");
                String encoded =
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(codec.canonical(facts).getBytes(StandardCharsets.UTF_8));
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
                  return withSuccess(
                          codec.canonical(
                              Map.of(
                                  "operationId",
                                  operation,
                                  "accountId",
                                  ACCOUNT,
                                  "result",
                                  "UPDATED",
                                  "receipt",
                                  receipt)),
                          MediaType.APPLICATION_JSON)
                      .createResponse(out);
                } catch (Exception e) {
                  throw new IllegalStateException("Synthetic receipt unavailable");
                }
              });
    }

    public void close() {
      jdbc.execute("shutdown");
    }
  }
}
