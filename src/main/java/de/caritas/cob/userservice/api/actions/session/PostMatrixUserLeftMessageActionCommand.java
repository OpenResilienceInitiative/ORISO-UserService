package de.caritas.cob.userservice.api.actions.session;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Action to notify a Matrix session that the advice seeker left the conversation. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostMatrixUserLeftMessageActionCommand implements ActionCommand<Session> {

  private final @NonNull MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  @Override
  public void execute(Session actionTarget) {
    if (nonNull(actionTarget)) {
      try {
        matrixSessionSystemMessageService.postUserLeftChatMessage(actionTarget);
      } catch (Exception e) {
        log.error("Unable to post Matrix user-left message for session {}", actionTarget.getId());
        log.error(getStackTrace(e));
      }
    }
  }
}
