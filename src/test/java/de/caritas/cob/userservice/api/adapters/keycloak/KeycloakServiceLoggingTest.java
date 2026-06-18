package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class KeycloakServiceLoggingTest {

  private static final String REFRESH_TOKEN = "secret-refresh-token-value";
  private static final String PASSWORD = "secret-password-value";
  private static final String USERNAME = "user";

  @Mock private RestTemplate restTemplate;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private UserAccountInputValidator userAccountInputValidator;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private KeycloakClient keycloakClient;
  @Mock private KeycloakMapper keycloakMapper;
  @Mock private UserHelper userHelper;

  private KeycloakService keycloakService;
  private Logger logger;
  private ListAppender<ILoggingEvent> listAppender;

  @BeforeEach
  void setUp() {
    keycloakService =
        new KeycloakService(
            restTemplate,
            authenticatedUser,
            userAccountInputValidator,
            identityClientConfig,
            keycloakClient,
            keycloakMapper,
            userHelper);
    setField(keycloakService, "keycloakClientId", "app");

    lenient().when(authenticatedUser.getAccessToken()).thenReturn("access-token");
    lenient()
        .when(identityClientConfig.getOpenIdConnectUrl(anyString()))
        .thenReturn("https://keycloak/logout");

    logger = (Logger) LoggerFactory.getLogger(KeycloakService.class);
    listAppender = new ListAppender<>();
    listAppender.start();
    logger.addAppender(listAppender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(listAppender);
  }

  @Test
  void loginUser_ShouldRedactPassword_WhenRequestBodyIsRenderedForDebugLogging() {
    var loginResponse = new KeycloakLoginResponseDTO();
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(new ResponseEntity<>(loginResponse, HttpStatus.OK));

    assertThat(keycloakService.loginUser(USERNAME, PASSWORD)).isSameAs(loginResponse);

    var requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .postForEntity(anyString(), requestCaptor.capture(), eq(KeycloakLoginResponseDTO.class));
    @SuppressWarnings("unchecked")
    var request = (HttpEntity<MultiValueMap<String, String>>) requestCaptor.getValue();

    assertThat(request.getBody().getFirst("password")).isEqualTo(PASSWORD);
    assertThat(request.getBody().toString()).doesNotContain(PASSWORD);
    assertThat(request.getBody().toString()).contains("[REDACTED]");
  }

  @Test
  void logoutUser_ShouldNotLogRefreshToken_WhenKeycloakLogoutThrowsException() {
    when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
        .thenThrow(new RestClientException("keycloak unavailable"));

    assertThat(keycloakService.logoutUser(REFRESH_TOKEN)).isFalse();

    assertRefreshTokenWasNotLogged();
  }

  @Test
  void logoutUser_ShouldNotLogRefreshToken_WhenKeycloakLogoutReturnsUnexpectedStatus() {
    when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
        .thenReturn(new ResponseEntity<>(HttpStatus.BAD_REQUEST));

    assertThat(keycloakService.logoutUser(REFRESH_TOKEN)).isFalse();

    assertRefreshTokenWasNotLogged();
  }

  @Test
  void logoutUser_ShouldRedactRefreshToken_WhenRequestBodyIsRenderedForDebugLogging() {
    when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
        .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

    assertThat(keycloakService.logoutUser(REFRESH_TOKEN)).isTrue();

    var requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate).postForEntity(anyString(), requestCaptor.capture(), eq(Void.class));
    @SuppressWarnings("unchecked")
    var request = (HttpEntity<MultiValueMap<String, String>>) requestCaptor.getValue();

    assertThat(request.getBody().getFirst("refresh_token")).isEqualTo(REFRESH_TOKEN);
    assertThat(request.getBody().toString()).doesNotContain(REFRESH_TOKEN);
    assertThat(request.getBody().toString()).contains("[REDACTED]");
  }

  private void assertRefreshTokenWasNotLogged() {
    assertThat(listAppender.list).isNotEmpty();
    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(message -> message.contains(REFRESH_TOKEN));
  }
}
