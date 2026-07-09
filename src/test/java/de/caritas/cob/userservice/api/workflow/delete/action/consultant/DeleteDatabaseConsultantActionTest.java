package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.DATABASE;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
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
public class DeleteDatabaseConsultantActionTest {

  @InjectMocks private DeleteDatabaseConsultantAction deleteDatabaseConsultantAction;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private SessionRepository sessionRepository;

  @Mock private IdentityTombstoneService identityTombstoneService;

  private final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeleteDatabaseConsultantAction.class);
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
  public void execute_Should_returnEmptyListAndPerformDeletion_When_consultantCanBeDeleted() {
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(new Consultant(), emptyList());

    this.deleteDatabaseConsultantAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    assertThat(
        logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR), is(false));
  }

  @Test
  public void
      execute_Should_returnEmptyListAndPerformDeletionAndUnassignConsultantFromSession_When_consultantCanBeDeleted() {
    Session session = new Session();
    session.setConsultant(new Consultant());
    when(sessionRepository.findByConsultantAndStatusIn(any(), any()))
        .thenReturn(Lists.newArrayList(session));
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(new Consultant(), emptyList());

    this.deleteDatabaseConsultantAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    verify(sessionRepository).findByConsultantAndStatusIn(any(), any());
    verify(sessionRepository).save(session);
    assertNull(session.getConsultant());
    assertThat(workflowErrors, hasSize(0));
    assertThat(
        logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR), is(false));
  }

  @Test
  public void execute_Should_returnExpectedWorkflowErrorAndLogError_When_deletionFails() {
    doThrow(new RuntimeException()).when(this.consultantRepository).delete(any(Consultant.class));
    Consultant consultant = new Consultant();
    consultant.setId("consultantId");
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());

    this.deleteDatabaseConsultantAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(CONSULTANT));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
    assertThat(workflowErrors.get(0).getIdentifier(), is("consultantId"));
    assertThat(workflowErrors.get(0).getReason(), is("Unable to delete consultant in database"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
    assertThat(
        logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR), is(true));
  }

  @Test
  public void
      execute_Should_returnExpectedWorkflowErrorAndLogError_When_unassignmentOfSessionsFails() {
    when(sessionRepository.findByConsultantAndStatusIn(any(), any()))
        .thenReturn(Lists.newArrayList(new Session(), new Session()));
    doThrow(new RuntimeException()).when(this.sessionRepository).save(any());
    Consultant consultant = new Consultant();
    consultant.setId("consultantId");
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());

    this.deleteDatabaseConsultantAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(CONSULTANT));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
    assertThat(workflowErrors.get(0).getIdentifier(), is("consultantId"));
    assertThat(
        workflowErrors.get(0).getReason(),
        is("Unable to unassign consultant from his sessions with state NEW or INITIAL"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
    assertThat(
        logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR), is(true));
  }
}
