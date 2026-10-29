package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class MatrixTrackedCreationAndCompensationTest {
  static final String BASE = "https://matrix.invalid";
  static final String LOGIN = BASE + "/_matrix/client/r0/login";
  static final String REGISTER = BASE + "/_synapse/admin/v1/register";

  @ParameterizedTest
  @CsvSource({
    "DEBUG,create",
    "TRACE,create",
    "DEBUG,reactivate",
    "TRACE,reactivate",
    "DEBUG,adminMissing",
    "TRACE,adminMissing",
    "DEBUG,purge",
    "TRACE,purge"
  })
  void actualAdapterAndTransportKeepPrivateDataOutOfDiagnostics(String level, String path)
      throws Exception {
    var template = new RestTemplate();
    var server = MockRestServiceServer.bindTo(template).build();
    var config = new MatrixConfig();
    config.setApiUrl(BASE);
    config.setServerName("matrix.invalid");
    config.setAdminUsername("MatrixAdminCanary");
    config.setAdminPassword("MatrixAdminPasswordCanary");
    config.setRegistrationSharedSecret("synthetic-shared-secret");
    var service =
        new MatrixSynapseService(
            config,
            template,
            template,
            mock(MatrixRoomClient.class),
            mock(MatrixMediaClient.class),
            MatrixIdentifierRedactor.withKey("synthetic-log-key"));
    if (path.equals("create") || path.equals("reactivate")) {
      server
          .expect(requestTo(REGISTER))
          .andExpect(method(HttpMethod.GET))
          .andRespond(withSuccess("{\"nonce\":\"synthetic-nonce\"}", MediaType.APPLICATION_JSON));
      var expected =
          server
              .expect(requestTo(REGISTER))
              .andExpect(method(HttpMethod.POST))
              .andExpect(jsonPath("$.username").value("MatrixUserCanary"))
              .andExpect(jsonPath("$.password").value("MatrixPasswordCanary"));
      if (path.equals("create"))
        expected.andRespond(withServerError().body("MatrixProviderErrorCanary"));
      else
        expected.andRespond(
            withBadRequest()
                .body("{\"errcode\":\"M_USER_IN_USE\",\"error\":\"MatrixProviderErrorCanary\"}"));
    }
    if (!path.equals("create")) {
      var login =
          server
              .expect(requestTo(LOGIN))
              .andExpect(method(HttpMethod.POST))
              .andExpect(jsonPath("$.password").value("MatrixAdminPasswordCanary"));
      if (path.equals("adminMissing"))
        login.andRespond(withUnauthorizedRequest().body("MatrixProviderErrorCanary"));
      else
        login.andRespond(
            withSuccess("{\"access_token\":\"MatrixTokenCanary\"}", MediaType.APPLICATION_JSON));
    }
    if (path.equals("reactivate")) {
      server
          .expect(
              request ->
                  assertThat(request.getURI().getPath())
                      .endsWith("/@matrixusercanary:matrix.invalid"))
          .andExpect(method(HttpMethod.GET))
          .andExpect(header("Authorization", "Bearer MatrixTokenCanary"))
          .andRespond(withSuccess("{\"deactivated\":true}", MediaType.APPLICATION_JSON));
      server
          .expect(
              request ->
                  assertThat(request.getURI().getPath())
                      .endsWith("/@matrixusercanary:matrix.invalid"))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(jsonPath("$.deactivated").value(false))
          .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
      server
          .expect(
              request ->
                  assertThat(request.getURI().getPath())
                      .endsWith("/@matrixusercanary:matrix.invalid"))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(jsonPath("$.password").value("MatrixPasswordCanary"))
          .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    }
    if (path.equals("purge"))
      server
          .expect(
              request ->
                  assertThat(request.getURI().getPath())
                      .endsWith("/!MatrixRoomCanary:matrix.invalid"))
          .andExpect(method(HttpMethod.DELETE))
          .andExpect(header("Authorization", "Bearer MatrixTokenCanary"))
          .andRespond(withServerError().body("MatrixProviderErrorCanary"));
    server
        .expect(requestTo(BASE + "/public-control"))
        .andRespond(withSuccess("public", MediaType.TEXT_PLAIN));
    var root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var framework = (Logger) LoggerFactory.getLogger("org.springframework.web");
    var transport = (Logger) LoggerFactory.getLogger(RestTemplate.class);
    var old = framework.getLevel();
    var oldTransport = transport.getLevel();
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    root.addAppender(capture);
    framework.setLevel(Level.valueOf(level));
    transport.setLevel(Level.valueOf(level));
    try {
      switch (path) {
        case "create" ->
            assertThatThrownBy(
                    () ->
                        service.createUserIdTracked(
                            "MatrixUserCanary", "MatrixPasswordCanary", "MatrixDisplayCanary"))
                .hasMessage("Tracked Matrix creation failed")
                .hasNoCause();
        case "reactivate" ->
            assertThat(
                    service.createUserIdTracked(
                        "MatrixUserCanary", "MatrixPasswordCanary", "MatrixDisplayCanary"))
                .isEqualTo("@matrixusercanary:matrix.invalid");
        case "adminMissing" ->
            assertThat(service.deactivateUser("@MatrixUserCanary:matrix.invalid", true)).isFalse();
        case "purge" ->
            assertThat(service.purgeRoom("!MatrixRoomCanary:matrix.invalid", true)).isFalse();
      }
      template.getForEntity(BASE + "/public-control", String.class);
      server.verify();
      var logs =
          capture.list.stream()
              .map(
                  event ->
                      event.getFormattedMessage()
                          + (event.getThrowableProxy() == null
                              ? ""
                              : ThrowableProxyUtil.asString(event.getThrowableProxy())))
              .collect(java.util.stream.Collectors.joining("\n"));
      assertThat(logs)
          .contains("public-control")
          .doesNotContain(
              "MatrixUserCanary",
              "matrixusercanary",
              "MatrixPasswordCanary",
              "MatrixDisplayCanary",
              "MatrixAdminCanary",
              "MatrixAdminPasswordCanary",
              "MatrixTokenCanary",
              "MatrixRoomCanary",
              "MatrixProviderErrorCanary");
    } finally {
      root.detachAppender(capture);
      capture.stop();
      framework.setLevel(old);
      transport.setLevel(oldTransport);
    }
  }
}
