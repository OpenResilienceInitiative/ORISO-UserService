package de.caritas.cob.userservice.api.service.teamdiscussion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.model.TeamDiscussionRoomCleanupTask;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRoomCleanupTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamDiscussionRoomCleanupServiceTest {

  private static final String LOSING_ROOM = "!loser:example.org";

  @Mock private TeamDiscussionRoomCleanupTaskRepository cleanupTaskRepository;
  @Mock private TeamDiscussionRepository discussionRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-14T15:00:00Z"), ZoneOffset.UTC);

  private TeamDiscussionRoomCleanupService underTest;

  private TeamDiscussionRoomCleanupTask task;

  @BeforeEach
  void setUp() {
    underTest =
        new TeamDiscussionRoomCleanupService(
            cleanupTaskRepository, discussionRepository, matrixSynapseService, clock);
    task =
        TeamDiscussionRoomCleanupTask.builder()
            .id(1L)
            .sessionId(42L)
            .matrixRoomId(LOSING_ROOM)
            .createDate(LocalDateTime.now(clock))
            .build();
  }

  @Test
  void failedCleanupIsPersistedBeforeTheRequestReturnsItsError() {
    underTest.recordFailedCleanup(42L, LOSING_ROOM);

    var saved = ArgumentCaptor.forClass(TeamDiscussionRoomCleanupTask.class);
    verify(cleanupTaskRepository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getSessionId()).isEqualTo(42L);
    assertThat(saved.getValue().getMatrixRoomId()).isEqualTo(LOSING_ROOM);
    assertThat(saved.getValue().getCreateDate()).isEqualTo(LocalDateTime.now(clock));
  }

  @Test
  void failedRetryKeepsTheDurableHandleForTheNextRun() {
    when(cleanupTaskRepository.findAllByOrderByCreateDateAsc()).thenReturn(List.of(task));
    when(discussionRepository.findByMatrixRoomId(LOSING_ROOM)).thenReturn(Optional.empty());
    when(matrixSynapseService.purgeRoomOrConfirmGone(LOSING_ROOM))
        .thenReturn(MatrixSynapseService.RoomPurgeOutcome.FAILED);

    underTest.retryPendingCleanup();

    assertThat(task.getAttemptCount()).isEqualTo(1);
    assertThat(task.getLastAttemptAt()).isEqualTo(LocalDateTime.now(clock));
    verify(cleanupTaskRepository).save(task);
    verify(cleanupTaskRepository, never()).delete(task);
  }

  @Test
  void successfulRetryDeletesTheDurableHandle() {
    when(cleanupTaskRepository.findAllByOrderByCreateDateAsc()).thenReturn(List.of(task));
    when(discussionRepository.findByMatrixRoomId(LOSING_ROOM)).thenReturn(Optional.empty());
    when(matrixSynapseService.purgeRoomOrConfirmGone(LOSING_ROOM))
        .thenReturn(MatrixSynapseService.RoomPurgeOutcome.PURGED);

    underTest.retryPendingCleanup();

    verify(cleanupTaskRepository).delete(task);
  }

  @Test
  void matrixExceptionKeepsTheDurableHandleForTheNextRun() {
    when(cleanupTaskRepository.findAllByOrderByCreateDateAsc()).thenReturn(List.of(task));
    when(discussionRepository.findByMatrixRoomId(LOSING_ROOM)).thenReturn(Optional.empty());
    when(matrixSynapseService.purgeRoomOrConfirmGone(LOSING_ROOM))
        .thenThrow(new IllegalStateException("matrix unavailable"));

    underTest.retryPendingCleanup();

    assertThat(task.getAttemptCount()).isEqualTo(1);
    verify(cleanupTaskRepository).save(task);
    verify(cleanupTaskRepository, never()).delete(task);
  }

  @Test
  void retryNeverPurgesARoomReferencedByAPersistedDiscussion() {
    when(cleanupTaskRepository.findAllByOrderByCreateDateAsc()).thenReturn(List.of(task));
    when(discussionRepository.findByMatrixRoomId(LOSING_ROOM))
        .thenReturn(Optional.of(TeamDiscussion.builder().matrixRoomId(LOSING_ROOM).build()));

    underTest.retryPendingCleanup();

    verify(matrixSynapseService, never()).purgeRoomOrConfirmGone(LOSING_ROOM);
    verify(cleanupTaskRepository).delete(task);
  }
}
