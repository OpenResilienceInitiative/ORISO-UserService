package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.temporaryaccount.MessageRetentionMode;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountExpiryPolicy;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class DirectLinkTemporaryAccountCleanupTest {

  private static final String USER_ID = "guest-1";
  private static final String MATRIX_ID = "@guest-1:matrix.oriso.org";
  private static final String ROOM = "!room:matrix.oriso.org";
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 12, 0);

  @Mock private UserRepository userRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private MatrixCallBindingRepository callBindings;
  @Mock private MatrixSynapseService matrix;
  @Mock private IdentityAccountRemover identityAccountRemover;

  private DirectLinkTemporaryAccountCleanup cleanup;
  private User user;
  private Session session;

  @BeforeEach
  void setUp() {
    cleanup =
        new DirectLinkTemporaryAccountCleanup(
            userRepository,
            sessionRepository,
            callBindings,
            matrix,
            identityAccountRemover,
            new TemporaryAccountExpiryPolicy());
    user = new User();
    user.setUserId(USER_ID);
    user.setMatrixUserId(MATRIX_ID);
    user.setTemporaryAccount(true);
    user.setCreateDate(NOW.minusDays(2));
    user.setTemporaryAccountSnapshot(snapshot("CHAT", MessageRetentionMode.ALWAYS_TEMPORARY));
    session = new Session();
    session.setId(7L);
    session.setUser(user);
    session.setMatrixRoomId(ROOM);
    // Closed, so the account is due and the delete tests reach the destructive steps.
    session.setStatus(SessionStatus.DONE);
    session.setCreateDate(NOW.minusDays(2));
    session.setUpdateDate(NOW.minusHours(1));
    when(userRepository.findLiveDirectLinkTemporaryAccountIds()).thenReturn(List.of(USER_ID));
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    when(sessionRepository.findByUser(user)).thenReturn(List.of(session));
    when(callBindings.findBySessionIdIn(any())).thenReturn(List.of());
    when(matrix.setAccountSuspended(anyString(), eq(true))).thenReturn(true);
    when(matrix.submitRedaction(anyString(), anyList())).thenReturn(Optional.of("r1"));
    when(matrix.redactionStatus("r1")).thenReturn(MatrixSynapseService.RedactionStatus.COMPLETE);
    when(matrix.deactivateUser(anyString(), anyBoolean())).thenReturn(true);
  }

  private static long utcMillis(LocalDateTime time) {
    return time.toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  /** Messages go through Matrix and never touch the session row; Matrix tells the last one. */
  @Test
  void dueAccountIds_Should_KeepAChatAccount_While_TheGuestKeepsWriting() {
    session.setStatus(SessionStatus.IN_PROGRESS);
    user.setCreateDate(NOW.minusDays(40));
    session.setCreateDate(NOW.minusDays(40));
    session.setUpdateDate(NOW.minusDays(40));
    when(matrix.latestMessageTimestamp(ROOM)).thenReturn(utcMillis(NOW.minusDays(1)));

    assertThat(cleanup.dueAccountIds(NOW)).isEmpty();

    when(matrix.latestMessageTimestamp(ROOM)).thenReturn(utcMillis(NOW.minusDays(31)));

    assertThat(cleanup.dueAccountIds(NOW)).containsExactly(USER_ID);
  }

  @Test
  void dueAccountIds_Should_KeepAChatAccount_When_MatrixCannotTellTheLastMessage() {
    session.setStatus(SessionStatus.IN_PROGRESS);
    user.setCreateDate(NOW.minusDays(40));
    session.setCreateDate(NOW.minusDays(40));
    session.setUpdateDate(NOW.minusDays(40));
    when(matrix.latestMessageTimestamp(ROOM))
        .thenThrow(new MatrixSynapseService.RoomEventUnavailableException(new RuntimeException()));

    assertThat(cleanup.dueAccountIds(NOW)).isEmpty();
  }

  /** Selection can be stale by the time the account comes up; delete checks again. */
  @Test
  void delete_Should_LeaveTheAccount_When_ItIsNoLongerDue() {
    session.setStatus(SessionStatus.IN_PROGRESS);

    assertThat(cleanup.delete(USER_ID, NOW)).isEmpty();
    verify(identityAccountRemover, never()).deleteUser(anyString());
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isNull();
  }

  @Test
  void delete_Should_ReportKeycloak_AndLeaveMatrixAlone_When_KeycloakFails() {
    doThrow(new RuntimeException("down")).when(identityAccountRemover).deleteUser(USER_ID);

    assertThat(cleanup.delete(USER_ID, NOW))
        .singleElement()
        .satisfies(
            error ->
                assertThat(error.getDeletionTargetType()).isEqualTo(DeletionTargetType.KEYCLOAK));
    verify(matrix, never()).submitRedaction(anyString(), anyList());
    verify(matrix, never()).deactivateUser(anyString(), anyBoolean());
    verify(userRepository, never()).save(any());
  }

  @Test
  void delete_Should_RetryLater_When_DeactivationFails() {
    when(matrix.deactivateUser(anyString(), anyBoolean())).thenReturn(false);

    assertThat(cleanup.delete(USER_ID, NOW)).singleElement();
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isNull();
    verify(userRepository, never()).save(any());
  }

  @Test
  void delete_Should_SkipMatrix_When_TheAccountHasNoMatrixUser() {
    user.setMatrixUserId(" ");

    assertThat(cleanup.delete(USER_ID, NOW)).isEmpty();
    verify(matrix, never()).submitRedaction(anyString(), anyList());
    verify(matrix, never()).deactivateUser(anyString(), anyBoolean());
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isEqualTo(NOW);
  }

  /** The error mail must not carry a Matrix identifier in clear text. */
  @Test
  void delete_Should_NameTheAccountNotTheMatrixUser_InErrors() {
    when(matrix.deactivateUser(anyString(), anyBoolean())).thenReturn(false);

    assertThat(cleanup.delete(USER_ID, NOW))
        .singleElement()
        .satisfies(error -> assertThat(error.getIdentifier()).isEqualTo(USER_ID));
  }

  private static TemporaryAccountSnapshot snapshot(String medium, MessageRetentionMode mode) {
    return TemporaryAccountSnapshot.builder()
        .medium(
            medium == null
                ? null
                : de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType
                    .valueOf(medium))
        .inviteLinkId(9L)
        .callDeleteAfterMinutes(15)
        .callMaxLifetimeHours(24)
        .chatInactivityDays(30)
        .messageRetention(mode)
        .build();
  }

  @Test
  void dueAccountIds_Should_ListAChatAccount_When_TheCounsellorArchivedTheConversation() {
    session.setStatus(SessionStatus.IN_PROGRESS);
    assertThat(cleanup.dueAccountIds(NOW)).isEmpty();

    session.setStatus(SessionStatus.IN_ARCHIVE);

    assertThat(cleanup.dueAccountIds(NOW)).containsExactly(USER_ID);
  }

  @Test
  void dueAccountIds_Should_ListACallAccount_FifteenMinutesAfterHangUp() {
    user.setTemporaryAccountSnapshot(snapshot("VIDEO", MessageRetentionMode.ALWAYS_TEMPORARY));
    user.setCreateDate(NOW.minusHours(1));
    var hangUp = utcMillis(NOW.minusMinutes(15));
    var call =
        MatrixCallBinding.builder()
            .sessionId(7L)
            .startedAt(hangUp - 60_000)
            .endedAt(hangUp)
            .build();
    when(callBindings.findBySessionIdIn(List.of(7L))).thenReturn(List.of(call));

    assertThat(cleanup.dueAccountIds(NOW)).containsExactly(USER_ID);
    assertThat(cleanup.dueAccountIds(NOW.minusMinutes(1))).isEmpty();
  }

  @Test
  void delete_Should_RemoveIdentitiesAndMessages_ButKeepTheConversation_When_AlwaysTemporary() {
    var errors = cleanup.delete(USER_ID, NOW);

    assertThat(errors).isEmpty();
    verify(identityAccountRemover).deleteUser(USER_ID);
    verify(matrix).submitRedaction(MATRIX_ID, List.of(ROOM));
    verify(matrix).deactivateUser(MATRIX_ID, true);
    verify(sessionRepository, never()).delete(any());
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isEqualTo(NOW);
    verify(userRepository).save(user);
  }

  @Test
  void delete_Should_KeepTheMessages_When_AlwaysKept() {
    user.setTemporaryAccountSnapshot(snapshot("CHAT", MessageRetentionMode.ALWAYS_PERSISTENT));

    cleanup.delete(USER_ID, NOW);

    verify(matrix, never()).submitRedaction(anyString(), anyList());
    verify(matrix).deactivateUser(MATRIX_ID, false);
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isEqualTo(NOW);
  }

  @Test
  void delete_Should_FollowTheGuestsAnswer_When_TheGuestDecides() {
    var decided = snapshot("CHAT", MessageRetentionMode.GUEST_DECIDES);
    decided.setKeepMessages(true);
    user.setTemporaryAccountSnapshot(decided);

    cleanup.delete(USER_ID, NOW);

    verify(matrix, never()).submitRedaction(anyString(), anyList());
    verify(matrix).deactivateUser(MATRIX_ID, false);
  }

  @Test
  void delete_Should_DeleteTheMessages_When_TheGuestNeverDecided() {
    user.setTemporaryAccountSnapshot(snapshot("CHAT", MessageRetentionMode.GUEST_DECIDES));

    cleanup.delete(USER_ID, NOW);

    verify(matrix).submitRedaction(MATRIX_ID, List.of(ROOM));
    verify(matrix).deactivateUser(MATRIX_ID, true);
  }

  /** Once Keycloak is gone, Matrix access goes too, whatever happens to the redaction. */
  @Test
  void delete_Should_RevokeMatrixAccessAndMarkTheStart_When_TheRedactionCannotStart() {
    when(matrix.submitRedaction(anyString(), anyList())).thenReturn(Optional.empty());

    var errors = cleanup.delete(USER_ID, NOW);

    assertThat(errors)
        .singleElement()
        .satisfies(
            error ->
                assertThat(error.getDeletionTargetType()).isEqualTo(DeletionTargetType.MATRIX));
    var order = org.mockito.Mockito.inOrder(identityAccountRemover, matrix);
    order.verify(identityAccountRemover).deleteUser(USER_ID);
    order.verify(matrix).setAccountSuspended(MATRIX_ID, true);
    verify(matrix, never()).deactivateUser(anyString(), anyBoolean());
    var snapshot = user.getTemporaryAccountSnapshot();
    assertThat(snapshot.getDeletionStartedAt()).isEqualTo(NOW);
    assertThat(snapshot.getDeletedAt()).isNull();
  }

  /** A started deletion is finished, never judged again on a half-deleted account. */
  @Test
  void delete_Should_FinishAStartedDeletion_WithoutCheckingInactivityAgain() {
    session.setStatus(SessionStatus.IN_PROGRESS);
    user.getTemporaryAccountSnapshot().setDeletionStartedAt(NOW.minusHours(1));

    assertThat(cleanup.dueAccountIds(NOW)).containsExactly(USER_ID);
    assertThat(cleanup.delete(USER_ID, NOW)).isEmpty();
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isEqualTo(NOW);
  }

  @Test
  void delete_Should_PollThePendingRedaction_InsteadOfSubmittingAgain() {
    var snapshot = user.getTemporaryAccountSnapshot();
    snapshot.setDeletionStartedAt(NOW.minusHours(1));
    snapshot.setRedactId("r1");
    when(matrix.redactionStatus("r1")).thenReturn(MatrixSynapseService.RedactionStatus.RUNNING);

    assertThat(cleanup.delete(USER_ID, NOW)).isEmpty();
    verify(matrix, never()).submitRedaction(anyString(), anyList());
    verify(matrix, never()).deactivateUser(anyString(), anyBoolean());
    assertThat(snapshot.getDeletedAt()).isNull();

    when(matrix.redactionStatus("r1")).thenReturn(MatrixSynapseService.RedactionStatus.COMPLETE);

    assertThat(cleanup.delete(USER_ID, NOW)).isEmpty();
    verify(matrix, never()).submitRedaction(anyString(), anyList());
    verify(matrix).deactivateUser(MATRIX_ID, true);
    assertThat(snapshot.getDeletedAt()).isEqualTo(NOW);
    assertThat(snapshot.getRedactId()).isNull();
  }

  @Test
  void delete_Should_SubmitAgainLater_When_TheRedactionFailed() {
    when(matrix.redactionStatus("r1")).thenReturn(MatrixSynapseService.RedactionStatus.FAILED);

    assertThat(cleanup.delete(USER_ID, NOW)).singleElement();
    assertThat(user.getTemporaryAccountSnapshot().getRedactId()).isNull();
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isNull();
    verify(matrix, never()).deactivateUser(anyString(), anyBoolean());
  }

  @Test
  void delete_Should_Continue_When_TheKeycloakUserIsAlreadyGone() {
    doThrow(new jakarta.ws.rs.NotFoundException()).when(identityAccountRemover).deleteUser(USER_ID);

    var errors = cleanup.delete(USER_ID, NOW);

    assertThat(errors).isEmpty();
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isEqualTo(NOW);
  }

  @Test
  void delete_Should_DoNothing_When_TheAccountIsAlreadyDeleted() {
    user.getTemporaryAccountSnapshot().setDeletedAt(NOW.minusDays(1));

    assertThat(cleanup.delete(USER_ID, NOW)).isEmpty();
    verify(identityAccountRemover, never()).deleteUser(any());
  }
}
