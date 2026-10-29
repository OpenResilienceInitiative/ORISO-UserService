package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TrackedServiceAuthenticationTransportTest {
  @ParameterizedTest
  @ValueSource(strings = {"DEBUG", "TRACE"})
  void sameConfidentialGrantAndResponseStayPrivateIncludingEncodedRepresentations(String level) {
    var template = new RestTemplate();
    var server = MockRestServiceServer.bindTo(template).build();
    var config = mock(IdentityClientConfig.class);
    when(config.getOpenIdConnectUrl("/token")).thenReturn("https://native.invalid/token");
    var client = new KeycloakAuthClient(template, mock(AuthenticatedUser.class), config);
    org.springframework.test.util.ReflectionTestUtils.setField(
        client, "keycloakClientId", "human-app");
    String secret = "Synthetic-Private-Client-Secret-Canary",
        token = "Synthetic-Private-Native-Token-Canary";
    server
        .expect(requestTo("https://native.invalid/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .string(
                    "grant_type=client_credentials&client_id=backend-account-create&client_secret="
                        + secret))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"" + token + "\",\"expires_in\":60}",
                MediaType.APPLICATION_JSON));
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger("org.springframework.web");
    var transport =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RestTemplate.class);
    var old = logger.getLevel();
    var oldTransport = transport.getLevel();
    var events =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    logger.setLevel(ch.qos.logback.classic.Level.valueOf(level));
    transport.setLevel(ch.qos.logback.classic.Level.valueOf(level));
    try {
      var result = client.loginServicePrivate("backend-account-create", secret);
      assertEquals(token, result.getAccessToken());
      assertEquals(60, result.getExpiresIn());
      String messages =
          events.list.stream()
              .map(
                  e ->
                      e.getFormattedMessage()
                          + (e.getThrowableProxy() == null
                              ? ""
                              : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                                  e.getThrowableProxy())))
              .collect(java.util.stream.Collectors.joining("\n"));
      for (String value : java.util.List.of(secret, token)) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        assertFalse(messages.contains(value));
        assertFalse(messages.contains(Base64.getEncoder().encodeToString(raw)));
        assertFalse(messages.contains(java.util.Arrays.toString(raw)));
      }
      server.verify();
    } finally {
      logger.detachAppender(events);
      events.stop();
      logger.setLevel(old);
      transport.setLevel(oldTransport);
    }
  }
}
