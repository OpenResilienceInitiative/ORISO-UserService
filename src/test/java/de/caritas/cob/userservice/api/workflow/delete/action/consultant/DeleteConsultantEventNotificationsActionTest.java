package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.DATABASE;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteConsultantEventNotificationsActionTest {

  @InjectMocks
  private DeleteConsultantEventNotificationsAction deleteConsultantEventNotificationsAction;

  @Mock private EventNotificationRepository eventNotificationRepository;

  @Test
  void execute_Should_deleteAllEventNotificationsOfConsultant() {
    Consultant consultant = new Consultant();
    consultant.setId("consultant-id");
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, emptyList());

    this.deleteConsultantEventNotificationsAction.execute(workflowDTO);

    verify(this.eventNotificationRepository, times(1)).deleteByRecipientUserId("consultant-id");
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  void execute_Should_addWorkflowError_When_deletionFails() {
    Consultant consultant = new Consultant();
    consultant.setId("consultant-id");
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());
    doThrow(new RuntimeException("db down"))
        .when(this.eventNotificationRepository)
        .deleteByRecipientUserId("consultant-id");

    this.deleteConsultantEventNotificationsAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(1));
    DeletionWorkflowError error = workflowDTO.getDeletionWorkflowErrors().get(0);
    assertThat(error.getDeletionSourceType(), is(CONSULTANT));
    assertThat(error.getDeletionTargetType(), is(DATABASE));
    assertThat(error.getIdentifier(), is("consultant-id"));
    assertThat(error.getTimestamp(), notNullValue());
  }
}
