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
            throw new UnsupportedOperationException("Explicit deletion capability required");
          }

          public void deleteUser(
              String id,
              de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
                  origin) {
            origin.requireLifecycleDeletion(id);
            throw new org.springframework.web.client.HttpClientErrorException(
                org.springframework.http.HttpStatus.NOT_FOUND);
          }

          public void rollbackUser(String id) {
            throw new UnsupportedOperationException();
          }
        };
    var user = new User("person", null, "test", "test@example.invalid", false);
    user.setDeleteDate(java.time.LocalDateTime.now());
    var outcome = new AskerDeletionWorkflowDTO(user, new ArrayList<>());
    new DeleteKeycloakAskerAction(remote).execute(outcome);
    assertThat(outcome.getDeletionWorkflowErrors()).isEmpty();
  }
}
