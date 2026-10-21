package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The one rule for a members-only chat: current participation is decisive, nothing else. */
class MembersOnlyChatAccessTest {

  private static final String ROOM = "!members-only:oriso";

  private final ChatRepository chats = mock(ChatRepository.class);
  private final GroupChatParticipantRepository participants =
      mock(GroupChatParticipantRepository.class);
  private final MembersOnlyChatAccess guard = new MembersOnlyChatAccess(chats, participants);

  @Test
  void aFormerMemberIsExcludedEvenWhenTheSessionIsStillAssignedToThem() {
    var former = consultant("former");
    var session = membersOnlySession(7L);
    session.setConsultant(former);
    when(participants.existsByChatIdAndConsultantId(7L, "former")).thenReturn(false);

    assertThat(guard.excludes(session, former)).isTrue();
    assertThrows(ForbiddenException.class, () -> guard.requireAccess(session, former));
  }

  @Test
  void aFormerSupervisorWithALeftOverSupervisionRowIsExcluded() {
    var supervisor = consultant("former-supervisor");
    var session = membersOnlySession(7L);
    when(participants.existsByChatIdAndConsultantId(7L, "former-supervisor")).thenReturn(false);

    assertThrows(ForbiddenException.class, () -> guard.requireAccess(session, supervisor));
  }

  @Test
  void aNonMemberColleagueOfTheSameCentreIsExcluded() {
    var colleague = consultant("colleague");
    var session = membersOnlySession(7L);
    session.setAgencyId(100L);
    when(participants.existsByChatIdAndConsultantId(7L, "colleague")).thenReturn(false);

    assertThat(guard.excludes(session, colleague)).isTrue();
    assertThat(guard.excludes(session, "colleague")).isTrue();
  }

  @Test
  void aParticipantIsAdmittedToTheSessionButNeverAnOrdinarySessionOrANonMember() {
    var member = consultant("member");
    var session = membersOnlySession(7L);
    when(participants.existsByChatIdAndConsultantId(7L, "member")).thenReturn(true);
    when(participants.existsByChatIdAndConsultantId(7L, "other")).thenReturn(false);
    var ordinary = new Session();
    ordinary.setId(8L);
    ordinary.setMatrixRoomId("!ordinary:oriso");
    ordinary.setConversationType(ConversationType.SELF_HELP);
    when(participants.existsByChatIdAndConsultantId(8L, "member")).thenReturn(true);

    assertThat(guard.admits(session, member)).isTrue();
    assertThat(guard.admits(session, consultant("other"))).isFalse();
    assertThat(guard.admits(ordinary, member)).isFalse();
    assertThat(guard.admits(session, null)).isFalse();
  }

  @Test
  void aParticipantIsNeverExcludedAndTheChatIsNotEvenLookedUp() {
    var member = consultant("member");
    var session = membersOnlySession(7L);
    when(participants.existsByChatIdAndConsultantId(7L, "member")).thenReturn(true);

    assertThat(guard.excludes(session, member)).isFalse();
    assertDoesNotThrow(() -> guard.requireAccess(session, member));
    verifyNoInteractions(chats);
  }

  @Test
  void anAdviceSeekerWithoutParticipationIsExcludedFromTheChatItself() {
    var chat = membersOnlyChat(41L);
    when(participants.findBySeriesIdAndConsultantId(41L, "advice-seeker"))
        .thenReturn(Optional.empty());

    assertThat(guard.excludes(chat, consultant("advice-seeker"))).isTrue();
    assertThat(guard.isMember(chat, "advice-seeker")).isFalse();
  }

  @Test
  void aMemberIsNotExcludedFromTheChat() {
    var chat = membersOnlyChat(41L);
    when(participants.findBySeriesIdAndConsultantId(41L, "member"))
        .thenReturn(Optional.of(GroupChatParticipant.builder().consultantId("member").build()));

    assertThat(guard.excludes(chat, consultant("member"))).isFalse();
  }

  @Test
  void anOrdinaryChatAndAnOrdinarySessionAreNeverTouchedByTheRule() {
    var ordinary = new Chat();
    ordinary.setId(5L);
    var oneToOne = new Session();
    oneToOne.setId(9L);
    oneToOne.setMatrixRoomId("!one-to-one:oriso");
    oneToOne.setConversationType(ConversationType.AGENCY_COUNSELLING);
    var team = new Session();
    team.setId(10L);
    team.setMatrixRoomId("!team:oriso");
    when(chats.existsByMatrixRoomIdAndMembersOnlyTrue("!team:oriso")).thenReturn(false);
    when(participants.existsByChatIdAndConsultantId(any(), any())).thenReturn(false);

    assertThat(guard.concerns(ordinary)).isFalse();
    assertThat(guard.excludes(ordinary, consultant("anybody"))).isFalse();
    assertThat(guard.excludes(oneToOne, consultant("anybody"))).isFalse();
    assertThat(guard.excludes(team, consultant("anybody"))).isFalse();
    assertDoesNotThrow(() -> guard.requireOrdinary(team, "never"));
  }

