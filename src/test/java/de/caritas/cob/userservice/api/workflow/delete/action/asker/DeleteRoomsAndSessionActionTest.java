package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatDeleteGroupException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DeleteRoomsAndSessionActionTest {

  @Mock private SessionRepository sessionRepository;

  @Mock private SessionDataRepository sessionDataRepository;

  @Mock private RocketChatService rocketChatService;

  @InjectMocks private DeleteSingleRoomAndSessionAction deleteRoomsAndSessionAction;

  @Test
  void performSessionDeletion_Should_ExecuteMethodsInCorrectOrder()
      throws RocketChatDeleteGroupException {
    // given
    Session session = new Session();
    session.setGroupId("group-id");
    session.setFeedbackGroupId("feedback-group-id");

    // when
    deleteRoomsAndSessionAction.performSessionDeletion(session, new ArrayList<>());

    // then
    InOrder inOrder = inOrder(sessionDataRepository, sessionRepository, rocketChatService);

    inOrder.verify(sessionDataRepository).findBySessionId(any());
    inOrder.verify(sessionDataRepository).deleteAll(anyList());
    inOrder.verify(sessionRepository).delete(eq(session));
    inOrder.verify(rocketChatService).deleteGroupAsTechnicalUser(eq("group-id"));
    inOrder.verify(rocketChatService).deleteGroupAsTechnicalUser(eq("feedback-group-id"));

    inOrder.verifyNoMoreInteractions();
  }
}
