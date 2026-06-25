package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.DATABASE;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
public class DeleteDatabaseAskerActionTest {

  @InjectMocks private DeleteDatabaseAskerAction deleteDatabaseAskerAction;

  @Mock private UserRepository userRepository;

  @Mock private IdentityTombstoneService identityTombstoneService;

  private final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeleteDatabaseAskerAction.class);
  private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

  @BeforeEach
  public void setup() {
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  public void tearDown() {
    logger.detachAppender(logAppender);
  }

  @Test
  public void execute_Should_returnEmptyList_When_deletionOfUserIsSuccessful() {
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(new User(), emptyList());

    this.deleteDatabaseAskerAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    verify(this.userRepository, times(1)).delete(any());
    assertThat(
        logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR), is(false));
  }

  @Test
  public void execute_Should_returnExpectedWorkflowErrorAndLogError_When_deletionOfUserFails() {
    doThrow(new RuntimeException()).when(this.userRepository).delete(any());
    User user = new User();
    user.setUserId("user id");
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    this.deleteDatabaseAskerAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
    assertThat(workflowErrors.get(0).getIdentifier(), is("user id"));
    assertThat(workflowErrors.get(0).getReason(), is("Unable to delete user"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
    assertThat(
        logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR), is(true));
  }
}
