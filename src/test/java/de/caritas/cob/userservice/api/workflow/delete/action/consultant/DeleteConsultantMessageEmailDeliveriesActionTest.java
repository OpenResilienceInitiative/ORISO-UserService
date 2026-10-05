package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteConsultantMessageEmailDeliveriesActionTest {
  @InjectMocks private DeleteConsultantMessageEmailDeliveriesAction action;
  @Mock private ReplyEmailDeliveryRepository repository;

  @Test
  void hardDeletionClearsOnlyConsultantDeliveryClaims() {
    var consultant = new Consultant();
    consultant.setId("consultant-id");
    consultant.setMatrixUserId("@consultant:matrix.example");
    var target = new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());

    action.execute(target);

    verify(repository)
        .deleteByRecipientKindAndRecipientUserId(RecipientKind.CONSULTANT, "consultant-id");
    verify(repository)
        .deleteByRecipientKindAndRecipientUserId(RecipientKind.FEEDBACK, "consultant-id");
    verify(repository)
        .deleteByRecipientKindAndRecipientUserId(RecipientKind.FEEDBACK_INTENT, "consultant-id");
    verify(repository).deleteBySourceMatrixUserId("@consultant:matrix.example");
    assertThat(target.getDeletionWorkflowErrors()).isEmpty();
  }
}
