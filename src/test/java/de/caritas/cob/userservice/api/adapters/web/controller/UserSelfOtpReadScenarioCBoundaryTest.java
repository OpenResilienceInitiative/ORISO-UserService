package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAppOtpMaintenance;
import de.caritas.cob.userservice.api.adapters.web.dto.OtpType;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.facade.userdata.*;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.IdentityPolicy;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.DecryptionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual current-user public read; native human verification remains the separate paired gate. */
class UserSelfOtpReadScenarioCBoundaryTest {
  @AfterEach
  void releaseThreadSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void publicCurrentSelfReadReturnsExistingAppThroughExactOriginalScopedRoute() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      configure(fixture, true, true);
      expectRead(fixture, "{\"otpSetup\":true,\"otpType\":\"APP\"}", false);
      var response = assertDoesNotThrow(() -> fixture.controller.getUserData());
      assertEquals(200, response.getStatusCode().value());
      assertTrue(response.getBody().getTwoFactorAuth().getIsActive());
      assertEquals(OtpType.APP, response.getBody().getTwoFactorAuth().getType());
      assertNull(response.getBody().getTwoFactorAuth().getSecret());
      assertReadOnly(fixture);
      fixture.server.verify();
    }
  }

  @Test
  void publicCurrentSelfReadPreservesUnconfiguredSecretAndQrWithoutIntentHeaderOrStorage() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      configure(fixture, true, true);
      expectRead(fixture, "{\"otpSetup\":false,\"otpSecret\":\"synthetic-secret\",\"otpSecretQrCode\":\"synthetic-qr\"}", false);
      var response = assertDoesNotThrow(() -> fixture.controller.getUserData());
      assertEquals(200, response.getStatusCode().value());
      assertFalse(response.getBody().getTwoFactorAuth().getIsActive());
      assertEquals("synthetic-secret", response.getBody().getTwoFactorAuth().getSecret());
      assertEquals("synthetic-qr", response.getBody().getTwoFactorAuth().getQrCode());
      assertReadOnly(fixture);
      fixture.server.verify();
    }
  }

  @Test
  void publicCurrentSelfReadPreservesExistingEmailFactorType() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      configure(fixture, true, true);
      expectRead(fixture, "{\"otpSetup\":true,\"otpType\":\"EMAIL\",\"otpSecret\":\"synthetic-secret\",\"otpSecretQrCode\":\"synthetic-qr\"}", false);
      var response = assertDoesNotThrow(() -> fixture.controller.getUserData());
      assertTrue(response.getBody().getTwoFactorAuth().getIsActive());
      assertEquals(OtpType.EMAIL, response.getBody().getTwoFactorAuth().getType());
      assertReadOnly(fixture);
      fixture.server.verify();
    }
  }

  @Test
  void publicCurrentSelfReadRefusesForeignCurrentDomainBeforeTokenOrNativeLookup() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      configure(fixture, true, true);
      fixture.jdbc.update("update user set tenant_id=8 where user_id=?", fixture.human.getUserId());
      assertThrows(ForbiddenException.class, () -> fixture.controller.getUserData());
      assertReadOnly(fixture);
      fixture.server.verify();
    }
  }

  @Test
  void publicCurrentSelfMalformedNativeReadCannotBecomeAnUnconfiguredCredential() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      configure(fixture, true, true);
      expectRead(fixture, "{\"otpSetup\":false,\"otpSecret\":\"synthetic-secret\",\"nativeUser\":{}}", false);
      var failure = assertThrows(ServiceUnavailableException.class, () -> fixture.controller.getUserData());
      assertFalse(failure.getMessage().contains("synthetic-secret"));
      assertReadOnly(fixture);
      fixture.server.verify();
    }
  }

  @Test
  void publicCurrentSelfUnknownNativeReadRefusesWithoutLegacyFallback() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      configure(fixture, true, true);
      expectRead(fixture, "{\"error\":\"bounded unavailable\"}", true);
      assertThrows(ServiceUnavailableException.class, () -> fixture.controller.getUserData());
      assertReadOnly(fixture);
      fixture.server.verify();
    }
  }

  @Test
  void publicOtpDisabledPolicyKeepsCurrentUserDataWorkingWithoutAnyCredentialRead() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      configure(fixture, true, false);
      var response = assertDoesNotThrow(() -> fixture.controller.getUserData());
      assertEquals(200, response.getStatusCode().value());
      assertFalse(response.getBody().getTwoFactorAuth().getIsEnabled());
      assertReadOnly(fixture);
      fixture.server.verify();
    }
  }

  @Test
  void missingScopedReadRetainsActualCRefusalAndLegacyCurrentUserRead() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, false)) {
      configure(fixture, false, true);
      assertThrows(ServiceUnavailableException.class, () -> fixture.controller.getUserData());
      assertReadOnly(fixture);
      fixture.server.verify();
    }
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(false, false)) {
      configure(fixture, false, true);
      var info = new OtpInfoDTO();
      info.setOtpSetup(false);
      info.setOtpSecret("legacy-secret");
      info.setOtpSecretQrCode("legacy-qr");
      when(fixture.nativeClient.get(any(), any(), eq(OtpInfoDTO.class))).thenReturn(ResponseEntity.ok(info));
      var response = assertDoesNotThrow(() -> fixture.controller.getUserData());
      assertEquals(200, response.getStatusCode().value());
      assertEquals("legacy-secret", response.getBody().getTwoFactorAuth().getSecret());
      assertReadOnly(fixture);
      fixture.server.verify();
    }
  }

  private void configure(UserSelfAppOtpScenarioCBoundaryTest.Fixture fixture, boolean scoped, boolean allowed) {
    fixture.request.setMethod("GET");
    fixture.request.setRequestURI("/service/users/data");
    fixture.request.removeHeader("Idempotency-Key");
    var policy = mock(IdentityPolicy.class);
    when(policy.isTwoFactorAuthenticationAllowed(anySet())).thenReturn(allowed);
    var users = mock(UserAccountService.class);
    var user = new User();
    user.setUserId(fixture.human.getUserId());
    when(users.retrieveValidatedUser()).thenReturn(user);
    var provider = mock(AskerDataProvider.class);
    when(provider.retrieveData(user)).thenReturn(new UserDataResponseDTO().userRoles(Set.of("user")));
    var identity = (IdentityManaging) ReflectionTestUtils.getField(fixture.delegate, "identityManager");
    var delegate = new UserAccountControllerDelegate(
        users, fixture.human, mock(DecryptionService.class), mock(ConsultantDataFacade.class),
        policy, identity, fixture.accounts, mock(Messaging.class), mock(ConsultantDtoMapper.class),
        new UserDtoMapper(policy), mock(ConsultantService.class), mock(ConsultantUpdateService.class),
        mock(ConsultantDataProvider.class), provider, mock(VideoChatConfig.class),
        mock(KeycloakUserDataProvider.class), mock(AgencyAdminDataProvider.class), new UsernameTranscoder());
    if (scoped) delegate.setSelfAppOtpMaintenance((SelfAppOtpMaintenance) ReflectionTestUtils.getField(fixture.delegate, "selfAppOtpMaintenance"));
    ReflectionTestUtils.setField(fixture.controller, "userAccountControllerDelegate", delegate);
  }

  private void expectRead(UserSelfAppOtpScenarioCBoundaryTest.Fixture fixture, String response, boolean unavailable) {
    var account = fixture.human.getUserId();
    fixture.server.expect(requestTo(fixture.issuer.issuer() + "/oriso-account-maintenance/v1/accounts/" + account + "/read"))
        .andExpect(method(HttpMethod.POST)).andRespond(outbound -> {
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          assertReadOnly(fixture);
          var body = fixture.codec.read(((MockClientHttpRequest) outbound).getBodyAsString(), JsonNode.class);
          fixture.checkProof(outbound.getHeaders(), body, "account.read", "ACCOUNT_MAINTENANCE", "oriso-account-maintenance", "POST");
          assertEquals(account, body.path("target").path("accountId").asText());
          return withSuccess("{\"id\":\"" + account + "\",\"username\":\"ordinary\",\"email\":null,\"firstName\":null,\"lastName\":null,\"enabled\":true,\"emailVerified\":false,\"tenantId\":\"7\",\"preferredLanguage\":\"en\",\"effectiveRoles\":[\"user\"],\"passwordChangeRequired\":false,\"state\":\"" + "a".repeat(64) + "\"}", MediaType.APPLICATION_JSON).createResponse(outbound);
        });
    fixture.server.expect(requestTo(fixture.issuer.issuer() + "/otp-config/fetch-otp-setup-info/ordinary"))
        .andExpect(method(HttpMethod.GET)).andRespond(outbound -> {
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          var target = fixture.codec.read(outbound.getHeaders().getFirst("X-ORISO-Account-Target"), ObjectNode.class);
          assertEquals(account, target.path("accountId").asText());
          assertEquals(account, target.path("origin").path("humanSubject").asText());
          assertEquals("SELF", target.path("origin").path("kind").asText());
          var materialized = fixture.codec.read("{}", ObjectNode.class);
          materialized.set("target", target);
          materialized.put("username", "ordinary");
          materialized.putNull("otp");
          fixture.checkProof(outbound.getHeaders(), materialized, "otp.read", "OTP", "oriso-otp", "GET");
          return (unavailable ? withStatus(HttpStatus.SERVICE_UNAVAILABLE) : withSuccess())
              .body(response).contentType(MediaType.APPLICATION_JSON).createResponse(outbound);
        });
  }

  private void assertReadOnly(UserSelfAppOtpScenarioCBoundaryTest.Fixture fixture) {
    assertEquals(0, fixture.jdbc.queryForObject("select count(*) from identity_self_app_otp_intent", Integer.class));
    assertEquals(0, fixture.domainWrites());
    assertEquals(0, fixture.nativeExecutions.get());
    verify(fixture.nativeClient, never()).getUsersResource();
  }
}
