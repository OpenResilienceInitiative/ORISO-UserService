package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.GroupRosterRemovalIntent;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.GroupRosterRemovalIntentRepository;
import de.caritas.cob.userservice.api.port.out.GroupRosterRemovalIntentRepository.RetryRoute;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupRosterRemovalRecoveryTest {

  private static final long GROUP_ID = 42;
  private static final long TENANT_ID = 7;
  private static final long SESSION_ID = 12;
  private static final long PARTICIPANT_ID = 15;
  private static final long ROSTER_VERSION = 4;
  private static final long SUPERVISION_ID = 32;
  private static final long INTENT_ID = 124;
  private static final int OCCURRENCE = 3;
  private static final String OWNER_ID = "owner";
  private static final String CONSULTANT_ID = "consultant";
  private static final String MEMBER_ID = "@consultant:matrix";
  private static final String SUPERVISOR_MEMBER_ID = "@supervisor:matrix";
  private static final String MAIN_ROOM = "!group:matrix";
  private static final String SIDE_ROOM = "!supervision:matrix";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-21T09:00:00Z"), ZoneOffset.UTC);
  private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
  private static final GroupRosterRemovalRecovery.GroupScope GROUP =
      new GroupRosterRemovalRecovery.GroupScope(
          GROUP_ID, OWNER_ID, TENANT_ID, MAIN_ROOM, OCCURRENCE);
  private static final GroupRosterRemovalRecovery.ParticipantScope PARTICIPANT =
      new GroupRosterRemovalRecovery.ParticipantScope(
          PARTICIPANT_ID, ROSTER_VERSION, CONSULTANT_ID);

  @Mock private GroupRosterRemovalIntentRepository intents;
  @Mock private ChatRepository groups;
  @Mock private ConsultantRepository consultants;
  @Mock private GroupChatParticipantRepository participants;
  @Mock private SessionSupervisorRepository supervisions;
  @Mock private MembersOnlyRevocation revocation;
  @Mock private MatrixSynapseService matrix;
  @Mock private Chat group;
  private GroupRosterRemovalRecovery recovery;

  @BeforeEach
  void createRecovery() {
    recovery =
        new GroupRosterRemovalRecovery(
            intents, groups, consultants, participants, supervisions, revocation, matrix, CLOCK);
  }

  @Test
  void confirmedMainDepartureRepairsTheOriginalParticipantAfterRollback() {
    var intent = intentFor(MAIN_ROOM).dispatchSettled(true).build();
    var participant = originalParticipant();
    givenCurrentRoster(intent, participant, OCCURRENCE);
    when(matrix.isOutOfRoom(MAIN_ROOM, MEMBER_ID)).thenReturn(Optional.of(true));

    recovery.retry(INTENT_ID);

    var order = inOrder(consultants, groups, participants, matrix, revocation, intents);
    order.verify(intents).findRetryRouteById(INTENT_ID);
    order.verify(consultants).findPictureOwnerForUpdate(OWNER_ID);
    order.verify(groups).findByIdForUpdate(GROUP_ID);
    order.verify(intents).lockById(INTENT_ID);
    order.verify(participants).findBySeriesIdAndConsultantIdForUpdate(GROUP_ID, CONSULTANT_ID);
    order.verify(matrix).isOutOfRoom(MAIN_ROOM, MEMBER_ID);
    order.verify(revocation).handOverAfterVerifiedGroupDeparture(group, participant);
    order.verify(participants).delete(participant);
    order.verify(intents).delete(intent);
    verifyNoMoreInteractions(matrix);
  }

  @Test
  void sideOnlyDepartureEndsTheOriginalSupervisionAndPreservesTheRoster() {
    var intent =
        intentFor(SIDE_ROOM).supervisionId(SUPERVISION_ID).memberId(SUPERVISOR_MEMBER_ID).build();
    var participant = originalParticipant();
    var supervision = supervision(SUPERVISION_ID, SIDE_ROOM);
    var supervisor = new Consultant();
    supervisor.setMatrixUserId(SUPERVISOR_MEMBER_ID);
    supervision.setSupervisorConsultant(supervisor);
    givenCurrentRoster(intent, participant, OCCURRENCE);
    givenActiveSupervision(supervision);
    when(matrix.isOutOfRoom(SIDE_ROOM, SUPERVISOR_MEMBER_ID)).thenReturn(Optional.of(true));

    recovery.retry(INTENT_ID);

    verify(revocation).endLocally(supervision);
    verify(intents).delete(intent);
    verify(participants).findBySeriesIdAndConsultantIdForUpdate(GROUP_ID, CONSULTANT_ID);
    verify(matrix).isOutOfRoom(SIDE_ROOM, SUPERVISOR_MEMBER_ID);
    verifyNoMoreInteractions(participants, revocation, matrix);
  }

  @Test
  void mainDepartureLeavesAReplacementSupervisionAndItsParticipantPending() {
    var intent = intentFor(MAIN_ROOM).localSupervisionId(SUPERVISION_ID).build();
    var participant = originalParticipant();
    givenCurrentRoster(intent, participant, OCCURRENCE);
    givenActiveSupervision(supervision(SUPERVISION_ID + 1, null));
    when(matrix.isOutOfRoom(MAIN_ROOM, MEMBER_ID)).thenReturn(Optional.of(true));

    recovery.retry(INTENT_ID);

    assertThat(intent.getRetryAt()).isEqualTo(NOW.plusMinutes(1));
    verify(participants).findBySeriesIdAndConsultantIdForUpdate(GROUP_ID, CONSULTANT_ID);
    verifyNoMoreInteractions(participants);
    verifyNoInteractions(revocation);
  }

  @Test
  void mainDepartureCanEndItsOriginalSupervisionWithoutASideRoom() {
    var intent = intentFor(MAIN_ROOM).localSupervisionId(SUPERVISION_ID).build();
    var participant = originalParticipant();
    var supervision = supervision(SUPERVISION_ID, null);
    givenCurrentRoster(intent, participant, OCCURRENCE);
    givenActiveSupervision(supervision);
    when(matrix.isOutOfRoom(MAIN_ROOM, MEMBER_ID)).thenReturn(Optional.of(true));

    recovery.retry(INTENT_ID);

    var order = inOrder(matrix, revocation, participants, intents);
    order.verify(matrix).isOutOfRoom(MAIN_ROOM, MEMBER_ID);
    order.verify(revocation).endLocally(supervision);
    order.verify(revocation).handOverAfterVerifiedGroupDeparture(group, participant);
    order.verify(participants).delete(participant);
    order.verify(intents).delete(intent);
    verifyNoMoreInteractions(matrix, revocation);
  }

  @ParameterizedTest
  @EnumSource(UnconfirmedAbsence.class)
  void aReplacementWithTheSameSideRoomKeepsTheUnsettledDeparture(UnconfirmedAbsence state) {
    var intent = intentFor(SIDE_ROOM).supervisionId(SUPERVISION_ID).build();
    givenCurrentRoster(intent, originalParticipant(), OCCURRENCE);
    givenActiveSupervision(supervision(SUPERVISION_ID + 1, SIDE_ROOM));
    when(matrix.isOutOfRoom(SIDE_ROOM, MEMBER_ID)).thenReturn(state.result);

    recovery.retry(INTENT_ID);

    assertThat(intent.getRetryAt()).isEqualTo(NOW.plusMinutes(1));
    verify(intents, never()).delete(intent);
    verify(participants, never()).delete(any());
    verifyNoInteractions(revocation);
  }

  @Test
  void confirmedStaleSideDepartureKeepsTheReplacementSupervision() {
    var intent = intentFor(SIDE_ROOM).supervisionId(SUPERVISION_ID).build();
    givenCurrentRoster(intent, originalParticipant(), OCCURRENCE);
    givenActiveSupervision(supervision(SUPERVISION_ID + 1, SIDE_ROOM));
    when(matrix.isOutOfRoom(SIDE_ROOM, MEMBER_ID)).thenReturn(Optional.of(true));

    recovery.retry(INTENT_ID);

    verify(matrix).isOutOfRoom(SIDE_ROOM, MEMBER_ID);
    verify(intents).delete(intent);
    verify(participants, never()).delete(any());
    verifyNoInteractions(revocation);
  }

  @Test
  void aReplacedParticipantKeepsTheUnsettledSideDeparture() {
    var intent =
        intentFor(SIDE_ROOM).supervisionId(SUPERVISION_ID).memberId(SUPERVISOR_MEMBER_ID).build();
    var participant = originalParticipant();
    participant.setId(PARTICIPANT_ID + 1);
    givenCurrentRoster(intent, participant, OCCURRENCE);
    when(matrix.isOutOfRoom(SIDE_ROOM, SUPERVISOR_MEMBER_ID)).thenReturn(Optional.empty());

    recovery.retry(INTENT_ID);

    assertThat(intent.getRetryAt()).isEqualTo(NOW.plusMinutes(1));
    verify(intents, never()).delete(intent);
    verify(participants, never()).delete(any());
    verifyNoInteractions(supervisions, revocation);
  }

  @ParameterizedTest
  @EnumSource(
      value = ParticipantRole.class,
      names = {"OWNER", "PARTICIPANT"})
  void aLaterRoleChangeRetiresThePreviousModeratorIntent(ParticipantRole laterRole) {
    var intent = intentFor(MAIN_ROOM).dispatchSettled(true).build();
    var participant = originalParticipant();
    participant.setRole(laterRole);
    givenCurrentRoster(intent, participant, OCCURRENCE);

    recovery.retry(INTENT_ID);

    verify(intents).delete(intent);
    verify(participants).findBySeriesIdAndConsultantIdForUpdate(GROUP_ID, CONSULTANT_ID);
    verifyNoMoreInteractions(participants);
    verifyNoInteractions(matrix, revocation);
  }

  @Test
  void aRecreatedParticipationCannotBeRemovedByTheOldIntent() {
    var intent = intentFor(MAIN_ROOM).dispatchSettled(true).build();
    var participant = originalParticipant();
    participant.setId(PARTICIPANT_ID + 1);
    givenCurrentRoster(intent, participant, OCCURRENCE);

    recovery.retry(INTENT_ID);

    verify(intents).delete(intent);
    verify(participants).findBySeriesIdAndConsultantIdForUpdate(GROUP_ID, CONSULTANT_ID);
    verifyNoMoreInteractions(participants);
    verifyNoInteractions(matrix, revocation);
  }

  @Test
  void aNewOccurrenceRetiresTheOldIntentWithoutCheckingThePreviousRoom() {
    var intent = intentFor(MAIN_ROOM).dispatchSettled(true).build();
    givenGroupAndIntent(intent, OCCURRENCE + 1);

    recovery.retry(INTENT_ID);

    verify(intents).delete(intent);
    verifyNoInteractions(participants, supervisions, matrix, revocation);
  }

  @Test
  void aModeratorWithAnUpdatedRosterVersionCannotBeRemovedByAnOlderIntent() {
    var intent = intentFor(MAIN_ROOM).dispatchSettled(true).build();
    var participant = originalParticipant();
    participant.setRosterVersion(ROSTER_VERSION + 2);
    givenCurrentRoster(intent, participant, OCCURRENCE);

    recovery.retry(INTENT_ID);

    verify(intents).delete(intent);
    verify(participants).findBySeriesIdAndConsultantIdForUpdate(GROUP_ID, CONSULTANT_ID);
    verifyNoMoreInteractions(participants);
    verifyNoInteractions(matrix, revocation);
  }

  @Test
  void anUnsettledStaleIntentInTheSameRoomContinuesToFenceAFutureRejoin() {
    var intent = intentFor(MAIN_ROOM).build();
    var participant = originalParticipant();
    participant.setRosterVersion(ROSTER_VERSION + 2);
    givenCurrentRoster(intent, participant, OCCURRENCE);
    when(matrix.isOutOfRoom(MAIN_ROOM, MEMBER_ID)).thenReturn(Optional.empty());

    recovery.retry(INTENT_ID);

    assertThat(intent.getRetryAt()).isEqualTo(NOW.plusMinutes(1));
    verify(intents, never()).delete(intent);
    verify(matrix).isOutOfRoom(MAIN_ROOM, MEMBER_ID);
    verify(participants).findBySeriesIdAndConsultantIdForUpdate(GROUP_ID, CONSULTANT_ID);
    verifyNoMoreInteractions(participants, matrix);
    verifyNoInteractions(revocation);
  }

  @ParameterizedTest
  @EnumSource(UnconfirmedAbsence.class)
  void pendingInviteOrUnknownStateDefersAnUnsettledDeparture(UnconfirmedAbsence state) {
    var intent = intentFor(MAIN_ROOM).build();
    givenCurrentRoster(intent, originalParticipant(), OCCURRENCE);
    when(matrix.isOutOfRoom(MAIN_ROOM, MEMBER_ID)).thenReturn(state.result);

    recovery.retry(INTENT_ID);

    assertThat(intent.isDispatchSettled()).isFalse();
    assertThat(intent.getRetryAt()).isEqualTo(NOW.plusMinutes(1));
    verify(participants).findBySeriesIdAndConsultantIdForUpdate(GROUP_ID, CONSULTANT_ID);
    verify(intents).findRetryRouteById(INTENT_ID);
    verify(intents).lockById(INTENT_ID);
    verify(matrix).isOutOfRoom(MAIN_ROOM, MEMBER_ID);
    verifyNoMoreInteractions(participants, intents, matrix);
    verifyNoInteractions(revocation);
  }

  @ParameterizedTest
  @EnumSource(UnconfirmedAbsence.class)
  void keepingTheParticipantWaitsForAnEarlierUnsettledDeparture(UnconfirmedAbsence state) {
    var intent = intentFor(MAIN_ROOM).build();
    givenRetainedIntent(intent);
    when(matrix.isOutOfRoom(MAIN_ROOM, MEMBER_ID)).thenReturn(state.result);

    assertThatThrownBy(
            () -> recovery.verifyRetainedMembers(GROUP, Map.of(CONSULTANT_ID, MEMBER_ID)))
        .isInstanceOf(ConflictException.class);

    assertThat(intent.isDispatchSettled()).isFalse();
    verify(matrix).isOutOfRoom(MAIN_ROOM, MEMBER_ID);
    verifyNoMoreInteractions(matrix);
  }

  @Test
  void confirmedDepartureAllowsARetainedParticipantToBeRejoinedOnce() {
    var intent = intentFor(MAIN_ROOM).build();
    when(intents.findBySeriesIdAndSupervisionIdIsNullAndDispatchSettledIsFalseAndConsultantIdIn(
            GROUP_ID, Set.of(CONSULTANT_ID)))
        .thenReturn(List.of(intent), List.of());
    when(matrix.isOutOfRoom(MAIN_ROOM, MEMBER_ID)).thenReturn(Optional.of(true));

    recovery.verifyRetainedMembers(GROUP, Map.of(CONSULTANT_ID, MEMBER_ID));
    recovery.verifyRetainedMembers(GROUP, Map.of(CONSULTANT_ID, MEMBER_ID));

    assertThat(intent.isDispatchSettled()).isTrue();
    verify(matrix).isOutOfRoom(MAIN_ROOM, MEMBER_ID);
    verifyNoMoreInteractions(matrix);
  }

  @Test
  void aLegitimateRejoinAfterReadbackSupersedesTheCompletedDeparture() {
    var intent = intentFor(MAIN_ROOM).dispatchSettled(true).build();
    givenCurrentRoster(intent, originalParticipant(), OCCURRENCE);
    when(matrix.isOutOfRoom(MAIN_ROOM, MEMBER_ID)).thenReturn(Optional.of(false));

    recovery.retry(INTENT_ID);

    verify(intents).delete(intent);
    verify(participants).findBySeriesIdAndConsultantIdForUpdate(GROUP_ID, CONSULTANT_ID);
    verify(matrix).isOutOfRoom(MAIN_ROOM, MEMBER_ID);
    verifyNoMoreInteractions(participants, matrix);
    verifyNoInteractions(revocation);
  }

  @ParameterizedTest
  @EnumSource(UnconfirmedAbsence.class)
  void retryingARemovalCannotReserveASecondDispatchWhileTheFirstIsUnsettled(
      UnconfirmedAbsence state) {
    var older = intentFor(MAIN_ROOM).build();
    when(intents.findBySeriesIdAndRoomIdAndMemberIdAndDispatchSettledIsFalse(
            GROUP_ID, MAIN_ROOM, MEMBER_ID))
        .thenReturn(List.of(older));
    when(matrix.isOutOfRoom(MAIN_ROOM, MEMBER_ID)).thenReturn(state.result);

    assertThatThrownBy(() -> recovery.record(GROUP, PARTICIPANT, MAIN_ROOM, MEMBER_ID, null, null))
        .isInstanceOf(ConflictException.class);

    assertThat(older.isDispatchSettled()).isFalse();
    verify(intents, never()).save(any(GroupRosterRemovalIntent.class));
    verify(matrix).isOutOfRoom(MAIN_ROOM, MEMBER_ID);
    verifyNoMoreInteractions(matrix);
  }

  @Test
  void retryingARemovalAdoptsTheVerifiedDepartureWithoutDispatchingAnotherLeave() {
    var older = intentFor(MAIN_ROOM).participantVersion(ROSTER_VERSION - 1).build();
    when(intents.findBySeriesIdAndRoomIdAndMemberIdAndDispatchSettledIsFalse(
            GROUP_ID, MAIN_ROOM, MEMBER_ID))
        .thenReturn(List.of(older));
    when(matrix.isOutOfRoom(MAIN_ROOM, MEMBER_ID)).thenReturn(Optional.of(true));
    when(intents.save(any(GroupRosterRemovalIntent.class)))
        .thenReturn(intentFor(MAIN_ROOM).id(INTENT_ID + 1).build());

    var reservation = recovery.record(GROUP, PARTICIPANT, MAIN_ROOM, MEMBER_ID, null, null);

    assertThat(reservation.id()).isEqualTo(INTENT_ID + 1);
    assertThat(reservation.sendLeave()).isFalse();
    assertThat(older.isDispatchSettled()).isTrue();
    var saved = ArgumentCaptor.forClass(GroupRosterRemovalIntent.class);
    verify(intents).save(saved.capture());
    assertThat(saved.getValue().isDispatchSettled()).isTrue();
    assertThat(saved.getValue().getParticipantVersion()).isEqualTo(ROSTER_VERSION);
    verify(matrix).isOutOfRoom(MAIN_ROOM, MEMBER_ID);
    verifyNoMoreInteractions(matrix);
  }

  private void givenGroupAndIntent(GroupRosterRemovalIntent intent, int occurrence) {
    var owner = new Consultant();
    owner.setId(OWNER_ID);
    owner.setTenantId(TENANT_ID);
    when(intents.findRetryRouteById(INTENT_ID))
        .thenReturn(Optional.of(new RetryRoute(OWNER_ID, GROUP_ID)));
    when(intents.lockById(INTENT_ID)).thenReturn(Optional.of(intent));
    when(consultants.findPictureOwnerForUpdate(OWNER_ID)).thenReturn(Optional.of(owner));
    when(groups.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
    when(group.getId()).thenReturn(GROUP_ID);
    when(group.getChatOwner()).thenReturn(owner);
    when(group.getMatrixRoomId()).thenReturn(MAIN_ROOM);
    when(group.getCurrentOccurrenceIndex()).thenReturn(occurrence);
  }

  private void givenCurrentRoster(
      GroupRosterRemovalIntent intent, GroupChatParticipant participant, int occurrence) {
    givenGroupAndIntent(intent, occurrence);
    if (intent.getSupervisionId() == null) {
      var member = new Consultant();
      member.setMatrixUserId(MEMBER_ID);
      when(consultants.findById(CONSULTANT_ID)).thenReturn(Optional.of(member));
    }
    when(participants.findBySeriesIdAndConsultantIdForUpdate(GROUP_ID, CONSULTANT_ID))
        .thenReturn(Optional.of(participant));
  }

  private void givenActiveSupervision(SessionSupervisor supervision) {
    when(group.isMembersOnly()).thenReturn(true);
    when(supervisions.findActiveForUpdate(SESSION_ID, CONSULTANT_ID))
        .thenReturn(Optional.of(supervision));
  }

  private void givenRetainedIntent(GroupRosterRemovalIntent intent) {
    when(intents.findBySeriesIdAndSupervisionIdIsNullAndDispatchSettledIsFalseAndConsultantIdIn(
            GROUP_ID, Set.of(CONSULTANT_ID)))
        .thenReturn(List.of(intent));
  }

  private static GroupChatParticipant originalParticipant() {
    return GroupChatParticipant.builder()
        .id(PARTICIPANT_ID)
        .seriesId(GROUP_ID)
        .chatId(SESSION_ID)
        .consultantId(CONSULTANT_ID)
        .role(ParticipantRole.CO_MODERATOR)
        .rosterVersion(ROSTER_VERSION)
        .build();
  }

  private static SessionSupervisor supervision(long id, String roomId) {
    var supervision = new SessionSupervisor();
    supervision.setId(id);
    supervision.setMatrixRoomId(roomId);
    return supervision;
  }

  private static GroupRosterRemovalIntent.GroupRosterRemovalIntentBuilder intentFor(String room) {
    return GroupRosterRemovalIntent.builder()
        .id(INTENT_ID)
        .seriesId(GROUP_ID)
        .ownerId(OWNER_ID)
        .tenantId(TENANT_ID)
        .seriesRoomId(MAIN_ROOM)
        .occurrenceIndex(OCCURRENCE)
        .participantId(PARTICIPANT_ID)
        .participantVersion(ROSTER_VERSION)
        .consultantId(CONSULTANT_ID)
        .roomId(room)
        .memberId(MEMBER_ID)
        .retryAt(NOW.minusSeconds(1));
  }

  private enum UnconfirmedAbsence {
    INVITED(Optional.of(false)),
    UNKNOWN(Optional.empty());

    private final Optional<Boolean> result;

    UnconfirmedAbsence(Optional<Boolean> result) {
      this.result = result;
    }
  }
}
