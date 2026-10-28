package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.time.ZoneId;
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
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setCreateDate(NOW.minusDays(2));
    session.setUpdateDate(NOW.minusHours(1));
    when(userRepository.findLiveDirectLinkTemporaryAccountIds()).thenReturn(List.of(USER_ID));
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    when(sessionRepository.findByUser(user)).thenReturn(List.of(session));
    when(callBindings.findBySessionIdIn(any())).thenReturn(List.of());
    when(matrix.redactUserEvents(anyString(), anyList())).thenReturn(true);
    when(matrix.deactivateUser(anyString(), anyBoolean())).thenReturn(true);
  }

  private static TemporaryAccountSnapshot snapshot(String medium, MessageRetentionMode mode) {
    return TemporaryAccountSnapshot.builder()
        .medium(medium)
        .inviteLinkId(9L)
        .callDeleteAfterMinutes(15)
        .callMaxLifetimeHours(24)
        .chatInactivityDays(30)
        .messageRetention(mode)
        .build();
  }

  @Test
  void dueAccountIds_Should_ListAChatAccount_When_TheCounsellorArchivedTheConversation() {
    assertThat(cleanup.dueAccountIds(NOW)).isEmpty();

    session.setStatus(SessionStatus.IN_ARCHIVE);

    assertThat(cleanup.dueAccountIds(NOW)).containsExactly(USER_ID);
  }

  @Test
  void dueAccountIds_Should_ListACallAccount_FifteenMinutesAfterHangUp() {
    user.setTemporaryAccountSnapshot(snapshot("VIDEO", MessageRetentionMode.ALWAYS_TEMPORARY));
    user.setCreateDate(NOW.minusHours(1));
    var hangUp = NOW.minusMinutes(15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
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
    verify(matrix).redactUserEvents(MATRIX_ID, List.of(ROOM));
    verify(matrix).deactivateUser(MATRIX_ID, true);
    verify(sessionRepository, never()).delete(any());
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isEqualTo(NOW);
    verify(userRepository).save(user);
  }

  @Test
  void delete_Should_KeepTheMessages_When_AlwaysKept() {
    user.setTemporaryAccountSnapshot(snapshot("CHAT", MessageRetentionMode.ALWAYS_PERSISTENT));

    cleanup.delete(USER_ID, NOW);

    verify(matrix, never()).redactUserEvents(anyString(), anyList());
    verify(matrix).deactivateUser(MATRIX_ID, false);
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isEqualTo(NOW);
  }

  @Test
  void delete_Should_FollowTheGuestsAnswer_When_TheGuestDecides() {
    var decided = snapshot("CHAT", MessageRetentionMode.GUEST_DECIDES);
    decided.setKeepMessages(true);
    user.setTemporaryAccountSnapshot(decided);

    cleanup.delete(USER_ID, NOW);

    verify(matrix, never()).redactUserEvents(anyString(), anyList());
    verify(matrix).deactivateUser(MATRIX_ID, false);
  }

  @Test
  void delete_Should_DeleteTheMessages_When_TheGuestNeverDecided() {
    user.setTemporaryAccountSnapshot(snapshot("CHAT", MessageRetentionMode.GUEST_DECIDES));

    cleanup.delete(USER_ID, NOW);

    verify(matrix).redactUserEvents(MATRIX_ID, List.of(ROOM));
    verify(matrix).deactivateUser(MATRIX_ID, true);
  }

  @Test
  void delete_Should_ReportAndRetryLater_When_MatrixFails() {
    when(matrix.redactUserEvents(anyString(), anyList())).thenReturn(false);

    var errors = cleanup.delete(USER_ID, NOW);

    assertThat(errors)
        .singleElement()
        .satisfies(
            error ->
                assertThat(error.getDeletionTargetType()).isEqualTo(DeletionTargetType.MATRIX));
    verify(matrix, never()).deactivateUser(anyString(), anyBoolean());
    assertThat(user.getTemporaryAccountSnapshot().getDeletedAt()).isNull();
    verify(userRepository, never()).save(any());
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
