package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
  private final de.caritas.cob.userservice.api.port.out.ChatRepository chats =
      mock(de.caritas.cob.userservice.api.port.out.ChatRepository.class);
  private final de.caritas.cob.userservice.api.actions.chat.MatrixChatShutdownService shutdown =
      mock(de.caritas.cob.userservice.api.actions.chat.MatrixChatShutdownService.class);
  private final MembersOnlyRevocation revocation =
      new MembersOnlyRevocation(
          sessions, consultants, participants, supervisions, membership, chats, shutdown);

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
  void aDeletedConsultantLeavesEveryMembersOnlyChatSideRoomFirstMainRoomLast() {
    session.setId(9L);
    var deleted = consultant("member");
    deleted.setMatrixUserId("@member:oriso");
    var supervision = supervisionInSideRoom("!side:oriso", "@member:oriso");
    when(supervisions.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(9L, "member"))
        .thenReturn(Optional.of(supervision));
    var held = participant("member", ParticipantRole.CO_MODERATOR);
    held.setId(1L);
    var ordinarySeries = mock(Chat.class);
    when(ordinarySeries.getId()).thenReturn(6L);
    var inOrdinary = participant("member", ParticipantRole.PARTICIPANT);
    inOrdinary.setSeriesId(6L);
    inOrdinary.setId(2L);
    when(participants.findByConsultantId("member")).thenReturn(List.of(held, inOrdinary));
    when(chats.findById(5L)).thenReturn(Optional.of(chat));
    when(chats.findById(6L)).thenReturn(Optional.of(ordinarySeries));

    revocation.revokeAllAccessOf(deleted);

    var order = org.mockito.Mockito.inOrder(membership, participants);
    order.verify(membership).removeMemberFromRoomOrFail("!side:oriso", "@member:oriso");
    order.verify(participants).delete(held);
    order.verify(membership).removeParticipantFromRoom(chat, "@member:oriso");
    verify(participants, never()).delete(inOrdinary);
    verify(membership, never()).removeParticipantFromRoom(eq(ordinarySeries), any());
  }

  @Test
  void aSessionWithoutParticipantsIsNotKept() {
    session.setId(9L);
    when(participants.findByChatId(9L)).thenReturn(List.of());

    assertThat(revocation.keepCaseOnDeletionOf(session, creator)).isFalse();
    verify(sessions, never()).save(session);
  }

  @Test
  void aDeletedOwnerPassesTheChatToTheLongestStandingActiveParticipant() {
    var deleted = consultant("owner");
    deleted.setMatrixUserId("@owner:oriso");
    var held = stored(1L, "owner", ParticipantRole.OWNER);
    var older = stored(2L, "creator", ParticipantRole.PARTICIPANT);
    var newer = stored(3L, "member", ParticipantRole.PARTICIPANT);
    when(participants.findByConsultantId("owner")).thenReturn(List.of(held));
    when(participants.findBySeriesId(5L)).thenReturn(List.of(newer, held, older));
    when(chats.findById(5L)).thenReturn(Optional.of(chat));
    when(consultants.findByIdAndDeleteDateIsNull("creator")).thenReturn(Optional.of(creator));

    revocation.revokeAllAccessOf(deleted);

    assertThat(older.getRole()).isEqualTo(ParticipantRole.OWNER);
    assertThat(newer.getRole()).isEqualTo(ParticipantRole.PARTICIPANT);
    verify(participants).save(older);
    verify(chat).setChatOwner(creator);
    verify(chats).save(chat);
    verify(participants).delete(held);
    verify(membership).removeParticipantFromRoom(chat, "@owner:oriso");
    verify(chats, never()).delete(chat);
    verifyNoInteractions(shutdown);
  }

  @Test
  void aCoModeratorIsPromotedBeforeAnOlderPlainParticipant() {
    var deleted = consultant("owner");
    var held = stored(1L, "owner", ParticipantRole.OWNER);
    var plain = stored(2L, "creator", ParticipantRole.PARTICIPANT);
    var moderator = stored(3L, "member", ParticipantRole.CO_MODERATOR);
    when(participants.findByConsultantId("owner")).thenReturn(List.of(held));
    when(participants.findBySeriesId(5L)).thenReturn(List.of(held, plain, moderator));
    when(chats.findById(5L)).thenReturn(Optional.of(chat));
    when(consultants.findByIdAndDeleteDateIsNull("creator")).thenReturn(Optional.of(creator));
    when(consultants.findByIdAndDeleteDateIsNull("member")).thenReturn(Optional.of(member));

    revocation.revokeAllAccessOf(deleted);

    assertThat(moderator.getRole()).isEqualTo(ParticipantRole.OWNER);
    assertThat(plain.getRole()).isEqualTo(ParticipantRole.PARTICIPANT);
    verify(chat).setChatOwner(member);
  }

  @Test
  void aParticipantWhoIsNoLongerAnActiveConsultantIsNotMadeOwner() {
    var deleted = consultant("owner");
    var held = stored(1L, "owner", ParticipantRole.OWNER);
    var gone = stored(2L, "creator", ParticipantRole.CO_MODERATOR);
    var active = stored(3L, "member", ParticipantRole.PARTICIPANT);
    when(participants.findByConsultantId("owner")).thenReturn(List.of(held));
    when(participants.findBySeriesId(5L)).thenReturn(List.of(held, gone, active));
    when(chats.findById(5L)).thenReturn(Optional.of(chat));
    when(consultants.findByIdAndDeleteDateIsNull("creator")).thenReturn(Optional.empty());
    when(consultants.findByIdAndDeleteDateIsNull("member")).thenReturn(Optional.of(member));

    revocation.revokeAllAccessOf(deleted);

    assertThat(active.getRole()).isEqualTo(ParticipantRole.OWNER);
    assertThat(gone.getRole()).isEqualTo(ParticipantRole.CO_MODERATOR);
    verify(chat).setChatOwner(member);
  }

  @Test
  void aDeletedOwnerWhoIsTheLastMemberRetiresTheChatAndPurgesItsRoomStrictly() {
    var deleted = consultant("owner");
    deleted.setMatrixUserId("@owner:oriso");
    var held = stored(1L, "owner", ParticipantRole.OWNER);
    session.setId(9L);
    session.setStatus(Session.SessionStatus.IN_PROGRESS);
    when(participants.findByConsultantId("owner")).thenReturn(List.of(held));
    when(participants.findBySeriesId(5L)).thenReturn(List.of(held));
    when(chats.findById(5L)).thenReturn(Optional.of(chat));

    revocation.revokeAllAccessOf(deleted);

    assertThat(session.getStatus()).isEqualTo(Session.SessionStatus.DONE);
    var order = org.mockito.Mockito.inOrder(participants, chats, shutdown);
    order.verify(participants).deleteBySeriesId(5L);
    order.verify(chats).delete(chat);
    order.verify(shutdown).shutdownRoom(chat);
    verify(chat, never()).setChatOwner(any());
  }

  @Test
  void aDeletedOwnerWithOnlyInactiveParticipantsLeftRetiresTheChatToo() {
    var deleted = consultant("owner");
    var held = stored(1L, "owner", ParticipantRole.OWNER);
    var gone = stored(2L, "member", ParticipantRole.CO_MODERATOR);
    session.setId(9L);
    when(participants.findByConsultantId("owner")).thenReturn(List.of(held));
    when(participants.findBySeriesId(5L)).thenReturn(List.of(held, gone));
    when(chats.findById(5L)).thenReturn(Optional.of(chat));
    when(consultants.findByIdAndDeleteDateIsNull("member")).thenReturn(Optional.empty());

    revocation.revokeAllAccessOf(deleted);

    verify(participants).deleteBySeriesId(5L);
    verify(chats).delete(chat);
    verify(shutdown).shutdownRoom(chat);
  }

  @Test
  void aRoomThatCannotBePurgedFailsTheDeletionOfTheLastOwnerSoItRollsBack() {
    var deleted = consultant("owner");
    var held = stored(1L, "owner", ParticipantRole.OWNER);
    when(participants.findByConsultantId("owner")).thenReturn(List.of(held));
    when(participants.findBySeriesId(5L)).thenReturn(List.of(held));
    when(chats.findById(5L)).thenReturn(Optional.of(chat));
    org.mockito.Mockito.doThrow(
            new CustomValidationHttpStatusException(
                HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED, HttpStatus.BAD_GATEWAY))
        .when(shutdown)
        .shutdownRoom(chat);

    org.junit.jupiter.api.Assertions.assertThrows(
        CustomValidationHttpStatusException.class, () -> revocation.revokeAllAccessOf(deleted));
  }

  @Test
  void aDeletedNonOwnerDoesNotTouchTheOwnershipOfTheChat() {
    var deleted = consultant("member");
    var held = stored(1L, "member", ParticipantRole.CO_MODERATOR);
    when(participants.findByConsultantId("member")).thenReturn(List.of(held));
    when(chats.findById(5L)).thenReturn(Optional.of(chat));

    revocation.revokeAllAccessOf(deleted);

    verify(chat, never()).setChatOwner(any());
    verify(chats, never()).save(any());
    verify(chats, never()).delete(any());
    verifyNoInteractions(shutdown);
  }

  @Test
  void thePrimaryOwnerWhoLeavesPassesTheChatToTheRemainingOwnerWithTheLowestId() {
    var leaving = stored(1L, "owner", ParticipantRole.OWNER);
    var laterOwner = stored(5L, "member", ParticipantRole.OWNER);
    var earlierOwner = stored(3L, "creator", ParticipantRole.OWNER);
    var coModerator = stored(2L, "other", ParticipantRole.CO_MODERATOR);
    var primary = consultant("owner");
    when(chat.getChatOwner()).thenReturn(primary);
    when(participants.findBySeriesId(5L))
        .thenReturn(List.of(leaving, laterOwner, coModerator, earlierOwner));
    when(consultants.findByIdAndDeleteDateIsNull("creator")).thenReturn(Optional.of(creator));
    when(consultants.findByIdAndDeleteDateIsNull("member")).thenReturn(Optional.of(member));

    var someoneRemains = revocation.passOwnershipOn(chat, "owner");

    assertThat(someoneRemains).isTrue();
    verify(chat).setChatOwner(creator);
    verify(chats).save(chat);
    // Another owner remains, so nobody is promoted.
    assertThat(coModerator.getRole()).isEqualTo(ParticipantRole.CO_MODERATOR);
    verify(participants, never()).save(any());
  }

  @Test
  void aLeavingOwnerWhoIsNotThePrimaryOwnerLeavesTheChatOwnerAlone() {
    var leaving = stored(1L, "owner", ParticipantRole.OWNER);
    var other = stored(2L, "member", ParticipantRole.OWNER);
    when(chat.getChatOwner()).thenReturn(consultant("member"));
    when(participants.findBySeriesId(5L)).thenReturn(List.of(leaving, other));
    when(consultants.findByIdAndDeleteDateIsNull("member")).thenReturn(Optional.of(member));

    assertThat(revocation.passOwnershipOn(chat, "owner")).isTrue();

    verify(chat, never()).setChatOwner(any());
    verify(chats, never()).save(any());
  }

  private static GroupChatParticipant stored(Long id, String consultantId, ParticipantRole role) {
    var participant = participant(consultantId, role);
    participant.setId(id);
    return participant;
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
