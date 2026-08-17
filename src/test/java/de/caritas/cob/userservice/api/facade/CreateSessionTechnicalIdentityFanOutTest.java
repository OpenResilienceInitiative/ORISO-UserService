package de.caritas.cob.userservice.api.facade;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.config.AppConfig;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.httpheader.HttpHeadersResolver;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.service.identity.TechnicalIdentityTokenProvider;
import de.caritas.cob.userservice.api.service.session.AgencyPreAssignmentRoomService;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService;
import de.caritas.cob.userservice.api.service.session.DirectSessionMatrixRoomService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Full service-path proof for the measured {@code POST /users/askers/session/new} identity fan-out.
 *
 * <p>The database-facing collaborators are deterministic test doubles, while the technical Keycloak
 * grant and AgencyService credential lookup cross real loopback HTTP boundaries through the
 * production clients. The real {@link CreateSessionFacade}, {@link AgencyPreAssignmentRoomService},
 * {@link AgencyMatrixCredentialClient}, {@link TechnicalIdentityTokenProvider}, and {@link
 * KeycloakAuthClient} compose the path.
 */
class CreateSessionTechnicalIdentityFanOutTest {

  private static final long AGENCY_ID = 42L;
  private static final int SESSION_COUNT = 64;
  private static final String TECHNICAL_USERNAME = "technical-user";
  private static final String TECHNICAL_PASSWORD = "technical-password";
  private static final String AGENCY_MATRIX_USER = "@agency-service:matrix.example";
  private static final String AGENCY_MATRIX_PASSWORD = "matrix-password";

  private final AtomicReference<AgencyBehavior> agencyBehavior =
      new AtomicReference<>(AgencyBehavior.ACCEPT_SHARED);
  private final AtomicInteger identityGrantCount = new AtomicInteger();
  private final AtomicInteger agencyAttemptCount = new AtomicInteger();
  private final CopyOnWriteArrayList<String> identityGrantBodies = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<String> agencyAuthorizationHeaders =
      new CopyOnWriteArrayList<>();

  private HttpServer identityServer;
  private HttpServer agencyServer;
  private ExecutorService identityServerExecutor;
  private ExecutorService agencyServerExecutor;
  private Logger roomServiceLogger;
  private Level originalRoomServiceLogLevel;
  private SimpleMeterRegistry meterRegistry;
  private SessionRoomGateway sessionRoomGateway;
  private CreateSessionFacade createSessionFacade;

  @BeforeEach
  void setUp() throws Exception {
    roomServiceLogger = (Logger) LoggerFactory.getLogger(AgencyPreAssignmentRoomService.class);
    originalRoomServiceLogLevel = roomServiceLogger.getLevel();
    roomServiceLogger.setLevel(Level.WARN);

    identityServer = HttpServer.create(new InetSocketAddress(0), 0);
    identityServer.createContext("/openid/token", this::handleIdentityGrant);
    identityServerExecutor = Executors.newCachedThreadPool();
    identityServer.setExecutor(identityServerExecutor);
    identityServer.start();

    agencyServer = HttpServer.create(new InetSocketAddress(0), 0);
    agencyServer.createContext(
        "/internal/agencies/" + AGENCY_ID + "/matrix-service-account",
        this::handleAgencyCredentials);
    agencyServerExecutor = Executors.newCachedThreadPool();
    agencyServer.setExecutor(agencyServerExecutor);
    agencyServer.start();

    meterRegistry = new SimpleMeterRegistry();
    createSessionFacade = composeCreateSessionPath();
  }

  @AfterEach
  void tearDown() {
    if (identityServer != null) {
      identityServer.stop(0);
    }
    if (agencyServer != null) {
      agencyServer.stop(0);
    }
    if (identityServerExecutor != null) {
      identityServerExecutor.close();
    }
    if (agencyServerExecutor != null) {
      agencyServerExecutor.close();
    }
    if (meterRegistry != null) {
      meterRegistry.close();
    }
    if (roomServiceLogger != null) {
      roomServiceLogger.setLevel(originalRoomServiceLogLevel);
    }
  }

