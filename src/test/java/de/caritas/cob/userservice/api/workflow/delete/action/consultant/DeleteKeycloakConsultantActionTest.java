package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.KEYCLOAK;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.workflow.delete.action.DeleteKeycloakUserAction;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
public class DeleteKeycloakConsultantActionTest {

  @InjectMocks private DeleteKeycloakConsultantAction deleteKeycloakConsultantAction;

  @Mock private KeycloakService keycloakService;

  private LogbackCaptor consultantActionLogCaptor;
  private LogbackCaptor userActionLogCaptor;

  @BeforeEach
  public void setup() {
    consultantActionLogCaptor = LogbackCaptor.forClass(DeleteKeycloakConsultantAction.class);
    userActionLogCaptor = LogbackCaptor.forClass(DeleteKeycloakUserAction.class);
  }

  @AfterEach
  public void tearDown() {
    consultantActionLogCaptor.detach();
    userActionLogCaptor.detach();
  }

  @Test
  public void
      execute_Should_deleteKeycloakUserAndReturnEmptyList_When_consultantDeletionIsSuccessful() {
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(new Consultant(), emptyList());

    this.deleteKeycloakConsultantAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    verify(this.keycloakService, times(1)).deleteUser(any());
  }

  @Test
  public void
      execute_Should_returnExpectedWorkflowErrorAndLogError_When_consultantDeletionFailes() {
    Consultant consultant = new Consultant();
    consultant.setId("consultantId");
    doThrow(new RuntimeException()).when(this.keycloakService).deleteUser(any());
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());

    this.deleteKeycloakConsultantAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(CONSULTANT));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(KEYCLOAK));
    assertThat(workflowErrors.get(0).getIdentifier(), is("consultantId"));
    assertThat(workflowErrors.get(0).getReason(), is("Unable to delete keycloak user account"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
    assertThat(consultantActionLogCaptor.contains(Level.ERROR, "UserService delete workflow error"))
        .isTrue();
  }

  @Test
  public void execute_Should_notReturnWorkflowErrorIfUserCouldNotBeFoundInKeycloak() {
    Consultant consultant = new Consultant();
    consultant.setId("consultantId");
    doThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND))
        .when(this.keycloakService)
        .deleteUser(any());
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());

    this.deleteKeycloakConsultantAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    assertThat(
            userActionLogCaptor.contains(
                Level.WARN,
                "No user with id consultantId could be found in keycloak, but proceeding with further actions."))
        .isTrue();
  }
}
