package de.caritas.cob.userservice.api.adapters.keycloak;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.OTP_INFO_DTO;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphabetic;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.keycloak.KeycloakException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerification;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.RandomStringUtils;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KeycloakServiceTest {

  private final String USER_ID = "asdh89sdfsjodifjsdf";
  private final String OLD_PW = "oldP@66w0rd!";
  private final String NEW_PW = "newP@66w0rd!";
  private final String REFRESH_TOKEN = "s09djf0w9ejf09wsejf09wjef";
  private static final String BEARER_TOKEN = "token";
  private static final String USERNAME = "testuser";

  @org.mockito.Spy @InjectMocks private KeycloakService keycloakService;

  @Mock private RestTemplate restTemplate;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private UserAccountInputValidator userAccountInputValidator;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private KeycloakClient keycloakClient;

  @Mock
  private de.caritas.cob.userservice.api.config.auth.TaskIdentityTokenVerifier
      taskIdentityTokenVerifier;

  @Mock
  @SuppressWarnings("unused")
  private KeycloakMapper keycloakMapper;

  /** Satisfies {@link InjectMocks}; replaced with a real client in {@link #setup()}. */
  @Mock private KeycloakAuthClient keycloakAuthClient;

  @Mock private UsernameTranscoder usernameTranscoder;
  @Mock private UserHelper userHelper;

  @Mock UsersResource usersResource;

  EasyRandom easyRandom = new EasyRandom();

  private LogbackCaptor logCaptor;
  private LogbackCaptor authLogCaptor;

  @BeforeEach
  public void setup() throws NoSuchFieldException, SecurityException {
    var otp =
        new de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials(
            "backend-otp", "test-only-secret", "otp-subject");
    when(identityClientConfig.getTaskIdentity(
            de.caritas.cob.userservice.api.config.auth.TaskIdentity.OTP))
        .thenReturn(otp);
    org.mockito.Mockito.doReturn(new IdentityLogin(BEARER_TOKEN, 300, 0, null))
        .when(keycloakService)
        .loginTask(any());
    givenAKeycloakLoginUrl();
    givenAKeycloakLogoutUrl();
    var realAuthClient =
        new KeycloakAuthClient(restTemplate, authenticatedUser, identityClientConfig);
    setField(realAuthClient, "keycloakClientId", "app");
    setField(keycloakService, "keycloakAuthClient", realAuthClient);
    setField(keycloakService, "usernameTranscoder", usernameTranscoder);
    setField(keycloakService, "multiTenancyEnabled", false);
    setField(keycloakService, "genericKeycloakError", "An unexpected Keycloak error occurred");
    logCaptor = LogbackCaptor.forClass(KeycloakService.class);
    authLogCaptor = LogbackCaptor.forClass(KeycloakAuthClient.class);
    when(usernameTranscoder.decodeUsername(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @AfterEach
  public void tearDown() {
    logCaptor.detach();
    authLogCaptor.detach();
  }

  @Test
  public void login_Should_MapKeycloakResponseToProviderNeutralCredentials() {
    KeycloakLoginResponseDTO loginResponseDTO =
        new EasyRandom().nextObject(KeycloakLoginResponseDTO.class);
    when(restTemplate.postForEntity(
            ArgumentMatchers.anyString(),
            any(),
            ArgumentMatchers.<Class<KeycloakLoginResponseDTO>>any()))
        .thenReturn(new ResponseEntity<>(loginResponseDTO, HttpStatus.OK));

    IdentityLogin response = keycloakService.login(USER_ID, OLD_PW);

    assertThat(response.accessToken(), is(loginResponseDTO.getAccessToken()));
    assertThat(response.expiresIn(), is(loginResponseDTO.getExpiresIn()));
    assertThat(response.refreshExpiresIn(), is(loginResponseDTO.getRefreshExpiresIn()));
    assertThat(response.refreshToken(), is(loginResponseDTO.getRefreshToken()));
  }

  @Test
  public void login_Should_ReturnBadRequest_When_KeycloakLoginFails() {
    var exception =
        new RestClientResponseException("some exception", 500, "text", null, null, null);
    when(restTemplate.postForEntity(
            ArgumentMatchers.anyString(),
            any(),
            ArgumentMatchers.<Class<KeycloakLoginResponseDTO>>any()))
        .thenThrow(exception);

    try {
      keycloakService.login(USER_ID, OLD_PW);
      fail("Expected exception: BadRequestException");
    } catch (BadRequestException badRequestException) {
      assertTrue(true, "Excepted BadRequestException thrown");
    }
  }

  @Test
  public void explicitSessionLogoutUsesOwnTokensWithoutRequestScope() {
    when(authenticatedUser.getAccessToken())
        .thenThrow(new IllegalStateException("No request scope"));
    when(restTemplate.postForEntity(anyString(), any(), ArgumentMatchers.<Class<Void>>any()))
        .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

    assertTrue(keycloakService.logout("synthetic-own-refresh", "synthetic-own-access"));

    ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .postForEntity(anyString(), request.capture(), ArgumentMatchers.<Class<Void>>any());
    org.assertj.core.api.Assertions.assertThat(
            request.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer synthetic-own-access");
    org.assertj.core.api.Assertions.assertThat(
            ((org.springframework.util.MultiValueMap<?, ?>) request.getValue().getBody())
                .get("refresh_token"))
        .isEqualTo(java.util.List.of("synthetic-own-refresh"));
    verify(authenticatedUser, never()).getAccessToken();
  }

  @Test
  public void logout_Should_ReturnTrue_When_KeycloakLoginWasSuccessful() {
    when(restTemplate.postForEntity(
            ArgumentMatchers.anyString(), any(), ArgumentMatchers.<Class<Void>>any()))
        .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

    assertTrue(keycloakService.logout(REFRESH_TOKEN));
  }

  @Test
  public void logout_Should_ReturnFalseAndLogError_WhenKeycloakLogoutFailsWithException() {
    RestClientException exception = new RestClientException("error");
    when(restTemplate.postForEntity(ArgumentMatchers.anyString(), any(), any()))
        .thenThrow(exception);

    boolean response = keycloakService.logout(REFRESH_TOKEN);

    assertFalse(response);
    assertTrue(authLogCaptor.contains(Level.ERROR, "Keycloak error: Could not log out user"));
  }

  @Test
  public void logout_Should_ReturnFalseAndLogError_When_KeycloakLogoutFails() {
    when(restTemplate.postForEntity(
            ArgumentMatchers.anyString(), any(), ArgumentMatchers.<Class<Void>>any()))
        .thenReturn(new ResponseEntity<>(HttpStatus.BAD_REQUEST));

    boolean response = keycloakService.logout(REFRESH_TOKEN);

    assertFalse(response);
    assertTrue(authLogCaptor.contains(Level.ERROR, "Keycloak error: Could not log out user"));
  }

  private UserRepresentation givenUserRepresentationWithFilledEmail(String email) {
    var userRepresentation = mock(UserRepresentation.class);
    when(userRepresentation.getEmail()).thenReturn(email);
    return userRepresentation;
  }

  private UserRepresentation givenUserRepresentationWithNullEmail() {
    UserRepresentation userRepresentation = givenUserRepresentationWithFilledEmail(null);
    return userRepresentation;
  }

  private UsersResource givenUsersResource(UserResource userResource) {
    var usersResource = mock(UsersResource.class);
    when(usersResource.get("userId")).thenReturn(userResource);
    when(usersResource.search(anyString(), eq(0), eq(Integer.MAX_VALUE))).thenReturn(List.of());
    return usersResource;
  }

  private UserResource givenUserResource(UserRepresentation userRepresentation) {
    var userResource = mock(UserResource.class);
    when(userResource.toRepresentation()).thenReturn(userRepresentation);
    return userResource;
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void getOtpCredential_Should_Return_Response_When_RequestWasSuccessful() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var credential = new IdentityOtpCredential(true, "secret", "QrCode", IdentityOtpType.APP);
    var entity = new ResponseEntity(OTP_INFO_DTO, HttpStatus.OK);
    when(this.keycloakClient.get(anyString(), any(), any())).thenReturn(entity);
    when(keycloakMapper.identityOtpCredentialOf(OTP_INFO_DTO)).thenReturn(credential);

    assertEquals(credential, keycloakService.getOtpCredential(USERNAME));

    verify(keycloakClient).get(anyString(), any(), eq(OtpInfoDTO.class));
    verifyNoInteractions(outboundHttpMetrics);
  }

  @Test
  public void getOtpCredential_Should_Throw_When_SuccessfulResponseHasNoBody() {
    for (var status : new HttpStatus[] {HttpStatus.OK, HttpStatus.NO_CONTENT}) {
      when(keycloakClient.get(anyString(), any(), eq(OtpInfoDTO.class)))
          .thenReturn(new ResponseEntity<OtpInfoDTO>(status));

      assertThrows(KeycloakException.class, () -> keycloakService.getOtpCredential(USERNAME));
    }
    verifyNoInteractions(keycloakMapper);
  }

  @Test
  public void getOtpCredential_Should_Preserve_ValidInactiveCredential() {
    var info = new OtpInfoDTO().otpSetup(false).otpSecret("setup-secret");
    var credential = new IdentityOtpCredential(false, "setup-secret", null, null);
    when(keycloakClient.get(anyString(), any(), eq(OtpInfoDTO.class)))
        .thenReturn(ResponseEntity.ok(info));
    when(keycloakMapper.identityOtpCredentialOf(info)).thenReturn(credential);

    assertEquals(credential, keycloakService.getOtpCredential(USERNAME));
  }

  @Test
  public void getOtpCredential_Should_Throw_When_RequestHasAnError() {
    assertThrows(
        RestClientException.class,
        () -> {
          when(this.keycloakClient.get(any(), any(), any()))
              .thenThrow(new RestClientException("Fail test case"));

          keycloakService.getOtpCredential(USERNAME);
        });
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void
      getOtpCredential_Should_ObtainFreshDedicatedOtpGrantOnce_When_FirstRequestIsUnauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var credential = new IdentityOtpCredential(true, "secret", "QrCode", IdentityOtpType.APP);
    var unauthorized =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.UNAUTHORIZED);
    var entity = new ResponseEntity(OTP_INFO_DTO, HttpStatus.OK);
    org.mockito.Mockito.doReturn(
            new IdentityLogin("stale-token", 300, 0, null),
            new IdentityLogin("fresh-token", 300, 0, null))
        .when(keycloakService)
        .loginTask(any());
    when(keycloakClient.get(eq("stale-token"), any(), any())).thenThrow(unauthorized);
    when(keycloakClient.get(eq("fresh-token"), any(), any())).thenReturn(entity);
    when(keycloakMapper.identityOtpCredentialOf(OTP_INFO_DTO)).thenReturn(credential);

    assertEquals(credential, keycloakService.getOtpCredential(USERNAME));

    verify(keycloakClient, never()).refreshAdminSession();
    verify(keycloakService, times(2)).loginTask(any());
    verify(outboundHttpMetrics).recordRetry("keycloak", "otp-fetch");
  }

  @Test
  public void getOtpCredential_Should_RetryOnlyOnce_When_BothRequestsAreUnauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var unauthorized =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.UNAUTHORIZED);
    org.mockito.Mockito.doReturn(
            new IdentityLogin("stale-token", 300, 0, null),
            new IdentityLogin("fresh-token", 300, 0, null))
        .when(keycloakService)
        .loginTask(any());
    when(keycloakClient.get(any(), any(), any())).thenThrow(unauthorized);

    assertThrows(
        org.springframework.web.client.HttpClientErrorException.class,
        () -> keycloakService.getOtpCredential(USERNAME));

    verify(keycloakClient, never()).refreshAdminSession();
    verify(keycloakClient, times(2)).get(any(), any(), any());
    verify(outboundHttpMetrics).recordRetry("keycloak", "otp-fetch");
  }

  @Test
  public void
      getOtpCredential_Should_NotObtainFreshDedicatedOtpGrant_When_RequestFailsWithNon401() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var badRequest =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.BAD_REQUEST);
    when(keycloakClient.get(any(), any(), any())).thenThrow(badRequest);

    assertThrows(
        org.springframework.web.client.HttpClientErrorException.class,
        () -> keycloakService.getOtpCredential(USERNAME));

    verify(keycloakClient, never()).refreshAdminSession();
    verify(keycloakClient).get(any(), any(), any());
    verifyNoInteractions(outboundHttpMetrics);
  }

  @Test
  public void
      setUpOtpCredential_ShouldNot_ThrowInternalServerErrorException_When_RequestWasSuccessfully() {

    assertDoesNotThrow(
        () ->
            keycloakService.setUpOtpCredential(USERNAME, randomAlphabetic(8), randomAlphabetic(8)));
  }

  @Test
  public void
      setUpOtpCredential_Should_ObtainFreshDedicatedOtpGrantOnce_When_FirstRequestIsUnauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var unauthorized =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.UNAUTHORIZED);
    org.mockito.Mockito.doReturn(
            new IdentityLogin("stale-token", 300, 0, null),
            new IdentityLogin("fresh-token", 300, 0, null))
        .when(keycloakService)
        .loginTask(any());
    when(keycloakClient.putForEntity(eq("stale-token"), any(), any(), any()))
        .thenThrow(unauthorized);
    when(keycloakClient.putForEntity(eq("fresh-token"), any(), any(), any()))
        .thenReturn(new ResponseEntity<>(HttpStatus.OK));

    assertThat(keycloakService.setUpOtpCredential(USERNAME, "123456", "secret"), is(true));

    verify(keycloakClient, never()).refreshAdminSession();
    verify(keycloakService, times(2)).loginTask(any());
    verify(outboundHttpMetrics).recordRetry("keycloak", "otp-setup");
  }

  @Test
  public void
      deleteOtpCredential_Should_Not_ThrowBadRequestException_When_RequestWasSuccessfully() {

    assertDoesNotThrow(() -> keycloakService.deleteOtpCredential(USERNAME));
  }

  @Test
  public void
      deleteOtpCredential_Should_ObtainFreshDedicatedOtpGrantOnce_When_FirstRequestIsUnauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var unauthorized =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.UNAUTHORIZED);
    org.mockito.Mockito.doReturn(
            new IdentityLogin("stale-token", 300, 0, null),
            new IdentityLogin("fresh-token", 300, 0, null))
        .when(keycloakService)
        .loginTask(any());
    when(keycloakClient.delete(eq("stale-token"), any(), eq(Void.class))).thenThrow(unauthorized);
    when(keycloakClient.delete(eq("fresh-token"), any(), eq(Void.class)))
        .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

    assertDoesNotThrow(() -> keycloakService.deleteOtpCredential(USERNAME));

    verify(keycloakClient, never()).refreshAdminSession();
    verify(keycloakService, times(2)).loginTask(any());
    verify(outboundHttpMetrics).recordRetry("keycloak", "otp-delete");
  }

  @Test
  public void otpRequests_ShouldUseDecodedKeycloakUsername() {
    var encodedUsername = "enc.ORSXG5BAOVZWK4Q.";
    when(usernameTranscoder.decodeUsername(encodedUsername)).thenReturn(USERNAME);
    when(identityClientConfig.getOtpUrl(anyString(), eq(USERNAME))).thenReturn("otp-url");
    when(keycloakClient.get(anyString(), anyString(), eq(OtpInfoDTO.class)))
        .thenReturn(new ResponseEntity<>(OTP_INFO_DTO, HttpStatus.OK));

    keycloakService.getOtpCredential(encodedUsername);
    keycloakService.setUpOtpCredential(encodedUsername, "123456", "secret");
    keycloakService.deleteOtpCredential(encodedUsername);
    keycloakService.initiateEmailVerification(encodedUsername, "mail@example.com");
    keycloakService.finishEmailVerification(encodedUsername, "123456");

    verify(identityClientConfig).getOtpUrl("/fetch-otp-setup-info/{username}", USERNAME);
    verify(identityClientConfig).getOtpUrl("/setup-otp/{username}", USERNAME);
    verify(identityClientConfig).getOtpUrl("/delete-otp/{username}", USERNAME);
    verify(identityClientConfig).getOtpUrl("/send-verification-mail/{username}", USERNAME);
    verify(identityClientConfig).getOtpUrl("/setup-otp-mail/{username}", USERNAME);
    verify(usernameTranscoder, times(5)).decodeUsername(encodedUsername);
  }

  private void givenAKeycloakLoginUrl() {
    when(identityClientConfig.getOpenIdConnectUrl(anyString()))
        .thenReturn(
            "https://caritas.local/auth/realms/online-beratung/protocol/openid-connect/token");
  }

  private void givenAKeycloakLogoutUrl() {
    when(identityClientConfig.getOpenIdConnectUrl(anyString()))
        .thenReturn(
            "https://caritas.local/auth/realms/online-beratung/protocol/openid-connect/logout");
  }

  private UserResource givenPostCreateAttributeUpdate(
      UsersResource usersResource, Response response, String userId) {
    var userResource = mock(UserResource.class);
    var storedRepresentation = new UserRepresentation();
    storedRepresentation.setAttributes(new HashMap<>());
    when(response.getLocation()).thenReturn(createdUserLocation(userId));
    when(usersResource.get(userId)).thenReturn(userResource);
    when(userResource.toRepresentation()).thenReturn(storedRepresentation);
    return userResource;
  }

  private URI createdUserLocation(String userId) {
    return URI.create("http://keycloak/admin/realms/online-beratung/users/" + userId);
  }

  private void givenAFailingCreateUserResponse(int status, String body) {
    UsersResource failingUsersResource = mock(UsersResource.class);
    Response response = mock(Response.class);
    when(response.getStatus()).thenReturn(status);
    when(response.readEntity(String.class)).thenReturn(body);
    when(failingUsersResource.create(any())).thenReturn(response);
    when(keycloakClient.getUsersResource()).thenReturn(failingUsersResource);
  }

  private UsersResource givenUsersResourceWithAnyUserId(UserResource userResource) {
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.get(any())).thenReturn(userResource);
    return usersResource;
  }

  private UserResource givenUserResourceWithRepresentation(UserRepresentation userRepresentation) {
    UserResource userResource = mock(UserResource.class);
    when(userResource.toRepresentation()).thenReturn(userRepresentation);
    return userResource;
  }

  private UserRepresentation givenUserRepresentation(String email) {
    UserRepresentation userRepresentation = mock(UserRepresentation.class);
    when(userRepresentation.getEmail()).thenReturn(email);
    return userRepresentation;
  }

  /**
   * Stubs the post-create lookup performed by {@code
   * KeycloakService#updateIdentityAttributesAfterCreate}: it fetches the freshly created user via
   * {@code getUsersResource().get(id)} and mutates its {@link UserRepresentation} attributes. The
   * created-user id is derived from the (unstubbed) response location, hence {@code get(any())}.
   */
  private void givenAUserResourceForCreatedUser(UsersResource usersResource) {
    var userResource = mock(UserResource.class);
    when(userResource.toRepresentation()).thenReturn(new UserRepresentation());
    when(usersResource.get(any())).thenReturn(userResource);
  }

  private String givenADuplicatedEmailErrorMessage() {
    var emailError = RandomStringUtils.random(32);
    when(identityClientConfig.getErrorMessageDuplicatedEmail()).thenReturn(emailError);

    return emailError;
  }

  private String givenADuplicatedUserErrorMessage() {
    var userError = RandomStringUtils.random(32);
    when(identityClientConfig.getErrorMessageDuplicatedUsername()).thenReturn(userError);

    return userError;
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-06
  // ---------------------------------------------------------------------------

  @Test
  public void
      verifyPasswordIgnoringSecondFactor_Should_ReturnTrue_When_MissingTotpButPasswordCorrect() {
    var exception = mock(org.springframework.web.client.HttpClientErrorException.class);
    when(exception.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
    when(exception.getResponseBodyAsString())
        .thenReturn("{\"error\":\"invalid_grant\",\"error_description\":\"Missing totp\"}");
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(exception);

    boolean result = keycloakService.verifyPasswordIgnoringSecondFactor(USERNAME, OLD_PW);

    assertThat(result, is(true));
  }

  @Test
  public void verifyPasswordIgnoringSecondFactor_Should_ReturnFalse_When_OtherBadRequest() {
    var exception = mock(org.springframework.web.client.HttpClientErrorException.class);
    when(exception.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
    when(exception.getResponseBodyAsString()).thenReturn("Invalid credentials");
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(exception);

    boolean result = keycloakService.verifyPasswordIgnoringSecondFactor(USERNAME, OLD_PW);

    assertThat(result, is(false));
  }

  @Test
  public void
      verifyPasswordIgnoringSecondFactor_Should_ReturnTrueAndLogout_When_LoginSucceedsWithRefreshToken() {
    var loginResponse = mock(KeycloakLoginResponseDTO.class);
    when(loginResponse.getRefreshToken()).thenReturn(REFRESH_TOKEN);
    ResponseEntity<KeycloakLoginResponseDTO> responseEntity =
        new ResponseEntity<>(loginResponse, HttpStatus.OK);
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(responseEntity);
    when(authenticatedUser.getAccessToken()).thenReturn("token");
    ResponseEntity<Void> logoutResponse = new ResponseEntity<>(HttpStatus.NO_CONTENT);
    when(restTemplate.postForEntity(anyString(), any(), eq(Void.class))).thenReturn(logoutResponse);

    boolean result = keycloakService.verifyPasswordIgnoringSecondFactor(USERNAME, OLD_PW);

    assertThat(result, is(true));
    verify(restTemplate).postForEntity(anyString(), any(), eq(Void.class));
  }

  @Test
  public void
      verifyPasswordIgnoringSecondFactor_Should_ReturnTrueWithoutLogout_When_NoRefreshToken() {
    var loginResponse = mock(KeycloakLoginResponseDTO.class);
    when(loginResponse.getRefreshToken()).thenReturn(null);
    ResponseEntity<KeycloakLoginResponseDTO> responseEntity =
        new ResponseEntity<>(loginResponse, HttpStatus.OK);
    when(restTemplate.postForEntity(anyString(), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(responseEntity);

    boolean result = keycloakService.verifyPasswordIgnoringSecondFactor(USERNAME, OLD_PW);

    assertThat(result, is(true));
    verify(restTemplate, org.mockito.Mockito.never())
        .postForEntity(anyString(), any(), eq(Void.class));
  }

  @Test
  public void initiateEmailVerification_Should_ReturnTypedSuccess_When_RequestSucceeds() {
    when(keycloakClient.putForEntity(any(), any(), any(), any()))
        .thenReturn(new ResponseEntity<>(HttpStatus.OK));

    var result = keycloakService.initiateEmailVerification(USERNAME, "mail@example.com");

    assertThat(result.started(), is(true));
    assertNull(result.failureMessage());
    verify(keycloakClient).putForEntity(any(), any(), any(), any());
  }

  @Test
  public void initiateEmailVerification_Should_ReturnTypedFailure_When_ProviderRejects() {
    when(keycloakClient.putForEntity(any(), any(), any(), any()))
        .thenThrow(new RestClientException("Keycloak said no"));

    var result = keycloakService.initiateEmailVerification(USERNAME, "mail@example.com");

    assertThat(result.started(), is(false));
    assertThat(result.failureMessage().contains("Keycloak said no"), is(true));
  }

  @Test
  public void
      initiateEmailVerification_Should_RecordOperationSpecificRetry_OnInitialUnauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var unauthorized =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.UNAUTHORIZED);
    org.mockito.Mockito.doReturn(
            new IdentityLogin("stale-token", 300, 0, null),
            new IdentityLogin("fresh-token", 300, 0, null))
        .when(keycloakService)
        .loginTask(any());
    when(keycloakClient.putForEntity(eq("stale-token"), any(), any(), any()))
        .thenThrow(unauthorized);
    when(keycloakClient.putForEntity(eq("fresh-token"), any(), any(), any()))
        .thenReturn(new ResponseEntity<>(HttpStatus.OK));

    var result = keycloakService.initiateEmailVerification(USERNAME, "mail@example.com");

    assertThat(result.started(), is(true));
    verify(keycloakClient, never()).refreshAdminSession();
    verify(keycloakClient, times(2)).putForEntity(any(), any(), any(), any());
    verify(outboundHttpMetrics).recordRetry("keycloak", "email-verification-start");
  }

  @Test
  public void finishEmailVerification_Should_ReturnMappedSuccess_When_RequestSucceeds() {
    ResponseEntity<de.caritas.cob.userservice.api.model.SuccessWithEmail> responseEntity =
        new ResponseEntity<>(
            new de.caritas.cob.userservice.api.model.SuccessWithEmail(), HttpStatus.OK);
    when(keycloakClient.postForEntity(
            any(), any(), any(), eq(de.caritas.cob.userservice.api.model.SuccessWithEmail.class)))
        .thenReturn(responseEntity);
    var expected = new IdentityEmailVerification(false, true, false, "mail@example.com");
    when(keycloakMapper.identityEmailVerificationOf(responseEntity)).thenReturn(expected);

    var result = keycloakService.finishEmailVerification(USERNAME, "123456");

    assertThat(result, is(expected));
    verify(keycloakClient).postForEntity(any(), any(), any(), any());
  }

  @Test
  public void finishEmailVerification_Should_ReturnMappedError_When_KeycloakRejects() {
    var exception =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.BAD_REQUEST);
    when(keycloakClient.postForEntity(any(), any(), any(), any())).thenThrow(exception);
    var expected = new IdentityEmailVerification(false, false, true, null);
    when(keycloakMapper.identityEmailVerificationOf(exception)).thenReturn(expected);

    var result = keycloakService.finishEmailVerification(USERNAME, "123456");

    assertThat(result, is(expected));
    verify(keycloakClient, never()).refreshAdminSession();
  }

  @Test
  public void finishEmailVerification_Should_NotRetryInvalidCodeUnauthorized() {
    var invalidCode =
        org.springframework.web.client.HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            new HttpHeaders(),
            "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid code\"}"
                .getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    when(keycloakClient.postForEntity(any(), any(), any(), any())).thenThrow(invalidCode);
    var expected = new IdentityEmailVerification(false, false, true, null);
    when(keycloakMapper.identityEmailVerificationOf(invalidCode)).thenReturn(expected);

    var result = keycloakService.finishEmailVerification(USERNAME, "invalid-code");

    assertThat(result, is(expected));
    verify(keycloakClient, times(1)).postForEntity(any(), any(), any(), any());
    verify(keycloakClient, never()).refreshAdminSession();
  }

  @Test
  public void finishEmailVerification_Should_RecordOperationSpecificRetry_OnInitialUnauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    var unauthorized =
        org.springframework.web.client.HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED, "Unauthorized", headers, new byte[0], StandardCharsets.UTF_8);
    var responseEntity =
        new ResponseEntity<>(
            new de.caritas.cob.userservice.api.model.SuccessWithEmail(), HttpStatus.CREATED);
    var expected = new IdentityEmailVerification(true, false, false, "mail@example.com");
    org.mockito.Mockito.doReturn(
            new IdentityLogin("stale-token", 300, 0, null),
            new IdentityLogin("fresh-token", 300, 0, null))
        .when(keycloakService)
        .loginTask(any());
    when(keycloakClient.postForEntity(eq("stale-token"), any(), any(), any()))
        .thenThrow(unauthorized);
    when(keycloakClient.postForEntity(
            eq("fresh-token"),
            any(),
            any(),
            eq(de.caritas.cob.userservice.api.model.SuccessWithEmail.class)))
        .thenReturn(responseEntity);
    when(keycloakMapper.identityEmailVerificationOf(responseEntity)).thenReturn(expected);

    var result = keycloakService.finishEmailVerification(USERNAME, "123456");

    assertThat(result, is(expected));
    verify(keycloakClient, never()).refreshAdminSession();
    verify(keycloakClient, times(2)).postForEntity(any(), any(), any(), any());
    verify(outboundHttpMetrics).recordRetry("keycloak", "email-verification-finish");
  }

  @Test
  public void finishEmailVerification_Should_ReportPersistentBearerChallengeAsServiceFailure() {
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    var unauthorized =
        org.springframework.web.client.HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED, "Unauthorized", headers, new byte[0], StandardCharsets.UTF_8);
    org.mockito.Mockito.doReturn(
            new IdentityLogin("stale-token", 300, 0, null),
            new IdentityLogin("fresh-token", 300, 0, null))
        .when(keycloakService)
        .loginTask(any());
    when(keycloakClient.postForEntity(any(), any(), any(), any())).thenThrow(unauthorized);

    assertThrows(
        ServiceUnavailableException.class,
        () -> keycloakService.finishEmailVerification(USERNAME, "valid-code"));
    verify(keycloakClient, times(2)).postForEntity(any(), any(), any(), any());
    verify(keycloakClient, never()).refreshAdminSession();
  }

  @Test
  public void finishEmailVerification_Should_RetryBearerChallenge_When_CombinedInOneField() {
    var headers = new HttpHeaders();
    headers.add(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"otp, code\", Bearer");

    assertPersistentBearerChallengeIsRetriedOnce(headers);
  }

  @Test
  public void finishEmailVerification_Should_RetryBearerChallenge_When_InRepeatedField() {
    var headers = new HttpHeaders();
    headers.add(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"otp\"");
    headers.add(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"oriso\"");

    assertPersistentBearerChallengeIsRetriedOnce(headers);
  }

  private void assertPersistentBearerChallengeIsRetriedOnce(HttpHeaders headers) {
    var unauthorized =
        org.springframework.web.client.HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED, "Unauthorized", headers, new byte[0], StandardCharsets.UTF_8);
    org.mockito.Mockito.doReturn(
            new IdentityLogin("stale-token", 300, 0, null),
            new IdentityLogin("fresh-token", 300, 0, null))
        .when(keycloakService)
        .loginTask(any());
    when(keycloakClient.postForEntity(any(), any(), any(), any())).thenThrow(unauthorized);

    assertThrows(
        ServiceUnavailableException.class,
        () -> keycloakService.finishEmailVerification(USERNAME, "valid-code"));
    verify(keycloakClient, times(2)).postForEntity(any(), any(), any(), any());
    verify(keycloakClient, never()).refreshAdminSession();
  }

  @Test
  public void finishEmailVerification_Should_NotRetry_When_BearerOnlyInsideQuotedValue() {
    var headers = new HttpHeaders();
    headers.add(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"otp, Bearer\"");
    var invalidCode =
        org.springframework.web.client.HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED, "Unauthorized", headers, new byte[0], StandardCharsets.UTF_8);
    when(keycloakClient.postForEntity(any(), any(), any(), any())).thenThrow(invalidCode);
    var expected = new IdentityEmailVerification(false, false, true, null);
    when(keycloakMapper.identityEmailVerificationOf(invalidCode)).thenReturn(expected);

    var result = keycloakService.finishEmailVerification(USERNAME, "invalid-code");

    assertThat(result, is(expected));
    verify(keycloakClient, times(1)).postForEntity(any(), any(), any(), any());
    verify(keycloakClient, never()).refreshAdminSession();
  }

  private UserResource givenUserResourceWithRealmRoles(String... roleNames) {
    RoleScopeResource roleScopeResource = mock(RoleScopeResource.class);
    var roleRepresentations =
        java.util.Arrays.stream(roleNames)
            .map(
                name -> {
                  RoleRepresentation role = mock(RoleRepresentation.class);
                  when(role.getName()).thenReturn(name);
                  return role;
                })
            .collect(java.util.stream.Collectors.toList());
    when(roleScopeResource.listAll()).thenReturn(roleRepresentations);
    RoleMappingResource roleMappingResource = mock(RoleMappingResource.class);
    when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
    UserResource userResource = mock(UserResource.class);
    when(userResource.roles()).thenReturn(roleMappingResource);
    return userResource;
  }

  // ---------------------------------------------------------------------------
  // Extended branch coverage — 2026-07-07 (isPasswordPolicyViolation / isPasswordPolicyMessage)
  // ---------------------------------------------------------------------------

  private void givenResetPasswordThrows(Exception exception) {
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    doThrow(exception).when(userResource).resetPassword(any());
  }

  @Test
  public void setUpOtpCredential_Should_ReturnFalse_When_KeycloakReturnsUnauthorized() {
    var exception = mock(org.springframework.web.client.HttpClientErrorException.class);
    when(exception.getStatusCode()).thenReturn(HttpStatus.UNAUTHORIZED);
    when(keycloakClient.putForEntity(any(), any(), any(), any())).thenThrow(exception);

    boolean result = keycloakService.setUpOtpCredential(USERNAME, "123456", "secret");

    assertThat(result, is(false));
  }

  @Test
  public void setUpOtpCredential_Should_RethrowException_When_StatusIsNotUnauthorized() {
    var exception =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.BAD_REQUEST);
    when(keycloakClient.putForEntity(any(), any(), any(), any())).thenThrow(exception);

    assertThrows(
        org.springframework.web.client.HttpClientErrorException.class,
        () -> keycloakService.setUpOtpCredential(USERNAME, "123456", "secret"));
  }

  // ---------------------------------------------------------------------------
  // Every Keycloak user update must keep the attributes Keycloak already holds. The userId
  // attribute feeds the custom userId JWT claim the AgencyService scopes restricted admins by;
  // losing it turned every agency list of an edited Beratungsstellen-Admin into a 403.
  // ---------------------------------------------------------------------------

  private UserRepresentation givenStoredUserWithAttributes(String email) {
    var existing = new UserRepresentation();
    existing.setId("userId");
    existing.setEmail(email);
    existing.setAttributes(
        new LinkedHashMap<>(
            Map.of(
                "userId", singletonList("userId"),
                "locale", singletonList("de"),
                "tenantId", singletonList("1"))));
    return existing;
  }

  private UserResource givenUserResourceHolding(UserRepresentation existing) {
    UserResource userResource = givenUserResourceWithRepresentation(existing);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    return userResource;
  }

  private Map<String, List<String>> attributesSentTo(UserResource userResource) {
    var representationCaptor = ArgumentCaptor.forClass(UserRepresentation.class);
    verify(userResource).update(representationCaptor.capture());
    return representationCaptor.getValue().getAttributes();
  }
}
