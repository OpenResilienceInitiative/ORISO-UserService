package de.caritas.cob.userservice.api.service.identity;

import com.fasterxml.jackson.databind.*;
import de.caritas.cob.userservice.api.exception.httpresponses.*;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport.*;
import de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance.CheckedAccount;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** A fixed tenant-then-profile edit; the original human owner alone can issue its checked frame. */
final class PureTenantAdminEdit {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final HumanAdminMaintenanceTransport nativeCommands;
  private final Clock clock;
  private final ObjectMapper json =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  PureTenantAdminEdit(
      JdbcTemplate jdbc,
      TransactionTemplate transaction,
      HumanAdminMaintenanceTransport nativeCommands,
      Clock clock) {
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.nativeCommands = nativeCommands;
    this.clock = clock;
  }

  static UUID child(UUID parent, boolean profile) {
    return UUID.nameUUIDFromBytes(
        ("tenant-admin-edit/v1/" + parent + "/" + (profile ? "profile" : "tenant"))
            .getBytes(StandardCharsets.UTF_8));
  }

  private record Commands(FrozenProfile tenant, FrozenProfile profile) {}

  private record Receipts(String tenant, String profile) {}

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
      String applied) {}

  de.caritas.cob.userservice.api.model.Admin apply(CheckedAccount checked) {
    checked.selectTenantStep(false);
    checked.requireCurrent();
    String original = checked.originalSqlState(), desired = checked.tenantDesiredJson();
    String digest =
        nativeCommands.profileRequestDigest(
            checked, checked.desiredProfile(), checked.workflowId());
    var terminals = pending(checked, false);
    for (var prior : terminals) {
      if (!"NO_EFFECT".equals(prior.status)) throw conflict();
      nativeCommands.verifyStoredProfileNoEffect(
          checked,
          UUID.fromString(prior.operation),
          prior.digest,
          decode(prior.body, FrozenProfile.class),
          prior.receipt);
    }
    Held held =
        recover(
            () ->
                transaction.execute(
                    ignored -> {
                      String current = checked.tenantSnapshot(true);
                      Held found = checked(checked, digest, original, desired, true);
                      if (found != null) {
                        snapshot(checked, found, current);
                        return found;
                      }
                      if (!checked.tenantOriginalSql().equals(current)
                          || !terminals.equals(pending(checked, true))) throw conflict();
                      var timestamp = java.sql.Timestamp.from(clock.instant());
                      jdbc.update(
                          "insert into general_admin_identity_intent(operation_id,account_id,tenant_id,human_subject,human_issuer,human_client,request_digest,original_json,desired_json,status,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,'PREPARED',?,?)",
                          checked.parentWorkflowId().toString(),
                          checked.accountId(),
                          checked.originalTenantId(),
                          checked.humanSubject(),
                          checked.humanIssuer(),
                          checked.humanClient(),
                          digest,
                          original,
                          desired,
                          timestamp,
                          timestamp);
                      return checked(checked, digest, original, desired, true);
                    }),
            () ->
                transaction.execute(ignored -> checked(checked, digest, original, desired, true)));
    if ("NO_EFFECT".equals(held.status)) {
      var refused =
          nativeCommands.verifyProfileNoEffect(
              checked, decode(held.body, FrozenProfile.class), held.receipt);
      throw checked.tenantInitialRefusal(refused.status());
    }
    if ("APPLIED".equals(held.status)) {
      var commands = commands(held);
      var receipts = receipts(held);
      replay(checked, false, commands.tenant, receipts.tenant);
      replay(checked, true, commands.profile, receipts.profile);
      snapshot(checked, held, checked.tenantSnapshot(false));
      return checked.tenantResult();
    }
    if ("PREPARED".equals(held.status)) {
      var state = nativeCommands.read(checked);
      checked.requireTenantProjection(state, false);
      var frozen =
          nativeCommands.freezeProfile(
              checked, checked.desiredProfile(), checked.workflowId(), state.state());
      held = change(checked, digest, original, desired, "PREPARED", "FROZEN", encode(frozen), null);
    }
    if ("FROZEN".equals(held.status)) {
      var frozen = decode(held.body, FrozenProfile.class);
      var outcome = nativeCommands.executeProfile(checked, frozen);
      if (outcome instanceof ProfileNoEffect refused) {
        var verified = nativeCommands.verifyProfileNoEffect(checked, frozen, refused.receipt());
        change(
            checked,
            digest,
            original,
            desired,
            "FROZEN",
            "NO_EFFECT",
            held.body,
            verified.receipt());
        throw checked.tenantInitialRefusal(verified.status());
      }
      if (!(outcome instanceof ProfileApplied applied)) throw unavailable();
      String receipt = nativeCommands.verifyProfileReceipt(checked, frozen, applied.receipt());
      held =
          change(
              checked,
              digest,
              original,
              desired,
              "FROZEN",
              "TENANT_ACKNOWLEDGED",
              encode(new Commands(frozen, null)),
              encode(new Receipts(receipt, null)));
    }
    var commands = commands(held);
    var receipts = receipts(held);
    // Once the tenant changed, a refusal can never mean that this whole DTO had no effect.
    replay(checked, false, commands.tenant, receipts.tenant);
    checked.selectTenantStep(true);
    if ("TENANT_ACKNOWLEDGED".equals(held.status)) {
      var state = nativeCommands.read(checked);
      checked.requireTenantProjection(state, false);
      var frozen =
          nativeCommands.freezeProfile(
              checked, checked.desiredProfile(), checked.workflowId(), state.state());
      held =
          change(
              checked,
              digest,
              original,
              desired,
              "TENANT_ACKNOWLEDGED",
              "PROFILE_FROZEN",
              encode(new Commands(commands.tenant, frozen)),
              held.receipt);
      commands = commands(held);
    }
    if ("PROFILE_REFUSED".equals(held.status))
      nativeCommands.verifyProfileNoEffect(checked, commands.profile, receipts.profile);
    var outcome = nativeCommands.executeProfile(checked, commands.profile);
    if (outcome instanceof ProfileNoEffect refused) {
      var verified =
          nativeCommands.verifyProfileNoEffect(checked, commands.profile, refused.receipt());
      if ("PROFILE_FROZEN".equals(held.status))
        change(
            checked,
            digest,
            original,
            desired,
            "PROFILE_FROZEN",
            "PROFILE_REFUSED",
            held.body,
            encode(new Receipts(receipts.tenant, verified.receipt())));
      else if (!"PROFILE_REFUSED".equals(held.status)
          || !verified.receipt().equals(receipts.profile)) throw unavailable();
      throw unavailable();
    }
    if (!(outcome instanceof ProfileApplied applied)) throw unavailable();
    String receipt =
        nativeCommands.verifyProfileReceipt(checked, commands.profile, applied.receipt());
    if ("PROFILE_REFUSED".equals(held.status)) throw unavailable();
    if ("PROFILE_FROZEN".equals(held.status))
      held =
          change(
              checked,
              digest,
              original,
              desired,
              "PROFILE_FROZEN",
              "ACKNOWLEDGED",
              held.body,
              encode(new Receipts(receipts.tenant, receipt)));
    else if (!"ACKNOWLEDGED".equals(held.status) || !receipt.equals(receipts.profile))
      throw unavailable();
    checked.requireTenantProjection(nativeCommands.read(checked), true);
    final String originalBody = held.body, originalReceipts = held.receipt;
    recover(
        () ->
            transaction.execute(
                ignored -> {
                  Held current = checked(checked, digest, original, desired, true);
                  snapshot(checked, current, checked.tenantSnapshot(true));
                  if ("APPLIED".equals(current.status)) return current;
                  if (!"ACKNOWLEDGED".equals(current.status)
                      || !originalBody.equals(current.body)
                      || !originalReceipts.equals(current.receipt)) throw conflict();
                  checked.applyTenantCallback();
                  String after = checked.tenantSnapshot(true);
                  postcondition(checked, after);
                  if (jdbc.update(
                          "update general_admin_identity_intent set status='APPLIED',applied_json=?,updated_at=? where operation_id=? and status='ACKNOWLEDGED' and native_body=? and native_receipt=? and applied_json is null",
                          after,
                          java.sql.Timestamp.from(clock.instant()),
                          checked.parentWorkflowId().toString(),
                          originalBody,
                          originalReceipts)
                      != 1) throw conflict();
                  return checked(checked, digest, original, desired, true);
                }),
        () ->
            transaction.execute(
                ignored -> {
                  Held current = checked(checked, digest, original, desired, true);
                  if (current == null
                      || !"APPLIED".equals(current.status)
                      || !originalBody.equals(current.body)
                      || !originalReceipts.equals(current.receipt)) throw unavailable();
                  snapshot(checked, current, checked.tenantSnapshot(true));
                  return current;
                }));
    return checked.tenantResult();
  }

  private void replay(CheckedAccount checked, boolean profile, FrozenProfile body, String receipt) {
    checked.selectTenantStep(profile);
    String original = nativeCommands.verifyProfileReceipt(checked, body, receipt);
    var outcome = nativeCommands.executeProfile(checked, body);
    if (!(outcome instanceof ProfileApplied applied)
        || !original.equals(nativeCommands.verifyProfileReceipt(checked, body, applied.receipt())))
      throw unavailable();
  }

  private Commands commands(Held held) {
    var value = decode(held.body, Commands.class);
    if (value.tenant == null
        || (!"TENANT_ACKNOWLEDGED".equals(held.status) && value.profile == null)) throw conflict();
    return value;
  }

  private Receipts receipts(Held held) {
    var value = decode(held.receipt, Receipts.class);
    if (value.tenant == null
        || Set.of("ACKNOWLEDGED", "APPLIED", "PROFILE_REFUSED").contains(held.status)
            && value.profile == null
        || Set.of("TENANT_ACKNOWLEDGED", "PROFILE_FROZEN").contains(held.status)
            && value.profile != null) throw conflict();
    return value;
  }

  private Held change(
      CheckedAccount checked,
      String digest,
      String original,
      String desired,
      String from,
      String to,
      String body,
      String receipt) {
    return recover(
        () ->
            transaction.execute(
                ignored -> {
                  Held held = checked(checked, digest, original, desired, true);
                  snapshot(checked, held, checked.tenantSnapshot(true));
                  if (!from.equals(held.status)) {
                    if (to.equals(held.status)
                        && Objects.equals(body, held.body)
                        && Objects.equals(receipt, held.receipt)) return held;
                    throw conflict();
                  }
                  if (jdbc.update(
                          "update general_admin_identity_intent set status=?,native_body=?,native_receipt=?,updated_at=? where operation_id=? and status=?",
                          to,
                          body,
                          receipt,
                          java.sql.Timestamp.from(clock.instant()),
                          checked.parentWorkflowId().toString(),
                          from)
                      != 1) throw conflict();
                  return checked(checked, digest, original, desired, true);
                }),
        () ->
            transaction.execute(
                ignored -> {
                  Held held = checked(checked, digest, original, desired, true);
                  if (held == null
                      || !to.equals(held.status)
                      || !Objects.equals(body, held.body)
                      || !Objects.equals(receipt, held.receipt)) throw unavailable();
                  return held;
                }));
  }

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
                    r.getString("applied_json")),
            id.toString());
    if (rows.size() > 1) throw conflict();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Held checked(
      CheckedAccount checked, String digest, String original, String desired, boolean lock) {
    checked.requireTenantSourceLive();
    var row = held(checked.parentWorkflowId(), lock);
    if (row == null) return null;
    if (!checked.accountId().equals(row.account)
        || !checked.originalTenantId().equals(row.tenant)
        || !checked.humanSubject().equals(row.subject)
        || !checked.humanIssuer().equals(row.issuer)
        || !checked.humanClient().equals(row.client)
        || !digest.equals(row.digest)
        || !original.equals(row.original)
        || !desired.equals(row.desired)
        || !Set.of(
                "PREPARED",
                "FROZEN",
                "NO_EFFECT",
                "TENANT_ACKNOWLEDGED",
                "PROFILE_FROZEN",
                "PROFILE_REFUSED",
                "ACKNOWLEDGED",
                "APPLIED")
            .contains(row.status)
        || "APPLIED".equals(row.status) != (row.applied != null)
        || "PREPARED".equals(row.status) && (row.body != null || row.receipt != null)
        || !"PREPARED".equals(row.status) && row.body == null
        || "FROZEN".equals(row.status) && row.receipt != null
        || !Set.of("PREPARED", "FROZEN").contains(row.status) && row.receipt == null)
      throw conflict();
    return row;
  }

  private List<Held> pending(CheckedAccount checked, boolean lock) {
    return jdbc
        .queryForList(
            "select operation_id from general_admin_identity_intent where account_id=? and operation_id<>? and status<>'APPLIED' order by operation_id"
                + (lock ? " for update" : ""),
            String.class,
            checked.accountId(),
            checked.parentWorkflowId().toString())
        .stream()
        .map(id -> held(UUID.fromString(id), lock))
        .toList();
  }

  private void snapshot(CheckedAccount checked, Held held, String actual) {
    if (held == null) throw conflict();
    if ("APPLIED".equals(held.status)) {
      if (!actual.equals(held.applied)) throw conflict();
      postcondition(checked, actual);
    } else if (!checked.tenantOriginalSql().equals(actual)) throw conflict();
  }

  private void postcondition(CheckedAccount checked, String actual) {
    var before = decode(checked.tenantOriginalSql(), JsonNode.class);
    var after = decode(actual, JsonNode.class);
    for (String table :
        List.of("consultant", "language", "consultant_topic", "consultant_agency", "admin_agency"))
      if (!before.path(table).equals(after.path(table))) throw conflict();
    if (before.path("admin").size() != 1 || after.path("admin").size() != 1) throw conflict();
    var expected =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            decode(before.path("admin").get(0).textValue(), JsonNode.class);
    var result =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            decode(after.path("admin").get(0).textValue(), JsonNode.class);
    if (!result.path("tenant_id").isIntegralNumber()
        || !result.path("tenant_id").canConvertToLong()
        || result.path("tenant_id").longValue() != checked.desiredProfile().tenantId())
      throw conflict();
    expected.set("tenant_id", result.path("tenant_id").deepCopy());
    expected.put("first_name", checked.desiredProfile().firstName());
    expected.put("last_name", checked.desiredProfile().lastName());
    expected.put("email", checked.desiredProfile().email());
    expected.remove("update_date");
    result.remove("update_date");
    if (!expected.equals(result)) throw conflict();
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

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception invalid) {
      throw unavailable();
    }
  }

  private <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception invalid) {
      throw unavailable();
    }
  }

  private static ConflictException conflict() {
    return new ConflictException("Original tenant edit changed");
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Original tenant edit remains pending");
  }
}
