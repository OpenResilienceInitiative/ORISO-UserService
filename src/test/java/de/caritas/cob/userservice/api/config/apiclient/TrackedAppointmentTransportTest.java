package de.caritas.cob.userservice.api.config.apiclient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.util.ReflectionTestUtils.setField;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.appointmentservice.generated.web.model.ConsultantDTO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;

class TrackedAppointmentTransportTest {
  @ParameterizedTest
  @ValueSource(strings = {"get", "post", "delete", "exchange", "execute"})
  void generatedAdapterRefusesUnsupportedEntryPointsBeforeAnyIo(String entrypoint)
      throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 2);
          exchange.getResponseBody().write(new byte[] {'o', 'k'});
          exchange.close();
        });
    server.start();
    try {
      var uri =
          java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/unsupported");
      var view =
          new de.caritas.cob.userservice.api.adapters.http.TrackedHttpTransport(new RestTemplate())
              .generatedClientView();
      assertThatThrownBy(
              () -> {
                switch (entrypoint) {
                  case "get" -> view.getForEntity(uri, String.class);
                  case "post" -> view.postForEntity(uri, HttpEntity.EMPTY, String.class);
                  case "delete" -> view.delete(uri);
                  case "exchange" ->
                      view.exchange(uri, HttpMethod.GET, HttpEntity.EMPTY, String.class);
                  default -> view.execute(uri, HttpMethod.GET, null, response -> null);
                }
              })
          .isInstanceOf(RestClientException.class);
      assertThat(calls).hasValue(0);
    } finally {
      server.stop(0);
    }
  }

  @org.junit.jupiter.api.Test
  void generatedPrivateExchangeRetainsTheActualConfiguredOutboundMetrics() {
    var template = new RestTemplate();
    var server = MockRestServiceServer.bindTo(template).build();
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    new de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics(registry)
        .customize(template);
    var factory = new AppointmentConsultantServiceApiControllerFactory();
    setField(factory, "restTemplate", template);
    setField(factory, "appointmentServiceApiUrl", "https://appointment.invalid");
    server
        .expect(requestTo("https://appointment.invalid/consultants"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":12}", MediaType.APPLICATION_JSON));
    assertThat(
            factory
                .createControllerApi(true)
                .createConsultant(new ConsultantDTO().username("Synthetic")))
        .isNotNull();
    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", "appointment.invalid", "method", "post", "outcome", "2xx")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("userservice.outbound.http.latency")
                .tags("dependency", "appointment.invalid", "method", "post", "outcome", "2xx")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("userservice.outbound.http.payload")
                .tags("dependency", "appointment.invalid", "direction", "request")
                .summary()
                .count())
        .isEqualTo(1);
    server.verify();
    registry.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"DEBUG", "TRACE"})
  void generatedClientPreservesRequestAndErrorSemanticsWithoutPrivateDiagnostics(String level) {
    var template = new RestTemplate();
    var server = MockRestServiceServer.bindTo(template).build();
    template
        .getInterceptors()
        .add(
            (request, body, execution) -> {
              request.getHeaders().set("X-Interceptor", "present");
              return execution.execute(request, body);
            });
    template
        .getClientHttpRequestInitializers()
        .add(request -> request.getHeaders().set("X-Initializer", "present"));
    var factory = new AppointmentConsultantServiceApiControllerFactory();
    setField(factory, "restTemplate", template);
    setField(factory, "appointmentServiceApiUrl", "https://appointment.invalid");
    var api = factory.createControllerApi(true);
    api.getApiClient().addDefaultHeader("Authorization", "Bearer AppointmentTokenCanary");
    api.getApiClient().addDefaultHeader("tenantId", "7");
    server
        .expect(requestTo("https://appointment.invalid/consultants"))
        .andExpect(header("X-Interceptor", "present"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Initializer", "present"))
        .andExpect(header("Authorization", "Bearer AppointmentTokenCanary"))
        .andExpect(header("tenantId", "7"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("AppointmentEmailCanary")))
        .andRespond(
            withSuccess(
                "{\"id\":12,\"email\":\"AppointmentResponseCanary@example.invalid\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://appointment.invalid/consultants/AppointmentUriCanary"))
        .andExpect(header("X-Interceptor", "present"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("AppointmentErrorCanary"));
    server
        .expect(requestTo("https://appointment.invalid/consultants/AppointmentDeletedUriCanary"))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("X-Interceptor", "present"))
        .andExpect(header("X-Initializer", "present"))
        .andExpect(header("Authorization", "Bearer AppointmentTokenCanary"))
        .andExpect(header("tenantId", "7"))
        .andRespond(
            withSuccess("AppointmentAcknowledgementCanary", MediaType.TEXT_PLAIN)
                .header("X-Deletion-Acknowledgement", "accepted"));
    server
        .expect(requestTo("https://appointment.invalid/public-control"))
        .andRespond(withSuccess("public", MediaType.TEXT_PLAIN));
    var logger = (Logger) LoggerFactory.getLogger("org.springframework.web");
    var transportLogger = (Logger) LoggerFactory.getLogger(RestTemplate.class);
    var old = logger.getLevel();
    var oldTransport = transportLogger.getLevel();
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    logger.setLevel(Level.valueOf(level));
    transportLogger.setLevel(Level.valueOf(level));
    try {
      var response =
          api.createConsultant(
              new ConsultantDTO()
                  .id("synthetic-native")
                  .email("AppointmentEmailCanary@example.invalid")
                  .username("AppointmentUsernameCanary"));
      assertThat(response.getId()).isEqualTo(12);
      assertThat(response.getEmail()).isEqualTo("AppointmentResponseCanary@example.invalid");
      assertThatThrownBy(() -> api.deleteConsultant("AppointmentUriCanary"))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      var deletion =
          org.junit.jupiter.api.Assertions.assertDoesNotThrow(
              () -> api.deleteConsultantWithHttpInfo("AppointmentDeletedUriCanary"));
      assertThat(deletion.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(deletion.getHeaders().getFirst("X-Deletion-Acknowledgement"))
          .isEqualTo("accepted");
      assertThat(deletion.getBody()).isNull();
      template.getForEntity("https://appointment.invalid/public-control", String.class);
      server.verify();
      var logs =
          capture.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .collect(java.util.stream.Collectors.joining("\n"));
      assertThat(logs)
          .contains("public-control")
          .doesNotContain(
              "AppointmentEmailCanary",
              "AppointmentUsernameCanary",
              "AppointmentResponseCanary",
              "AppointmentUriCanary",
              "AppointmentErrorCanary",
              "AppointmentTokenCanary",
              "AppointmentDeletedUriCanary",
              "AppointmentAcknowledgementCanary");
    } finally {
      logger.setLevel(old);
      transportLogger.setLevel(oldTransport);
      logger.detachAppender(capture);
      capture.stop();
    }
  }
}
