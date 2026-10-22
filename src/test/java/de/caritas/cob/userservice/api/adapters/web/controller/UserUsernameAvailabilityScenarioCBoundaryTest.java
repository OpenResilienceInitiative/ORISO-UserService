package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.IdentityManager;
import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.KeycloakUsernameAvailability;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.ScopedUsernameAvailability;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.UsernameAvailabilityConfiguration;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.*;
import de.caritas.cob.userservice.api.port.out.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Actual public controller/IdentityManager/KeycloakService; exact selector peer is controlled. */
class UserUsernameAvailabilityScenarioCBoundaryTest {
  private static final String NAME = "ordinary-proof";
  private static final String ENCODED = new UsernameTranscoder().encodeUsername(NAME);

  @Test
  void publicCReportsOccupiedRawUsernameThroughBothExactSelectors() {
    var fixture = new Fixture(true);
    fixture.install(name -> !NAME.equals(name));
    assertEquals(409, fixture.controller.usernameAvailability(NAME).getStatusCode().value());
    assertEquals(List.of(NAME, ENCODED), fixture.calls);
    verifyNoInteractions(fixture.nativeClient);
  }

  @Test
  void publicCReportsOccupiedEncodedUsernameThroughBothExactSelectors() {
    var fixture = new Fixture(true);
    fixture.install(name -> !ENCODED.equals(name));
    assertEquals(409, fixture.controller.usernameAvailability(ENCODED).getStatusCode().value());
    assertEquals(List.of(NAME, ENCODED), fixture.calls);
    verifyNoInteractions(fixture.nativeClient);
  }

  @Test
  void publicCReportsAvailableOnlyAfterBothExactSelectorsConfirm() {
    var fixture = new Fixture(true);
    fixture.install(name -> true);
    assertEquals(204, fixture.controller.usernameAvailability(NAME).getStatusCode().value());
    assertEquals(List.of(NAME, ENCODED), fixture.calls);
    verifyNoInteractions(fixture.nativeClient);
  }

  @Test
  void publicCUnknownReadCannotBecomeAnAvailableUsername() {
    var fixture = new Fixture(true);
    fixture.install(
        name -> {
          throw new ScopedAvailabilityUnavailableException();
        });
    assertThrows(
        ScopedAvailabilityUnavailableException.class,
        () -> fixture.controller.usernameAvailability(NAME));
    assertEquals(List.of(NAME), fixture.calls);
    verifyNoInteractions(fixture.nativeClient);
  }

  @Test
  void publicCMissingBoundedAdapterRefusesBeforeRawNativeSearch() {
    var fixture = new Fixture(true);
    assertThrows(
        ScopedAvailabilityUnavailableException.class,
        () -> fixture.controller.usernameAvailability(NAME));
    verifyNoInteractions(fixture.nativeClient);
  }

