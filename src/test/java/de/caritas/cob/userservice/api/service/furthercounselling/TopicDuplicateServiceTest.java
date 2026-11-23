package de.caritas.cob.userservice.api.service.furthercounselling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TopicDuplicateServiceTest {

  private static final long TOPIC = 3L;

  @Mock SessionRepository sessionRepository;
  @Mock FurtherCounsellingSettings settings;
  @Mock MatrixSessionSystemMessageService systemMessages;
  @Mock EventNotificationService eventNotificationService;
  @Mock SupervisorAddedEmailNotificationService emailNotifications;
  @InjectMocks TopicDuplicateService service;

  private final User asker = user();
  private Session old;
  private Session accepted;

  @BeforeEach
  void setUp() {
    old = session(1L, SessionStatus.IN_PROGRESS, LocalDateTime.of(2026, 10, 1, 9, 0), "old-c");
    accepted =
        session(2L, SessionStatus.IN_PROGRESS, LocalDateTime.of(2026, 10, 20, 9, 0), "new-c");
    lenient().when(sessionRepository.findById(2L)).thenReturn(Optional.of(accepted));
    lenient()
        .when(sessionRepository.findByUserAndMainTopicId(asker, TOPIC))
        .thenReturn(List.of(old, accepted));
    lenient().when(settings.topicDuplicateMode()).thenReturn(TopicDuplicateMode.DEFAULT);
    lenient()
        .when(eventNotificationService.createTopicDuplicateStartedNotification(any(), anyLong()))
        .thenReturn(true);
    lenient()
        .when(systemMessages.postSystemMessage(any(), anyString(), anyBoolean()))
        .thenReturn(true);
  }

  @Test
  void acceptanceNotifiesTheOldRoomWithoutNamingTheNewCentre() {
    accepted.setAgencyId(777L);

    service.notifyDuplicateStarted(2L);

    var body = ArgumentCaptor.forClass(String.class);
    verify(systemMessages).postSystemMessage(eq(old), body.capture(), eq(false));
    assertThat(body.getValue())
        .isEqualTo("[SYSTEM_NOTIFICATION]{\"type\":\"TOPIC_DUPLICATE_STARTED\",\"topicId\":3}")
        .doesNotContain("777", "79098", "newSessionId");
    verify(eventNotificationService).createTopicDuplicateStartedNotification(old, 2L);
    verify(emailNotifications).notifyTopicDuplicateStarted(eq(old.getConsultant()), eq(1L), any());
  }

  @Test
  void theAcceptedSessionItselfIsNeverNotified() {
    service.notifyDuplicateStarted(2L);

    verify(systemMessages, never()).postSystemMessage(eq(accepted), anyString(), anyBoolean());
  }

  @Test
  void secondRunForTheSamePairPostsNothing() {
    when(eventNotificationService.createTopicDuplicateStartedNotification(old, 2L))
        .thenReturn(false);

    service.notifyDuplicateStarted(2L);

    verify(systemMessages, never()).postSystemMessage(any(), anyString(), anyBoolean());
    verifyNoInteractions(emailNotifications);
  }

  @Test
  void aThrowingPostDoesNotStopTheNextOldSession() {
    var second =
        session(5L, SessionStatus.IN_PROGRESS, LocalDateTime.of(2026, 10, 5, 9, 0), "second-c");
    when(sessionRepository.findByUserAndMainTopicId(asker, TOPIC))
        .thenReturn(List.of(old, second, accepted));
    when(systemMessages.postSystemMessage(eq(old), anyString(), anyBoolean()))
        .thenThrow(new IllegalStateException("matrix down"));

    service.notifyDuplicateStarted(2L);

    verify(systemMessages).postSystemMessage(eq(second), anyString(), eq(false));
    verify(emailNotifications)
        .notifyTopicDuplicateStarted(eq(second.getConsultant()), eq(5L), any());
    verify(emailNotifications, never())
        .notifyTopicDuplicateStarted(eq(old.getConsultant()), eq(1L), any());
  }

  @Test
  void aThrowingPostLeavesNoMarkerSoARetryCanDeliver() {
    when(systemMessages.postSystemMessage(eq(old), anyString(), anyBoolean()))
        .thenThrow(new IllegalStateException("matrix down"));

    service.notifyDuplicateStarted(2L);

    verify(eventNotificationService).removeTopicDuplicateStartedNotification(old, 2L);
    verifyNoInteractions(emailNotifications);
  }

  @Test
  void anUndeliveredPostLeavesNoMarkerSoARetryCanDeliver() {
    when(systemMessages.postSystemMessage(eq(old), anyString(), anyBoolean())).thenReturn(false);

    service.notifyDuplicateStarted(2L);

    verify(eventNotificationService).removeTopicDuplicateStartedNotification(old, 2L);
    verifyNoInteractions(emailNotifications);
  }

  @Test
  void aDeliveredPostKeepsTheMarker() {
    service.notifyDuplicateStarted(2L);

    verify(eventNotificationService, never()).removeTopicDuplicateStartedNotification(any(), any());
  }

  @Test
  void nullTopicDoesNothing() {
    accepted.setMainTopicId(null);

    service.notifyDuplicateStarted(2L);

    verify(sessionRepository, never()).findByUserAndMainTopicId(any(), any());
    verifyNoInteractions(systemMessages, eventNotificationService, emailNotifications);
  }

  @ParameterizedTest
  @EnumSource(
      value = SessionStatus.class,
      names = {"IN_PROGRESS"},
      mode = EnumSource.Mode.EXCLUDE)
  void sessionsThatAreNotAcceptedAreNotNotified(SessionStatus status) {
    old.setStatus(status);

    service.notifyDuplicateStarted(2L);

    verifyNoInteractions(systemMessages, eventNotificationService, emailNotifications);
  }

  @Test
  void groupSessionsAreIgnored() {
    old.setConversationType(ConversationType.SELF_HELP);

    service.notifyDuplicateStarted(2L);

    verifyNoInteractions(systemMessages);
  }

  @ParameterizedTest
  @EnumSource(
      value = TopicDuplicateMode.class,
      names = {"INFORM_EXISTING_COUNSELLOR_ONCE"},
      mode = EnumSource.Mode.EXCLUDE)
  void reservedModesSendNothingYet(TopicDuplicateMode mode) {
    when(settings.topicDuplicateMode()).thenReturn(mode);

    service.notifyDuplicateStarted(2L);

    verifyNoInteractions(systemMessages, eventNotificationService, emailNotifications);
  }

  @Test
  void olderSessionsAreFoundByCreateDate() {
    assertThat(service.olderActiveSameTopicSessions(accepted)).containsExactly(old);
    assertThat(service.olderActiveSameTopicSessions(old)).isEmpty();
    assertThat(service.isNewerDuplicate(accepted)).isTrue();
    assertThat(service.isNewerDuplicate(old)).isFalse();
  }

  @Test
  void equalCreateDatesFallBackToTheId() {
    accepted.setCreateDate(old.getCreateDate());

    assertThat(service.olderActiveSameTopicSessions(accepted)).containsExactly(old);
    assertThat(service.olderActiveSameTopicSessions(old)).isEmpty();
  }

  @Test
  void endedNoticeGoesIntoEachOlderRoom() {
    service.notifyDuplicateEnded(List.of(old), TOPIC);

    verify(systemMessages)
        .postSystemMessage(
            old, "[SYSTEM_NOTIFICATION]{\"type\":\"TOPIC_DUPLICATE_ENDED\",\"topicId\":3}", false);
  }

  @Test
  void endedNoticeFailureIsSwallowed() {
    when(systemMessages.postSystemMessage(any(), anyString(), anyBoolean()))
        .thenThrow(new IllegalStateException("matrix down"));

    service.notifyDuplicateEnded(List.of(old), TOPIC);
  }

  private Session session(long id, SessionStatus status, LocalDateTime created, String consultant) {
    var c = new Consultant();
    c.setId(consultant);
    var s = new Session();
    s.setId(id);
    s.setUser(asker);
    s.setConsultant(c);
    s.setStatus(status);
    s.setCreateDate(created);
    s.setMainTopicId(TOPIC);
    s.setPostcode("79098");
    s.setConversationType(ConversationType.AGENCY_COUNSELLING);
    return s;
  }

  private static User user() {
    var u = new User();
    u.setUserId("asker");
    return u;
  }
}
