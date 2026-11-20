package de.caritas.cob.userservice.api.facade.sessionlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionTopicDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.helper.CustomLocalDateTime;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.furthercounselling.FurtherCounsellingSettings;
import de.caritas.cob.userservice.api.service.furthercounselling.TopicDuplicateService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * FE#1876: the list flag and the end-duplicate endpoint must answer "is this the newer duplicate?"
 * identically, or the asker sees an "end" action the server refuses (or misses one it allows).
 */
class TopicDuplicateMarkerAgreementTest {

  private static final LocalDateTime T = LocalDateTime.of(2026, 10, 20, 9, 0, 0);
  private static final User ASKER = asker();

  static Stream<Arguments> scenarios() {
    return Stream.of(
        Arguments.of(
            "older and newer on one topic",
            List.of(
                s(1, SessionStatus.IN_PROGRESS, T, 3L, null),
                s(2, SessionStatus.IN_PROGRESS, T.plusDays(1), 3L, null))),
        Arguments.of(
            // Same second, the higher id is older by milliseconds: the list only knows seconds.
            "same second, ids against milliseconds",
            List.of(
                s(1, SessionStatus.IN_PROGRESS, T.plusNanos(900_000_000), 3L, null),
                s(2, SessionStatus.IN_PROGRESS, T.plusNanos(100_000_000), 3L, null))),
        Arguments.of(
            "different topics",
            List.of(
                s(1, SessionStatus.IN_PROGRESS, T, 3L, null),
                s(2, SessionStatus.IN_PROGRESS, T.plusDays(1), 4L, null))),
        Arguments.of(
            "older one archived or still an enquiry",
            List.of(
                s(1, SessionStatus.IN_ARCHIVE, T, 3L, null),
                s(2, SessionStatus.NEW, T.plusHours(1), 3L, null),
                s(3, SessionStatus.IN_PROGRESS, T.plusDays(1), 3L, null))),
        Arguments.of(
            "group and members-only chats",
            List.of(
                s(1, SessionStatus.IN_PROGRESS, T, 3L, ConversationType.SELF_HELP),
                s(
                    2,
                    SessionStatus.IN_PROGRESS,
                    T.plusHours(1),
                    3L,
                    ConversationType.INTERNAL_GROUP),
                s(3, SessionStatus.IN_PROGRESS, T.plusDays(1), 3L, ConversationType.LIVE_CHAT),
                s(
                    4,
                    SessionStatus.IN_PROGRESS,
                    T.plusDays(2),
                    3L,
                    ConversationType.AGENCY_COUNSELLING))),
        Arguments.of(
            "no topic",
            List.of(
                s(1, SessionStatus.IN_PROGRESS, T, null, null),
                s(2, SessionStatus.IN_PROGRESS, T.plusDays(1), null, null))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void listFlagAndEndpointAgree(String name, List<Session> sessions) {
    var repository = mock(SessionRepository.class);
    for (var session : sessions) {
      if (session.getMainTopicId() != null) {
        when(repository.findByUserAndMainTopicId(ASKER, session.getMainTopicId()))
            .thenReturn(
                sessions.stream()
                    .filter(other -> session.getMainTopicId().equals(other.getMainTopicId()))
                    .toList());
      }
    }
    var service =
        new TopicDuplicateService(
            repository,
            mock(FurtherCounsellingSettings.class),
            mock(MatrixSessionSystemMessageService.class),
            mock(EventNotificationService.class),
            mock(SupervisorAddedEmailNotificationService.class));
    var list = new ArrayList<UserSessionResponseDTO>();
    for (var session : sessions) {
      list.add(new UserSessionResponseDTO().session(dto(session)));
    }

    TopicDuplicateMarker.mark(list);

    for (int i = 0; i < sessions.size(); i++) {
      var flag = list.get(i).getSession().getIsTopicDuplicate();
      assertThat(Boolean.TRUE.equals(flag))
          .as("session %s", sessions.get(i).getId())
          .isEqualTo(service.isNewerDuplicate(sessions.get(i)));
    }
  }

  private static Session s(
      long id, SessionStatus status, LocalDateTime created, Long topic, ConversationType type) {
    var session = new Session();
    session.setId(id);
    session.setUser(ASKER);
    session.setStatus(status);
    session.setCreateDate(created);
    session.setMainTopicId(topic);
    session.setConversationType(type);
    return session;
  }

  /** The fields SessionMapper copies that the marker reads. */
  private static SessionDTO dto(Session session) {
    var dto =
        new SessionDTO()
            .id(session.getId())
            .status(session.getStatus().getValue())
            .createDate(CustomLocalDateTime.toIsoTime(session.getCreateDate()))
            .topic(new SessionTopicDTO().id(session.getMainTopicId()));
    if (session.getConversationType() != null) {
      dto.conversationType(
          de.caritas.cob.userservice.api.adapters.web.dto.ConversationType.fromValue(
              session.getConversationType().name()));
    }
    return dto;
  }

  private static User asker() {
    var user = new User();
    user.setUserId("asker");
    return user;
  }
}
