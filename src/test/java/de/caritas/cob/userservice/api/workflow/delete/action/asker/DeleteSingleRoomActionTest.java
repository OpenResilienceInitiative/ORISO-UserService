package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.powermock.reflect.Whitebox.setInternalState;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatDeleteGroupException;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.model.RocketchatRoomDeletionWorkflowDTO;
import java.util.ArrayList;
import java.util.List;
import org.jeasy.random.EasyRandom;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;

@RunWith(MockitoJUnitRunner.class)
public class DeleteSingleRoomActionTest {

  @InjectMocks private DeleteSingleRoomAction deleteSingleRoomAction;

  @Mock private RocketChatService rocketChatService;

  @Mock private Logger logger;

  @Before
  public void setup() {
    setInternalState(DeleteSingleRoomAction.class, "log", logger);
  }

  @Test
  public void
      execute_Should_returnEmptyListAndPerformAllDeletions_When_userSessionIsDeletedSuccessful()
          throws Exception {
    String rocketChatId = new EasyRandom().nextObject(String.class);
    RocketchatRoomDeletionWorkflowDTO workflowDTO =
        new RocketchatRoomDeletionWorkflowDTO(rocketChatId, emptyList());

    this.deleteSingleRoomAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    verifyNoMoreInteractions(this.logger);
    verify(this.rocketChatService, times(1)).deleteGroupAsTechnicalUser(any());
  }

  @Test
  public void
      execute_Should_returnExpectedWorkflowErrors_When_noUserSessionDeletedStepIsSuccessful()
          throws Exception {
    doThrow(new RocketChatDeleteGroupException(new RuntimeException()))
        .when(this.rocketChatService)
        .deleteGroupAsTechnicalUser(any());

    RocketchatRoomDeletionWorkflowDTO workflowDTO =
        new RocketchatRoomDeletionWorkflowDTO("rocketChatId", new ArrayList<>());

    this.deleteSingleRoomAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    verify(logger, times(1)).error(anyString(), any(Exception.class));
  }
}
