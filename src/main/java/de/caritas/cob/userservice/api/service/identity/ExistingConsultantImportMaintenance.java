package de.caritas.cob.userservice.api.service.identity;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.exception.httpresponses.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport.*;
import de.caritas.cob.userservice.api.service.ConsultantImportService.ScopedExistingImport;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/**
 * One configured-file record owns its original role union and complete local relationship callback.
 */
public final class ExistingConsultantImportMaintenance {
  private static final String SOURCE = "CONFIGURED_IMPORT";
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final jakarta.persistence.EntityManagerFactory factory;
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(
              com.fasterxml.jackson.databind.DeserializationFeature
                  .FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private final ExistingConsultantImportTransport transport;
  private final ReviewedOrdinaryGroups groups;
  private final Clock clock;

  public ExistingConsultantImportMaintenance(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      ExistingConsultantImportTransport transport,
      Map<String, Set<String>> groups,
      Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transport = Objects.requireNonNull(transport);
    this.groups = new ReviewedOrdinaryGroups(groups);
    this.clock = Objects.requireNonNull(clock);
    if (!(manager instanceof JpaTransactionManager jpa)
        || jpa.getDataSource() != jdbc.getDataSource()
        || jpa.getEntityManagerFactory() == null) throw unavailable();
    factory = jpa.getEntityManagerFactory();
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Denial-only parser guard: a changed file cannot strand an earlier original command. */
  public void requirePendingConfiguredSource(String sourceDigest) {
    outside();
    for (var original : pendingConfiguredImports()) {
      if (!sourceDigest.equals(original.path("sourceDigest").textValue())) throw unavailable();
    }
  }

  /** A legacy skip is successful only if this exact configured record has no pending custody. */
  public void requireNoPendingSkippedRecord(String sourceDigest, long recordNumber) {
    outside();
    for (var original : pendingConfiguredImports()) {
      if (!sourceDigest.equals(original.path("sourceDigest").textValue())
          || recordNumber == original.path("recordNumber").longValue()) throw unavailable();
    }
  }

  public void requireNoPendingConfiguredImport() {
    outside();
    if (!pendingConfiguredImports().isEmpty()) throw unavailable();
  }

  private List<JsonNode> pendingConfiguredImports() {
    return jdbc
        .queryForList(
            "select original_json from general_admin_identity_intent where human_subject=? and human_issuer=? and human_client=? and status not in ('APPLIED','NO_EFFECT')",
            String.class,
            SOURCE,
            SOURCE,
            SOURCE)
        .stream()
        .map(value -> decode(value, JsonNode.class))
        .toList();
  }

  public void apply(ScopedExistingImport source) {
    outside();
    source.requireJpaOwner(factory);
    source.validateOutsideSql();
    groups.requireConfigured();
    String key = transport.sourceKey(source);
    UUID work =
        UUID.nameUUIDFromBytes(
            ("existing-consultant-import/" + key).getBytes(StandardCharsets.UTF_8));
    Held prior = held(work, false);
    String facts =
        encode(Map.of("kind", "EXISTING_CONSULTANT_IMPORT", "agencies", source.agencies()));
    String sql =
        prior == null
            ? snapshot(source.accountId(), false)
            : encode(decode(prior.original, JsonNode.class).path("originalSql"));
    JsonNode before = decode(sql, JsonNode.class);
    requireIdentity(source, before);
    var original = new TreeMap<String, Object>();
    original.put("v", 1);
    original.put("kind", "EXISTING_CONSULTANT_IMPORT");
    original.put("sourceDigest", source.sourceDigest());
    original.put("recordNumber", source.recordNumber());
    original.put("workId", work.toString());
    original.put("accountId", source.accountId());
    original.put("tenantId", source.tenantId().toString());
    var consultant = decode(before.path("consultant").get(0).textValue(), JsonNode.class);
    Instant born = Instant.parse(consultant.path("create_date").textValue());
    original.put(
        "createdAtMicros",
        Math.addExact(Math.multiplyExact(born.getEpochSecond(), 1000000L), born.getNano() / 1000));
    original.put("record", source.record());
    original.put(
        "agencies",
        source.agencies().stream()
            .map(
                a ->
                    Map.of(
                        "agencyId",
                        a.id().toString(),
                        "requiredRoles",
                        a.requiredRoles().stream().sorted().toList()))
            .toList());
    original.put("originalSql", before);
    original.put("initiator", Map.of("kind", "IMPORT"));
    String originalJson = encode(original);
    var binding = transport.originalBinding(source, originalJson);
    String rowDigest = binding.rowDigest(), requestDigest = binding.requestDigest();
    try (var checked =
        new CheckedImport(source, work, originalJson, sql, facts, rowDigest, requestDigest)) {
      checked.requireCurrent();
      var terminals = pending(checked, false);
      for (var other : terminals) {
        if (!"NO_EFFECT".equals(other.status)) throw conflict();
        transport.verifyStoredNoEffect(
            checked, other.operation, other.digest, frozen(other), other.receipt);
      }
      Held current =
          recover(
              () ->
                  transaction.execute(
                      ignored -> {
                        String now = snapshot(source.accountId(), true);
                        Held found = checkedHeld(checked, true);
                        if (found != null) {
                          requireSnapshot(checked, found, now);
                          return found;
                        }
                        if (!sql.equals(now) || !terminals.equals(pending(checked, true)))
                          throw conflict();
                        var lifecycle =
                            jdbc.queryForList(
                                "select status from account_inactivity where identity_id=? for update",
                                String.class,
                                source.accountId());
                        if (lifecycle.size() > 1
                            || !lifecycle.isEmpty() && !"ACTIVE".equals(lifecycle.getFirst()))
                          throw conflict();
                        var timestamp = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
                        jdbc.update(
                            "insert into general_admin_identity_intent(operation_id,account_id,tenant_id,human_subject,human_issuer,human_client,request_digest,original_json,desired_json,status,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,'PREPARED',?,?)",
                            work.toString(),
                            source.accountId(),
                            source.tenantId(),
                            SOURCE,
                            SOURCE,
                            SOURCE,
                            requestDigest,
                            originalJson,
                            facts,
                            timestamp,
                            timestamp);
                        return checkedHeld(checked, true);
                      }),
              () -> transaction.execute(ignored -> checkedHeld(checked, true)));
      if ("NO_EFFECT".equals(current.status)) {
        transport.verifyNoEffect(checked, frozen(current), current.receipt);
        throw new ConflictException("Configured import was refused without effect");
      }
      if ("APPLIED".equals(current.status)) {
        String receipt = transport.verifyReceipt(checked, frozen(current), current.receipt);
        var replay = transport.execute(checked, frozen(current));
        if (!(replay instanceof ProfileApplied ack)
            || !receipt.equals(transport.verifyReceipt(checked, frozen(current), ack.receipt())))
          throw unavailable();
        checked.requireCurrent();
        return;
      }
      if ("PREPARED".equals(current.status)) {
        var nativeState = transport.read(checked);
        requireProjection(checked, nativeState);
        var transition =
            groups.importUnion(nativeState.effectiveRoles(), Set.copyOf(source.requiredRoles()));
        boolean hasAdmin = !before.path("admin").isEmpty();
        if (hasAdmin != nativeState.effectiveRoles().contains("restricted-agency-admin")
            || hasAdmin != transition.desired().contains("restricted-agency-admin"))
          throw new ConflictException(
              "Configured import cannot change the local Administrator family");
        var command = transport.freeze(checked, nativeState.state(), transition.group());
        String body = encode(command);
        current = change(checked, "PREPARED", "FROZEN", body, null);
      }
      var command = frozen(current);
      var outcome = transport.execute(checked, command);
      if (outcome instanceof ProfileNoEffect noEffect) {
        var verified = transport.verifyNoEffect(checked, command, noEffect.receipt());
        if (!"FROZEN".equals(current.status)) throw unavailable();
        change(checked, "FROZEN", "NO_EFFECT", current.body, verified.receipt());
        throw new ConflictException("Configured import was refused without effect");
      }
      if (!(outcome instanceof ProfileApplied ack)) throw unavailable();
      String receipt = transport.verifyReceipt(checked, command, ack.receipt());
      if ("ACKNOWLEDGED".equals(current.status) && !receipt.equals(current.receipt))
        throw conflict();
      if ("FROZEN".equals(current.status))
        current = change(checked, "FROZEN", "ACKNOWLEDGED", current.body, receipt);
      var nativeState = transport.read(checked);
      requireProjection(checked, nativeState);
      String selected =
          decode(command.body(), JsonNode.class).path("change").path("group").textValue();
      if (!groups
          .importDesired(selected, Set.copyOf(source.requiredRoles()))
          .equals(Set.copyOf(nativeState.effectiveRoles()))) throw conflict();
      source.validateOutsideSql();
      recover(
          () ->
              transaction.execute(
                  ignored -> {
                    Held held = checkedHeld(checked, true);
                    String actual = snapshot(source.accountId(), true);
                    requireSnapshot(checked, held, actual);
                    if ("APPLIED".equals(held.status)) return held;
                    if (!"ACKNOWLEDGED".equals(held.status) || !receipt.equals(held.receipt))
                      throw conflict();
                    TransactionSynchronizationManager.bindResource(source, checked);
                    try {
                      TenantContext.runIn(source.tenantId(), () -> source.apply(checked));
                    } finally {
                      TransactionSynchronizationManager.unbindResource(source);
                    }
                    String after = snapshot(source.accountId(), true);
                    requirePostcondition(checked, after);
                    if (jdbc.update(
                            "update general_admin_identity_intent set status='APPLIED',applied_json=?,updated_at=? where operation_id=? and status='ACKNOWLEDGED' and native_receipt=? and applied_json is null",
                            after,
                            java.sql.Timestamp.from(clock.instant()),
                            work.toString(),
                            receipt)
                        != 1) throw conflict();
                    return checkedHeld(checked, true);
                  }),
          () ->
              transaction.execute(
                  ignored -> {
                    Held held = checkedHeld(checked, true);
                    if (held == null
                        || !"APPLIED".equals(held.status)
                        || !receipt.equals(held.receipt)) throw unavailable();
                    requireSnapshot(checked, held, snapshot(source.accountId(), true));
                    return held;
                  }));
      checked.requireCurrent();
    }
  }

  private void requireIdentity(ScopedExistingImport source, JsonNode before) {
    if (before.path("consultant").size() != 1 || before.path("admin").size() > 1) throw conflict();
    var row = decode(before.path("consultant").get(0).textValue(), JsonNode.class);
    if (!source.accountId().equals(row.path("consultant_id").textValue())
        || row.path("tenant_id").longValue() != source.tenantId()
        || !row.path("delete_date").isNull()
        || !source.record().get("usernameEncoded").equals(row.path("username").textValue()))
      throw conflict();
    if (!before.path("admin").isEmpty()) {
      var admin = decode(before.path("admin").get(0).textValue(), JsonNode.class);
      if (!"AGENCY".equals(admin.path("type").textValue())
          || admin.path("tenant_id").longValue() != source.tenantId())
        throw new ConflictException("Configured import target family is unsupported");
    }
    if (jdbc.queryForObject(
            "select count(*) from user where user_id=?", Integer.class, source.accountId())
        != 0) throw conflict();
  }

  private void requireProjection(CheckedImport checked, Projection state) {
    var row =
        decode(
            decode(checked.sql, JsonNode.class).path("consultant").get(0).textValue(),
            JsonNode.class);
    if (state == null
        || !checked.accountId().equals(state.id())
        || !checked.tenantId().equals(state.tenantId())
        || !checked.source.record().get("username").equals(state.username())
        || !Objects.equals(text(row, "first_name"), state.firstName())
        || !Objects.equals(text(row, "last_name"), state.lastName())
        || !Objects.equals(text(row, "email"), state.email())) throw conflict();
  }

  private static String text(JsonNode node, String field) {
    return node.path(field).isNull() ? null : node.path(field).textValue();
  }

  private String snapshot(String id, boolean lock) {
    return ConsultantProfileSqlSnapshot.capture(jdbc, id, lock, this::encode);
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (java.io.IOException | RuntimeException invalid) {
      throw unavailable();
    }
  }

  private <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (java.io.IOException | RuntimeException invalid) {
      throw unavailable();
    }
  }

  private void outside() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) throw unavailable();
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Configured import remains pending");
  }

  private static ConflictException conflict() {
    return new ConflictException("Configured import original state changed");
  }

  private <T> T recover(java.util.function.Supplier<T> write, java.util.function.Supplier<T> read) {
    try {
      return Objects.requireNonNull(write.get());
    } catch (ConflictException | ForbiddenException refused) {
      throw refused;
    } catch (RuntimeException unknown) {
      return Objects.requireNonNull(read.get());
    }
  }

  private record Held(
      String operation,
      String account,
      Long tenant,
      String subject,
      String issuer,
      String client,
      String digest,
      String original,
      String desired,
      String body,
      String receipt,
      String status,
      String applied,
      LocalDateTime created) {}

  private Held held(UUID id, boolean lock) {
    var rows =
        jdbc.query(
            "select * from general_admin_identity_intent where operation_id=?"
                + (lock ? " for update" : ""),
            (r, n) ->
                new Held(
                    r.getString("operation_id"),
                    r.getString("account_id"),
                    r.getObject("tenant_id", Long.class),
                    r.getString("human_subject"),
                    r.getString("human_issuer"),
                    r.getString("human_client"),
                    r.getString("request_digest"),
                    r.getString("original_json"),
                    r.getString("desired_json"),
                    r.getString("native_body"),
                    r.getString("native_receipt"),
                    r.getString("status"),
                    r.getString("applied_json"),
                    r.getObject("created_at", LocalDateTime.class)),
            id.toString());
    if (rows.size() > 1) throw conflict();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private List<Held> pending(CheckedImport source, boolean lock) {
    var ids =
        jdbc.queryForList(
            "select operation_id from general_admin_identity_intent where account_id=? and operation_id<>? and status<>'APPLIED' order by operation_id"
                + (lock ? " for update" : ""),
            String.class,
            source.accountId(),
            source.work.toString());
    return ids.stream().map(id -> held(UUID.fromString(id), lock)).toList();
  }

  private Held checkedHeld(CheckedImport source, boolean lock) {
    source.source.requireLive();
    Held held = held(source.work, lock);
    if (held == null) return null;
    if (!source.accountId().equals(held.account)
        || !source.tenantId().equals(held.tenant)
        || !SOURCE.equals(held.subject)
        || !SOURCE.equals(held.issuer)
        || !SOURCE.equals(held.client)
        || !source.original.equals(held.original)
        || !source.facts.equals(held.desired)
        || !source.requestDigest.equals(held.digest)
        || !Set.of("PREPARED", "FROZEN", "ACKNOWLEDGED", "APPLIED", "NO_EFFECT")
            .contains(held.status)
        || "PREPARED".equals(held.status) && (held.body != null || held.receipt != null)
        || !"PREPARED".equals(held.status) && held.body == null
        || Set.of("ACKNOWLEDGED", "APPLIED", "NO_EFFECT").contains(held.status)
            && held.receipt == null
        || "FROZEN".equals(held.status) && held.receipt != null
        || "APPLIED".equals(held.status) != (held.applied != null)) throw conflict();
    return held;
  }

  private void requireSnapshot(CheckedImport source, Held held, String actual) {
    if (held == null) throw conflict();
    if ("APPLIED".equals(held.status)) {
      if (!actual.equals(held.applied)) throw conflict();
      requirePostcondition(source, actual);
    } else if (!source.sql.equals(actual)) throw conflict();
  }

  private FrozenProfile frozen(Held held) {
    return decode(held.body, FrozenProfile.class);
  }

  private Held change(CheckedImport scope, String from, String to, String body, String receipt) {
    return recover(
        () ->
            transaction.execute(
                ignored -> {
                  Held prior = checkedHeld(scope, true);
                  requireSnapshot(scope, prior, snapshot(scope.accountId(), true));
                  if (!from.equals(prior.status)) {
                    if (to.equals(prior.status)
                        && Objects.equals(body, prior.body)
                        && Objects.equals(receipt, prior.receipt)) return prior;
                    throw conflict();
                  }
                  if (jdbc.update(
                          "update general_admin_identity_intent set status=?,native_body=?,native_receipt=?,updated_at=? where operation_id=? and status=?",
                          to,
                          body,
                          receipt,
                          java.sql.Timestamp.from(clock.instant()),
                          scope.work.toString(),
                          from)
                      != 1) throw conflict();
                  return checkedHeld(scope, true);
                }),
        () ->
            transaction.execute(
                ignored -> {
                  Held found = checkedHeld(scope, true);
                  if (found == null
                      || !to.equals(found.status)
                      || !Objects.equals(body, found.body)
                      || !Objects.equals(receipt, found.receipt)) throw unavailable();
                  return found;
                }));
  }

  private void requirePostcondition(CheckedImport checked, String actual) {
    var before = decode(checked.sql, JsonNode.class);
    var after = decode(actual, JsonNode.class);
    for (String table : List.of("admin", "admin_agency", "language", "consultant_topic"))
      if (!before.path(table).equals(after.path(table))) throw conflict();
    if (after.path("consultant").size() != 1) throw conflict();
    var old =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            decode(before.path("consultant").get(0).textValue(), JsonNode.class);
    var now =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            decode(after.path("consultant").get(0).textValue(), JsonNode.class);
    boolean team =
        old.path("is_team_consultant").asBoolean()
            || checked.source.agencies().stream()
                .anyMatch(
                    de.caritas.cob.userservice.api.service.ConsultantImportService.ExistingAgency
                        ::team);
    boolean pending = false;
    for (var encoded : before.path("consultant_agency")) {
      var relation = decode(encoded.textValue(), JsonNode.class);
      if (relation.path("delete_date").isNull()
          && "IN_PROGRESS".equals(relation.path("status").textValue())) pending = true;
    }
    String status =
        checked.agenciesToCreate().isEmpty()
            ? old.path("status").textValue()
            : pending ? "IN_PROGRESS" : "CREATED";
    if (now.path("is_team_consultant").asBoolean() != team
        || !status.equals(now.path("status").textValue())) throw conflict();
    for (String field : List.of("update_date", "status", "is_team_consultant")) {
      old.remove(field);
      now.remove(field);
    }
    if (!old.equals(now)) throw conflict();
    var additions = new ArrayList<JsonNode>();
    after.path("consultant_agency").forEach(additions::add);
    for (var row : before.path("consultant_agency")) if (!additions.remove(row)) throw conflict();
    var expected = new HashSet<>(checked.agenciesToCreate());
    String timestamp = java.sql.Timestamp.valueOf(checked.creationDate()).toInstant().toString();
    for (var encoded : additions) {
      var row = decode(encoded.textValue(), JsonNode.class);
      if (row.size() != 8
          || !checked.accountId().equals(row.path("consultant_id").textValue())
          || row.path("tenant_id").longValue() != checked.tenantId()
          || !expected.remove(row.path("agency_id").longValue())
          || row.path("id").longValue() < 1
          || !row.path("delete_date").isNull()
          || !"CREATED".equals(row.path("status").textValue())
          || !timestamp.equals(row.path("create_date").textValue())
          || !timestamp.equals(row.path("update_date").textValue())) throw conflict();
    }
    if (!expected.isEmpty()) throw conflict();
  }

  public final class CheckedImport implements AutoCloseable {
    private final ScopedExistingImport source;
    private final UUID work;
    private final String original, sql, facts, rowDigest, requestDigest;
    private final Thread thread = Thread.currentThread();
    private boolean active = true;

    private CheckedImport(
        ScopedExistingImport source,
        UUID work,
        String original,
        String sql,
        String facts,
        String rowDigest,
        String requestDigest) {
      this.source = source;
      this.work = work;
      this.original = original;
      this.sql = sql;
      this.facts = facts;
      this.rowDigest = rowDigest;
      this.requestDigest = requestDigest;
    }

    private void live() {
      source.requireLive();
      if (!active || thread != Thread.currentThread()) throw unavailable();
    }

    public void requireCurrent() {
      live();
      outside();
      source.validateOutsideSql();
      Held held = checkedHeld(this, false);
      if (held == null) {
        if (!sql.equals(snapshot(accountId(), false))) throw conflict();
      } else requireSnapshot(this, held, snapshot(accountId(), false));
    }

    public String accountId() {
      live();
      return source.accountId();
    }

    public Long tenantId() {
      live();
      return source.tenantId();
    }

    public UUID workId() {
      live();
      return work;
    }

    public String requestDigest() {
      live();
      return requestDigest;
    }

    public Map<String, Object> workflow() {
      live();
      return Map.of(
          "v",
          1,
          "kind",
          "IMPORT",
          "workId",
          work.toString(),
          "sourceDigest",
          source.sourceDigest(),
          "recordNumber",
          source.recordNumber(),
          "rowDigest",
          rowDigest,
          "requiredRoles",
          source.requiredRoles());
    }

    public Set<String> requiredRoles() {
      live();
      return Set.copyOf(source.requiredRoles());
    }

    public Set<Long> agenciesToCreate() {
      live();
      var result = new TreeSet<Long>();
      source.agencies().forEach(a -> result.add(a.id()));
      for (var encoded : decode(sql, JsonNode.class).path("consultant_agency")) {
        var row = decode(encoded.textValue(), JsonNode.class);
        if (row.path("delete_date").isNull()) result.remove(row.path("agency_id").longValue());
      }
      return Set.copyOf(result);
    }

    public LocalDateTime creationDate() {
      live();
      var found = held(work, false);
      if (found == null || found.created == null) throw unavailable();
      return found.created;
    }

    public void requireLocalCallback(ScopedExistingImport provided) {
      live();
      if (source != provided
          || !TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.getResource(source) != this) throw unavailable();
    }

    @Override
    public void close() {
      active = false;
    }

    @Override
    public String toString() {
      return "CheckedImport[ownership=redacted]";
    }
  }
}
