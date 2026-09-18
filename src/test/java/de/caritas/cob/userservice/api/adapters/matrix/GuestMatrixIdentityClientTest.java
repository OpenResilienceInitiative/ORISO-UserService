package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class GuestMatrixIdentityClientTest {
  @Test
  void deactivatedLoginIsTerminalAndNeverAttemptsRegistration() {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/login"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errcode\":\"M_USER_DEACTIVATED\"}"));
    assertThatThrownBy(() -> client(rest).ensureOwned("biene_rayan_1234", "temporary-password"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    server.verify();
  }

  @Test
  void occupiedOrDeactivatedNameIsNeverReactivated() {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    var config = new MatrixConfig();
    config.setApiUrl("https://matrix.example");
    config.setServerName("matrix.example");
    config.setRegistrationSharedSecret("test-registration-key");
    var client = new GuestMatrixIdentityClient(config, rest);
    server
        .expect(requestTo("https://matrix.example/_synapse/admin/v1/register"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"nonce\":\"nonce\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://matrix.example/_synapse/admin/v1/register"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errcode\":\"M_USER_IN_USE\",\"error\":\"User ID already taken\"}"));

    assertThatThrownBy(() -> client.createOnly("biene_rayan_1234", "temporary-password"))
        .isInstanceOf(ConflictException.class);
    // Any GET/PUT to an existing account would be an unexpected HTTP request.
    server.verify();
  }

  @Test
  void ownershipAlwaysChecksSuppliedPasswordAndRevokesProofToken() {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    var config = new MatrixConfig();
    config.setApiUrl("https://matrix.example");
    config.setServerName("matrix.example");
    var client = new GuestMatrixIdentityClient(config, rest);
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/login"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.password").value("correct-password"))
        .andExpect(jsonPath("$.identifier.user").value("@biene_rayan_1234:matrix.example"))
        .andRespond(
            withSuccess(
                "{\"user_id\":\"@biene_rayan_1234:matrix.example\",\"access_token\":\"proof-token\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/logout"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer proof-token"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/login"))
        .andExpect(jsonPath("$.password").value("wrong-password"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errcode\":\"M_FORBIDDEN\"}"));

    assertThat(client.verifyOwnership("biene_rayan_1234", "correct-password")).isTrue();
    assertThat(client.verifyOwnership("biene_rayan_1234", "wrong-password")).isFalse();
    server.verify();
  }

  @Test
  void createsExactlySelectedIdentityAndRevokesRegistrationToken() {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    var client = client(rest);
    server
        .expect(requestTo("https://matrix.example/_synapse/admin/v1/register"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"nonce\":\"nonce\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://matrix.example/_synapse/admin/v1/register"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.username").value("biene_rayan_1234"))
        .andExpect(jsonPath("$.displayname").value("biene_rayan_1234"))
        .andExpect(jsonPath("$.password").value("temporary-password"))
        .andExpect(jsonPath("$.admin").value(false))
        .andExpect(jsonPath("$.inhibit_login").value(true))
        // Independent fixed vector for Synapse's shared-secret registration protocol.
        .andExpect(jsonPath("$.mac").value("aa19ce25a400f630fa5027c5d72c40ddea3ba447"))
        .andRespond(
            withSuccess(
                "{\"user_id\":\"@biene_rayan_1234:matrix.example\",\"access_token\":\"registration-token\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/logout"))
        .andExpect(header("Authorization", "Bearer registration-token"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThat(client.createOnly("biene_rayan_1234", "temporary-password"))
        .isEqualTo("@biene_rayan_1234:matrix.example");
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"errcode\":\"M_LIMIT_EXCEEDED\"}", "not-json"})
  void unknownLoginRejectionCannotBecomeAnAbsentAccount(String body) {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/login"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(body));

    assertThatThrownBy(() -> client(rest).verifyOwnership("biene_rayan_1234", "temporary-password"))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasNoCause();
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"user_id\":\"@biene_rayan_1234:matrix.example\"}",
        "{\"access_token\":17}"
      })
  void incompleteLoginResponseCannotProveOwnership(String body) {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/login"))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client(rest).verifyOwnership("biene_rayan_1234", "temporary-password"))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasNoCause();
    server.verify();
  }

  @Test
  void differentReturnedIdentityIsRejectedAfterRevokingItsToken() {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/login"))
        .andRespond(
            withSuccess(
                "{\"user_id\":\"@someone_else:matrix.example\",\"access_token\":\"proof-token\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/logout"))
        .andExpect(header("Authorization", "Bearer proof-token"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client(rest).verifyOwnership("biene_rayan_1234", "temporary-password"))
        .isInstanceOf(ServiceUnavailableException.class);
    server.verify();
  }

  @Test
  void failedProofCleanupIsUnknownAndDoesNotLeakProviderResponse() {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/login"))
        .andRespond(
            withSuccess(
                "{\"user_id\":\"@biene_rayan_1234:matrix.example\",\"access_token\":\"proof-token\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/logout"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("provider detail proof-token"));

    assertThatThrownBy(() -> client(rest).verifyOwnership("biene_rayan_1234", "temporary-password"))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Guest chat provisioning outcome is unknown")
        .hasNoCause();
    server.verify();
  }

  @Test
  void lostRegistrationResponseRecoversWithoutAnotherRegistrationOrUntrackedLogin() {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    var client = client(rest);
    server
        .expect(requestTo("https://matrix.example/_synapse/admin/v1/register"))
        .andRespond(withSuccess("{\"nonce\":\"nonce\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://matrix.example/_synapse/admin/v1/register"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.inhibit_login").value(true))
        .andRespond(
            request -> {
              throw new java.io.IOException("Response lost after creation");
            });
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/login"))
        .andExpect(jsonPath("$.password").value("temporary-password"))
        .andRespond(
            withSuccess(
                "{\"user_id\":\"@biene_rayan_1234:matrix.example\",\"access_token\":\"proof-token\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/logout"))
        .andExpect(header("Authorization", "Bearer proof-token"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.createOnly("biene_rayan_1234", "temporary-password"))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(client.ensureOwned("biene_rayan_1234", "temporary-password"))
        .isEqualTo("@biene_rayan_1234:matrix.example");
    server.verify();
  }

  private GuestMatrixIdentityClient client(RestTemplate rest) {
    var config = new MatrixConfig();
    config.setApiUrl("https://matrix.example");
    config.setServerName("matrix.example");
    config.setRegistrationSharedSecret("test-registration-key");
    return new GuestMatrixIdentityClient(config, rest);
  }
}
