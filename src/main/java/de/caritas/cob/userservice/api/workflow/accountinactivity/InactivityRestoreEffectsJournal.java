package de.caritas.cob.userservice.api.workflow.accountinactivity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Finite restore WAL data on the existing owned row; cannot issue a claimed-work capability. */
final class InactivityRestoreEffectsJournal {
  private static final String NOT_STARTED = "NOT_STARTED";
  private static final String STARTED = "STARTED";
  private static final String ACKNOWLEDGED = "ACKNOWLEDGED";
  private static final Set<String> PHASES = Set.of(NOT_STARTED, STARTED, ACKNOWLEDGED);
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final JdbcTemplate jdbc;
  private final TransactionTemplate durable;

  InactivityRestoreEffectsJournal(JdbcTemplate jdbc, TransactionTemplate durable) {
    this.jdbc = jdbc;
    this.durable = durable;
  }

  record Matrix(String id, boolean originalLocked) {}

  List<Matrix> prepare(AccountInactivityService.ClaimedWork work) {
    String source = work.originalSourceJson();
    return durable.execute(ignored -> {
      work.requireOwnedSql();
      var ids = ownedMatrix(jdbc, work, source);
      String raw = raw(jdbc, work);
      if (raw == null) {
        String phase = jdbc.queryForObject("select native_phase from account_inactivity where identity_id=? and work_id=?", String.class, work.identityId(), work.workId());
        if (!"CLAIMED".equals(phase)) throw invalid();
        var wal = JSON.createObjectNode();
        wal.put("v", 1);
        wal.put("workId", work.workId());
        wal.put("suspensionOperationId", work.suspensionOperationId());
        var matrix = wal.putArray("matrix");
        ids.forEach((id, locked) -> {
          var item = matrix.addObject();
          item.put("matrixId", id);
          item.put("originalLocked", locked);
          item.put("restorePhase", NOT_STARTED);
          item.put("compensatePhase", NOT_STARTED);
        });
        var media = wal.putObject("media");
        media.put("restorePhase", NOT_STARTED);
        media.put("compensatePhase", NOT_STARTED);
        save(work, wal, null);
      }
      readOwned(jdbc, work, source);
      return ids.entrySet().stream().map(e -> new Matrix(e.getKey(), e.getValue())).toList();
    });
  }

  boolean start(AccountInactivityService.ClaimedWork work, String matrixId, boolean compensate) {
    return change(work, matrixId, compensate, false);
  }

  void acknowledge(AccountInactivityService.ClaimedWork work, String matrixId, boolean compensate) {
    change(work, matrixId, compensate, true);
  }

  private boolean change(AccountInactivityService.ClaimedWork work, String matrixId, boolean compensate, boolean acknowledge) {
    String source = work.originalSourceJson();
    return Boolean.TRUE.equals(durable.execute(ignored -> {
      work.requireOwnedSql();
      String previous = raw(jdbc, work);
      var wal = readOwned(jdbc, work, source);
      var entry = entry(wal, matrixId);
      String name = compensate ? "compensatePhase" : "restorePhase";
      String current = entry.path(name).textValue();
      if (compensate && !ACKNOWLEDGED.equals(entry.path("restorePhase").textValue())) throw invalid();
      if (!compensate && !NOT_STARTED.equals(entry.path("compensatePhase").textValue())) throw invalid();
      if (ACKNOWLEDGED.equals(current)) return false;
      if (acknowledge && !STARTED.equals(current)) throw invalid();
      entry.put(name, acknowledge ? ACKNOWLEDGED : STARTED);
      save(work, wal, previous);
      return true;
    }));
  }

  static void requireRestoredOwned(JdbcTemplate jdbc, AccountInactivityService.ClaimedWork work, String source) {
    var wal = readOwned(jdbc, work, source);
    for (JsonNode entry : entries(wal))
      if (!ACKNOWLEDGED.equals(entry.path("restorePhase").textValue())
          || !NOT_STARTED.equals(entry.path("compensatePhase").textValue())) throw invalid();
  }

  static void requireCompensatedOwned(JdbcTemplate jdbc, AccountInactivityService.ClaimedWork work, String source) {
    var wal = readOwned(jdbc, work, source);
    for (JsonNode entry : entries(wal))
      if (!ACKNOWLEDGED.equals(entry.path("restorePhase").textValue())
          || !ACKNOWLEDGED.equals(entry.path("compensatePhase").textValue())) throw invalid();
  }

  private void save(AccountInactivityService.ClaimedWork work, ObjectNode wal, String previous) {
    try {
      String value = JSON.writeValueAsString(wal);
      if (value.getBytes(StandardCharsets.UTF_8).length > 8192) throw invalid();
      int count = previous == null
          ? jdbc.update("update account_inactivity set restore_effects_json=? where identity_id=? and work_id=? and restore_effects_json is null", value, work.identityId(), work.workId())
          : jdbc.update("update account_inactivity set restore_effects_json=? where identity_id=? and work_id=? and restore_effects_json=?", value, work.identityId(), work.workId(), previous);
      if (count != 1) throw invalid();
    } catch (java.io.IOException failure) {
      throw invalid();
    }
  }