  @Test
  void parallelSessionCreationUsesOneIdentityGrantAndOneAgencyCallPerSession() throws Exception {
    agencyBehavior.set(AgencyBehavior.ACCEPT_SHARED);

    List<Long> sessionIds = createSessionsInParallel(SESSION_COUNT);

    assertThat(sessionIds).hasSize(SESSION_COUNT).doesNotHaveDuplicates();
    assertThat(identityGrantCount).hasValue(1);
    assertIdentityGrantForms();
    assertThat(agencyAttemptCount).hasValue(SESSION_COUNT);
    assertThat(agencyAuthorizationHeaders)
        .hasSize(SESSION_COUNT)
        .containsOnly("Bearer shared-token");
    verify(sessionRoomGateway, times(SESSION_COUNT)).createRoom(any(), any(), any());
    assertOutboundCallCount("localhost", "post", "2xx", 1);
    assertOutboundCallCount("127.0.0.1", "get", "2xx", SESSION_COUNT);
    assertOutboundLatencyCount("localhost", "post", "2xx", 1);
    assertOutboundLatencyCount("127.0.0.1", "get", "2xx", SESSION_COUNT);
    assertThat(
            meterRegistry
                .get("userservice.outbound.http.payload")
                .tags("dependency", "127.0.0.1", "direction", "response")
                .summary()
                .count())
        .isEqualTo(SESSION_COUNT);
  }

  @Test
  void parallelStaleGrantRefreshIsSharedAndBoundedToOneRetryPerSession() throws Exception {
    agencyBehavior.set(AgencyBehavior.REJECT_STALE_THEN_ACCEPT);

    List<Long> sessionIds = createSessionsInParallel(SESSION_COUNT);

    long staleAttempts =
        agencyAuthorizationHeaders.stream().filter("Bearer stale-token"::equals).count();
    long freshAttempts =
        agencyAuthorizationHeaders.stream().filter("Bearer fresh-token"::equals).count();

    assertThat(sessionIds).hasSize(SESSION_COUNT).doesNotHaveDuplicates();
    assertThat(identityGrantCount).hasValue(2);
    assertIdentityGrantForms();
    assertThat(staleAttempts).isBetween(1L, (long) SESSION_COUNT);
    assertThat(freshAttempts).isEqualTo(SESSION_COUNT);
    assertThat(agencyAttemptCount).hasValue((int) (staleAttempts + freshAttempts));
    assertThat(agencyAttemptCount.get()).isLessThanOrEqualTo(SESSION_COUNT * 2);
    assertRetryCount(staleAttempts);
    assertOutboundCallCount("localhost", "post", "2xx", 2);
    assertOutboundCallCount("127.0.0.1", "get", "4xx", staleAttempts);
    assertOutboundCallCount("127.0.0.1", "get", "2xx", SESSION_COUNT);
    assertOutboundLatencyCount("localhost", "post", "2xx", 2);
    assertOutboundLatencyCount("127.0.0.1", "get", "4xx", staleAttempts);
    assertOutboundLatencyCount("127.0.0.1", "get", "2xx", SESSION_COUNT);
    assertAgencyResponsePayloadCount(agencyAttemptCount.get());
    verify(sessionRoomGateway, times(SESSION_COUNT)).createRoom(any(), any(), any());
  }

  @Test
  void persistentlyRejectedRefreshIsInvalidatedWithoutThirdAttemptInSameSession() {
    agencyBehavior.set(AgencyBehavior.REJECT_ALL);

    createSession(1);
    createSession(2);

    assertThat(identityGrantCount).hasValue(4);
    assertIdentityGrantForms();
    assertThat(agencyAttemptCount).hasValue(4);
    assertThat(agencyAuthorizationHeaders)
        .containsExactly(
            "Bearer rejected-token-1",
            "Bearer rejected-token-2",
            "Bearer rejected-token-3",
            "Bearer rejected-token-4");
    assertRetryCount(2);
    assertOutboundCallCount("localhost", "post", "2xx", 4);
    assertOutboundCallCount("127.0.0.1", "get", "4xx", 4);
    assertOutboundLatencyCount("localhost", "post", "2xx", 4);
    assertOutboundLatencyCount("127.0.0.1", "get", "4xx", 4);
    assertAgencyResponsePayloadCount(4);
    verifyNoInteractions(sessionRoomGateway);
  }

