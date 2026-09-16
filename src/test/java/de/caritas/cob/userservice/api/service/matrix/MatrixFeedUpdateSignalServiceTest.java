package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.config.observability.FeedSignalMetrics;
import de.caritas.cob.userservice.api.config.observability.FeedSignalMetrics.Outcome;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** P2 feed-update signal (ADR-020), service level: coalescing, async hand-off, metrics. */
@ExtendWith(MockitoExtension.class)
class MatrixFeedUpdateSignalServiceTest {

  private static final String RECIPIENT_ID = "recipient-id";
  private static final String OTHER_RECIPIENT_ID = "other-recipient-id";
  private static final String MATRIX_USER_ID = "@alice:matrix.example.com";
  private static final long COALESCE_MILLIS = 500L;

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private FeedSignalMetrics metrics;

  /** Captures scheduled timers so the test fires the window end itself — no sleeping. */
  private static final class RecordingScheduler implements FeedSignalDelayScheduler {
    private final List<Runnable> pending = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();

    @Override
    public void schedule(Runnable task, long delayMillis) {
      pending.add(task);
      delays.add(delayMillis);
    }

    void fireAll() {
      var due = List.copyOf(pending);
      pending.clear();
      due.forEach(Runnable::run);
    }
  }

  private RecordingScheduler scheduler;
  private AtomicLong now;
  private Executor directExecutor;

  @BeforeEach
  void setUp() {
    scheduler = new RecordingScheduler();
    now = new AtomicLong(1_000L);
    directExecutor = Runnable::run;
  }

  @AfterEach
  void clearTransactionSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private MatrixFeedUpdateSignalService service(boolean enabled) {
    return service(enabled, directExecutor);
  }

  private MatrixFeedUpdateSignalService service(boolean enabled, Executor executor) {
    return new MatrixFeedUpdateSignalService(
        matrixSynapseService,
        userRepository,
        consultantRepository,
        metrics,
        scheduler,
        executor,
        enabled,
        COALESCE_MILLIS,
        now::get);
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

  private void givenConsultantRecipient() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenReturn(Optional.of(consultantWith(MATRIX_USER_ID)));
  }

  private void givenSendSucceeds() {
    when(matrixSynapseService.sendToDeviceMessage(anyString(), anyString(), any()))
        .thenReturn(true);
  }

  // ---------------------------------------------------------------- contract

  @Test
  void shouldUseTheAgreedEventType() {
    assertThat(MatrixFeedUpdateSignalService.FEED_UPDATE_EVENT_TYPE)
        .isEqualTo("org.oriso.feed.updated");
  }

  @Test
  void shouldSendContentFreeSignalToAConsultantRecipient() {
    givenConsultantRecipient();
    givenSendSucceeds();

    service(true).signalFeedUpdated(RECIPIENT_ID);
    scheduler.fireAll();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> contentCaptor = ArgumentCaptor.forClass(Map.class);
    verify(matrixSynapseService)
        .sendToDeviceMessage(
            org.mockito.ArgumentMatchers.eq(MatrixFeedUpdateSignalService.FEED_UPDATE_EVENT_TYPE),
            org.mockito.ArgumentMatchers.eq(MATRIX_USER_ID),
            contentCaptor.capture());
    assertThat(contentCaptor.getValue()).isEmpty();
    verify(metrics).record(Outcome.SENT);
  }

  @Test
  void shouldFallBackToTheAdviceSeekerRepositoryWhenTheRecipientIsNoConsultant() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenReturn(Optional.of(userWith(MATRIX_USER_ID)));
    givenSendSucceeds();

    service(true).signalFeedUpdated(RECIPIENT_ID);
    scheduler.fireAll();

