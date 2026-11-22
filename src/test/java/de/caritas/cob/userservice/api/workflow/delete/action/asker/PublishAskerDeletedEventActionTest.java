package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublishAskerDeletedEventActionTest {

  private static final String ASKER_ID = "8d3c3c2e-4a8e-4b8a-9f43-6c0a0c7d1f10";

  @InjectMocks private PublishAskerDeletedEventAction action;

  @Mock private UserRepository userRepository;

  @Mock private LifecycleEventPublisher lifecycleEventPublisher;

  @Test
  void publishesUserDeletedOnceTheAccountRowIsGone() {
    var user = asker();
    when(userRepository.findById(ASKER_ID)).thenReturn(Optional.empty());

    action.execute(new AskerDeletionWorkflowDTO(user, new ArrayList<>()));

    verify(lifecycleEventPublisher).userDeleted(4L, ASKER_ID, "asker");
  }

  @Test
  void publishesNothingWhileTheAccountRowIsKept() {
    var user = asker();
    when(userRepository.findById(ASKER_ID)).thenReturn(Optional.of(user));

    action.execute(new AskerDeletionWorkflowDTO(user, new ArrayList<>()));

    verifyNoInteractions(lifecycleEventPublisher);
  }

  @Test
  void ignoresATargetWithoutUser() {
    action.execute(new AskerDeletionWorkflowDTO(null, new ArrayList<>()));

    verifyNoInteractions(lifecycleEventPublisher);
    verify(userRepository, org.mockito.Mockito.never()).findById(any());
  }

  private static User asker() {
    var user = new User();
    user.setUserId(ASKER_ID);
    user.setTenantId(4L);
    return user;
  }
}
