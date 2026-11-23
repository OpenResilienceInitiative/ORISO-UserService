package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteKeycloakAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class InactiveIdentityDeletionRetryTest {
  @Test
  void alreadyDeletedIdentityDoesNotPreventCompletionOnRetry() {
    var remote =
        new IdentityAccountRemover() {
          public void deleteUser(String id) {
            throw new jakarta.ws.rs.NotFoundException();
          }

          public void rollbackUser(String id) {
            throw new UnsupportedOperationException();
          }
        };
    var user = new User("person", null, "test", "test@example.invalid", false);
    var outcome = new AskerDeletionWorkflowDTO(user, new ArrayList<>());
    new DeleteKeycloakAskerAction(remote).execute(outcome);
    assertThat(outcome.getDeletionWorkflowErrors()).isEmpty();
  }
}
