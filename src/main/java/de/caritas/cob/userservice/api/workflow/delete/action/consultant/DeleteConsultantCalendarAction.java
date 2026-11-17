package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.CalendarBlockerRepository;
import de.caritas.cob.userservice.api.port.out.CalendarBookingSettingRepository;
import de.caritas.cob.userservice.api.port.out.CalendarConsultationHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarMoveProposalRepository;
import de.caritas.cob.userservice.api.port.out.CalendarSyncRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingDayExceptionRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursRepository;
import de.caritas.cob.userservice.api.port.out.CalendarWorkingHoursSettingRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the calendar of a consultant (ADR-032): the calendar connections with the private address
 * of their own calendar, their blockers with notes and imported busy times, their working hours
 * with the exceptions on single dates, their holiday region, and the move proposals they made or
 * were asked to decide.
 *
 * <p>None of these tables has a foreign key to the consultant, so without this action the rows
 * would outlive the account. All go in one transaction. A failure counts as user content, so the
 * account row stays and the next run of the deletion workflow tries again.
 */
@Slf4j
@Component
public class DeleteConsultantCalendarAction
    implements ActionCommand<ConsultantDeletionWorkflowDTO> {

  private final CalendarSyncRepository calendarSyncRepository;
  private final CalendarBlockerRepository calendarBlockerRepository;
  private final CalendarWorkingHoursRepository calendarWorkingHoursRepository;
  private final CalendarWorkingDayExceptionRepository calendarWorkingDayExceptionRepository;
  private final CalendarWorkingHoursSettingRepository calendarWorkingHoursSettingRepository;
  private final CalendarMoveProposalRepository calendarMoveProposalRepository;
  private final CalendarConsultationHoursRepository calendarConsultationHoursRepository;
  private final CalendarBookingSettingRepository calendarBookingSettingRepository;
  private final TransactionTemplate transaction;

  public DeleteConsultantCalendarAction(
      CalendarSyncRepository calendarSyncRepository,
      CalendarBlockerRepository calendarBlockerRepository,
      CalendarWorkingHoursRepository calendarWorkingHoursRepository,
      CalendarWorkingDayExceptionRepository calendarWorkingDayExceptionRepository,
      CalendarWorkingHoursSettingRepository calendarWorkingHoursSettingRepository,
      CalendarMoveProposalRepository calendarMoveProposalRepository,
      CalendarConsultationHoursRepository calendarConsultationHoursRepository,
      CalendarBookingSettingRepository calendarBookingSettingRepository,
      PlatformTransactionManager transactionManager) {
    this.calendarSyncRepository = calendarSyncRepository;
    this.calendarBlockerRepository = calendarBlockerRepository;
    this.calendarWorkingHoursRepository = calendarWorkingHoursRepository;
    this.calendarWorkingDayExceptionRepository = calendarWorkingDayExceptionRepository;
    this.calendarWorkingHoursSettingRepository = calendarWorkingHoursSettingRepository;
    this.calendarMoveProposalRepository = calendarMoveProposalRepository;
    this.calendarConsultationHoursRepository = calendarConsultationHoursRepository;
    this.calendarBookingSettingRepository = calendarBookingSettingRepository;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * Deletes every calendar row of the {@link Consultant} being deleted.
   *
   * @param actionTarget the {@link ConsultantDeletionWorkflowDTO} containing the consultant
   */
  @Override
  public void execute(ConsultantDeletionWorkflowDTO actionTarget) {
    var consultantId = actionTarget.getConsultant().getId();
    try {
      transaction.executeWithoutResult(
          status -> {
            calendarSyncRepository.deleteByConsultantId(consultantId);
            calendarBlockerRepository.deleteByConsultantId(consultantId);
            calendarWorkingHoursRepository.deleteByConsultantId(consultantId);
            calendarWorkingDayExceptionRepository.deleteByConsultantId(consultantId);
            calendarWorkingHoursSettingRepository.deleteByConsultantId(consultantId);
            calendarConsultationHoursRepository.deleteByConsultantId(consultantId);
            calendarBookingSettingRepository.deleteByConsultantId(consultantId);
            calendarMoveProposalRepository.deleteByConsultantId(consultantId);
          });
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      actionTarget
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(CONSULTANT)
                  .deletionTargetType(DeletionTargetType.USER_CONTENT)
                  .identifier(consultantId)
                  .reason("Could not delete the calendar")
                  .timestamp(nowInUtc())
                  .build());
    }
  }
}
