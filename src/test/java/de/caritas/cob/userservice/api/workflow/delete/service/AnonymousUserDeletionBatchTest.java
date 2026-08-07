package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.UnexpectedRollbackException;

@ExtendWith(MockitoExtension.class)
class AnonymousUserDeletionBatchTest {

  @InjectMocks private AnonymousUserDeletionBatch deletionBatch;

  @Mock private AnonymousUserDeletionCandidates deletionCandidates;

  @Mock private AnonymousUserDeletionUnit deletionUnit;

  @Test
  void deleteOverdueUsers_Should_notDeleteAnything_When_thereAreNoCandidates() {
    when(deletionCandidates.findOverdueAnonymousUserIds()).thenReturn(List.of());

    assertThat(deletionBatch.deleteOverdueUsers()).isEmpty();
    verifyNoInteractions(deletionUnit);
  }

  @Test
  void deleteOverdueUsers_Should_deleteEachCandidateSeparately() {
    when(deletionCandidates.findOverdueAnonymousUserIds()).thenReturn(List.of("first", "second"));
    when(deletionUnit.deleteUser("first")).thenReturn(List.of());
    when(deletionUnit.deleteUser("second")).thenReturn(List.of());

    deletionBatch.deleteOverdueUsers();

    verify(deletionUnit).deleteUser("first");
    verify(deletionUnit).deleteUser("second");
  }

  @Test
  void deleteOverdueUsers_Should_collectWorkflowErrorsOfAllCandidates() {
    var error = mock(DeletionWorkflowError.class);
    when(deletionCandidates.findOverdueAnonymousUserIds()).thenReturn(List.of("first", "second"));
    when(deletionUnit.deleteUser("first")).thenReturn(List.of(error));
    when(deletionUnit.deleteUser("second")).thenReturn(List.of());

    assertThat(deletionBatch.deleteOverdueUsers()).containsExactly(error);
  }

  /**
   * A failed commit surfaces where the isolated deletion is called, not inside the workflow
   * actions. Letting it escape here would abandon every candidate that has not run yet, which is
   * the replay this workflow has to stop.
   */
  @Test
  void deleteOverdueUsers_Should_continueWithRemainingUsers_When_oneUserCommitFails() {
    when(deletionCandidates.findOverdueAnonymousUserIds())
        .thenReturn(List.of("poisoned", "healthy"));
    when(deletionUnit.deleteUser("poisoned"))
        .thenThrow(new UnexpectedRollbackException("marked rollback-only"));
    when(deletionUnit.deleteUser("healthy")).thenReturn(List.of());

    List<DeletionWorkflowError> workflowErrors = deletionBatch.deleteOverdueUsers();

    verify(deletionUnit).deleteUser("healthy");
    assertThat(workflowErrors)
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getIdentifier()).isEqualTo("poisoned");
              assertThat(error.getDeletionSourceType()).isEqualTo(DeletionSourceType.ASKER);
              assertThat(error.getDeletionTargetType()).isEqualTo(DeletionTargetType.DATABASE);
            });
  }

  @Test
  void deleteOverdueUsers_Should_reportRatherThanThrow_When_aUserFailsInTheDatabase() {
    when(deletionCandidates.findOverdueAnonymousUserIds()).thenReturn(List.of("poisoned"));
    when(deletionUnit.deleteUser("poisoned"))
        .thenThrow(new DataIntegrityViolationException("restricting foreign key"));

    assertThatNoException().isThrownBy(deletionBatch::deleteOverdueUsers);
  }
}
