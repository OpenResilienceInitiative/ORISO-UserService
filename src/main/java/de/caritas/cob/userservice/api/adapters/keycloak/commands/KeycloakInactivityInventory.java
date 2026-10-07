package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.port.out.IdentityInactivityInventory;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Issues read-only inventory proof from the actual database-owned immutable rollout row. */
@Component
@RequiredArgsConstructor
public final class KeycloakInactivityInventory implements IdentityInactivityInventory {
  private final JdbcTemplate jdbc;
  private final KeycloakTaskCommands commands;

  @Override
  public Page page(int first, int max) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || first < 0
        || max < 1
        || max > 1000)
      throw new AccessDeniedException(
          "Inactivity inventory requires its bounded database-owned rollout transaction");
    var cutoff =
        jdbc.queryForObject(
            "SELECT rollout_at FROM account_inactivity_rollout WHERE id=1 FOR UPDATE",
            LocalDateTime.class);
    if (cutoff == null)
      throw new AccessDeniedException("Immutable inactivity rollout cutoff is missing");
    var instant = cutoff.toInstant(ZoneOffset.UTC);
    return commands.inventory(
        instant, first, max, IdentityCommandAuthorization.inactivityInventory(instant, first, max));
  }
}
