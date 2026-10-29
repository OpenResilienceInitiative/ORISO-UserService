package de.caritas.cob.userservice.api.service.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SelfContactEmailJpaTest {
  private static final String ASKER = "82000000-0000-4000-8000-000000000011";
  private static final String CONSULTANT = "82000000-0000-4000-8000-000000000012";
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private javax.sql.DataSource dataSource;
  @Autowired private jakarta.persistence.EntityManager entities;
  private org.springframework.jdbc.core.JdbcTemplate jdbc;
  private org.springframework.test.web.client.MockRestServiceServer server;
  private final java.util.concurrent.atomic.AtomicInteger calls =
      new java.util.concurrent.atomic.AtomicInteger();
  private final java.util.Map<String, String> nativeReplies = new java.util.HashMap<>();
  private final java.util.Map<String, String> nativeBodies = new java.util.HashMap<>();
  private String scenario = "updated";
  private UserAccountService currentService;
  private AppointmentService appointments;
  private SupervisorAddedEmailNotificationService notifications;
  private IdentityEmailAddressUpdater genericEmail;
  private IdentityClientConfig mode;
  private UserHelper helper;
  private org.springframework.mock.web.MockHttpServletRequest currentRequest;

  @BeforeEach
  void actualOwningJpaAndClosedGenericIdentity() {
    assertInstanceOf(JpaTransactionManager.class, transactions);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    for (var migration :
        java.util.Map.of(
                "TEMPORARY_ACCOUNT", "20260923_user_temporary_account",
                "AVATAR_ID", "20261007_user_avatar_1240")
            .entrySet()) {
      if (jdbc.queryForObject(
              "select count(*) from information_schema.columns where table_name='USER' and column_name=?",
              Integer.class,
              migration.getKey())
          == 0)
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource(
                    "db/changelog/changeset/" + migration.getValue() + "/migrate.sql"))
            .execute(dataSource);
    }
    jdbc.execute("drop table if exists identity_account_operation_intent");
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                "db/changelog/changeset/20261021_identity_self_locale_367/migrate.sql"),
            new org.springframework.core.io.ClassPathResource(
                "db/changelog/changeset/20261023_existing_import_intent_367/migrate.sql"))
        .execute(dataSource);
    TenantContext.clear();
    TenantContext.setCurrentTenant(7L);
    for (String id : List.of(ASKER, CONSULTANT)) {
      users.findById(id).ifPresent(users::delete);
      consultants.findById(id).ifPresent(consultants::delete);
    }
    mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    when(mode.getEmailDummySuffix()).thenReturn("@dummy.invalid");
    genericEmail = mock(IdentityEmailAddressUpdater.class);
    doThrow(new ScopedIdentityCommandRequiredException())
        .when(genericEmail)
        .updateCurrentUserEmail(anyString());
    doThrow(new ScopedIdentityCommandRequiredException())
        .when(genericEmail)
        .deleteCurrentUserEmail();
    appointments = mock(AppointmentService.class);
    notifications = mock(SupervisorAddedEmailNotificationService.class);
    doAnswer(
            i -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return null;
            })
        .when(appointments)
        .updateAskerEmail(anyString(), anyString());
    doAnswer(
            i -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return null;
            })
        .when(notifications)
        .notifyEmailAddressChanged(anyString(), anyString(), any(), any(), anyString(), any());
    helper = mock(UserHelper.class);
    when(helper.getDummyEmail(CONSULTANT)).thenReturn(CONSULTANT + "@dummy.invalid");
  }

  @AfterEach
  void releaseTenant() {
    if (server != null) {
      server.verify();
      assertTrue(calls.get() >= 2);
    }
    TenantContext.clear();
  }

  @Test
  void anAskerCanChangeContactEmailThroughTheActualPublicService() {
    var user = new User(ASKER, null, encodedUsername(), "old@oriso.org", false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setEncourage2fa(false);
    user.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);
    users.save(user);
    var service = service(ASKER, "user");

    assertDoesNotThrow(
        () -> service.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));

    assertEquals("changed@oriso.org", users.findById(ASKER).orElseThrow().getEmail());
    verifyNoInteractions(genericEmail);
    verify(appointments).updateAskerEmail(ASKER, "changed@oriso.org");
    verify(notifications)
        .notifyEmailAddressChanged(
            anyString(), eq("changed@oriso.org"), eq(7L), any(), anyString(), eq(LanguageCode.en));
  }

  @Test
  void aConsultantCanRemoveContactEmailWithoutRemovingTheAccount() {
    consultants.save(
        Consultant.builder()
            .id(CONSULTANT)
            .tenantId(7L)
            .username(encodedUsername())
            .firstName("Contact")
            .lastName("Consultant")
            .email("consultant@oriso.org")
            .languageCode(LanguageCode.en)
            .encourage2fa(false)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .createDate(LocalDateTime.now(ZoneOffset.UTC).minusYears(1))
            .status(ConsultantStatus.CREATED)
            .deletionLifecycleState(DeletionLifecycleState.ACTIVE)
            .build());
    var service = service(CONSULTANT, "consultant");

    assertDoesNotThrow(() -> service.changeUserAccountEmailAddress(Optional.empty()));

    var saved = consultants.findById(CONSULTANT).orElseThrow();
    assertEquals(CONSULTANT + "@dummy.invalid", saved.getEmail());
    assertNull(saved.getDeleteDate());
    assertEquals(DeletionLifecycleState.ACTIVE, saved.getDeletionLifecycleState());
    verifyNoInteractions(genericEmail, appointments, notifications);
  }

  private UserAccountService service(String id, String role) {
    var actor =
        new AuthenticatedUser(id, encodedUsername(), Set.of(role), "fixture-human", Set.of());
    actor.setTenantId(7L);
    var userService =
        new UserService(
            users,
            mock(UserMobileTokenRepository.class),
            mock(AuditingHandler.class),
            mock(AccountInactivityEnrollmentService.class));
    var service =
        new UserAccountService(
            userService,
            new ConsultantService(consultants, mock(ConsultantMobileTokenRepository.class)),
            appointments,
            actor,
            mock(IdentityClient.class),
            genericEmail,
            mock(IdentityDeactivator.class),
            helper,
            mode,
            mock(StatisticsService.class),
            notifications,
            new DeletionLifecycleService());
    configureActualOwner(service, actor, id);
    currentService = service;
    return service;
  }

  @Test
  void exactRetryRechecksNativeSuccessWithoutRepeatingTheLocalOrExternalCallback() {
    anAskerCanChangeContactEmailThroughTheActualPublicService();
    var before = jdbc.queryForMap("select * from user where user_id=?", ASKER);
    assertDoesNotThrow(
        () -> currentService.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));
    assertEquals(before, jdbc.queryForMap("select * from user where user_id=?", ASKER));
    verify(appointments, times(1)).updateAskerEmail(ASKER, "changed@oriso.org");
    verify(notifications, times(1))
        .notifyEmailAddressChanged(
            anyString(), eq("changed@oriso.org"), eq(7L), any(), anyString(), eq(LanguageCode.en));
    assertEquals(1, nativeReplies.size());
  }

  @Test
  void anUnsignedNativeSuccessCannotChangeSqlAppointmentsOrNotifications() {
    scenario = "unsigned";
    seedAsker();
    var service = service(ASKER, "user");
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> service.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));
    assertEquals("old@oriso.org", users.findById(ASKER).orElseThrow().getEmail());
    assertEquals(
        "FROZEN",
        jdbc.queryForObject("select status from identity_account_operation_intent", String.class));
    verifyNoInteractions(genericEmail, appointments, notifications);
  }

  @Test
  void aLostPositiveReplyRetainsTheExactOriginalBodyAndCompletesOnlyOnce() {
    scenario = "lost";
    seedAsker();
    var service = service(ASKER, "user");
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> service.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));
    assertEquals("old@oriso.org", users.findById(ASKER).orElseThrow().getEmail());
    verifyNoInteractions(appointments, notifications);
    assertDoesNotThrow(
        () -> service.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));
    assertEquals("changed@oriso.org", users.findById(ASKER).orElseThrow().getEmail());
    assertEquals(1, nativeReplies.size());
    verify(appointments, times(1)).updateAskerEmail(ASKER, "changed@oriso.org");
    verify(notifications, times(1))
        .notifyEmailAddressChanged(
            anyString(), eq("changed@oriso.org"), eq(7L), any(), anyString(), eq(LanguageCode.en));
  }

  @Test
  void aChangedCompleteSourceStopsAnOldAttemptBeforeAnotherNativeRequest() {
    anAskerCanChangeContactEmailThroughTheActualPublicService();
    int previous = calls.get();
    jdbc.update(
        "update user set avatar_id=? where user_id=?",
        "82000000-0000-4000-8000-000000000099",
        ASKER);
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,
        () -> currentService.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));
    assertEquals(previous, calls.get());
    verify(appointments, times(1)).updateAskerEmail(ASKER, "changed@oriso.org");
  }

  @Test
  void aChangedEmailCannotReuseTheOldKeyOrDispatchAnotherNativeMutation() {
    anAskerCanChangeContactEmailThroughTheActualPublicService();
    int previous = calls.get();
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,
        () -> currentService.changeUserAccountEmailAddress(Optional.of("other@oriso.org")));
    assertEquals(previous, calls.get());
    assertEquals("changed@oriso.org", users.findById(ASKER).orElseThrow().getEmail());
  }

  @Test
  void aRawJournalPhaseChangeCannotReuseAnAuthenticatedSuccess() {
    anAskerCanChangeContactEmailThroughTheActualPublicService();
    int previous = calls.get();
    var before = jdbc.queryForMap("select * from user where user_id=?", ASKER);
    assertEquals(
        1,
        jdbc.update(
            "update identity_account_operation_intent set status='FROZEN',domain_applied=false"));
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> currentService.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));
    assertEquals(previous, calls.get());
    assertEquals(before, jdbc.queryForMap("select * from user where user_id=?", ASKER));
    verify(appointments, times(1)).updateAskerEmail(ASKER, "changed@oriso.org");
  }

  @Test
  void aForeignRequestPrincipalCannotReuseTheOriginalHumansAttempt() {
    anAskerCanChangeContactEmailThroughTheActualPublicService();
    int previous = calls.get();
    var jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture-human")
            .header("alg", "RS256")
            .subject(CONSULTANT)
            .issuer("https://identity.test/realms/fixture")
            .claim("azp", "app")
            .claim("tenantId", "7")
            .issuedAt(java.time.Instant.now().minusSeconds(10))
            .expiresAt(java.time.Instant.now().plusSeconds(600))
            .build();
    currentRequest.setUserPrincipal(
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtAuthenticationToken(jwt));
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> currentService.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));
    assertEquals(previous, calls.get());
    assertEquals("changed@oriso.org", users.findById(ASKER).orElseThrow().getEmail());
    verify(appointments, times(1)).updateAskerEmail(ASKER, "changed@oriso.org");
  }

  private void seedAsker() {
    var user = new User(ASKER, null, encodedUsername(), "old@oriso.org", false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setEncourage2fa(false);
    user.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);
    users.save(user);
  }

  @Test
  void aSignedNativeRefusalIsDefiniteAndNeverRunsAnyLocalOrExternalCallback() {
    scenario = "signed-403";
    seedAsker();
    var service = service(ASKER, "user");
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> service.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));
    assertEquals("old@oriso.org", users.findById(ASKER).orElseThrow().getEmail());
    assertEquals(
        "REFUSED",
        jdbc.queryForObject("select status from identity_account_operation_intent", String.class));
    int previous = calls.get();
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> service.changeUserAccountEmailAddress(Optional.of("changed@oriso.org")));
    assertEquals(previous, calls.get());
    verifyNoInteractions(genericEmail, appointments, notifications);
  }

  private void configureActualOwner(
      UserAccountService service, AuthenticatedUser actor, String id) {
    var request =
        new org.springframework.mock.web.MockHttpServletRequest(
            id.equals(ASKER) ? "PUT" : "DELETE", "/service/users/email");
    currentRequest = request;
    var jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture-human")
            .header("alg", "RS256")
            .subject(id)
            .issuer("https://identity.test/realms/fixture")
            .claim("azp", "app")
            .claim("tenantId", "7")
            .issuedAt(java.time.Instant.now().minusSeconds(10))
            .expiresAt(java.time.Instant.now().plusSeconds(600))
            .build();
    request.setUserPrincipal(
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtAuthenticationToken(
            jwt,
            java.util.List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_USER"))));
    request.addHeader("Authorization", "Bearer fixture-human");
    request.addHeader("Idempotency-Key", java.util.UUID.randomUUID().toString());
    var actors = mock(de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration.class);
    when(actors.require(de.caritas.cob.userservice.api.config.auth.TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new de.caritas.cob.userservice.api.config.auth.TaskActorCredentials(
                de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN,
                "backend-account-admin",
                "fixture-secret",
                "33333333-3333-4333-8333-333333333333"));
    var broker = mock(de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker.class);
    var machine =
        mock(
            de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker.VerifiedMaintenanceToken
                .class);
    when(machine.accessToken()).thenReturn("fixture-admin");
    when(machine.actor())
        .thenReturn(de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN);
    when(machine.purpose())
        .thenReturn(de.caritas.cob.userservice.api.config.auth.TaskPurpose.ACCOUNT_MAINTENANCE);
    when(machine.clientId()).thenReturn("backend-account-admin");
    when(machine.subject()).thenReturn("33333333-3333-4333-8333-333333333333");
    when(machine.issuer()).thenReturn("https://identity.test/realms/fixture");
    when(machine.validatedJwtIssuedAt())
        .thenReturn(java.time.Instant.now().minusSeconds(1).getEpochSecond());
    when(machine.validatedJwtExpiresAt())
        .thenReturn(java.time.Instant.now().plusSeconds(600).getEpochSecond());
    when(broker.issueCheckedMaintenance(
            de.caritas.cob.userservice.api.config.auth.TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(machine);
    var http = new org.springframework.web.client.RestTemplate();
    server = org.springframework.test.web.client.MockRestServiceServer.bindTo(http).build();
    var codec =
        new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec(
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    server
        .expect(
            org.springframework.test.web.client.ExpectedCount.manyTimes(),
            org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              calls.incrementAndGet();
              assertEquals("Bearer fixture-admin", outbound.getHeaders().getFirst("Authorization"));
              assertEquals(
                  "Bearer fixture-human",
                  outbound.getHeaders().getFirst("X-ORISO-Human-Authorization"));
              var body =
                  codec.read(
                      ((org.springframework.mock.http.client.MockClientHttpRequest) outbound)
                          .getBodyAsString(),
                      com.fasterxml.jackson.databind.JsonNode.class);
              String reply;
              if (outbound.getURI().getPath().endsWith("/read")) {
                var projection = new java.util.TreeMap<String, Object>();
                projection.put("id", id);
                projection.put("username", encodedUsername());
                projection.put("email", "old@oriso.org");
                projection.put("firstName", null);
                projection.put("lastName", null);
                projection.put("enabled", true);
                projection.put("emailVerified", false);
                projection.put("tenantId", "7");
                projection.put("preferredLanguage", "en");
                projection.put("effectiveRoles", java.util.List.of("user"));
                projection.put("passwordChangeRequired", false);
                projection.put("state", "a".repeat(64));
                reply = codec.canonical(projection);
              } else {
                assertTrue(outbound.getURI().getPath().endsWith("/email"));
                assertEquals(
                    java.util.Set.of("email", "preserveEmailVerified"),
                    new java.util.HashSet<>(
                        java.util.stream.StreamSupport.stream(
                                java.util.Spliterators.spliteratorUnknownSize(
                                    body.path("change").fieldNames(), 0),
                                false)
                            .toList()));
                assertTrue(body.path("change").path("preserveEmailVerified").booleanValue());
                var claims = new java.util.TreeMap<String, Object>();
                claims.put("realmId", "22222222-2222-4222-8222-222222222222");
                claims.put("accountId", id);
                claims.put("operationId", body.path("operationId").asText());
                claims.put("command", "email");
                claims.put("ownerClient", "backend-account-admin");
                claims.put("ownerSubject", "33333333-3333-4333-8333-333333333333");
                claims.put("intentDigest", codec.digest("maintenance-intent", body));
                claims.put("previousState", body.path("expectedState").asText());
                claims.put("tenantId", "7");
                claims.put(
                    "originDigest",
                    codec.digest("maintenance-origin", body.path("target").path("origin")));
                claims.put("result", scenario.equals("signed-403") ? "NO_EFFECT" : "UPDATED");
                if (scenario.equals("signed-403")) claims.put("status", 403);
                String encoded =
                    java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                            codec
                                .canonical(claims)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try {
                  var mac = javax.crypto.Mac.getInstance("HmacSHA256");
                  mac.init(
                      new javax.crypto.spec.SecretKeySpec(
                          "0123456789abcdef0123456789abcdef"
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                          "HmacSHA256"));
                  String domain =
                      scenario.equals("signed-403")
                          ? "maintenance-no-effect-receipt/v1\n"
                          : "maintenance-operation-receipt\n";
                  String receipt =
                      encoded
                          + "."
                          + java.util.HexFormat.of()
                              .formatHex(
                                  mac.doFinal(
                                      (domain + encoded)
                                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                  reply =
                      codec.canonical(
                          scenario.equals("signed-403")
                              ? java.util.Map.of(
                                  "operationId",
                                  body.path("operationId").asText(),
                                  "accountId",
                                  id,
                                  "result",
                                  "NO_EFFECT",
                                  "status",
                                  403,
                                  "receipt",
                                  receipt)
                              : java.util.Map.of(
                                  "operationId",
                                  body.path("operationId").asText(),
                                  "accountId",
                                  id,
                                  "result",
                                  "UPDATED",
                                  "receipt",
                                  receipt));
                } catch (java.security.GeneralSecurityException invalid) {
                  throw new AssertionError(invalid);
                }
                String operation = body.path("operationId").asText();
                String original = nativeBodies.putIfAbsent(operation, codec.canonical(body));
                if (original != null) assertEquals(original, codec.canonical(body));
                String saved = nativeReplies.putIfAbsent(operation, reply);
                if (saved != null) reply = saved;
                if (scenario.equals("unsigned")) reply = "{}";
                if (scenario.equals("lost") && saved == null)
                  throw new org.springframework.web.client.ResourceAccessException(
                      "Simulated lost positive reply");
              }
              if (scenario.equals("signed-403") && outbound.getURI().getPath().endsWith("/email"))
                return org.springframework.test.web.client.response.MockRestResponseCreators
                    .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(reply)
                    .createResponse(outbound);
              return org.springframework.test.web.client.response.MockRestResponseCreators
                  .withSuccess(reply, org.springframework.http.MediaType.APPLICATION_JSON)
                  .createResponse(outbound);
            });
    var owner =
        new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfContactEmailMaintenance(
            mode,
            actor,
            request,
            jdbc,
            transactions,
            entities,
            actors,
            broker,
            codec,
            http,
            java.time.Clock.systemUTC(),
            "https://identity.test",
            "https://identity.test/realms/fixture",
            "fixture",
            "22222222-2222-4222-8222-222222222222");
    service.configureSelfContactEmailMaintenance(
        new org.springframework.beans.factory.support.StaticListableBeanFactory(
                java.util.Map.of("contact", owner))
            .getBeanProvider(
                de.caritas.cob.userservice.api.adapters.keycloak.lifecycle
                    .SelfContactEmailMaintenance.class));
  }

  private String encodedUsername() {
    return new UsernameTranscoder().encodeUsername("contact-email");
  }
}
