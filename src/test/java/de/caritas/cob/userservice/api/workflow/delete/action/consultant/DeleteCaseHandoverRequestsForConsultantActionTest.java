package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.DATABASE;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.ArrayList;
import java.util.List;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteCaseHandoverRequestsForConsultantActionTest {

  @InjectMocks
  private DeleteCaseHandoverRequestsForConsultantAction
      deleteCaseHandoverRequestsForConsultantAction;

  @Mock private CaseHandoverRequestRepository caseHandoverRequestRepository;

  private LogbackCaptor logCaptor;

  @BeforeEach
  void setup() {
    logCaptor = LogbackCaptor.forClass(DeleteCaseHandoverRequestsForConsultantAction.class);
  }

  @AfterEach
  void tearDown() {
    logCaptor.detach();
  }

  @Test
  void
      execute_Should_returnEmptyListAndPerformNoDeletion_When_consultantHasNoCaseHandoverRequests() {
    Consultant consultant = new Consultant();
    consultant.setId("consultantId");
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, emptyList());

    this.deleteCaseHandoverRequestsForConsultantAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
    assertThat(logCaptor.events()).isEmpty();
    verify(this.caseHandoverRequestRepository, times(1))
        .findByRequesterConsultantId("consultantId");
    verify(this.caseHandoverRequestRepository, times(1)).findByPreviousConsultantId("consultantId");
  }

  @Test
  void execute_Should_deleteCaseHandoverRequests_When_consultantIsRequesterOrPreviousConsultant() {
    Consultant consultant = new Consultant();
    consultant.setId("consultantId");
    CaseHandoverRequest requesterRequest = new EasyRandom().nextObject(CaseHandoverRequest.class);
    requesterRequest.setId(1L);
    CaseHandoverRequest previousRequest = new EasyRandom().nextObject(CaseHandoverRequest.class);
    previousRequest.setId(2L);
    when(this.caseHandoverRequestRepository.findByRequesterConsultantId("consultantId"))
        .thenReturn(singletonList(requesterRequest));
    when(this.caseHandoverRequestRepository.findByPreviousConsultantId("consultantId"))
        .thenReturn(singletonList(previousRequest));
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, emptyList());

    this.deleteCaseHandoverRequestsForConsultantAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
    ArgumentCaptor<List<CaseHandoverRequest>> captor = ArgumentCaptor.forClass(List.class);
    verify(this.caseHandoverRequestRepository, times(1)).deleteAll(captor.capture());
    assertThat(captor.getValue(), hasSize(2));
  }

  @Test
  void
      execute_Should_deduplicateCaseHandoverRequest_When_consultantIsBothRequesterAndPreviousConsultantOfSameRequest() {
    Consultant consultant = new Consultant();
    consultant.setId("consultantId");
    CaseHandoverRequest sharedRequest = new EasyRandom().nextObject(CaseHandoverRequest.class);
    sharedRequest.setId(1L);
    when(this.caseHandoverRequestRepository.findByRequesterConsultantId("consultantId"))
        .thenReturn(singletonList(sharedRequest));
    when(this.caseHandoverRequestRepository.findByPreviousConsultantId("consultantId"))
        .thenReturn(singletonList(sharedRequest));
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, emptyList());

    this.deleteCaseHandoverRequestsForConsultantAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
    ArgumentCaptor<List<CaseHandoverRequest>> captor = ArgumentCaptor.forClass(List.class);
    verify(this.caseHandoverRequestRepository, times(1)).deleteAll(captor.capture());
    assertThat(captor.getValue(), hasSize(1));
  }

  @Test
  void execute_Should_returnExpectedWorkflowErrorAndLogError_When_deletionFails() {
    Consultant consultant = new Consultant();
    consultant.setId("consultantId");
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());
    doThrow(new RuntimeException()).when(this.caseHandoverRequestRepository).deleteAll(any());

    this.deleteCaseHandoverRequestsForConsultantAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(CONSULTANT));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
    assertThat(workflowErrors.get(0).getIdentifier(), is("consultantId"));
    assertThat(
        workflowErrors.get(0).getReason(),
        is("Could not delete case handover requests for consultant"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
    assertThat(logCaptor.contains(Level.ERROR, "UserService delete workflow error")).isTrue();
  }
}