  @Test
  void theCaseOfAChatThatIsGoneBelongsToNobodyNotEvenToItsAssignedConsultant() {
    var creator = consultant("creator");
    var session = new Session();
    session.setId(7L);
    session.setMatrixRoomId(ROOM);
    session.setConversationType(ConversationType.INTERNAL_GROUP);
    session.setTeamSession(false);
    session.setConsultant(creator);
    when(chats.existsByMatrixRoomIdAndMembersOnlyTrue(ROOM)).thenReturn(false);
    when(chats.existsByMatrixRoomId(ROOM)).thenReturn(false);
    when(participants.existsByChatIdAndConsultantId(7L, "creator")).thenReturn(false);

    assertThat(guard.concerns(session)).isTrue();
    assertThat(guard.excludes(session, creator)).isTrue();
    assertThrows(ForbiddenException.class, () -> guard.requireAccess(session, creator));
  }

  @Test
  void anInternalTeamSessionWhoseChatIsGoneIsNotAMembersOnlyCase() {
    var session = new Session();
    session.setId(7L);
    session.setMatrixRoomId(ROOM);
    session.setConversationType(ConversationType.INTERNAL_GROUP);
    session.setTeamSession(true);

    assertThat(guard.concerns(session)).isFalse();
  }

  @Test
  void aListFailsClosedForTheCasesOfChatsThatAreGone() {
    var gone = new Session();
    gone.setId(7L);
    gone.setMatrixRoomId("!gone:oriso");
    gone.setConversationType(ConversationType.INTERNAL_GROUP);
    var team = new Session();
    team.setId(8L);
    team.setMatrixRoomId("!team:oriso");
    team.setConversationType(ConversationType.INTERNAL_GROUP);
    team.setTeamSession(true);
    when(chats.findMembersOnlyMatrixRoomIds(any())).thenReturn(Set.of());
    when(chats.findExistingMatrixRoomIds(any())).thenReturn(Set.of());

    assertThat(guard.membersOnlySessionIds(List.of(gone, team))).containsExactly(7L);
  }

  @Test
  void aSessionWithoutARoomIsNotAMembersOnlyCase() {
    var session = new Session();
    session.setId(3L);

    assertThat(guard.concerns(session)).isFalse();
    verifyNoInteractions(chats);
  }

  @Test
  void theCaseOfAMembersOnlyChatIsNeverOrdinary() {
    var session = membersOnlySession(7L);

    assertThrows(ForbiddenException.class, () -> guard.requireOrdinary(session, "reason"));
  }

  @Test
  void aListIsFilteredWithOneLookupPerKindWhateverItsLength() {
    var inside = membersOnlySession(7L);
    var outside = membersOnlySession(8L);
    outside.setMatrixRoomId("!other:oriso");
    var ordinary = new Session();
    ordinary.setId(9L);
    ordinary.setMatrixRoomId("!ordinary:oriso");
    when(chats.findMembersOnlyMatrixRoomIds(any())).thenReturn(Set.of(ROOM, "!other:oriso"));
    when(participants.findByConsultantId("member"))
        .thenReturn(
            List.of(GroupChatParticipant.builder().chatId(7L).consultantId("member").build()));

    var excluded =
        guard.excludedSessionIds(List.of(inside, outside, ordinary), consultant("member"));

    assertThat(excluded).containsExactly(8L);
    assertThat(guard.membersOnlySessionIds(List.of(inside, outside, ordinary)))
        .containsExactlyInAnyOrder(7L, 8L);
  }

  private static Consultant consultant(String id) {
    var consultant = new Consultant();
    consultant.setId(id);
    return consultant;
  }

  private Session membersOnlySession(Long id) {
    var session = new Session();
    session.setId(id);
    session.setMatrixRoomId(ROOM);
    session.setConversationType(ConversationType.INTERNAL_GROUP);
    when(chats.existsByMatrixRoomIdAndMembersOnlyTrue(ROOM)).thenReturn(true);
    return session;
  }

  private static Chat membersOnlyChat(Long id) {
    var chat = new Chat();
    chat.setId(id);
    chat.setMembersOnly(true);
    return chat;
  }
}
