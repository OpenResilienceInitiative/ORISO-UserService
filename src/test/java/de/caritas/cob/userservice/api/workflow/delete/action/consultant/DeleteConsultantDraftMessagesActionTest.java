package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.USER_CONTENT;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.DraftMessageRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteConsultantDraftMessagesActionTest {

  @InjectMocks private DeleteConsultantDraftMessagesAction deleteConsultantDraftMessagesAction;

  @Mock private DraftMessageRepository draftMessageRepository;

  @Test
  void execute_Should_deleteAllDraftMessagesOfConsultant() {
    Consultant consultant = new Consultant();
    consultant.setId("consultant-id");
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, emptyList());

    this.deleteConsultantDraftMessagesAction.execute(workflowDTO);

    verify(this.draftMessageRepository, times(1)).deleteByUserId("consultant-id");
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  void execute_Should_addWorkflowError_When_deletionFails() {
    Consultant consultant = new Consultant();
    consultant.setId("consultant-id");
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());
    doThrow(new RuntimeException("db down"))
        .when(this.draftMessageRepository)
        .deleteByUserId("consultant-id");

    this.deleteConsultantDraftMessagesAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(1));
    DeletionWorkflowError error = workflowDTO.getDeletionWorkflowErrors().get(0);
    assertThat(error.getDeletionSourceType(), is(CONSULTANT));
    assertThat(error.getDeletionTargetType(), is(USER_CONTENT));
    assertThat(error.getIdentifier(), is("consultant-id"));
    assertThat(error.getTimestamp(), notNullValue());
  }
}
