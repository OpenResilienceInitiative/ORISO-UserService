package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class MatrixStrictReadOnlyHttpTest {
  @ParameterizedTest
  @ValueSource(ints = {200, 404, 401, 403, 503})
  void configuredCredentialPermitsOnlyTheLookupGetEvenWhenRejected(int status) {
    var config = new MatrixConfig();
    config.setApiUrl("https://matrix.example.test");
    config.setServerName("matrix.example.test");
    config.setAdminUsername("technical-admin");
    config.setAdminPassword("synthetic-test-password");
    config.setAvailabilityAdminAccessToken("synthetic-lookup-token");
    var rest = new RestTemplate();
    var server = MockRestServiceServer.createServer(rest);
    server
        .expect(
            request ->
                assertThat(request.getURI().getPath())
                    .isEqualTo("/_synapse/admin/v2/users/@otter:matrix.example.test"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer synthetic-lookup-token"))
        .andRespond(withStatus(HttpStatus.valueOf(status)));
    var service = new MatrixSynapseService(config, rest, rest, null, null);
    if (status == 200 || status == 404) {
      assertThat(service.userExistsStrict("Otter")).isEqualTo(status == 200);
    } else {
      assertThatThrownBy(() -> service.userExistsStrict("Otter"))
          .isInstanceOf(ServiceUnavailableException.class)
          .hasNoCause();
    }
    server.verify();
  }

  @Test
  void coldLookupNeverLogsInOrRegistersAnAdmin() {
    var config = new MatrixConfig();
    config.setApiUrl("https://matrix.example.test");
    config.setServerName("matrix.example.test");
    config.setAdminUsername("technical-admin");
    config.setAdminPassword("synthetic-test-password");
    var rest = new RestTemplate();
    var server = MockRestServiceServer.createServer(rest);
    var service = new MatrixSynapseService(config, rest, rest, null, null);

    assertThatThrownBy(() -> service.userExistsStrict("otter"))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasNoCause();
    server.verify();
  }
}
