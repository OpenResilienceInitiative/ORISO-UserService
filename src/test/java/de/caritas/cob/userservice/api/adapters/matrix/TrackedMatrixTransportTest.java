package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TrackedMatrixTransportTest {
  @ParameterizedTest
  @ValueSource(strings = {"DEBUG", "TRACE"})
  void realTransportPreservesWireDataWithoutLoggingBodyUriOrError(String level) {
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
        .add(request -> request.getHeaders().set("X-Synthetic-Initializer", "present"));
    var uri = URI.create("https://matrix.invalid/users/MatrixUriCanary");
    server
        .expect(requestTo(uri))
        .andExpect(header("X-Interceptor", "present"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("X-Synthetic-Initializer", "present"))
        .andExpect(header("Authorization", "Bearer SyntheticTokenCanary"))
        .andExpect(content().json("{\"password\":\"MatrixPasswordCanary\"}"))
        .andRespond(
            withSuccess("{\"user_id\":\"MatrixResponseCanary\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://matrix.invalid/public-control"))
        .andRespond(withSuccess("public", MediaType.TEXT_PLAIN));
    Logger logger = (Logger) LoggerFactory.getLogger("org.springframework.web.client.RestTemplate");
    var old = logger.getLevel();
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    logger.setLevel(Level.valueOf(level));
    try {
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth("SyntheticTokenCanary");
      var response =
          new de.caritas.cob.userservice.api.adapters.http.TrackedHttpTransport(template)
              .exchange(
                  uri,
                  HttpMethod.PUT,
                  new HttpEntity<>(Map.of("password", "MatrixPasswordCanary"), headers),
                  Map.class);
      assertThat(response.getBody()).containsEntry("user_id", "MatrixResponseCanary");
      template.getForEntity("https://matrix.invalid/public-control", String.class);
      server.verify();
      String messages =
          capture.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .collect(java.util.stream.Collectors.joining("\n"));
      assertThat(messages).contains("public-control");
      assertThat(messages)
          .doesNotContain(
              "MatrixUriCanary",
              "MatrixPasswordCanary",
              "MatrixResponseCanary",
              "SyntheticTokenCanary");
    } finally {
      logger.setLevel(old);
      logger.detachAppender(capture);
      capture.stop();
    }
  }

  @ParameterizedTest
  @ValueSource(classes = {Void.class, void.class})
  void noBodyResponseTypesIgnoreAcknowledgementsButStillUseTheErrorHandler(Class<Void> type) {
    var template = new RestTemplate();
    var server = MockRestServiceServer.bindTo(template).build();
    var uri = URI.create("https://matrix.invalid/synthetic-delete");
    server
        .expect(requestTo(uri))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .contentType(MediaType.TEXT_PLAIN)
                .body("nonempty acknowledgement")
                .header("X-Acknowledgement", "accepted"));
    server
        .expect(requestTo(uri))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("failed acknowledgement"));
    var transport = new de.caritas.cob.userservice.api.adapters.http.TrackedHttpTransport(template);
    var response =
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> transport.exchange(uri, HttpMethod.DELETE, HttpEntity.EMPTY, type));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getFirst("X-Acknowledgement")).isEqualTo("accepted");
    assertThat(response.getBody()).isNull();
    assertThatThrownBy(() -> transport.exchange(uri, HttpMethod.DELETE, HttpEntity.EMPTY, type))
        .isInstanceOf(org.springframework.web.client.HttpClientErrorException.BadRequest.class);
    server.verify();
  }
}