  private CreateSessionFacade composeCreateSessionPath() throws Exception {
    var outboundHttpMetrics = new OutboundHttpMetrics(meterRegistry);
    var restTemplate = new AppConfig().restTemplate(new RestTemplateBuilder(), outboundHttpMetrics);

    var identityConfig = new IdentityConfig();
    identityConfig.setOpenidConnectUrl(
        "http://localhost:" + identityServer.getAddress().getPort() + "/openid");
    var technicalUser = new TechnicalUserConfig();
    technicalUser.setUsername(TECHNICAL_USERNAME);
    technicalUser.setPassword(TECHNICAL_PASSWORD);
    identityConfig.setTechnicalUser(technicalUser);

    var keycloakAuthClient =
        new KeycloakAuthClient(restTemplate, mock(AuthenticatedUser.class), identityConfig);
    ReflectionTestUtils.setField(keycloakAuthClient, "keycloakClientId", "user-service");
    IdentityAuthentication identityAuthentication = identityAuthentication(keycloakAuthClient);
    var tokenProvider =
        new TechnicalIdentityTokenProvider(
            identityAuthentication, identityConfig, Clock.systemUTC());

    var securityHeaderSupplier = new SecurityHeaderSupplier(mock(AuthenticatedUser.class));
    ReflectionTestUtils.setField(securityHeaderSupplier, "csrfHeaderProperty", "X-CSRF-Token");
    ReflectionTestUtils.setField(securityHeaderSupplier, "csrfCookieProperty", "CSRF-TOKEN");
    var tenantHeaderSupplier = new TenantHeaderSupplier(new HttpHeadersResolver());
    ReflectionTestUtils.setField(tenantHeaderSupplier, "multitenancy", false);

    var credentialClient =
        new AgencyMatrixCredentialClient(
            restTemplate,
            securityHeaderSupplier,
            tenantHeaderSupplier,
            tokenProvider,
            outboundHttpMetrics);
    ReflectionTestUtils.setField(
        credentialClient,
        "agencyServiceBaseUrl",
        "http://127.0.0.1:" + agencyServer.getAddress().getPort());

    sessionRoomGateway = mock(SessionRoomGateway.class);
    var roomSequence = new AtomicInteger();
    when(sessionRoomGateway.loginUser(any(), any())).thenReturn("agency-matrix-token");
    when(sessionRoomGateway.loginAsUser(any())).thenReturn("asker-matrix-token");
    when(sessionRoomGateway.createRoom(any(), any(), any()))
        .thenAnswer(invocation -> "!holding-" + roomSequence.incrementAndGet() + ":matrix.example");
    when(sessionRoomGateway.joinRoom(any(), any())).thenReturn(true);

    var sessionService = mock(SessionService.class);
    var sessionSequence = new AtomicLong(1_000);
    when(sessionService.initializeSession(any(), any(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              var user = invocation.getArgument(0, User.class);
              var userDto = invocation.getArgument(1, UserDTO.class);
              var session = new Session();
              session.setId(sessionSequence.incrementAndGet());
              session.setUser(user);
              session.setAgencyId(userDto.getAgencyId());
              return session;
            });
    when(sessionService.saveSession(any(Session.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var agencyVerifier = mock(AgencyVerifier.class);
    when(agencyVerifier.getVerifiedAgency(any(), anyInt()))
        .thenReturn(new AgencyDTO().id(AGENCY_ID).consultingType(1).teamAgency(false));

    var roomService =
        new AgencyPreAssignmentRoomService(
            credentialClient,
            sessionRoomGateway,
            sessionService,
            mock(AgencySilentMembershipService.class));

    return new CreateSessionFacade(
        sessionService,
        agencyVerifier,
        mock(SessionDataService.class),
        mock(RollbackFacade.class),
        mock(UserAccountService.class),
        mock(ConsultantAgencyRepository.class),
        roomService,
        mock(DirectSessionMatrixRoomService.class));
  }

  private IdentityAuthentication identityAuthentication(KeycloakAuthClient keycloakAuthClient) {
    return new IdentityAuthentication() {
      @Override
      public IdentityLogin login(String username, String password) {
        var login = keycloakAuthClient.loginUser(username, password);
        return new IdentityLogin(
            login.getAccessToken(),
            login.getExpiresIn(),
            login.getRefreshExpiresIn(),
            login.getRefreshToken());
      }

      @Override
      public boolean logout(String refreshToken) {
        throw new UnsupportedOperationException("Not used by this contract");
      }

      @Override
      public boolean verifyPasswordIgnoringSecondFactor(String username, String password) {
        throw new UnsupportedOperationException("Not used by this contract");
      }
    };
  }

  private List<Long> createSessionsInParallel(int count) throws Exception {
    try (var executor = Executors.newFixedThreadPool(16)) {
      var requests =
          java.util.stream.IntStream.range(0, count)
              .mapToObj(index -> (java.util.concurrent.Callable<Long>) () -> createSession(index))
              .toList();
      return executor.invokeAll(requests).stream()
          .map(
              result -> {
                try {
                  return result.get();
                } catch (Exception exception) {
                  throw new AssertionError(exception);
                }
              })
          .toList();
    }
  }

  private Long createSession(int index) {
    var user = new User();
    user.setUserId("user-" + index);
    user.setUsername("user-" + index);
    user.setMatrixUserId("@user-" + index + ":matrix.example");

    var userDto = new UserDTO();
    userDto.setAgencyId(AGENCY_ID);
    userDto.setConsultingType("1");
    userDto.setPostcode("12345");
    userDto.setTermsAccepted("true");

    var consultingType = new ExtendedConsultingTypeResponseDTO();
    consultingType.setId(1);
    return createSessionFacade.createUserSession(userDto, user, consultingType, List.of());
  }

  private void handleIdentityGrant(HttpExchange exchange) throws IOException {
    int grantNumber = identityGrantCount.incrementAndGet();
    String requestBody = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
    identityGrantBodies.add(requestBody);

    String token =
        switch (agencyBehavior.get()) {
          case ACCEPT_SHARED -> "shared-token";
          case REJECT_STALE_THEN_ACCEPT -> grantNumber == 1 ? "stale-token" : "fresh-token";
          case REJECT_ALL -> "rejected-token-" + grantNumber;
        };
    respond(
        exchange,
        200,
        """
        {
          "access_token": "%s",
          "expires_in": 300,
          "refresh_expires_in": 1800,
          "refresh_token": "refresh-token",
          "token_type": "Bearer"
        }
        """
            .formatted(token));
  }

  private void handleAgencyCredentials(HttpExchange exchange) throws IOException {
    agencyAttemptCount.incrementAndGet();
    String authorization = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    agencyAuthorizationHeaders.add(authorization);

    boolean accepted =
        switch (agencyBehavior.get()) {
          case ACCEPT_SHARED -> "Bearer shared-token".equals(authorization);
          case REJECT_STALE_THEN_ACCEPT -> "Bearer fresh-token".equals(authorization);
          case REJECT_ALL -> false;
        };
    if (!accepted) {
      respond(exchange, 401, "{\"error\":\"unauthorized\"}");
      return;
    }

    respond(
        exchange,
        200,
        """
        {
          "matrixUserId": "%s",
          "matrixPassword": "%s"
        }
        """
            .formatted(AGENCY_MATRIX_USER, AGENCY_MATRIX_PASSWORD));
  }

  private void assertOutboundCallCount(
      String dependency, String method, String outcome, long expected) {
    assertThat(
            meterRegistry
                .get("userservice.outbound.http.calls")
                .tags("dependency", dependency, "method", method, "outcome", outcome)
                .counter()
                .count())
        .isEqualTo(expected);
  }

  private void assertOutboundLatencyCount(
      String dependency, String method, String outcome, long expected) {
    assertThat(
            meterRegistry
                .get("userservice.outbound.http.latency")
                .tags("dependency", dependency, "method", method, "outcome", outcome)
                .timer()
                .count())
        .isEqualTo(expected);
  }

  private void assertAgencyResponsePayloadCount(long expected) {
    assertThat(
            meterRegistry
                .get("userservice.outbound.http.payload")
                .tags("dependency", "127.0.0.1", "direction", "response")
                .summary()
                .count())
        .isEqualTo(expected);
  }

  private void assertIdentityGrantForms() {
    assertThat(identityGrantBodies)
        .hasSize(identityGrantCount.get())
        .allSatisfy(
            requestBody ->
                assertThat(requestBody)
                    .contains("grant_type=password")
                    .contains("username=" + TECHNICAL_USERNAME)
                    .contains("password=" + TECHNICAL_PASSWORD));
  }

  private void assertRetryCount(long expected) {
    assertThat(
            meterRegistry
                .get("userservice.outbound.retries")
                .tags(
                    "dependency", "agency-service", "operation", "matrix-credentials-auth-refresh")
                .counter()
                .count())
        .isEqualTo(expected);
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    } finally {
      exchange.close();
    }
  }

  private enum AgencyBehavior {
    ACCEPT_SHARED,
    REJECT_STALE_THEN_ACCEPT,
    REJECT_ALL
  }
}
