package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublishConsultantDeletedEventActionTest {

  private static final String CONSULTANT_ID = "2d18c0de-4a8e-4b8a-9f43-6c0a0c7d1f10";

  @InjectMocks private PublishConsultantDeletedEventAction action;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private LifecycleEventPublisher lifecycleEventPublisher;

  @Test
  void publishesUserDeletedOnceTheAccountRowIsGone() {
    when(consultantRepository.findById(CONSULTANT_ID)).thenReturn(Optional.empty());

    action.execute(new ConsultantDeletionWorkflowDTO(consultant(), new ArrayList<>()));

    verify(lifecycleEventPublisher).userDeleted(2L, CONSULTANT_ID, "consultant");
  }

  @Test
  void publishesNothingWhileTheAccountRowIsKept() {
    var consultant = consultant();
    when(consultantRepository.findById(CONSULTANT_ID)).thenReturn(Optional.of(consultant));

    action.execute(new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>()));

    verifyNoInteractions(lifecycleEventPublisher);
  }

  private static Consultant consultant() {
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setTenantId(2L);
    return consultant;
  }
}
