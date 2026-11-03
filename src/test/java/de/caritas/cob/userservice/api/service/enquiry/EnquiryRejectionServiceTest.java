package de.caritas.cob.userservice.api.service.enquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixRoomClient;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.EnquiryRejection;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class EnquiryRejectionServiceTest {
  private final EnquiryRejectionTransactions transactions =
      mock(EnquiryRejectionTransactions.class);
  private final AgencyMatrixCredentialClient credentials = mock(AgencyMatrixCredentialClient.class);
  private final EnquiryRejectionService service =
      new EnquiryRejectionService(
          transactions,
          credentials,
          mock(MatrixSynapseService.class),
          mock(MatrixRoomClient.class));
  private final Logger logger = (Logger) LoggerFactory.getLogger(EnquiryRejectionService.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  @Test
  void unavailableClosureIsLoggedWithStageAndClassButNoProviderText() {
    var decision = new EnquiryRejection();
    decision.setSessionId(5L);
    decision.setAgencyId(70L);
    decision.setGeneration("generation");
    decision.setState(EnquiryRejection.State.PENDING);
    when(transactions.begin(5L))
        .thenReturn(new EnquiryRejectionTransactions.Attempt(decision, "claim"));
    when(credentials.fetchMatrixCredentials(70L))
        .thenThrow(new IllegalStateException("secret provider body"));

    assertThat(service.rejectEnquiry(5L)).isFalse();

    verify(transactions).finish(5L, "generation", "claim", false, "CREDENTIALS");
    assertThat(logs.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "Enquiry rejection closure failed for session 5: stage=CREDENTIALS,"
                + " failure=IllegalStateException");
  }
}
