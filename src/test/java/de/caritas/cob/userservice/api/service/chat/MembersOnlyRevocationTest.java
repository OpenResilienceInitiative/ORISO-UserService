package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
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
import org.springframework.http.HttpStatus;

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
  void aSupervisorWhoLeavesLeavesTheSideRoomBeforeTheTransactionCommits() {
    var supervision = supervisionInSideRoom("!side:oriso", "@member:oriso");
    when(supervisions.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(9L, "member"))
        .thenReturn(Optional.of(supervision));

    revocation.revokeAccessOf(chat, participant("member", ParticipantRole.CO_MODERATOR));

    // Inside the transaction, no commit hook: a failure can still roll the removal back.
    verify(membership).removeMemberFromRoomOrFail("!side:oriso", "@member:oriso");
    assertThat(supervision.getIsActive()).isFalse();
  }

  @Test
  void aSideRoomThatCannotBeLeftFailsTheRemovalSoItRollsBack() {
    var supervision = supervisionInSideRoom("!side:oriso", "@member:oriso");
    when(supervisions.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(9L, "member"))
        .thenReturn(Optional.of(supervision));
    org.mockito.Mockito.doThrow(
            new CustomValidationHttpStatusException(
                HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED, HttpStatus.BAD_GATEWAY))
        .when(membership)
        .removeMemberFromRoomOrFail(any(), any());

    org.junit.jupiter.api.Assertions.assertThrows(
        CustomValidationHttpStatusException.class,
        () -> revocation.revokeAccessOf(chat, participant("member", ParticipantRole.CO_MODERATOR)));
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

  @Test
  void aCaseOfADeletedConsultantGoesToARemainingActiveParticipantOwnerFirst() {
    session.setId(9L);
    when(participants.findByChatId(9L)).thenReturn(participantsOfTheChat());

    var kept = revocation.keepCaseOnDeletionOf(session, creator);

    assertThat(kept).isTrue();
    assertThat(session.getConsultant()).isSameAs(owner);
    verify(sessions).save(session);
  }

  @Test
  void aCaseWithNoActiveParticipantLeftIsRetiredNotDeleted() {
    session.setId(9L);
    session.setStatus(Session.SessionStatus.IN_PROGRESS);
    when(participants.findByChatId(9L)).thenReturn(participantsOfTheChat());
    when(consultants.findByIdAndDeleteDateIsNull("owner")).thenReturn(Optional.empty());
    when(consultants.findByIdAndDeleteDateIsNull("member")).thenReturn(Optional.empty());

    var kept = revocation.keepCaseOnDeletionOf(session, creator);

    assertThat(kept).isTrue();
    assertThat(session.getStatus()).isEqualTo(Session.SessionStatus.DONE);
    verify(sessions).save(session);
  }

  @Test
  void aSessionWithoutParticipantsIsNotKept() {
    session.setId(9L);
    when(participants.findByChatId(9L)).thenReturn(List.of());

    assertThat(revocation.keepCaseOnDeletionOf(session, creator)).isFalse();
    verify(sessions, never()).save(session);
  }

  private List<GroupChatParticipant> participantsOfTheChat() {
    return List.of(
        participant("creator", ParticipantRole.CO_MODERATOR),
        participant("member", ParticipantRole.CO_MODERATOR),
        participant("owner", ParticipantRole.OWNER));
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
