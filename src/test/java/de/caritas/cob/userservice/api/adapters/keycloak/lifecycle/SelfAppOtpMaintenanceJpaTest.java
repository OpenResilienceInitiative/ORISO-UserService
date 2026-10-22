package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.PatchConsultantSaga;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.config.auth.SelfAppSignedIssuer;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** Actual JPA invite/User owner and JDBC sidecar; native pairing is a separate hosted gate. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SelfAppOtpMaintenanceJpaTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM = "22222222-2222-4222-8222-222222222222";
  private static final String ADMIN = "33333333-3333-4333-8333-333333333333";
  private static final String CODE = "123456";
  private static final String SECRET = "JBSWY3DPEHPK3PXP";
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  @Autowired private DataSource source;
  @Autowired private UserRepository users;
  @Autowired private AccountInviteRepository invites;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entityManager;
  private JdbcTemplate jdbc;
  private SelfAppSignedIssuer issuer;
  private RestTemplate http;
  private MockRestServiceServer server;
  private AuthenticatedUser human;
  private MockHttpServletRequest request;
  private IdentityClientConfig mode;
  private AccountInviteService inviteService;
  private String operation = UUID.randomUUID().toString();
  private String emailStart;
  private static final String PENDING_EMAIL = "pending@fixture.invalid";
  private static final String CREDENTIAL = "55555555-5555-4555-8555-555555555555";
  private final AtomicInteger activations = new AtomicInteger();
  private final AtomicInteger nativeCalls = new AtomicInteger();
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
  private Long inviteId;

  @BeforeEach
  void actualJpaAndJdbcShareTheOwningTransaction() throws Exception {
    assertInstanceOf(JpaTransactionManager.class, transactions);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    issuer = new SelfAppSignedIssuer(ADMIN);
    jdbc = new JdbcTemplate(source);
    jdbc.execute("drop table if exists identity_self_otp_pending_email");
    jdbc.execute("drop table if exists identity_self_app_otp_intent");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261022_identity_self_app_otp_367/migrate.sql"))
        .execute(source);
    new ResourceDatabasePopulator(new ClassPathResource(
        "db/changelog/changeset/20261022_identity_self_otp_email_367/migrate.sql")).execute(source);
    users.findById(ACCOUNT).ifPresent(users::delete);
    var user = new User(ACCOUNT, null, "ordinary", "ordinary@fixture.invalid", false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setEncourage2fa(false);
    users.save(user);
    invites.deleteAll(
        invites.findAllByAcceptedByUserIdAndTwoFactorStatus(
            ACCOUNT, TwoFactorGateStatus.PENDING_SETUP));
    invites.deleteAll(
        invites.findAllByAcceptedByUserIdAndTwoFactorStatus(ACCOUNT, TwoFactorGateStatus.ACTIVE));
    var invite =
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.ADVICE_SEEKER)
            .tenantId(7L)
            .recipientEmail("ordinary@fixture.invalid")
            .acceptedByUserId(ACCOUNT)
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .createDate(LocalDateTime.now())
            .build();
    inviteId = invites.save(invite).getId();
    human =
        new AuthenticatedUser(
            ACCOUNT, "ordinary", Set.of("user"), "fixture-human", Set.of("ROLE_USER"));
    human.setTenantId(7L);
    request = new MockHttpServletRequest("PUT", "/service/users/2fa/app");
    var jwt =
        Jwt.withTokenValue("fixture-human")
            .header("alg", "RS256")
            .subject(ACCOUNT)
            .issuer(issuer.issuer())
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
    mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    inviteService =
        new AccountInviteService(
            invites,
            mock(InviteEmailTemplateRepository.class),
            human,
            mock(IdentityEmailOwnerLookup.class),
            transactions,
            mock(AccountInviteAccessPolicy.class),
            mock(AgencyFacts.class),
            mock(InviteTargetResolver.class),
            mock(ReservationLedger.class),
            mock(UnitQueue.class),
            mock(InviteDelivery.class),
            mock(ExistingAccountSetupIssuer.class));
  }

  @AfterEach
  void closeOwnedIssuer() {
    if (issuer != null) issuer.close();
  }

  @Test
  void actualJpaInviteAndReceiptCommitTogetherOnlyAfterVerifiedNativeOutcome() {
    expectNative(false, false);
    assertTrue(owner(transactions).setup(CODE, SECRET, this::activate));
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(TwoFactorGateStatus.ACTIVE, invite().getTwoFactorStatus());
    assertNotNull(invite().getTwoFactorActivatedAt());
    assertEquals(1, activations.get());
    assertEquals(1, nativeCalls.get());
    server.verify();
  }

  @Test
  void actualJpaActivationFlushFailureRollsBackInviteAndReceiptThenExactOwnerRetryRecovers() {
    expectNative(false, true);
    assertThrows(
        RuntimeException.class,
        () ->
            owner(transactions)
                .setup(
                    CODE,
                    SECRET,
                    () -> {
                      activate();
                      entityManager.flush();
                      assertEquals(
                          "ACTIVE",
                          jdbc.queryForObject(
                              "select two_factor_status from account_invite where id=?",
                              String.class,
                              inviteId));
                      throw new IllegalStateException("Fixture failure after actual invite flush");
                    }));
    assertEquals("FROZEN", status());
    assertEquals(TwoFactorGateStatus.PENDING_SETUP, invite().getTwoFactorStatus());
    assertNull(invite().getTwoFactorActivatedAt());
    assertNull(
        jdbc.queryForObject(
            "select receipt from identity_self_app_otp_intent where operation_id=?",
            String.class,
            operation));
    String frozen = envelope();
    assertTrue(owner(transactions).setup(CODE, SECRET, this::activate));
    assertTrue(frozen.equals(envelope()), "Retry must keep exact ciphertext");
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(TwoFactorGateStatus.ACTIVE, invite().getTwoFactorStatus());
    assertEquals(2, nativeCalls.get());
    server.verify();
  }

  @Test
  void actualJpaCommittedReplyLossResolvesFreshReceiptWithoutSecondDomainCallback() {
    expectNative(false, false);
    var uncertain =
        new PlatformTransactionManager() {
          private boolean failed;

          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return transactions.getTransaction(definition);
          }

          public void commit(TransactionStatus status) {
            boolean terminal =
                jdbc.queryForObject(
                        "select count(*) from identity_self_app_otp_intent where operation_id=? and status='ACKNOWLEDGED'",
                        Integer.class,
                        operation)
                    == 1;
            transactions.commit(status);
            if (terminal && !failed) {
              failed = true;
              throw new IllegalStateException("Fixture lost owning commit acknowledgement");
            }
          }

          public void rollback(TransactionStatus status) {
            transactions.rollback(status);
          }
        };
    assertTrue(owner(uncertain).setup(CODE, SECRET, this::activate));
    assertTrue(owner(transactions).setup(CODE, SECRET, this::activate));
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(TwoFactorGateStatus.ACTIVE, invite().getTwoFactorStatus());
    assertEquals(1, activations.get());
    assertEquals(1, nativeCalls.get());
    server.verify();
  }

  @Test
  void actualJpaInvalidNativeCodeCommitsTerminalRefusalAndNeverMarksInviteActive() {
    expectNative(true, false);
    assertFalse(owner(transactions).setup(CODE, SECRET, this::activate));
    assertFalse(owner(transactions).setup(CODE, SECRET, this::activate));
    assertEquals("TERMINAL_REFUSAL", status());
    assertEquals(
        401,
        jdbc.queryForObject(
            "select native_status from identity_self_app_otp_intent where operation_id=?",
            Integer.class,
            operation));
    assertEquals(TwoFactorGateStatus.PENDING_SETUP, invite().getTwoFactorStatus());
    assertEquals(0, activations.get());
    assertEquals(1, nativeCalls.get());
    server.verify();
  }

  @Test
  void actualJpaLinkedEmailPointerInviteAndReceiptCommitTogether() {
    prepareDeliveredEmail();
    expectLinkedFinish(false, false);
    assertTrue(owner(transactions).finishEmail(CODE, this::applyEmail));
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(PENDING_EMAIL, users.findById(ACCOUNT).orElseThrow().getEmail());
    assertEquals(TwoFactorGateStatus.ACTIVE, invite().getTwoFactorStatus());
    assertEquals(0, pendingCount());
    assertTrue(owner(transactions).finishEmail(CODE, this::applyEmail));
    assertEquals(1, activations.get());
    server.verify();
  }

  @Test
  void actualJpaLinkedEmailFlushRollbackKeepsPointerAndFrozenCommandForExactRecovery() {
    prepareDeliveredEmail();
    expectLinkedFinish(false, true);
    assertThrows(RuntimeException.class, () -> owner(transactions).finishEmail(CODE, email -> {
      applyEmail(email);
      entityManager.flush();
      assertEquals(PENDING_EMAIL, jdbc.queryForObject("select email from user where user_id=?", String.class, ACCOUNT));
      assertEquals("ACTIVE", jdbc.queryForObject("select two_factor_status from account_invite where id=?", String.class, inviteId));
      throw new IllegalStateException("Fixture rollback after real email and invite flush");
    }));
    assertEquals("FROZEN", status());
    assertEquals("ordinary@fixture.invalid", users.findById(ACCOUNT).orElseThrow().getEmail());
    assertEquals(TwoFactorGateStatus.PENDING_SETUP, invite().getTwoFactorStatus());
    assertEquals(1, pendingCount());
    assertNull(jdbc.queryForObject("select receipt from identity_self_app_otp_intent where operation_id=?", String.class, operation));
    String frozen = envelope();
    assertTrue(owner(transactions).finishEmail(CODE, this::applyEmail));
    assertEquals(frozen, envelope());
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(PENDING_EMAIL, users.findById(ACCOUNT).orElseThrow().getEmail());
    assertEquals(TwoFactorGateStatus.ACTIVE, invite().getTwoFactorStatus());
    assertEquals(0, pendingCount());
    server.verify();
  }

  @Test
  void actualJpaLinkedEmailCommittedReplyLossResolvesFreshAckWithoutReapplyingDomain() {
    prepareDeliveredEmail();
    expectLinkedFinish(false, false);
    var uncertain = new PlatformTransactionManager() {
      private boolean failed;
      public TransactionStatus getTransaction(TransactionDefinition definition) { return transactions.getTransaction(definition); }
      public void commit(TransactionStatus status) {
        boolean terminal = jdbc.queryForObject("select count(*) from identity_self_app_otp_intent where operation_id=? and status='ACKNOWLEDGED'", Integer.class, operation) == 1;
        transactions.commit(status);
        if (terminal && !failed) { failed = true; throw new IllegalStateException("Fixture lost owning email commit acknowledgement"); }
      }
      public void rollback(TransactionStatus status) { transactions.rollback(status); }
    };
    assertTrue(owner(uncertain).finishEmail(CODE, this::applyEmail));
    assertTrue(owner(transactions).finishEmail(CODE, this::applyEmail));
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(PENDING_EMAIL, users.findById(ACCOUNT).orElseThrow().getEmail());
    assertEquals(TwoFactorGateStatus.ACTIVE, invite().getTwoFactorStatus());
    assertEquals(0, pendingCount());
    assertEquals(1, activations.get());
    server.verify();
  }

  @Test
  void actualJpaLinkedInvalidCodeCommitsTerminalReceiptWithoutChangingEmailPointerOrInvite() {
    prepareDeliveredEmail();
    expectLinkedFinish(true, false);
    assertFalse(owner(transactions).finishEmail(CODE, this::applyEmail));
    assertFalse(owner(transactions).finishEmail(CODE, this::applyEmail));
    assertEquals("TERMINAL_REFUSAL", status());
    assertEquals(401, jdbc.queryForObject("select native_status from identity_self_app_otp_intent where operation_id=?", Integer.class, operation));
    assertEquals("ordinary@fixture.invalid", users.findById(ACCOUNT).orElseThrow().getEmail());
    assertEquals(TwoFactorGateStatus.PENDING_SETUP, invite().getTwoFactorStatus());
    assertEquals(1, pendingCount());
    assertEquals(0, activations.get());
    server.verify();
  }

  private int pendingCount() {
    return jdbc.queryForObject("select count(*) from identity_self_otp_pending_email where account_id=? and start_operation_id=?", Integer.class, ACCOUNT, emailStart);
  }

  private void applyEmail(String email) {
    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
    assertEquals(PENDING_EMAIL, email);
    var transcoder = new UsernameTranscoder();
    var accounts = new AccountManager(mock(ConsultantRepository.class), mock(AdminRepository.class), users,
        new UserServiceMapper(transcoder), transcoder, mock(AgencyService.class), mock(TenantService.class),
        mock(ConsultantAgencyRepository.class), human, mock(SessionRepository.class), mock(AppointmentService.class), mock(PatchConsultantSaga.class));
    assertTrue(accounts.patchUser(Map.of("id", ACCOUNT, "email", email)).isPresent());
    activate();
  }

  private void prepareDeliveredEmail() {
    request.setRequestURI("/service/users/2fa/email");
    expectProjection();
    var sent = new AtomicInteger();
    server.expect(ExpectedCount.twice(), requestTo(issuer.issuer()+"/otp-config/send-verification-mail/ordinary"))
        .andExpect(method(HttpMethod.PUT)).andRespond(outbound -> {
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          var body = codec.read(((MockClientHttpRequest) outbound).getBodyAsString(), ObjectNode.class);
          int status = sent.getAndIncrement() == 0 ? 202 : 200;
          return withStatus(status == 202 ? HttpStatus.ACCEPTED : HttpStatus.OK)
              .body(emailReply(body, status, true)).contentType(MediaType.APPLICATION_JSON).createResponse(outbound);
        });
    assertTrue(owner(transactions).startEmail(PENDING_EMAIL));
    server.verify(); server.reset();
    emailStart = operation;
    assertEquals(1, pendingCount());
    assertEquals("ordinary@fixture.invalid", users.findById(ACCOUNT).orElseThrow().getEmail());
    assertEquals(TwoFactorGateStatus.PENDING_SETUP, invite().getTwoFactorStatus());
    operation = UUID.randomUUID().toString();
    request.removeHeader("Idempotency-Key"); request.addHeader("Idempotency-Key", operation); request.setMethod("POST");
  }

  private void expectProjection() {
    server.expect(requestTo(issuer.issuer()+"/oriso-account-maintenance/v1/accounts/"+ACCOUNT+"/read"))
        .andExpect(method(HttpMethod.POST)).andRespond(outbound -> {
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          assertEquals("PREPARED", status());
          return withSuccess("{\"id\":\""+ACCOUNT+"\",\"username\":\"ordinary\",\"email\":null,\"firstName\":null,\"lastName\":null,\"enabled\":true,\"emailVerified\":false,\"tenantId\":\"7\",\"preferredLanguage\":\"en\",\"effectiveRoles\":[\"user\"],\"passwordChangeRequired\":false,\"state\":\""+"a".repeat(64)+"\"}", MediaType.APPLICATION_JSON).createResponse(outbound);
        });
  }

  private void expectLinkedFinish(boolean invalid, boolean twice) {
    expectProjection();
    server.expect(twice ? ExpectedCount.twice() : ExpectedCount.once(), requestTo(issuer.issuer()+"/otp-config/setup-otp-mail/ordinary"))
        .andExpect(method(HttpMethod.POST)).andRespond(outbound -> {
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          assertEquals("FROZEN", status());
          assertEquals(1, pendingCount());
          assertEquals("ordinary@fixture.invalid", users.findById(ACCOUNT).orElseThrow().getEmail());
          assertEquals(TwoFactorGateStatus.PENDING_SETUP, invite().getTwoFactorStatus());
          var body=codec.read(((MockClientHttpRequest)outbound).getBodyAsString(), ObjectNode.class);
          assertEquals(emailStart, body.path("emailStartOperationId").textValue());
          assertEquals(CODE, body.path("otp").path("initialCode").textValue());
          return withStatus(invalid ? HttpStatus.UNAUTHORIZED : HttpStatus.CREATED)
              .body(emailReply(body, invalid ? 401 : 201, false)).contentType(MediaType.APPLICATION_JSON).createResponse(outbound);
        });
  }

  private String emailReply(ObjectNode body, int status, boolean start) {
    var materialized=body.deepCopy(); materialized.put("username", "ordinary");
    var result=codec.read(start ? (status==202 ? "{\"info\":\"OTP mail pending\"}" : "{\"info\":\"OTP mail sent\"}")
        : status==401 ? "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid code\"}" : "{\"info\":\"OTP setup created\"}", JsonNode.class);
    var saved=codec.read("{}", ObjectNode.class);
    saved.put("realmId", REALM);saved.put("accountId", ACCOUNT);saved.put("operationId", operation);
    saved.put("operation", start ? "otp.email-start" : "otp.email-finish");
    saved.put("ownerClient", "backend-account-admin");saved.put("ownerSubject", ADMIN);
    saved.put("intentDigest",codec.digest("otp-intent", materialized));saved.put("previousState", "a".repeat(64));
    saved.put("tenantId","7");saved.put("originDigest",codec.digest("otp-origin",body.path("target").path("origin")));
    saved.put("status",status);saved.put("resultDigest",codec.digest("otp-result",result));
    var credential=saved.putObject("emailCredential");credential.put("credentialId",CREDENTIAL);
    credential.put("generation",(start ? "b" : "c").repeat(64));
    credential.put("destinationDigest",codec.digest("otp-email-destination",codec.read("\""+PENDING_EMAIL+"\"",JsonNode.class)));
    if(start) { saved.put("authorityValidUntil",body.path("authorityValidUntil").longValue());saved.put("deliveryState",status==202 ? "READY" : "SENT"); }
    else saved.put("emailStartOperationId",emailStart);
    String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(codec.canonical(saved).getBytes(StandardCharsets.UTF_8));
    var reply=codec.read("{}", ObjectNode.class);reply.put("operationId",operation);reply.put("accountId",ACCOUNT);reply.put("status",status);
    reply.set("result",result);reply.put("receipt",encoded+"."+HexFormat.of().formatHex(mac("otp-operation-receipt\n"+encoded)));
    if(start)reply.put("deliveryState",status==202 ? "READY" : "SENT");
    return codec.canonical(reply);
  }

  private SelfAppOtpMaintenance owner(PlatformTransactionManager manager) {
    return new SelfAppOtpMaintenance(
        mode,
        human,
        request,
        jdbc,
        manager,
        issuer.actors(),
        issuer.broker(),
        codec,
        http,
        Clock.systemUTC(),
        issuer.base(),
        "synthetic",
        REALM);
  }

  private void activate() {
    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
    inviteService.markTwoFactorActive(ACCOUNT);
    activations.incrementAndGet();
  }

  private void expectNative(boolean refusal, boolean twice) {
    server
        .expect(
            requestTo(
                issuer.issuer() + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT + "/read"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("PREPARED", status());
              assertEquals(TwoFactorGateStatus.PENDING_SETUP, invite().getTwoFactorStatus());
              return withSuccess(
                      "{\"id\":\""
                          + ACCOUNT
                          + "\",\"username\":\"ordinary\",\"email\":null,\"firstName\":null,\"lastName\":null,\"enabled\":true,\"emailVerified\":false,\"tenantId\":\"7\",\"preferredLanguage\":\"en\",\"effectiveRoles\":[\"user\"],\"passwordChangeRequired\":false,\"state\":\""
                          + "a".repeat(64)
                          + "\"}",
                      MediaType.APPLICATION_JSON)
                  .createResponse(outbound);
            });
    server
        .expect(
            twice ? ExpectedCount.twice() : ExpectedCount.once(),
            requestTo(issuer.issuer() + "/otp-config/setup-otp/ordinary"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("FROZEN", status());
              assertEquals(TwoFactorGateStatus.PENDING_SETUP, invite().getTwoFactorStatus());
              assertTrue(
                  jdbc.queryForObject(
                      "select pending_marker from identity_self_app_otp_intent where operation_id=?",
                      Boolean.class,
                      operation));
              nativeCalls.incrementAndGet();
              var body =
                  codec.read(
                      ((MockClientHttpRequest) outbound).getBodyAsString(), ObjectNode.class);
              var materialized = body.deepCopy();
              materialized.put("username", "ordinary");
              int status = refusal ? 401 : 201;
              JsonNode result =
                  codec.read(
                      refusal
                          ? "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid otp code\"}"
                          : "{\"info\":\"OTP credential created\"}",
                      JsonNode.class);
              var receipt = codec.read("{}", ObjectNode.class);
              receipt.put("realmId", REALM);
              receipt.put("accountId", ACCOUNT);
              receipt.put("operationId", operation);
              receipt.put("operation", "otp.app-setup");
              receipt.put("ownerClient", "backend-account-admin");
              receipt.put("ownerSubject", ADMIN);
              receipt.put("intentDigest", codec.digest("otp-intent", materialized));
              receipt.put("previousState", "a".repeat(64));
              receipt.put("tenantId", "7");
              receipt.put(
                  "originDigest", codec.digest("otp-origin", body.path("target").path("origin")));
              receipt.put("status", status);
              receipt.put("resultDigest", codec.digest("otp-result", result));
              String encoded =
                  Base64.getUrlEncoder()
                      .withoutPadding()
                      .encodeToString(codec.canonical(receipt).getBytes(StandardCharsets.UTF_8));
              var reply = codec.read("{}", ObjectNode.class);
              reply.put("operationId", operation);
              reply.put("accountId", ACCOUNT);
              reply.put("status", status);
              reply.set("result", result);
              reply.put(
                  "receipt",
                  encoded
                      + "."
                      + HexFormat.of().formatHex(mac("otp-operation-receipt\n" + encoded)));
              return withStatus(refusal ? HttpStatus.UNAUTHORIZED : HttpStatus.CREATED)
                  .body(codec.canonical(reply))
                  .contentType(MediaType.APPLICATION_JSON)
                  .createResponse(outbound);
            });
  }

  private byte[] mac(String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception impossible) {
      throw new IllegalStateException("Fixture MAC unavailable");
    }
  }

  private AccountInvite invite() {
    return invites.findById(inviteId).orElseThrow();
  }

  private String status() {
    return jdbc.queryForObject(
        "select status from identity_self_app_otp_intent where operation_id=?",
        String.class,
        operation);
  }

  private String envelope() {
    return jdbc.queryForObject(
        "select envelope from identity_self_app_otp_intent where operation_id=?",
        String.class,
        operation);
  }
}
