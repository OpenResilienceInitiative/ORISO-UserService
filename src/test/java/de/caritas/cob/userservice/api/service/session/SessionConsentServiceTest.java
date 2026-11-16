package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-022 decision 2 — the session consent pointer. These tests pin the two properties the ADR
 * argues about: the pointer is <b>overwritten</b> (never appended to), and only the help-seeker who
 * owns the room can move it.
 */
@ExtendWith(MockitoExtension.class)
class SessionConsentServiceTest {

  @Mock private SessionRepository sessionRepository;
  @Mock private de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository members;

  private SessionConsentService sessionConsentService;

  @BeforeEach
  void setUp() {
    sessionConsentService = new SessionConsentService(sessionRepository, members);
  }

  @Test
  void recordConsentStoresThePointerForTheOwningAdviceSeeker() {
    var session = sessionOf(42L, "asker-1", null);
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));

    sessionConsentService.recordConsent(42L, askerWithId("asker-1"), 7L);

    var saved = ArgumentCaptor.forClass(Session.class);
    verify(sessionRepository).save(saved.capture());
    assertThat(saved.getValue().getConsentedLegalVersionId()).isEqualTo(7L);
  }

  @Test
  void recordConsentOverwritesAnEarlierPointerInsteadOfKeepingHistory() {
    // ADR-022 decision 2 rejects a consent event log: re-consent replaces the value in place.
    var session = sessionOf(42L, "asker-1", 6L);
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));

    sessionConsentService.recordConsent(42L, askerWithId("asker-1"), 7L);

    var saved = ArgumentCaptor.forClass(Session.class);
    verify(sessionRepository).save(saved.capture());
    assertThat(saved.getValue().getConsentedLegalVersionId()).isEqualTo(7L);
  }

  @Test
  void recordConsentRejectsAForeignSession() {
    var session = sessionOf(42L, "asker-1", null);
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
    var stranger = askerWithId("asker-2");

    assertThatThrownBy(() -> sessionConsentService.recordConsent(42L, stranger, 7L))
        .isInstanceOf(ForbiddenException.class);
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void recordConsentRejectsAnUnknownSession() {
    when(sessionRepository.findById(42L)).thenReturn(Optional.empty());
    var asker = askerWithId("asker-1");

    assertThatThrownBy(() -> sessionConsentService.recordConsent(42L, asker, 7L))
        .isInstanceOf(NotFoundException.class);
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void recordConsentRejectsAMissingLegalVersion() {
    // The pointer must always point at something: a null would be indistinguishable from
    // "never consented" and would silently reopen the gate.
    var asker = askerWithId("asker-1");
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(sessionOf(42L, "asker-1", 6L)));

    assertThatThrownBy(() -> sessionConsentService.recordConsent(42L, asker, null))
        .isInstanceOf(BadRequestException.class);
    verify(sessionRepository, never()).save(any());
    verify(members, never()).save(any());
  }

  @Test
  void recordConsentRejectsAGroupChatRoomThatHasNoGate() {
    /* SELF_HELP and INTERNAL_GROUP sessions are owned by a tenant system user
    (CreateChatFacade#createMatrixGroupChat) — one pointer there cannot express
    per-participant consent, so the pointer must refuse the room outright rather
    than clear the gate for everybody. */
    var groupChat = sessionOf(42L, "system-user", null);
    groupChat.setConversationType(ConversationType.INTERNAL_GROUP);
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(groupChat));
    var participant = askerWithId("system-user");

    assertThatThrownBy(() -> sessionConsentService.recordConsent(42L, participant, 7L))
        .isInstanceOf(ConflictException.class);
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void recordConsentRejectsAnInternalRoomThatHasNoGate() {
    var internalRoom = sessionOf(42L, "system-user", null);
    internalRoom.setConversationType(ConversationType.INTERNAL_GROUP);
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(internalRoom));
    var participant = askerWithId("system-user");

    assertThatThrownBy(() -> sessionConsentService.recordConsent(42L, participant, 7L))
        .isInstanceOf(ConflictException.class);
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void recordConsentAcceptsALiveChatRoom() {
    var liveChat = sessionOf(42L, "asker-1", null);
    liveChat.setConversationType(ConversationType.LIVE_CHAT);
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(liveChat));

    sessionConsentService.recordConsent(42L, askerWithId("asker-1"), 7L);

    var saved = ArgumentCaptor.forClass(Session.class);
    verify(sessionRepository).save(saved.capture());
    assertThat(saved.getValue().getConsentedLegalVersionId()).isEqualTo(7L);
  }

  // --- ORISO-Frontend#2252, decision B: each person of a shared appointment conversation ---

  @Test
  void recordConsent_Should_StoreAJoinersConsentOnTheirOwnMemberRow() {
    var shared = sharedConversation(42L, "asker-1", 6L);
    var row = memberRow(42L, "joiner-1");
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(shared));
    when(members.findBySessionIdAndUserIdForUpdate(42L, "joiner-1")).thenReturn(Optional.of(row));

    sessionConsentService.recordConsent(42L, askerWithId("joiner-1"), 7L);

    assertThat(row.getConsentedLegalVersionId()).isEqualTo(7L);
    verify(members).save(row);
    assertThat(shared.getConsentedLegalVersionId()).as("the owner's pointer").isEqualTo(6L);
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void recordConsent_Should_Refuse_SomebodyWhoIsNoMemberOfTheSharedConversation() {
    when(sessionRepository.findById(42L))
        .thenReturn(Optional.of(sharedConversation(42L, "asker-1", null)));
    when(members.findBySessionIdAndUserIdForUpdate(42L, "stranger")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> sessionConsentService.recordConsent(42L, askerWithId("stranger"), 7L))
        .isInstanceOf(ForbiddenException.class);
    verify(members, never()).save(any());
  }

  @Test
  void recordConsent_Should_Refuse_AMemberWhoWasTakenOutAlready() {
    var row = memberRow(42L, "joiner-1");
    row.setRemovedAt(java.time.LocalDateTime.of(2030, 1, 1, 10, 0));
    when(sessionRepository.findById(42L))
        .thenReturn(Optional.of(sharedConversation(42L, "asker-1", null)));
    when(members.findBySessionIdAndUserIdForUpdate(42L, "joiner-1")).thenReturn(Optional.of(row));

    assertThatThrownBy(() -> sessionConsentService.recordConsent(42L, askerWithId("joiner-1"), 7L))
        .isInstanceOf(ForbiddenException.class);
    verify(members, never()).save(any());
  }

  @Test
  void recordConsent_Should_StillRefuseAStranger_InAnOrdinary1to1() {
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(sessionOf(42L, "asker-1", null)));
    when(members.findBySessionIdAndUserIdForUpdate(42L, "stranger")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> sessionConsentService.recordConsent(42L, askerWithId("stranger"), null))
        .isInstanceOf(ForbiddenException.class);
    verify(members, never()).save(any());
  }

  // --- an invitation into an EXISTING 1:1 (createForSession): the session stays unmarked ---

  @Test
  void recordConsent_Should_StoreTheVersion_ForAnInviteeOfAnExisting1to1() {
    var oneToOne = sessionOf(42L, "asker-1", 6L);
    var row = memberRow(42L, "joiner-1");
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(oneToOne));
    when(members.findBySessionIdAndUserIdForUpdate(42L, "joiner-1")).thenReturn(Optional.of(row));

    sessionConsentService.recordConsent(42L, askerWithId("joiner-1"), 7L);

    assertThat(row.getConsentedLegalVersionId()).isEqualTo(7L);
    assertThat(row.isConsentGiven()).isTrue();
    verify(members).save(row);
    assertThat(oneToOne.isDirectInvitationConversation()).as("stays an ordinary 1:1").isFalse();
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void consentRequired_Should_Clear_ForAnInviteeOfAnExisting1to1_WithAVersion() {
    var row = memberRow(42L, "joiner-1");
    row.setConsentedLegalVersionId(7L);
    row.setConsentGiven(true);
    when(members.findBySessionIdAndUserId(42L, "joiner-1")).thenReturn(Optional.of(row));

    assertThat(sessionConsentService.consentRequired(sessionOf(42L, "asker-1", null), "joiner-1"))
        .isFalse();
  }

  @Test
  void consentRequired_Should_StayOpen_ForAnInviteeOfAnExisting1to1_WhoHasNotAgreed() {
    when(members.findBySessionIdAndUserId(42L, "joiner-1"))
        .thenReturn(Optional.of(memberRow(42L, "joiner-1")));

    assertThat(sessionConsentService.consentRequired(sessionOf(42L, "asker-1", 6L), "joiner-1"))
        .isTrue();
  }

  // --- the advice centre has no consent text of its own: "agreed, no version" (Frank) ---

  @Test
  void recordConsent_Should_AcceptAMemberWithoutAVersion_AndClearTheirGate() {
    var oneToOne = sessionOf(42L, "asker-1", null);
    var row = memberRow(42L, "joiner-1");
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(oneToOne));
    when(members.findBySessionIdAndUserIdForUpdate(42L, "joiner-1")).thenReturn(Optional.of(row));
    when(members.findBySessionIdAndUserId(42L, "joiner-1")).thenReturn(Optional.of(row));

    sessionConsentService.recordConsent(42L, askerWithId("joiner-1"), null);

    assertThat(row.isConsentGiven()).isTrue();
    assertThat(row.getConsentedLegalVersionId()).isNull();
    verify(members).save(row);
    assertThat(sessionConsentService.consentRequired(oneToOne, "joiner-1")).isFalse();
  }

  @Test
  void recordConsent_Should_AcceptASharedConversationMemberWithoutAVersion() {
    var row = memberRow(42L, "joiner-1");
    row.setConsentedLegalVersionId(5L);
    when(sessionRepository.findById(42L))
        .thenReturn(Optional.of(sharedConversation(42L, "asker-1", null)));
    when(members.findBySessionIdAndUserIdForUpdate(42L, "joiner-1")).thenReturn(Optional.of(row));

    sessionConsentService.recordConsent(42L, askerWithId("joiner-1"), null);

    assertThat(row.isConsentGiven()).isTrue();
    assertThat(row.getConsentedLegalVersionId()).as("overwritten, never kept").isNull();
  }

  @Test
  void consentRequired_Should_Clear_ForAMemberMarkedAsAgreedWithoutAVersion() {
    var row = memberRow(42L, "joiner-1");
    row.setConsentGiven(true);
    when(members.findBySessionIdAndUserId(42L, "joiner-1")).thenReturn(Optional.of(row));

    assertThat(
            sessionConsentService.consentRequired(
                sharedConversation(42L, "asker-1", null), "joiner-1"))
        .isFalse();
  }

  @Test
  void consentRequired_Should_StayOpen_ForAMemberWhoWasTakenOut() {
    var row = memberRow(42L, "joiner-1");
    row.setConsentGiven(true);
    row.setRemovedAt(java.time.LocalDateTime.of(2030, 1, 1, 10, 0));
    when(members.findBySessionIdAndUserId(42L, "joiner-1")).thenReturn(Optional.of(row));

    assertThat(
            sessionConsentService.consentRequired(
                sharedConversation(42L, "asker-1", null), "joiner-1"))
        .isTrue();
  }

  @Test
  void consentRequired_Should_StayOpenForAJoiner_AfterTheOwnerAgreed() {
    var shared = sharedConversation(42L, "asker-1", 6L);
    when(members.findBySessionIdAndUserId(42L, "joiner-1"))
        .thenReturn(Optional.of(memberRow(42L, "joiner-1")));

    assertThat(sessionConsentService.consentRequired(shared, "joiner-1")).isTrue();
  }

  @Test
  void consentRequired_Should_Close_OnceTheJoinerAgreedThemselves() {
    var shared = sharedConversation(42L, "asker-1", null);
    var row = memberRow(42L, "joiner-1");
    row.setConsentedLegalVersionId(7L);
    when(members.findBySessionIdAndUserId(42L, "joiner-1")).thenReturn(Optional.of(row));

    assertThat(sessionConsentService.consentRequired(shared, "joiner-1")).isFalse();
  }

  @Test
  void consentRequired_Should_FollowTheSessionPointer_ForTheOwner() {
    assertThat(
            sessionConsentService.consentRequired(
                sharedConversation(42L, "asker-1", null), "asker-1"))
        .isTrue();
    assertThat(
            sessionConsentService.consentRequired(
                sharedConversation(42L, "asker-1", 6L), "asker-1"))
        .isFalse();
    verify(members, never()).findBySessionIdAndUserId(any(), any());
  }

  @Test
  void consentRequired_Should_BeFalse_WhereTheRoomHasNoGate() {
    var groupChat = sessionOf(42L, "system-user", null);
    groupChat.setConversationType(ConversationType.INTERNAL_GROUP);

    assertThat(sessionConsentService.consentRequired(groupChat, "anybody")).isFalse();
  }

  @Test
  void consentRequired_Should_LoadTheSession_WhenOnlyItsIdIsKnown() {
    when(sessionRepository.findById(42L))
        .thenReturn(Optional.of(sharedConversation(42L, "asker-1", 6L)));
    when(members.findBySessionIdAndUserId(42L, "joiner-1"))
        .thenReturn(Optional.of(memberRow(42L, "joiner-1")));

    assertThat(sessionConsentService.consentRequired(42L, "joiner-1")).isTrue();
  }

  private Session sharedConversation(Long id, String ownerId, Long ownerConsent) {
    var session = sessionOf(id, ownerId, ownerConsent);
    session.setDirectInvitationConversation(true);
    return session;
  }

  private static de.caritas.cob.userservice.api.model.DirectInvitationMember memberRow(
      Long sessionId, String userId) {
    return de.caritas.cob.userservice.api.model.DirectInvitationMember.builder()
        .id(3L)
        .sessionId(sessionId)
        .userId(userId)
        .inviteLinkId(9L)
        .joinedAt(java.time.LocalDateTime.of(2030, 1, 1, 9, 0))
        .build();
  }

  private Session sessionOf(Long id, String askerId, Long consentedLegalVersionId) {
    var session = new Session();
    session.setId(id);
    session.setUser(askerWithId(askerId));
    session.setConversationType(ConversationType.AGENCY_COUNSELLING);
    session.setConsentedLegalVersionId(consentedLegalVersionId);
    return session;
  }

  private User askerWithId(String userId) {
    var user = new User();
    user.setUserId(userId);
    return user;
  }
}
