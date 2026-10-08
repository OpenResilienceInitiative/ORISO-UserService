package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Child-only independent writes deliberately have no parent FK or parent-row reread/lock. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class IdentityCreationEffectWriter {
  private final JdbcTemplate jdbc;

  void started(
      UUID id,
      KeycloakTaskCommands.CreationResult receipt,
      Long tenantId,
      String kind,
      String requested) {
    if (requested == null || requested.isBlank()) throw denied();
    jdbc.update(
        "INSERT INTO identity_creation_effect(id,attempt_id,account_id,tenant_id,execution_claim,effect_kind,state,requested_target) VALUES(?,?,?,?,?,?,'STARTED',?)",
        id.toString(),
        receipt.attemptId().toString(),
        receipt.accountId(),
        tenantId,
        receipt.executionClaim().toString(),
        kind,
        requested);
  }

  void acknowledge(UUID id, String provenance, String target) {
    if (target == null || target.isBlank()) throw denied();
    var rows =
        jdbc.queryForList(
            "SELECT * FROM identity_creation_effect WHERE id=? FOR UPDATE", id.toString());
    if (rows.size() != 1 || !"STARTED".equals(rows.get(0).get("state"))) throw denied();
    var row = rows.get(0);
    if (("MATRIX_USER".equals(row.get("effect_kind"))
            || "APPOINTMENT_CONSULTANT".equals(row.get("effect_kind")))
        && !target.equals(row.get("requested_target"))) throw denied();
    if ("APPOINTMENT_CONSULTANT".equals(row.get("effect_kind"))
        && (!target.equals(row.get("account_id")) || !"CREATED".equals(provenance))) throw denied();
    if (!SetHolder.PROVENANCE.contains(provenance)) throw denied();
    jdbc.update(
        "UPDATE identity_creation_effect SET state='ACKNOWLEDGED',provenance=?,target_id=? WHERE id=? AND state='STARTED'",
        provenance,
        target,
        id.toString());
  }

  void rejected(UUID id) {
    // Only a definitive rejection before mutation can erase uncertainty. Prior-state restoration
    // is already ACKNOWLEDGED and cannot be cleared by a later password error.
    jdbc.update(
        "UPDATE identity_creation_effect SET state='NO_EFFECT' WHERE id=? AND state='STARTED'",
        id.toString());
  }

  private static class SetHolder {
    static final java.util.Set<String> PROVENANCE =
        java.util.Set.of("CREATED", "REACTIVATED_FROM_DEACTIVATED");
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Unproven downstream effect acknowledgment");
  }
}
