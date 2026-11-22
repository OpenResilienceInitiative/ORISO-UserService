package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class GroupChatAdmissionProcessorTest {

  @Mock private GroupChatJoinRequestRepository requests;
  @Mock private GroupChatParticipantRepository participants;
  @Mock private ChatRepository chats;
  @Mock private ConsultantRepository consultants;
  @Mock private GroupChatMembershipService membership;
  @Mock private GroupAppointmentSeriesEventProducer appointmentEvents;
  @Mock private GroupChatAdmissionMatrixRepairService repair;

  @Mock
  private de.caritas.cob.userservice.api.service.matrix.GroupModeratorPowerService moderatorPower;

  @InjectMocks private GroupChatAdmissionProcessor processor;

  private GroupChatJoinRequest request;
  private Chat series;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(
        processor,
        "dpaPolicy",
        de.caritas.cob.userservice.api.testHelper.PermittingDpaOwnerFixture.groupPolicy());
    TransactionSynchronizationManager.initSynchronization();
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
    var owner = new Consultant();
    owner.setTenantId(41L);
    lenient().when(series.getChatOwner()).thenReturn(owner);
    var requester = mock(Consultant.class);
    when(requester.getMatrixUserId()).thenReturn("@r:test");
    when(requests.findByIdForUpdate(7L)).thenReturn(Optional.of(request));
    when(chats.findSeriesForAppointmentMailUpdate(11L)).thenReturn(Optional.of(series));
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

  @AfterEach
  void clearSynchronization() {
    TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  void failedMatrixJoinLeavesDurableIntentWithoutParticipantAndLaterRetryCompletes() {
    when(membership.isMemberInRoom(series, "@r:test")).thenReturn(Optional.of(false));
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

  /** ORISO-Frontend#2229: a colleague admitted as co-moderator may blur passages right away. */
  @Test
  void aColleagueAdmittedAsCoModeratorIsRaisedToTheMatrixModeratorLevel() {
    request.setAdmittedRole(ParticipantRole.CO_MODERATOR);
    when(membership.isMemberInRoom(series, "@r:test")).thenReturn(Optional.of(false));
    when(membership.addMemberToRoom(series, "@r:test")).thenReturn(true);

    processor.process(7L);

    var order = org.mockito.Mockito.inOrder(participants, moderatorPower);
    order.verify(participants).save(any());
    order
        .verify(moderatorPower)
        .grantModeratorsAfterCommit(series, java.util.Collections.singletonList("@r:test"));
  }

  /** A former moderator who comes back as a participant does not keep an old moderator level. */
  @Test
  void aColleagueAdmittedAsParticipantHoldsNoMatrixModeratorLevel() {
    when(membership.isMemberInRoom(series, "@r:test")).thenReturn(Optional.of(false));
    when(membership.addMemberToRoom(series, "@r:test")).thenReturn(true);

    processor.process(7L);

    verify(moderatorPower).reconcileOrFail(series, "@r:test", ParticipantRole.PARTICIPANT);
  }

  @Test
  void aColleagueAdmittedAsParticipantIsNotAdmittedWhenMatrixKeepsTheModeratorLevel() {
    when(membership.isMemberInRoom(series, "@r:test")).thenReturn(Optional.of(false));
    when(membership.resolveMatrixRoomId(series)).thenReturn("!room:test");
    when(membership.addMemberToRoom(series, "@r:test")).thenReturn(true);
    var refused = matrixKeepsTheModeratorLevel();

    assertThatThrownBy(() -> processor.process(7L)).isSameAs(refused);
    rollBack();

    assertThat(request.getStatus()).isEqualTo(Status.ADMITTING);
    // The join of this attempt is undone with the rolled-back admission.
    verify(membership).removeMemberFromRoom("!room:test", "@r:test");
  }

  /** Someone who was in the room before this attempt is not taken out by its rollback. */
  @Test
  void aRolledBackParticipantAdmissionKeepsAMemberWhoWasInTheRoomBefore() {
    when(membership.isMemberInRoom(series, "@r:test")).thenReturn(Optional.of(true));
    lenient().when(membership.resolveMatrixRoomId(series)).thenReturn("!room:test");
    lenient().when(membership.addMemberToRoom(series, "@r:test")).thenReturn(true);
    var refused = matrixKeepsTheModeratorLevel();

    assertThatThrownBy(() -> processor.process(7L)).isSameAs(refused);
    rollBack();

    verify(membership, never()).removeMemberFromRoom(any(), any());
  }

  private de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
      matrixKeepsTheModeratorLevel() {
    var refused =
        new de.caritas.cob.userservice.api.exception.httpresponses
            .CustomValidationHttpStatusException(
            de.caritas.cob.userservice.api.exception.httpresponses.customheader
                .HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED,
            org.springframework.http.HttpStatus.BAD_GATEWAY);
    doThrow(refused)
        .when(moderatorPower)
        .reconcileOrFail(series, "@r:test", ParticipantRole.PARTICIPANT);
    return refused;
  }

  private static void rollBack() {
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(
            synchronization ->
                synchronization.afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization
                        .STATUS_ROLLED_BACK));
  }

  @Test
  void failedParticipantWriteDoesNotMarkRequestAdmittedAfterMatrixJoined() {
    when(membership.isMemberInRoom(series, "@r:test")).thenReturn(Optional.of(false));
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

  @Test
  void unknownMatrixMembershipLeavesAdmissionForRetryWithoutJoining() {
    when(membership.isMemberInRoom(series, "@r:test")).thenReturn(Optional.empty());

    processor.process(7L);

    assertThat(request.getStatus()).isEqualTo(Status.ADMITTING);
    assertThat(request.getAdmissionAttemptCount()).isEqualTo(1);
    verify(membership, never()).addMemberToRoom(any(), any());
    verify(participants, never()).save(any());
  }

  @Test
  void anAdmissionThatKeepsFailingIsHandedBackToModeratorsAtTheAttemptLimit() {
    ReflectionTestUtils.setField(processor, "maxAttempts", 2);
    request.setDecidedBy("owner");
    when(membership.isMemberInRoom(series, "@r:test")).thenReturn(Optional.empty());

    processor.process(7L);
    assertThat(request.getStatus()).isEqualTo(Status.ADMITTING);
    assertThat(request.getAdmissionAttemptCount()).isEqualTo(1);

    processor.recordFailure(7L);

    assertThat(request.getStatus()).isEqualTo(Status.PENDING);
    assertThat(request.getAdmissionRequestedAt()).isNull();
    assertThat(request.getAdmittedRole()).isNull();
    assertThat(request.getDecidedBy()).isNull();
    assertThat(request.getAdmissionAttemptCount()).isZero();
    verify(participants, never()).save(any());
  }
}
