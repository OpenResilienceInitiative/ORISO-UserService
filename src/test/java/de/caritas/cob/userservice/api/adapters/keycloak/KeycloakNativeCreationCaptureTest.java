package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.port.out.identity.NativeCreationCapture;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;

/** Exercises the actual adapter with only the external provider transport replaced. */
class KeycloakNativeCreationCaptureTest {

  private static final String ID = "native-id-canary";
  private static final String PRIVATE = "provider-password-email-selector-canary";
  private final KeycloakClient client = mock(KeycloakClient.class);
  private final UsersResource users = mock(UsersResource.class);
  private final UserResource resource = mock(UserResource.class);
  private final Response response = mock(Response.class);
  private final NativeCreationCapture capture = mock(NativeCreationCapture.class);
  private KeycloakService service;
  private UserDTO user;

  @BeforeEach
  void setUp() {
    service =
        new KeycloakService(
            mock(AuthenticatedUser.class),
            mock(UserAccountInputValidator.class),
            mock(IdentityClientConfig.class),
            client,
            mock(KeycloakMapper.class),
            mock(UserHelper.class),
            mock(KeycloakAuthClient.class));
    setField(service, "multiTenancyEnabled", false);
    user = new UserDTO();
    user.setUsername(PRIVATE);
    user.setPassword(PRIVATE);
    user.setEmail(PRIVATE + "@example.invalid");
    when(client.getUsersResource()).thenReturn(users);
    when(users.create(any())).thenReturn(response);
  }

