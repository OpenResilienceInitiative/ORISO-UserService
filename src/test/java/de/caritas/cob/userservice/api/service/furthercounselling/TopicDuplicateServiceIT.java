package de.caritas.cob.userservice.api.service.furthercounselling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** FE#1876: the same-topic lookup against the real session table. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(TopicDuplicateService.class)
class TopicDuplicateServiceIT {

  private static final long TOPIC = 9_876L;

  @Autowired SessionRepository sessions;
  @Autowired UserRepository users;
  @Autowired TopicDuplicateService service;

  @MockitoBean FurtherCounsellingSettings settings;
  @MockitoBean MatrixSessionSystemMessageService systemMessages;
  @MockitoBean EventNotificationService eventNotificationService;
  @MockitoBean SupervisorAddedEmailNotificationService emailNotifications;

  private final List<Long> created = new ArrayList<>();
  private Session older;
  private Session newer;
  private Session archived;
  private Session otherTopic;

  @BeforeEach
  void fixture() {
    older = save(SessionStatus.IN_PROGRESS, TOPIC, LocalDateTime.of(2026, 10, 1, 9, 0));
    newer = save(SessionStatus.IN_PROGRESS, TOPIC, LocalDateTime.of(2026, 10, 20, 9, 0));
    archived = save(SessionStatus.IN_ARCHIVE, TOPIC, LocalDateTime.of(2026, 9, 1, 9, 0));
    otherTopic = save(SessionStatus.IN_PROGRESS, TOPIC + 1, LocalDateTime.of(2026, 8, 1, 9, 0));
    when(settings.topicDuplicateMode()).thenReturn(TopicDuplicateMode.DEFAULT);
    when(eventNotificationService.createTopicDuplicateStartedNotification(any(), anyLong()))
        .thenReturn(true);
    when(systemMessages.postSystemMessage(any(), anyString(), anyBoolean())).thenReturn(true);
  }

  @AfterEach
  void cleanup() {
    created.forEach(sessions::deleteById);
  }

  @Test
  void findsOnlyTheOlderAcceptedSessionOnTheSameTopic() {
    assertThat(service.olderActiveSameTopicSessions(newer))
        .extracting(Session::getId)
        .containsExactly(older.getId());
    assertThat(service.isNewerDuplicate(newer)).isTrue();
    assertThat(service.isNewerDuplicate(older)).isFalse();
    assertThat(service.isNewerDuplicate(otherTopic)).isFalse();
  }

  @Test
  void acceptanceNoticeReachesOnlyTheOtherAcceptedSameTopicRoom() {
    service.notifyDuplicateStarted(newer.getId());

    verify(systemMessages)
        .postSystemMessage(
            argThat(s -> s.getId().equals(older.getId())),
            eq(FurtherCounsellingNotices.duplicateStarted(TOPIC)),
            eq(false));
    verify(systemMessages, never())
        .postSystemMessage(
            argThat(
                s ->
                    s.getId().equals(archived.getId())
                        || s.getId().equals(otherTopic.getId())
                        || s.getId().equals(newer.getId())),
            anyString(),
            anyBoolean());
  }

  private Session save(SessionStatus status, long topicId, LocalDateTime createDate) {
    var user = users.findAll().iterator().next();
    var session = new Session(user, 1, "10965", 1L, status, false);
    session.setConversationType(ConversationType.AGENCY_COUNSELLING);
    session.setIsConsultantDirectlySet(false);
    session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    session.setMainTopicId(topicId);
    session.setCreateDate(createDate);
    var saved = sessions.save(session);
    created.add(saved.getId());
    return saved;
  }
}
