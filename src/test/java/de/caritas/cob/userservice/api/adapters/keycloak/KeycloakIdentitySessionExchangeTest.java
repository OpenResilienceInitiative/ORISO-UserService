package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class KeycloakIdentitySessionExchangeTest {

  private static final String TOKEN_URL = "https://identity.example/token";

  @Mock private RestTemplate restTemplate;
  @Mock private IdentityClientConfig identityClientConfig;

  private KeycloakIdentitySessionExchange exchange;

  @BeforeEach
  void setUp() {
    exchange = new KeycloakIdentitySessionExchange(restTemplate, identityClientConfig);
    ReflectionTestUtils.setField(exchange, "keycloakAdminUsername", "admin");
    ReflectionTestUtils.setField(exchange, "keycloakAdminPassword", "secret");
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
    assertThat(form(adminRequest).getFirst("grant_type")).isEqualTo("password");
    assertThat(form(adminRequest).getFirst("client_id")).isEqualTo("app");
    assertThat(form(adminRequest).getFirst("username")).isEqualTo("admin");
    assertThat(form(adminRequest).getFirst("password")).isEqualTo("secret");

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
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(new IllegalStateException("identity provider unavailable"));

    assertThat(exchange.exchangeForUser("identity-user-id")).isEmpty();
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
