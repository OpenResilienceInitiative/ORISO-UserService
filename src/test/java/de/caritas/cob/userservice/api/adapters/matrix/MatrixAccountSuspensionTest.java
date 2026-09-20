package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class MatrixAccountSuspensionTest {
  @Test
  void suspensionLocksAccessWithoutErasingTheAccount() {
    var transport = new RestTemplate();
    var server = MockRestServiceServer.bindTo(transport).build();
    var config = new MatrixConfig();
    config.setApiUrl("https://matrix.example");
    config.setAdminUsername("admin");
    config.setAdminPassword("test-only");
    server
        .expect(requestTo("https://matrix.example/_matrix/client/r0/login"))
        .andRespond(withSuccess("{\"access_token\":\"test-admin\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/_synapse/admin/v2/users/")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"locked\":false}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/_synapse/admin/v2/users/")))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().json("{\"locked\":true}", true))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("/_synapse/admin/v2/users/")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"locked\":true}", MediaType.APPLICATION_JSON));
    var service = new MatrixSynapseService(config, transport, transport, null, null);
    assertThat(service.setAccountSuspended("@person:matrix.example", true)).isTrue();
    server.verify();
  }
}