  @Test
  void enabledRuntimeConfigurationWiresTheActualPublicBoundedSelector() {
    var fixture = new Fixture(true);
    fixture.expect(name -> true);
    fixture
        .runtimeContext()
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var response =
                  assertDoesNotThrow(() -> fixture.controller.usernameAvailability(NAME));
              assertEquals(204, response.getStatusCode().value());
              assertEquals(List.of(NAME, ENCODED), fixture.calls);
              verifyNoInteractions(fixture.nativeClient);
            });
  }

  @Test
  void defaultOffRuntimeConfigurationCreatesNoSelectorWithoutBindings() {
    new ApplicationContextRunner()
        .withUserConfiguration(UsernameAvailabilityConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(context.getBeansOfType(ScopedUsernameAvailability.class).isEmpty());
            });
  }

  @Test
  void enabledRuntimeRealmMismatchRefusesStartupBeforeAnyNativeCall() {
    var fixture = new Fixture(true);
    fixture
        .runtimeContext()
        .withPropertyValues("ORISO_ACCOUNT_LIFECYCLE_REALM=another-realm")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(fixture.calls.isEmpty());
              verifyNoInteractions(fixture.nativeClient);
            });
  }

  @Test
  void legacyExistingUsernameKeepsItsConflictResponse() {
    var fixture = new Fixture(false);
    fixture.install(
        name -> {
          throw new AssertionError("Legacy cannot use the C selector");
        });
    assertEquals(409, fixture.controller.usernameAvailability(NAME).getStatusCode().value());
    assertTrue(fixture.calls.isEmpty());
    verify(fixture.users, times(2)).search(anyString());
  }

  @Test
  void legacyUnknownAvailabilityKeepsItsExistingHintResponse() {
    var fixture = new Fixture(false);
    when(fixture.nativeClient.getUsersResource())
        .thenThrow(new IllegalStateException("Fixture legacy unavailable"));
    assertEquals(204, fixture.controller.usernameAvailability(NAME).getStatusCode().value());
    assertTrue(fixture.calls.isEmpty());
  }

  private static final class Fixture {
    final List<String> calls = new ArrayList<>();
    final KeycloakClient nativeClient = mock(KeycloakClient.class);
    final UsersResource users = mock(UsersResource.class);
    final KeycloakService service;
    final AccountLifecycleCodec codec =
        new AccountLifecycleCodec(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    final RestTemplate http = new RestTemplate();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
    final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
    final KeycloakUsernameAvailability gateway;
    final UserController controller = mock(UserController.class, CALLS_REAL_METHODS);

    Fixture(boolean fiveActors) {
      var config = mock(IdentityClientConfig.class);
      when(config.isFiveActorMode()).thenReturn(fiveActors);
      when(nativeClient.getUsersResource()).thenReturn(users);
      var nativeUser = new UserRepresentation();
      nativeUser.setUsername(NAME);
      when(users.search(anyString())).thenReturn(List.of(nativeUser));
      service =
          new KeycloakService(
              new AuthenticatedUser(),
              mock(UserAccountInputValidator.class),
              config,
              nativeClient,
              mock(KeycloakMapper.class),
              mock(UserHelper.class),
              mock(KeycloakAuthClient.class));
      var identity =
          new IdentityManager(
              mock(IdentityClient.class),
              mock(IdentityEmailAddressUpdater.class),
              mock(IdentityAuthentication.class),
              mock(IdentityEmailOwnerLookup.class),
              mock(IdentityRoleLookup.class),
              mock(IdentitySecondFactor.class),
              service,
              new UsernameTranscoder());
      ReflectionTestUtils.setField(controller, "identityManager", identity);
      when(actors.require(TaskPurpose.ACCOUNT_PROVISIONING))
          .thenReturn(
              new TaskActorCredentials(
                  TaskActor.ACCOUNT_CREATE,
                  "backend-account-create",
                  "fixture-secret",
                  "33333333-3333-4333-8333-333333333333"));
      var creator = mock(TaskActorTokenBroker.VerifiedProvisioningToken.class);
      when(creator.actor()).thenReturn(TaskActor.ACCOUNT_CREATE);
      when(creator.accessToken()).thenReturn("fixture-create");
      when(broker.issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING)).thenReturn(creator);
      gateway =
          new KeycloakUsernameAvailability(
              http,
              actors,
              broker,
              codec,
              "https://identity.test",
              "fixture",
              "22222222-2222-4222-8222-222222222222");
    }

    void install(ScopedUsernameAvailability answer) {
      expect(answer);
      service.setScopedUsernameAvailability(gateway);
    }

    ApplicationContextRunner runtimeContext() {
      var keycloak = mock(KeycloakConfig.class);
      when(keycloak.getRealm()).thenReturn("fixture");
      when(keycloak.getAuthServerUrl()).thenReturn("https://identity.test");
      return new ApplicationContextRunner()
          .withUserConfiguration(UsernameAvailabilityConfiguration.class)
          .withPropertyValues(
              "ORISO_ACCOUNT_LIFECYCLE_ENABLED=true",
              "TASK_IDENTITY_MODEL=FIVE_ACTORS",
              "api.error.keycloakError=fixture",
              "multitenancy.enabled=false",
              "ORISO_ACCOUNT_LIFECYCLE_REALM=fixture",
              "KEYCLOAK_REALM_INTERNAL_ID=22222222-2222-4222-8222-222222222222")
          .withInitializer(
              context -> {
                var beans = context.getBeanFactory();
                beans.registerSingleton("availabilityKeycloak", keycloak);
                beans.registerSingleton("keycloakRestTemplate", http);
                beans.registerSingleton("availabilityActors", actors);
                beans.registerSingleton("availabilityBroker", broker);
                beans.registerSingleton("availabilityCodec", codec);
              })
          .withBean(KeycloakService.class, () -> service);
    }

    void expect(ScopedUsernameAvailability answer) {
      server
          .expect(
              ExpectedCount.between(0, 2),
              requestTo(
                  "https://identity.test/realms/fixture/oriso-account-maintenance/v1/availability"))
          .andExpect(method(org.springframework.http.HttpMethod.POST))
          .andExpect(header("Authorization", "Bearer fixture-create"))
          .andRespond(
              outbound -> {
                var body =
                    codec.read(
                        ((MockClientHttpRequest) outbound).getBodyAsString(),
                        com.fasterxml.jackson.databind.JsonNode.class);
                assertEquals(2, body.size());
                assertEquals("username", body.path("selector").asText());
                String name = body.path("value").asText();
                calls.add(name);
                String proof = outbound.getHeaders().getFirst("X-ORISO-Task-Context");
                assertNotNull(proof);
                var claims =
                    codec.read(
                        new String(
                            Base64.getUrlDecoder().decode(proof.split("\\.")[0]),
                            StandardCharsets.UTF_8),
                        com.fasterxml.jackson.databind.JsonNode.class);
                assertEquals("userservice", claims.path("iss").asText());
                assertEquals("POST", claims.path("method").asText());
                assertEquals("fixture", claims.path("realm").asText());
                assertEquals("backend-account-create", claims.path("taskClient").asText());
                assertEquals(
                    "33333333-3333-4333-8333-333333333333", claims.path("taskSubject").asText());
                assertEquals(15, claims.size());
                assertTrue(claims.path("exp").asLong() > claims.path("iat").asLong());
                assertTrue(claims.path("exp").asLong() - claims.path("iat").asLong() <= 60);
                try {
                  var mac = javax.crypto.Mac.getInstance("HmacSHA256");
                  mac.init(
                      new javax.crypto.spec.SecretKeySpec(
                          "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                          "HmacSHA256"));
                  var signed = proof.split("\\.");
                  assertArrayEquals(
                      mac.doFinal(("proof\n" + signed[0]).getBytes(StandardCharsets.UTF_8)),
                      Base64.getUrlDecoder().decode(signed[1]));
                } catch (java.security.GeneralSecurityException unavailable) {
                  throw new AssertionError("Fixture HMAC unavailable", unavailable);
                }
                assertEquals("ACCOUNT_CREATE", claims.path("actor").asText());
                assertEquals("ACCOUNT_AVAILABILITY", claims.path("purpose").asText());
                assertEquals("account.username-availability", claims.path("operation").asText());
                assertEquals("oriso-account-maintenance", claims.path("aud").asText());
                assertEquals(codec.digest("payload", body), claims.path("payloadDigest").asText());
                assertEquals(
                    "realm/22222222-2222-4222-8222-222222222222/availability/username",
                    claims.path("target").asText());
                return withSuccess(
                        codec.canonical(Map.of("available", answer.available(name))),
                        MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
    }
  }
}
