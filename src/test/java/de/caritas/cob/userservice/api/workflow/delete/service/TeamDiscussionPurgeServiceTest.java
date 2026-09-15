package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.DATABASE;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.MATRIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.teamdiscussionretention.service.TeamDiscussionPurgeWriter;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamDiscussionPurgeServiceTest {

  private static final String ROOM_ID = "!discussion:matrix.example.com";

  @InjectMocks private TeamDiscussionPurgeService teamDiscussionPurgeService;

  @Mock private TeamDiscussionPurgeWriter deletionWriter;
  @Mock private MatrixSynapseService matrixSynapseService;

  private LogbackCaptor logCaptor;
  private List<DeletionWorkflowError> workflowErrors;

  @BeforeEach
  void setUp() {
    logCaptor = LogbackCaptor.forClass(TeamDiscussionPurgeService.class);
    workflowErrors = new ArrayList<>();
  }

  @AfterEach
  void tearDown() {
    logCaptor.detach();
  }

  @Test
  void purge_Should_purgeRoomThenDeleteParticipantsAndRow_When_synapseSucceeds() {
    var discussion = discussion(ROOM_ID);
    when(matrixSynapseService.purgeRoom(ROOM_ID)).thenReturn(true);

    var purged = teamDiscussionPurgeService.purge(discussion, workflowErrors);

    assertThat(purged).isTrue();
    assertThat(workflowErrors).isEmpty();
    assertThat(logCaptor.count(Level.ERROR)).isZero();
    var order = inOrder(matrixSynapseService, deletionWriter);
    order.verify(matrixSynapseService).purgeRoom(ROOM_ID);
    order.verify(deletionWriter).deleteDiscussionAndParticipants(discussion);
  }

  /** The row is the only handle that still names the room, so it must survive a failed purge. */
  @Test
  void purge_Should_reportMatrixErrorAndKeepRow_When_synapsePurgeFails() {
    var discussion = discussion(ROOM_ID);
    when(matrixSynapseService.purgeRoom(ROOM_ID)).thenReturn(false);

    var purged = teamDiscussionPurgeService.purge(discussion, workflowErrors);

    assertThat(purged).isFalse();
    assertThat(workflowErrors).hasSize(1);
    var error = workflowErrors.get(0);
    assertThat(error.getDeletionSourceType()).isEqualTo(ASKER);
    assertThat(error.getDeletionTargetType()).isEqualTo(MATRIX);
    assertThat(error.getIdentifier()).isEqualTo(ROOM_ID);
    assertThat(error.getReason()).isEqualTo(TeamDiscussionPurgeService.MATRIX_ROOM_ERROR_REASON);
    assertThat(error.getTimestamp()).isNotNull();
    assertThat(logCaptor.contains(Level.ERROR, "UserService delete workflow error")).isTrue();
    verifyNoInteractions(deletionWriter);
  }

  @Test
  void purge_Should_skipSynapseAndDeleteRows_When_discussionHasNoRoomId() {
    var discussion = discussion(" ");

    var purged = teamDiscussionPurgeService.purge(discussion, workflowErrors);

    assertThat(purged).isTrue();
    assertThat(workflowErrors).isEmpty();
    verifyNoInteractions(matrixSynapseService);
    verify(deletionWriter).deleteDiscussionAndParticipants(discussion);
  }

  @Test
  void purge_Should_reportDatabaseError_When_rowDeletionFails() {
    var discussion = discussion(ROOM_ID);
    when(matrixSynapseService.purgeRoom(ROOM_ID)).thenReturn(true);
    doThrow(new RuntimeException("db down"))
        .when(deletionWriter)
        .deleteDiscussionAndParticipants(any(TeamDiscussion.class));

    var purged = teamDiscussionPurgeService.purge(discussion, workflowErrors);

    assertThat(purged).isFalse();
    assertThat(workflowErrors).hasSize(1);
    var error = workflowErrors.get(0);
    assertThat(error.getDeletionSourceType()).isEqualTo(ASKER);
    assertThat(error.getDeletionTargetType()).isEqualTo(DATABASE);
    assertThat(error.getIdentifier()).isEqualTo("7");
    assertThat(error.getReason()).isEqualTo(TeamDiscussionPurgeService.DATABASE_ERROR_REASON);
    assertThat(logCaptor.contains(Level.ERROR, "UserService delete workflow error")).isTrue();
  }

  private static TeamDiscussion discussion(String roomId) {
    return TeamDiscussion.builder()
        .id(7L)
        .sessionId(42L)
        .matrixRoomId(roomId)
        .createDate(LocalDateTime.of(2026, 1, 1, 0, 0))
        .build();
  }
}
