package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class GroupChatAdmissionProcessorTest {

  @Mock private GroupChatJoinRequestRepository requests;
  @Mock private GroupChatParticipantRepository participants;
  @Mock private ChatRepository chats;
  @Mock private ConsultantRepository consultants;
  @Mock private GroupChatMembershipService membership;
  @Mock private GroupAppointmentSeriesEventProducer appointmentEvents;
  @InjectMocks private GroupChatAdmissionProcessor processor;

  private GroupChatJoinRequest request;
  private Chat series;

  @BeforeEach
  void setUp() {
    request =
        GroupChatJoinRequest.builder()
            .id(7L)
            .seriesId(11L)
            .consultantId("requester")
            .status(Status.ADMITTING)
            .admittedRole(ParticipantRole.PARTICIPANT)
            .admissionRequestedAt(LocalDateTime.now())
            .build();
    series = mock(Chat.class);
    when(series.getConversationType()).thenReturn(ConversationType.SELF_HELP);
    var requester = mock(Consultant.class);
    when(requester.getMatrixUserId()).thenReturn("@r:test");
    when(requests.findByIdForUpdate(7L)).thenReturn(Optional.of(request));
    when(chats.findById(11L)).thenReturn(Optional.of(series));
    when(consultants.findByIdAndDeleteDateIsNull("requester")).thenReturn(Optional.of(requester));
    when(participants.findBySeriesIdForUpdate(11L))
        .thenReturn(
            List.of(
                GroupChatParticipant.builder()
                    .chatId(19L)
                    .seriesId(11L)
                    .consultantId("owner")
                    .role(ParticipantRole.OWNER)
                    .build()));
  }

  @Test
  void failedMatrixJoinLeavesDurableIntentWithoutParticipantAndLaterRetryCompletes() {
    when(membership.addMemberToRoom(series, "@r:test")).thenReturn(false, true);

    processor.process(7L);
    assertThat(request.getStatus()).isEqualTo(Status.ADMITTING);
    assertThat(request.getAdmissionAttemptCount()).isEqualTo(1);
    assertThat(request.getAdmissionLastAttemptAt()).isNotNull();
    verify(participants, never()).save(any());
    verify(appointmentEvents, never()).recordMemberJoined(any(), any(), any());

    processor.process(7L);
    assertThat(request.getStatus()).isEqualTo(Status.ADMITTED);
    assertThat(request.getDecidedAt()).isNotNull();
    var saved = ArgumentCaptor.forClass(GroupChatParticipant.class);
    verify(participants).save(saved.capture());
    assertThat(saved.getValue().getConsultantId()).isEqualTo("requester");
    assertThat(saved.getValue().getChatId()).isEqualTo(19L);
    verify(appointmentEvents).recordMemberJoined(series, RecipientRole.COUNSELOR, "requester");

    processor.process(7L);
    verify(membership, times(2)).addMemberToRoom(series, "@r:test");
    verify(appointmentEvents).recordMemberJoined(series, RecipientRole.COUNSELOR, "requester");
  }

  @Test
  void failedParticipantWriteDoesNotMarkRequestAdmittedAfterMatrixJoined() {
    when(membership.addMemberToRoom(series, "@r:test")).thenReturn(true);
    when(participants.save(any())).thenThrow(new DataIntegrityViolationException("write failed"));

    assertThatThrownBy(() -> processor.process(7L))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(request.getStatus()).isEqualTo(Status.ADMITTING);
    assertThat(request.getAdmissionRequestedAt()).isNotNull();
    verify(appointmentEvents, never()).recordMemberJoined(any(), any(), any());

    processor.recordFailure(7L);
    assertThat(request.getAdmissionAttemptCount()).isEqualTo(1);
    assertThat(request.getAdmissionLastAttemptAt()).isNotNull();
  }
}
