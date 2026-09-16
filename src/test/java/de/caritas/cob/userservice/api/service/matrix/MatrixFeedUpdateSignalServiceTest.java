package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** P2 feed-update signal (ADR-020), service level. */
@ExtendWith(MockitoExtension.class)
class MatrixFeedUpdateSignalServiceTest {

  private static final String RECIPIENT_ID = "recipient-id";
  private static final String MATRIX_USER_ID = "@alice:matrix.example.com";

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;

  @AfterEach
  void clearTransactionSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private MatrixFeedUpdateSignalService service(boolean enabled) {
    return new MatrixFeedUpdateSignalService(
        matrixSynapseService, userRepository, consultantRepository, enabled);
  }

  private Consultant consultantWith(String matrixUserId) {
    var consultant = new Consultant();
    consultant.setMatrixUserId(matrixUserId);
    return consultant;
  }

  private User userWith(String matrixUserId) {
    var user = new User();
    user.setMatrixUserId(matrixUserId);
    return user;
  }

  @Test
  void shouldSendContentFreeSignalToAConsultantRecipient() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenReturn(Optional.of(consultantWith(MATRIX_USER_ID)));

    service(true).signalFeedUpdated(RECIPIENT_ID);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> contentCaptor = ArgumentCaptor.forClass(Map.class);
    verify(matrixSynapseService)
        .sendToDeviceMessage(
            eq(MatrixFeedUpdateSignalService.FEED_UPDATE_EVENT_TYPE),
            eq(MATRIX_USER_ID),
            contentCaptor.capture());
    // No notification content may leave the persisted feed — only "something changed".
    assertThat(contentCaptor.getValue()).isEmpty();
  }

  @Test
  void shouldUseTheAgreedEventType() {
    assertThat(MatrixFeedUpdateSignalService.FEED_UPDATE_EVENT_TYPE)
        .isEqualTo("org.oriso.feed.updated");
  }

  @Test
  void shouldFallBackToTheAdviceSeekerRepositoryWhenTheRecipientIsNoConsultant() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenReturn(Optional.of(userWith(MATRIX_USER_ID)));

    service(true).signalFeedUpdated(RECIPIENT_ID);

    verify(matrixSynapseService)
        .sendToDeviceMessage(
            MatrixFeedUpdateSignalService.FEED_UPDATE_EVENT_TYPE, MATRIX_USER_ID, Map.of());
  }

  @Test
  void shouldDoNothingWhenDisabledByConfiguration() {
    service(false).signalFeedUpdated(RECIPIENT_ID);

    verifyNoInteractions(matrixSynapseService, userRepository, consultantRepository);
  }

  @Test
  void shouldDoNothingForABlankRecipient() {
    service(true).signalFeedUpdated("  ");
    service(true).signalFeedUpdated(null);

    verifyNoInteractions(matrixSynapseService, userRepository, consultantRepository);
  }

  @Test
  void shouldDoNothingWhenTheRecipientHasNoChatIdentityYet() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenReturn(Optional.of(consultantWith(null)));
    when(userRepository.findByUserIdAndDeleteDateIsNull(RECIPIENT_ID)).thenReturn(Optional.empty());

    service(true).signalFeedUpdated(RECIPIENT_ID);

    verify(matrixSynapseService, never()).sendToDeviceMessage(anyString(), anyString(), any());
  }

  @Test
  void shouldNeverPropagateAMatrixOutageToTheCaller() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenReturn(Optional.of(consultantWith(MATRIX_USER_ID)));
    when(matrixSynapseService.sendToDeviceMessage(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("synapse down"));

    assertThatCode(() -> service(true).signalFeedUpdated(RECIPIENT_ID)).doesNotThrowAnyException();
  }

  @Test
  void shouldNeverPropagateARepositoryFailureToTheCaller() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatCode(() -> service(true).signalFeedUpdated(RECIPIENT_ID)).doesNotThrowAnyException();
    verify(matrixSynapseService, never()).sendToDeviceMessage(anyString(), anyString(), any());
  }

  @Test
  void shouldDeferTheSignalUntilAfterCommitWhenATransactionIsActive() {
    TransactionSynchronizationManager.initSynchronization();

    service(true).signalFeedUpdated(RECIPIENT_ID);

    // Nothing sent yet: the row is not visible to other connections before commit.
    verifyNoInteractions(matrixSynapseService, userRepository, consultantRepository);
    assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

    when(consultantRepository.findByIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenReturn(Optional.of(consultantWith(MATRIX_USER_ID)));
    TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

    verify(matrixSynapseService)
        .sendToDeviceMessage(
            MatrixFeedUpdateSignalService.FEED_UPDATE_EVENT_TYPE, MATRIX_USER_ID, Map.of());
  }

  @Test
  void shouldSendNothingWhenTheTransactionRollsBack() {
    TransactionSynchronizationManager.initSynchronization();

    service(true).signalFeedUpdated(RECIPIENT_ID);
    // afterCompletion without afterCommit == rollback.
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(
            s ->
                s.afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization
                        .STATUS_ROLLED_BACK));

    verifyNoInteractions(matrixSynapseService);
  }
}
