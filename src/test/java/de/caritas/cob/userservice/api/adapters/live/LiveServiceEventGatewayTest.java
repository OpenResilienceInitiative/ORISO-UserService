package de.caritas.cob.userservice.api.adapters.live;

import static de.caritas.cob.userservice.api.service.liveevents.LiveEvent.FinishConversationPhase.IN_PROGRESS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.service.liveevents.LiveEvent;
import de.caritas.cob.userservice.liveservice.generated.web.LiveControllerApi;
import de.caritas.cob.userservice.liveservice.generated.web.model.EventType;
import de.caritas.cob.userservice.liveservice.generated.web.model.LiveEventMessage;
import de.caritas.cob.userservice.liveservice.generated.web.model.StatusSource;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiveServiceEventGatewayTest {

  @Mock private LiveControllerApi liveControllerApi;

  @Test
  void shouldObserveAndContainAsynchronousDeliveryFailures() throws Exception {
    var registry = new SimpleMeterRegistry();
    var transport = new CompletableFuture<Void>();
    when(liveControllerApi.sendLiveEvent(any())).thenReturn(transport);
    var gateway =
        new LiveServiceEventGateway(
            liveControllerApi, new ObjectMapper(), new OutboundHttpMetrics(registry));

    try (var logCaptor = LogbackCaptor.forClass(LiveServiceEventGateway.class)) {
      gateway.send(LiveEvent.directMessage(List.of("recipient-1")));
      transport.completeExceptionally(new IllegalStateException("unreachable"));

      assertThat(logCaptor.contains(Level.ERROR, "Unable to deliver live event")).isTrue();
    }
    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", "live-service", "method", "post", "outcome", "async_error")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void shouldReuseOneHttpClientAndMeasureActualRequestBytes() throws Exception {
    var registry = new SimpleMeterRegistry();
    var requestBytes = new AtomicLong();
    var remotePorts = new CopyOnWriteArrayList<Integer>();
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/liveevent/send",
        exchange -> {
          var body = exchange.getRequestBody().readAllBytes();
          requestBytes.addAndGet(body.length);
          remotePorts.add(exchange.getRemoteAddress().getPort());
          exchange.sendResponseHeaders(202, -1);
          exchange.close();
        });
    server.start();

    try {
      var baseUrl =
          "http://"
              + server.getAddress().getAddress().getHostAddress()
              + ":"
              + server.getAddress().getPort();
      var gateway =
          new LiveServiceEventGateway(
              baseUrl, new ObjectMapper(), new OutboundHttpMetrics(registry));

      gateway.send(LiveEvent.directMessage(List.of("recipient-1")));
      await()
          .atMost(5, SECONDS)
          .untilAsserted(() -> assertThat(successCount(registry)).isEqualTo(1));
      gateway.send(LiveEvent.directMessage(List.of("recipient-2")));
      await()
          .atMost(5, SECONDS)
          .untilAsserted(() -> assertThat(successCount(registry)).isEqualTo(2));

      assertThat(Set.copyOf(remotePorts)).hasSize(1);
      assertThat(
              registry
                  .get("userservice.outbound.http.payload")
                  .tags("dependency", "live-service", "direction", "request")
                  .summary()
                  .totalAmount())
          .isEqualTo(requestBytes.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void shouldMapFinishedConversationDetailsInsideTheAdapter() throws Exception {
    when(liveControllerApi.sendLiveEvent(any()))
        .thenReturn(CompletableFuture.completedFuture(null));
    var gateway =
        new LiveServiceEventGateway(
            liveControllerApi,
            new ObjectMapper(),
            new OutboundHttpMetrics(new SimpleMeterRegistry()));

    gateway.send(LiveEvent.anonymousConversationFinished(List.of("recipient-1"), IN_PROGRESS));

    var message = ArgumentCaptor.forClass(LiveEventMessage.class);
    verify(liveControllerApi).sendLiveEvent(message.capture());
    assertThat(message.getValue().getEventType())
        .isEqualTo(EventType.ANONYMOUS_CONVERSATION_FINISHED);
    assertThat(((StatusSource) message.getValue().getEventContent()).getFinishConversationPhase())
        .isEqualTo(StatusSource.FinishConversationPhaseEnum.IN_PROGRESS);
  }

  @Test
  void shouldObserveAndContainNonSuccessfulHttpResponses() throws Exception {
    var registry = new SimpleMeterRegistry();
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/liveevent/send",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    server.start();

    try {
      var baseUrl =
          "http://"
              + server.getAddress().getAddress().getHostAddress()
              + ":"
              + server.getAddress().getPort();
      var gateway =
          new LiveServiceEventGateway(
              baseUrl, new ObjectMapper(), new OutboundHttpMetrics(registry));

      try (var logCaptor = LogbackCaptor.forClass(LiveServiceEventGateway.class)) {
        gateway.send(LiveEvent.directMessage(List.of("recipient-1")));

        await()
            .atMost(5, SECONDS)
            .untilAsserted(
                () -> {
                  assertThat(
                          registry
                              .get("userservice.outbound.http.calls")
                              .tags(
                                  "dependency",
                                  "live-service",
                                  "method",
                                  "post",
                                  "outcome",
                                  "async_error")
                              .counter()
                              .count())
                      .isEqualTo(1);
                  assertThat(logCaptor.contains(Level.ERROR, "Unable to deliver live event"))
                      .isTrue();
                });
      }
    } finally {
      server.stop(0);
    }
  }

  private double successCount(SimpleMeterRegistry registry) {
    return registry
        .get("userservice.outbound.http.calls")
        .tags("dependency", "live-service", "method", "post", "outcome", "2xx")
        .counter()
        .count();
  }
}