  private static ObjectNode readOwned(JdbcTemplate jdbc, AccountInactivityService.ClaimedWork work, String source) {
    work.requireOwnedSql();
    var ids = ownedMatrix(jdbc, work, source);
    String value = raw(jdbc, work);
    if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 8192) throw invalid();
    try {
      JsonNode node = JSON.readTree(value);
      keys(node, Set.of("v", "workId", "suspensionOperationId", "matrix", "media"));
      if (!node.path("v").isIntegralNumber() || !node.path("v").canConvertToInt() || node.path("v").intValue() != 1
          || !node.path("workId").isTextual() || !work.workId().equals(node.path("workId").textValue())
          || !node.path("suspensionOperationId").isTextual() || !Objects.equals(work.suspensionOperationId(), node.path("suspensionOperationId").textValue())
          || !node.path("matrix").isArray() || node.path("matrix").size() != ids.size()) throw invalid();
      int index = 0;
      for (var expected : ids.entrySet()) {
        var item = node.path("matrix").get(index++);
        keys(item, Set.of("matrixId", "originalLocked", "restorePhase", "compensatePhase"));
        if (!item.path("matrixId").isTextual() || !expected.getKey().equals(item.path("matrixId").textValue())
            || !item.path("originalLocked").isBoolean() || expected.getValue() != item.path("originalLocked").booleanValue()) throw invalid();
        phases(item);
      }
      keys(node.path("media"), Set.of("restorePhase", "compensatePhase"));
      phases(node.path("media"));
      return (ObjectNode) node;
    } catch (java.io.IOException failure) {
      throw invalid();
    }
  }

  private static TreeMap<String, Boolean> ownedMatrix(JdbcTemplate jdbc, AccountInactivityService.ClaimedWork work, String source) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource()))
        || !"RESTORE".equals(work.action()) || work.suspensionOperationId() == null) throw invalid();
    if (!Objects.equals(work.suspensionOperationId(), jdbc.queryForObject("select access_work_id from account_inactivity where identity_id=? and work_id=?", String.class, work.identityId(), work.workId()))) throw invalid();
    var ids = new java.util.TreeSet<String>();
    try {
      var original = JSON.readTree(source);
      for (String type : List.of("user", "consultant")) {
        if (!original.path(type).isArray()) throw invalid();
        for (JsonNode row : original.path(type)) {
          var id = row.path("matrix_user_id");
          if (id.isMissingNode() || id.isNull()) continue;
          if (!id.isTextual() || id.textValue().isBlank() || id.textValue().length() > 255) throw invalid();
          ids.add(id.textValue());
        }
      }
    } catch (java.io.IOException failure) {
      throw invalid();
    }
    if (ids.size() > 2) throw invalid();
    var captured = new TreeMap<String, Boolean>();
    jdbc.query("select matrix_user_id,original_locked from account_inactivity_matrix_state where identity_id=? order by matrix_user_id", rs -> {
      String id = rs.getString(1);
      boolean locked = rs.getBoolean(2);
      if (rs.wasNull() || captured.put(id, locked) != null) throw invalid();
    }, work.identityId());
    if (!ids.equals(captured.keySet())) throw invalid();
    return captured;
  }

  private static String raw(JdbcTemplate jdbc, AccountInactivityService.ClaimedWork work) {
    return jdbc.queryForObject("select restore_effects_json from account_inactivity where identity_id=? and work_id=?", String.class, work.identityId(), work.workId());
  }

  private static ObjectNode entry(ObjectNode wal, String id) {
    if (id == null) return (ObjectNode) wal.path("media");
    for (JsonNode node : wal.path("matrix"))
      if (id.equals(node.path("matrixId").textValue())) return (ObjectNode) node;
    throw invalid();
  }

  private static List<JsonNode> entries(ObjectNode wal) {
    var result = new ArrayList<JsonNode>();
    wal.path("matrix").forEach(result::add);
    result.add(wal.path("media"));
    return result;
  }

  private static void phases(JsonNode node) {
    for (String name : List.of("restorePhase", "compensatePhase"))
      if (!node.path(name).isTextual() || !PHASES.contains(node.path(name).textValue())) throw invalid();
    if (!NOT_STARTED.equals(node.path("compensatePhase").textValue()) && !ACKNOWLEDGED.equals(node.path("restorePhase").textValue())) throw invalid();
  }

  private static void keys(JsonNode node, Set<String> expected) {
    if (!node.isObject()) throw invalid();
    var actual = new java.util.HashSet<String>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalid();
  }

  private static IllegalStateException invalid() { return InactivityClaimJournal.invalid(); }
}
