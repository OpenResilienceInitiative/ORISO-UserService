package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.USER_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarMoveProposalRepository;
import de.caritas.cob.userservice.api.port.out.CalendarSyncRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class DeleteConsultantCalendarActionTest {

  private static final String CONSULTANT_ID = "consultant-id";

  @Mock private CalendarSyncRepository calendarSyncRepository;
  @Mock private CalendarBlockerRepository calendarBlockerRepository;
  @Mock private CalendarWorkingHoursRepository calendarWorkingHoursRepository;
  @Mock private CalendarMoveProposalRepository calendarMoveProposalRepository;
  @Mock private PlatformTransactionManager transactionManager;

  private DeleteConsultantCalendarAction action;

  @BeforeEach
  void setUp() {
    action =
        new DeleteConsultantCalendarAction(
            calendarSyncRepository,
            calendarBlockerRepository,
            calendarWorkingHoursRepository,
            calendarMoveProposalRepository,
            transactionManager);
  }

  @Test
  void execute_Should_DeleteEveryCalendarRowOfTheConsultant_InOneTransaction() {
    var workflow = workflow();

    action.execute(workflow);

    verify(calendarSyncRepository).deleteByConsultantId(CONSULTANT_ID);
    verify(calendarBlockerRepository).deleteByConsultantId(CONSULTANT_ID);
    verify(calendarWorkingHoursRepository).deleteByConsultantId(CONSULTANT_ID);
    // Proposals they made and proposals they were asked to decide: one query covers both roles.
    verify(calendarMoveProposalRepository).deleteByConsultantId(CONSULTANT_ID);
    verify(transactionManager).commit(any());
    assertThat(workflow.getDeletionWorkflowErrors()).isEmpty();
  }

  @Test
  void execute_Should_RollBackEveryCalendarRow_When_TheProposalsCannotBeDeleted() {
    var workflow = workflow();
    doThrow(new IllegalStateException("db down"))
        .when(calendarMoveProposalRepository)
        .deleteByConsultantId(CONSULTANT_ID);

    action.execute(workflow);

    verify(transactionManager).rollback(any());
    assertThat(workflow.getDeletionWorkflowErrors()).hasSize(1);
  }

  @Test
  void execute_Should_KeepTheAccountForARetry_When_TheDeletionFails() {
    var workflow = workflow();
    doThrow(new IllegalStateException("db down"))
        .when(calendarBlockerRepository)
        .deleteByConsultantId(CONSULTANT_ID);

    action.execute(workflow);

    verify(transactionManager).rollback(any());
    assertThat(workflow.getDeletionWorkflowErrors()).hasSize(1);
    var error = workflow.getDeletionWorkflowErrors().get(0);
    assertThat(error.getDeletionSourceType()).isEqualTo(CONSULTANT);
    // User content: the account row stays, so the next run of the workflow tries again.
    assertThat(error.getDeletionTargetType()).isEqualTo(USER_CONTENT);
    assertThat(error.getIdentifier()).isEqualTo(CONSULTANT_ID);
    assertThat(error.getTimestamp()).isNotNull();
  }

  private static ConsultantDeletionWorkflowDTO workflow() {
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    return new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());
  }
}
