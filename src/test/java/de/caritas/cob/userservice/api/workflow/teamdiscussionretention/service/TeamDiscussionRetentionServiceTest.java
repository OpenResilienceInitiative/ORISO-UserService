package de.caritas.cob.userservice.api.workflow.teamdiscussionretention.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.RoomPurgeOutcome;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class TeamDiscussionRetentionServiceTest {

  private static final String ROOM = "!discussion:matrix.example.com";

  @InjectMocks private TeamDiscussionRetentionService underTest;

  @Mock private TeamDiscussionRepository teamDiscussionRepository;
  @Mock private TeamDiscussionPurgeWriter purgeWriter;
  @Mock private MatrixSynapseService matrixSynapseService;

  @BeforeEach
  void setUp() {
    setField(underTest, "retentionDays", 90);
  }

  @Test
  void purge_selectsArchivedByArchiveDateAndOpenByCreateDate_underTheSameCutoff() {
    LocalDateTime before = LocalDateTime.now();

    underTest.purgeExpiredDiscussions();
    LocalDateTime after = LocalDateTime.now();

    ArgumentCaptor<LocalDateTime> archivedCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> openCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(teamDiscussionRepository)
        .findByStatusAndArchiveDateBefore(
            eq(TeamDiscussion.Status.ARCHIVED), archivedCutoff.capture());
    verify(teamDiscussionRepository)
        .findByStatusAndCreateDateBefore(eq(TeamDiscussion.Status.OPEN), openCutoff.capture());

    assertThat(archivedCutoff.getValue()).isBetween(before.minusDays(90), after.minusDays(90));
    assertThat(openCutoff.getValue()).isEqualTo(archivedCutoff.getValue());
  }

  @Test
  void purge_purgesTheRoomBeforeDeletingTheRow() {
    TeamDiscussion discussion = discussion(7L);
    when(teamDiscussionRepository.findByStatusAndArchiveDateBefore(any(), any()))
        .thenReturn(List.of(discussion));
    when(matrixSynapseService.purgeRoomOrConfirmGone(ROOM)).thenReturn(RoomPurgeOutcome.PURGED);

    underTest.purgeExpiredDiscussions();

    var inOrder = org.mockito.Mockito.inOrder(matrixSynapseService, purgeWriter);
    inOrder.verify(matrixSynapseService).purgeRoomOrConfirmGone(ROOM);
    inOrder.verify(purgeWriter).deleteDiscussionAndParticipants(discussion);
  }

  /** Never lose the pointer to a room that may still exist: keep the row and retry next run. */
  @Test
  void purge_keepsTheRow_When_theSynapsePurgeFails() {
    TeamDiscussion discussion = discussion(7L);
    when(teamDiscussionRepository.findByStatusAndArchiveDateBefore(any(), any()))
        .thenReturn(List.of(discussion));
    when(matrixSynapseService.purgeRoomOrConfirmGone(ROOM)).thenReturn(RoomPurgeOutcome.FAILED);

    underTest.purgeExpiredDiscussions();

    verifyNoInteractions(purgeWriter);
  }

  @Test
  void purge_deletesTheRow_When_theRoomNoLongerExists() {
    TeamDiscussion discussion = discussion(7L);
    when(teamDiscussionRepository.findByStatusAndCreateDateBefore(any(), any()))
        .thenReturn(List.of(discussion));
    when(matrixSynapseService.purgeRoomOrConfirmGone(ROOM))
        .thenReturn(RoomPurgeOutcome.ALREADY_GONE);

    underTest.purgeExpiredDiscussions();

    verify(purgeWriter).deleteDiscussionAndParticipants(discussion);
  }

  /** One failing discussion must not stop the run for the others. */
  @Test
  void purge_continuesWithTheNextDiscussion_When_oneRowDeleteFails() {
    TeamDiscussion first = discussion(1L);
    TeamDiscussion second = discussion(2L);
    when(teamDiscussionRepository.findByStatusAndArchiveDateBefore(any(), any()))
        .thenReturn(List.of(first, second));
    when(matrixSynapseService.purgeRoomOrConfirmGone(ROOM)).thenReturn(RoomPurgeOutcome.PURGED);
    org.mockito.Mockito.doThrow(new DataIntegrityViolationException("boom"))
        .when(purgeWriter)
        .deleteDiscussionAndParticipants(first);

    underTest.purgeExpiredDiscussions();

    verify(purgeWriter).deleteDiscussionAndParticipants(second);
  }

  @Test
  void purge_doesNothing_When_thePeriodIsZero() {
    setField(underTest, "retentionDays", 0);

    assertThat(underTest.isEnabled()).isFalse();
    underTest.purgeExpiredDiscussions();

    verifyNoInteractions(teamDiscussionRepository, matrixSynapseService, purgeWriter);
  }

  @Test
  void purge_doesNothing_When_thePeriodIsNegative() {
    setField(underTest, "retentionDays", -5);

    underTest.purgeExpiredDiscussions();

    verifyNoInteractions(teamDiscussionRepository, matrixSynapseService, purgeWriter);
    verify(matrixSynapseService, never()).purgeRoomOrConfirmGone(any());
  }

  private static TeamDiscussion discussion(Long id) {
    return TeamDiscussion.builder()
        .id(id)
        .sessionId(id * 100)
        .matrixRoomId(ROOM)
        .status(TeamDiscussion.Status.ARCHIVED)
        .createDate(LocalDateTime.now().minusDays(200))
        .archiveDate(LocalDateTime.now().minusDays(120))
        .tenantId(1L)
        .build();
  }
}
