package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class KeycloakIdentitySessionExchangeTest {

  private static final String TOKEN_URL = "https://identity.example/token";

  @Mock private RestTemplate restTemplate;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private JwtDecoder jwtDecoder;

  private KeycloakIdentitySessionExchange exchange;

  @BeforeEach
  void setUp() {
    exchange = new KeycloakIdentitySessionExchange(restTemplate, identityClientConfig, jwtDecoder);
    ReflectionTestUtils.setField(exchange, "sessionExchangeClientId", "backend-session-exchange");
    ReflectionTestUtils.setField(exchange, "sessionExchangeClientSecret", "secret");
    ReflectionTestUtils.setField(
        exchange, "sessionExchangeServiceSubject", "exchange-service-subject");
    ReflectionTestUtils.setField(exchange, "keycloakAppClientId", "app");
    when(identityClientConfig.getOpenIdConnectUrl("/token")).thenReturn(TOKEN_URL);
  }

  @Test
  void exchangeForUserShouldMapProviderResponseAndKeepGrantFieldsInsideAdapter() {
    var providerResponse =
        new KeycloakLoginResponseDTO(
            "access-token", 300, 600, "refresh-token", "Bearer", "session-state", "openid profile");
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "admin-token")));
    when(jwtDecoder.decode("admin-token"))
        .thenReturn(
            adminToken(
                "exchange-service-subject",
                "backend-session-exchange",
                List.of("session-exchange")));
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(providerResponse));

    var result = exchange.exchangeForUser("identity-user-id");

    assertThat(result)
        .hasValueSatisfying(
            session -> {
              assertThat(session.accessToken()).isEqualTo("access-token");
              assertThat(session.expiresIn()).isEqualTo(300);
              assertThat(session.refreshExpiresIn()).isEqualTo(600);
              assertThat(session.refreshToken()).isEqualTo("refresh-token");
              assertThat(session.tokenType()).isEqualTo("Bearer");
              assertThat(session.sessionState()).isEqualTo("session-state");
              assertThat(session.scope()).isEqualTo("openid profile");
            });

    var adminRequest = requestCaptor();
    verify(restTemplate).postForEntity(eq(TOKEN_URL), adminRequest.capture(), eq(Map.class));
    assertThat(form(adminRequest).getFirst("grant_type")).isEqualTo("client_credentials");
    assertThat(form(adminRequest).getFirst("client_id")).isEqualTo("backend-session-exchange");
    assertThat(form(adminRequest).getFirst("client_secret")).isEqualTo("secret");
    assertThat(form(adminRequest)).doesNotContainKeys("username", "password");

    var exchangeRequest = requestCaptor();
    verify(restTemplate)
        .postForEntity(
            eq(TOKEN_URL), exchangeRequest.capture(), eq(KeycloakLoginResponseDTO.class));
    assertThat(form(exchangeRequest).getFirst("grant_type"))
        .isEqualTo("urn:ietf:params:oauth:grant-type:token-exchange");
    assertThat(form(exchangeRequest).getFirst("client_id")).isEqualTo("app");
    assertThat(form(exchangeRequest).getFirst("subject_token")).isEqualTo("admin-token");
    assertThat(form(exchangeRequest).getFirst("requested_subject")).isEqualTo("identity-user-id");
  }

  @Test
  void exchangeForUserShouldReturnEmptyWhenAdminLoginHasNoToken() {
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));

    assertThat(exchange.exchangeForUser("identity-user-id")).isEmpty();

    verify(restTemplate, never())
        .postForEntity(eq(TOKEN_URL), any(), eq(KeycloakLoginResponseDTO.class));
  }

  @Test
  void exchangeForUserShouldReturnEmptyWhenProviderExchangeFails() {
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "admin-token")));
    when(jwtDecoder.decode("admin-token"))
        .thenReturn(
            adminToken(
                "exchange-service-subject",
                "backend-session-exchange",
                List.of("session-exchange")));
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(new IllegalStateException("identity provider unavailable"));

    assertThat(exchange.exchangeForUser("identity-user-id")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrong-subject",
        "wrong-azp",
        "expired",
        "missing-role",
        "technical",
        "realm-admin",
        "resource-realm-admin",
        "unexpected-management-role",
        "foreign-resource"
      })
  void exchangeForUserShouldRejectUnexpectedAdminToken(String invalidClaim) {
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "admin-token")));
    var subject = "wrong-subject".equals(invalidClaim) ? "other" : "exchange-service-subject";
    var azp = "wrong-azp".equals(invalidClaim) ? "other" : "backend-session-exchange";
    var roles =
        switch (invalidClaim) {
          case "missing-role" -> List.of("view-users");
          case "technical" -> List.of("session-exchange", "technical");
          case "realm-admin" -> List.of("session-exchange", "realm-admin");
          default -> List.of("session-exchange");
        };
    var expiry =
        "expired".equals(invalidClaim)
            ? Instant.now().minusSeconds(1)
            : Instant.now().plusSeconds(300);
    var managementRoles =
        switch (invalidClaim) {
          case "resource-realm-admin" ->
              List.of("manage-users", "view-users", "query-users", "view-realm", "realm-admin");
          case "unexpected-management-role" ->
              List.of("manage-users", "view-users", "query-users", "view-realm", "impersonation");
          default -> List.of("manage-users", "view-users", "query-users", "view-realm");
        };
    var resourceAccess =
        "foreign-resource".equals(invalidClaim)
            ? Map.of(
                "realm-management", Map.of("roles", managementRoles),
                "account", Map.of("roles", List.of("manage-account")))
            : Map.of("realm-management", Map.of("roles", managementRoles));
    when(jwtDecoder.decode("admin-token"))
        .thenReturn(adminToken(subject, azp, roles, resourceAccess, expiry));

    assertThat(exchange.exchangeForUser("identity-user-id")).isEmpty();

    verify(restTemplate, never())
        .postForEntity(eq(TOKEN_URL), any(), eq(KeycloakLoginResponseDTO.class));
  }

  private static Jwt adminToken(String subject, String azp, List<String> roles) {
    return adminToken(subject, azp, roles, Instant.now().plusSeconds(300));
  }

  private static Jwt adminToken(String subject, String azp, List<String> roles, Instant expiresAt) {
    return adminToken(subject, azp, roles, Map.of(), expiresAt);
  }

  private static Jwt adminToken(
      String subject,
      String azp,
      List<String> roles,
      Map<String, Map<String, List<String>>> resourceAccess,
      Instant expiresAt) {
    return Jwt.withTokenValue("admin-token")
        .header("alg", "none")
        .subject(subject)
        .claim("azp", azp)
        .claim("realm_access", Map.of("roles", roles))
        .claim("resource_access", resourceAccess)
        .issuedAt(Instant.now().minusSeconds(10))
        .expiresAt(expiresAt)
        .build();
  }

  @SuppressWarnings("rawtypes")
  private static ArgumentCaptor<HttpEntity> requestCaptor() {
    return ArgumentCaptor.forClass(HttpEntity.class);
  }

  @SuppressWarnings("unchecked")
  private static MultiValueMap<String, String> form(ArgumentCaptor<HttpEntity> captor) {
    return (MultiValueMap<String, String>) captor.getValue().getBody();
  }
}
