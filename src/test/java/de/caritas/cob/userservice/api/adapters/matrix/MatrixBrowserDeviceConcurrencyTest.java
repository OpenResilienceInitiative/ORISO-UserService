package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/** Real HTTP boundary tests with deliberately delayed device operations, without internal mocks. */
class MatrixBrowserDeviceConcurrencyTest {
  private static final String ALICE = "@alice:matrix.example.test";
  private static final String BOB = "@bob:matrix.example.test";
  private static final String ADMIN_USER = "synthetic-admin";
  private static final String ADMIN_PASSWORD = "synthetic-admin-password";
  private static final String SHARED_SECRET = "synthetic-registration-secret-for-tests-only";

  @Test
  void deviceACompletesDelayedInteractiveAuthenticationAfterDeviceBLogsIn() throws Exception {
    try (var synapse = new StatefulSynapse()) {
      var deviceA = synapse.newService().loginBrowserDevice(ALICE, "DEVICE_A");
      assertThat(deviceA).as("the first browser receives a usable login").isNotNull();
      var workers = Executors.newSingleThreadExecutor();
      try {
        var delayedAuthentication = workers.submit(() -> synapse.authenticate(deviceA, ALICE));
        assertThat(synapse.interactiveAuthStarted.await(5, TimeUnit.SECONDS))
            .as("device A reaches the homeserver and waits before password verification")
            .isTrue();

        // A different UserService instance represents a second browser served by another pod.
        var deviceB = synapse.newService().loginBrowserDevice(ALICE, "DEVICE_B");
        assertThat(deviceB).as("the second browser receives a usable login").isNotNull();
        synapse.releaseInteractiveAuth.countDown();

        assertThat(delayedAuthentication.get(5, TimeUnit.SECONDS))
            .as("device A's issued credential still authenticates after device B logs in")
            .isEqualTo(200);
      } finally {
        synapse.releaseInteractiveAuth.countDown();
        workers.shutdownNow();
      }
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void missingCredentialRootFailsWithoutChangingTheExistingAccountPassword(String root)
      throws Exception {
    try (var synapse = new StatefulSynapse()) {
      var originalDevice = synapse.newService().loginBrowserDevice(ALICE, "DEVICE_A");
      assertThat(originalDevice).isNotNull();
      var writesBeforeMissingConfig = synapse.passwordUpdateRequests.get();

      var failedLogin =
          synapse.newService(root, System::currentTimeMillis).loginBrowserDevice(ALICE, "DEVICE_B");
      // Report only success/failure so assertion diagnostics cannot expose credential-bearing maps.
      assertThat(failedLogin == null)
          .as("a missing credential root cannot fall back to replacing the account password")
          .isTrue();
      assertThat(synapse.passwordUpdateRequests.get())
          .as("the homeserver receives no password update after configuration failure")
          .isEqualTo(writesBeforeMissingConfig);
      synapse.releaseInteractiveAuth.countDown();
      assertThat(synapse.authenticate(originalDevice, ALICE))
          .as("the existing browser can still authenticate after a misconfigured instance fails")
          .isEqualTo(200);
    }
  }

  @Test
  void interactiveAuthenticationCredentialsCannotAuthenticateAnotherAccount() throws Exception {
    try (var synapse = new StatefulSynapse()) {
      var alice = synapse.newService().loginBrowserDevice(ALICE, "DEVICE_A");
      var bob = synapse.newService().loginBrowserDevice(BOB, "DEVICE_B");
      assertThat(alice).isNotNull();
      assertThat(bob).isNotNull();
      synapse.releaseInteractiveAuth.countDown();

      assertThat(synapse.authenticate(alice, ALICE)).isEqualTo(200);
      assertThat(synapse.authenticate(bob, BOB)).isEqualTo(200);
      assertThat(synapse.authenticate(bob, BOB, alice.get("interactive_auth_password")))
          .as("an account's issued credential cannot authorize a different account's reset")
          .isEqualTo(403);
    }
  }

  @Test
  void issuedInteractiveAuthenticationSurvivesServiceRestartAndCacheExpiry() throws Exception {
    try (var synapse = new StatefulSynapse()) {
      var now = new AtomicLong(1_000);
      var existingService = synapse.newService(SHARED_SECRET, now::get);
      var originalDevice = existingService.loginBrowserDevice(ALICE, "DEVICE_A");
      assertThat(originalDevice).isNotNull();

      // Expire every existing token cache, without waiting fifty real minutes.
      now.addAndGet(TimeUnit.MINUTES.toMillis(51));
      assertThat(existingService.loginBrowserDevice(ALICE, "DEVICE_B")).isNotNull();
      assertThat(synapse.newService(SHARED_SECRET, now::get).loginBrowserDevice(ALICE, "DEVICE_C"))
          .as("a fresh UserService can log in after the old instance's caches expired")
          .isNotNull();
      synapse.releaseInteractiveAuth.countDown();

      assertThat(synapse.authenticate(originalDevice, ALICE))
          .as("the original browser credential still authenticates after restart and cache expiry")
          .isEqualTo(200);
    }
  }

  @Test
  void overlappingDeviceLoginsOnSeparateServiceInstancesBothComplete() throws Exception {
    try (var synapse = new StatefulSynapse(true)) {
      var workers = Executors.newSingleThreadExecutor();
      try {
        var firstLogin =
            workers.submit(() -> synapse.newService().loginBrowserDevice(ALICE, "DEVICE_A"));
        assertThat(synapse.browserLoginStarted.await(5, TimeUnit.SECONDS))
            .as("device A waits after password update and before login verification")
            .isTrue();
        var secondLogin = synapse.newService().loginBrowserDevice(ALICE, "DEVICE_B");
        assertThat(secondLogin)
            .as("device B logs in while A's login is still in flight")
            .isNotNull();
        synapse.releaseBrowserLogin.countDown();
        var firstDevice = firstLogin.get(5, TimeUnit.SECONDS);
        assertThat(firstDevice).as("device A completes its overlapping login").isNotNull();
        synapse.releaseInteractiveAuth.countDown();
        assertThat(synapse.authenticate(firstDevice, ALICE)).isEqualTo(200);
        assertThat(synapse.authenticate(secondLogin, ALICE)).isEqualTo(200);
      } finally {
        synapse.releaseBrowserLogin.countDown();
        workers.shutdownNow();
      }
    }
  }

  /**
   * Models Synapse's account-wide password and its password verification over HTTP. Issued browser
   * credentials are checked against current server state, never compared to a derived expectation.
   */
  private static final class StatefulSynapse implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> passwords = new ConcurrentHashMap<>();
    private final AtomicInteger passwordUpdateRequests = new AtomicInteger();
    private final CountDownLatch interactiveAuthStarted = new CountDownLatch(1);
    private final CountDownLatch releaseInteractiveAuth = new CountDownLatch(1);
    private final CountDownLatch browserLoginStarted = new CountDownLatch(1);
    private final CountDownLatch releaseBrowserLogin = new CountDownLatch(1);
    private final boolean pauseFirstBrowserLogin;
    private final HttpServer server;
    private final ExecutorService handlers = Executors.newCachedThreadPool();

    private StatefulSynapse() throws IOException {
      this(false);
    }

    private StatefulSynapse(boolean pauseFirstBrowserLogin) throws IOException {
      this.pauseFirstBrowserLogin = pauseFirstBrowserLogin;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.setExecutor(handlers);
      server.start();
    }

    private String url(String path) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private MatrixSynapseService newService() {
      return newService(SHARED_SECRET, System::currentTimeMillis);
    }

    private MatrixSynapseService newService(String sharedSecret, LongSupplier clock) {
      var config = new MatrixConfig();
      config.setApiUrl(url(""));
      config.setServerName("matrix.example.test");
      config.setRegistrationSharedSecret(sharedSecret);
      config.setAdminUsername(ADMIN_USER);
      config.setAdminPassword(ADMIN_PASSWORD);
      var rest = restTemplate();
      return new MatrixSynapseService(
          config, rest, rest, null, null, MatrixIdentifierRedactor.withKey(SHARED_SECRET), clock);
    }

    private int authenticate(Map<String, Object> device, String user) {
      return authenticate(device, user, device.get("interactive_auth_password"));
    }

    private int authenticate(Map<String, Object> device, String user, Object password) {
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth((String) device.get("access_token"));
      var auth = Map.of("type", "m.login.password", "user", user, "password", password);
      try {
        return restTemplate()
            .postForEntity(
                url("/_matrix/client/v3/keys/device_signing/upload"),
                new HttpEntity<>(Map.of("auth", auth), headers),
                Map.class)
            .getStatusCode()
            .value();
      } catch (HttpClientErrorException rejected) {
        return rejected.getStatusCode().value();
      }
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        var body =
            json.readValue(exchange.getRequestBody(), new TypeReference<Map<String, Object>>() {});
        var path = exchange.getRequestURI().getPath();
        if (path.startsWith("/_synapse/admin/v2/users/")
            && exchange.getRequestMethod().equals("PUT")) {
          var user = path.substring("/_synapse/admin/v2/users/".length());
          passwordUpdateRequests.incrementAndGet();
          passwords.put(user, (String) body.get("password"));
          respond(exchange, 200, Map.of("name", user));
        } else if (path.equals("/_matrix/client/r0/login")) {
          var user = (String) body.get("user");
          var password = (String) body.get("password");
          if (pauseFirstBrowserLogin && "DEVICE_A".equals(body.get("device_id"))) {
            browserLoginStarted.countDown();
            try {
              if (!releaseBrowserLogin.await(5, TimeUnit.SECONDS)) {
                respond(exchange, 503, Map.of("errcode", "M_UNKNOWN"));
                return;
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              respond(exchange, 503, Map.of("errcode", "M_UNKNOWN"));
              return;
            }
          }
          if (ADMIN_USER.equals(user) && ADMIN_PASSWORD.equals(password)) {
            respond(exchange, 200, Map.of("access_token", "synthetic-admin-token"));
          } else if (password != null && password.equals(passwords.get(user))) {
            var device = (String) body.get("device_id");
            respond(
                exchange,
                200,
                Map.of(
                    "access_token", "synthetic-token-" + device,
                    "device_id", device,
                    "user_id", user));
          } else {
            respond(exchange, 403, Map.of("errcode", "M_FORBIDDEN"));
          }
        } else if (path.equals("/_matrix/client/v3/keys/device_signing/upload")) {
          interactiveAuthStarted.countDown();
          try {
            if (!releaseInteractiveAuth.await(5, TimeUnit.SECONDS)) {
              respond(exchange, 503, Map.of("errcode", "M_UNKNOWN"));
              return;
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            respond(exchange, 503, Map.of("errcode", "M_UNKNOWN"));
            return;
          }
          @SuppressWarnings("unchecked")
          var auth = (Map<String, Object>) body.get("auth");
          var valid = auth.get("password").equals(passwords.get((String) auth.get("user")));
          respond(exchange, valid ? 200 : 403, valid ? Map.of() : Map.of("errcode", "M_FORBIDDEN"));
        } else {
          respond(exchange, 404, Map.of("errcode", "M_NOT_FOUND"));
        }
      }
    }

    private void respond(HttpExchange exchange, int status, Map<String, ?> body)
        throws IOException {
      var bytes = json.writeValueAsBytes(body);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
      releaseInteractiveAuth.countDown();
      releaseBrowserLogin.countDown();
      server.stop(0);
      handlers.shutdownNow();
    }
  }

  private static RestTemplate restTemplate() {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(6));
    return new RestTemplate(factory);
  }
}
