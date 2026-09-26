package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.CustomLocalDateTime;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.RecipientRole;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.notification.GroupAppointmentSeriesEventProducer;
import java.time.LocalDateTime;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("testing")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GroupChatAdmissionCommitIT {

  @Autowired private GroupChatJoinRequestService service;
  @Autowired private GroupChatAdmissionProcessor processor;
  @Autowired private GroupChatJoinRequestRepository requests;
  @Autowired private GroupChatParticipantRepository participants;
  @Autowired private ChatRepository chats;
  @Autowired private ConsultantRepository consultants;

  @MockitoBean private GroupChatPermissionService permissions;
  @MockitoBean private GroupChatMembershipService membership;
  @MockitoBean private GroupAppointmentSeriesEventProducer appointmentEvents;

  private Chat series;
  private Consultant requester;
  private String oldMatrixUserId;

  @BeforeEach
  void setUp() {
    var users =
        StreamSupport.stream(consultants.findAll().spliterator(), false)
            .filter(user -> user.getDeleteDate() == null)
            .limit(2)
            .toList();
    assertThat(users).hasSize(2);
    var owner = users.get(0);
    requester = users.get(1);
    oldMatrixUserId = requester.getMatrixUserId();
    requester.setMatrixUserId("@admission-test:matrix.test");
    consultants.save(requester);

    var now = LocalDateTime.now();
    series =
        chats.save(
            Chat.builder()
                .topic("Commit boundary group")
                .consultingTypeId(1)
                .initialStartDate(now)
                .startDate(now)
                .duration(60)
                .conversationType(ConversationType.SELF_HELP)
                .matrixRoomId("!admission-test:matrix.test")
                .chatOwner(owner)
                .createDate(now)
                .updateDate(now)
                .build());
    participants.save(
        GroupChatParticipant.builder()
            .chatId(991L)
            .seriesId(series.getId())
            .consultantId(owner.getId())
            .role(ParticipantRole.OWNER)
            .build());
  }

  @AfterEach
  void cleanUp() {
    if (series != null) {
      requests
          .findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
              java.util.Set.of(series.getId()), Status.PENDING)
          .forEach(requests::delete);
      requests
          .findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
              java.util.Set.of(series.getId()), Status.ADMITTING)
          .forEach(requests::delete);
      requests
          .findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
              java.util.Set.of(series.getId()), Status.ADMITTED)
          .forEach(requests::delete);
      participants.findBySeriesId(series.getId()).forEach(participants::delete);
      chats.delete(series);
    }
    if (requester != null) {
      requester.setMatrixUserId(oldMatrixUserId);
      consultants.save(requester);
    }
  }

  @Test
  void failedImmediateJoinRemainsDurableAndLaterRetryGrantsAccess() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(LocalDateTime.now())
                .build());
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(false, true);

    service.admit(series.getId(), request.getId(), series.getChatOwner().getId(), null);

    var waiting = requests.findById(request.getId()).orElseThrow();
    assertThat(waiting.getStatus()).isEqualTo(Status.ADMITTING);
    assertThat(waiting.getAdmissionRequestedAt()).isNotNull();
    assertThat(waiting.getAdmissionAttemptCount()).isEqualTo(1);
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isEmpty();
    verify(appointmentEvents, never()).recordMemberJoined(any(), any(), any());
    assertThat(processor.pendingIds()).doesNotContain(request.getId());

    waiting.setAdmissionLastAttemptAt(CustomLocalDateTime.nowInUtc().minusMinutes(2));
    requests.save(waiting);
    assertThat(processor.pendingIds()).contains(request.getId());

    processor.process(request.getId());

    var admitted = requests.findById(request.getId()).orElseThrow();
    assertThat(admitted.getStatus()).isEqualTo(Status.ADMITTED);
    assertThat(admitted.getDecidedAt()).isNotNull();
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .map(GroupChatParticipant::getRole)
        .contains(ParticipantRole.PARTICIPANT);
    verify(appointmentEvents)
        .recordMemberJoined(any(Chat.class), eq(RecipientRole.COUNSELOR), eq(requester.getId()));
    verify(membership, org.mockito.Mockito.times(2))
        .addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test"));
  }

  @Test
  void failedAppointmentQueueRollsBackAdmissionAndRetriesAfterMatrixJoined() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(LocalDateTime.now())
                .build());
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(true);
    doThrow(new IllegalStateException("mail queue unavailable"))
        .when(appointmentEvents)
        .recordMemberJoined(any(), any(), any());

    service.admit(series.getId(), request.getId(), series.getChatOwner().getId(), null);

    assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.ADMITTING);
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isEmpty();

    reset(appointmentEvents);
    processor.process(request.getId());

    assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.ADMITTED);
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isPresent();
    verify(appointmentEvents)
        .recordMemberJoined(any(Chat.class), eq(RecipientRole.COUNSELOR), eq(requester.getId()));
  }

  @Test
  void freshAdmissionIsNotStarvedByTwentyRepeatedFailures() {
    var oldAttempt = CustomLocalDateTime.nowInUtc().minusMinutes(2);
    for (int index = 0; index < 20; index++) {
      requests.save(
          GroupChatJoinRequest.builder()
              .seriesId(series.getId())
              .consultantId(requester.getId())
              .status(Status.ADMITTING)
              .admittedRole(ParticipantRole.PARTICIPANT)
              .admissionRequestedAt(oldAttempt.minusDays(1))
              .admissionLastAttemptAt(oldAttempt)
              .admissionAttemptCount(1)
              .requestedAt(oldAttempt.minusDays(1))
              .build());
    }
    var fresh =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.ADMITTING)
                .admittedRole(ParticipantRole.PARTICIPANT)
                .admissionRequestedAt(CustomLocalDateTime.nowInUtc())
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());

    assertThat(processor.pendingIds()).hasSize(20).contains(fresh.getId());
  }

  @Test
  void unexpectedWorkerFailureRecordsRetryAfterOriginalDecisionCommitted() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenThrow(new IllegalStateException("remote call failed"));

    service.admit(series.getId(), request.getId(), series.getChatOwner().getId(), null);

    var waiting = requests.findById(request.getId()).orElseThrow();
    assertThat(waiting.getStatus()).isEqualTo(Status.ADMITTING);
    assertThat(waiting.getAdmissionAttemptCount()).isEqualTo(1);
    assertThat(waiting.getAdmissionLastAttemptAt()).isNotNull();
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isEmpty();
  }
}
