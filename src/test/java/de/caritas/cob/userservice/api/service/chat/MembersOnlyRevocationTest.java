package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class MembersOnlyRevocationTest {

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final GroupChatParticipantRepository participants =
      mock(GroupChatParticipantRepository.class);
  private final GroupChatMembershipService membership = mock(GroupChatMembershipService.class);
  private final SessionSupervisorRepository supervisions = mock(SessionSupervisorRepository.class);
  private final MembersOnlyRevocation revocation =
      new MembersOnlyRevocation(sessions, consultants, participants, supervisions, membership);

  private final Consultant creator = consultant("creator");
  private final Consultant owner = consultant("owner");
  private final Consultant member = consultant("member");
  private final Session session = new Session();
  private Chat chat;

  @BeforeEach
  void aMembersOnlyChatCreatedByTheCreator() {
    chat = mock(Chat.class);
    when(chat.getId()).thenReturn(5L);
    when(chat.isMembersOnly()).thenReturn(true);
    session.setConsultant(creator);
    when(sessions.findById(9L)).thenReturn(Optional.of(session));
    when(participants.findBySeriesId(5L))
        .thenReturn(
            List.of(
                participant("creator", ParticipantRole.CO_MODERATOR),
                participant("member", ParticipantRole.CO_MODERATOR),
                participant("owner", ParticipantRole.OWNER)));
    when(consultants.findByIdAndDeleteDateIsNull("owner")).thenReturn(Optional.of(owner));
    when(consultants.findByIdAndDeleteDateIsNull("member")).thenReturn(Optional.of(member));
  }

  @Test
  void theSessionOfALeavingCreatorGoesToARemainingOwner() {
    revocation.revokeAccessOf(chat, participant("creator", ParticipantRole.CO_MODERATOR));

    assertThat(session.getConsultant()).isSameAs(owner);
    verify(sessions).save(session);
  }

  @Test
  void aSessionAssignedToSomeoneElseStaysAsItIs() {
    revocation.revokeAccessOf(chat, participant("member", ParticipantRole.CO_MODERATOR));

    assertThat(session.getConsultant()).isSameAs(creator);
    verify(sessions, never()).save(session);
  }

  @Test
  void anOrdinaryChatIsLeftAlone() {
    when(chat.isMembersOnly()).thenReturn(false);

    revocation.revokeAccessOf(chat, participant("creator", ParticipantRole.CO_MODERATOR));

    assertThat(session.getConsultant()).isSameAs(creator);
    verify(sessions, never()).save(session);
  }

  @Test
  void aSessionStaysWhenNoOtherMemberIsAnActiveConsultant() {
    when(consultants.findByIdAndDeleteDateIsNull("owner")).thenReturn(Optional.empty());
    when(consultants.findByIdAndDeleteDateIsNull("member")).thenReturn(Optional.empty());

    revocation.revokeAccessOf(chat, participant("creator", ParticipantRole.CO_MODERATOR));

    assertThat(session.getConsultant()).isSameAs(creator);
    verify(sessions, never()).save(session);
  }

  @Test
  void aMissingSessionIsLeftAlone() {
    when(sessions.findById(9L)).thenReturn(Optional.empty());

    revocation.revokeAccessOf(chat, participant("creator", ParticipantRole.CO_MODERATOR));

    verify(sessions, never()).save(session);
  }

  @Test
  void aParticipationWithoutASessionIdIsLeftAlone() {
    var withoutSession = participant("creator", ParticipantRole.CO_MODERATOR);
    withoutSession.setChatId(null);

    revocation.revokeAccessOf(chat, withoutSession);

    assertThat(session.getConsultant()).isSameAs(creator);
    verify(sessions, never()).save(session);
  }

  @Test
  void aRemainingMemberTakesOverWhenTheOwnerIsNoLongerActive() {
    when(consultants.findByIdAndDeleteDateIsNull("owner")).thenReturn(Optional.empty());

    revocation.revokeAccessOf(chat, participant("creator", ParticipantRole.CO_MODERATOR));

    assertThat(session.getConsultant()).isSameAs(member);
    verify(sessions).save(session);
  }

  @Test
  void aMemberWhoLeavesNoLongerSupervisesTheSession() {
    var supervision = SessionSupervisor.builder().session(session).isActive(true).build();
    when(supervisions.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(9L, "member"))
        .thenReturn(Optional.of(supervision));

    revocation.revokeAccessOf(chat, participant("member", ParticipantRole.CO_MODERATOR));

    assertThat(supervision.getIsActive()).isFalse();
    assertThat(supervision.getRemovedDate()).isNotNull();
    verify(supervisions).save(supervision);
    verify(sessions, never()).save(session);
  }

  @Test
  void supervisionOfAnOrdinaryChatIsLeftAlone() {
    when(chat.isMembersOnly()).thenReturn(false);

    revocation.revokeAccessOf(chat, participant("member", ParticipantRole.CO_MODERATOR));

    verify(supervisions, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void aSupervisorWhoLeavesLeavesTheSideRoomOnlyAfterTheTransactionHasCommitted() {
    var supervision = supervisionInSideRoom("!side:oriso", "@member:oriso");
    when(supervisions.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(9L, "member"))
        .thenReturn(Optional.of(supervision));

    TransactionSynchronizationManager.initSynchronization();
    try {
      revocation.revokeAccessOf(chat, participant("member", ParticipantRole.CO_MODERATOR));

      verify(membership, never()).removeMemberFromRoom(any(), any());
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(membership).removeMemberFromRoom("!side:oriso", "@member:oriso");
  }

  @Test
  void aRolledBackRemovalNeverTouchesTheSideRoom() {
    var supervision = supervisionInSideRoom("!side:oriso", "@member:oriso");
    when(supervisions.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(9L, "member"))
        .thenReturn(Optional.of(supervision));

    TransactionSynchronizationManager.initSynchronization();
    try {
      revocation.revokeAccessOf(chat, participant("member", ParticipantRole.CO_MODERATOR));
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(membership, never()).removeMemberFromRoom(any(), any());
  }

  @Test
  void aSideRoomThatCannotBeLeftDoesNotUndoTheRemoval() {
    var supervision = supervisionInSideRoom("!side:oriso", "@member:oriso");
    when(supervisions.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(9L, "member"))
        .thenReturn(Optional.of(supervision));
    org.mockito.Mockito.doThrow(new IllegalStateException("synapse down"))
        .when(membership)
        .removeMemberFromRoom(any(), any());

    revocation.revokeAccessOf(chat, participant("member", ParticipantRole.CO_MODERATOR));

    assertThat(supervision.getIsActive()).isFalse();
    verify(supervisions).save(supervision);
  }

  @Test
  void aSupervisionWithoutASideRoomEndsWithoutAnyMatrixCall() {
    var supervision = supervisionInSideRoom(null, "@member:oriso");
    when(supervisions.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(9L, "member"))
        .thenReturn(Optional.of(supervision));

    revocation.revokeAccessOf(chat, participant("member", ParticipantRole.CO_MODERATOR));

    assertThat(supervision.getIsActive()).isFalse();
    verifyNoInteractions(membership);
  }

  @Test
  void aDeletedChatClosesItsSessionAndEndsEverySupervisionOfIt() {
    var supervision = supervisionInSideRoom("!side:oriso", "@member:oriso");
    when(supervisions.findBySessionIdAndIsActiveTrue(9L)).thenReturn(List.of(supervision));
    session.setId(9L);
    session.setStatus(Session.SessionStatus.IN_PROGRESS);
    when(chat.getMatrixRoomId()).thenReturn("!room:oriso");
    when(sessions.findByMatrixRoomId("!room:oriso")).thenReturn(Optional.of(session));

    revocation.retireCaseOf(chat);

    assertThat(session.getStatus()).isEqualTo(Session.SessionStatus.DONE);
    verify(sessions).save(session);
    assertThat(supervision.getIsActive()).isFalse();
    verify(supervisions).save(supervision);
  }

  @Test
  void aDeletedChatWithoutParticipationsStillFindsItsSessionByRoom() {
    when(participants.findBySeriesId(5L)).thenReturn(List.of());
    session.setId(9L);
    session.setStatus(Session.SessionStatus.IN_PROGRESS);
    when(chat.getMatrixRoomId()).thenReturn("!room:oriso");
    when(sessions.findByMatrixRoomId("!room:oriso")).thenReturn(Optional.of(session));

    revocation.retireCaseOf(chat);

    assertThat(session.getStatus()).isEqualTo(Session.SessionStatus.DONE);
  }

  @Test
  void aDeletedOrdinaryChatLeavesItsSessionAlone() {
    when(chat.isMembersOnly()).thenReturn(false);
    session.setStatus(Session.SessionStatus.IN_PROGRESS);

    revocation.retireCaseOf(chat);

    assertThat(session.getStatus()).isEqualTo(Session.SessionStatus.IN_PROGRESS);
    verify(sessions, never()).save(session);
  }

  private SessionSupervisor supervisionInSideRoom(String sideRoomId, String matrixUserId) {
    var supervisor = consultant("member");
    supervisor.setMatrixUserId(matrixUserId);
    return SessionSupervisor.builder()
        .session(session)
        .supervisorConsultant(supervisor)
        .matrixRoomId(sideRoomId)
        .isActive(true)
        .build();
  }

  private static Consultant consultant(String id) {
    var consultant = new Consultant();
    consultant.setId(id);
    return consultant;
  }

  private static GroupChatParticipant participant(String consultantId, ParticipantRole role) {
    var participant = new GroupChatParticipant();
    participant.setChatId(9L);
    participant.setSeriesId(5L);
    participant.setConsultantId(consultantId);
    participant.setRole(role);
    return participant;
  }
}
