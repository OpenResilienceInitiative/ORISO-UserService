package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class TenantDpaSignatureLegacyReadLoggingTest {
  private static final long TENANT_ID = 825719L;
  private static final String PRIVATE_DETAIL = "synthetic-external-diagnostic";

  private final TenantControllerApi api = mock(TenantControllerApi.class);
  private final Logger logger =
      (Logger) LoggerFactory.getLogger(TenantDpaSignatureReadClient.class);
  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
  private boolean additive;
  private TenantDpaSignatureReadClient client;

  @BeforeEach
  void setUp() {
    var identityConfig = mock(IdentityClientConfig.class);
    var factory = mock(TenantAdminServiceApiControllerFactory.class);
    var headers = mock(SecurityHeaderSupplier.class);
    when(identityConfig.isFiveActorMode()).thenReturn(true);
    when(identityConfig.taskAccessToken(TaskPurpose.NOTIFICATION_DISPATCH)).thenReturn("fixture");
    when(headers.getKeycloakAndCsrfHttpHeaders("fixture")).thenReturn(HttpHeaders.EMPTY);
    when(factory.createControllerApi()).thenReturn(api);
    when(api.getApiClient()).thenReturn(mock(ApiClient.class));
    client =
        new TenantDpaSignatureReadClient(
            headers, mock(IdentityAuthentication.class), identityConfig, factory);
    additive = logger.isAdditive();
    logger.setAdditive(false);
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(captured);
    logger.setAdditive(additive);
    captured.stop();
  }

  @Test
  void returnsVerifiedSignaturesWithoutLogging() {
    var signature = new DpaSignatureDTO();
    when(api.getDataProcessingAgreementSignatures(TENANT_ID)).thenReturn(List.of(signature));

    assertThat(client.readSignatures(TENANT_ID)).containsExactly(signature);
    assertThat(captured.list).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("failureCases")
  void leavesFailedReadEmptyAndLogsOnlyTheSafeDiagnostic(
      RuntimeException failure, String safeCategory) {
    when(api.getDataProcessingAgreementSignatures(TENANT_ID)).thenThrow(failure);

    assertThat(client.readSignatures(TENANT_ID)).isEmpty();
    assertThat(captured.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("DPA signatures", "TenantService", safeCategory)
                  .doesNotContain(PRIVATE_DETAIL, Long.toString(TENANT_ID));
              assertThat(event.getThrowableProxy()).isNull();
            });
  }

  private static Stream<Arguments> failureCases() {
    return Stream.of(
        Arguments.of(
            new HttpClientErrorException(HttpStatus.FORBIDDEN, PRIVATE_DETAIL), "HTTP 403"),
        Arguments.of(
            new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE, PRIVATE_DETAIL),
            "HTTP 503"),
        Arguments.of(new ResourceAccessException(PRIVATE_DETAIL), "transport failure"),
        Arguments.of(new IllegalStateException(PRIVATE_DETAIL), "IllegalStateException"));
  }
}
