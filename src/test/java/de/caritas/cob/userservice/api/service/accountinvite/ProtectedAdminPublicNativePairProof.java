package de.caritas.cob.userservice.api.service.accountinvite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.*;
import de.caritas.cob.userservice.api.adapters.web.controller.TenantAdminOnboardingController;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiDefaultResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.*;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.*;
import de.caritas.cob.userservice.api.service.accountinvite.mail.*;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.*;
import de.caritas.cob.userservice.api.service.accountpolicy.CheckedPlatformInactivityPolicyReads;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.*;
import de.caritas.cob.userservice.api.workflow.accountinactivity.*;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.*;

/**
 * Hosted public-service/JPA/native flow. Invitation SMTP and policy reads are explicit boundaries.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AccountInviteService.class,
  InviteTargetResolver.class,
  ReservationLedger.class,
  UnitQueue.class,
  InviteDelivery.class,
  AccountInviteTopicPermissionService.class,
  AccountInviteAccessPolicy.class,
  AdminScope.class,
  IdReservationReleaseProcessor.class,
  ProtectedInvitationIssuanceConfiguration.class,
  TenantAdminOnboardingController.class,
  ProtectedAdminRegistrationBodyAdvice.class,
  ProtectedAdminPublicNativePairProof.Inputs.class
})
@AsTechnicalUser
class ProtectedAdminPublicNativePairProof {
  static final String LOGICAL = "http://protected-fixture.invalid";
  static final List<Map<String, Object>> HTTP = new ArrayList<>();
  static final AtomicInteger APPROVALS = new AtomicInteger();
  @Autowired AccountInviteService invitations;
  @Autowired ProtectedAdminOnboardingService onboarding;
  @Autowired AccountInviteRepository invites;
  @Autowired InviteEmailTemplateRepository templates;
  @Autowired InviteEmailDeliveryRepository deliveries;
  @Autowired AdminRepository admins;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource source;
  @Autowired PlatformTransactionManager transactions;
  @Autowired AccountLifecycleCodec json;
  @Autowired TenantAdminOnboardingController controller;
  @Autowired ProtectedAdminRegistrationBodyAdvice bodyAdvice;
  @Autowired AuthenticatedUser human;
  @Autowired JwtDecoder decoder;
  @MockitoBean ExistingAccountSetupIssuer existingAccountSetupIssuer;
  @MockitoBean TenantAdminOnboardingService legacyOnboarding;
  @MockitoBean CounsellorOnboardingService legacyCounsellor;
  @MockitoBean de.caritas.cob.userservice.api.service.agency.AgencyService agencyService;
  @MockitoBean IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean TenantService tenantService;
  @MockitoBean TenantIdAllocationClient tenantIds;
  @MockitoBean AgencyIdAllocationClient agencyIds;
  @MockitoBean AgencyFacts agencyFacts;
  @MockitoBean InviteMailDispatchService sender;
  @MockitoBean InviteEmailDeliveryFailureRecorder failureRecorder;
  @MockitoBean OperatorDpaContentClient dpa;
  @MockitoBean TenantCreationClient tenantCreation;
  @MockitoBean IdentityAuthentication identityAuthentication;
  @MockitoBean TenantContextProvider tenantContextProvider;
  @MockitoBean ScheduledTaskClaimService leases;
  @MockitoBean CheckedPlatformInactivityPolicyReads platformPolicy;

  static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing isolated public pair input");
    return value;
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("TASK_IDENTITY_MODEL", () -> "FIVE_ACTORS");
    p.add("ORISO_ACCOUNT_LIFECYCLE_ENABLED", () -> "true");
    p.add("KEYCLOAK_REALM_INTERNAL_ID", () -> required("PAIR_REALM_ID"));
    p.add("ORISO_ACCOUNT_LIFECYCLE_REALM", () -> required("PAIR_REALM"));
    p.add("ORISO_TASK_CONTEXT_KEY", () -> required("PAIR_CONTEXT_KEY_B64"));
    p.add(
        "ORISO_PROTECTED_INVITATION_PROFILE_IDS",
        () -> "{\"TENANT_ADMIN\":\"tenant-v1\",\"PLATFORM_ADMIN\":\"platform-v1\"}");
    for (TaskActor actor : TaskActor.values()) {
      String target = "IDENTITY_" + actor.name(), input = "PAIR_" + actor.name();
      p.add(target + "_CLIENT_ID", () -> required(input + "_CLIENT"));
      p.add(target + "_CLIENT_SECRET", () -> required(input + "_SECRET"));
      p.add(target + "_SERVICE_SUBJECT", () -> required(input + "_SUBJECT"));
    }
  }

  @Test
  void realPublicPlatformInvitationStagesJpaAndCompletesNativeAppBeforeAcknowledgingActivation()
      throws Exception {
    assertEquals("true", required("GITHUB_ACTIONS"));
    assertEquals("Linux", System.getProperty("os.name"));
    assertInstanceOf(JpaTransactionManager.class, transactions);
    assertSame(source, ((JpaTransactionManager) transactions).getDataSource());
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    for (String name :
        List.of(
            "20261022_protected_invitation_367",
            "20261022_protected_invitation_mail_367",
            "20261022_protected_invitation_cancel_367",
            "20261022_protected_invitation_acceptance_367",
            "20261022_protected_account_creation_367",
            "20261022_protected_account_activation_367",
            "20261022_protected_account_cleanup_367",
            "20261022_protected_invitation_cleanup_status_367",
            "20261022_protected_otp_bootstrap_367"))
      new ResourceDatabasePopulator(
              new ClassPathResource("db/changelog/changeset/" + name + "/migrate.sql"))
          .execute(source);
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261022_protected_otp_bootstrap_367/operations.sql"))
        .execute(source);
    // Execute the real table DDL only: unrelated historical identity backfill is outside this
    // fixture.
    jdbc.execute(
        Files.readString(
                Path.of(
                    "src/main/resources/db/changelog/changeset/20260916_account_inactivity/migrate.sql"))
            .split(";")[0]);
    String recipient = required("PAIR_RECIPIENT");
    when(identityEmailOwnerLookup.findByEmail(recipient)).thenReturn(Optional.empty());
    when(platformPolicy.capture())
        .thenReturn(
            new AccountInactivityEnrollmentService.Policy(
                12, 4, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
    List<String> sentLinks = new ArrayList<>();
    when(sender.send(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(
                  "SENDING",
                  jdbc.queryForObject(
                      "SELECT state FROM protected_invitation_mail_outbox", String.class));
              String link = call.getArgument(3);
              String usableToken = link.substring(link.lastIndexOf('/') + 1);
              String encrypted =
                  jdbc.queryForObject(
                      "SELECT sealed_mail FROM protected_invitation_mail_outbox", String.class);
              assertNotNull(encrypted);
              assertFalse(encrypted.contains(usableToken));
              assertFalse(encrypted.contains(link));
              assertEquals(
                  AccountInviteService.hash(usableToken),
                  jdbc.queryForObject("SELECT token_hash FROM account_invite", String.class));
              sentLinks.add(link);
              return new InviteMailSendReceipt(call.getArgument(0), Instant.now());
            });
    Tenants.actAs(
        human, required("PAIR_HUMAN_SUBJECT"), 0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN);
    human.setAccessToken(required("PAIR_HUMAN_TOKEN"));
    var template =
        templates.saveAndFlush(
            InviteEmailTemplate.builder()
                .kind(InviteEmailTemplateKind.TENANT_INVITE)
                .name("Public native welcome")
                .subject("Welcome")
                .body("Accept {{inviteLink}}")
                .language("de")
                .active(true)
                .createDate(LocalDateTime.now())
                .build());
    var sent =
        invitations.createAndSendInvite(
            new CreateAccountInviteCommand(
                AccountInviteTargetRole.PLATFORM_ADMIN,
                null,
                recipient,
                "Public",
                "Platform",
                null,
                null,
                1L),
            template.getId());
    assertEquals(AccountInviteStatus.EMAIL_SENT, sent.invite().getStatus());
    assertEquals(0L, sent.invite().getTenantId());
    assertEquals(1, APPROVALS.get());
    assertEquals(1, sentLinks.size());
    String link = sentLinks.getFirst();
    assertTrue(link.startsWith("https://admin.example.test/admin/tenant-onboarding/"));
    String token = link.substring(link.lastIndexOf('/') + 1);
    assertFalse(token.isBlank());
    assertNull(sent.rawToken());
    assertNull(sent.acceptUrl());
    assertEquals(
        AccountInviteService.hash(token),
        invites.findById(sent.invite().getId()).orElseThrow().getTokenHash());
    for (var audit : deliveries.findAll()) assertFalse(audit.getBodySnapshot().contains(token));
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                bodyAdvice,
                new ApiResponseEntityExceptionHandler(),
                new ApiDefaultResponseEntityExceptionHandler())
            .build();
    String raw = "{\"account\":{\"password\":\"SyntheticPublicOnly!731\"}}";
    String key = UUID.randomUUID().toString();
    String registrationPath = "/service/users/account-invites/" + token + "/onboarding/register";
    var reply =
        mvc.perform(
                post(registrationPath)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(raw))
            .andReturn()
            .getResponse();
    assertEquals(200, reply.getStatus());
    var registered = json.read(reply.getContentAsString(), JsonNode.class);
    assertEquals("PENDING_2FA_ACTIVATION", registered.path("phase").asText());
    String secret = registered.path("twoFactor").path("secret").asText();
    assertFalse(secret.isBlank());
    String account = invites.findById(sent.invite().getId()).orElseThrow().getAcceptedByUserId();
    assertNotNull(account);
    assertEquals(0L, admins.findById(account).orElseThrow().getTenantId());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM account_inactivity WHERE identity_id=?", Integer.class, account));
    assertEquals(
        "WAITING_OTP",
        jdbc.queryForObject("SELECT state FROM protected_account_activation", String.class));
    assertNull(invites.findById(sent.invite().getId()).orElseThrow().getTotpPendingSecret());
    assertFalse(
        jdbc.queryForObject(
                "SELECT prepared_checkpoint FROM protected_account_creation", String.class)
            .contains("SyntheticPublicOnly!731"));
    assertFalse(
        jdbc.queryForObject(
                "SELECT request_json FROM protected_invitation_authorization", String.class)
            .contains(human.getAccessToken()));
    var retry =
        mvc.perform(
                post(registrationPath)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(raw))
            .andReturn()
            .getResponse();
    assertEquals(200, retry.getStatus());
    assertEquals(
        secret,
        json.read(retry.getContentAsString(), JsonNode.class)
            .path("twoFactor")
            .path("secret")
            .asText());
    String appPath = "/service/users/account-invites/" + token + "/onboarding/two-factor";
    var activated =
        mvc.perform(
                post(appPath)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json")
                    .content(json.canonical(Map.of("otp", totp(secret)))))
            .andReturn()
            .getResponse();
    assertEquals(200, activated.getStatus());
    assertEquals(
        "COMMITTED",
        jdbc.queryForObject("SELECT state FROM protected_account_activation", String.class));
    var resolved =
        mvc.perform(get("/service/users/account-invites/" + token + "/onboarding"))
            .andReturn()
            .getResponse();
    assertEquals(200, resolved.getStatus());
    assertEquals(
        "COMPLETED",
        json.read(resolved.getContentAsString(), JsonNode.class).path("phase").asText());
    assertEquals(1, admins.count());
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM protected_account_creation", Integer.class));
    var loginBody = new org.springframework.util.LinkedMultiValueMap<String, String>();
    loginBody.add("client_id", required("PAIR_HUMAN_CLIENT"));
    loginBody.add("grant_type", "password");
    loginBody.add("scope", "openid");
    loginBody.add("username", recipient);
    loginBody.add("password", "SyntheticPublicOnly!731");
    loginBody.add("totp", totp(secret));
    var headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
    String oidc =
        required("PAIR_NATIVE_BASE")
            + "/realms/"
            + required("PAIR_REALM")
            + "/protocol/openid-connect";
    var login =
        new RestTemplate()
            .postForEntity(
                oidc + "/token",
                new org.springframework.http.HttpEntity<>(loginBody, headers),
                String.class);
    assertEquals(200, login.getStatusCode().value());
    var tokenReply = json.read(login.getBody(), JsonNode.class);
    var recipientJwt = decoder.decode(tokenReply.path("access_token").asText());
    assertEquals(account, recipientJwt.getSubject());
    assertEquals("0", recipientJwt.getClaimAsString("tenantId"));
    assertEquals(required("PAIR_HUMAN_CLIENT"), recipientJwt.getClaimAsString("azp"));
    assertEquals(Set.of("userservice"), Set.copyOf(recipientJwt.getAudience()));
    assertFalse(recipientJwt.getClaimAsString("sid").isBlank());
    var infoHeaders = new org.springframework.http.HttpHeaders();
    infoHeaders.setBearerAuth(tokenReply.path("access_token").asText());
    var info =
        new RestTemplate()
            .exchange(
                oidc + "/userinfo",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(infoHeaders),
                String.class);
    assertEquals(200, info.getStatusCode().value());
    assertEquals(account, json.read(info.getBody(), JsonNode.class).path("sub").asText());
    verifyNoInteractions(
        existingAccountSetupIssuer,
        legacyOnboarding,
        legacyCounsellor,
        tenantCreation,
        tenantIds,
        agencyIds);
    Files.writeString(Path.of(required("PAIR_HTTP_WITNESS")), json.canonical(HTTP));
    Files.writeString(
        Path.of(required("PAIR_WITNESS")),
        json.canonical(
            Map.of(
                "realJpaOwner",
                true,
                "proposalBeforeHumanRpc",
                true,
                "encryptedInviteTokenBeforeDispatch",
                true,
                "productionProtectedBeans",
                true,
                "publicCoordinatorBeforeNativeCommit",
                true,
                "oneAdminAndInactivity",
                true,
                "localCommittedAfterNativeReceipt",
                true,
                "newRecipientLoginAfterOtpAndCommit",
                true)));
  }

  static String totp(String secret) throws Exception {
    var mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    byte[] digest =
        mac.doFinal(ByteBuffer.allocate(8).putLong(Instant.now().getEpochSecond() / 30).array());
    int offset = digest[digest.length - 1] & 15;
    return String.format(
        Locale.ROOT, "%06d", (ByteBuffer.wrap(digest, offset, 4).getInt() & 0x7fffffff) % 1000000);
  }

  // Lite configuration: only explicitly imported here; no test component enters production scans.
  static class Inputs {
    @Bean
    JdbcTemplate pairJdbc(DataSource ds) {
      return new JdbcTemplate(ds);
    }

    @Bean
    Clock pairClock() {
      return Clock.systemUTC();
    }

    @Bean
    AccountLifecycleCodec pairJson() {
      return new AccountLifecycleCodec(
          Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64")));
    }

    @Bean(destroyMethod = "")
    @Scope(value = "request", proxyMode = ScopedProxyMode.INTERFACES)
    HttpServletRequest pairRequest() {
      return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
          .getRequest();
    }

    @Bean
    AuthenticatedUser pairHuman() {
      return new AuthenticatedUser("uninitialized", "uninitialized", Set.of(), "", Set.of());
    }

    @Bean
    UsernameTranscoder pairUsernames() {
      return new UsernameTranscoder();
    }

    @Bean
    InviteAcceptUrlBuilder pairUrls() {
      return new InviteAcceptUrlBuilder("https://app.example.test", "https://admin.example.test");
    }

    @Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor
        pairNativeClientInputs() {
      return factory -> {
        factory.registerScope("request", new RequestScope());
        var keycloak = new KeycloakConfig();
        keycloak.setAuthServerUrl(LOGICAL);
        keycloak.setRealm(required("PAIR_REALM"));
        var identity = new IdentityConfig();
        identity.setOpenidConnectUrl(
            LOGICAL + "/realms/" + required("PAIR_REALM") + "/protocol/openid-connect");
        factory.registerSingleton("pairKeycloak", keycloak);
        factory.registerSingleton("pairIdentity", identity);
      };
    }

    @Bean
    TaskActorConfiguration pairActors(Environment e, IdentityConfig id) {
      return new TaskActorConfiguration(e, new KeycloakCustomConfig(), id);
    }

    @Bean
    JwtDecoder pairDecoder() {
      var d =
          NimbusJwtDecoder.withJwkSetUri(
                  required("PAIR_NATIVE_BASE")
                      + "/realms/"
                      + required("PAIR_REALM")
                      + "/protocol/openid-connect/certs")
              .build();
      d.setJwtValidator(
          JwtValidators.createDefaultWithIssuer(LOGICAL + "/realms/" + required("PAIR_REALM")));
      return d;
    }

    @Bean("keycloakRestTemplate")
    RestTemplate pairHttp(JdbcTemplate jdbc, AccountLifecycleCodec json) {
      var http = new RestTemplate();
      http.getInterceptors()
          .add(
              (outbound, bytes, execute) -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                URI logical = outbound.getURI();
                assertEquals("protected-fixture.invalid", logical.getHost());
                String path = logical.getPath();
                boolean approval =
                    outbound.getMethod() == org.springframework.http.HttpMethod.PUT
                        && path.matches(
                            ".*/oriso-protected-invitations/v1/approvals/[a-f0-9-]{36}");
                if (approval) {
                  assertEquals(
                      1,
                      jdbc.queryForObject(
                          "SELECT COUNT(*) FROM protected_invitation_authorization WHERE state='APPROVAL_REQUESTED'",
                          Integer.class));
                  assertEquals(
                      "DRAFT",
                      jdbc.queryForObject("SELECT status FROM account_invite", String.class));
                  assertNull(
                      jdbc.queryForObject("SELECT token_hash FROM account_invite", String.class));
                  assertEquals(
                      0,
                      jdbc.queryForObject(
                          "SELECT COUNT(*) FROM protected_invitation_mail_outbox", Integer.class));
                  APPROVALS.incrementAndGet();
                }
                URI physical =
                    URI.create(
                        required("PAIR_NATIVE_BASE")
                            + logical.getRawPath()
                            + (logical.getRawQuery() == null ? "" : "?" + logical.getRawQuery()));
                var response =
                    execute.execute(
                        new HttpRequestWrapper(outbound) {
                          @Override
                          public URI getURI() {
                            return physical;
                          }
                        },
                        bytes);
                if (HTTP.size() >= 100)
                  throw new IllegalStateException(
                      "Closed native public observation bound exceeded");
                String phase;
                if (path.endsWith("/protocol/openid-connect/token")) phase = "token";
                else if (approval) phase = "approve";
                else if (outbound.getMethod() == org.springframework.http.HttpMethod.POST
                    && path.matches(
                        ".*/oriso-protected-invitations/v1/approvals/[a-f0-9-]{36}/status"))
                  phase = "status";
                else if (path.endsWith("/owned-read")) phase = "owned-read";
                else if (path.contains("/fetch-otp-setup-info/")) phase = "prepare";
                else if (path.contains("/setup-otp/")) phase = "app";
                else if (path.endsWith("/commit")) phase = "commit";
                else if (path.matches(
                    ".*/oriso-protected-invitations/v1/approvals/[a-f0-9-]{36}/attempts/[a-f0-9-]{36}"))
                  phase = "create";
                else throw new IllegalStateException("Unexpected fixed native public route");
                HTTP.add(Map.of("phase", phase, "status", response.getStatusCode().value()));
                Files.writeString(Path.of(required("PAIR_HTTP_WITNESS")), json.canonical(HTTP));
                return response;
              });
      return http;
    }

    @Bean
    TaskActorTokenBroker pairBroker(
        TaskActorConfiguration a,
        IdentityConfig id,
        JwtDecoder d,
        KeycloakConfig c,
        AuthenticatedUser h,
        @org.springframework.beans.factory.annotation.Qualifier("keycloakRestTemplate")
            RestTemplate http) {
      return new TaskActorTokenBroker(a, new KeycloakAuthClient(http, h, id), d, c);
    }

    @Bean
    AccountInactivityService pairInactivity(
        JdbcTemplate jdbc, PlatformTransactionManager tx, Clock clock) {
      return new AccountInactivityService(jdbc, tx, clock, mock(AccountInactivityEffects.class));
    }

    @Bean
    AccountInactivityEnrollmentService pairEnrollment(
        AccountInactivityService lifecycle, Clock clock) {
      return new AccountInactivityEnrollmentService(
          mock(TenantServiceApiControllerFactory.class),
          lifecycle,
          clock,
          mock(SecurityHeaderSupplier.class));
    }
  }
}
