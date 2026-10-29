package de.caritas.cob.userservice.api.adapters.persistence.creation;

import de.caritas.cob.userservice.api.port.out.CreationOperationStore;
import de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** No entity merge, foreign-key ownership, provider lookup or upsert can authorize dispatch. */
@Repository
public class JdbcCreationOperationStore implements CreationOperationStore {
  private final JdbcTemplate jdbc;

  public JdbcCreationOperationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int insert(Receipt r) {
    return jdbc.update(
        "INSERT INTO account_creation_attempt (receipt_id, operation_key, owner_id, tenant_id, agency_ids, creation_role, fingerprint_version, request_fingerprint, receipt_version, created_at, updated_at, status, dispatch, workflow, consultant_row, onboarding, compensation, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        r.operationId().toString(),
        r.operationKey().toString(),
        r.ownerId(),
        r.scope().tenantId(),
        agencyJson(r.scope().agencyIds()),
        r.scope().role(),
        r.fingerprintVersion(),
        r.requestFingerprint(),
        r.version(),
        utc(r.createdAt()),
        utc(r.updatedAt()),
        "PENDING",
        r.dispatch().name(),
        r.workflow().name(),
        r.row().name(),
        r.onboarding().name(),
        r.compensation().name(),
        r.reason().name());
  }

  public Optional<Receipt> findByKey(UUID key) {
    return jdbc
        .query(
            "SELECT * FROM account_creation_attempt WHERE operation_key = ? AND receipt_id IS NOT NULL",
            this::map,
            key.toString())
        .stream()
        .findFirst();
  }

  public Optional<Receipt> findById(UUID id) {
    return jdbc
        .query(
            "SELECT * FROM account_creation_attempt WHERE receipt_id = ?", this::map, id.toString())
        .stream()
        .findFirst();
  }

  public int compareAndSet(Receipt r, long expectedVersion) {
    return jdbc.update(
        "UPDATE account_creation_attempt SET receipt_version = ?, updated_at = ?, dispatched_at = ?, captured_at = ?, native_identity_id = ?, status = ?, dispatch = ?, workflow = ?, consultant_row = ?, onboarding = ?, compensation = ?, reason = ? WHERE receipt_id = ? AND receipt_version = ?",
        r.version(),
        utc(r.updatedAt()),
        utc(r.dispatchedAt()),
        utc(r.capturedAt()),
        r.nativeId(),
        r.nativeId() == null ? "PENDING" : "NATIVE_ID_KNOWN",
        r.dispatch().name(),
        r.workflow().name(),
        r.row().name(),
        r.onboarding().name(),
        r.compensation().name(),
        r.reason().name(),
        r.operationId().toString(),
        expectedVersion);
  }

  private static String agencyJson(List<Long> ids) {
    return ids.toString();
  }

  private static List<Long> agencies(String json) {
    String body = json.substring(1, json.length() - 1).trim();
    return body.isEmpty()
        ? List.of()
        : Arrays.stream(body.split(",")).map(String::trim).map(Long::valueOf).toList();
  }

  private Receipt map(ResultSet rs, int row) throws SQLException {
    return new Receipt(
        UUID.fromString(rs.getString("receipt_id")),
        UUID.fromString(rs.getString("operation_key")),
        rs.getString("owner_id"),
        new Scope(
            rs.getLong("tenant_id"),
            agencies(rs.getString("agency_ids")),
            rs.getString("creation_role")),
        rs.getShort("fingerprint_version"),
        rs.getString("request_fingerprint"),
        rs.getLong("receipt_version"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        instant(rs, "dispatched_at"),
        instant(rs, "captured_at"),
        rs.getString("native_identity_id"),
        DispatchState.valueOf(rs.getString("dispatch")),
        WorkflowState.valueOf(rs.getString("workflow")),
        RowState.valueOf(rs.getString("consultant_row")),
        OnboardingState.valueOf(rs.getString("onboarding")),
        CompensationState.valueOf(rs.getString("compensation")),
        Reason.valueOf(rs.getString("reason")));
  }

  private static LocalDateTime utc(Instant time) {
    return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var time = rs.getObject(column, LocalDateTime.class);
    return time == null ? null : time.toInstant(ZoneOffset.UTC);
  }
}
