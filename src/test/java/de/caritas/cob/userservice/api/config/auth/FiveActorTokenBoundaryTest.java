package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class FiveActorTokenBoundaryTest {
  SyntheticTaskIssuer issuer;
  TaskActorTokenBroker broker;

  @BeforeEach
  void setup() throws Exception {
    issuer = new SyntheticTaskIssuer();
    var identity = new IdentityConfig();
    identity.setOpenidConnectUrl(issuer.base());
    var authentication =
        new KeycloakAuthClient(new RestTemplate(), new AuthenticatedUser(), identity);
    ReflectionTestUtils.setField(authentication, "keycloakClientId", "app");
    var keycloak = new KeycloakConfig();
    keycloak.setAuthServerUrl(issuer.base());
    keycloak.setRealm("synthetic");
    broker =
        new TaskActorTokenBroker(
            FiveActorConfigurationTest.configured(FiveActorConfigurationTest.environment()),
            authentication,
            NimbusJwtDecoder.withJwkSetUri(issuer.base() + "/certs").build(),
            keycloak);
  }

  @AfterEach
  void cleanup() {
    issuer.close();
  }

  @Test
  void setupOperationUsesConfidentialActorGrantAndCryptographicallyVerifiedToken() {
    var token = broker.issue(TaskPurpose.INVITE_RESERVATIONS);
    assertThat(issuer.calls.get()).isEqualTo(1);
    assertThat(issuer.lastForm)
        .containsEntry("grant_type", "client_credentials")
        .containsEntry("client_id", "backend-setup");
    assertThat(issuer.lastForm.keySet()).doesNotContain("username", "password", "refresh_token");
    assertThat(token.actor()).isEqualTo(TaskActor.SETUP);
    assertThat(token.accessToken().split("\\.").length).isEqualTo(3);
    assertThat(token.toString().contains(token.accessToken())).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = TaskPurpose.class,
      names = {
        "CONFIG_WIZARD",
        "CONSULTANT_IMPORT",
        "SMTP_SYNC",
        "SYSTEM_EMAIL_DELIVERY",
        "NOTIFICATION_DISPATCH",
        "RUNTIME_POLICY",
        "MATRIX_AGENCY",
        "APPOINTMENT_SYNC",
        "APPOINTMENT_CLEANUP",
        "DELETION_AUDIT_PULL"
      })
  void operationAliasesSelectTheirDeclaredActorAtTheTokenEndpoint(TaskPurpose purpose) {
    var token = broker.issue(purpose);
    String expected =
        switch (purpose) {
          case CONFIG_WIZARD, CONSULTANT_IMPORT -> "backend-setup";
          case SMTP_SYNC, SYSTEM_EMAIL_DELIVERY, NOTIFICATION_DISPATCH -> "backend-mail";
          default -> "backend-runtime";
        };
    assertThat(issuer.lastForm.get("client_id")).isEqualTo(expected);
    assertThat(token.actor()).isEqualTo(purpose.actor());
    assertThat(issuer.calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mixed-roles",
        "extra-role",
        "missing-role",
        "wrong-audience",
        "extra-audience",
        "wrong-subject",
        "wrong-client",
        "wrong-issuer",
        "expired",
        "missing-expiry",
        "future",
        "native-admin",
        "wrong-signature",
        "provider-error"
      })
  void invalidSignedOrProviderResponsesNeverReturnATokenOrTryFallback(String variant) {
    issuer.variant = variant;
    assertThatThrownBy(() -> broker.issue(TaskPurpose.CONFIG_WIZARD))
        .hasMessage("Task actor authentication unavailable")
        .hasNoCause();
    assertThat(issuer.calls.get()).isEqualTo(1);
    assertThat(issuer.lastForm.get("client_id")).isEqualTo("backend-setup");
  }

  @ParameterizedTest
  @EnumSource(
      value = TaskPurpose.class,
      names = {"ACCOUNT_PROVISIONING", "ACCOUNT_MAINTENANCE", "OTP", "SESSION_EXCHANGE"})
  void unpinnedNativeAccountAndLifecycleContractsRefuseBeforeHttp(TaskPurpose purpose) {
    assertThatThrownBy(() -> broker.issue(purpose))
        .hasMessage("Native account actor issuance remains gated")
        .hasNoCause();
    assertThat(issuer.calls.get()).isZero();
  }
}
