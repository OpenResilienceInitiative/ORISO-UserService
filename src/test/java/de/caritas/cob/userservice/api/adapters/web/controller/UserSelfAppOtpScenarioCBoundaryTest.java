package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.IdentityManager;
import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAppOtpMaintenance;
import de.caritas.cob.userservice.api.adapters.web.dto.EmailDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.OneTimePasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.SelfAppSignedIssuer;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityPolicy;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.accountinvite.*;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Actual public controller/delegate/invite code and H2 ownership; native JWT pairing is separate.
 */
class UserSelfAppOtpScenarioCBoundaryTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM_ID = "22222222-2222-4222-8222-222222222222";
  private static final String ADMIN = "33333333-3333-4333-8333-333333333333";
  private static final String SECRET = "JBSWY3DPEHPK3PXP";
  private static final String CODE = "123456";
  private static final String PENDING_EMAIL = "pending@oriso.org";
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  @AfterEach
  void releaseThreadSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void actualGeneratedDeleteRouteReachesOwnedMaintenanceAndReopensOnlyItsInvite() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa");
      fixture.jdbc.update("update account_invite set two_factor_status='ACTIVE' where id=1");
      fixture.expectDelete(false);
      org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(fixture.controller)
          .setControllerAdvice(
              new de.caritas.cob.userservice.api.adapters.web.controller.interceptor
                  .ApiResponseEntityExceptionHandler())
          .build()
          .perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                  "/users/2fa"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void actualGeneratedEmailValidationRouteReachesTheOriginalOwnedEmailStart() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.expectEmailFinish(false, false, false);
      org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(fixture.controller)
          .setControllerAdvice(
              new de.caritas.cob.userservice.api.adapters.web.controller.interceptor
                  .ApiResponseEntityExceptionHandler())
          .build()
          .perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                  "/users/2fa/email/validate/" + CODE))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isNoContent());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(PENDING_EMAIL, fixture.currentEmail());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void deleteCannotBorrowTheAppEnrollmentRouteBeforeAnyOwnedEffect() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa/app");
      assertThrows(
          ForbiddenException.class, () -> fixture.controller.deactivateTwoFactorAuthByApp());
      assertNull(fixture.status());
      assertEquals(0, fixture.nativeExecutions.get());
      assertEquals(0, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void emailValidationPathMustMatchTheActualCodeBeforeAnyNewOwnedEffect() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      assertThrows(
          ForbiddenException.class,
          () -> fixture.controller.finishTwoFactorAuthByEmailSetup("654321"));
      assertNull(fixture.status());
      assertEquals(fixture.emailStartOperation, fixture.pendingEmailOperation());
      assertEquals(1, fixture.nativeExecutions.get());
      assertEquals(0, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupValidatesThePublicIssuerWhileUsingTheConfiguredInternalTransport()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      String internal = "https://keycloak.internal.fixture.invalid";
      var internalCalls = new AtomicInteger();
      fixture
          .http
          .getInterceptors()
          .add(
              (request, body, execution) -> {
                assertTrue(request.getURI().toString().startsWith(internal + "/realms/"));
                internalCalls.incrementAndGet();
                var uri =
                    java.net.URI.create(
                        fixture.issuer.base()
                            + request.getURI().toString().substring(internal.length()));
                return execution.execute(
                    new org.springframework.http.client.support.HttpRequestWrapper(request) {
                      @Override
                      public java.net.URI getURI() {
                        return uri;
                      }
                    },
                    body);
              });
      fixture.delegate.setSelfAppOtpMaintenance(
          new SelfAppOtpMaintenance(
              fixture.mode,
              fixture.human,
              fixture.request,
              fixture.jdbc,
              fixture.transactions,
              fixture.issuer.actors(),
              fixture.issuer.broker(),
              fixture.codec,
              fixture.http,
              Clock.systemUTC(),
              internal,
              "synthetic",
              REALM_ID,
              fixture.issuer.issuer()));
      fixture.expectNative(false, false);
      assertDoesNotThrow(
          () ->
              assertEquals(
                  200,
                  fixture
                      .controller
                      .activateTwoFactorAuthByApp(fixture.dto())
                      .getStatusCode()
                      .value()));
      assertEquals(2, internalCalls.get());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(1, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupFreezesEncryptedOwnedIntentAndMarksInviteOnlyAfterVerifiedReceipt()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.expectNative(false, false);
      assertDoesNotThrow(
          () ->
              assertEquals(
                  200,
                  fixture
                      .controller
                      .activateTwoFactorAuthByApp(fixture.dto())
                      .getStatusCode()
                      .value()));
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.assertProtectedStorage();
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupLostReplyNewOwnerReplaysExactFrozenBytesWithoutSecondActivation()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.expectNative(true, false);
      assertThrows(
          RuntimeException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals("FROZEN", fixture.status());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      String encrypted = fixture.envelope();
      fixture.jdbc.update("update user set username='later-domain-name' where user_id=?", ACCOUNT);
      fixture.installOwner();
      assertDoesNotThrow(
          () ->
              assertEquals(
                  200,
                  fixture
                      .controller
                      .activateTwoFactorAuthByApp(fixture.dto())
                      .getStatusCode()
                      .value()));
      assertDoesNotThrow(() -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertTrue(encrypted.equals(fixture.envelope()), "An exact retry cannot reseal the intent");
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(1, fixture.nativeExecutions.get());
      assertEquals(1, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupSameIdChangedCompleteSecretRequestRefusesBeforeAnotherRpc() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.expectNative(false, false);
      assertDoesNotThrow(() -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      var changed = fixture.dto();
      changed.setSecret("KRUGS4ZANFZSAYJA");
      assertThrows(
          ConflictException.class, () -> fixture.controller.activateTwoFactorAuthByApp(changed));
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(1, fixture.nativeExecutions.get());
      assertEquals(1, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupInvalidNativeCodeCommitsTerminalRefusalBeforeReturningPublic400()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.expectNative(false, true);
      assertDoesNotThrow(
          () ->
              assertEquals(
                  400,
                  fixture
                      .controller
                      .activateTwoFactorAuthByApp(fixture.dto())
                      .getStatusCode()
                      .value()));
      assertEquals("TERMINAL_REFUSAL", fixture.status());
      assertEquals(
          401,
          fixture.jdbc.queryForObject(
              "select native_status from identity_self_app_otp_intent where operation_id=?",
              Integer.class,
              fixture.operation));
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      fixture.installOwner();
      assertEquals(
          400,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupInvalidMacKeepsFrozenOwnershipAndCannotActivateInvite() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.tamperReceipt = true;
      fixture.expectNative(false, false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals("FROZEN", fixture.status());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      assertNull(
          fixture.jdbc.queryForObject(
              "select receipt from identity_self_app_otp_intent where operation_id=?",
              String.class,
              fixture.operation));
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupMissingIntentAndForeignCurrentDomainRefuseBeforePrivateRowOrRpc()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.removeHeader("Idempotency-Key");
      assertThrows(
          BadRequestException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      fixture.request.addHeader("Idempotency-Key", fixture.operation);
      fixture.jdbc.update("update user set tenant_id=8 where user_id=?", ACCOUNT);
      assertThrows(
          ForbiddenException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals(
          0,
          fixture.jdbc.queryForObject(
              "select count(*) from identity_self_app_otp_intent", Integer.class));
      assertEquals(0, fixture.domainWrites());
      assertEquals(0, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void missingScopedServiceKeepsActualCGuardAndLegacyAppSetupStillWorks() throws Exception {
    try (var fixture = new Fixture(true, false)) {
      assertThrows(
          ScopedIdentityCommandRequiredException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      verifyNoInteractions(fixture.nativeClient);
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
    }
    try (var fixture = new Fixture(false, false)) {
      fixture.request.removeHeader("Idempotency-Key");
      assertEquals(
          200,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertEquals(
          0,
          fixture.jdbc.queryForObject(
              "select count(*) from identity_self_app_otp_intent", Integer.class));
      verify(fixture.nativeClient).putForEntity(any(), any(), any(), eq(OtpInfoDTO.class));
    }
  }

  @Test
  void publicAppDeleteFreezesOwnedIntentAndMarksPendingOnlyAfterVerifiedReceipt() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa");
      fixture.jdbc.update("update account_invite set two_factor_status='ACTIVE' where id=1");
      fixture.expectDelete(false);
      assertEquals(200, fixture.controller.deactivateTwoFactorAuthByApp().getStatusCode().value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicAppDeleteLostReplyNewOwnerReplaysFrozenDeleteWithoutSecondGateTransition()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa");
      fixture.jdbc.update("update account_invite set two_factor_status='ACTIVE' where id=1");
      fixture.expectDelete(true);
      assertThrows(RuntimeException.class, () -> fixture.controller.deactivateTwoFactorAuthByApp());
      assertEquals("FROZEN", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      String encrypted = fixture.envelope();
      fixture.installOwner();
      assertEquals(200, fixture.controller.deactivateTwoFactorAuthByApp().getStatusCode().value());
      assertEquals(200, fixture.controller.deactivateTwoFactorAuthByApp().getStatusCode().value());
      assertTrue(encrypted.equals(fixture.envelope()), "Exact delete retry cannot reseal");
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicAppDeleteInvalidReceiptRetainsActiveGateAndFrozenOwnership() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa");
      fixture.jdbc.update("update account_invite set two_factor_status='ACTIVE' where id=1");
      fixture.tamperReceipt = true;
      fixture.expectDelete(false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.deactivateTwoFactorAuthByApp());
      assertEquals("FROZEN", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void missingScopedDeleteKeepsActualCGuardAndLegacyDeleteRemainsWorking() throws Exception {
    try (var fixture = new Fixture(true, false)) {
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa");
      fixture.jdbc.update("update account_invite set two_factor_status='ACTIVE' where id=1");
      assertThrows(
          ScopedIdentityCommandRequiredException.class,
          () -> fixture.controller.deactivateTwoFactorAuthByApp());
      assertEquals("ACTIVE", fixture.inviteStatus());
      verifyNoInteractions(fixture.nativeClient);
    }
    try (var fixture = new Fixture(false, false)) {
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa");
      fixture.request.removeHeader("Idempotency-Key");
      fixture.jdbc.update("update account_invite set two_factor_status='ACTIVE' where id=1");
      assertEquals(200, fixture.controller.deactivateTwoFactorAuthByApp().getStatusCode().value());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      verify(fixture.nativeClient).delete(any(), any(), eq(Void.class));
    }
  }

  @Test
  void publicEmailStartAcknowledgesOnlyVerifiedSentAndKeepsPendingRecipientEncrypted()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.expectEmail(false, false);
      assertEquals(
          204,
          assertDoesNotThrow(
                  () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(fixture.operation, fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.assertEmailStorage();
      fixture.server.verify();
    }
  }

  @Test
  void publicEmailIdempotencyBindsTheCompleteEmailFieldBeforeNormalization() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.expectEmail(false, false);
      assertEquals(
          204,
          fixture
              .controller
              .startTwoFactorAuthByEmailSetup(fixture.email())
              .getStatusCode()
              .value());
      var changed = new EmailDTO();
      changed.setEmail(PENDING_EMAIL);
      assertThrows(
          ConflictException.class,
          () -> fixture.controller.startTwoFactorAuthByEmailSetup(changed));
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(fixture.operation, fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals(1, fixture.nativeExecutions.get());
      assertEquals(0, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void publicEmailStartLostReplyNewOwnerReplaysFrozenRecipientAndOriginalExpiry() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.expectEmail(true, false);
      assertThrows(
          RuntimeException.class,
          () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()));
      assertEquals("FROZEN", fixture.status());
      assertNull(fixture.pendingEmailOperation());
      String envelope = fixture.envelope();
      long expiry =
          fixture.jdbc.queryForObject(
              "select authority_valid_until from identity_self_app_otp_intent where operation_id=?",
              Long.class,
              fixture.operation);
      fixture.installOwner();
      assertEquals(
          204,
          fixture
              .controller
              .startTwoFactorAuthByEmailSetup(fixture.email())
              .getStatusCode()
              .value());
      assertEquals(
          204,
          fixture
              .controller
              .startTwoFactorAuthByEmailSetup(fixture.email())
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(fixture.operation, fixture.pendingEmailOperation());
      assertEquals(envelope, fixture.envelope());
      assertEquals(
          expiry,
          fixture.jdbc.queryForObject(
              "select authority_valid_until from identity_self_app_otp_intent where operation_id=?",
              Long.class,
              fixture.operation));
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.assertEmailStorage();
      fixture.server.verify();
    }
  }

  @Test
  void publicEmailStartSameIdCannotReplaceTheCompletePendingDestination() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.expectEmail(false, false);
      assertEquals(
          204,
          assertDoesNotThrow(
                  () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()))
              .getStatusCode()
              .value());
      String original = fixture.envelope();
      var changed = fixture.email();
      changed.setEmail("changed@oriso.org");
      assertThrows(
          ConflictException.class,
          () -> fixture.controller.startTwoFactorAuthByEmailSetup(changed));
      assertEquals(original, fixture.envelope());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicEmailStartUnknownNativeDeliveryCannotCommitSuccessOrPendingRecipient()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.expectEmail(false, true);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()));
      assertEquals("FROZEN", fixture.status());
      assertNull(fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.assertEmailStorage();
      fixture.server.verify();
    }
  }

  @Test
  void publicEmailStartInvalidReceiptRetainsFrozenOwnershipBeforeAnyLocalSuccess()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.tamperReceipt = true;
      fixture.expectEmail(false, false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()));
      assertEquals("FROZEN", fixture.status());
      assertNull(fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals(0, fixture.domainWrites());
      fixture.assertEmailStorage();
      fixture.server.verify();
    }
  }

  @Test
  void publicEmailStartMissingIntentAndForeignCurrentDomainRefuseBeforeOwnedStateOrRpc()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.request.removeHeader("Idempotency-Key");
      assertThrows(
          BadRequestException.class,
          () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()));
      assertNull(fixture.status());
      fixture.request.addHeader("Idempotency-Key", fixture.operation);
      fixture.jdbc.update("update user set tenant_id=9 where user_id=?", ACCOUNT);
      assertThrows(
          ForbiddenException.class,
          () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()));
      assertNull(fixture.status());
      assertNull(fixture.pendingEmailOperation());
      assertEquals(0, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void missingScopedEmailStartKeepsActualCGuardAndLegacyEmailStartWorking() throws Exception {
    try (var fixture = new Fixture(true, false)) {
      fixture.request.setRequestURI("/service/users/2fa/email");
      assertThrows(
          ScopedIdentityCommandRequiredException.class,
          () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()));
      verifyNoInteractions(fixture.nativeClient);
      assertNull(fixture.status());
    }
    try (var fixture = new Fixture(false, false)) {
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.request.removeHeader("Idempotency-Key");
      assertEquals(
          204,
          fixture
              .controller
              .startTwoFactorAuthByEmailSetup(fixture.email())
              .getStatusCode()
              .value());
      verify(fixture.nativeClient)
          .putForEntity(
              any(), any(), any(), eq(de.caritas.cob.userservice.api.model.Success.class));
      assertNull(fixture.status());
    }
  }

  @Test
  void publicLinkedEmailFinishCommitsEmailPointerInviteOnlyAfterVerifiedNativeReceipt()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.expectEmailFinish(false, false, false);
      assertEquals(
          204,
          assertDoesNotThrow(() -> fixture.controller.finishTwoFactorAuthByEmailSetup(CODE))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertNull(fixture.pendingEmailOperation());
      assertEquals(PENDING_EMAIL, fixture.currentEmail());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertEquals(2, fixture.nativeExecutions.get());
      fixture.assertEmailStorage();
      fixture.server.verify();
    }
  }

  @Test
  void publicLinkedEmailFinishLostReplyNewOwnerReplaysOneNativeAndOneDomainEffect()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.expectEmailFinish(true, false, false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.finishTwoFactorAuthByEmailSetup(CODE));
      assertEquals("FROZEN", fixture.status());
      assertEquals(fixture.emailStartOperation, fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      String cipher = fixture.envelope();
      fixture.installOwner();
      assertEquals(
          204,
          assertDoesNotThrow(() -> fixture.controller.finishTwoFactorAuthByEmailSetup(CODE))
              .getStatusCode()
              .value());
      assertEquals(
          204, fixture.controller.finishTwoFactorAuthByEmailSetup(CODE).getStatusCode().value());
      assertEquals(cipher, fixture.envelope());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertNull(fixture.pendingEmailOperation());
      assertEquals(PENDING_EMAIL, fixture.currentEmail());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertEquals(2, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicLinkedEmailFinishInvalidCodeCommitsOneTerminalAttemptAndRejectsChangedSameIntent()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.expectEmailFinish(false, true, false);
      assertEquals(
          400, fixture.controller.finishTwoFactorAuthByEmailSetup(CODE).getStatusCode().value());
      assertEquals("TERMINAL_REFUSAL", fixture.status());
      assertEquals(
          400, fixture.controller.finishTwoFactorAuthByEmailSetup(CODE).getStatusCode().value());
      fixture.request.setRequestURI("/service/users/2fa/email/validate/654321");
      assertThrows(
          ConflictException.class,
          () -> fixture.controller.finishTwoFactorAuthByEmailSetup("654321"));
      assertEquals(fixture.emailStartOperation, fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      assertEquals(2, fixture.nativeExecutions.get());
      fixture.assertEmailStorage();
      fixture.server.verify();
    }
  }

  @Test
  void publicLinkedEmailFinishUnknownOutcomeKeepsOriginalPointerEmailAndInvite() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.expectEmailFinish(false, false, true);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.finishTwoFactorAuthByEmailSetup(CODE));
      assertEquals("FROZEN", fixture.status());
      assertEquals(fixture.emailStartOperation, fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      fixture.assertEmailStorage();
      fixture.server.verify();
    }
  }

  @Test
  void publicLinkedEmailFinishInvalidMacCannotChangeEmailPointerOrInvite() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.tamperReceipt = true;
      fixture.expectEmailFinish(false, false, false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.finishTwoFactorAuthByEmailSetup(CODE));
      assertEquals("FROZEN", fixture.status());
      assertEquals(fixture.emailStartOperation, fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void publicLinkedEmailFinishMissingIntentOrForeignDomainRefusesBeforeAnyNativeFinish()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.request.removeHeader("Idempotency-Key");
      assertThrows(
          BadRequestException.class,
          () -> fixture.controller.finishTwoFactorAuthByEmailSetup(CODE));
      assertNull(fixture.status());
      fixture.request.addHeader("Idempotency-Key", fixture.operation);
      fixture.jdbc.update("update user set tenant_id=9 where user_id=?", ACCOUNT);
      assertThrows(
          ForbiddenException.class, () -> fixture.controller.finishTwoFactorAuthByEmailSetup(CODE));
      assertNull(fixture.status());
      assertEquals(fixture.emailStartOperation, fixture.pendingEmailOperation());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicEmailStartAcknowledgedRetryAfterOriginalExpiryReturnsSavedOutcomeWithoutResend()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.operation = fixture.emailStartOperation;
      fixture.request.removeHeader("Idempotency-Key");
      fixture.request.addHeader("Idempotency-Key", fixture.operation);
      fixture.request.setMethod("PUT");
      fixture.request.setRequestURI("/service/users/2fa/email");
      String cipher = fixture.envelope();
      fixture.installOwner(fixture.afterOriginalExpiry());
      assertEquals(
          204,
          assertDoesNotThrow(
                  () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(cipher, fixture.envelope());
      assertEquals(fixture.emailStartOperation, fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicEmailSentLostAckAfterOriginalExpiryRecoversExactOutcomeWithoutAnotherSend()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.humanExpiry(Instant.now().plusSeconds(20));
      fixture.expectEmail(true, false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()));
      assertEquals("FROZEN", fixture.status());
      String original = fixture.envelope();
      long deadline =
          fixture.jdbc.queryForObject(
              "select authority_valid_until from identity_self_app_otp_intent where operation_id=?",
              Long.class,
              fixture.operation);
      fixture.humanExpiry(Instant.now().plusSeconds(600));
      fixture.installOwner(fixture.afterOriginalExpiry());
      assertEquals(
          204,
          assertDoesNotThrow(
                  () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(original, fixture.envelope());
      assertEquals(
          deadline,
          fixture.jdbc.queryForObject(
              "select authority_valid_until from identity_self_app_otp_intent where operation_id=?",
              Long.class,
              fixture.operation));
      assertEquals(fixture.operation, fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicExpiredHistoricalSentAckCannotReplaceLaterEnrollmentOrPendingEmail() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      String oldStart = fixture.operation;
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.humanExpiry(Instant.now().plusSeconds(20));
      fixture.expectEmail(true, false, 2);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.startTwoFactorAuthByEmailSetup(fixture.email()));
      String oldCipher = fixture.envelope();
      fixture.server.verify();
      fixture.server.reset();
      fixture.humanExpiry(Instant.now().plusSeconds(600));
      fixture.operation = UUID.randomUUID().toString();
      String later = fixture.operation;
      fixture.request.removeHeader("Idempotency-Key");
      fixture.request.addHeader("Idempotency-Key", later);
      fixture.request.setRequestURI("/service/users/2fa/app");
      fixture.expectNative(false, false);
      assertEquals(
          200,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      fixture.server.verify();
      fixture.server.reset();
      String laterPending = UUID.randomUUID().toString();
      fixture.jdbc.update(
          "insert into identity_self_otp_pending_email(account_id,start_operation_id) values(?,?)",
          ACCOUNT,
          laterPending);
      fixture.operation = oldStart;
      fixture.request.removeHeader("Idempotency-Key");
      fixture.request.addHeader("Idempotency-Key", oldStart);
      fixture.request.setRequestURI("/service/users/2fa/email");
      fixture.installOwner(fixture.afterOriginalExpiry());
      fixture.expectSavedEmail();
      assertEquals(
          204,
          fixture
              .controller
              .startTwoFactorAuthByEmailSetup(fixture.email())
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(oldCipher, fixture.envelope());
      assertEquals(laterPending, fixture.pendingEmailOperation());
      assertEquals(
          later,
          fixture.jdbc.queryForObject(
              "select operation_id from identity_self_otp_local_enrollment where account_id=?",
              String.class,
              ACCOUNT));
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals(1, fixture.domainWrites());
      assertEquals(2, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicLinkedEmailTerminalRefusalAfterOriginalExpiryReturnsSaved400WithoutAnotherAttempt()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.expectEmailFinish(false, true, false);
      assertEquals(
          400, fixture.controller.finishTwoFactorAuthByEmailSetup(CODE).getStatusCode().value());
      assertEquals("TERMINAL_REFUSAL", fixture.status());
      String cipher = fixture.envelope();
      fixture.installOwner(fixture.afterOriginalExpiry());
      assertEquals(
          400,
          assertDoesNotThrow(() -> fixture.controller.finishTwoFactorAuthByEmailSetup(CODE))
              .getStatusCode()
              .value());
      assertEquals(cipher, fixture.envelope());
      assertEquals("TERMINAL_REFUSAL", fixture.status());
      assertEquals(fixture.emailStartOperation, fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      assertEquals(2, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicDeleteAfterSentEmailClearsOnlyOriginalPendingPointerWithVerifiedAck()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa");
      fixture.expectDelete(false, "PENDING_SETUP");
      assertEquals(200, fixture.controller.deactivateTwoFactorAuthByApp().getStatusCode().value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertNull(fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      String laterPending = UUID.randomUUID().toString();
      fixture.jdbc.update(
          "insert into identity_self_otp_pending_email(account_id,start_operation_id) values(?,?)",
          ACCOUNT,
          laterPending);
      assertEquals(200, fixture.controller.deactivateTwoFactorAuthByApp().getStatusCode().value());
      assertEquals(laterPending, fixture.pendingEmailOperation());
      assertEquals(0, fixture.domainWrites());
      assertEquals(2, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicDeleteInvalidReceiptCannotClearSentPendingEmailPointer() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.prepareDeliveredEmail();
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa");
      fixture.tamperReceipt = true;
      fixture.expectDelete(false, "PENDING_SETUP");
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.deactivateTwoFactorAuthByApp());
      assertEquals("FROZEN", fixture.status());
      assertEquals(fixture.emailStartOperation, fixture.pendingEmailOperation());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void publicLostDeleteAckCannotReopenInviteOrClearPointerOfLaterEnrollment() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa");
      fixture.jdbc.update("update account_invite set two_factor_status='ACTIVE' where id=1");
      String oldDelete = fixture.operation;
      fixture.expectDelete(true, "ACTIVE", 1);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.deactivateTwoFactorAuthByApp());
      assertEquals("FROZEN", fixture.status());
      String originalCipher = fixture.envelope();
      fixture.server.verify();
      fixture.server.reset();
      // The later enrollment has its own public request and verified native receipt. Its local
      // setup prerequisite is seeded, then the real invite service performs its ACTIVE transition.
      fixture.operation = UUID.randomUUID().toString();
      fixture.request.removeHeader("Idempotency-Key");
      fixture.request.addHeader("Idempotency-Key", fixture.operation);
      fixture.request.setMethod("PUT");
      fixture.request.setRequestURI("/service/users/2fa/app");
      fixture.jdbc.update("update account_invite set two_factor_status='PENDING_SETUP' where id=1");
      fixture.expectNative(false, false);
      assertEquals(
          200,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      fixture.server.verify();
      fixture.server.reset();
      String laterPending = UUID.randomUUID().toString();
      fixture.jdbc.update(
          "insert into identity_self_otp_pending_email(account_id,start_operation_id) values(?,?)",
          ACCOUNT,
          laterPending);
      fixture.operation = oldDelete;
      fixture.request.removeHeader("Idempotency-Key");
      fixture.request.addHeader("Idempotency-Key", oldDelete);
      fixture.request.setMethod("DELETE");
      fixture.request.setRequestURI("/service/users/2fa");
      fixture.expectSavedDelete();
      assertEquals(200, fixture.controller.deactivateTwoFactorAuthByApp().getStatusCode().value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(originalCipher, fixture.envelope());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(laterPending, fixture.pendingEmailOperation());
      assertEquals(1, fixture.domainWrites());
      assertEquals(2, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicReorderedAppAckKeepsTheLaterAppliedEnrollmentIncarnation() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      String older = fixture.operation;
      fixture.expectNative(true, false, 1);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      String oldReply = fixture.savedAppReply;
      String oldWire = fixture.savedAppWire;
      String oldCipher = fixture.envelope();
      fixture.server.verify();
      fixture.server.reset();
      fixture.operation = UUID.randomUUID().toString();
      String later = fixture.operation;
      fixture.request.removeHeader("Idempotency-Key");
      fixture.request.addHeader("Idempotency-Key", later);
      fixture.expectNative(false, false);
      assertEquals(
          200,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      fixture.server.verify();
      fixture.server.reset();
      fixture.operation = older;
      fixture.request.removeHeader("Idempotency-Key");
      fixture.request.addHeader("Idempotency-Key", older);
      fixture.savedAppReply = oldReply;
      fixture.savedAppWire = oldWire;
      fixture.expectSavedApp();
      assertEquals(
          200,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(oldCipher, fixture.envelope());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertEquals(
          later,
          fixture.jdbc.queryForObject(
              "select operation_id from identity_self_otp_local_enrollment where account_id=?",
              String.class,
              ACCOUNT));
      assertEquals(2, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicOtpHttpKeyCannotMoveAnAcknowledgedRequestToAnotherAllowedPath() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.expectNative(false, false);
      assertEquals(
          200,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      String original = fixture.envelope();
      fixture.server.verify();
      fixture.server.reset();
      fixture.request.setRequestURI("/users/2fa/app");
      assertThrows(
          ConflictException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(original, fixture.envelope());
      assertEquals(1, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicOtpHttpKeyKeepsPreparedMethodPathAndBodyBindingBeforeAnotherRpc() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      var reads = new AtomicInteger();
      fixture
          .server
          .expect(
              ExpectedCount.between(1, 2),
              requestTo(
                  fixture.issuer.issuer()
                      + "/oriso-account-maintenance/v1/accounts/"
                      + ACCOUNT
                      + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(
              outbound -> {
                reads.incrementAndGet();
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("{\"error\":\"bounded read unavailable\"}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals("PREPARED", fixture.status());
      assertNull(fixture.envelope());
      fixture.request.setRequestURI("/users/2fa/app");
      assertThrows(
          ConflictException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals(1, reads.get());
      fixture.server.verify();
      fixture.server.reset();
      fixture.request.setRequestURI("/service/users/2fa/app");
      fixture.expectNative(false, false);
      assertEquals(
          200,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(1, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicOtpHttpIntentNeverAdoptsAnOlderPreparedRowWithoutItsPublicBinding() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      var reads = new AtomicInteger();
      fixture
          .server
          .expect(
              ExpectedCount.between(1, 2),
              requestTo(
                  fixture.issuer.issuer()
                      + "/oriso-account-maintenance/v1/accounts/"
                      + ACCOUNT
                      + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(
              outbound -> {
                reads.incrementAndGet();
                return withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("{\"error\":\"bounded read unavailable\"}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      String older =
          fixture.codec.digest(
              "oriso/user-maintenance-intent/request/v1", Map.of("otp", CODE, "secret", SECRET));
      fixture.jdbc.update(
          "update identity_self_app_otp_intent set request_digest=? where operation_id=?",
          older,
          fixture.operation);
      assertThrows(
          ConflictException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals("PREPARED", fixture.status());
      assertNull(fixture.envelope());
      assertEquals(
          older,
          fixture.jdbc.queryForObject(
              "select request_digest from identity_self_app_otp_intent where operation_id=?",
              String.class,
              fixture.operation));
      assertEquals(1, reads.get());
      assertEquals(0, fixture.domainWrites());
      assertEquals(0, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicOtpAckCannotTransitionInviteAttachedAfterItsFrozenMembershipCheck() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.attachInviteDuringCallback = true;
      fixture.expectNative(false, false);
      assertEquals(
          200,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(
          "PENDING_SETUP",
          fixture.jdbc.queryForObject(
              "select two_factor_status from account_invite where id=2", String.class));
      assertEquals(
          0,
          fixture.jdbc.queryForObject(
              "select write_count from account_invite where id=2", Integer.class));
      assertEquals(1, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void missingScopedEmailFinishKeepsActualCGuardAndLegacyInvalidCodeWorking() throws Exception {
    try (var fixture = new Fixture(true, false)) {
      fixture.request.setMethod("POST");
      fixture.request.setRequestURI("/service/users/2fa/email/validate/" + CODE);
      assertThrows(
          ScopedIdentityCommandRequiredException.class,
          () -> fixture.controller.finishTwoFactorAuthByEmailSetup(CODE));
      verifyNoInteractions(fixture.nativeClient);
      assertNull(fixture.status());
    }
    try (var fixture = new Fixture(false, false)) {
      fixture.request.setMethod("POST");
      fixture.request.setRequestURI("/service/users/2fa/email/validate/" + CODE);
      fixture.request.removeHeader("Idempotency-Key");
      when(fixture.nativeClient.postForEntity(
              any(), any(), any(), eq(de.caritas.cob.userservice.api.model.SuccessWithEmail.class)))
          .thenThrow(
              new org.springframework.web.client.HttpClientErrorException(HttpStatus.UNAUTHORIZED));
      assertEquals(
          400, fixture.controller.finishTwoFactorAuthByEmailSetup(CODE).getStatusCode().value());
      verify(fixture.nativeClient, times(1))
          .postForEntity(
              any(), any(), any(), eq(de.caritas.cob.userservice.api.model.SuccessWithEmail.class));
      assertNull(fixture.status());
    }
  }

  static final class Fixture implements AutoCloseable {
    final boolean fiveActors;
    final SelfAppSignedIssuer issuer = new SelfAppSignedIssuer(ADMIN);
    final AuthenticatedUser human =
        new AuthenticatedUser(
            ACCOUNT, "ordinary", Set.of("user"), "fixture-human", Set.of("ROLE_USER"));
    final MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/service/users/2fa/app");
    final DriverManagerDataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:self-app-"
                + UUID.randomUUID()
                + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
    final IdentityClientConfig mode = mock(IdentityClientConfig.class);
    final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
    final RestTemplate http = new RestTemplate();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    final KeycloakClient nativeClient = mock(KeycloakClient.class);
    final AccountManaging accounts = mock(AccountManaging.class);
    final UserController controller = mock(UserController.class, CALLS_REAL_METHODS);
    final UserTwoFactorAuthControllerDelegate delegate;
    String operation = UUID.randomUUID().toString();
    String emailStartOperation;
    final AtomicInteger nativeExecutions = new AtomicInteger();
    boolean attachInviteDuringCallback;
    long currentTenant = 7L;
    boolean protectedAccount;
    boolean tamperReceipt;
    Runnable beforeEmailReceipt = () -> {};
    String savedEmailReply;
    String savedEmailWire;
    String savedAppReply;
    String savedAppWire;
    String savedDeleteReply;
    String savedDeleteWire;

    Fixture(boolean fiveActors, boolean scopedService) throws Exception {
      this.fiveActors = fiveActors;
      try {
        when(mode.isFiveActorMode()).thenReturn(fiveActors);
        when(mode.getOtpUrl(anyString(), anyString())).thenReturn(issuer.base() + "/legacy-otp");
        when(nativeClient.putForEntity(any(), any(), any(), eq(OtpInfoDTO.class)))
            .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(new OtpInfoDTO()));
        var nativeUsers = mock(org.keycloak.admin.client.resource.UsersResource.class);
        when(nativeClient.getUsersResource()).thenReturn(nativeUsers);
        when(nativeUsers.search(anyString(), eq(0), eq(Integer.MAX_VALUE))).thenReturn(List.of());
        human.setTenantId(7L);
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
        var authentication =
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        request.setUserPrincipal(authentication);
        request.addHeader("Authorization", "Bearer fixture-human");
        request.addHeader("Idempotency-Key", operation);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        jdbc.execute(
            "create table user(user_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,username varchar(512),email varchar(320))");
        jdbc.update(
            "insert into user values(?,?,null,'ordinary','original@oriso.org')", ACCOUNT, 7L);
        jdbc.execute(
            "create table admin(admin_id varchar(36) primary key,tenant_id bigint,type varchar(6),email varchar(320))");
        jdbc.execute(
            "create table consultant(consultant_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,username varchar(512),email varchar(320))");
        jdbc.execute(
            "create table account_invite(id bigint primary key,accepted_by_user_id varchar(36),two_factor_status varchar(32),write_count int)");
        jdbc.update("insert into account_invite values(1,?,'PENDING_SETUP',0)", ACCOUNT);
        jdbc.execute(
            "create table identity_self_app_otp_intent(operation_id varchar(36) primary key,realm_id varchar(512),realm_name varchar(512),account_id varchar(36),tenant_id bigint,owner_client varchar(512),owner_subject varchar(36),request_digest varchar(64),target_json clob,status varchar(32),authority_valid_until bigint,metadata_json clob,envelope clob,pending_marker boolean,receipt clob,native_status int,updated_at timestamp)");
        jdbc.execute(
            "create table identity_self_otp_pending_email(account_id varchar(36) primary key,start_operation_id varchar(36))");
        jdbc.execute(
            "create table identity_self_otp_local_enrollment(account_id varchar(36) primary key,operation_id varchar(36))");
        var invites = mock(AccountInviteRepository.class);
        when(invites.findAllByAcceptedByUserIdAndTwoFactorStatus(eq(ACCOUNT), any()))
            .thenAnswer(
                invocation -> {
                  if (fiveActors)
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  TwoFactorGateStatus wanted = invocation.getArgument(1);
                  if (attachInviteDuringCallback) {
                    attachInviteDuringCallback = false;
                    // A second physical connection commits after the private membership check,
                    // at the actual domain query. It does not acquire the owning User row lock.
                    try (var writer = source.getConnection()) {
                      writer.setAutoCommit(false);
                      try (var insert =
                          writer.prepareStatement(
                              "insert into account_invite values(2,?,'PENDING_SETUP',0)")) {
                        insert.setString(1, ACCOUNT);
                        assertEquals(1, insert.executeUpdate());
                      }
                      writer.commit();
                    }
                  }
                  return jdbc
                      .queryForList(
                          "select id,two_factor_status from account_invite where accepted_by_user_id=? and two_factor_status=? order by id",
                          ACCOUNT,
                          wanted.name())
                      .stream()
                      .map(
                          row -> {
                            var invite = new AccountInvite();
                            invite.setId(((Number) row.get("id")).longValue());
                            invite.setAcceptedByUserId(ACCOUNT);
                            invite.setTwoFactorStatus(
                                TwoFactorGateStatus.valueOf((String) row.get("two_factor_status")));
                            return invite;
                          })
                      .toList();
                });
        when(invites.saveAll(any()))
            .thenAnswer(
                invocation -> {
                  if (fiveActors)
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  Iterable<AccountInvite> saved = invocation.getArgument(0);
                  for (var invite : saved)
                    jdbc.update(
                        "update account_invite set two_factor_status=?,write_count=write_count+1 where id=?",
                        invite.getTwoFactorStatus().name(),
                        invite.getId());
                  return saved;
                });
        var inviteService =
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
        var nativeService =
            new KeycloakService(
                human,
                mock(UserAccountInputValidator.class),
                mode,
                nativeClient,
                new KeycloakMapper(),
                mock(UserHelper.class),
                mock(KeycloakAuthClient.class));
        var transcoder = new UsernameTranscoder();
        var identity =
            new IdentityManager(
                nativeService,
                nativeService,
                nativeService,
                nativeService,
                nativeService,
                nativeService,
                nativeService,
                transcoder);
        when(accounts.patchUser(anyMap()))
            .thenAnswer(
                invocation -> {
                  assertTrue(fiveActors);
                  assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  Map<String, Object> patch = invocation.getArgument(0);
                  assertEquals(Set.of("id", "email"), patch.keySet());
                  assertEquals(ACCOUNT, patch.get("id"));
                  assertEquals(PENDING_EMAIL, patch.get("email"));
                  assertEquals(
                      1,
                      jdbc.update(
                          "update user set email=? where user_id=? and tenant_id=7 and delete_date is null",
                          patch.get("email"),
                          patch.get("id")));
                  return Optional.of(patch);
                });
        var policy = mock(IdentityPolicy.class);
        when(policy.isTwoFactorAuthenticationAllowed(anySet())).thenReturn(true);
        delegate =
            new UserTwoFactorAuthControllerDelegate(
                human,
                policy,
                identity,
                accounts,
                inviteService,
                new UserDtoMapper(policy),
                transcoder);
        ReflectionTestUtils.setField(controller, "userTwoFactorAuthControllerDelegate", delegate);
        var checked = issuer.broker().issueCheckedMaintenance(TaskPurpose.OTP);
        assertEquals(ADMIN, checked.subject());
        assertTrue(checked.validatedJwtExpiresAt() > Instant.now().getEpochSecond());
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from user where user_id=? and tenant_id=7 and delete_date is null",
                Integer.class,
                ACCOUNT));
        assertEquals("PENDING_SETUP", inviteStatus());
        if (scopedService) installOwner();
      } catch (Exception | AssertionError failure) {
        issuer.close();
        jdbc.execute("SHUTDOWN");
        throw failure;
      }
    }

    void protectedAdmin(long tenant, String type) {
      currentTenant = tenant;
      protectedAccount = !"AGENCY".equals(type);
      jdbc.update("delete from user where user_id=?", ACCOUNT);
      jdbc.update("insert into admin values(?,?,?,'original@oriso.org')", ACCOUNT, tenant, type);
      jdbc.execute("alter table account_invite add tenant_id bigint");
      jdbc.execute("alter table account_invite add target_role varchar(32)");
      jdbc.execute("alter table account_invite add purpose varchar(16)");
      jdbc.execute("alter table account_invite add provisioning_status varchar(32)");
      jdbc.update(
          "update account_invite set tenant_id=?,target_role=?,purpose='INVITE',provisioning_status='COMPLETED' where id=1",
          tenant,
          "SUPER".equals(type) ? "PLATFORM_ADMIN" : "TENANT_ADMIN");
      jdbc.execute(
          "create table protected_invitation_acceptance(invite_id bigint primary key,claim_id varchar(36),attempt_id varchar(36),execution_id varchar(36),account_id varchar(36))");
      jdbc.execute(
          "create table protected_account_activation(invite_id bigint primary key,claim_id varchar(36),attempt_id varchar(36),execution_id varchar(36),account_id varchar(36),state varchar(32),native_commit_receipt clob)");
      String claim = UUID.randomUUID().toString();
      String attempt = UUID.randomUUID().toString();
      String execution = UUID.randomUUID().toString();
      // These are private local commit facts, not proof of native permission. HTTP is captured.
      jdbc.update(
          "insert into protected_invitation_acceptance values(1,?,?,?,?)",
          claim,
          attempt,
          execution,
          ACCOUNT);
      jdbc.update(
          "insert into protected_account_activation values(1,?,?,?,?,'COMMITTED','fixture-local-verified-receipt')",
          claim,
          attempt,
          execution,
          ACCOUNT);
      human.setTenantId(tenant);
      var previous = ((JwtAuthenticationToken) request.getUserPrincipal()).getToken();
      var jwt =
          Jwt.withTokenValue("fixture-human")
              .headers(headers -> headers.putAll(previous.getHeaders()))
              .claims(claims -> claims.putAll(previous.getClaims()))
              .claim("tenantId", Long.toString(tenant))
              .build();
      var authentication =
          new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
      request.setUserPrincipal(authentication);
      SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    void refuseNativeRead() {
      server
          .expect(
              requestTo(
                  issuer.issuer() + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(withStatus(HttpStatus.FORBIDDEN));
    }

    void humanExpiry(Instant deadline) {
      var previous = ((JwtAuthenticationToken) request.getUserPrincipal()).getToken();
      var jwt =
          Jwt.withTokenValue("fixture-human")
              .headers(headers -> headers.putAll(previous.getHeaders()))
              .claims(claims -> claims.putAll(previous.getClaims()))
              .expiresAt(deadline)
              .build();
      var authentication =
          new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
      request.setUserPrincipal(authentication);
      SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    Clock afterOriginalExpiry() {
      long deadline =
          jdbc.queryForObject(
              "select authority_valid_until from identity_self_app_otp_intent where operation_id=?",
              Long.class,
              operation);
      var jwt = ((JwtAuthenticationToken) request.getUserPrincipal()).getToken();
      assertTrue(
          deadline + 1 < jwt.getExpiresAt().getEpochSecond(),
          "Original machine bound must expire before the actual human");
      return Clock.fixed(Instant.ofEpochSecond(deadline + 1), java.time.ZoneOffset.UTC);
    }

    void installOwner() {
      installOwner(Clock.systemUTC());
    }

    void installOwner(Clock currentClock) {
      delegate.setSelfAppOtpMaintenance(
          new SelfAppOtpMaintenance(
              mode,
              human,
              request,
              jdbc,
              transactions,
              issuer.actors(),
              issuer.broker(),
              codec,
              http,
              currentClock,
              issuer.base(),
              "synthetic",
              REALM_ID));
    }

    EmailDTO email() {
      var email = new EmailDTO();
      email.setEmail(PENDING_EMAIL.toUpperCase(java.util.Locale.ROOT));
      return email;
    }

    String pendingEmailOperation() {
      var rows =
          jdbc.queryForList(
              "select start_operation_id from identity_self_otp_pending_email where account_id=?",
              String.class,
              ACCOUNT);
      return rows.isEmpty() ? null : rows.getFirst();
    }

    String currentEmail() {
      return jdbc.queryForObject(
          jdbc.queryForObject("select count(*) from admin where admin_id=?", Integer.class, ACCOUNT) == 1
              ? "select email from admin where admin_id=?"
              : jdbc.queryForObject("select count(*) from user where user_id=?", Integer.class, ACCOUNT) == 1
                  ? "select email from user where user_id=?"
                  : "select email from consultant where consultant_id=?",
          String.class,
          ACCOUNT);
    }

    void assertEmailStorage() {
      assertProtectedStorage();
      String persisted =
          jdbc.queryForList("select * from identity_self_app_otp_intent").toString()
              + jdbc.queryForList("select * from identity_self_otp_pending_email").toString();
      assertFalse(persisted.contains(PENDING_EMAIL));
      assertFalse(persisted.contains(PENDING_EMAIL.toUpperCase(java.util.Locale.ROOT)));
    }

    OneTimePasswordDTO dto() {
      var dto = new OneTimePasswordDTO();
      dto.setOtp(CODE);
      dto.setSecret(SECRET);
      return dto;
    }

    String status() {
      var rows =
          jdbc.queryForList(
              "select status from identity_self_app_otp_intent where operation_id=?",
              String.class,
              operation);
      return rows.isEmpty() ? null : rows.getFirst();
    }

    String envelope() {
      return jdbc.queryForObject(
          "select envelope from identity_self_app_otp_intent where operation_id=?",
          String.class,
          operation);
    }

    String inviteStatus() {
      return jdbc.queryForObject(
          "select two_factor_status from account_invite where id=1", String.class);
    }

    int domainWrites() {
      return jdbc.queryForObject(
          "select write_count from account_invite where id=1", Integer.class);
    }

    void assertProtectedStorage() {
      String values =
          jdbc.queryForList(
                  "select * from identity_self_app_otp_intent where operation_id=?", operation)
              .toString();
      for (var value : List.of(SECRET, CODE, "fixture-human", "ordinary"))
        assertFalse(
            values.contains(value),
            "Private intent must contain no plaintext credentials or bearer");
      assertNotNull(envelope());
    }

    void expectNative(boolean lostReply, boolean refusal) {
      expectNative(lostReply, refusal, lostReply ? 2 : 1);
    }

    void expectNative(boolean lostReply, boolean refusal, int requests) {
      server
          .expect(
              requestTo(
                  issuer.issuer() + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("PREPARED", status());
                assertEquals("PENDING_SETUP", inviteStatus());
                assertNull(envelope());
                var read =
                    codec.read(
                        ((MockClientHttpRequest) outbound).getBodyAsString(), JsonNode.class);
                checkProof(
                    outbound.getHeaders(),
                    read,
                    "account.read",
                    "ACCOUNT_MAINTENANCE",
                    "oriso-account-maintenance",
                    "POST");
                return withSuccess(
                        "{\"id\":\""
                            + ACCOUNT
                            + "\",\"username\":\"ordinary\",\"email\":null,\"firstName\":null,\"lastName\":null,\"enabled\":true,\"emailVerified\":false,\"tenantId\":\""
                            + currentTenant
                            + "\",\"preferredLanguage\":\"en\",\"effectiveRoles\":[\"user\"],\"passwordChangeRequired\":false,\"state\":\""
                            + "a".repeat(64)
                            + "\"}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
      var frozen = new AtomicReference<String>();
      var encrypted = new AtomicReference<String>();
      var response = new AtomicReference<String>();
      server
          .expect(
              ExpectedCount.times(requests),
              requestTo(issuer.issuer() + "/otp-config/setup-otp/ordinary"))
          .andExpect(method(HttpMethod.PUT))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("FROZEN", status());
                assertEquals("PENDING_SETUP", inviteStatus());
                assertEquals(0, domainWrites());
                assertEquals(
                    Boolean.TRUE,
                    jdbc.queryForObject(
                        "select pending_marker from identity_self_app_otp_intent where operation_id=?",
                        Boolean.class,
                        operation));
                assertProtectedStorage();
                String wire = ((MockClientHttpRequest) outbound).getBodyAsString();
                var body = codec.read(wire, ObjectNode.class);
                var materialized = body.deepCopy();
                materialized.put("username", "ordinary");
                checkProof(
                    outbound.getHeaders(),
                    materialized,
                    "otp.app-setup",
                    "OTP",
                    "oriso-otp",
                    "PUT");
                if (frozen.get() == null) {
                  frozen.set(wire);
                  encrypted.set(envelope());
                  assertTrue(CODE.equals(body.path("otp").path("initialCode").textValue()));
                  assertTrue(SECRET.equals(body.path("otp").path("secret").textValue()));
                  assertEquals(operation, body.path("operationId").textValue());
                  assertEquals("a".repeat(64), body.path("expectedState").textValue());
                  int status = refusal ? 401 : 201;
                  JsonNode nativeResult =
                      codec.read(
                          refusal
                              ? "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid otp code\"}"
                              : "{\"info\":\"OTP credential created\"}",
                          JsonNode.class);
                  var outcome = codec.read("{}", ObjectNode.class);
                  outcome.put("realmId", REALM_ID);
                  outcome.put("accountId", ACCOUNT);
                  outcome.put("operationId", operation);
                  outcome.put("operation", "otp.app-setup");
                  outcome.put("ownerClient", "backend-account-admin");
                  outcome.put("ownerSubject", ADMIN);
                  outcome.put("intentDigest", codec.digest("otp-intent", materialized));
                  outcome.put("previousState", "a".repeat(64));
                  outcome.put("tenantId", Long.toString(currentTenant));
                  outcome.put(
                      "originDigest",
                      codec.digest("otp-origin", body.path("target").path("origin")));
                  outcome.put("status", status);
                  outcome.put("resultDigest", codec.digest("otp-result", nativeResult));
                  String encoded =
                      Base64.getUrlEncoder()
                          .withoutPadding()
                          .encodeToString(
                              codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
                  var result = codec.read("{}", ObjectNode.class);
                  result.put("operationId", operation);
                  result.put("accountId", ACCOUNT);
                  result.put("status", status);
                  result.set("result", nativeResult);
                  result.put(
                      "receipt",
                      encoded
                          + "."
                          + HexFormat.of().formatHex(mac("otp-operation-receipt\n" + encoded)));
                  if (tamperReceipt)
                    result.put("receipt", result.path("receipt").textValue() + "x");
                  response.set(codec.canonical(result));
                  savedAppWire = wire;
                  savedAppReply = response.get();
                  nativeExecutions.incrementAndGet();
                  if (lostReply)
                    throw new ResourceAccessException("Fixture committed reply was lost");
                } else {
                  assertTrue(frozen.get().equals(wire), "Exact frozen body required");
                  assertTrue(encrypted.get().equals(envelope()), "Exact frozen envelope required");
                }
                return withStatus(refusal ? HttpStatus.UNAUTHORIZED : HttpStatus.CREATED)
                    .body(response.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
    }

    void expectSavedApp() {
      assertNotNull(savedAppReply);
      server
          .expect(requestTo(issuer.issuer() + "/otp-config/setup-otp/ordinary"))
          .andExpect(method(HttpMethod.PUT))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(savedAppWire, ((MockClientHttpRequest) outbound).getBodyAsString());
                var materialized = codec.read(savedAppWire, ObjectNode.class);
                materialized.put("username", "ordinary");
                checkProof(
                    outbound.getHeaders(),
                    materialized,
                    "otp.app-setup",
                    "OTP",
                    "oriso-otp",
                    "PUT");
                return withStatus(HttpStatus.CREATED)
                    .body(savedAppReply)
                    .contentType(MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
    }

    void prepareDeliveredEmail() {
      request.setRequestURI("/service/users/2fa/email");
      expectEmail(false, false);
      assertEquals(204, controller.startTwoFactorAuthByEmailSetup(email()).getStatusCode().value());
      assertEquals("ACKNOWLEDGED", status());
      assertEquals(operation, pendingEmailOperation());
      server.verify();
      server.reset();
      emailStartOperation = operation;
      operation = UUID.randomUUID().toString();
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", operation);
      request.setMethod("POST");
      request.setRequestURI("/service/users/2fa/email/validate/" + CODE);
    }

    void expectEmailFinish(boolean lostReply, boolean invalid, boolean unknown) {
      server
          .expect(
              requestTo(
                  issuer.issuer() + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("PREPARED", status());
                var read =
                    codec.read(
                        ((MockClientHttpRequest) outbound).getBodyAsString(), JsonNode.class);
                checkProof(
                    outbound.getHeaders(),
                    read,
                    "account.read",
                    "ACCOUNT_MAINTENANCE",
                    "oriso-account-maintenance",
                    "POST");
                return withSuccess(
                        "{\"id\":\""
                            + ACCOUNT
                            + "\",\"username\":\"ordinary\",\"email\":null,\"firstName\":null,\"lastName\":null,\"enabled\":true,\"emailVerified\":false,\"tenantId\":\""
                            + currentTenant
                            + "\",\"preferredLanguage\":\"en\",\"effectiveRoles\":[\"user\"],\"passwordChangeRequired\":false,\"state\":\""
                            + "c".repeat(64)
                            + "\"}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
      var frozen = new AtomicReference<String>();
      var response = new AtomicReference<String>();
      server
          .expect(
              ExpectedCount.times(lostReply ? 2 : 1),
              requestTo(issuer.issuer() + "/otp-config/setup-otp-mail/ordinary"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("FROZEN", status());
                assertEquals(emailStartOperation, pendingEmailOperation());
                assertEquals("original@oriso.org", currentEmail());
                assertEquals("PENDING_SETUP", inviteStatus());
                assertEmailStorage();
                String wire = ((MockClientHttpRequest) outbound).getBodyAsString();
                var body = codec.read(wire, ObjectNode.class);
                assertEquals(emailStartOperation, body.path("emailStartOperationId").textValue());
                assertEquals(CODE, body.path("otp").path("initialCode").textValue());
                assertTrue(body.path("otp").path("secret").isNull());
                assertTrue(body.path("otp").path("email").isNull());
                var materialized = body.deepCopy();
                materialized.put("username", "ordinary");
                checkProof(
                    outbound.getHeaders(),
                    materialized,
                    "otp.email-finish",
                    "OTP",
                    "oriso-otp",
                    "POST");
                if (frozen.compareAndSet(null, wire)) {
                  nativeExecutions.incrementAndGet();
                  if (unknown)
                    return withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("{\"error\":\"Outcome unknown\"}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .createResponse(outbound);
                  int status = invalid ? 401 : 201;
                  var nativeResult =
                      codec.read(
                          invalid
                              ? "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid code\"}"
                              : "{\"info\":\"OTP setup created\"}",
                          JsonNode.class);
                  var payload = codec.read("{}", ObjectNode.class);
                  payload.put("realmId", REALM_ID);
                  payload.put("accountId", ACCOUNT);
                  payload.put("operationId", operation);
                  payload.put("operation", "otp.email-finish");
                  payload.put("ownerClient", "backend-account-admin");
                  payload.put("ownerSubject", ADMIN);
                  payload.put("intentDigest", codec.digest("otp-intent", materialized));
                  payload.put("previousState", "c".repeat(64));
                  payload.put("tenantId", Long.toString(currentTenant));
                  payload.put(
                      "originDigest",
                      codec.digest("otp-origin", body.path("target").path("origin")));
                  payload.put("status", status);
                  payload.put("resultDigest", codec.digest("otp-result", nativeResult));
                  payload.put("emailStartOperationId", emailStartOperation);
                  var binding = payload.putObject("emailCredential");
                  binding.put("credentialId", "55555555-5555-4555-8555-555555555555");
                  binding.put("generation", "d".repeat(64));
                  binding.put(
                      "destinationDigest",
                      codec.digest(
                          "otp-email-destination",
                          codec.read("\"" + PENDING_EMAIL + "\"", JsonNode.class)));
                  String encoded =
                      Base64.getUrlEncoder()
                          .withoutPadding()
                          .encodeToString(
                              codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
                  var result = codec.read("{}", ObjectNode.class);
                  result.put("operationId", operation);
                  result.put("accountId", ACCOUNT);
                  result.put("status", status);
                  result.set("result", nativeResult);
                  result.put(
                      "receipt",
                      encoded
                          + "."
                          + HexFormat.of().formatHex(mac("otp-operation-receipt\n" + encoded)));
                  if (tamperReceipt) result.put("receipt", result.path("receipt").asText() + "x");
                  response.set(codec.canonical(result));
                  if (lostReply)
                    throw new ResourceAccessException(
                        "Fixture actual native linked finish reply lost");
                } else assertEquals(frozen.get(), wire);
                beforeEmailReceipt.run();
                return withStatus(invalid ? HttpStatus.UNAUTHORIZED : HttpStatus.CREATED)
                    .body(response.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
    }

    void expectEmail(boolean lostReply, boolean unknown) {
      expectEmail(lostReply, unknown, lostReply ? 3 : 2);
    }

    void expectEmail(boolean lostReply, boolean unknown, int requests) {
      server
          .expect(
              requestTo(
                  issuer.issuer() + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("PREPARED", status());
                assertEquals("PENDING_SETUP", inviteStatus());
                assertNull(envelope());
                var read =
                    codec.read(
                        ((MockClientHttpRequest) outbound).getBodyAsString(), JsonNode.class);
                checkProof(
                    outbound.getHeaders(),
                    read,
                    "account.read",
                    "ACCOUNT_MAINTENANCE",
                    "oriso-account-maintenance",
                    "POST");
                return withSuccess(
                        "{\"id\":\""
                            + ACCOUNT
                            + "\",\"username\":\"ordinary\",\"email\":null,\"firstName\":null,\"lastName\":null,\"enabled\":true,\"emailVerified\":false,\"tenantId\":\""
                            + currentTenant
                            + "\",\"preferredLanguage\":\"en\",\"effectiveRoles\":[\"user\"],\"passwordChangeRequired\":false,\"state\":\""
                            + "a".repeat(64)
                            + "\"}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
      var frozen = new AtomicReference<String>();
      var encrypted = new AtomicReference<String>();
      var calls = new AtomicInteger();
      server
          .expect(
              ExpectedCount.times(requests),
              requestTo(issuer.issuer() + "/otp-config/send-verification-mail/ordinary"))
          .andExpect(method(HttpMethod.PUT))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("FROZEN", status());
                assertNull(pendingEmailOperation());
                assertEquals("original@oriso.org", currentEmail());
                assertEmailStorage();
                String wire = ((MockClientHttpRequest) outbound).getBodyAsString();
                var body = codec.read(wire, ObjectNode.class);
                var materialized = body.deepCopy();
                materialized.put("username", "ordinary");
                checkProof(
                    outbound.getHeaders(),
                    materialized,
                    "otp.email-start",
                    "OTP",
                    "oriso-otp",
                    "PUT");
                long originalExpiry =
                    jdbc.queryForObject(
                        "select authority_valid_until from identity_self_app_otp_intent where operation_id=?",
                        Long.class,
                        operation);
                assertEquals(originalExpiry, body.path("authorityValidUntil").longValue());
                assertEquals(PENDING_EMAIL, body.path("otp").path("email").textValue());
                assertTrue(body.path("otp").path("initialCode").isNull());
                assertTrue(body.path("otp").path("secret").isNull());
                if (frozen.compareAndSet(null, wire)) {
                  encrypted.set(envelope());
                  nativeExecutions.incrementAndGet();
                } else {
                  assertEquals(frozen.get(), wire);
                  assertEquals(encrypted.get(), envelope());
                }
                int call = calls.incrementAndGet();
                if (call > 1 && unknown)
                  return withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                      .body("{\"error\":\"OTP delivery outcome is unknown\"}")
                      .contentType(MediaType.APPLICATION_JSON)
                      .createResponse(outbound);
                boolean ready = call == 1;
                int status = ready ? 202 : 200;
                String state = ready ? "READY" : "SENT";
                var nativeResult =
                    codec.read(
                        ready ? "{\"info\":\"OTP mail pending\"}" : "{\"info\":\"OTP mail sent\"}",
                        JsonNode.class);
                var outcome = codec.read("{}", ObjectNode.class);
                outcome.put("realmId", REALM_ID);
                outcome.put("accountId", ACCOUNT);
                outcome.put("operationId", operation);
                outcome.put("operation", "otp.email-start");
                outcome.put("ownerClient", "backend-account-admin");
                outcome.put("ownerSubject", ADMIN);
                outcome.put("intentDigest", codec.digest("otp-intent", materialized));
                outcome.put("previousState", "a".repeat(64));
                outcome.put("tenantId", Long.toString(currentTenant));
                outcome.put(
                    "originDigest", codec.digest("otp-origin", body.path("target").path("origin")));
                outcome.put("status", status);
                outcome.put("deliveryState", state);
                outcome.put("authorityValidUntil", originalExpiry);
                outcome.put("resultDigest", codec.digest("otp-result", nativeResult));
                var credential = outcome.putObject("emailCredential");
                credential.put("credentialId", "55555555-5555-4555-8555-555555555555");
                credential.put("generation", "b".repeat(64));
                credential.put(
                    "destinationDigest",
                    codec.digest(
                        "otp-email-destination",
                        codec.read("\"" + PENDING_EMAIL + "\"", JsonNode.class)));
                String encoded =
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
                var result = codec.read("{}", ObjectNode.class);
                result.put("operationId", operation);
                result.put("accountId", ACCOUNT);
                result.put("status", status);
                result.put("deliveryState", state);
                result.set("result", nativeResult);
                result.put(
                    "receipt",
                    encoded
                        + "."
                        + HexFormat.of().formatHex(mac("otp-operation-receipt\n" + encoded)));
                if (tamperReceipt && !ready)
                  result.put("receipt", result.path("receipt").asText() + "x");
                if (!ready) {
                  savedEmailReply = codec.canonical(result);
                  savedEmailWire = wire;
                }
                if (lostReply && call == 2)
                  throw new ResourceAccessException("Fixture committed final SENT reply was lost");
                return withStatus(ready ? HttpStatus.ACCEPTED : HttpStatus.OK)
                    .body(codec.canonical(result))
                    .contentType(MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
    }

    void expectSavedEmail() {
      assertNotNull(savedEmailReply);
      server
          .expect(requestTo(issuer.issuer() + "/otp-config/send-verification-mail/ordinary"))
          .andExpect(method(HttpMethod.PUT))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(savedEmailWire, ((MockClientHttpRequest) outbound).getBodyAsString());
                var materialized = codec.read(savedEmailWire, ObjectNode.class);
                materialized.put("username", "ordinary");
                checkProof(
                    outbound.getHeaders(),
                    materialized,
                    "otp.email-start",
                    "OTP",
                    "oriso-otp",
                    "PUT");
                return withStatus(HttpStatus.OK)
                    .body(savedEmailReply)
                    .contentType(MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
    }

    void expectDelete(boolean lostReply) {
      expectDelete(lostReply, "ACTIVE");
    }

    void expectDelete(boolean lostReply, String initialGate) {
      expectDelete(lostReply, initialGate, lostReply ? 2 : 1);
    }

    void expectSavedDelete() {
      assertNotNull(savedDeleteReply);
      assertNotNull(savedDeleteWire);
      server
          .expect(requestTo(issuer.issuer() + "/otp-config/delete-otp/ordinary"))
          .andExpect(method(HttpMethod.DELETE))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(savedDeleteWire, ((MockClientHttpRequest) outbound).getBodyAsString());
                var materialized = codec.read(savedDeleteWire, ObjectNode.class);
                materialized.put("username", "ordinary");
                checkProof(
                    outbound.getHeaders(),
                    materialized,
                    "otp.delete",
                    "OTP",
                    "oriso-otp",
                    "DELETE");
                return withSuccess(savedDeleteReply, MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
    }

    void expectDelete(boolean lostReply, String initialGate, int requests) {
      server
          .expect(
              requestTo(
                  issuer.issuer() + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("PREPARED", status());
                assertEquals(initialGate, inviteStatus());
                return withSuccess(
                        "{\"id\":\""
                            + ACCOUNT
                            + "\",\"username\":\"ordinary\",\"email\":null,\"firstName\":null,\"lastName\":null,\"enabled\":true,\"emailVerified\":false,\"tenantId\":\""
                            + currentTenant
                            + "\",\"preferredLanguage\":\"en\",\"effectiveRoles\":[\"user\"],\"passwordChangeRequired\":false,\"state\":\""
                            + "a".repeat(64)
                            + "\"}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
      var frozen = new AtomicReference<String>();
      var response = new AtomicReference<String>();
      server
          .expect(
              ExpectedCount.times(requests),
              requestTo(issuer.issuer() + "/otp-config/delete-otp/ordinary"))
          .andExpect(method(HttpMethod.DELETE))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("FROZEN", status());
                assertEquals(initialGate, inviteStatus());
                assertEquals(0, domainWrites());
                String wire = ((MockClientHttpRequest) outbound).getBodyAsString();
                var body = codec.read(wire, ObjectNode.class);
                var materialized = body.deepCopy();
                materialized.put("username", "ordinary");
                checkProof(
                    outbound.getHeaders(),
                    materialized,
                    "otp.delete",
                    "OTP",
                    "oriso-otp",
                    "DELETE");
                assertTrue(body.path("otp").isNull());
                if (frozen.get() == null) {
                  frozen.set(wire);
                  JsonNode result =
                      codec.read("{\"info\":\"OTP credentials deleted\"}", JsonNode.class);
                  var facts = codec.read("{}", ObjectNode.class);
                  facts.put("realmId", REALM_ID);
                  facts.put("accountId", ACCOUNT);
                  facts.put("operationId", operation);
                  facts.put("operation", "otp.delete");
                  facts.put("ownerClient", "backend-account-admin");
                  facts.put("ownerSubject", ADMIN);
                  facts.put("intentDigest", codec.digest("otp-intent", materialized));
                  facts.put("previousState", "a".repeat(64));
                  facts.put("tenantId", Long.toString(currentTenant));
                  facts.put(
                      "originDigest",
                      codec.digest("otp-origin", body.path("target").path("origin")));
                  facts.put("status", 200);
                  facts.put("resultDigest", codec.digest("otp-result", result));
                  String encoded =
                      Base64.getUrlEncoder()
                          .withoutPadding()
                          .encodeToString(codec.canonical(facts).getBytes(StandardCharsets.UTF_8));
                  var reply = codec.read("{}", ObjectNode.class);
                  reply.put("operationId", operation);
                  reply.put("accountId", ACCOUNT);
                  reply.put("status", 200);
                  reply.set("result", result);
                  reply.put(
                      "receipt",
                      encoded
                          + "."
                          + HexFormat.of().formatHex(mac("otp-operation-receipt\n" + encoded))
                          + (tamperReceipt ? "x" : ""));
                  response.set(codec.canonical(reply));
                  savedDeleteReply = response.get();
                  savedDeleteWire = wire;
                  nativeExecutions.incrementAndGet();
                  if (lostReply)
                    throw new ResourceAccessException("Fixture lost committed delete reply");
                } else assertTrue(frozen.get().equals(wire), "Exact frozen delete body required");
                return withSuccess(response.get(), MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
    }

    void checkProof(
        org.springframework.http.HttpHeaders headers,
        JsonNode body,
        String operationName,
        String purpose,
        String audience,
        String method) {
      assertEquals("Bearer fixture-human", headers.getFirst("X-ORISO-Human-Authorization"));
      if (protectedAccount)
        assertEquals(
            "PROTECTED_ACCOUNT_OPERATIONS_V1",
            body.path("target").path("origin").path("humanAuthority").textValue());
      else assertTrue(body.path("target").path("origin").path("humanAuthority").isNull());
      String machine = headers.getFirst("Authorization");
      assertNotNull(machine);
      assertTrue(machine.startsWith("Bearer "));
      var token =
          NimbusJwtDecoder.withJwkSetUri(issuer.base() + "/certs")
              .build()
              .decode(machine.substring(7));
      assertEquals(ADMIN, token.getSubject());
      assertEquals(
          Set.of("userservice", "oriso-account-lifecycle", "oriso-otp"),
          Set.copyOf(token.getAudience()));
      String proof = headers.getFirst("X-ORISO-Task-Context");
      assertNotNull(proof);
      String[] parts = proof.split("\\.");
      assertEquals(2, parts.length);
      assertTrue(
          java.security.MessageDigest.isEqual(
              mac("proof\n" + parts[0]), Base64.getUrlDecoder().decode(parts[1])));
      var claims =
          codec.read(
              new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
              JsonNode.class);
      assertEquals(operationName, claims.path("operation").textValue());
      assertEquals(purpose, claims.path("purpose").textValue());
      assertEquals(audience, claims.path("aud").textValue());
      assertEquals(method, claims.path("method").textValue());
      assertEquals("realm/" + REALM_ID + "/account/" + ACCOUNT, claims.path("target").textValue());
      assertEquals("backend-account-admin", claims.path("taskClient").textValue());
      assertEquals(ADMIN, claims.path("taskSubject").textValue());
      assertEquals(codec.digest("payload", body), claims.path("payloadDigest").textValue());
    }

    byte[] mac(String text) {
      try {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
      } catch (java.security.GeneralSecurityException impossible) {
        throw new IllegalStateException("Fixture MAC unavailable");
      }
    }

    @Override
    public void close() {
      issuer.close();
      jdbc.execute("SHUTDOWN");
    }
  }
}
