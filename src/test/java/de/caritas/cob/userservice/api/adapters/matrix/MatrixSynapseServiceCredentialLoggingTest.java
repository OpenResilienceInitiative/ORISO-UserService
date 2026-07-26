package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.util.List;
import java.util.function.Supplier;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Regression for the PreDev finding that Spring's {@link RestTemplate} DEBUG logging serialized
 * outbound Matrix credential bodies ("Writing [...]"), leaking the service-account password into
 * pod logs. The wire request must keep carrying the credential while no log line may contain it,
 * regardless of the configured log level.
 */
class MatrixSynapseServiceCredentialLoggingTest {

  private static final String MATRIX_BASE_URL = "https://matrix.example.com";
  private static final String LOGIN_URL = MATRIX_BASE_URL + "/_matrix/client/r0/login";
  private static final String ADMIN_USERNAME = "matrix-admin";
  private static final String ADMIN_PASSWORD = "super-secret-matrix-password";
  private static final String MATRIX_USER_ID = "@consultant:matrix.example.com";
  private static final String DEVICE_ID = "ORISOWEBDEVICE01";

  private RestTemplate restTemplate;
  private MockRestServiceServer mockServer;
  private MatrixSynapseService service;

  private Logger restTemplateLogger;
  private Level previousLevel;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    restTemplate = new RestTemplate();
    mockServer = MockRestServiceServer.bindTo(restTemplate).build();

    var matrixConfig = new MatrixConfig();
    matrixConfig.setApiUrl(MATRIX_BASE_URL);
    matrixConfig.setAdminUsername(ADMIN_USERNAME);
    matrixConfig.setAdminPassword(ADMIN_PASSWORD);
    var browserLoginCoordinator = mock(MatrixBrowserLoginCoordinator.class);
    when(browserLoginCoordinator.coordinate(anyString(), any()))
        .thenAnswer(
            invocation -> {
              Supplier<?> operation = invocation.getArgument(1);
              return operation.get();
            });

    service =
        new MatrixSynapseService(
            matrixConfig,
            restTemplate,
            restTemplate,
            mock(MatrixRoomClient.class),
            mock(MatrixMediaClient.class),
            browserLoginCoordinator);

    restTemplateLogger = (Logger) LoggerFactory.getLogger(RestTemplate.class);
    previousLevel = restTemplateLogger.getLevel();
    restTemplateLogger.setLevel(Level.DEBUG);
    logAppender = new ListAppender<>();
    logAppender.start();
    restTemplateLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    restTemplateLogger.detachAppender(logAppender);
    restTemplateLogger.setLevel(previousLevel);
  }

  @Test
  void loginUserShouldNotExposePasswordInRestTemplateDebugLogs() {
    mockServer
        .expect(requestTo(LOGIN_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.type").value("m.login.password"))
        .andExpect(jsonPath("$.user").value(ADMIN_USERNAME))
        .andExpect(jsonPath("$.password").value(ADMIN_PASSWORD))
        .andRespond(withSuccess("{\"access_token\":\"syt_token\"}", MediaType.APPLICATION_JSON));

    var accessToken = service.loginUser(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockServer.verify();
    assertThat(accessToken).isEqualTo("syt_token");
    assertThat(bodyWriteLogs()).isNotEmpty();
    assertThat(allLogMessages()).noneMatch(message -> message.contains(ADMIN_PASSWORD));
  }

  @Test
  void loginBrowserDeviceShouldNotExposeAnyPasswordInRestTemplateDebugLogs() throws Exception {
    var sentPasswords = new java.util.ArrayList<String>();
    var objectMapper = new ObjectMapper();

    mockServer
        .expect(requestTo(LOGIN_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.user").value(ADMIN_USERNAME))
        .andRespond(withSuccess("{\"access_token\":\"syt_admin\"}", MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(Matchers.containsString("/_synapse/admin/v2/users/")))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonPath("$.logout_devices").value(false))
        .andExpect(
            request ->
                sentPasswords.add(
                    objectMapper
                        .readTree(((MockClientHttpRequest) request).getBodyAsString())
                        .path("password")
                        .asText()))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(LOGIN_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.user").value(MATRIX_USER_ID))
        .andExpect(jsonPath("$.device_id").value(DEVICE_ID))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"syt_device\",\"device_id\":\"" + DEVICE_ID + "\"}",
                MediaType.APPLICATION_JSON));

    var loginResponse = service.loginBrowserDevice(MATRIX_USER_ID, DEVICE_ID);

    mockServer.verify();
    assertThat(loginResponse).containsEntry("access_token", "syt_device");
    assertThat(sentPasswords).hasSize(1);
    var transientPassword = sentPasswords.get(0);
    assertThat(transientPassword).isNotBlank();
    assertThat(bodyWriteLogs()).isNotEmpty();
    assertThat(allLogMessages())
        .noneMatch(message -> message.contains(ADMIN_PASSWORD))
        .noneMatch(message -> message.contains(transientPassword));
  }

  private List<String> allLogMessages() {
    return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** Guards against vacuous passes: the body-write DEBUG line must actually be captured. */
  private List<String> bodyWriteLogs() {
    return allLogMessages().stream().filter(message -> message.startsWith("Writing [")).toList();
  }
}
