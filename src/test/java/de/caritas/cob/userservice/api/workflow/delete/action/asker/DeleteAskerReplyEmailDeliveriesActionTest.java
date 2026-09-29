package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteAskerReplyEmailDeliveriesActionTest {
  @InjectMocks private DeleteAskerReplyEmailDeliveriesAction action;
  @Mock private ReplyEmailDeliveryRepository repository;

  @Test
  void removesDeliveryEvidenceForDeletedRecipient() {
    var target = target();

    action.execute(target);

    verify(repository).deleteByRecipientUserId("asker-id");
    assertThat(target.getDeletionWorkflowErrors()).isEmpty();
  }

  @Test
  void reportsFailedCleanupSoTheAccountRemainsRetryable() {
    var target = target();
    doThrow(new IllegalStateException("database unavailable"))
        .when(repository)
        .deleteByRecipientUserId("asker-id");

    action.execute(target);

    assertThat(target.getDeletionWorkflowErrors()).hasSize(1);
    assertThat(target.getDeletionWorkflowErrors().get(0).getDeletionTargetType())
        .isEqualTo(DeletionTargetType.USER_CONTENT);
  }

  private static AskerDeletionWorkflowDTO target() {
    var user = new User();
    user.setUserId("asker-id");
    return new AskerDeletionWorkflowDTO(user, new ArrayList<>());
  }
}
