package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.NotificationRoomLevel;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.model.TeamDiscussionParticipant;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.NotificationRoomLevelRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * US#473 hybrid notification rule: first post → whole eligible circle; later posts → participants
 * plus mentioned; per-conversation levels (Muted / Snoozed / Mentions-only) are honoured.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamDiscussionNotificationServiceTest {

  private static final String ROOM_ID = "!discussion:oriso";
  private static final Long SESSION_ID = 42L;
  private static final Long AGENCY_ID = 7L;
  private static final Long DISCUSSION_ID = 99L;
  private static final String SENDER = "consultant-sender";
  private static final String COLLEAGUE_A = "consultant-a";
  private static final String COLLEAGUE_B = "consultant-b";

  @InjectMocks private TeamDiscussionNotificationService service;

  @Mock private TeamDiscussionRepository teamDiscussionRepository;
  @Mock private TeamDiscussionParticipantRepository participantRepository;
  @Mock private NotificationRoomLevelRepository notificationRoomLevelRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private EventNotificationService eventNotificationService;

  private TeamDiscussion discussion;
  private Session session;

  @BeforeEach
  void setUp() {
    discussion =
        TeamDiscussion.builder()
            .id(DISCUSSION_ID)
            .sessionId(SESSION_ID)
            .matrixRoomId(ROOM_ID)
            .status(TeamDiscussion.Status.OPEN)
            .firstNotified(false)
            .build();
    session = new Session();
    session.setId(SESSION_ID);
    session.setAgencyId(AGENCY_ID);
    session.setTenantId(3L);

    when(teamDiscussionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(discussion));
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(AGENCY_ID))
        .thenReturn(
            List.of(
                consultantAgency(SENDER),
                consultantAgency(COLLEAGUE_A),
                consultantAgency(COLLEAGUE_B)));
    when(participantRepository.findByTeamDiscussionId(DISCUSSION_ID)).thenReturn(List.of());
    when(notificationRoomLevelRepository.findByUserIdAndRoomId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(eventNotificationService.isNotificationSuppressed(anyString(), anyString()))
        .thenReturn(false);
  }

  private ConsultantAgency consultantAgency(String consultantId) {
    var consultant = new Consultant();
    consultant.setId(consultantId);
    var ca = new ConsultantAgency();
    ca.setConsultant(consultant);
    ca.setAgencyId(AGENCY_ID);
    return ca;
  }

  private TeamDiscussionParticipant participant(String consultantId) {
    return TeamDiscussionParticipant.builder()
        .teamDiscussionId(DISCUSSION_ID)
        .consultantId(consultantId)
        .joinDate(LocalDateTime.now())
        .build();
  }

  @Test
  void firstPost_shouldNotifyWholeEligibleCircleExceptSender() {
    service.createTeamDiscussionNotification(ROOM_ID, SENDER, "Kim", null);

    verify(eventNotificationService)
        .createEvent(
            eq(COLLEAGUE_A),
            eq("team.discussion.new"),
            eq(EventNotificationService.CATEGORY_MESSAGE),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            eq(SESSION_ID),
            eq(3L));
    verify(eventNotificationService)
        .createEvent(
            eq(COLLEAGUE_B),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
    verify(eventNotificationService, never())
        .createEvent(
            eq(SENDER),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
    assertThat(discussion.isFirstNotified()).isTrue();
    verify(teamDiscussionRepository).save(discussion);
  }

  @Test
  void laterPost_shouldNotifyOnlyParticipantsAndMentioned() {
    discussion.setFirstNotified(true);
    when(participantRepository.findByTeamDiscussionId(DISCUSSION_ID))
        .thenReturn(List.of(participant(SENDER), participant(COLLEAGUE_A)));

    service.createTeamDiscussionNotification(ROOM_ID, SENDER, "Kim", List.of(COLLEAGUE_B));

    // participant
    verify(eventNotificationService)
        .createEvent(
            eq(COLLEAGUE_A),
            eq("team.discussion.new"),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
    // mentioned non-participant
    verify(eventNotificationService)
        .createEvent(
            eq(COLLEAGUE_B),
            eq("team.discussion.new"),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
  }

  @Test
  void laterPost_shouldNotNotifyNonParticipants() {
    discussion.setFirstNotified(true);
    when(participantRepository.findByTeamDiscussionId(DISCUSSION_ID))
        .thenReturn(List.of(participant(SENDER)));

    service.createTeamDiscussionNotification(ROOM_ID, SENDER, "Kim", null);

    verify(eventNotificationService, never())
        .createEvent(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
  }

  @Test
  void mutedRecipient_shouldBeSkipped() {
    when(notificationRoomLevelRepository.findByUserIdAndRoomId(COLLEAGUE_A, ROOM_ID))
        .thenReturn(
            Optional.of(
                NotificationRoomLevel.builder()
                    .userId(COLLEAGUE_A)
                    .roomId(ROOM_ID)
                    .level(NotificationRoomLevel.Level.MUTED)
                    .build()));

    service.createTeamDiscussionNotification(ROOM_ID, SENDER, "Kim", null);

    verify(eventNotificationService, never())
        .createEvent(
            eq(COLLEAGUE_A),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
  }

  @Test
  void activeSnooze_shouldBeSkipped_andExpiredSnoozeDelivers() {
    when(notificationRoomLevelRepository.findByUserIdAndRoomId(COLLEAGUE_A, ROOM_ID))
        .thenReturn(
            Optional.of(
                NotificationRoomLevel.builder()
                    .userId(COLLEAGUE_A)
                    .roomId(ROOM_ID)
                    .level(NotificationRoomLevel.Level.ALL)
                    .snoozedUntil(LocalDateTime.now().plusHours(1))
                    .build()));
    when(notificationRoomLevelRepository.findByUserIdAndRoomId(COLLEAGUE_B, ROOM_ID))
        .thenReturn(
            Optional.of(
                NotificationRoomLevel.builder()
                    .userId(COLLEAGUE_B)
                    .roomId(ROOM_ID)
                    .level(NotificationRoomLevel.Level.ALL)
                    .snoozedUntil(LocalDateTime.now().minusHours(1))
                    .build()));

    service.createTeamDiscussionNotification(ROOM_ID, SENDER, "Kim", null);

    verify(eventNotificationService, never())
        .createEvent(
            eq(COLLEAGUE_A),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
    verify(eventNotificationService)
        .createEvent(
            eq(COLLEAGUE_B),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
  }

  @Test
  void mentionsOnlyRecipient_shouldOnlyReceiveWhenMentioned() {
    when(notificationRoomLevelRepository.findByUserIdAndRoomId(COLLEAGUE_A, ROOM_ID))
        .thenReturn(
            Optional.of(
                NotificationRoomLevel.builder()
                    .userId(COLLEAGUE_A)
                    .roomId(ROOM_ID)
                    .level(NotificationRoomLevel.Level.MENTIONS)
                    .build()));

    service.createTeamDiscussionNotification(ROOM_ID, SENDER, "Kim", List.of(COLLEAGUE_A));
    verify(eventNotificationService)
        .createEvent(
            eq(COLLEAGUE_A),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
  }

  @Test
  void archivedDiscussion_shouldProduceNothing() {
    discussion.setStatus(TeamDiscussion.Status.ARCHIVED);

    service.createTeamDiscussionNotification(ROOM_ID, SENDER, "Kim", null);

    verify(eventNotificationService, never())
        .createEvent(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
  }

  @Test
  void senderBecomesParticipant() {
    service.createTeamDiscussionNotification(ROOM_ID, SENDER, "Kim", null);

    verify(participantRepository).save(any(TeamDiscussionParticipant.class));
  }

  @Test
  void updateConversationLevel_shouldUpsert() {
    when(notificationRoomLevelRepository.findByUserIdAndRoomId(COLLEAGUE_A, ROOM_ID))
        .thenReturn(Optional.empty());

    service.updateConversationLevel(COLLEAGUE_A, ROOM_ID, NotificationRoomLevel.Level.MUTED, null);

    verify(notificationRoomLevelRepository).save(any(NotificationRoomLevel.class));
  }

  @Test
  void ineligibleSender_shouldProduceNothingAndNotFlipFirstNotified() {
    // Review finding F1: /users/event-notifications/** is reachable for USER_DEFAULT too —
    // a caller outside the eligible circle must never trigger (or suppress) the broadcast.
    service.createTeamDiscussionNotification(ROOM_ID, "outsider-or-asker", "Spoof", null);

    verify(eventNotificationService, never())
        .createEvent(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
    assertThat(discussion.isFirstNotified()).isFalse();
    verify(teamDiscussionRepository, never()).save(any());
    verify(participantRepository, never()).save(any());
  }

  @Test
  void assignedSession_shouldProduceNothingEvenWhileDiscussionStillOpen() {
    // Review finding F2 (race): once the case is accepted, an OPEN row must not keep notifying.
    var acceptingConsultant = new Consultant();
    acceptingConsultant.setId(COLLEAGUE_A);
    session.setConsultant(acceptingConsultant);

    service.createTeamDiscussionNotification(ROOM_ID, SENDER, "Kim", null);

    verify(eventNotificationService, never())
        .createEvent(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any());
  }
}
