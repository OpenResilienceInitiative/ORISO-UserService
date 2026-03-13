package de.caritas.cob.userservice.api.actions.session;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Async wrapper for {@link PostConsultantDisplayNameChangedAliasMessageCommand}.
 *
 * <p>{@link PostConsultantDisplayNameChangedAliasMessageCommand} implements {@link
 * de.caritas.cob.userservice.api.actions.ActionCommand}, which causes Spring to create a JDK proxy
 * when {@code @Async} is placed directly on the implementing class. That proxy type is incompatible
 * with constructor injection by concrete type. Placing {@code @Async} here avoids the mismatch.
 *
 * <p>The {@code tenantId} must be captured from the calling thread before the async invocation,
 * since {@link TenantContext} is ThreadLocal and would be empty in the async thread.
 */
@Service
@RequiredArgsConstructor
public class AsyncAliasMessageCommandExecutor {

  private final PostConsultantDisplayNameChangedAliasMessageCommand
      postConsultantDisplayNameChangedAliasMessageCommand;

  @Async
  public void executeDisplayNameChanged(Consultant consultant, Long tenantId) {
    try {
      if (tenantId != null) {
        TenantContext.setCurrentTenant(tenantId);
      }
      postConsultantDisplayNameChangedAliasMessageCommand.execute(consultant);
    } finally {
      TenantContext.clear();
    }
  }
}
