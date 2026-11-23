package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamDiscussionOrphanCleanupServiceTest {

  @InjectMocks private TeamDiscussionOrphanCleanupService teamDiscussionOrphanCleanupService;

  @Mock private TeamDiscussionRepository teamDiscussionRepository;
  @Mock private TeamDiscussionPurgeService teamDiscussionPurgeService;
  @Mock private WorkflowErrorMailService workflowErrorMailService;

  @Test
  void purgeOrphanedDiscussions_Should_doNothing_When_noOrphanExists() {
    when(teamDiscussionRepository.findAllOrphaned()).thenReturn(List.of());

    var errors = teamDiscussionOrphanCleanupService.purgeOrphanedDiscussions();

    assertThat(errors).isEmpty();
    verifyNoInteractions(teamDiscussionPurgeService, workflowErrorMailService);
  }

  @Test
  void purgeOrphanedDiscussions_Should_purgeEveryOrphanAndSendNoMail_When_allSucceed() {
    var first = discussion(1L);
    var second = discussion(2L);
    when(teamDiscussionRepository.findAllOrphaned()).thenReturn(List.of(first, second));
    when(teamDiscussionPurgeService.purge(any(), anyList())).thenReturn(true);

    var errors = teamDiscussionOrphanCleanupService.purgeOrphanedDiscussions();

    assertThat(errors).isEmpty();
    verify(teamDiscussionPurgeService).purge(eq(first), anyList());
    verify(teamDiscussionPurgeService).purge(eq(second), anyList());
    verifyNoInteractions(workflowErrorMailService);
  }

  @Test
  void purgeOrphanedDiscussions_Should_continueWithNextOrphanAndMailErrors_When_purgeFails() {
    var failing = discussion(1L);
    var fine = discussion(2L);
    when(teamDiscussionRepository.findAllOrphaned()).thenReturn(List.of(failing, fine));
    when(teamDiscussionPurgeService.purge(eq(failing), anyList()))
        .thenAnswer(
            invocation -> {
              List<DeletionWorkflowError> errors = invocation.getArgument(1);
              errors.add(
                  DeletionWorkflowError.builder()
                      .deletionSourceType(DeletionSourceType.ASKER)
                      .deletionTargetType(DeletionTargetType.MATRIX)
                      .identifier(failing.getMatrixRoomId())
                      .reason("boom")
                      .build());
              return false;
            });
    when(teamDiscussionPurgeService.purge(eq(fine), anyList())).thenReturn(true);

    var errors = teamDiscussionOrphanCleanupService.purgeOrphanedDiscussions();

    assertThat(errors).hasSize(1);
    verify(teamDiscussionPurgeService).purge(eq(fine), anyList());
    verify(workflowErrorMailService).buildAndSendErrorMail(errors);
  }

  private static TeamDiscussion discussion(long id) {
    return TeamDiscussion.builder()
        .id(id)
        .sessionId(100L + id)
        .matrixRoomId("!room" + id + ":matrix.example.com")
        .createDate(LocalDateTime.of(2026, 1, 1, 0, 0))
        .build();
  }
}
