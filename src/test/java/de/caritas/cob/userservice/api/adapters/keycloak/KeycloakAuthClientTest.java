package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.resource.RealmResource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KeycloakAuthClientTest {

  private static final String USERNAME = "testuser";
  private static final String PASSWORD = "oldP@66w0rd!";
  private static final String REFRESH_TOKEN = "s09djf0w9ejf09wsejf09wjef";

  @InjectMocks private KeycloakAuthClient keycloakAuthClient;

  @Mock private RestTemplate restTemplate;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private KeycloakClient keycloakClient;

  private LogbackCaptor logCaptor;

  @BeforeEach
  void setUp() {
    when(identityClientConfig.getOpenIdConnectUrl(anyString()))
        .thenReturn("https://keycloak/token-or-logout");
    setField(keycloakAuthClient, "keycloakClientId", "app");
    logCaptor = LogbackCaptor.forClass(KeycloakAuthClient.class);
  }

  @AfterEach
  void tearDown() {
    logCaptor.detach();
  }

  @Test
  void loginUser_Should_ReturnResponseBody_When_KeycloakLoginSucceeds() {
    var loginResponse = new KeycloakLoginResponseDTO();
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(new ResponseEntity<>(loginResponse, HttpStatus.OK));

    assertThat(keycloakAuthClient.loginUser(USERNAME, PASSWORD), is(loginResponse));
  }

  @Test
  void loginUser_Should_ThrowBadRequest_When_KeycloakLoginFails() {
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(new RestClientResponseException("error", 500, "text", null, null, null));

    assertThrows(BadRequestException.class, () -> keycloakAuthClient.loginUser(USERNAME, PASSWORD));
  }

  @Test
  void verifyIgnoringOtp_Should_ReturnTrue_When_MissingTotpButPasswordCorrect() {
    // The vendored otp-config SPI (ADR-013) answers with this exact JSON error
    // contract when the password was accepted but the second factor is absent.
    var exception = mock(HttpClientErrorException.class);
    when(exception.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
    when(exception.getResponseBodyAsString())
        .thenReturn("{\"error\":\"invalid_grant\",\"error_description\":\"Missing totp\"}");
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(exception);

    assertTrue(keycloakAuthClient.verifyIgnoringOtp(USERNAME, PASSWORD));
  }

  @Test
  void verifyIgnoringOtp_Should_ReturnFalse_When_MissingTotpAppearsOutsideTheJsonContract() {
    // A body merely CONTAINING the phrase (e.g. inside another message) must not
    // count as password-verified — only the SPI's exact error_description does.
    var exception = mock(HttpClientErrorException.class);
    when(exception.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
    when(exception.getResponseBodyAsString())
        .thenReturn(
            "{\"error\":\"invalid_grant\",\"error_description\":\"Account disabled; Missing totp enrollment\"}");
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(exception);

    assertThat(keycloakAuthClient.verifyIgnoringOtp(USERNAME, PASSWORD), is(false));
  }

  @Test
  void verifyIgnoringOtp_Should_ReturnFalse_When_OtherBadRequest() {
    var exception = mock(HttpClientErrorException.class);
    when(exception.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
    when(exception.getResponseBodyAsString()).thenReturn("Invalid credentials");
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(exception);

    assertThat(keycloakAuthClient.verifyIgnoringOtp(USERNAME, PASSWORD), is(false));
  }

  @Test
  void verifyIgnoringOtp_Should_ReturnTrueAndLogout_When_LoginSucceedsWithRefreshToken() {
    var loginResponse = mock(KeycloakLoginResponseDTO.class);
    when(loginResponse.getRefreshToken()).thenReturn(REFRESH_TOKEN);
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(new ResponseEntity<>(loginResponse, HttpStatus.OK));
    when(authenticatedUser.getAccessToken()).thenReturn("token");
    when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
        .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

    assertTrue(keycloakAuthClient.verifyIgnoringOtp(USERNAME, PASSWORD));

    verify(restTemplate).postForEntity(anyString(), any(), eq(Void.class));
  }

  @Test
  void verifyIgnoringOtp_Should_ReturnTrueWithoutLogout_When_ResponseBodyIsNull() {
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(ResponseEntity.status(HttpStatus.OK).body(null));

    assertTrue(keycloakAuthClient.verifyIgnoringOtp(USERNAME, PASSWORD));

    verify(restTemplate, never()).postForEntity(anyString(), any(), eq(Void.class));
  }

  @Test
  void verifyIgnoringOtp_Should_ReturnTrueWithoutLogout_When_RefreshTokenIsNull() {
    var loginResponse = mock(KeycloakLoginResponseDTO.class);
    when(loginResponse.getRefreshToken()).thenReturn(null);
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(new ResponseEntity<>(loginResponse, HttpStatus.OK));

    assertTrue(keycloakAuthClient.verifyIgnoringOtp(USERNAME, PASSWORD));

    verify(restTemplate, never()).postForEntity(anyString(), any(), eq(Void.class));
  }

  @Test
  void logoutUser_Should_ReturnTrue_When_KeycloakReturnsNoContent() {
    when(authenticatedUser.getAccessToken()).thenReturn("token");
    when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
        .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

    assertTrue(keycloakAuthClient.logoutUser(REFRESH_TOKEN));
  }

  @Test
  void logoutUser_Should_ReturnFalseAndLogError_When_KeycloakReturnsUnexpectedStatus() {
    when(authenticatedUser.getAccessToken()).thenReturn("token");
    when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
        .thenReturn(new ResponseEntity<>(HttpStatus.BAD_REQUEST));

    assertThat(keycloakAuthClient.logoutUser(REFRESH_TOKEN), is(false));
    assertTrue(logCaptor.contains(Level.ERROR, "Keycloak error: Could not log out user"));
  }

  @Test
  void logoutUser_Should_ReturnFalseAndLogError_When_KeycloakLogoutThrowsException() {
    when(authenticatedUser.getAccessToken()).thenReturn("token");
    when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
        .thenThrow(new RestClientException("keycloak unavailable"));

    assertThat(keycloakAuthClient.logoutUser(REFRESH_TOKEN), is(false));
    assertTrue(logCaptor.contains(Level.ERROR, "Keycloak error: Could not log out user"));
  }

  @Test
  void closeSession_Should_DeleteSession() {
    var realmResource = mock(RealmResource.class);
    when(keycloakClient.getRealmResource()).thenReturn(realmResource);

    keycloakAuthClient.closeSession("sessionId");

    verify(realmResource, times(1)).deleteSession(eq("sessionId"), eq(false));
  }

  @Test
  void verifyWithOtp_Should_ReturnTrueAndLogout_When_LoginWithOtpSucceeds() {
    var loginResponse = new KeycloakLoginResponseDTO();
    loginResponse.setRefreshToken(REFRESH_TOKEN);
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(loginResponse));
    when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
        .thenReturn(ResponseEntity.noContent().build());

    assertTrue(keycloakAuthClient.verifyWithOtp(USERNAME, PASSWORD, "123456"));
  }

  @Test
  void verifyWithOtp_Should_ReturnFalse_When_LoginIsRejected() {
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

    assertThat(keycloakAuthClient.verifyWithOtp(USERNAME, PASSWORD, "123456"), is(false));
  }

  @Test
  void verifyWithOtp_Should_ReturnFalse_When_OtpIsMissingOrWrong() {
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                org.springframework.http.HttpHeaders.EMPTY,
                "{\"error_description\":\"Missing totp\"}".getBytes(),
                null));

    assertThat(keycloakAuthClient.verifyWithOtp(USERNAME, PASSWORD, ""), is(false));
  }
}
