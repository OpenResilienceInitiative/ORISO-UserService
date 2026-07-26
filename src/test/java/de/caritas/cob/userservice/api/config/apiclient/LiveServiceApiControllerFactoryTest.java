package de.caritas.cob.userservice.api.config.apiclient;

import static de.caritas.cob.userservice.liveservice.generated.web.model.EventType.DIRECT_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.liveservice.generated.ApiException;
import de.caritas.cob.userservice.liveservice.generated.web.model.LiveEventMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LiveServiceApiControllerFactoryTest {

  private HttpServer httpServer;

  @AfterEach
  void stopServer() {
    if (httpServer != null) {
      httpServer.stop(0);
    }
  }

  @Test
  void shouldMeasureSuccessfulAsyncLiveServiceCall() throws Exception {
    var responseBody = "";
    startServer(202, responseBody);
    var registry = new SimpleMeterRegistry();
    var objectMapper = new ObjectMapper();
    var factory =
        new LiveServiceApiControllerFactory(objectMapper, new OutboundHttpMetrics(registry));
    ReflectionTestUtils.setField(factory, "liveServiceApiUrl", serverBaseUrl());
    var message = new LiveEventMessage().eventType(DIRECT_MESSAGE).userIds(List.of("consultant-1"));

    factory.createControllerApi().sendLiveEvent(message).join();

    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", loopbackHost(), "method", "post", "outcome", "2xx")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("userservice.outbound.http.payload")
                .tags("dependency", loopbackHost(), "direction", "request")
                .summary()
                .totalAmount())
        .isEqualTo(objectMapper.writeValueAsBytes(message).length);
  }

  @Test
  void shouldMeasureHttpFailureBeforeCompletingFutureExceptionally() throws Exception {
    var responseBody = "{\"error\":\"temporarily unavailable\"}";
    startServer(503, responseBody);
    var registry = new SimpleMeterRegistry();
    var factory =
        new LiveServiceApiControllerFactory(new ObjectMapper(), new OutboundHttpMetrics(registry));
    ReflectionTestUtils.setField(factory, "liveServiceApiUrl", serverBaseUrl());

    assertThatThrownBy(
            () ->
                factory
                    .createControllerApi()
                    .sendLiveEvent(new LiveEventMessage().eventType(DIRECT_MESSAGE))
                    .join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ApiException.class);

    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", loopbackHost(), "method", "post", "outcome", "5xx")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("userservice.outbound.http.payload")
                .tags("dependency", loopbackHost(), "direction", "response")
                .summary()
                .totalAmount())
        .isEqualTo(responseBody.getBytes(StandardCharsets.UTF_8).length);
  }

  private void startServer(int status, String responseBody) throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    httpServer.createContext(
        "/liveevent/send",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    httpServer.start();
  }

  private String serverBaseUrl() {
    return "http://" + loopbackHost() + ":" + httpServer.getAddress().getPort();
  }

  private String loopbackHost() {
    return InetAddress.getLoopbackAddress().getHostAddress();
  }
}
