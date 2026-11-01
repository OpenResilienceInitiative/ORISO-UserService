package de.caritas.cob.userservice.api.service.archive;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.SESSION_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.ArchiveOrDeleteSessionStatisticsEvent;
import de.caritas.cob.userservice.api.service.statistics.event.StatisticsEvent;
import de.caritas.cob.userservice.statisticsservice.generated.web.model.EventType;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SessionArchiveServiceTest {

  @InjectMocks SessionArchiveService sessionArchiveService;

  @Mock
  private de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess membersOnlyChatAccess;

  @Mock SessionRepository sessionRepository;

  @Mock
  @SuppressWarnings("unused")
  AuthenticatedUser authenticatedUser;

  @Mock SessionArchiveValidator sessionArchiveValidator;

  @Mock
  @SuppressWarnings("unused")
  ConsultantAgencyService consultantAgencyService;

  @Mock
  @SuppressWarnings("unused")
  AccountManager accountManager;

  @Mock StatisticsService statisticsService;

  @Mock
  de.caritas.cob.userservice.api.service.furthercounselling.TopicDuplicateService
      topicDuplicateService;

  private LogbackCaptor logCaptor;

  @BeforeEach
  public void setUp() {
    logCaptor = LogbackCaptor.forClass(SessionArchiveService.class);
  }

  @AfterEach
  public void tearDown() {
    logCaptor.detach();
  }

  @Test
  public void archiveSession_Should_ThrowNotFoundException_WhenSessionIsNotFound() {
    assertThrows(
        NotFoundException.class,
        () -> {
          when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

          sessionArchiveService.archiveSession(SESSION_ID);
        });
  }

  @Test
  public void
      archiveSession_Should_ThrowConflictException_WhenSessionShouldBeArchivedAndIsNotInProgress() {
    assertThrows(
        ConflictException.class,
        () -> {
          Session session = Mockito.mock(Session.class);
          when(session.isAdvised(any())).thenReturn(true);
          when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
          doThrow(new ConflictException("Conflict"))
              .when(sessionArchiveValidator)
              .isValidForArchiving(session);

          sessionArchiveService.archiveSession(SESSION_ID);
          verify(session, times(0)).setStatus(SessionStatus.IN_ARCHIVE);
        });
  }

  @Test
  public void
      archiveSession_Should_ThrowForbiddenException_WhenConsultantHasaNoAuthorizationForTheSession() {
    assertThrows(
        ForbiddenException.class,
        () -> {
          Session session = Mockito.mock(Session.class);
          when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

          sessionArchiveService.archiveSession(SESSION_ID);

          verify(session, times(0)).setStatus(SessionStatus.IN_ARCHIVE);
        });
  }

  @Test
  public void archiveAndDearchive_Should_AskToStopTheChat_ForTheSessionOfAMembersOnlyChat() {
    Session session = Mockito.mock(Session.class);
    when(session.isAdvised(any())).thenReturn(true);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(membersOnlyChatAccess.concerns(session)).thenReturn(true);

    for (Runnable change :
        java.util.List.<Runnable>of(
            () -> sessionArchiveService.archiveSession(SESSION_ID),
            () -> sessionArchiveService.dearchiveSession(SESSION_ID))) {
      var refused =
          assertThrows(
              de.caritas.cob.userservice.api.exception.httpresponses
                  .CustomValidationHttpStatusException.class,
              change::run);
      org.junit.jupiter.api.Assertions.assertEquals(
          org.springframework.http.HttpStatus.CONFLICT, refused.getHttpStatus());
      org.junit.jupiter.api.Assertions.assertEquals(
          "MEMBERS_ONLY_CHAT_STOP_REQUIRED", refused.getCustomHttpHeaders().getFirst("X-Reason"));
    }
    verify(session, times(0)).setStatus(any());
    verify(sessionRepository, times(0)).save(any());
  }

  @Test
  public void archiveSession_Should_RefuseANonParticipantOfAMembersOnlyChatWith403() {
    Session session = Mockito.mock(Session.class);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(membersOnlyChatAccess.excludes(
            any(Session.class), org.mockito.ArgumentMatchers.<String>any()))
        .thenReturn(true);

    assertThrows(ForbiddenException.class, () -> sessionArchiveService.archiveSession(SESSION_ID));
  }

  @Test
  public void archiveSession_Should_ChangeStatusOfSession_WhenConsultantHasPermission() {

    Session session = Mockito.mock(Session.class);
    when(session.isAdvised(any())).thenReturn(true);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

    sessionArchiveService.archiveSession(SESSION_ID);

    verify(session, times(1)).setStatus(SessionStatus.IN_ARCHIVE);
  }

  @Test
  public void
      archiveSession_Should_ThrowForbiddenException_WhenUserHasNoAuthorizationForTheSession() {
    assertThrows(
        ForbiddenException.class,
        () -> {
          Session session = Mockito.mock(Session.class);
          when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

          sessionArchiveService.archiveSession(SESSION_ID);

          verify(session, times(0)).setStatus(SessionStatus.IN_ARCHIVE);
        });
  }

  @Test
  public void
      archiveSession_Should_ChangeStatusOfSessionAndFireArchiveStatisticsEvent_WhenUserHasPermission() {

    Session session = Mockito.mock(Session.class);
    when(session.isAdvised(any())).thenReturn(true);
    when(session.getUser()).thenReturn(new User());
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

    sessionArchiveService.archiveSession(SESSION_ID);

    verify(session, times(1)).setStatus(SessionStatus.IN_ARCHIVE);
    ArgumentCaptor<ArchiveOrDeleteSessionStatisticsEvent> statisticsEventArgumentCaptor =
        ArgumentCaptor.forClass(ArchiveOrDeleteSessionStatisticsEvent.class);
    verify(statisticsService).fireEvent(statisticsEventArgumentCaptor.capture());
    ArchiveOrDeleteSessionStatisticsEvent event = statisticsEventArgumentCaptor.getValue();
    assertThat(event.getEventType()).isEqualTo(EventType.ARCHIVE_SESSION);
    assertThat(event.getPayload().get()).isNotEmpty();
  }

  @Test
  public void archiveSession_Should_LogButNotFail_WhenErrorDuringStatisticsArchiveOccurs() {

    Session session = Mockito.mock(Session.class);
    when(session.isAdvised(any())).thenReturn(true);
    when(session.getUser()).thenReturn(new User());
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    IllegalStateException illegalStateException = new IllegalStateException();
    doThrow(illegalStateException)
        .when(statisticsService)
        .fireEvent(Mockito.any(StatisticsEvent.class));
    sessionArchiveService.archiveSession(SESSION_ID);

    verify(session, times(1)).setStatus(SessionStatus.IN_ARCHIVE);
    ArgumentCaptor<ArchiveOrDeleteSessionStatisticsEvent> statisticsEventArgumentCaptor =
        ArgumentCaptor.forClass(ArchiveOrDeleteSessionStatisticsEvent.class);
    verify(statisticsService).fireEvent(statisticsEventArgumentCaptor.capture());
    ArchiveOrDeleteSessionStatisticsEvent event = statisticsEventArgumentCaptor.getValue();
    assertThat(event.getEventType()).isEqualTo(EventType.ARCHIVE_SESSION);
    assertThat(event.getPayload().get()).isNotEmpty();
    assertThat(logCaptor.contains(Level.ERROR, "Could not create session archive statistics event"))
        .isTrue();
  }

  @Test
  public void dearchiveSession_Should_ThrowNotFoundException_WhenSessionIsNotFound() {
    assertThrows(
        NotFoundException.class,
        () -> {
          when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());
          sessionArchiveService.dearchiveSession(SESSION_ID);
        });
  }

  @Test
  public void
      dearchiveSession_Should_ThrowConflictException_WhenSessionShouldBeReactivatedAndIsAlreadyInProgress() {
    assertThrows(
        ConflictException.class,
        () -> {
          Session session = Mockito.mock(Session.class);
          when(session.isAdvised(any())).thenReturn(true);
          when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
          doThrow(new ConflictException("Conflict"))
              .when(sessionArchiveValidator)
              .isValidForDearchiving(session);

          sessionArchiveService.dearchiveSession(SESSION_ID);

          verify(session, times(0)).setStatus(SessionStatus.IN_PROGRESS);
        });
  }

  @Test
  public void
      dearchiveSession_Should_ThrowForbiddenException_WhenConsultantHasNoAuthorizationForTheSession() {
    assertThrows(
        ForbiddenException.class,
        () -> {
          Session session = Mockito.mock(Session.class);
          when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

          sessionArchiveService.dearchiveSession(SESSION_ID);

          verify(session, times(0)).setStatus(SessionStatus.IN_PROGRESS);
        });
  }

  @Test
  public void
      dearchiveSession_Should_ThrowForbiddenException_WhenSessionIsNotTeamSessionAndConsultantNotAssigned() {
    assertThrows(
        ForbiddenException.class,
        () -> {
          Session session = Mockito.mock(Session.class);
          when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

          sessionArchiveService.dearchiveSession(SESSION_ID);

          verify(session, times(1)).setStatus(SessionStatus.IN_ARCHIVE);
        });
  }

  @Test
  public void dearchiveSession_Should_ThrowForbiddenException_WhenNoConsultantOrUserRole() {
    assertThrows(
        ForbiddenException.class,
        () -> {
          Session session = Mockito.mock(Session.class);
          when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

          sessionArchiveService.dearchiveSession(SESSION_ID);

          verify(session, times(0)).setStatus(SessionStatus.IN_PROGRESS);
        });
  }

  @Test
  public void dearchiveSession_Should_ChangeStatusOfSession_WhenConsultantHasPermission() {
    Session session = Mockito.mock(Session.class);
    when(session.isAdvised(any())).thenReturn(true);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

    sessionArchiveService.dearchiveSession(SESSION_ID);

    verify(session, times(1)).setStatus(SessionStatus.IN_PROGRESS);
  }

  @Test
  public void
      dearchiveSession_Should_ThrowForbiddenException_WhenUserHasNoAuthorizationForTheSession() {
    assertThrows(
        ForbiddenException.class,
        () -> {
          Session session = Mockito.mock(Session.class);
          when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

          sessionArchiveService.dearchiveSession(SESSION_ID);

          verify(session, times(1)).setStatus(SessionStatus.IN_ARCHIVE);
        });
  }

  @Test
  public void dearchiveSession_Should_ChangeStatusOfSession_WhenUserHasPermission() {

    Session session = Mockito.mock(Session.class);
    when(session.isAdvised(any())).thenReturn(true);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

    sessionArchiveService.dearchiveSession(SESSION_ID);

    verify(session, times(1)).setStatus(SessionStatus.IN_PROGRESS);
  }

  // ── FE#1876 S6: ending a same-topic duplicate ────────────────────────────

  private Session acceptedSession(long id, String askerId) {
    var asker = new User();
    asker.setUserId(askerId);
    var session = new Session();
    session.setId(id);
    session.setUser(asker);
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setMainTopicId(3L);
    session.setPostcode("79098");
    return session;
  }

  @Test
  public void endTopicDuplicate_Should_ArchiveAndNoticeTheOlderRoom() {
    var newer = acceptedSession(SESSION_ID, "asker");
    var older = acceptedSession(1L, "asker");
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(newer));
    when(authenticatedUser.getUserId()).thenReturn("asker");
    when(topicDuplicateService.olderActiveSameTopicSessions(newer))
        .thenReturn(java.util.List.of(older));

    sessionArchiveService.endTopicDuplicate(SESSION_ID);

    assertThat(newer.getStatus()).isEqualTo(SessionStatus.IN_ARCHIVE);
    verify(sessionRepository).save(newer);
    verify(topicDuplicateService).notifyDuplicateEnded(java.util.List.of(older), 3L);
  }

  @Test
  public void endTopicDuplicate_Should_BeForbidden_ForSomebodyElse() {
    var newer = acceptedSession(SESSION_ID, "asker");
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(newer));
    when(authenticatedUser.getUserId()).thenReturn("another-asker");

    assertThrows(
        ForbiddenException.class, () -> sessionArchiveService.endTopicDuplicate(SESSION_ID));
    verify(sessionRepository, times(0)).save(any());
  }

  @Test
  public void endTopicDuplicate_Should_Conflict_WhenThereIsNoOlderSameTopicSession() {
    var only = acceptedSession(SESSION_ID, "asker");
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(only));
    when(authenticatedUser.getUserId()).thenReturn("asker");
    when(topicDuplicateService.olderActiveSameTopicSessions(only)).thenReturn(java.util.List.of());

    assertThrows(
        ConflictException.class, () -> sessionArchiveService.endTopicDuplicate(SESSION_ID));
    verify(sessionRepository, times(0)).save(any());
  }

  @Test
  public void endTopicDuplicate_Should_Conflict_WhenTheSessionIsNotInProgress() {
    var archived = acceptedSession(SESSION_ID, "asker");
    archived.setStatus(SessionStatus.IN_ARCHIVE);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(archived));
    when(authenticatedUser.getUserId()).thenReturn("asker");

    assertThrows(
        ConflictException.class, () -> sessionArchiveService.endTopicDuplicate(SESSION_ID));
  }

  @Test
  public void archiveSession_Should_NoticeTheOlderRoom_WhenACounsellorArchivesTheNewerDuplicate() {
    var newer = acceptedSession(SESSION_ID, "asker");
    var older = acceptedSession(1L, "asker");
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(newer));
    when(authenticatedUser.getUserId()).thenReturn("counsellor");
    var counsellor = new de.caritas.cob.userservice.api.model.Consultant();
    counsellor.setId("counsellor");
    newer.setConsultant(counsellor);
    when(topicDuplicateService.olderActiveSameTopicSessions(newer))
        .thenReturn(java.util.List.of(older));

    sessionArchiveService.archiveSession(SESSION_ID);

    verify(topicDuplicateService).notifyDuplicateEnded(java.util.List.of(older), 3L);
  }

  @Test
  public void archiveSession_Should_SendNoDuplicateNotice_ForAnOrdinarySession() {
    var only = acceptedSession(SESSION_ID, "asker");
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(only));
    when(authenticatedUser.getUserId()).thenReturn("asker");
    when(topicDuplicateService.olderActiveSameTopicSessions(only)).thenReturn(java.util.List.of());

    sessionArchiveService.archiveSession(SESSION_ID);

    verify(topicDuplicateService, times(0)).notifyDuplicateEnded(any(), anyLong());
  }

  @Test
  public void archiveSession_Should_SkipTheTopicLookup_WhenTheSessionHasNoTopic() {
    var only = acceptedSession(SESSION_ID, "asker");
    only.setMainTopicId(null);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(only));
    when(authenticatedUser.getUserId()).thenReturn("asker");

    sessionArchiveService.archiveSession(SESSION_ID);

    verifyNoInteractions(topicDuplicateService);
  }
}
