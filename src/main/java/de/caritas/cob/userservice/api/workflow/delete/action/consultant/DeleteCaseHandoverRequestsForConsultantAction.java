package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Deletes {@link CaseHandoverRequest}s referencing a {@link Consultant} as requester or previous
 * consultant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteCaseHandoverRequestsForConsultantAction
    implements ActionCommand<ConsultantDeletionWorkflowDTO> {

  private final @NonNull CaseHandoverRequestRepository caseHandoverRequestRepository;

  /**
   * Deletes all {@link CaseHandoverRequest}s referencing the given {@link Consultant} as requester
   * or previous consultant.
   *
   * @param actionTarget the {@link ConsultantDeletionWorkflowDTO} containing the {@link Consultant}
   */
  @Override
  public void execute(ConsultantDeletionWorkflowDTO actionTarget) {
    try {
      var consultantId = actionTarget.getConsultant().getId();
      List<CaseHandoverRequest> caseHandoverRequests =
          Stream.concat(
                  this.caseHandoverRequestRepository
                      .findByRequesterConsultantId(consultantId)
                      .stream(),
                  this.caseHandoverRequestRepository
                      .findByPreviousConsultantId(consultantId)
                      .stream())
              .collect(
                  Collectors.collectingAndThen(
                      Collectors.toMap(CaseHandoverRequest::getId, r -> r, (a, b) -> a),
                      map -> List.copyOf(map.values())));
      this.caseHandoverRequestRepository.deleteAll(caseHandoverRequests);
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      actionTarget
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(CONSULTANT)
                  .deletionTargetType(DeletionTargetType.DATABASE)
                  .identifier(actionTarget.getConsultant().getId())
                  .reason("Could not delete case handover requests for consultant")
                  .timestamp(nowInUtc())
                  .build());
    }
  }
}
