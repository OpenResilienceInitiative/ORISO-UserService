package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * P2 feed-update signal (ADR-020), hook level: the same two hook points commit {@code 8b75eddd}
 * used for the retired LiveService — {@code createEvent} and the genuine first persist inside
 * {@code createEventOnce}.
 */
@ExtendWith(MockitoExtension.class)
class EventNotificationServiceFeedSignalTest {

  private static final String RECIPIENT = "recipient-id";
  private static final String DEDUPLICATION_KEY = "group_chat.reminder:42";

  @Mock private EventNotificationRepository eventNotificationRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private IdentityTombstoneService identityTombstoneService;
  @Mock private EventNotificationDeduplicationWriter deduplicationWriter;
  @Mock private MatrixFeedUpdateSignalService feedUpdateSignalService;

  @InjectMocks private EventNotificationService eventNotificationService;

  private void createEvent() {
    eventNotificationService.createEvent(
        RECIPIENT,
        "request.new",
        EventNotificationService.CATEGORY_SYSTEM,
        "T",
        "X",
        null,
        null,
        null,
        null);
  }

  private void createEventOnce() {
    eventNotificationService.createEventOnce(
        DEDUPLICATION_KEY,
        RECIPIENT,
        "group_chat.reminder",
        EventNotificationService.CATEGORY_SYSTEM,
        "T",
        "X",
        null,
        null,
        null,
        null);
  }

  @Test
  void createEventShouldSignalTheRecipient() {
    createEvent();

    verify(eventNotificationRepository).save(any(EventNotification.class));
    verify(feedUpdateSignalService).signalFeedUpdated(RECIPIENT);
  }

  @Test
  void createEventShouldNotSignalForABlankRecipient() {
    eventNotificationService.createEvent(
        "  ",
        "request.new",
        EventNotificationService.CATEGORY_SYSTEM,
        "T",
        "X",
        null,
        null,
        null,
        null);

    verify(feedUpdateSignalService, never()).signalFeedUpdated(anyString());
  }

  @Test
  void createEventShouldStillPersistWhenSignallingFails() {
    doThrow(new IllegalStateException("matrix down"))
        .when(feedUpdateSignalService)
        .signalFeedUpdated(RECIPIENT);

    assertThatCode(this::createEvent).doesNotThrowAnyException();
    verify(eventNotificationRepository).save(any(EventNotification.class));
  }

  @Test
  void createEventOnceShouldSignalOnAGenuineFirstPersist() {
    when(eventNotificationRepository.existsByRecipientUserIdAndDeduplicationKey(
            RECIPIENT, DEDUPLICATION_KEY))
        .thenReturn(false);

    createEventOnce();

    verify(deduplicationWriter).persistInNewTransaction(any(EventNotification.class));
    verify(feedUpdateSignalService).signalFeedUpdated(RECIPIENT);
  }

  @Test
  void createEventOnceShouldNotSignalForAKnownDuplicate() {
    when(eventNotificationRepository.existsByRecipientUserIdAndDeduplicationKey(
            RECIPIENT, DEDUPLICATION_KEY))
        .thenReturn(true);

    createEventOnce();

    verify(feedUpdateSignalService, never()).signalFeedUpdated(anyString());
  }

  @Test
  void createEventOnceShouldNotSignalWhenAnotherReplicaWonTheUniqueKeyRace() {
    when(eventNotificationRepository.existsByRecipientUserIdAndDeduplicationKey(
            RECIPIENT, DEDUPLICATION_KEY))
        .thenReturn(false);
    doThrow(new DataIntegrityViolationException("duplicate"))
        .when(deduplicationWriter)
        .persistInNewTransaction(any(EventNotification.class));

    createEventOnce();

    // The winning replica already signalled the recipient.
    verify(feedUpdateSignalService, never()).signalFeedUpdated(anyString());
  }
}
