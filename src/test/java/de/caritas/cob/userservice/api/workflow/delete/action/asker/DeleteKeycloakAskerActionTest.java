package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
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
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.workflow.delete.action.DeleteKeycloakUserAction;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
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
public class DeleteKeycloakAskerActionTest {

  @InjectMocks private DeleteKeycloakAskerAction deleteKeycloakAskerAction;

  @Mock private KeycloakService keycloakService;

  private LogbackCaptor askerActionLogCaptor;
  private LogbackCaptor userActionLogCaptor;

  @BeforeEach
  public void setup() {
    askerActionLogCaptor = LogbackCaptor.forClass(DeleteKeycloakAskerAction.class);
    userActionLogCaptor = LogbackCaptor.forClass(DeleteKeycloakUserAction.class);
  }

  @AfterEach
  public void tearDown() {
    askerActionLogCaptor.detach();
    userActionLogCaptor.detach();
  }

  @Test
  public void execute_Should_deleteKeycloakUserAndReturnEmptyList_When_userDeletionIsSuccessful() {
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(new User(), emptyList());

    this.deleteKeycloakAskerAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    verify(this.keycloakService, times(1)).deleteUser(any());
  }

  @Test
  public void execute_Should_returnExpectedWorkflowErrorAndLogError_When_userDeletionFailes() {
    User user = new User();
    user.setUserId("userId");
    doThrow(new RuntimeException()).when(this.keycloakService).deleteUser(any());
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    this.deleteKeycloakAskerAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(KEYCLOAK));
    assertThat(workflowErrors.get(0).getIdentifier(), is("userId"));
    assertThat(workflowErrors.get(0).getReason(), is("Unable to delete keycloak user account"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
    assertThat(askerActionLogCaptor.contains(Level.ERROR, "UserService delete workflow error"))
        .isTrue();
  }

  @Test
  public void execute_Should_notReturnWorkflowErrorIfUserCouldNotBeFoundInKeycloak() {
    User user = new User();
    user.setUserId("userId");
    doThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND))
        .when(this.keycloakService)
        .deleteUser(any());
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    this.deleteKeycloakAskerAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    assertThat(
            userActionLogCaptor.contains(
                Level.WARN,
                "No user with id userId could be found in keycloak, but proceeding with further actions."))
        .isTrue();
  }
}
