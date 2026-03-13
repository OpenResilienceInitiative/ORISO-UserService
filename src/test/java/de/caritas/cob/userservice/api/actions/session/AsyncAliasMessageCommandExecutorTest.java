package de.caritas.cob.userservice.api.actions.session;

import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.model.Consultant;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AsyncAliasMessageCommandExecutorTest {

  @InjectMocks private AsyncAliasMessageCommandExecutor executor;

  @Mock
  private PostConsultantDisplayNameChangedAliasMessageCommand
      postConsultantDisplayNameChangedAliasMessageCommand;

  private final EasyRandom easyRandom = new EasyRandom();

  @Test
  void executeDisplayNameChanged_Should_delegateToCommand() {
    var consultant = easyRandom.nextObject(Consultant.class);

    executor.executeDisplayNameChanged(consultant);

    verify(postConsultantDisplayNameChangedAliasMessageCommand).execute(consultant);
  }
}
