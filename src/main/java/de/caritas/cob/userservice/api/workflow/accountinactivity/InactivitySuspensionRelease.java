package de.caritas.cob.userservice.api.workflow.accountinactivity;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Snapshot;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ephemeral proof of a completed prior lineage, rechecked before the existing claim is replaced.
 */
final class InactivitySuspensionRelease {
  private record Access(boolean enabled, boolean restored, boolean deletionAuthorized) {}

  private record Metadata(String owner, String wal, String body, String receipt) {}

  private record State(
      InactivityClaimJournal.Saved saved,
      String source,
      String owner,
      String wal,
      String suspensionBody,
      String suspensionReceipt,
      Access access,
      Map<String, Boolean> matrix) {}

  private final JdbcTemplate jdbc;
  private final InactivityClaimJournal journal;
  private final Snapshot row;
  private final State original;
  private final Thread thread = Thread.currentThread();
  private Object transactionResource;
  private boolean consumed;

  private InactivitySuspensionRelease(
      JdbcTemplate jdbc, InactivityClaimJournal journal, Snapshot row, State original) {
    this.jdbc = jdbc;
    this.journal = journal;
    this.row = row;
    this.original = original;
  }

  static InactivitySuspensionRelease prepare(
      JdbcTemplate jdbc,
      InactivityClaimJournal journal,
      AccountInactivityIdentityTransport transport,
      Snapshot row) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || row.status() != AccountInactivityService.Status.ACTIVE) throw invalid();
    var state = state(jdbc, journal, row, false);
    String custody = state.saved().suspensionSource();
    if (custody == null) {
      if (state.saved().work() != null
          || state.saved().phase() != null
          || state.saved().body() != null
          || state.saved().receipt() != null
          || state.saved().suspension() != null
          || state.owner() != null
          || state.wal() != null
          || state.suspensionBody() != null
          || state.suspensionReceipt() != null
          || state.access() != null
          || !state.matrix().isEmpty()) throw invalid();
    } else {
      transport.verifySuspensionCustody(custody);
      JsonNode saved = journal.decode(custody), prior = saved.path("originalRow");
      if (!row.identityId().equals(prior.path("identityId").textValue())
          || !Objects.equals(
              row.tenantId() == null ? null : row.tenantId().toString(),
              prior.path("tenantId").isNull() ? null : prior.path("tenantId").textValue())
          || !state.source().equals(journal.encode(saved.path("source")))
          || !state.source().equals(state.saved().source())
          || state.access() == null
          || state.access().deletionAuthorized()) throw invalid();
      var expectedIds = new TreeSet<String>();
      for (String kind : List.of("user", "consultant"))
        for (var source : saved.path("source").path(kind))
          if (!source.path("matrix_user_id").isNull())
            expectedIds.add(source.path("matrix_user_id").textValue());
      if (!expectedIds.equals(state.matrix().keySet())) throw invalid();
      String phase = saved.path("phase").textValue();
      if ("NO_EFFECT".equals(phase)) {
        if (!"NO_EFFECT".equals(state.saved().phase())
            || !state.saved().work().equals(prior.path("workId").textValue())
            || !journal.decode(state.saved().claimed()).equals(prior)
            || !journal.decode(state.saved().body()).equals(saved.path("nativeBody"))
            || !Objects.equals(state.saved().receipt(), saved.path("nativeReceipt").textValue())
            || !state.saved().work().equals(state.owner())
            || state.access().restored()
            || state.wal() != null) throw invalid();
        policy(row, prior);
      } else if ("ACKNOWLEDGED".equals(phase)) {
        if (!"ACKNOWLEDGED".equals(state.saved().phase())
            || !state.access().restored()
            || !prior.path("workId").textValue().equals(state.owner())
            || !Objects.equals(state.owner(), state.saved().suspension())
            || state.suspensionBody() == null
            || state.suspensionReceipt() == null
            || !journal.decode(state.suspensionBody()).equals(saved.path("nativeBody"))
            || !state.suspensionReceipt().equals(saved.path("nativeReceipt").textValue()))
          throw invalid();
        bounded(state.saved().body(), 32768);
        bounded(state.saved().receipt(), 32768);
        transport.verifyCompletedInactivityRestoreReceipt(
            state.saved().body(), state.saved().receipt(), state.saved().claimed());
        var restore = journal.decode(state.saved().body());
        var originalRestore = journal.decode(state.saved().claimed());
        restoredRow(row, state, prior, originalRestore, restore);
        if (state.access().enabled()
            != restore.path("change").path("restoreEnabled").booleanValue()) throw invalid();
        bounded(state.wal(), 8192);
        restoredWal(journal.decode(state.wal()), state);
      } else throw invalid();
    }
    return new InactivitySuspensionRelease(jdbc, journal, row, state);
  }

  void recheckAndCleanup(Snapshot actual) {
    if (thread != Thread.currentThread()
        || transactionResource != null
        || consumed
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || !row.equals(actual)) throw invalid();
    Object resource = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
    if (resource == null || !original.equals(state(jdbc, journal, row, true))) throw invalid();
    if (original.saved().suspensionSource() != null) {
      if (jdbc.update(
              "update account_inactivity set access_work_id=null,suspension_operation_id=null,suspension_body=null,suspension_receipt=null where identity_id=? and work_id=? and access_work_id=?",
              row.identityId(),
              original.saved().work(),
              original.owner())
          != 1) throw invalid();
      jdbc.update(
          "delete from account_inactivity_matrix_state where identity_id=?", row.identityId());
      if (jdbc.update(
              "delete from account_inactivity_access_state where identity_id=?", row.identityId())
          != 1) throw invalid();
    }
    transactionResource = resource;
  }

  void requireCapture(InactivityClaimJournal.Saved claimed) {
    if (thread != Thread.currentThread()
        || consumed
        || transactionResource == null
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || transactionResource
            != TransactionSynchronizationManager.getResource(jdbc.getDataSource())
        || !row.identityId().equals(claimed.id())
        || Objects.equals(original.saved().work(), claimed.work())
        || !original.source().equals(claimed.source())
        || !Objects.equals(original.saved().suspensionSource(), claimed.suspensionSource()))
      throw invalid();
    consumed = true;
  }

  private static State state(
      JdbcTemplate jdbc, InactivityClaimJournal journal, Snapshot row, boolean lock) {
    var saved = journal.saved(row.identityId(), lock);
    var metadata =
        jdbc.query(
            "select access_work_id,restore_effects_json,suspension_body,suspension_receipt from account_inactivity where identity_id=?"
                + (lock ? " for update" : ""),
            (r, n) -> new Metadata(r.getString(1), r.getString(2), r.getString(3), r.getString(4)),
            row.identityId());
    if (metadata.size() != 1) throw invalid();
    var data = metadata.getFirst();
    var access =
        jdbc.query(
            "select keycloak_enabled,restored,deletion_authorized from account_inactivity_access_state where identity_id=?"
                + (lock ? " for update" : ""),
            (r, n) -> {
              boolean enabled = r.getBoolean(1);
              if (r.wasNull()) throw invalid();
              boolean restored = r.getBoolean(2);
              if (r.wasNull()) throw invalid();
              boolean deleting = r.getBoolean(3);
              if (r.wasNull()) throw invalid();
              return new Access(enabled, restored, deleting);
            },
            row.identityId());
    if (access.size() > 1) throw invalid();
    var matrix = new TreeMap<String, Boolean>();
    jdbc.query(
        "select matrix_user_id,original_locked from account_inactivity_matrix_state where identity_id=? order by matrix_user_id"
            + (lock ? " for update" : ""),
        r -> {
          String id = r.getString(1);
          boolean locked = r.getBoolean(2);
          if (id == null
              || id.isBlank()
              || id.length() > 255
              || r.wasNull()
              || matrix.put(id, locked) != null) throw invalid();
        },
        row.identityId());
    if (matrix.size() > 2) throw invalid();
    return new State(
        saved,
        journal.source(row.identityId(), row.tenantId(), lock),
        data.owner(),
        data.wal(),
        data.body(),
        data.receipt(),
        access.isEmpty() ? null : access.getFirst(),
        Collections.unmodifiableMap(matrix));
  }

  private static void restoredRow(
      Snapshot row, State state, JsonNode suspension, JsonNode original, JsonNode body) {
    keys(
        original,
        Set.of(
            "identityId",
            "tenantId",
            "assignedMonths",
            "revision",
            "lastActivityMicros",
            "dueAtMicros",
            "status",
            "attempts",
            "lastError",
            "workId",
            "action",
            "initiator",
            "suspensionOperationId",
            "claimedAtMicros"));
    if (!"RESTORE".equals(original.path("action").textValue())
        || !"REACTIVATING".equals(original.path("status").textValue())
        || !state.saved().work().equals(original.path("workId").textValue())
        || !state.saved().work().equals(body.path("operationId").textValue())
        || !row.identityId().equals(body.path("target").path("accountId").textValue())
        || !original.path("tenantId").equals(body.path("target").path("tenantId"))
        || !state.owner().equals(original.path("suspensionOperationId").textValue())
        || !state.owner().equals(body.path("workflow").path("suspensionOperationId").textValue())
        || state.saved().claimedAt() == null
        || !original.path("claimedAtMicros").isIntegralNumber()
        || !original.path("claimedAtMicros").canConvertToLong()
        || original.path("claimedAtMicros").longValue() <= 0
        || !original.path("attempts").isIntegralNumber()
        || !original.path("attempts").canConvertToInt()
        || original.path("attempts").intValue() < 0
        || !(original.path("lastError").isNull()
            || original.path("lastError").isTextual()
                && original.path("lastError").textValue().length() <= 1024
                && original
                    .path("lastError")
                    .textValue()
                    .matches("[A-Z][A-Z0-9_]*(?::[A-Z][A-Z0-9_]*)?"))
        || original.path("claimedAtMicros").longValue()
            != InactivityClaimJournal.micros(state.saved().claimedAt())) throw invalid();
    for (String name :
        List.of(
            "identityId",
            "tenantId",
            "assignedMonths",
            "revision",
            "lastActivityMicros",
            "dueAtMicros")) if (!original.path(name).equals(suspension.path(name))) throw invalid();
    var workflow = body.path("workflow");
    if (original.path("revision").longValue() != workflow.path("revision").longValue()
        || original.path("assignedMonths").intValue() != workflow.path("assignedMonths").intValue()
        || original.path("lastActivityMicros").longValue() / 1_000_000L
            != workflow.path("lastActivity").longValue()
        || original.path("dueAtMicros").longValue() / 1_000_000L
            != workflow.path("dueAt").longValue()) throw invalid();
    var initiator = original.path("initiator");
    var origin = body.path("target").path("origin");
    if ("SCHEDULED".equals(initiator.path("kind").textValue())) {
      keys(initiator, Set.of("kind"));
      if (!"INACTIVITY".equals(origin.path("kind").textValue())) throw invalid();
    } else {
      keys(initiator, Set.of("kind", "subject", "issuer", "client"));
      if (!"HUMAN_ADMIN".equals(initiator.path("kind").textValue())
          || !"HUMAN_ADMIN".equals(origin.path("kind").textValue())
          || !initiator.path("subject").equals(origin.path("humanSubject"))
          || !initiator.path("issuer").equals(origin.path("humanIssuer"))
          || !initiator.path("client").equals(origin.path("humanClient"))) throw invalid();
    }
    policy(row, suspension);
  }

  private static void policy(Snapshot row, JsonNode original) {
    if (row.assignedMonths() != original.path("assignedMonths").intValue()
        || row.revision() != original.path("revision").longValue()
        || InactivityClaimJournal.micros(row.lastActivity())
            < original.path("lastActivityMicros").longValue()
        || !row.lastActivity()
            .atZone(ZoneOffset.UTC)
            .plusMonths(row.assignedMonths())
            .toInstant()
            .equals(row.dueAt())) throw invalid();
  }

  private static void restoredWal(JsonNode wal, State state) {
    keys(wal, Set.of("v", "workId", "suspensionOperationId", "matrix", "media"));
    if (!wal.path("v").isIntegralNumber()
        || !wal.path("v").canConvertToInt()
        || wal.path("v").intValue() != 1
        || !state.saved().work().equals(wal.path("workId").textValue())
        || !state.owner().equals(wal.path("suspensionOperationId").textValue())
        || !wal.path("matrix").isArray()
        || wal.path("matrix").size() != state.matrix().size()) throw invalid();
    int index = 0;
    for (var expected : state.matrix().entrySet()) {
      var item = wal.path("matrix").get(index++);
      keys(item, Set.of("matrixId", "originalLocked", "restorePhase", "compensatePhase"));
      if (!expected.getKey().equals(item.path("matrixId").textValue())
          || !item.path("originalLocked").isBoolean()
          || expected.getValue() != item.path("originalLocked").booleanValue()) throw invalid();
      restored(item);
    }
    keys(wal.path("media"), Set.of("restorePhase", "compensatePhase"));
    restored(wal.path("media"));
  }

  private static void restored(JsonNode item) {
    if (!"ACKNOWLEDGED".equals(item.path("restorePhase").textValue())
        || !"NOT_STARTED".equals(item.path("compensatePhase").textValue())) throw invalid();
  }

  private static void bounded(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maximum)
      throw invalid();
  }

  private static void keys(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
    var actual = new HashSet<String>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!expected.equals(actual)) throw invalid();
  }

  @Override
  public String toString() {
    return "InactivitySuspensionRelease[private-source=redacted]";
  }

  private static IllegalStateException invalid() {
    return InactivityClaimJournal.invalid();
  }
}
