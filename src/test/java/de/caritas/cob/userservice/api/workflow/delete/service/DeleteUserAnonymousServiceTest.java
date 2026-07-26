package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import jakarta.transaction.Transactional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteUserAnonymousServiceTest {

  @InjectMocks private DeleteUserAnonymousService deletionService;

  @Mock private AnonymousUserDeletionBatch deletionBatch;
  @Mock private WorkflowErrorMailService workflowErrorMailService;

  @Test
  void deleteInactiveAnonymousUsers_Should_notifyOutsideTheDeletionTransaction() throws Exception {
    var entrypoint = DeleteUserAnonymousService.class.getMethod("deleteInactiveAnonymousUsers");

    assertThat(entrypoint.getAnnotation(Transactional.class)).isNull();
  }

  @Test
  void deleteInactiveAnonymousUsers_Should_notNotify_When_batchHasNoErrors() {
    when(deletionBatch.deleteOverdueUsers()).thenReturn(List.of());

    deletionService.deleteInactiveAnonymousUsers();

    verify(workflowErrorMailService, never())
        .buildAndSendErrorMail(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void deleteInactiveAnonymousUsers_Should_notifyAfterTransactionalBatchReturns() {
    var error = org.mockito.Mockito.mock(DeletionWorkflowError.class);
    when(deletionBatch.deleteOverdueUsers()).thenReturn(List.of(error));

    deletionService.deleteInactiveAnonymousUsers();

    var order = inOrder(deletionBatch, workflowErrorMailService);
    order.verify(deletionBatch).deleteOverdueUsers();
    order.verify(workflowErrorMailService).buildAndSendErrorMail(List.of(error));
  }

  @Test
  void deleteInactiveAnonymousUsers_Should_notReplayBatch_When_notificationFails() {
    var error = org.mockito.Mockito.mock(DeletionWorkflowError.class);
    when(deletionBatch.deleteOverdueUsers()).thenReturn(List.of(error));
    org.mockito.Mockito.doThrow(new IllegalStateException("tenant unavailable"))
        .when(workflowErrorMailService)
        .buildAndSendErrorMail(List.of(error));

    assertThatThrownBy(deletionService::deleteInactiveAnonymousUsers)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tenant unavailable");

    verify(deletionBatch).deleteOverdueUsers();
  }
}
