package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.config.observability.FeedSignalMetrics;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationDeduplicationWriter;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class FeedSignalPrivacyTest {
  private static final String RECIPIENT = "sensitive-recipient-id";
  private static final String MATRIX_ID = "@private.person:matrix.example.com";
  private static final String DETAIL = RECIPIENT + " " + MATRIX_ID;

  @Test
  void schedulingFailureDoesNotDiscloseRecipientOrDependencyMessage() {
    var service =
        service(
            (task, delay) -> {
              throw new IllegalStateException(DETAIL);
            },
            Runnable::run);
    assertPrivateFailure(
        MatrixFeedUpdateSignalService.class, () -> service.signalFeedUpdated(RECIPIENT));
  }

  @Test
  void saturatedExecutorDoesNotDiscloseDependencyMessage() {
    var service =
        service(
            (task, delay) -> task.run(),
            task -> {
              throw new RejectedExecutionException(DETAIL);
            });
    assertPrivateFailure(
        MatrixFeedUpdateSignalService.class, () -> service.signalFeedUpdated(RECIPIENT));
  }

  @Test
  void recipientLookupFailureDoesNotDiscloseDependencyMessage() {
    var consultants = mock(ConsultantRepository.class);
    when(consultants.findByIdAndDeleteDateIsNull(RECIPIENT))
        .thenThrow(new IllegalStateException(DETAIL));
    var service =
        new MatrixFeedUpdateSignalService(
            mock(MatrixSynapseService.class),
            mock(UserRepository.class),
            consultants,
            mock(FeedSignalMetrics.class),
            (task, delay) -> task.run(),
            Runnable::run,
            true,
            0);
    assertPrivateFailure(
        MatrixFeedUpdateSignalService.class, () -> service.signalFeedUpdated(RECIPIENT));
  }

  @Test
  void notificationHookFailureDoesNotDiscloseRecipientOrDependencyMessage() {
    var signal = mock(MatrixFeedUpdateSignalService.class);
    org.mockito.Mockito.doThrow(new IllegalStateException(DETAIL))
        .when(signal)
        .signalFeedUpdated(RECIPIENT);
    var service =
        new EventNotificationService(
            mock(EventNotificationRepository.class),
            mock(SessionRepository.class),
            mock(UserRepository.class),
            mock(ConsultantRepository.class),
            mock(IdentityTombstoneService.class),
            mock(EventNotificationDeduplicationWriter.class),
            signal,
            new ConsultantDisplayNameResolver());
    assertPrivateFailure(
        EventNotificationService.class,
        () ->
            service.createEvent(
                RECIPIENT,
                "request.new",
                EventNotificationService.CATEGORY_SYSTEM,
                "Title",
                "Text",
                null,
                null,
                null,
                null));
  }

  private MatrixFeedUpdateSignalService service(
      FeedSignalDelayScheduler scheduler, Executor executor) {
    return new MatrixFeedUpdateSignalService(
        mock(MatrixSynapseService.class),
        mock(UserRepository.class),
        mock(ConsultantRepository.class),
        mock(FeedSignalMetrics.class),
        scheduler,
        executor,
        true,
        0);
  }

  private void assertPrivateFailure(Class<?> owner, Runnable action) {
    var logger = (Logger) LoggerFactory.getLogger(owner);
    var previousLevel = logger.getLevel();
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.setLevel(Level.WARN);
    logger.addAppender(appender);
    try {
      action.run();
      assertThat(appender.list)
          .isNotEmpty()
          .allSatisfy(
              event ->
                  assertThat(event.getFormattedMessage()).doesNotContain(RECIPIENT, MATRIX_ID));
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
      appender.stop();
    }
  }
}
