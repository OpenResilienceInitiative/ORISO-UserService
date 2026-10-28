package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Via;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectCallKnockServiceTest {

  private static final long SESSION_ID = 500L;

  @Mock private GroupChatJoinRequestRepository repository;
  @Mock private SessionRepository sessionRepository;

  private DirectCallKnockService service;
  private Consultant counsellor;
  private User asker;

  @BeforeEach
  void setUp() {
    service = new DirectCallKnockService(repository, sessionRepository);
    counsellor = consultant("counsellor-1");
    asker = new User();
    asker.setUserId("asker-1");
    var session = new Session();
    session.setId(SESSION_ID);
    session.setConsultant(counsellor);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(sessionRepository.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(session));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void knock_Should_CreatePendingDirectLinkKnock() {
    var knock = service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

    assertThat(knock.getVia()).isEqualTo(Via.DIRECT_LINK);
    assertThat(knock.getStatus()).isEqualTo(Status.PENDING);
    assertThat(knock.getSessionId()).isEqualTo(SESSION_ID);
    assertThat(knock.getUserId()).isEqualTo("asker-1");
    assertThat(knock.getInviteLinkId()).isEqualTo(9L);
    assertThat(knock.getMedium()).isEqualTo("VIDEO");
    // Never part of the Matrix admission path of the self-help groups.
    assertThat(knock.getSeriesId()).isNull();
    assertThat(knock.getAdmissionRequestedAt()).isNull();
  }

  @Test
  void knock_Should_LockTheConversationBeforeLookingForAnOpenKnock() {
    service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

    var order = inOrder(sessionRepository, repository);
    order.verify(sessionRepository).findByIdForUpdate(SESSION_ID);
    order
        .verify(repository)
        .findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            SESSION_ID, "asker-1", Via.DIRECT_LINK, Status.PENDING);
  }

  @Test
  void knock_Should_ReturnOpenKnock_When_PersonKnocksAgain() {
    var open = pending(1L, LocalDateTime.now().minusMinutes(5));
    when(repository.findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            SESSION_ID, "asker-1", Via.DIRECT_LINK, Status.PENDING))
        .thenReturn(Optional.of(open));

    assertThat(service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.AUDIO)).isSameAs(open);
    verify(repository, never()).save(any());
  }

  @Test
  void knock_Should_StartAfresh_When_TheOpenKnockIsStale() {
    when(repository.findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            SESSION_ID, "asker-1", Via.DIRECT_LINK, Status.PENDING))
        .thenReturn(Optional.of(pending(1L, LocalDateTime.now().minusHours(3))));

    var knock = service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.AUDIO);

    assertThat(knock.getId()).isNull();
    verify(repository).save(any());
  }

  @Test
  void knock_Should_ReturnTheAdmission_When_ThePersonWasLetInBefore() {
    var admitted = pending(1L, LocalDateTime.now().minusDays(3));
    admitted.setStatus(Status.ADMITTED);
    when(repository.findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            SESSION_ID, "asker-1", Via.DIRECT_LINK, Status.ADMITTED))
        .thenReturn(Optional.of(admitted));

    // Let in once holds for the conversation, also long after the waiting window.
    assertThat(service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO)).isSameAs(admitted);
    verify(repository, never()).save(any());
  }

  @Test
  void knock_Should_OpenANewKnock_When_TheLastOneWasDeclined() {
    when(repository.findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            any(), any(), any(), any()))
        .thenReturn(Optional.empty());

    var knock = service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

    assertThat(knock.getStatus()).isEqualTo(Status.PENDING);
    verify(repository).save(any());
  }

  @Test
  void ownFor_Should_PreferTheAdmission_ThenTheLatestKnock() {
    var admitted = pending(1L, LocalDateTime.now());
    admitted.setStatus(Status.ADMITTED);
    var latest = pending(2L, LocalDateTime.now());
    when(repository.findFirstBySessionIdAndUserIdAndViaOrderByIdDesc(
            SESSION_ID, "asker-1", Via.DIRECT_LINK))
        .thenReturn(Optional.of(latest));

    assertThat(service.ownFor(SESSION_ID, asker)).isSameAs(latest);

    when(repository.findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            SESSION_ID, "asker-1", Via.DIRECT_LINK, Status.ADMITTED))
        .thenReturn(Optional.of(admitted));
    assertThat(service.ownFor(SESSION_ID, asker)).isSameAs(admitted);

    var stranger = new User();
    stranger.setUserId("asker-2");
    assertThatThrownBy(() -> service.ownFor(SESSION_ID, stranger))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void admit_And_Decline_Should_BeTheConversationsCounsellorsDecision() {
    var knock = pending(1L, LocalDateTime.now());
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(knock));

    var admitted = service.admit(1L, counsellor);

    assertThat(admitted.getStatus()).isEqualTo(Status.ADMITTED);
    assertThat(admitted.getDecidedBy()).isEqualTo("counsellor-1");
    assertThat(admitted.getDecidedAt()).isNotNull();
    // Idempotent.
    assertThat(service.admit(1L, counsellor).getStatus()).isEqualTo(Status.ADMITTED);
    assertThatThrownBy(() -> service.decline(1L, counsellor)).isInstanceOf(ConflictException.class);
  }

  @Test
  void admit_Should_Return404_ForAnotherCounsellor_AndForSeriesKnocks() {
    when(repository.findByIdForUpdate(1L))
        .thenReturn(Optional.of(pending(1L, LocalDateTime.now())));
    assertThatThrownBy(() -> service.admit(1L, consultant("someone-else")))
        .isInstanceOf(NotFoundException.class);

    var seriesKnock =
        GroupChatJoinRequest.builder()
            .id(2L)
            .via(Via.INVITE_LINK)
            .seriesId(77L)
            .consultantId("c")
            .status(Status.PENDING)
            .requestedAt(LocalDateTime.now())
            .build();
    when(repository.findByIdForUpdate(2L)).thenReturn(Optional.of(seriesKnock));
    assertThatThrownBy(() -> service.admit(2L, counsellor)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void admitAll_Should_LetEveryoneWaitingIn() {
    var first = pending(1L, LocalDateTime.now().minusMinutes(2));
    var second = pending(2L, LocalDateTime.now().minusMinutes(1));
    when(repository.findCallKnocksForConsultantForUpdate(
            eq("counsellor-1"), eq(Via.DIRECT_LINK), eq(Status.PENDING), any(), eq(SESSION_ID)))
        .thenReturn(List.of(first, second));

    var admitted = service.admitAll(counsellor, SESSION_ID, null);

    assertThat(admitted)
        .extracting(GroupChatJoinRequest::getStatus)
        .containsExactly(Status.ADMITTED, Status.ADMITTED);
  }

  @Test
  void admitAll_Should_RefuseWithoutTheCurrentCall() {
    assertThatThrownBy(() -> service.admitAll(counsellor, null, null))
        .isInstanceOf(BadRequestException.class);
    verify(repository, never())
        .findCallKnocksForConsultantForUpdate(any(), any(), any(), any(), any());
  }

  @Test
  void pendingFor_Should_ScopeToTheCallRoom_And_KeepTheUnscopedList() {
    var session = new Session();
    session.setId(SESSION_ID);
    when(sessionRepository.findByMatrixRoomId("!room:oriso")).thenReturn(Optional.of(session));

    service.pendingFor(counsellor, null, "!room:oriso");
    verify(repository)
        .findCallKnocksForConsultant(
            eq("counsellor-1"), eq(Via.DIRECT_LINK), eq(Status.PENDING), any(), eq(SESSION_ID));

    service.pendingFor(counsellor, null, null);
    verify(repository)
        .findCallKnocksForConsultant(
            eq("counsellor-1"), eq(Via.DIRECT_LINK), eq(Status.PENDING), any(), eq(null));
  }

  @Test
  void scope_Should_RejectAContradictionAndAnUnknownRoom() {
    var other = new Session();
    other.setId(SESSION_ID + 1);
    when(sessionRepository.findByMatrixRoomId("!other:oriso")).thenReturn(Optional.of(other));

    assertThatThrownBy(() -> service.pendingFor(counsellor, SESSION_ID, "!other:oriso"))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.pendingFor(counsellor, null, "!unknown:oriso"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void ownKnock_Should_BeReadableAndCancellableOnlyByThePerson() {
    var knock = pending(1L, LocalDateTime.now());
    when(repository.findById(1L)).thenReturn(Optional.of(knock));
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(knock));

    assertThat(service.getOwn(1L, asker)).isSameAs(knock);
    assertThat(service.cancelOwn(1L, asker).getStatus()).isEqualTo(Status.CANCELLED);
    // Idempotent; a decided knock stays as it is.
    assertThat(service.cancelOwn(1L, asker).getStatus()).isEqualTo(Status.CANCELLED);

    var stranger = new User();
    stranger.setUserId("asker-2");
    assertThatThrownBy(() -> service.getOwn(1L, stranger)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.cancelOwn(1L, stranger)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void cancelOwn_Should_ReadTheKnockUnderLock_AndKeepAnAdmission() {
    var admitted = pending(1L, LocalDateTime.now());
    admitted.setStatus(Status.ADMITTED);
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(admitted));

    assertThat(service.cancelOwn(1L, asker).getStatus()).isEqualTo(Status.ADMITTED);
    verify(repository, never()).findById(any());
    verify(repository, never()).save(any());
  }

  private GroupChatJoinRequest pending(Long id, LocalDateTime requestedAt) {
    return GroupChatJoinRequest.builder()
        .id(id)
        .via(Via.DIRECT_LINK)
        .sessionId(SESSION_ID)
        .userId("asker-1")
        .medium("VIDEO")
        .status(Status.PENDING)
        .requestedAt(requestedAt)
        .build();
  }

  private static Consultant consultant(String id) {
    var consultant = new Consultant();
    consultant.setId(id);
    return consultant;
  }
}
