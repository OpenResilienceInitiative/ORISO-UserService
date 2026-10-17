package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MembersOnlySessionAssignmentTest {

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final GroupChatParticipantRepository participants =
      mock(GroupChatParticipantRepository.class);
  private final MembersOnlySessionAssignment assignment =
      new MembersOnlySessionAssignment(sessions, consultants, participants);

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
    assignment.handOverFrom(chat, participant("creator", ParticipantRole.CO_MODERATOR));

    assertThat(session.getConsultant()).isSameAs(owner);
    verify(sessions).save(session);
  }

  @Test
  void aSessionAssignedToSomeoneElseStaysAsItIs() {
    assignment.handOverFrom(chat, participant("member", ParticipantRole.CO_MODERATOR));

    assertThat(session.getConsultant()).isSameAs(creator);
    verify(sessions, never()).save(session);
  }

  @Test
  void anOrdinaryChatIsLeftAlone() {
    when(chat.isMembersOnly()).thenReturn(false);

    assignment.handOverFrom(chat, participant("creator", ParticipantRole.CO_MODERATOR));

    assertThat(session.getConsultant()).isSameAs(creator);
    verify(sessions, never()).save(session);
  }

  @Test
  void aSessionStaysWhenNoOtherMemberIsAnActiveConsultant() {
    when(consultants.findByIdAndDeleteDateIsNull("owner")).thenReturn(Optional.empty());
    when(consultants.findByIdAndDeleteDateIsNull("member")).thenReturn(Optional.empty());

    assignment.handOverFrom(chat, participant("creator", ParticipantRole.CO_MODERATOR));

    assertThat(session.getConsultant()).isSameAs(creator);
    verify(sessions, never()).save(session);
  }

  @Test
  void aMissingSessionIsLeftAlone() {
    when(sessions.findById(9L)).thenReturn(Optional.empty());

    assignment.handOverFrom(chat, participant("creator", ParticipantRole.CO_MODERATOR));

    verify(sessions, never()).save(session);
  }

  @Test
  void aParticipationWithoutASessionIdIsLeftAlone() {
    var withoutSession = participant("creator", ParticipantRole.CO_MODERATOR);
    withoutSession.setChatId(null);

    assignment.handOverFrom(chat, withoutSession);

    assertThat(session.getConsultant()).isSameAs(creator);
    verify(sessions, never()).save(session);
  }

  @Test
  void aRemainingMemberTakesOverWhenTheOwnerIsNoLongerActive() {
    when(consultants.findByIdAndDeleteDateIsNull("owner")).thenReturn(Optional.empty());

    assignment.handOverFrom(chat, participant("creator", ParticipantRole.CO_MODERATOR));

    assertThat(session.getConsultant()).isSameAs(member);
    verify(sessions).save(session);
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
