package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.IdentityCreationAttempt;
import de.caritas.cob.userservice.api.port.out.OwnedMatrixEffect;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Exact acknowledged downstream references. STARTED is uncertainty, never cleanup authority. */
@Service
@RequiredArgsConstructor
public class IdentityCreationEffects {
  private final IdentityCreationJournalWriter journal;
  private final IdentityCreationEffectWriter writer;
  private final JdbcTemplate jdbc;
  private final MatrixSynapseService matrix;

  @Transactional(propagation = Propagation.MANDATORY)
  public Scope capture(KeycloakTaskCommands.CreationResult receipt) {
    journal.acquireLocalSaga(receipt);
    var row = journal.attemptInSaga(receipt.attemptId());
    if (!Set.of("ASKER", "ANONYMOUS", "CONSULTANT", "CONSULTANT_AGENCY_ADMIN")
        .contains(row.getRegistrationKind())) throw denied();
    return new Scope(receipt, row.getTenantId(), row.getRegistrationKind());
  }

  /** Constructible only under the original receipt's fenced local saga transaction. */
  public final class Scope {
    private final KeycloakTaskCommands.CreationResult receipt;
    private final Long tenantId;
    private final String registrationKind;

    private Scope(
        KeycloakTaskCommands.CreationResult receipt, Long tenantId, String registrationKind) {
      this.receipt = receipt;
      this.tenantId = tenantId;
      this.registrationKind = registrationKind;
    }

    public void requireOwner(String accountId, Long actualTenant) {
      if (!Objects.equals(receipt.accountId(), accountId)
          || !Objects.equals(tenantId, actualTenant)) throw denied();
    }

    public OwnedMatrixEffect user() {
      return observer("MATRIX_USER");
    }

    public OwnedMatrixEffect privateRoom() {
      if (!"ASKER".equals(registrationKind)) throw denied();
      return observer("PRIVATE_ROOM");
    }

    private OwnedMatrixEffect observer(String kind) {
      UUID effectId = UUID.randomUUID();
      return new OwnedMatrixEffect() {
        public void started(String requestedTarget) {
          journal.acquireLocalSaga(receipt); // original lock/nonce, retained through the outer saga
          writer.started(effectId, receipt, tenantId, kind, requestedTarget);
        }

        public void created(String exactTarget) {
          writer.acknowledge(effectId, "CREATED", exactTarget);
        }

        public void restoreDeactivated(String exactTarget) {
          if (!"MATRIX_USER".equals(kind)) throw denied();
          writer.acknowledge(effectId, "REACTIVATED_FROM_DEACTIVATED", exactTarget);
        }

        public void rejectedWithoutEffect() {
          writer.rejected(effectId);
        }
      };
    }
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public void clean(UUID attemptId) {
    IdentityCreationAttempt row = journal.cleanupAttempt(attemptId);
    var effects =
        jdbc.queryForList(
            "SELECT * FROM identity_creation_effect WHERE attempt_id=? ORDER BY effect_kind, id FOR UPDATE",
            attemptId.toString());
    effects.sort(
        Comparator.comparing(effect -> "PRIVATE_ROOM".equals(effect.get("effect_kind")) ? 0 : 1));
    for (var effect : effects) {
      if (!Objects.equals(row.getAccountId(), effect.get("account_id"))
          || !Objects.equals(
              row.getTenantId(),
              effect.get("tenant_id") == null
                  ? null
                  : ((Number) effect.get("tenant_id")).longValue())) throw denied();
      String state = (String) effect.get("state");
      if (Set.of("NO_EFFECT", "CLEANED").contains(state)) continue;
      if (!"ACKNOWLEDGED".equals(state))
        throw new IllegalStateException(
            "Unacknowledged Matrix outcome requires ownership reconciliation");
      String target = (String) effect.get("target_id");
      if (target == null || target.isBlank()) throw denied();
      boolean cleaned;
      if ("PRIVATE_ROOM".equals(effect.get("effect_kind"))
          && "CREATED".equals(effect.get("provenance"))) cleaned = matrix.purgeRoom(target);
      else if ("MATRIX_USER".equals(effect.get("effect_kind"))
          && "CREATED".equals(effect.get("provenance"))) cleaned = matrix.deactivateUser(target);
      else if ("MATRIX_USER".equals(effect.get("effect_kind"))
          && "REACTIVATED_FROM_DEACTIVATED".equals(effect.get("provenance")))
        cleaned = matrix.restoreDeactivatedUser(target);
      else throw denied();
      if (!cleaned) throw new IllegalStateException("Owned Matrix cleanup remains retryable");
      jdbc.update(
          "UPDATE identity_creation_effect SET state='CLEANED' WHERE id=? AND state='ACKNOWLEDGED'",
          effect.get("id"));
    }
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Downstream effect exceeds its owned creation receipt");
  }
}
