package de.caritas.cob.userservice.api.actions.session;

import de.caritas.cob.userservice.api.model.Consultant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AsyncAliasMessageCommandExecutor {

  private final PostConsultantDisplayNameChangedAliasMessageCommand
      postConsultantDisplayNameChangedAliasMessageCommand;

  @Async
  public void executeDisplayNameChanged(Consultant consultant) {
    postConsultantDisplayNameChangedAliasMessageCommand.execute(consultant);
  }
}
