package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.USER_CONTENT;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.DraftMessageRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteAskerDraftMessagesActionTest {

  @InjectMocks private DeleteAskerDraftMessagesAction deleteAskerDraftMessagesAction;

  @Mock private DraftMessageRepository draftMessageRepository;

  @Test
  void execute_Should_deleteAllDraftMessagesOfAsker() {
    User user = new User();
    user.setUserId("asker-id");
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, emptyList());

    this.deleteAskerDraftMessagesAction.execute(workflowDTO);

    verify(this.draftMessageRepository, times(1)).deleteByUserId("asker-id");
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  void execute_Should_addWorkflowError_When_deletionFails() {
    User user = new User();
    user.setUserId("asker-id");
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());
    doThrow(new RuntimeException("db down"))
        .when(this.draftMessageRepository)
        .deleteByUserId("asker-id");

    this.deleteAskerDraftMessagesAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(1));
    DeletionWorkflowError error = workflowDTO.getDeletionWorkflowErrors().get(0);
    assertThat(error.getDeletionSourceType(), is(ASKER));
    assertThat(error.getDeletionTargetType(), is(USER_CONTENT));
    assertThat(error.getIdentifier(), is("asker-id"));
    assertThat(error.getTimestamp(), notNullValue());
  }
}
