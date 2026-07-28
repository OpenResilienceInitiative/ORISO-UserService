package de.caritas.cob.userservice.api.adapters.keycloak.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KeycloakAdminClientTransportTest {

  private HttpServer server;
  private ExecutorService serverExecutor;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
    if (serverExecutor != null) {
      serverExecutor.shutdownNow();
    }
  }

  @Test
  void slowAdminResponseIsBoundedAndMeasuredAsTransportFailure() throws IOException {
    var registry = new SimpleMeterRegistry();
    startSlowAdminStub();
    var transport =
        new KeycloakAdminClientTransport(
            new OutboundHttpMetrics(registry),
            Duration.ofMillis(100),
            Duration.ofMillis(100),
            Duration.ofMillis(100));
    var startedAt = System.nanoTime();

    try (var keycloak = transport.create(serverUrl(), "oriso", adminConfig())) {
      assertThatThrownBy(() -> keycloak.realm("oriso").users().count())
          .isInstanceOf(jakarta.ws.rs.ProcessingException.class);
    }

    assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", "keycloak", "method", "get", "outcome", "io_error")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void transportFailureIsNotRetriedBelowTheMeasuredAttempt() throws IOException {
    var registry = new SimpleMeterRegistry();
    var adminRequests = startDisconnectingAdminStub();
    var transport = new KeycloakAdminClientTransport(new OutboundHttpMetrics(registry));

    try (var keycloak = transport.create(serverUrl(), "oriso", adminConfig())) {
      assertThatThrownBy(() -> keycloak.realm("oriso").users().count())
          .isInstanceOf(jakarta.ws.rs.ProcessingException.class);
    }

    assertThat(adminRequests).hasValue(1);
    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", "keycloak", "method", "get", "outcome", "io_error")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void exhaustedConnectionPoolFailsWithinTheCheckoutTimeout() throws Exception {
    var registry = new SimpleMeterRegistry();
    var stub = startBlockingAdminStub();
    var transport =
        new KeycloakAdminClientTransport(
            new OutboundHttpMetrics(registry),
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofMillis(100),
            1);

    try (var keycloak = transport.create(serverUrl(), "oriso", adminConfig());
        var caller = Executors.newSingleThreadExecutor()) {
      CompletableFuture<Integer> firstCall =
          CompletableFuture.supplyAsync(() -> keycloak.realm("oriso").users().count(), caller);
      assertThat(stub.requestStarted().await(2, TimeUnit.SECONDS)).isTrue();
      var startedAt = System.nanoTime();

      try {
        assertThatThrownBy(() -> keycloak.realm("oriso").users().count())
            .isInstanceOf(jakarta.ws.rs.ProcessingException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
            .isLessThan(Duration.ofSeconds(1));
      } finally {
        stub.releaseResponse().countDown();
      }

      assertThat(firstCall.get(2, TimeUnit.SECONDS)).isZero();
    }

    assertThat(stub.adminRequests()).hasValue(1);
    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", "keycloak", "method", "get", "outcome", "io_error")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void pooledSingletonAllowsParallelAdminCallsWithOneSharedToken() throws Exception {
    var registry = new SimpleMeterRegistry();
    var stub = startConcurrentAdminStub(8);
    var transport = new KeycloakAdminClientTransport(new OutboundHttpMetrics(registry));

    try (var keycloak = transport.create(serverUrl(), "oriso", adminConfig());
        var callers = Executors.newFixedThreadPool(8)) {
      List<CompletableFuture<Integer>> calls =
          IntStream.range(0, 8)
              .mapToObj(
                  ignored ->
                      CompletableFuture.supplyAsync(
                          () -> keycloak.realm("oriso").users().count(), callers))
              .toList();

      for (var call : calls) {
        assertThat(call.get(3, TimeUnit.SECONDS)).isZero();
      }
    }

    assertThat(stub.tokenRequests()).hasValue(1);
    assertThat(stub.adminRequests()).hasValue(8);
    assertThat(stub.maxConcurrentRequests().get()).isGreaterThan(1);
    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", "keycloak", "method", "get", "outcome", "2xx")
                .counter()
                .count())
        .isEqualTo(8);
  }

  private void startSlowAdminStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if (!exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token")) {
            try {
              Thread.sleep(1500);
            } catch (InterruptedException interruptedException) {
              Thread.currentThread().interrupt();
            }
          }
          var responseBody =
              exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token")
                  ? """
                    {"access_token":"token","expires_in":300,"refresh_expires_in":1800,
                     "refresh_token":"refresh","token_type":"Bearer","session_state":"session"}
                    """
                  : "0";
          var responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBytes.length);
          exchange.getResponseBody().write(responseBytes);
          exchange.close();
        });
    server.start();
  }

  private AtomicInteger startDisconnectingAdminStub() throws IOException {
    var adminRequests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if (exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token")) {
            var responseBytes =
                """
                {"access_token":"token","expires_in":300,"refresh_expires_in":1800,
                 "refresh_token":"refresh","token_type":"Bearer","session_state":"session"}
                """
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
          } else {
            adminRequests.incrementAndGet();
          }
          exchange.close();
        });
    server.start();
    return adminRequests;
  }

  private BlockingAdminStub startBlockingAdminStub() throws IOException {
    var requestStarted = new CountDownLatch(1);
    var releaseResponse = new CountDownLatch(1);
    var adminRequests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          var tokenRequest =
              exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token");
          if (!tokenRequest) {
            adminRequests.incrementAndGet();
            requestStarted.countDown();
            try {
              releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interruptedException) {
              Thread.currentThread().interrupt();
            }
          }
          var responseBody =
              tokenRequest
                  ? """
                    {"access_token":"token","expires_in":300,"refresh_expires_in":1800,
                     "refresh_token":"refresh","token_type":"Bearer","session_state":"session"}
                    """
                  : "0";
          var responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBytes.length);
          exchange.getResponseBody().write(responseBytes);
          exchange.close();
        });
    server.start();
    return new BlockingAdminStub(requestStarted, releaseResponse, adminRequests);
  }

  private ConcurrentAdminStub startConcurrentAdminStub(int expectedAdminRequests)
      throws IOException {
    var tokenRequests = new AtomicInteger();
    var adminRequests = new AtomicInteger();
    var activeRequests = new AtomicInteger();
    var maxConcurrentRequests = new AtomicInteger();
    var allRequestsStarted = new CountDownLatch(expectedAdminRequests);
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          var tokenRequest =
              exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token");
          if (tokenRequest) {
            tokenRequests.incrementAndGet();
          } else {
            adminRequests.incrementAndGet();
            var active = activeRequests.incrementAndGet();
            maxConcurrentRequests.accumulateAndGet(active, Math::max);
            allRequestsStarted.countDown();
            try {
              allRequestsStarted.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interruptedException) {
              Thread.currentThread().interrupt();
            }
          }
          try {
            var responseBody =
                tokenRequest
                    ? """
                      {"access_token":"token","expires_in":300,"refresh_expires_in":1800,
                       "refresh_token":"refresh","token_type":"Bearer","session_state":"session"}
                      """
                    : "0";
            var responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
          } finally {
            if (!tokenRequest) {
              activeRequests.decrementAndGet();
            }
            exchange.close();
          }
        });
    server.start();
    return new ConcurrentAdminStub(tokenRequests, adminRequests, maxConcurrentRequests);
  }

  private String serverUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  private KeycloakCustomConfig adminConfig() {
    var config = new KeycloakCustomConfig();
    config.setAdminUsername("admin");
    config.setAdminPassword("password");
    config.setAdminClientId("admin-cli");
    return config;
  }

  private record BlockingAdminStub(
      CountDownLatch requestStarted, CountDownLatch releaseResponse, AtomicInteger adminRequests) {}

  private record ConcurrentAdminStub(
      AtomicInteger tokenRequests,
      AtomicInteger adminRequests,
      AtomicInteger maxConcurrentRequests) {}
}