    verify(matrixSynapseService)
        .sendToDeviceMessage(
            MatrixFeedUpdateSignalService.FEED_UPDATE_EVENT_TYPE, MATRIX_USER_ID, Map.of());
  }

  // ------------------------------------------------------------- switchability

  @Test
  void shouldDoNothingButCountWhenDisabledByConfiguration() {
    service(false).signalFeedUpdated(RECIPIENT_ID);
    scheduler.fireAll();

    verifyNoInteractions(matrixSynapseService, userRepository, consultantRepository);
    verify(metrics).record(Outcome.DISABLED);
  }

  @Test
  void shouldDoNothingForABlankRecipient() {
    service(true).signalFeedUpdated("  ");
    service(true).signalFeedUpdated(null);
    scheduler.fireAll();

    verifyNoInteractions(matrixSynapseService, userRepository, consultantRepository, metrics);
  }

  // ---------------------------------------------------------------- coalescing

  @Test
  void aBurstOfRowsForOneRecipientCostsExactlyOneToDeviceSend() {
    givenConsultantRecipient();
    givenSendSucceeds();
    var service = service(true);

    for (int i = 0; i < 20; i++) {
      now.set(1_000L + (i * 10L));
      service.signalFeedUpdated(RECIPIENT_ID);
    }
    scheduler.fireAll();

    verify(matrixSynapseService, times(1)).sendToDeviceMessage(anyString(), anyString(), any());
    verify(metrics, times(1)).record(Outcome.SENT);
    verify(metrics, times(19)).record(Outcome.COALESCED);
  }

  @Test
  void theSingleSendHappensAtTheEndOfTheWindowNotImmediately() {
    givenConsultantRecipient();
    givenSendSucceeds();

    service(true).signalFeedUpdated(RECIPIENT_ID);

    // Nothing sent before the timer fires: the window must cover the whole burst.
    verify(matrixSynapseService, never()).sendToDeviceMessage(anyString(), anyString(), any());
    assertThat(scheduler.delays).containsExactly(COALESCE_MILLIS);

    scheduler.fireAll();
    verify(matrixSynapseService).sendToDeviceMessage(anyString(), anyString(), any());
  }

  @Test
  void differentRecipientsAreNotCoalescedIntoOneSignal() {
    givenConsultantRecipient();
    when(consultantRepository.findByIdAndDeleteDateIsNull(OTHER_RECIPIENT_ID))
        .thenReturn(Optional.of(consultantWith("@bob:matrix.example.com")));
    givenSendSucceeds();
    var service = service(true);

    service.signalFeedUpdated(RECIPIENT_ID);
    service.signalFeedUpdated(OTHER_RECIPIENT_ID);
    scheduler.fireAll();

    verify(matrixSynapseService, times(2)).sendToDeviceMessage(anyString(), anyString(), any());
  }

  @Test
  void aRowAfterTheWindowClosedSignalsAgain() {
    givenConsultantRecipient();
    givenSendSucceeds();
    var service = service(true);

    service.signalFeedUpdated(RECIPIENT_ID);
    scheduler.fireAll();
    now.set(1_000L + COALESCE_MILLIS + 1);
    service.signalFeedUpdated(RECIPIENT_ID);
    scheduler.fireAll();

    verify(matrixSynapseService, times(2)).sendToDeviceMessage(anyString(), anyString(), any());
  }

  // ------------------------------------------------------------ async hand-off

  @Test
  void shouldNeverRunTheMatrixCallOnTheCallingThread() throws Exception {
    givenConsultantRecipient();
    givenSendSucceeds();
    var callingThread = Thread.currentThread();
    var ranOn = new java.util.concurrent.atomic.AtomicReference<Thread>();
    var finished = new java.util.concurrent.CountDownLatch(1);
    Executor recordingExecutor =
        task ->
            new Thread(
                    () -> {
                      ranOn.set(Thread.currentThread());
                      try {
                        task.run();
                      } finally {
                        finished.countDown();
                      }
                    })
                .start();

    service(true, recordingExecutor).signalFeedUpdated(RECIPIENT_ID);
    scheduler.fireAll();

    assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(ranOn.get()).isNotNull().isNotSameAs(callingThread);
    verify(matrixSynapseService).sendToDeviceMessage(anyString(), anyString(), any());
  }

  @Test
  void shouldWarnAndCountWhenTheBoundedExecutorIsSaturated() {
    Executor saturated =
        task -> {
          throw new RejectedExecutionException("queue full");
        };

    assertThatCode(() -> service(true, saturated).signalFeedUpdated(RECIPIENT_ID))
        .doesNotThrowAnyException();
    scheduler.fireAll();

    verify(metrics).record(Outcome.FAILED);
    verifyNoInteractions(matrixSynapseService);
  }

  // -------------------------------------------------------------- best effort

  @Test
  void shouldCountAndSwallowARecipientWithoutAChatIdentity() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenReturn(Optional.of(consultantWith(null)));
    when(userRepository.findByUserIdAndDeleteDateIsNull(RECIPIENT_ID)).thenReturn(Optional.empty());

    service(true).signalFeedUpdated(RECIPIENT_ID);
    scheduler.fireAll();

    verify(matrixSynapseService, never()).sendToDeviceMessage(anyString(), anyString(), any());
    verify(metrics).record(Outcome.SKIPPED_NO_IDENTITY);
  }

  @Test
  void shouldCountARejectedSendAsFailedRatherThanSent() {
    givenConsultantRecipient();
    when(matrixSynapseService.sendToDeviceMessage(anyString(), anyString(), any()))
        .thenReturn(false);

    service(true).signalFeedUpdated(RECIPIENT_ID);
    scheduler.fireAll();

    verify(metrics).record(Outcome.FAILED);
    verify(metrics, never()).record(Outcome.SENT);
  }

  @Test
  void shouldNeverPropagateAMatrixOutageToTheCaller() {
    givenConsultantRecipient();
    when(matrixSynapseService.sendToDeviceMessage(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("synapse down"));

    assertThatCode(
            () -> {
              service(true).signalFeedUpdated(RECIPIENT_ID);
              scheduler.fireAll();
            })
        .doesNotThrowAnyException();
    verify(metrics).record(Outcome.FAILED);
  }

  @Test
  void shouldNeverPropagateARepositoryFailureToTheCaller() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(RECIPIENT_ID))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatCode(
            () -> {
              service(true).signalFeedUpdated(RECIPIENT_ID);
              scheduler.fireAll();
            })
        .doesNotThrowAnyException();
    verify(matrixSynapseService, never()).sendToDeviceMessage(anyString(), anyString(), any());
    verify(metrics).record(Outcome.FAILED);
  }

  // ------------------------------------------------------------ after commit

  @Test
  void shouldDeferEverythingUntilAfterCommitWhenATransactionIsActive() {
    TransactionSynchronizationManager.initSynchronization();

    service(true).signalFeedUpdated(RECIPIENT_ID);

    // Not even the coalescing window opens before commit.
    verifyNoInteractions(matrixSynapseService, userRepository, consultantRepository, metrics);
    assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

    givenConsultantRecipient();
    givenSendSucceeds();
    TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
    scheduler.fireAll();

    verify(matrixSynapseService)
        .sendToDeviceMessage(
            MatrixFeedUpdateSignalService.FEED_UPDATE_EVENT_TYPE, MATRIX_USER_ID, Map.of());
  }

  @Test
  void shouldSendNothingWhenTheTransactionRollsBack() {
    TransactionSynchronizationManager.initSynchronization();

    service(true).signalFeedUpdated(RECIPIENT_ID);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(
            s ->
                s.afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization
                        .STATUS_ROLLED_BACK));
    scheduler.fireAll();

    verifyNoInteractions(matrixSynapseService, metrics);
  }
}
