package de.caritas.cob.userservice.api.actions.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
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

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void executeDisplayNameChanged_Should_delegateToCommand() {
    var consultant = easyRandom.nextObject(Consultant.class);

    executor.executeDisplayNameChanged(consultant, 1L);

    verify(postConsultantDisplayNameChangedAliasMessageCommand).execute(consultant);
  }

  @Test
  void executeDisplayNameChanged_Should_clearTenantContext_After_execution() {
    var consultant = easyRandom.nextObject(Consultant.class);

    executor.executeDisplayNameChanged(consultant, 42L);

    assertThat(TenantContext.getCurrentTenant()).isNull();
  }
}
