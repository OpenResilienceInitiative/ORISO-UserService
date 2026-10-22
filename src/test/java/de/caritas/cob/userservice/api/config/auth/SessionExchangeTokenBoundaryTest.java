package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** Real HTTP/JWKS broker boundary only; this fixture supplies no native session authority. */
class SessionExchangeTokenBoundaryTest {
  private SyntheticTaskIssuer issuer;
  private KeycloakAuthClient authentication;
  private TaskActorTokenBroker broker;

  @BeforeEach
  void setup() throws Exception {
    issuer = new SyntheticTaskIssuer();
    var identity = new IdentityConfig();
    identity.setOpenidConnectUrl(issuer.base());
    authentication = new KeycloakAuthClient(new RestTemplate(), new AuthenticatedUser(), identity);
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
  void finiteSessionPurposeReturnsOnlyCanonicalNativeAdminAndOriginalBounds() {
    var grant =
        authentication.loginService("backend-account-admin", "synthetic-account-admin-secret");
    var decoded =
        NimbusJwtDecoder.withJwkSetUri(issuer.base() + "/certs")
            .build()
            .decode(grant.getAccessToken());
    assertThat(decoded.getAudience())
        .containsExactlyInAnyOrder("userservice", "oriso-account-lifecycle", "oriso-otp");
    assertThat(decoded.getSubject()).isEqualTo("native-account-admin-subject");
    assertThat(decoded.getIssuedAt()).isNotNull();
    assertThat(decoded.getExpiresAt()).isAfter(Instant.now());
    assertThat(issuer.calls.get()).isEqualTo(1);

    var result =
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> broker.issueCheckedSessionExchange());
    assertThat(issuer.calls.get()).isEqualTo(2);
    assertThat(issuer.lastForm)
        .containsEntry("grant_type", "client_credentials")
        .containsEntry("client_id", "backend-account-admin");
    assertThat(issuer.lastForm.keySet()).doesNotContain("username", "password", "refresh_token");
    assertThat(result.purpose()).isEqualTo(TaskPurpose.SESSION_EXCHANGE);
    assertThat(result.actor()).isEqualTo(TaskActor.ACCOUNT_ADMIN);
    assertThat(result.clientId()).isEqualTo("backend-account-admin");
    assertThat(result.subject()).isEqualTo("native-account-admin-subject");
    assertThat(result.issuer()).isEqualTo(issuer.issuer());
    assertThat(result.validatedJwtIssuedAt()).isPositive();
    assertThat(result.validatedJwtExpiresAt()).isGreaterThan(result.validatedJwtIssuedAt());
    assertThat(result.validatedJwtExpiresAt()).isGreaterThan(Instant.now().getEpochSecond());
    assertThat(result.toString().contains(result.accessToken())).isFalse();
  }

  @Test
  void invalidNativeResponsesRefuseAtTheSameAdminWithoutFallbackOrNewAudience() {
    List<Executable> checks = new ArrayList<>();
    for (String variant :
        List.of(
            "mixed-roles",
            "admin-extra-role",
            "admin-missing-role",
            "wrong-audience",
            "admin-extra-audience",
            "admin-missing-audience",
            "wrong-subject",
            "wrong-client",
            "wrong-issuer",
            "expired",
            "missing-expiry",
            "future",
            "native-admin",
            "wrong-signature",
            "provider-error",
            "missing-issued-at",
            "zero-issued-at",
            "negative-issued-at",
            "future-issued-at",
            "unordered-native-bounds")) {
      checks.add(
          () -> {
            issuer.variant = variant;
            int before = issuer.calls.get();
            assertThatThrownBy(() -> broker.issueCheckedSessionExchange())
                .as(variant)
                .hasMessage("Checked maintenance authentication unavailable")
                .hasNoCause();
            assertThat(issuer.calls.get()).as(variant).isEqualTo(before + 1);
            assertThat(issuer.lastForm.get("client_id")).isEqualTo("backend-account-admin");
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(checks);
  }

  @Test
  void sessionCannotAliasMaintenanceOtpOrGenericAdminIssuance() {
    assertThatThrownBy(() -> broker.issueCheckedMaintenance(TaskPurpose.SESSION_EXCHANGE))
        .hasMessage("Checked maintenance authentication unavailable")
        .hasNoCause();
    assertThatThrownBy(() -> broker.issue(TaskPurpose.SESSION_EXCHANGE))
        .hasMessage("Native account actor issuance remains gated")
        .hasNoCause();
    assertThat(issuer.calls.get()).isZero();
  }
}