  @Test
  void knownConflictHasOnlySafeBoundedTypeAndDoesNotReadProviderBody() {
    when(response.getStatus()).thenReturn(409);
    when(response.readEntity(String.class)).thenReturn(PRIVATE);
    assertThatThrownBy(this::createTracked)
        .satisfies(
            failure -> {
              assertThat(failure.getClass().getSimpleName()).isEqualTo("NativeCreationConflict");
              assertThat(failure.getMessage()).isEqualTo("Native identity creation conflict");
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getSuppressed()).isEmpty();
            });
    verify(response, never()).readEntity(String.class);
    verify(users, times(1)).create(any());
    verify(users, never()).get(any());
    verifyNoInteractions(capture, resource);
  }

  @Test
  void capturePrecedesMandatoryAttributeMutation() {
    givenCreated();
    givenAttributes();

    var created = createTracked();

    assertThat(created.getUserId()).isEqualTo(ID);
    var order = inOrder(users, response, capture, resource);
    order.verify(users).create(any());
    order.verify(response).getLocation();
    order
        .verify(capture)
        .captured(
            org.mockito.ArgumentMatchers.argThat(identity -> ID.equals(identity.getUserId())));
    order.verify(users).get(ID);
    order.verify(resource).toRepresentation();
    order.verify(resource).update(any());
    verify(capture, never()).compensationStarting();
    verify(capture, never()).compensationReturned();
    verify(resource, never()).remove();
    verifyNoMoreInteractions(resource);
  }

  @Test
  void captureFailureDoesNotCompensateOrContinue() {
    givenCreated();
    doThrow(new IllegalStateException(PRIVATE)).when(capture).captured(any());

    assertSafeFailure();

    verify(users, never()).get(any());
    verifyNoInteractions(resource);
    verify(capture, never()).compensationStarting();
    verify(capture, never()).compensationReturned();
    verify(users).create(any());
    verifyNoMoreInteractions(users);
  }

  @Test
  void missingLocationNeverCaptures() {
    when(response.getStatus()).thenReturn(201);

    assertSafeFailure();

    verifyNoInteractions(capture, resource);
    verify(users, never()).get(any());
  }

  static Stream<String> invalidLocations() {
    return Stream.of(
        "https://identity.invalid/users/",
        "https://identity.invalid",
        "mailto:opaque",
        "https://identity.invalid/users/" + "x".repeat(256),
        "https://identity.invalid/users/white%20space",
        "https://identity.invalid/users/line%0Abreak",
        "https://identity.invalid/users/unicode%E2%80%83space",
        "https://identity.invalid/users/a%2Fb",
        "https://identity.invalid/users/a%5Cb",
        "https://identity.invalid/users/a%FFb",
        "https://identity.invalid/users/a%C3");
  }

  @ParameterizedTest
  @MethodSource("invalidLocations")
  void invalidLocationNeverCaptures(String location) {
    when(response.getStatus()).thenReturn(201);
    when(response.getLocation()).thenReturn(URI.create(location));

    assertSafeFailure();

    verifyNoInteractions(capture, resource);
    verify(users, never()).get(any());
  }

  @ParameterizedTest
  @CsvSource({
    "plus+sign,plus+sign",
    "colon%3Aid,colon:id",
    "encoded%2Bid,encoded+id",
    "encoded%25id,encoded%id",
    "encoded%252Fid,encoded%2Fid",
    "a%EF%BF%BDb,a\uFFFDb"
  })
  void decodesFinalSegmentExactlyOnceWithoutFormEncoding(String encodedId, String expectedId) {
    when(response.getStatus()).thenReturn(201);
    when(response.getLocation())
        .thenReturn(URI.create("https://identity.invalid/users/" + encodedId));
    when(users.get(expectedId)).thenReturn(resource);
    when(resource.toRepresentation()).thenReturn(new UserRepresentation());

    assertThat(createTracked().getUserId()).isEqualTo(expectedId);

    verify(capture)
        .captured(
            org.mockito.ArgumentMatchers.argThat(
                identity -> expectedId.equals(identity.getUserId())));
    verify(resource).update(any());
  }

  @Test
  void maximumLengthIdIsCapturedWithoutChangingIt() {
    var id = "x".repeat(255);
    when(response.getStatus()).thenReturn(201);
    when(response.getLocation()).thenReturn(URI.create("https://identity.invalid/users/" + id));
    when(users.get(id)).thenReturn(resource);
    when(resource.toRepresentation()).thenReturn(new UserRepresentation());
    doThrow(new IllegalStateException(PRIVATE)).when(capture).captured(any());

    assertSafeFailure();

    var identity = org.mockito.ArgumentCaptor.forClass(CreatedIdentity.class);
    verify(capture).captured(identity.capture());
    assertThat(identity.getValue().getUserId()).isEqualTo(id);
    verifyNoInteractions(resource);
  }

  @Test
  void trackedUnauthorizedResponsePostsOnlyOnce() {
    when(response.getStatus()).thenReturn(401);

    assertSafeFailure();

    verify(users).create(any());
    verify(client, never()).refreshAdminSession();
    verifyNoInteractions(capture, resource);
    verify(response, never()).readEntity(String.class);
  }

  @Test
  void trackedUnauthorizedTransportPostsOnlyOnce() {
    when(users.create(any())).thenThrow(new NotAuthorizedException(PRIVATE));

    assertSafeFailure();

    verify(users).create(any());
    verify(client, never()).refreshAdminSession();
    verifyNoInteractions(capture, resource);
  }

  @Test
  void providerBodyIsNeverReadForTrackedFailure() {
    when(response.getStatus()).thenReturn(500);

    assertSafeFailure();

    verify(response, never()).readEntity(String.class);
    verifyNoInteractions(capture, resource);
  }

  @Test
  void mandatoryAttributeFailureRecordsBothCompensationObservations() {
    givenCreated();
    givenAttributes();
    doThrow(new IllegalStateException(PRIVATE)).when(resource).update(any());

    assertSafeFailure();

    var order = inOrder(capture, resource);
    order.verify(capture).captured(any());
    order.verify(resource).update(any());
    order.verify(capture).compensationStarting();
    order.verify(resource).remove();
    order.verify(capture).compensationReturned();
  }

  @Test
  void compensationStartFailureBlocksDestructiveRollback() {
    givenCreated();
    givenAttributes();
    doThrow(new IllegalStateException(PRIVATE)).when(resource).update(any());
    doThrow(new IllegalStateException(PRIVATE)).when(capture).compensationStarting();

    assertSafeFailure();

    verify(capture).captured(any());
    verify(capture).compensationStarting();
    verify(resource, never()).remove();
    verify(capture, never()).compensationReturned();
  }

  @Test
  void compensationReturnWriteFailureRemainsSafe() {
    givenCreated();
    givenAttributes();
    doThrow(new IllegalStateException(PRIVATE)).when(resource).update(any());
    doThrow(new IllegalStateException(PRIVATE)).when(capture).compensationReturned();

    assertSafeFailure();

    verify(resource).remove();
    verify(capture).compensationReturned();
  }

  static Stream<RuntimeException> failedCompensations() {
    return Stream.of(
        new NotAuthorizedException(PRIVATE),
        new NotFoundException(PRIVATE),
        new IllegalStateException(PRIVATE));
  }

  @ParameterizedTest
  @MethodSource("failedCompensations")
  void compensationReturnNeverCertifiesAbsence(RuntimeException failure) {
    givenCreated();
    givenAttributes();
    doThrow(new IllegalStateException(PRIVATE)).when(resource).update(any());
    doThrow(failure).when(resource).remove();

    assertSafeFailure();

    verify(capture).compensationStarting();
    verify(capture).compensationReturned();
    verify(users).create(any());
    verify(resource, times(failure instanceof NotAuthorizedException ? 2 : 1)).remove();
  }

  @Test
  void nullCaptureFailsBeforeNativePost() {
    assertThatThrownBy(() -> service.createUser(user, null, null, null))
        .isInstanceOf(RuntimeException.class);
    verifyNoInteractions(users, response, resource);
  }

  private CreatedIdentity createTracked() {
    return service.createUser(user, "first", "last", capture);
  }

  private void givenCreated() {
    when(response.getStatus()).thenReturn(201);
    when(response.getLocation()).thenReturn(URI.create("https://identity.invalid/users/" + ID));
  }

  private void givenAttributes() {
    when(users.get(ID)).thenReturn(resource);
    when(resource.toRepresentation()).thenReturn(new UserRepresentation());
  }

  private void assertSafeFailure() {
    try (var logs = LogbackCaptor.forClass(KeycloakService.class)) {
      assertThatThrownBy(this::createTracked)
          .isInstanceOf(RuntimeException.class)
          .satisfies(
              failure -> {
                var text = new StringWriter();
                failure.printStackTrace(new PrintWriter(text));
                assertThat(text.toString()).doesNotContain(PRIVATE, ID);
                assertThat(failure.getCause()).isNull();
              });
      assertThat(logs.events())
          .allSatisfy(
              event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE, ID);
                assertThat(event.getThrowableProxy()).isNull();
              });
    }
  }
}
