package de.caritas.cob.userservice.api.adapters.keycloak;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.EMAIL_NOT_AVAILABLE;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.OTP_INFO_DTO;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.RandomStringUtils.random;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphabetic;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.keycloak.KeycloakException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwner;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.RandomStringUtils;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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

  @InjectMocks private KeycloakService keycloakService;

  @Mock private RestTemplate restTemplate;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private UserAccountInputValidator userAccountInputValidator;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private KeycloakClient keycloakClient;

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
    givenAKeycloakLoginUrl();
    givenAKeycloakLogoutUrl();
    var realAuthClient =
        new KeycloakAuthClient(restTemplate, authenticatedUser, identityClientConfig);
    setField(realAuthClient, "keycloakClientId", "app");
    setField(keycloakService, "keycloakAuthClient", realAuthClient);
    setField(keycloakService, "usernameTranscoder", usernameTranscoder);
    setField(keycloakService, "multiTenancyEnabled", false);
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
  public void changePassword_Should_ReturnTrue_When_KeycloakPasswordChangeWasSuccessful() {
    var usersResource = mock(UsersResource.class);
    var userResource = mock(UserResource.class);
    when(usersResource.get(USER_ID)).thenReturn(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    assertTrue(keycloakService.changePassword(USER_ID, NEW_PW));
  }

  @Test
  public void
      changePassword_Should_ReturnFalseAndLogError_When_KeycloakPasswordChangeFailsWithException() {
    assertFalse(keycloakService.changePassword(USER_ID, NEW_PW));
    assertTrue(logCaptor.contains(Level.INFO, "Could not change password for user with id"));
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

  @Test
  public void changeEmailAddress_Should_useServicesCorrectly() {
    when(this.authenticatedUser.getUserId()).thenReturn("userId");
    UserRepresentation userRepresentation =
        givenUserRepresentationWithFilledEmail(RandomStringUtils.randomAlphanumeric(8));
    UserResource userResource = givenUserResource(userRepresentation);
    UsersResource usersResource = givenUsersResource(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    var email = RandomStringUtils.randomAlphabetic(8);

    this.keycloakService.changeEmailAddress(email);

    verify(this.userAccountInputValidator, times(1)).validateEmailAddress(email);
    verify(this.authenticatedUser, times(1)).getUserId();
  }

  private UserRepresentation givenUserRepresentationWithFilledEmail(String email) {
    var userRepresentation = mock(UserRepresentation.class);
    when(userRepresentation.getEmail()).thenReturn(email);
    return userRepresentation;
  }

  @Test
  public void changeEmailAddress_Should_NotThrowNPEIfUserDoesNotHaveEmailDefinedInKeycloak() {
    when(this.authenticatedUser.getUserId()).thenReturn("userId");
    UserRepresentation userRepresentation = givenUserRepresentationWithNullEmail();
    UserResource userResource = givenUserResource(userRepresentation);
    UsersResource usersResource = givenUsersResource(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    var email = RandomStringUtils.randomAlphabetic(8);

    this.keycloakService.changeEmailAddress(email);

    verify(this.userAccountInputValidator, times(1)).validateEmailAddress(email);
    verify(this.authenticatedUser, times(1)).getUserId();
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
  public void deleteEmailAddress_Should_useServicesCorrectly() {
    // Current-user email deletion remains an email-address operation and writes the configured
    // dummy address through the existing update path.
    var userId = random(16);
    when(authenticatedUser.getUserId()).thenReturn(userId);
    when(userHelper.getDummyEmail(userId)).thenReturn("dummy");
    UserRepresentation userRepresentation = givenUserRepresentation("oldEmail");
    UserResource userResource = givenUserResourceWithRepresentation(userRepresentation);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    keycloakService.deleteEmailAddress();

    verify(authenticatedUser).getUserId();
    verify(userHelper).getDummyEmail(userId);
    verify(userRepresentation).setEmail("dummy");
    verify(userResource).update(userRepresentation);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void getOtpCredential_Should_Return_Response_When_RequestWasSuccessful() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);
    var entity = new ResponseEntity(OTP_INFO_DTO, HttpStatus.OK);
    when(this.keycloakClient.get(anyString(), any(), any())).thenReturn(entity);

    assertEquals(OTP_INFO_DTO, keycloakService.getOtpCredential(USERNAME));

    verifyNoInteractions(outboundHttpMetrics);
  }

  @Test
  public void getOtpCredential_Should_Throw_When_RequestHasAnError() {
    assertThrows(
        RestClientException.class,
        () -> {
          when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);
          when(this.keycloakClient.get(any(), any(), any()))
              .thenThrow(new RestClientException("Fail test case"));

          keycloakService.getOtpCredential(USERNAME);
        });
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void getOtpCredential_Should_RefreshAdminSessionOnce_When_FirstRequestIsUnauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var unauthorized =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.UNAUTHORIZED);
    var entity = new ResponseEntity(OTP_INFO_DTO, HttpStatus.OK);
    when(keycloakClient.getBearerToken()).thenReturn("stale-token").thenReturn("fresh-token");
    when(keycloakClient.get(eq("stale-token"), any(), any())).thenThrow(unauthorized);
    when(keycloakClient.get(eq("fresh-token"), any(), any())).thenReturn(entity);

    assertEquals(OTP_INFO_DTO, keycloakService.getOtpCredential(USERNAME));

    verify(keycloakClient).refreshAdminSession();
    verify(keycloakClient, times(2)).getBearerToken();
    verify(outboundHttpMetrics).recordRetry("keycloak", "admin-session-refresh");
  }

  @Test
  public void getOtpCredential_Should_RetryOnlyOnce_When_BothRequestsAreUnauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var unauthorized =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.UNAUTHORIZED);
    when(keycloakClient.getBearerToken()).thenReturn("stale-token").thenReturn("fresh-token");
    when(keycloakClient.get(any(), any(), any())).thenThrow(unauthorized);

    assertThrows(
        org.springframework.web.client.HttpClientErrorException.class,
        () -> keycloakService.getOtpCredential(USERNAME));

    verify(keycloakClient).refreshAdminSession();
    verify(keycloakClient, times(2)).get(any(), any(), any());
    verify(outboundHttpMetrics).recordRetry("keycloak", "admin-session-refresh");
  }

  @Test
  public void getOtpCredential_Should_NotRefreshAdminSession_When_RequestFailsWithNon401() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    var badRequest =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.BAD_REQUEST);
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);
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
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);

    assertDoesNotThrow(
        () ->
            keycloakService.setUpOtpCredential(USERNAME, randomAlphabetic(8), randomAlphabetic(8)));
  }

  @Test
  public void setUpOtpCredential_Should_RefreshAdminSessionOnce_When_FirstRequestIsUnauthorized() {
    var unauthorized =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.UNAUTHORIZED);
    when(keycloakClient.getBearerToken()).thenReturn("stale-token").thenReturn("fresh-token");
    when(keycloakClient.putForEntity(eq("stale-token"), any(), any(), any()))
        .thenThrow(unauthorized);
    when(keycloakClient.putForEntity(eq("fresh-token"), any(), any(), any()))
        .thenReturn(new ResponseEntity<>(HttpStatus.OK));

    assertThat(keycloakService.setUpOtpCredential(USERNAME, "123456", "secret"), is(true));

    verify(keycloakClient).refreshAdminSession();
    verify(keycloakClient, times(2)).getBearerToken();
  }

  @Test
  public void
      deleteOtpCredential_Should_Not_ThrowBadRequestException_When_RequestWasSuccessfully() {
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);

    assertDoesNotThrow(() -> keycloakService.deleteOtpCredential(USERNAME));
  }

  @Test
  public void deleteOtpCredential_Should_RefreshAdminSessionOnce_When_FirstRequestIsUnauthorized() {
    var unauthorized =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.UNAUTHORIZED);
    when(keycloakClient.getBearerToken()).thenReturn("stale-token").thenReturn("fresh-token");
    when(keycloakClient.delete(eq("stale-token"), any(), eq(Void.class))).thenThrow(unauthorized);
    when(keycloakClient.delete(eq("fresh-token"), any(), eq(Void.class)))
        .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

    assertDoesNotThrow(() -> keycloakService.deleteOtpCredential(USERNAME));

    verify(keycloakClient).refreshAdminSession();
    verify(keycloakClient, times(2)).getBearerToken();
  }

  @Test
  public void otpRequests_ShouldUseDecodedKeycloakUsername() {
    var encodedUsername = "enc.ORSXG5BAOVZWK4Q.";
    when(usernameTranscoder.decodeUsername(encodedUsername)).thenReturn(USERNAME);
    when(identityClientConfig.getOtpUrl(anyString(), eq(USERNAME))).thenReturn("otp-url");
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);
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

  @Test
  public void createUser_Should_createExpectedUser_When_keycloakReturnsCreated() {
    UserDTO userDTO = new EasyRandom().nextObject(UserDTO.class);
    UsersResource usersResource = mock(UsersResource.class);
    Response response = mock(Response.class);
    when(response.getStatus()).thenReturn(HttpStatus.CREATED.value());
    when(usersResource.create(any())).thenReturn(response);
    // On a successful create, production resolves the new user and persists mandatory attributes
    // via getUsersResource().get(id).toRepresentation() (updateIdentityAttributesAfterCreate).
    givenAUserResourceForCreatedUser(usersResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    givenPostCreateAttributeUpdate(usersResource, response, USER_ID);

    CreatedIdentity keycloakUser = this.keycloakService.createUser(userDTO);

    assertThat(keycloakUser, notNullValue());
    assertThat(keycloakUser.getUserId(), is(USER_ID));
  }

  @Test
  public void createUser_Should_createExpectedTenantAwareUser_When_keycloakReturnsCreated() {
    TenantContext.setCurrentTenant(1L);
    setField(keycloakService, "multiTenancyEnabled", true);

    UserDTO userDTO = new EasyRandom().nextObject(UserDTO.class);
    userDTO.setTenantId(1L);
    UsersResource usersResource = mock(UsersResource.class);
    Response response = mock(Response.class);
    when(response.getStatus()).thenReturn(HttpStatus.CREATED.value());
    when(usersResource.create(any())).thenReturn(response);
    // On a successful create, production resolves the new user and persists mandatory attributes
    // via getUsersResource().get(id).toRepresentation() (updateIdentityAttributesAfterCreate).
    givenAUserResourceForCreatedUser(usersResource);
    when(this.keycloakClient.getUsersResource()).thenReturn(usersResource);
    givenPostCreateAttributeUpdate(usersResource, response, USER_ID);

    CreatedIdentity keycloakUser = this.keycloakService.createUser(userDTO);

    assertThat(keycloakUser, notNullValue());
    assertThat(keycloakUser.getUserId(), is(USER_ID));

    ArgumentCaptor<UserRepresentation> argumentCaptor =
        ArgumentCaptor.forClass(UserRepresentation.class);
    verify(usersResource, times(1)).create(argumentCaptor.capture());

    Assertions.assertEquals(
        argumentCaptor.getValue().getAttributes().get("tenantId").get(0),
        TenantContext.getCurrentTenant().toString());

    TenantContext.clear();
  }

  @Test
  public void createUser_Should_updateIdentityAttributes_When_keycloakReturnsCreated() {
    TenantContext.setCurrentTenant(7L);
    setField(keycloakService, "multiTenancyEnabled", true);

    var userDTO = new UserDTO();
    userDTO.setUsername("encoded-user");
    userDTO.setEmail("user@example.org");
    userDTO.setTenantId(7L);
    when(usernameTranscoder.decodeUsername("encoded-user")).thenReturn("decoded-user");

    var usersResource = mock(UsersResource.class);
    var userResource = mock(UserResource.class);
    var response = mock(Response.class);
    var storedRepresentation = new UserRepresentation();
    storedRepresentation.setAttributes(new HashMap<>());
    when(response.getStatus()).thenReturn(HttpStatus.CREATED.value());
    when(response.getLocation()).thenReturn(createdUserLocation(USER_ID));
    when(usersResource.create(any())).thenReturn(response);
    when(usersResource.get(USER_ID)).thenReturn(userResource);
    when(userResource.toRepresentation()).thenReturn(storedRepresentation);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    var keycloakUser = this.keycloakService.createUser(userDTO);

    assertThat(keycloakUser.getUserId(), is(USER_ID));

    var representationCaptor = ArgumentCaptor.forClass(UserRepresentation.class);
    verify(userResource).update(representationCaptor.capture());
    var attributes = representationCaptor.getValue().getAttributes();
    assertThat(attributes.get("userId").get(0), is(USER_ID));
    assertThat(attributes.get("tenantId").get(0), is("7"));
    assertThat(attributes.get("username").get(0), is("decoded-user"));
    assertThat(attributes.get("userName").get(0), is("decoded-user"));

    TenantContext.clear();
  }

  @Test
  public void createUser_Should_createUserWithDefaultLocale() {
    var userDTO = easyRandom.nextObject(UserDTO.class);
    userDTO.setPreferredLanguage(null);
    var usersResource = mock(UsersResource.class);
    var response = mock(Response.class);
    when(response.getStatus()).thenReturn(HttpStatus.CREATED.value());
    when(usersResource.create(any())).thenReturn(response);
    // On a successful create, production resolves the new user and persists mandatory attributes
    // via getUsersResource().get(id).toRepresentation() (updateIdentityAttributesAfterCreate).
    givenAUserResourceForCreatedUser(usersResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    givenPostCreateAttributeUpdate(usersResource, response, USER_ID);

    var keycloakUser = keycloakService.createUser(userDTO);

    assertThat(keycloakUser.getUserId(), is(USER_ID));

    var argumentCaptor = ArgumentCaptor.forClass(UserRepresentation.class);
    verify(usersResource).create(argumentCaptor.capture());

    var locales = argumentCaptor.getValue().getAttributes().get("locale");
    assertEquals("de", locales.get(0));
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

  @Test
  public void
      createUser_Should_throwExpectedStatusException_When_keycloakResponseHasEmailErrorMessage() {
    var emailError = givenADuplicatedEmailErrorMessage();
    givenADuplicatedUserErrorMessage();
    UserDTO userDTO = new EasyRandom().nextObject(UserDTO.class);
    Response response = mock(Response.class);
    // Production reads the raw Keycloak error body as a String (see
    // KeycloakService#handleCreateKeycloakUserError); the message is matched case-insensitively
    // against the configured duplicated-email marker.
    when(response.readEntity(String.class)).thenReturn(emailError);
    when(usersResource.create(any())).thenReturn(response);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    try {
      this.keycloakService.createUser(userDTO);
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders(), notNullValue());
      assertThat(e.getCustomHttpHeaders().get("X-Reason").get(0), is(EMAIL_NOT_AVAILABLE.name()));
    }
  }

  @Test
  public void
      createUser_Should_throwExpectedStatusException_When_keycloakResponseHasUsernameErrorMessage() {
    givenADuplicatedEmailErrorMessage();
    var keycloakErrorUsername = givenADuplicatedUserErrorMessage();
    UserDTO userDTO = new EasyRandom().nextObject(UserDTO.class);
    UsersResource usersResource = mock(UsersResource.class);
    Response response = mock(Response.class);
    // Production reads the raw Keycloak error body as a String (see
    // KeycloakService#handleCreateKeycloakUserError) and matches it against the configured
    // duplicated-username marker.
    when(response.readEntity(String.class)).thenReturn(keycloakErrorUsername);
    when(usersResource.create(any())).thenReturn(response);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    try {
      this.keycloakService.createUser(userDTO);
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders(), notNullValue());
      assertThat(
          e.getCustomHttpHeaders().get("X-Reason").get(0), is(USERNAME_NOT_AVAILABLE.name()));
    }
  }

  @Test
  public void createUser_Should_throwExpectedResponseException_When_keycloakMailUpdateFails() {
    givenADuplicatedEmailErrorMessage();
    var keycloakErrorUsername = givenADuplicatedUserErrorMessage();
    UserDTO userDTO = new EasyRandom().nextObject(UserDTO.class);
    UsersResource usersResource = mock(UsersResource.class);
    Response response = mock(Response.class);
    // Production reads the raw Keycloak error body as a String (see
    // KeycloakService#handleCreateKeycloakUserError) and matches it against the configured
    // duplicated-username marker.
    when(response.readEntity(String.class)).thenReturn(keycloakErrorUsername);
    when(usersResource.create(any())).thenReturn(response);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    try {
      this.keycloakService.createUser(userDTO);
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders(), notNullValue());
      assertThat(
          e.getCustomHttpHeaders().get("X-Reason").get(0), is(USERNAME_NOT_AVAILABLE.name()));
    }
  }

  @Test
  public void createUser_Should_ThrowInternalServerException_When_errorIsUnknown() {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          // The configured duplicated-account markers must be stubbed (non-null) because
          // production lower-cases them unconditionally; see the production NPE flag in
          // KeycloakService#handleCreateKeycloakUserError lines 417/423.
          givenADuplicatedEmailErrorMessage();
          givenADuplicatedUserErrorMessage();
          UsersResource usersResource = mock(UsersResource.class);
          Response response = mock(Response.class);
          when(usersResource.create(any())).thenReturn(response);
          // An error body matching neither the duplicated-email nor the duplicated-username
          // marker must fall through to a generic InternalServerErrorException.
          when(response.readEntity(String.class)).thenReturn("unexpected keycloak failure");
          when(keycloakClient.getUsersResource()).thenReturn(usersResource);
          UserDTO userDTO = new EasyRandom().nextObject(UserDTO.class);

          this.keycloakService.createUser(userDTO);
        });
  }

  @Test
  public void createUser_Should_notThrowNpe_When_duplicateMarkersAreNull_And_fallBackToStatus() {
    // Guards the null-safe errorMatchesMarker(...): when the configured duplicate-email/username
    // markers are unset (null), production must NOT NPE while lower-casing them. Instead it falls
    // through to the status-based handling and still maps a 409 CONFLICT carrying "email" to a
    // CustomValidationHttpStatusException (EMAIL_NOT_AVAILABLE), not an opaque 500.
    UserDTO userDTO = new EasyRandom().nextObject(UserDTO.class);
    Response response = mock(Response.class);
    when(identityClientConfig.getErrorMessageDuplicatedEmail()).thenReturn(null);
    when(identityClientConfig.getErrorMessageDuplicatedUsername()).thenReturn(null);
    when(response.getStatus()).thenReturn(HttpStatus.CONFLICT.value());
    when(response.readEntity(String.class)).thenReturn("User exists with same email");
    when(usersResource.create(any())).thenReturn(response);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    CustomValidationHttpStatusException exception =
        assertThrows(
            CustomValidationHttpStatusException.class,
            () -> this.keycloakService.createUser(userDTO));

    assertThat(
        exception.getCustomHttpHeaders().get("X-Reason").get(0), is(EMAIL_NOT_AVAILABLE.name()));
  }

  @Test
  public void
      createUser_Should_throwInternalServerError_When_duplicateMarkersAreNull_And_statusUnknown() {
    // Same null-marker guard, but with a non-conflict status and an unrelated error body: the
    // method must fall through to a generic InternalServerErrorException rather than NPE.
    UserDTO userDTO = new EasyRandom().nextObject(UserDTO.class);
    Response response = mock(Response.class);
    when(identityClientConfig.getErrorMessageDuplicatedEmail()).thenReturn(null);
    when(identityClientConfig.getErrorMessageDuplicatedUsername()).thenReturn(null);
    when(response.getStatus()).thenReturn(HttpStatus.INTERNAL_SERVER_ERROR.value());
    when(response.readEntity(String.class)).thenReturn("unexpected keycloak failure");
    when(usersResource.create(any())).thenReturn(response);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    assertThrows(
        InternalServerErrorException.class, () -> this.keycloakService.createUser(userDTO));
  }

  @Test
  public void isUsernameAvailable_ShouldSearchDecodedAndEncodedUsernameExactlyOnce() {
    String inputUsername = "enc.KVXGS4LVMU......";
    String decodedUsername = "NotUnique";
    String encodedUsername = "enc.JZXW6......";
    UsersResource usersResource = mock(UsersResource.class);
    when(usernameTranscoder.decodeUsername(inputUsername)).thenReturn(decodedUsername);
    when(usernameTranscoder.encodeUsername(inputUsername)).thenReturn(encodedUsername);
    when(usersResource.search(decodedUsername)).thenReturn(List.of());
    when(usersResource.search(encodedUsername)).thenReturn(List.of());
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    boolean isAvailable = this.keycloakService.isUsernameAvailable(inputUsername);

    assertThat(isAvailable, is(true));
    verify(usersResource).search(decodedUsername);
    verify(usersResource).search(encodedUsername);
    verify(usersResource, times(2)).search(anyString());
  }

  @Test
  public void isUsernameAvailable_Should_returnFalse_When_DecodedUsernameIsNotAvailable() {
    String notUnique = "NotUnique";
    UserRepresentation userMock = easyRandom.nextObject(UserRepresentation.class);
    userMock.setUsername(notUnique);
    List<UserRepresentation> decodedUserRepresentations = singletonList(userMock);
    List<UserRepresentation> encodedUserRepresentations =
        singletonList(easyRandom.nextObject(UserRepresentation.class));
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.search(any()))
        .thenReturn(decodedUserRepresentations)
        .thenReturn(encodedUserRepresentations);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    when(usernameTranscoder.decodeUsername(any())).thenReturn(notUnique);

    boolean isAvailable = this.keycloakService.isUsernameAvailable(notUnique);

    assertThat(isAvailable, is(false));
  }

  @Test
  public void isUsernameAvailable_Should_returnFalse_When_EncodedUsernameIsNotAvailable() {
    String notUnique = "enc.KVXGS4LVMU......";
    UserRepresentation userMock = easyRandom.nextObject(UserRepresentation.class);
    userMock.setUsername(notUnique);
    List<UserRepresentation> decodedUserRepresentations =
        singletonList(easyRandom.nextObject(UserRepresentation.class));
    List<UserRepresentation> encodedUserRepresentations = singletonList(userMock);
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.search(any()))
        .thenReturn(decodedUserRepresentations)
        .thenReturn(encodedUserRepresentations);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    when(usernameTranscoder.encodeUsername(any())).thenReturn(notUnique);

    boolean isAvailable = this.keycloakService.isUsernameAvailable(notUnique);

    assertThat(isAvailable, is(false));
  }

  @Test
  public void updateRole_Should_throwKeycloakException_When_roleCouldNotBeUpdated() {
    assertThrows(
        KeycloakException.class,
        () -> {
          UserResource userResource = mock(UserResource.class);
          UsersResource usersResource = mock(UsersResource.class);
          when(usersResource.get(anyString())).thenReturn(userResource);
          RoleScopeResource roleScopeResource = mock(RoleScopeResource.class);
          RoleMappingResource roleMappingResource = mock(RoleMappingResource.class);
          when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
          when(userResource.roles()).thenReturn(roleMappingResource);

          RoleRepresentation roleRepresentation =
              new EasyRandom().nextObject(RoleRepresentation.class);
          RoleResource roleResource = mock(RoleResource.class);
          when(roleResource.toRepresentation()).thenReturn(roleRepresentation);
          RolesResource rolesResource = mock(RolesResource.class);
          when(rolesResource.get(any())).thenReturn(roleResource);

          RealmResource realmResource = mock(RealmResource.class);
          when(realmResource.users()).thenReturn(usersResource);
          when(realmResource.roles()).thenReturn(rolesResource);
          when(keycloakClient.getRealmResource()).thenReturn(realmResource);

          this.keycloakService.updateRole("user", "role");
        });
  }

  @Test
  public void updateRole_Should_updateRole_When_roleUpdateIsValid() {
    String validRole = "role";

    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.get(anyString())).thenReturn(userResource);
    RoleScopeResource roleScopeResource = mock(RoleScopeResource.class);
    RoleRepresentation keycloakRoleMock = mock(RoleRepresentation.class);
    // Production verifies the assignment via RoleRepresentation#getName (isRoleAssigned), not
    // toString; otherwise the role appears unassigned and updateRole throws a KeycloakException.
    when(keycloakRoleMock.getName()).thenReturn(validRole);
    when(roleScopeResource.listAll()).thenReturn(singletonList(keycloakRoleMock));
    RoleMappingResource roleMappingResource = mock(RoleMappingResource.class);
    when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
    when(userResource.roles()).thenReturn(roleMappingResource);

    RoleRepresentation roleRepresentation = new EasyRandom().nextObject(RoleRepresentation.class);
    RoleResource roleResource = mock(RoleResource.class);
    when(roleResource.toRepresentation()).thenReturn(roleRepresentation);
    RolesResource rolesResource = mock(RolesResource.class);
    when(rolesResource.get(any())).thenReturn(roleResource);

    RealmResource realmResource = mock(RealmResource.class);
    when(realmResource.users()).thenReturn(usersResource);
    when(realmResource.roles()).thenReturn(rolesResource);
    when(keycloakClient.getRealmResource()).thenReturn(realmResource);

    this.keycloakService.updateRole("user", validRole);

    verify(roleScopeResource, times(1)).add(any());
  }

  @Test
  public void updateRole_Should_RefreshAdminSessionAndRetry_When_Unauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    String validRole = "role";
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.get(anyString())).thenReturn(userResource);
    RoleScopeResource roleScopeResource = mock(RoleScopeResource.class);
    RoleRepresentation assignedRole = mock(RoleRepresentation.class);
    when(assignedRole.getName()).thenReturn(validRole);
    when(roleScopeResource.listAll()).thenReturn(singletonList(assignedRole));
    RoleMappingResource roleMappingResource = mock(RoleMappingResource.class);
    when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
    when(userResource.roles()).thenReturn(roleMappingResource);
    RoleRepresentation roleRepresentation = new EasyRandom().nextObject(RoleRepresentation.class);
    RoleResource roleResource = mock(RoleResource.class);
    when(roleResource.toRepresentation()).thenReturn(roleRepresentation);
    RolesResource rolesResource = mock(RolesResource.class);
    when(rolesResource.get(any())).thenReturn(roleResource);
    RealmResource realmResource = mock(RealmResource.class);
    when(realmResource.users()).thenReturn(usersResource);
    when(realmResource.roles()).thenReturn(rolesResource);
    when(keycloakClient.getRealmResource())
        .thenThrow(new NotAuthorizedException("Bearer"))
        .thenReturn(realmResource);

    keycloakService.updateRole("user", validRole);

    verify(keycloakClient).refreshAdminSession();
    verify(outboundHttpMetrics).recordRetry("keycloak", "admin-session-refresh");
    verify(keycloakClient, times(2)).getRealmResource();
    verify(roleScopeResource).add(any());
  }

  @Test
  public void removeRole_Should_removeRole_When_rolePresent() {
    String validRole = "role";

    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.get(anyString())).thenReturn(userResource);
    RoleScopeResource roleScopeResource = mock(RoleScopeResource.class);
    RoleRepresentation keycloakRoleMock = mock(RoleRepresentation.class);
    when(keycloakRoleMock.getName()).thenReturn(validRole);
    when(roleScopeResource.listAll()).thenReturn(singletonList(keycloakRoleMock));
    when(roleScopeResource.listAll()).thenReturn(singletonList(keycloakRoleMock));
    RoleMappingResource roleMappingResource = mock(RoleMappingResource.class);
    when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
    when(userResource.roles()).thenReturn(roleMappingResource);

    RoleRepresentation roleRepresentation = new EasyRandom().nextObject(RoleRepresentation.class);
    roleRepresentation.setName("role");
    RoleResource roleResource = mock(RoleResource.class);
    when(roleResource.toRepresentation()).thenReturn(roleRepresentation);
    RolesResource rolesResource = mock(RolesResource.class);
    when(rolesResource.get(any())).thenReturn(roleResource);

    RealmResource realmResource = mock(RealmResource.class);
    when(realmResource.users()).thenReturn(usersResource);
    when(realmResource.roles()).thenReturn(rolesResource);
    when(keycloakClient.getRealmResource()).thenReturn(realmResource);

    this.keycloakService.removeRoleIfPresent("user", validRole);

    verify(roleScopeResource, times(1)).remove(any());
  }

  @Test
  public void updateRole_Should_updateUserWithProvidedRole() {
    UserRole validRole = UserRole.USER;

    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.get(anyString())).thenReturn(userResource);
    RoleScopeResource roleScopeResource = mock(RoleScopeResource.class);
    RoleRepresentation keycloakRoleMock = mock(RoleRepresentation.class);
    // Production verifies the assignment via RoleRepresentation#getName (isRoleAssigned), not
    // toString; otherwise the role appears unassigned and updateRole throws a KeycloakException.
    when(keycloakRoleMock.getName()).thenReturn(validRole.getValue());
    when(roleScopeResource.listAll()).thenReturn(singletonList(keycloakRoleMock));
    RoleMappingResource roleMappingResource = mock(RoleMappingResource.class);
    when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
    when(userResource.roles()).thenReturn(roleMappingResource);

    RoleRepresentation roleRepresentation = new EasyRandom().nextObject(RoleRepresentation.class);
    RoleResource roleResource = mock(RoleResource.class);
    when(roleResource.toRepresentation()).thenReturn(roleRepresentation);
    RolesResource rolesResource = mock(RolesResource.class);
    when(rolesResource.get(any())).thenReturn(roleResource);

    RealmResource realmResource = mock(RealmResource.class);
    when(realmResource.users()).thenReturn(usersResource);
    when(realmResource.roles()).thenReturn(rolesResource);
    when(keycloakClient.getRealmResource()).thenReturn(realmResource);

    this.keycloakService.updateRole("user", validRole);

    verify(roleScopeResource, times(1)).add(any());
    verify(rolesResource, times(1)).get(validRole.getValue());
  }

  @Test
  public void updatePassword_Should_callServicesCorrectly() {
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    this.keycloakService.updatePassword("userId", "password");

    verify(keycloakClient, times(1)).getUsersResource();
    verify(usersResource, times(1)).get("userId");
    verify(userResource, times(1)).resetPassword(any());
  }

  @Test
  public void
      updatePassword_Should_throwCustomValidationHttpStatusException_When_passwordPolicyFails() {
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    doThrow(new BadRequestException("Invalid password")).when(userResource).resetPassword(any());

    CustomValidationHttpStatusException exception =
        assertThrows(
            CustomValidationHttpStatusException.class,
            () -> this.keycloakService.updatePassword("userId", "weak"));

    assertThat(exception.getCustomHttpHeaders().get("X-Reason").get(0), is("PASSWORD_NOT_VALID"));
  }

  @Test
  public void updateDummyMail_id_dto_Should_callServicesCorrectly() {
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    when(this.userHelper.getDummyEmail(anyString())).thenReturn("dummy");

    String dummyMail =
        this.keycloakService.updateDummyEmail(
            "userId", new IdentityDummyEmailUpdate("encoded-user", 42L));

    verify(keycloakClient, times(1)).getUsersResource();
    verify(usersResource, times(1)).get("userId");
    verify(userResource, times(1)).update(any());
    assertThat(dummyMail, is("dummy"));
  }

  @Test
  public void updateDummyMail_Should_MapProviderNeutralIdentityMetadata() {
    setField(keycloakService, "multiTenancyEnabled", true);
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    when(userHelper.getDummyEmail("userId")).thenReturn("dummy");
    when(usernameTranscoder.decodeUsername("encoded-user")).thenReturn("decoded-user");

    keycloakService.updateDummyEmail("userId", new IdentityDummyEmailUpdate("encoded-user", 42L));

    var representationCaptor = ArgumentCaptor.forClass(UserRepresentation.class);
    verify(userResource).update(representationCaptor.capture());
    var representation = representationCaptor.getValue();
    assertThat(representation.getUsername(), is("decoded-user"));
    assertThat(representation.getEmail(), is("dummy"));
    assertThat(representation.getAttributes().get("tenantId").get(0), is("42"));
    setField(keycloakService, "multiTenancyEnabled", false);
  }

  @Test
  public void updateUserData_Should_callServicesCorrectly_When_emailIsChangedAndAvailable() {
    UserRepresentation userRepresentation = givenUserRepresentation("email");
    UserResource userResource = givenUserResourceWithRepresentation(userRepresentation);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    UserDTO userDTO = new UserDTO();
    userDTO.setEmail("anotherEmail");

    this.keycloakService.updateUserData("userId", userDTO, "firstName", "lastName");

    verify(userResource, times(1)).update(any());
  }

  @Test
  public void updateUserData_Should_callServicesCorrectly_When_emailIsUnchanged() {
    UserRepresentation userRepresentation = givenUserRepresentation("email");
    UserResource userResource = givenUserResourceWithRepresentation(userRepresentation);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    UserDTO userDTO = new UserDTO();
    userDTO.setEmail("email");

    this.keycloakService.updateUserData("userId", userDTO, "firstName", "lastName");

    verify(userResource, times(1)).update(any());
  }

  @Test
  public void updateUserData_Should_throwCustomException_When_emailIsChangedButNotAvailable() {
    UserRepresentation userRepresentation = givenUserRepresentation("email");
    UserRepresentation otherUserRepresentation = givenUserRepresentation("newemail");
    UserResource userResource = givenUserResourceWithRepresentation(userRepresentation);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(usersResource.search(any(), any(), any()))
        .thenReturn(singletonList(otherUserRepresentation));
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    UserDTO userDTO = new UserDTO();
    userDTO.setEmail("newemail");

    try {
      this.keycloakService.updateUserData("userId", userDTO, "firstName", "lastName");
      fail("Exception was not thrown");
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders().get("X-Reason").get(0), is(EMAIL_NOT_AVAILABLE.name()));
    }
  }

  @Test
  public void rollbackUser_Should_callServicesCorrectly() {
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    this.keycloakService.rollbackUser("userId");

    verify(keycloakClient, times(1)).getUsersResource();
    verify(usersResource, times(1)).get("userId");
    verify(userResource, times(1)).remove();
  }

  @Test
  public void rollbackUser_Should_logError_When_rollbackFails() {
    UserResource userResource = mock(UserResource.class);
    doThrow(new RuntimeException()).when(userResource).remove();
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    this.keycloakService.rollbackUser("userId");

    assertTrue(
        logCaptor.contains(Level.ERROR, "Keycloak error: User could not be removed/rolled back:"));
  }

  @Test
  public void userHasAuthority_Should_returnTrue_When_userHasAuthority() {
    RoleRepresentation roleRepresentation = mock(RoleRepresentation.class);
    when(roleRepresentation.getName()).thenReturn("user");
    RoleScopeResource roleScopeResource = mock(RoleScopeResource.class);
    when(roleScopeResource.listAll()).thenReturn(singletonList(roleRepresentation));
    RoleMappingResource roleMappingResource = mock(RoleMappingResource.class);
    when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
    UserResource userResource = mock(UserResource.class);
    when(userResource.roles()).thenReturn(roleMappingResource);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    boolean hasAuthority =
        this.keycloakService.userHasAuthority("user", AuthorityValue.USER_DEFAULT);

    assertThat(hasAuthority, is(true));
  }

  @Test
  public void userHasAuthority_Should_returnThrowKeycloakException_When_userHasNoRoles() {
    assertThrows(
        KeycloakException.class,
        () -> {
          UserResource userResource = mock(UserResource.class);
          UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
          when(keycloakClient.getUsersResource()).thenReturn(usersResource);

          this.keycloakService.userHasAuthority("user", "authority");
        });
  }

  @Test
  public void userHasAuthority_Should_returnFalse_When_userHasNotAuthority() {
    RoleRepresentation roleRepresentation = mock(RoleRepresentation.class);
    when(roleRepresentation.getName()).thenReturn("user");
    RoleScopeResource roleScopeResource = mock(RoleScopeResource.class);
    when(roleScopeResource.listAll()).thenReturn(singletonList(roleRepresentation));
    RoleMappingResource roleMappingResource = mock(RoleMappingResource.class);
    when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
    UserResource userResource = mock(UserResource.class);
    when(userResource.roles()).thenReturn(roleMappingResource);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    boolean hasAuthority = this.keycloakService.userHasAuthority("user", AuthorityValue.USER_ADMIN);

    assertThat(hasAuthority, is(false));
  }

  @Test
  public void deactivateUser_Should_deactivateUser() {
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = mock(UsersResource.class);
    UserRepresentation userRepresentation = mock(UserRepresentation.class);
    when(userResource.toRepresentation()).thenReturn(userRepresentation);
    when(usersResource.get(any())).thenReturn(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    this.keycloakService.deactivateUser("userId");

    verify(keycloakClient, times(1)).getUsersResource();
    verify(usersResource, times(1)).get("userId");
    verify(userResource, times(1)).toRepresentation();
    verify(userRepresentation, times(1)).setEnabled(false);
    verify(userResource, times(1)).update(userRepresentation);
  }

  @Test
  public void changeEmailAddress_Should_callServicesCorrectly_When_emailIsChangedAndAvailable() {
    // KeycloakService#updateEmail now restores the real Keycloak update: it resolves the user,
    // verifies email availability, sets the new email on the representation and persists it via
    // UserResource#update. Verify the new email is set and the representation is written back.
    UserRepresentation userRepresentation = givenUserRepresentation("oldEmail");
    UserResource userResource = givenUserResourceWithRepresentation(userRepresentation);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    this.keycloakService.updateEmail("userId", "anotherEmail");

    verify(userRepresentation).setEmail("anotherEmail");
    ArgumentCaptor<UserRepresentation> captor = ArgumentCaptor.forClass(UserRepresentation.class);
    verify(userResource).update(captor.capture());
    assertThat(captor.getValue(), is(userRepresentation));
  }

  @Test
  public void changeLanguage_ShouldNotChangeLanguageIfLanguageExistInKeycloak() {
    // given
    UserRepresentation userRepresentation = givenUserRepresentation("email");
    UserResource userResource = givenUserResourceWithRepresentation(userRepresentation);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    HashMap<String, List<String>> attributeMap = Maps.newHashMap();
    attributeMap.put("locale", Lists.newArrayList("de"));
    when(userRepresentation.getAttributes()).thenReturn(attributeMap);

    // when
    this.keycloakService.changeLanguage("userId", "de");

    // then
    verify(userResource, Mockito.never()).update(userRepresentation);
  }

  private UsersResource givenUsersResourceWithAnyUserId(UserResource userResource) {
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.get(any())).thenReturn(userResource);
    return usersResource;
  }

  @Test
  public void changeLanguage_ShouldChangeLanguageIfLanguageDoesNotExistInKeycloak() {
    // given
    UserRepresentation userRepresentation = givenUserRepresentation("email");
    UserResource userResource = givenUserResourceWithRepresentation(userRepresentation);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    HashMap<String, List<String>> attributeMap = Maps.newHashMap();
    attributeMap.put("locale", Lists.newArrayList("en"));
    when(userRepresentation.getAttributes()).thenReturn(attributeMap);

    // when
    this.keycloakService.changeLanguage("userId", "de");

    // then
    verify(userResource).update(userRepresentation);
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

  @Test
  public void changeLanguage_ShouldChangeLanguageIfLocaleAttributeDoesNotExistInKeycloak() {
    // given
    UserRepresentation userRepresentation = givenUserRepresentation("email");
    UserResource userResource = givenUserResourceWithRepresentation(userRepresentation);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    HashMap<String, List<String>> attributeMap = Maps.newHashMap();
    when(userRepresentation.getAttributes()).thenReturn(attributeMap);

    // when
    this.keycloakService.changeLanguage("userId", "de");

    // then
    verify(userResource).update(userRepresentation);
  }

  @Test
  public void findById_Should_MapUserRepresentation() {

    // given
    UserRepresentation userRepresentation = new UserRepresentation();
    userRepresentation.setId("userId");
    userRepresentation.setUsername("username");
    userRepresentation.setFirstName("first");
    userRepresentation.setLastName("last");
    userRepresentation.setEmail("email@example.org");
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = mock(UsersResource.class);
    when(userResource.toRepresentation()).thenReturn(userRepresentation);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    when(usersResource.get("userId")).thenReturn(userResource);

    // when
    Optional<IdentityProfile> profile = this.keycloakService.findById("userId");

    // then
    verify(keycloakClient, times(1)).getUsersResource();
    assertThat(
        profile,
        equalTo(
            Optional.of(
                new IdentityProfile("userId", "username", "first", "last", "email@example.org"))));
  }

  @Test
  public void findById_Should_ReturnEmptyIfUserResourceIsAbsent() {

    // given
    UsersResource usersResource = mock(UsersResource.class);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    when(usersResource.get("userId")).thenReturn(null);

    // when, then
    assertThat(this.keycloakService.findById("userId"), equalTo(Optional.empty()));
  }

  @Test
  public void findById_Should_ReturnEmptyIfKeycloakReportsUserNotFound() {

    // given
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = mock(UsersResource.class);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    when(usersResource.get("userId")).thenReturn(userResource);
    when(userResource.toRepresentation()).thenThrow(new NotFoundException());

    // when, then
    assertThat(this.keycloakService.findById("userId"), equalTo(Optional.empty()));
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
  public void initiateEmailVerification_Should_ReturnEmptyOptional_When_RequestSucceeds() {
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);
    when(keycloakClient.putForEntity(any(), any(), any(), any()))
        .thenReturn(new ResponseEntity<>(HttpStatus.OK));

    var result = keycloakService.initiateEmailVerification(USERNAME, "mail@example.com");

    assertThat(result.isPresent(), is(false));
  }

  @Test
  public void initiateEmailVerification_Should_ReturnMessage_When_KeycloakRejects() {
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);
    when(keycloakClient.putForEntity(any(), any(), any(), any()))
        .thenThrow(new RestClientException("Keycloak said no"));

    var result = keycloakService.initiateEmailVerification(USERNAME, "mail@example.com");

    assertThat(result.isPresent(), is(true));
    assertThat(result.get().contains("Keycloak said no"), is(true));
  }

  @Test
  public void finishEmailVerification_Should_ReturnMappedSuccess_When_RequestSucceeds() {
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);
    ResponseEntity<de.caritas.cob.userservice.api.model.SuccessWithEmail> responseEntity =
        new ResponseEntity<>(
            new de.caritas.cob.userservice.api.model.SuccessWithEmail(), HttpStatus.OK);
    when(keycloakClient.postForEntity(
            any(), any(), any(), eq(de.caritas.cob.userservice.api.model.SuccessWithEmail.class)))
        .thenReturn(responseEntity);
    var expected = new HashMap<String, String>();
    expected.put("status", "ok");
    when(keycloakMapper.mapOf(responseEntity)).thenReturn(expected);

    var result = keycloakService.finishEmailVerification(USERNAME, "123456");

    assertThat(result, is(expected));
  }

  @Test
  public void finishEmailVerification_Should_ReturnMappedError_When_KeycloakRejects() {
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);
    var exception =
        new org.springframework.web.client.HttpClientErrorException(HttpStatus.BAD_REQUEST);
    when(keycloakClient.postForEntity(any(), any(), any(), any())).thenThrow(exception);
    var expected = new HashMap<String, String>();
    expected.put("status", "error");
    when(keycloakMapper.mapOf(exception)).thenReturn(expected);

    var result = keycloakService.finishEmailVerification(USERNAME, "123456");

    assertThat(result, is(expected));
    verify(keycloakClient, never()).refreshAdminSession();
  }

  @Test
  public void findByEmail_Should_ReturnTypedOwner_When_ExactMatchFound() {
    var email = "mail@example.com";
    UserRepresentation userRepresentation = mock(UserRepresentation.class);
    when(userRepresentation.getEmail()).thenReturn(email);
    when(userRepresentation.getUsername()).thenReturn(USERNAME);
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.search(email, 0, Integer.MAX_VALUE))
        .thenReturn(singletonList(userRepresentation));
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    var result = keycloakService.findByEmail(email);

    assertThat(result, is(Optional.of(new IdentityEmailOwner(USERNAME))));
  }

  @Test
  public void findByEmail_Should_ReturnEmpty_When_NoMatchFound() {
    var email = "mail@example.com";
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.search(email, 0, Integer.MAX_VALUE)).thenReturn(List.of());
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    var result = keycloakService.findByEmail(email);

    assertThat(result.isEmpty(), is(true));
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

  @Test
  public void userHasRole_Should_ReturnTrue_When_UserHasRole() {
    UserResource userResource = givenUserResourceWithRealmRoles("user");
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    assertThat(keycloakService.userHasRole(USER_ID, "user"), is(true));
  }

  @Test
  public void userHasRole_Should_ReturnFalse_When_UserDoesNotHaveRole() {
    UserResource userResource = givenUserResourceWithRealmRoles("consultant");
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    assertThat(keycloakService.userHasRole(USER_ID, "user"), is(false));
  }

  @Test
  public void userHasRole_Should_ThrowKeycloakException_When_LookupFails() {
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.get(any())).thenThrow(new RuntimeException("boom"));
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    assertThrows(KeycloakException.class, () -> keycloakService.userHasRole(USER_ID, "user"));
  }

  @Test
  public void findAllByUserId_Should_ReturnRoleNames_When_LookupSucceeds() {
    UserResource userResource = givenUserResourceWithRealmRoles("user", "consultant");
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    List<String> roles = keycloakService.findAllByUserId(USER_ID);

    assertThat(roles, is(Lists.newArrayList("user", "consultant")));
  }

  @Test
  public void findAllByUserId_Should_ThrowKeycloakException_When_LookupFails() {
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.get(any())).thenThrow(new RuntimeException("boom"));
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    assertThrows(KeycloakException.class, () -> keycloakService.findAllByUserId(USER_ID));
  }

  @Test
  public void findByUsername_Should_DelegateToUsersResourceSearch() {
    UserRepresentation userRepresentation = mock(UserRepresentation.class);
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.search(USERNAME)).thenReturn(singletonList(userRepresentation));
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    List<UserRepresentation> result = keycloakService.findByUsername(USERNAME);

    assertThat(result, is(singletonList(userRepresentation)));
  }

  @Test
  public void findByUsername_Should_RefreshAdminSessionAndRetry_When_Unauthorized() {
    UserRepresentation userRepresentation = mock(UserRepresentation.class);
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.search(USERNAME))
        .thenThrow(new jakarta.ws.rs.NotAuthorizedException("Bearer"))
        .thenReturn(singletonList(userRepresentation));
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    List<UserRepresentation> result = keycloakService.findByUsername(USERNAME);

    assertThat(result, is(singletonList(userRepresentation)));
    verify(keycloakClient).refreshAdminSession();
    verify(usersResource, times(2)).search(USERNAME);
  }

  @Test
  public void deleteUser_Should_RemoveUser_When_UserExists() {
    UserResource userResource = mock(UserResource.class);
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    keycloakService.deleteUser(USER_ID);

    verify(keycloakClient, times(1)).getUsersResource();
    verify(usersResource, times(1)).get(USER_ID);
    verify(userResource, times(1)).remove();
  }

  @Test
  public void deleteUser_Should_LogWarnAndSwallow_When_UserNotFound() {
    UsersResource usersResource = mock(UsersResource.class);
    UserResource userResource = mock(UserResource.class);
    org.mockito.Mockito.doThrow(mock(jakarta.ws.rs.NotFoundException.class))
        .when(userResource)
        .remove();
    when(usersResource.get(any())).thenReturn(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    keycloakService.deleteUser(USER_ID);

    assertThat(
        logCaptor.contains(Level.WARN, "not found in Keycloak, skipping deletion"), is(true));
    verify(keycloakClient, times(1)).getUsersResource();
    verify(usersResource, times(1)).get(USER_ID);
    verify(userResource, times(1)).remove();
    verify(keycloakClient, never()).refreshAdminSession();
  }

  @Test
  public void deleteUser_Should_RefreshAdminSessionAndRetry_When_Unauthorized() {
    UsersResource usersResource = mock(UsersResource.class);
    UserResource userResource = mock(UserResource.class);
    org.mockito.Mockito.doThrow(mock(jakarta.ws.rs.NotAuthorizedException.class))
        .doNothing()
        .when(userResource)
        .remove();
    when(usersResource.get(any())).thenReturn(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    keycloakService.deleteUser(USER_ID);

    verify(keycloakClient, times(2)).getUsersResource();
    verify(usersResource, times(2)).get(USER_ID);
    verify(keycloakClient, times(1)).refreshAdminSession();
    verify(userResource, times(2)).remove();
  }

  @Test
  public void deleteUser_Should_TreatNotFoundAfterUnauthorizedRetryAsAlreadyDeleted() {
    UsersResource usersResource = mock(UsersResource.class);
    UserResource userResource = mock(UserResource.class);
    org.mockito.Mockito.doThrow(new jakarta.ws.rs.NotAuthorizedException("unauthorized"))
        .doThrow(new jakarta.ws.rs.NotFoundException("already deleted"))
        .when(userResource)
        .remove();
    when(usersResource.get(any())).thenReturn(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    keycloakService.deleteUser(USER_ID);

    verify(keycloakClient).refreshAdminSession();
    verify(userResource, times(2)).remove();
    assertThat(
        logCaptor.contains(Level.WARN, "not found in Keycloak, skipping deletion"), is(true));
  }

  @Test
  public void ensureRoles_Should_NotCallKeycloak_When_NoRolesAreRequested() {
    keycloakService.ensureRoles(USER_ID, List.of());

    verifyNoInteractions(keycloakClient);
  }

  @Test
  public void ensureRoles_Should_DeduplicateAndAddOnlyMissingRolesInOneCall() {
    UserResource userResource = givenUserResourceWithRealmRoles("consultant");
    RoleScopeResource currentRoles = userResource.roles().realmLevel();
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    var realmResource = mock(RealmResource.class);
    var rolesResource = mock(RolesResource.class);
    var roleResource = mock(RoleResource.class);
    var roleRepresentation = new RoleRepresentation();
    roleRepresentation.setName("group-chat-consultant");
    when(roleResource.toRepresentation()).thenReturn(roleRepresentation);
    when(rolesResource.get("group-chat-consultant")).thenReturn(roleResource);
    when(realmResource.roles()).thenReturn(rolesResource);
    UserResource userResourceForUpdate = givenUserResourceWithRealmRoles("group-chat-consultant");
    RoleScopeResource updatedRoles = userResourceForUpdate.roles().realmLevel();
    UsersResource usersResourceForUpdate = givenUsersResourceWithAnyUserId(userResourceForUpdate);
    when(realmResource.users()).thenReturn(usersResourceForUpdate);
    when(keycloakClient.getRealmResource()).thenReturn(realmResource);

    keycloakService.ensureRoles(
        USER_ID, List.of("consultant", "group-chat-consultant", "group-chat-consultant"));

    verify(currentRoles, times(1)).listAll();
    verify(rolesResource, never()).get("consultant");
    verify(rolesResource, times(1)).get("group-chat-consultant");
    verify(updatedRoles).add(singletonList(roleRepresentation));
    verify(updatedRoles, times(1)).listAll();
  }

  @Test
  public void ensureRoles_Should_VerifyAllMissingRolesWithOneReadPerAttempt() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    UserResource userResourceForCheck = givenUserResourceWithRealmRoles();
    RoleScopeResource currentRoles = userResourceForCheck.roles().realmLevel();
    UsersResource usersResourceForCheck = givenUsersResourceWithAnyUserId(userResourceForCheck);
    when(keycloakClient.getUsersResource()).thenReturn(usersResourceForCheck);

    var realmResource = mock(RealmResource.class);
    var rolesResource = mock(RolesResource.class);
    var consultantRoleResource = mock(RoleResource.class);
    var groupChatRoleResource = mock(RoleResource.class);
    var consultantRole = new RoleRepresentation();
    consultantRole.setName("consultant");
    var groupChatRole = new RoleRepresentation();
    groupChatRole.setName("group-chat-consultant");
    when(consultantRoleResource.toRepresentation()).thenReturn(consultantRole);
    when(groupChatRoleResource.toRepresentation()).thenReturn(groupChatRole);
    when(rolesResource.get("consultant")).thenReturn(consultantRoleResource);
    when(rolesResource.get("group-chat-consultant")).thenReturn(groupChatRoleResource);
    when(realmResource.roles()).thenReturn(rolesResource);

    RoleScopeResource updatedRoles = mock(RoleScopeResource.class);
    when(updatedRoles.listAll())
        .thenReturn(List.of())
        .thenReturn(List.of(consultantRole, groupChatRole));
    RoleMappingResource roleMappingResource = mock(RoleMappingResource.class);
    when(roleMappingResource.realmLevel()).thenReturn(updatedRoles);
    UserResource userResourceForUpdate = mock(UserResource.class);
    when(userResourceForUpdate.roles()).thenReturn(roleMappingResource);
    UsersResource usersResourceForUpdate = givenUsersResourceWithAnyUserId(userResourceForUpdate);
    when(realmResource.users()).thenReturn(usersResourceForUpdate);
    when(keycloakClient.getRealmResource()).thenReturn(realmResource);

    keycloakService.ensureRoles(USER_ID, List.of("consultant", "group-chat-consultant"));

    verify(currentRoles, times(1)).listAll();
    verify(updatedRoles).add(List.of(consultantRole, groupChatRole));
    verify(updatedRoles, times(2)).listAll();
    verify(outboundHttpMetrics).recordRetry("keycloak", "role-visibility");
  }

  @Test
  public void ensureRoles_Should_RefreshAdminSessionAndRetryInitialRead_When_Unauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    UserResource userResource = givenUserResourceWithRealmRoles("consultant");
    UsersResource usersResource = givenUsersResourceWithAnyUserId(userResource);
    when(keycloakClient.getUsersResource())
        .thenThrow(new NotAuthorizedException("Bearer"))
        .thenReturn(usersResource);

    keycloakService.ensureRoles(USER_ID, List.of("consultant"));

    verify(keycloakClient).refreshAdminSession();
    verify(keycloakClient, times(2)).getUsersResource();
    verify(outboundHttpMetrics).recordRetry("keycloak", "admin-session-refresh");
    verify(keycloakClient, never()).getRealmResource();
  }

  @Test
  public void ensureRoles_Should_RefreshAdminSessionAndRetryBatchOnce_When_AddIsUnauthorized() {
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    keycloakService.setOutboundHttpMetrics(outboundHttpMetrics);
    UserResource userResourceForCheck = givenUserResourceWithRealmRoles();
    UsersResource usersResourceForCheck = givenUsersResourceWithAnyUserId(userResourceForCheck);
    when(keycloakClient.getUsersResource()).thenReturn(usersResourceForCheck);

    var realmResource = mock(RealmResource.class);
    var rolesResource = mock(RolesResource.class);
    var roleResource = mock(RoleResource.class);
    var roleRepresentation = new RoleRepresentation();
    roleRepresentation.setName("consultant");
    when(roleResource.toRepresentation()).thenReturn(roleRepresentation);
    when(rolesResource.get("consultant")).thenReturn(roleResource);
    when(realmResource.roles()).thenReturn(rolesResource);
    RoleScopeResource updatedRoles = mock(RoleScopeResource.class);
    doThrow(new NotAuthorizedException("Bearer")).doNothing().when(updatedRoles).add(any());
    when(updatedRoles.listAll()).thenReturn(List.of(roleRepresentation));
    RoleMappingResource roleMappingResource = mock(RoleMappingResource.class);
    when(roleMappingResource.realmLevel()).thenReturn(updatedRoles);
    UserResource userResourceForUpdate = mock(UserResource.class);
    when(userResourceForUpdate.roles()).thenReturn(roleMappingResource);
    UsersResource usersResourceForUpdate = givenUsersResourceWithAnyUserId(userResourceForUpdate);
    when(realmResource.users()).thenReturn(usersResourceForUpdate);
    when(keycloakClient.getRealmResource()).thenReturn(realmResource);

    keycloakService.ensureRoles(USER_ID, List.of("consultant"));

    verify(keycloakClient).refreshAdminSession();
    verify(keycloakClient, times(2)).getUsersResource();
    verify(updatedRoles, times(2)).add(singletonList(roleRepresentation));
    verify(outboundHttpMetrics).recordRetry("keycloak", "admin-session-refresh");
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
  public void
      updatePassword_Should_throwCustomValidationHttpStatusException_When_MessageMentionsPasswordPolicy() {
    givenResetPasswordThrows(new RuntimeException("password policy violation"));

    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void
      updatePassword_Should_throwCustomValidationHttpStatusException_When_MessageMentionsPasswordInvalid() {
    givenResetPasswordThrows(new RuntimeException("password is invalid"));

    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void
      updatePassword_Should_throwCustomValidationHttpStatusException_When_MessageMentionsPasswordNotMet() {
    givenResetPasswordThrows(new RuntimeException("password requirements not met"));

    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void
      updatePassword_Should_throwCustomValidationHttpStatusException_When_MessageMentionsPasswordDoesNotMatch() {
    givenResetPasswordThrows(new RuntimeException("password does not match pattern"));

    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void
      updatePassword_Should_ThrowCustomValidationHttpStatusException_When_CauseIsPolicyViolation() {
    var cause = new RuntimeException("password policy violation");
    givenResetPasswordThrows(new RuntimeException("wrapper", cause));

    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void
      updatePassword_Should_ThrowCustomValidationHttpStatusException_When_RestClientResponseExceptionHasPolicyBody() {
    var restException = mock(RestClientResponseException.class);
    when(restException.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
    when(restException.getResponseBodyAsString()).thenReturn("password policy violated");
    when(restException.getMessage()).thenReturn("400 Bad Request");
    givenResetPasswordThrows(restException);

    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void
      updatePassword_Should_RethrowOriginalException_When_RestClientResponseExceptionHasNonPolicyBody() {
    var restException = mock(RestClientResponseException.class);
    when(restException.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
    when(restException.getResponseBodyAsString()).thenReturn("some other error");
    when(restException.getMessage()).thenReturn("some other error");
    givenResetPasswordThrows(restException);

    assertThrows(
        RestClientResponseException.class, () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void
      updatePassword_Should_RethrowOriginalException_When_RestClientResponseExceptionHasNonBadRequestStatus() {
    var restException = mock(RestClientResponseException.class);
    when(restException.getStatusCode()).thenReturn(HttpStatus.INTERNAL_SERVER_ERROR);
    when(restException.getMessage()).thenReturn("server error");
    givenResetPasswordThrows(restException);

    assertThrows(
        RestClientResponseException.class, () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void updatePassword_Should_RethrowOriginalException_When_MessageIsNull() {
    givenResetPasswordThrows(new RuntimeException((String) null));

    assertThrows(RuntimeException.class, () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void updatePassword_Should_RethrowOriginalException_When_MessageIsBlank() {
    givenResetPasswordThrows(new RuntimeException("   "));

    assertThrows(RuntimeException.class, () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void
      updatePassword_Should_RethrowOriginalException_When_MessageMentionsPasswordButNoPolicyKeyword() {
    givenResetPasswordThrows(new RuntimeException("password field is required"));

    assertThrows(RuntimeException.class, () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void updatePassword_Should_RethrowOriginalException_When_UnrelatedError() {
    givenResetPasswordThrows(new RuntimeException("connection refused"));

    assertThrows(RuntimeException.class, () -> keycloakService.updatePassword("userId", "weak"));
  }

  @Test
  public void createUser_Should_LeaveTenantIdAttributeUnset_When_TenantIdAndCurrentTenantAreNull() {
    setField(keycloakService, "multiTenancyEnabled", true);
    TenantContext.clear();
    UserDTO userDTO = new EasyRandom().nextObject(UserDTO.class);
    userDTO.setTenantId(null);
    UsersResource usersResource = mock(UsersResource.class);
    Response response = mock(Response.class);
    when(response.getStatus()).thenReturn(HttpStatus.CREATED.value());
    when(usersResource.create(any())).thenReturn(response);
    givenAUserResourceForCreatedUser(usersResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);
    givenPostCreateAttributeUpdate(usersResource, response, USER_ID);

    var keycloakUser = keycloakService.createUser(userDTO);

    assertThat(keycloakUser.getUserId(), is(USER_ID));
    setField(keycloakService, "multiTenancyEnabled", false);
  }

  @Test
  public void changeEmailAddress_username_Should_UpdateEmail_When_EmailDiffers() {
    UserRepresentation userRepresentation = mock(UserRepresentation.class);
    when(userRepresentation.getEmail()).thenReturn("old@example.com");
    when(userRepresentation.getId()).thenReturn(USER_ID);
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.search(USERNAME)).thenReturn(List.of(userRepresentation));
    UserResource userResource = mock(UserResource.class);
    when(usersResource.get(USER_ID)).thenReturn(userResource);
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    keycloakService.changeEmailAddress(USERNAME, "New@Example.com");

    verify(userRepresentation).setEmail("new@example.com");
    verify(userResource).update(userRepresentation);
  }

  @Test
  public void changeEmailAddress_username_Should_NotUpdateEmail_When_EmailIsUnchanged() {
    UserRepresentation userRepresentation = mock(UserRepresentation.class);
    when(userRepresentation.getEmail()).thenReturn("same@example.com");
    UsersResource usersResource = mock(UsersResource.class);
    when(usersResource.search(USERNAME)).thenReturn(List.of(userRepresentation));
    when(keycloakClient.getUsersResource()).thenReturn(usersResource);

    keycloakService.changeEmailAddress(USERNAME, "same@example.com");

    verify(userRepresentation, org.mockito.Mockito.never()).setEmail(anyString());
    verify(usersResource, org.mockito.Mockito.never()).get(anyString());
  }

  @Test
  public void setUpOtpCredential_Should_ReturnFalse_When_KeycloakReturnsUnauthorized() {
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);
    var exception = mock(org.springframework.web.client.HttpClientErrorException.class);
    when(exception.getStatusCode()).thenReturn(HttpStatus.UNAUTHORIZED);
    when(keycloakClient.putForEntity(any(), any(), any(), any())).thenThrow(exception);

    boolean result = keycloakService.setUpOtpCredential(USERNAME, "123456", "secret");

    assertThat(result, is(false));
  }

  @Test
  public void setUpOtpCredential_Should_RethrowException_When_StatusIsNotUnauthorized() {
    when(keycloakClient.getBearerToken()).thenReturn(BEARER_TOKEN);
    var exception = mock(org.springframework.web.client.HttpClientErrorException.class);
    when(exception.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
    when(keycloakClient.putForEntity(any(), any(), any(), any())).thenThrow(exception);

    assertThrows(
        org.springframework.web.client.HttpClientErrorException.class,
        () -> keycloakService.setUpOtpCredential(USERNAME, "123456", "secret"));
  }
}
