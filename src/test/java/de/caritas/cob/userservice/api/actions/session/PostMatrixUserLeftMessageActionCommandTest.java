package de.caritas.cob.userservice.api.actions.session;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostMatrixUserLeftMessageActionCommandTest {

  @InjectMocks private PostMatrixUserLeftMessageActionCommand actionCommand;

  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  @Test
  void executeDoesNothingForMissingSession() {
    actionCommand.execute(null);

    verifyNoInteractions(matrixSessionSystemMessageService);
  }

  @Test
  void executePostsMatrixUserLeftMessage() {
    var session = new Session();
    session.setId(42L);

    actionCommand.execute(session);

    verify(matrixSessionSystemMessageService).postUserLeftChatMessage(session);
  }
}
