package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationSagaStore;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** The existing asker saga's fixed chosen-password native boundary. */
@RequiredArgsConstructor
public final class ScopedAskerReactivation {
  public static final String SECRET_ERASURE_TASK = "reactivation-secret-erasure-v1";
  private final @NonNull IdentityClientConfig mode;
  private final @NonNull AuthenticatedUser authenticated;
  private final @NonNull AdminScope scope;
  private final @NonNull HttpServletRequest request;
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull RestTemplate http;
  private final @NonNull Clock clock;
  private final @NonNull String authServer;
  private final @NonNull String publicIssuer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;
  private final @NonNull IdentityReactivationSagaStore saga;

  /**
   * Local data retention after original expiry plus seven days; no human or native command. Each
   * call scans at most 100 rows. An EOF call only resets the persisted cursor.
   */
  public int eraseExpiredScopedSecrets(
      de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.ClaimLease
          lease) {
    if (!mode.isFiveActorMode()
        || lease == null
        || !SECRET_ERASURE_TASK.equals(lease.taskName())
        || lease.claimedUntil() == null) return 0;
    var source = Objects.requireNonNull(jdbc.getDataSource());
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.hasResource(source)
        || !(transactions instanceof JpaTransactionManager jpa && jpa.getDataSource() == source
            || transactions
                    instanceof
                    org.springframework.jdbc.datasource.DataSourceTransactionManager local
                && local.getDataSource() == source)) throw unavailable();
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    return transaction(
        () -> {
          var held =
              jdbc.query(
                  "select claimed_until,reactivation_erasure_after_account_id from scheduled_task_claim where task_name=? for update",
                  (rs, index) ->
                      new ErasurePosition(
                          rs.getObject(1, java.time.LocalDateTime.class), rs.getString(2)),
                  SECRET_ERASURE_TASK);
          if (held.size() != 1
              || !lease.claimedUntil().equals(held.getFirst().until())
              || !lease
                  .claimedUntil()
                  .isAfter(
                      java.time.LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)))
            return 0;
          String cursor = held.getFirst().cursor();
          if (cursor != null && cursor.length() > 36) throw unavailable();
          List<String> selected =
              cursor == null
                  ? jdbc.queryForList(
                      "select user_id from user where reactivation_scoped_json is not null order by user_id limit 100",
                      String.class)
                  : jdbc.queryForList(
                      "select user_id from user where reactivation_scoped_json is not null and user_id>? order by user_id limit 100",
                      String.class,
                      cursor);
          int erased = 0;
          for (String account : selected) {
            var rows =
                jdbc.query(
                    "select case when char_length(reactivation_scoped_json)<=131072 then reactivation_scoped_json else null end from user where user_id=? for update skip locked",
                    (rs, index) -> rs.getString(1),
                    account);
            if (rows.isEmpty() || rows.getFirst() == null) continue;
            JsonNode retained;
            try {
              String encoded = rows.getFirst();
              var raw = codec.read(encoded, JsonNode.class);
              if (!encoded.equals(codec.canonical(raw))) continue;
              var tenant = raw.path("metadata").path("tenantId");
              if (!tenant.isIntegralNumber()
                  || !tenant.canConvertToLong()
                  || tenant.longValue() < 0) continue;
              var wrapper = open(raw, account, tenant.longValue(), actor);
              String phase = phase(raw);
              if (!"FROZEN".equals(phase)) {
                verifyTerminal(raw, wrapper, actor);
                requirePost(raw, wrapper.path("originalSql"), phase);
              }
              if (history(raw)
                  || clock.instant().getEpochSecond()
                      < Math.addExact(
                          raw.path("metadata").path("authorityValidUntil").longValue(), 604800L))
                continue;
              retained = raw.deepCopy();
              ((ObjectNode) retained).put("phase", phase + "_HISTORY");
              ((ObjectNode) retained)
                  .put(
                      "envelope",
                      codec.retainReactivationHistory(
                          raw.path("metadata"),
                          raw.path("envelope").textValue(),
                          phase,
                          raw.path("receipt").textValue(),
                          actor));
            } catch (RuntimeException malformedCustody) {
              // Pure parsing/authentication only: preserve poison bytes and advance fairly.
              continue;
            }
            // Database failures must roll back the whole cursor/erasure transaction.
            save(account, retained);
            erased++;
          }
          if (!lease
              .claimedUntil()
              .isAfter(
                  java.time.LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)))
            throw unavailable();
          if (jdbc.update(
                  "update scheduled_task_claim set reactivation_erasure_after_account_id=? where task_name=? and claimed_until=?",
                  selected.isEmpty() ? null : selected.getLast(),
                  SECRET_ERASURE_TASK,
                  lease.claimedUntil())
              != 1) throw unavailable();
          return erased;
        });
  }

  private record ErasurePosition(java.time.LocalDateTime until, String cursor) {
    @Override
    public String toString() {
      return "ErasurePosition[redacted]";
    }
  }

  public void reactivate(AskerReactivationRequestDTO input) {
    requireOutside();
    if (input == null || input.getTenantId() == null || input.getTenantId() < 0)
      throw invalidInput();
    text(input.getUsername(), 255);
    text(input.getEmail(), 255);
    password(input.getPassword());
    Human human = currentHuman();
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var machine = checkedMachine(actor);
    String account = locate(input);
    authorize(human, account, input.getTenantId());
    Initial initial = transaction(() -> new Initial(snapshot(account, true), saved(account)));
    String digest = requestDigest(input, human, account);
    Capsule frozen;
    if (initial.capsule != null) {
      var opened = open(initial.capsule, account, input.getTenantId(), actor);
      JsonNode original = opened.path("originalSql");
      String phase = phase(initial.capsule);
      if ("FROZEN".equals(phase)) {
        same(initial.row, original);
        requireIntent(initial.capsule, opened, human, digest);
        frozen =
            new Capsule(
                initial.capsule, materialize(initial.capsule, opened, actor, input.getPassword()));
      } else {
        verifyTerminal(initial.capsule, opened, actor);
        requirePost(initial.capsule, original, phase);
        if ("ACKNOWLEDGED".equals(phase)
            && beginsNewDeletionCycle(initial.row, original, initial.capsule.path("postSql"))) {
          frozen = freezeFresh(input, human, account, initial, digest, actor, machine);
        } else {
          same(initial.row, initial.capsule.path("postSql"));
          boolean exact =
              digest.equals(initial.capsule.path("metadata").path("requestDigest").textValue());
          if (exact) {
            requireIntent(initial.capsule, opened, human, digest);
            replayTerminal(
                human,
                account,
                initial,
                input.getTenantId(),
                actor,
                materialize(initial.capsule, opened, actor, input.getPassword()));
            return;
          }
          if (!"NO_EFFECT".equals(phase)) throw conflict();
          frozen = freezeFresh(input, human, account, initial, digest, actor, machine);
        }
      }
    } else frozen = freezeFresh(input, human, account, initial, digest, actor, machine);
    execute(input, human, account, frozen, actor);
  }

  private Capsule freezeFresh(
      AskerReactivationRequestDTO input,
      Human human,
      String account,
      Initial initial,
      String digest,
      TaskActorCredentials actor,
      TaskActorTokenBroker.VerifiedMaintenanceToken machine) {
    eligible(initial.row);
    String operation = UUID.randomUUID().toString();
    var target = target(human, account, input.getTenantId(), operation);
    var read =
        codec.read(
            call("read", human, account, codec.canonical(Map.of("target", target)), actor),
            JsonNode.class);
    requireProjection(initial.row, read);
    long expiry =
        Math.min(
            Math.min(human.expires.getEpochSecond(), machine.validatedJwtExpiresAt()),
            clock.instant().getEpochSecond() + 600);
    if (expiry <= clock.instant().getEpochSecond()) throw unavailable();
    return transaction(
        () -> {
          same(snapshot(account, true), initial.row);
          sameNullable(saved(account), initial.capsule);
          eligible(initial.row);
          if (expiry <= clock.instant().getEpochSecond()) throw unavailable();
          var started =
              java.time.LocalDateTime.ofInstant(
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  java.time.ZoneOffset.UTC);
          if (jdbc.update(
                  "update user set deletion_lifecycle_state='REACTIVATION_IN_PROGRESS',reactivation_operation_id=?,reactivation_operation_started_at=? where user_id=? and reactivation_operation_id is null",
                  operation,
                  started,
                  account)
              != 1) throw conflict();
          var original = snapshot(account, true);
          var body = codec.read("{}", ObjectNode.class);
          body.set("target", target);
          body.put("operationId", operation);
          body.put("expectedState", read.path("state").textValue());
          body.putObject("change")
              .put("expectedUsername", read.path("username").textValue())
              .put("expectedEmail", read.path("email").textValue())
              .put("password", input.getPassword());
          var metadata = codec.read("{}", ObjectNode.class);
          metadata.put("v", 1);
          metadata.put("realmId", realmId);
          metadata.put("operationId", operation);
          metadata.put("command", "account.reactivate");
          metadata.put("method", "POST");
          metadata.put("route", "oriso-account-maintenance/v1/accounts/reactivate");
          metadata.put("accountId", account);
          metadata.put("tenantId", input.getTenantId());
          metadata.put("ownerClient", actor.clientId());
          metadata.put("ownerSubject", actor.serviceSubject());
          metadata.put("originDigest", codec.digest("maintenance-origin", target.path("origin")));
          metadata.put("workflowId", operation);
          metadata.put("requestDigest", digest);
          metadata.put("expectedState", read.path("state").textValue());
          metadata.put("authorityValidUntil", expiry);
          metadata.put("representation", "FROZEN_REACTIVATION_HTTP_V1");
          var wrapper = codec.read("{}", ObjectNode.class);
          wrapper.set("body", body);
          wrapper.set("originalSql", original);
          String envelope =
              codec.sealReactivationIntent(
                  metadata, codec.canonical(wrapper).getBytes(StandardCharsets.UTF_8));
          var capsule = codec.read("{}", ObjectNode.class);
          capsule.put("v", 1);
          capsule.put("phase", "FROZEN");
          capsule.set("metadata", metadata);
          capsule.put("envelope", envelope);
          capsule.putNull("receipt");
          capsule.putNull("postSql");
          save(account, capsule);
          return new Capsule(capsule, wrapper);
        });
  }

  private void execute(
      AskerReactivationRequestDTO input,
      Human human,
      String account,
      Capsule frozen,
      TaskActorCredentials actor) {
    authorize(human, account, input.getTenantId());
    transaction(
        () -> {
          same(snapshot(account, true), frozen.wrapper.path("originalSql"));
          same(saved(account), frozen.raw);
          return null;
        });
    String reply;
    try {
      reply =
          call("reactivate", human, account, codec.canonical(frozen.wrapper.path("body")), actor);
    } catch (NativeRefusal refused) {
      final AccountLifecycleCodec.VerifiedMaintenanceNoEffect proof;
      final String canonicalReceipt;
      try {
        proof =
            codec.verifyMaintenanceNoEffectReceipt(
                refused.reply, realmId, actor, frozen.wrapper.path("body"), "reactivate");
        if (proof.status() != refused.status) throw unavailable();
        canonicalReceipt = codec.canonical(codec.read(refused.reply, JsonNode.class));
      } catch (RuntimeException unsignedOrUnbound) {
        throw unavailable();
      }
      transaction(
          () -> {
            same(snapshot(account, true), frozen.wrapper.path("originalSql"));
            same(saved(account), frozen.raw);
            if (jdbc.update(
                    "update user set deletion_lifecycle_state='READ_ONLY_SAFEGUARD',reactivation_operation_id=null,reactivation_operation_started_at=null where user_id=? and reactivation_operation_id=?",
                    account,
                    proof.operationId().toString())
                != 1) throw conflict();
            var terminal = terminalCapsule(frozen, "NO_EFFECT", canonicalReceipt, actor);
            ((ObjectNode) terminal).set("postSql", snapshot(account, true));
            requirePost(terminal, frozen.wrapper.path("originalSql"), "NO_EFFECT");
            save(account, terminal);
            return null;
          });
      throw terminal(proof.status());
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
    String verified =
        codec.verifyMaintenanceReactivationReceipt(
            reply, realmId, actor, frozen.wrapper.path("body"));
    transaction(
        () -> {
          same(snapshot(account, true), frozen.wrapper.path("originalSql"));
          same(saved(account), frozen.raw);
          var acknowledged = terminalCapsule(frozen, "ACKNOWLEDGED", verified, actor);
          // Persist the verified ACK first, then the original saga callback in this SAME
          // transaction.
          save(account, acknowledged);
          var jpa = (JpaTransactionManager) transactions;
          var entities =
              EntityManagerFactoryUtils.getTransactionalEntityManager(
                  Objects.requireNonNull(jpa.getEntityManagerFactory()));
          if (entities == null) throw unavailable();
          saga.acknowledgeScoped(
              new VerifiedUpdate(codec, frozen.wrapper.path("originalSql")), entities);
          ((ObjectNode) acknowledged).set("postSql", snapshot(account, true));
          requirePost(acknowledged, frozen.wrapper.path("originalSql"), "ACKNOWLEDGED");
          save(account, acknowledged);
          return null;
        });
  }

  private void replayTerminal(
      Human human,
      String account,
      Initial initial,
      Long tenant,
      TaskActorCredentials actor,
      JsonNode opened) {
    authorize(human, account, tenant);
    String phase = phase(initial.capsule);
    String reply;
    try {
      reply = call("reactivate", human, account, codec.canonical(opened.path("body")), actor);
    } catch (NativeRefusal refused) {
      if (!"NO_EFFECT".equals(phase)) throw unavailable();
      var proof =
          codec.verifyMaintenanceNoEffectReceipt(
              refused.reply, realmId, actor, opened.path("body"), "reactivate");
      if (proof.status() != refused.status
          || !codec
              .canonical(codec.read(refused.reply, JsonNode.class))
              .equals(initial.capsule.path("receipt").textValue())) throw unavailable();
      transaction(
          () -> {
            same(snapshot(account, true), initial.row);
            same(saved(account), initial.capsule);
            return null;
          });
      throw terminal(proof.status());
    }
    if (!"ACKNOWLEDGED".equals(phase)
        || !codec
            .verifyMaintenanceReactivationReceipt(reply, realmId, actor, opened.path("body"))
            .equals(initial.capsule.path("receipt").textValue())) throw unavailable();
    transaction(
        () -> {
          same(snapshot(account, true), initial.row);
          same(saved(account), initial.capsule);
          return null;
        });
  }

  private JsonNode open(JsonNode capsule, String account, Long tenant, TaskActorCredentials actor) {
    fields(capsule, Set.of("v", "phase", "metadata", "envelope", "receipt", "postSql"));
    if (!capsule.path("v").isIntegralNumber()
        || !capsule.path("v").canConvertToInt()
        || capsule.path("v").intValue() != 1) throw unavailable();
    String phase = phase(capsule);
    if ("FROZEN".equals(phase)) {
      if (!capsule.path("receipt").isNull() || !capsule.path("postSql").isNull())
        throw unavailable();
    } else if (!capsule.path("receipt").isTextual()
        || capsule.path("receipt").textValue().length() > 32768
        || !capsule.path("postSql").isObject()) throw unavailable();
    var metadata = capsule.path("metadata");
    if (!realmId.equals(metadata.path("realmId").textValue())
        || !account.equals(metadata.path("accountId").textValue())
        || !metadata.path("tenantId").isIntegralNumber()
        || !metadata.path("tenantId").canConvertToLong()
        || metadata.path("tenantId").longValue() != tenant
        || !actor.clientId().equals(metadata.path("ownerClient").textValue())
        || !actor.serviceSubject().equals(metadata.path("ownerSubject").textValue()))
      throw unavailable();
    String envelope = capsule.path("envelope").textValue();
    if (history(capsule)) {
      var verified =
          codec.verifyReactivationHistory(
              metadata,
              envelope,
              capsule.path("phase").textValue(),
              capsule.path("receipt").textValue(),
              actor);
      var wrapper = codec.read("{}", ObjectNode.class);
      wrapper.set("originalSql", verified.originalSql());
      wrapper.set("body", verified.template());
      return wrapper;
    }
    return codec.read(codec.utf8(codec.openReactivationIntent(metadata, envelope)), JsonNode.class);
  }

  private JsonNode materialize(
      JsonNode raw, JsonNode wrapper, TaskActorCredentials actor, String password) {
    if (!history(raw)) return wrapper;
    var materialized = wrapper.deepCopy();
    ((ObjectNode) materialized)
        .set(
            "body",
            codec.read(
                codec.materializeReactivationHistory(
                    raw.path("metadata"),
                    raw.path("envelope").textValue(),
                    raw.path("phase").textValue(),
                    raw.path("receipt").textValue(),
                    actor,
                    password),
                JsonNode.class));
    return materialized;
  }

  private JsonNode terminalCapsule(
      Capsule frozen, String phase, String receipt, TaskActorCredentials actor) {
    var terminal = frozen.raw.deepCopy();
    ((ObjectNode) terminal).put("receipt", receipt);
    if (history(frozen.raw)) {
      // The recovered original is transient. Only authenticated password-free history is saved.
      String transientEnvelope =
          codec.sealReactivationIntent(
              frozen.raw.path("metadata"),
              codec.canonical(frozen.wrapper).getBytes(StandardCharsets.UTF_8));
      ((ObjectNode) terminal)
          .put(
              "envelope",
              codec.retainReactivationHistory(
                  frozen.raw.path("metadata"), transientEnvelope, phase, receipt, actor));
      ((ObjectNode) terminal).put("phase", phase + "_HISTORY");
    } else ((ObjectNode) terminal).put("phase", phase);
    return terminal;
  }

  private static boolean history(JsonNode raw) {
    return Set.of("FROZEN_HISTORY", "ACKNOWLEDGED_HISTORY", "NO_EFFECT_HISTORY")
        .contains(raw.path("phase").asText());
  }

  private static String phase(JsonNode raw) {
    String value = raw.path("phase").textValue();
    if (value == null) throw unavailable();
    return switch (value) {
      case "FROZEN", "FROZEN_HISTORY" -> "FROZEN";
      case "ACKNOWLEDGED", "ACKNOWLEDGED_HISTORY" -> "ACKNOWLEDGED";
      case "NO_EFFECT", "NO_EFFECT_HISTORY" -> "NO_EFFECT";
      default -> throw unavailable();
    };
  }

  private void requireIntent(JsonNode capsule, JsonNode wrapper, Human human, String digest) {
    if (!digest.equals(capsule.path("metadata").path("requestDigest").textValue()))
      throw conflict();
    var origin = wrapper.path("body").path("target").path("origin");
    if (!human.subject.equals(origin.path("humanSubject").textValue())
        || !human.issuer.equals(origin.path("humanIssuer").textValue())
        || !human.client.equals(origin.path("humanClient").textValue())) throw forbidden();
  }

  private void verifyTerminal(JsonNode raw, JsonNode wrapper, TaskActorCredentials actor) {
    String receipt = raw.path("receipt").textValue();
    if (history(raw)) {
      codec.verifyReactivationHistory(
          raw.path("metadata"),
          raw.path("envelope").textValue(),
          raw.path("phase").textValue(),
          receipt,
          actor);
      return;
    }
    if ("ACKNOWLEDGED".equals(raw.path("phase").textValue()))
      codec.verifyMaintenanceReactivationReceipt(receipt, realmId, actor, wrapper.path("body"));
    else if ("NO_EFFECT".equals(raw.path("phase").textValue()))
      codec.verifyMaintenanceNoEffectReceipt(
          receipt, realmId, actor, wrapper.path("body"), "reactivate");
    else throw unavailable();
  }

  private void requirePost(JsonNode raw, JsonNode original, String phase) {
    var expected = original.deepCopy();
    ((ObjectNode) expected).putNull("reactivation_operation_id");
    ((ObjectNode) expected).putNull("reactivation_operation_started_at");
    ((ObjectNode) expected)
        .put(
            "deletion_lifecycle_state",
            "ACKNOWLEDGED".equals(phase) ? "ACTIVE" : "READ_ONLY_SAFEGUARD");
    if ("ACKNOWLEDGED".equals(phase)) {
      for (String name :
          List.of(
              "delete_date",
              "deletion_read_only_until",
              "deletion_paused_until",
              "deletion_pause_reason",
              "deletion_paused_by",
              "deletion_pause_created_at")) ((ObjectNode) expected).putNull(name);
      var update = raw.path("postSql").path("update_date");
      if (!update.isTextual()) throw unavailable();
      try {
        Instant time = Instant.parse(update.textValue());
        if (time.isBefore(
                Instant.parse(original.path("reactivation_operation_started_at").textValue()))
            || time.isAfter(clock.instant().plusSeconds(5))
            || time.getNano() % 1000 != 0
            || !time.toString().equals(update.textValue())) throw unavailable();
      } catch (java.time.DateTimeException invalid) {
        throw unavailable();
      }
      ((ObjectNode) expected).set("update_date", update);
    }
    same(expected, raw.path("postSql"));
  }

  private boolean beginsNewDeletionCycle(JsonNode current, JsonNode original, JsonNode post) {
    if (current.path("delete_date").isNull()
        || !Set.of("PENDING_DELETION", "READ_ONLY_SAFEGUARD")
            .contains(current.path("deletion_lifecycle_state").asText())
        || !current.path("reactivation_operation_id").isNull()
        || !current.path("reactivation_operation_started_at").isNull()) return false;
    for (String anchor : List.of("user_id", "create_date", "tenant_id"))
      if (!current.path(anchor).equals(original.path(anchor))) throw conflict();
    try {
      return Instant.parse(current.path("delete_date").textValue())
          .isAfter(Instant.parse(post.path("update_date").textValue()));
    } catch (java.time.DateTimeException | NullPointerException invalid) {
      throw conflict();
    }
  }

  private String locate(AskerReactivationRequestDTO input) {
    String logical =
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
            .decodeUsername(input.getUsername().trim().toLowerCase(Locale.ROOT));
    String encoded =
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
            .encodeUsername(logical)
            .toLowerCase(Locale.ROOT);
    var rows =
        jdbc.queryForList(
            "select user_id,username,email from user where lower(username) in (?,?) and tenant_id=? and lower(email)=? limit 2",
            encoded,
            logical.toLowerCase(Locale.ROOT),
            input.getTenantId(),
            input.getEmail().trim().toLowerCase(Locale.ROOT));
    var exact =
        rows.stream()
            .filter(row -> input.getEmail().trim().equalsIgnoreCase((String) row.get("email")))
            .toList();
    if (exact.size() != 1) throw conflict();
    return uuid((String) exact.getFirst().get("user_id"));
  }

  private void authorize(Human human, String account, Long tenant) {
    Human current = currentHuman();
    if (!human.sameIdentity(current)) throw forbidden();
    if (!Objects.equals(
            authenticated.getTenantId(),
            de.caritas.cob.userservice.api.tenant.TenantContext.getCurrentTenant())
        || !(Long.valueOf(0).equals(authenticated.getTenantId())
            || Objects.equals(authenticated.getTenantId(), tenant))) throw forbidden();
    scope.assertMay(AdminScope.Target.adviceSeeker(account));
    if (jdbc.queryForObject(
            "select (select count(*) from admin where admin_id=?)+(select count(*) from consultant where consultant_id=?)",
            Long.class,
            account,
            account)
        != 0) throw forbidden();
  }

  private void eligible(JsonNode row) {
    if (row.path("delete_date").isNull()
        || !row.path("reactivation_operation_id").isNull()
        || Set.of(
                "HARD_DELETED",
                "HARD_DELETE_IN_PROGRESS",
                "HARD_DELETE_PARTIAL_FAILURE",
                "REACTIVATION_IN_PROGRESS",
                "REACTIVATION_REPAIR_REQUIRED")
            .contains(row.path("deletion_lifecycle_state").asText())) throw conflict();
  }

  private void requireProjection(JsonNode sql, JsonNode read) {
    fields(
        read,
        Set.of(
            "id",
            "username",
            "email",
            "firstName",
            "lastName",
            "enabled",
            "emailVerified",
            "tenantId",
            "preferredLanguage",
            "effectiveRoles",
            "passwordChangeRequired",
            "state"));
    if (!sql.path("user_id").textValue().equals(read.path("id").textValue())
        || !sql.path("username").textValue().equalsIgnoreCase(read.path("username").textValue())
        || !sql.path("email").textValue().equalsIgnoreCase(read.path("email").textValue())
        || !Long.toString(sql.path("tenant_id").longValue())
            .equals(read.path("tenantId").textValue())
        || !read.path("state").isTextual()
        || !read.path("state").textValue().matches("[0-9a-f]{64}")
        || !read.path("enabled").isBoolean()
        || !read.path("emailVerified").isBoolean()
        || !read.path("passwordChangeRequired").isBoolean()
        || !read.path("effectiveRoles").isArray()
        || read.path("effectiveRoles").size() != 1
        || !"user".equals(read.path("effectiveRoles").get(0).textValue())) throw forbidden();
    for (String name : List.of("firstName", "lastName", "preferredLanguage")) {
      var value = read.path(name);
      if (!value.isNull()
          && (!value.isTextual()
              || value.textValue().length() > 255
              || value.textValue().chars().anyMatch(c -> c < 32 || c == 127))) throw forbidden();
    }
  }

  private Human currentHuman() {
    if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken authentication)
        || !authentication.isAuthenticated()
        || !"POST".equals(request.getMethod())
        || !Set.of(
                "/useradmin/askers/deletion/reactivate",
                "/service/useradmin/askers/deletion/reactivate")
            .contains(request.getRequestURI())) throw forbidden();
    var jwt = authentication.getToken();
    String raw = request.getHeader("Authorization");
    String subject = jwt.getSubject(), client = jwt.getClaimAsString("azp");
    if (raw == null
        || raw.length() > 32775
        || !raw.regionMatches(true, 0, "Bearer ", 0, 7)
        || !jwt.getTokenValue().equals(raw.substring(7))
        || !jwt.getTokenValue().equals(authenticated.getAccessToken())
        || !Objects.equals(subject, authenticated.getUserId())
        || jwt.getIssuer() == null
        || !publicIssuer.equals(jwt.getIssuer().toString())
        || jwt.getIssuedAt() == null
        || jwt.getIssuedAt().getEpochSecond() <= 0
        || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(5))
        || jwt.getExpiresAt() == null
        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
        || !jwt.getExpiresAt().isAfter(clock.instant())
        || client == null
        || !client.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,254}")) throw forbidden();
    String tenant = jwt.getClaimAsString("tenantId");
    if (tenant == null
        || !tenant.matches("0|[1-9][0-9]{0,18}")
        || !tenant.equals(String.valueOf(authenticated.getTenantId()))) throw forbidden();
    return new Human(uuid(subject), publicIssuer, client, raw.substring(7), jwt.getExpiresAt());
  }

  private String call(
      String command, Human human, String account, String raw, TaskActorCredentials actor) {
    requireOutside();
    if (!human.sameIdentity(currentHuman()) || !human.expires.isAfter(clock.instant()))
      throw forbidden();
    var machine = checkedMachine(actor);
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(machine.accessToken());
    headers.set("X-ORISO-Human-Authorization", "Bearer " + human.bearer);
    headers.set(
        "X-ORISO-Task-Context",
        codec.maintenanceProof(
            command, realmName, realmId, account, actor, codec.read(raw, JsonNode.class)));
    var uri =
        UriComponentsBuilder.fromUriString(authServer)
            .pathSegment(
                "realms",
                realmName,
                "oriso-account-maintenance",
                "v1",
                "accounts",
                account,
                command)
            .build()
            .encode()
            .toUri();
    try {
      var result =
          http.exchange(uri, HttpMethod.POST, new HttpEntity<>(raw, headers), String.class);
      if (result.getStatusCode().value() != 200
          || result.getBody() == null
          || result.getBody().length() > 32768) throw unavailable();
      return result.getBody();
    } catch (HttpStatusCodeException refused) {
      if ("reactivate".equals(command)
          && Set.of(400, 403, 409).contains(refused.getStatusCode().value())) {
        String reply = refused.getResponseBodyAsString();
        if (reply == null || reply.length() > 32768) throw unavailable();
        throw new NativeRefusal(refused.getStatusCode().value(), reply);
      }
      throw unavailable();
    } catch (NativeRefusal refused) {
      throw refused;
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
  }

  private TaskActorTokenBroker.VerifiedMaintenanceToken checkedMachine(TaskActorCredentials actor) {
    var value = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    long now = clock.instant().getEpochSecond();
    if (value == null
        || actor.actor() != TaskActor.ACCOUNT_ADMIN
        || value.actor() != actor.actor()
        || value.purpose() != TaskPurpose.ACCOUNT_MAINTENANCE
        || !actor.clientId().equals(value.clientId())
        || !actor.serviceSubject().equals(value.subject())
        || !publicIssuer.equals(value.issuer())
        || value.validatedJwtIssuedAt() <= 0
        || value.validatedJwtIssuedAt() > now
        || value.validatedJwtExpiresAt() <= value.validatedJwtIssuedAt()
        || value.validatedJwtExpiresAt() <= now) throw unavailable();
    return value;
  }

  private ObjectNode target(Human human, String account, Long tenant, String operation) {
    var target = codec.read("{}", ObjectNode.class);
    target.put("accountId", account);
    target.put("tenantId", tenant.toString());
    var origin = target.putObject("origin");
    origin.put("kind", "HUMAN_ADMIN");
    origin.put("workflowId", operation);
    origin.put("humanSubject", human.subject);
    origin.put("humanIssuer", human.issuer);
    origin.put("humanClient", human.client);
    origin.put("humanAuthority", "PROTECTED_ACCOUNT_OPERATIONS_V1");
    return target;
  }

  private String requestDigest(AskerReactivationRequestDTO input, Human human, String account) {
    return codec.digest(
        "maintenance-intent",
        Map.of(
            "kind",
            "ASKER_REACTIVATION",
            "accountId",
            account,
            "tenantId",
            input.getTenantId(),
            "username",
            input.getUsername().trim().toLowerCase(Locale.ROOT),
            "email",
            input.getEmail().trim().toLowerCase(Locale.ROOT),
            "password",
            input.getPassword(),
            "humanSubject",
            human.subject,
            "humanIssuer",
            human.issuer,
            "humanClient",
            human.client));
  }

  private JsonNode snapshot(String account, boolean lock) {
    return ReactivationUserSnapshotSchema.capture(jdbc, codec, account, lock);
  }

  private JsonNode saved(String account) {
    String raw =
        jdbc.queryForObject(
            "select reactivation_scoped_json from user where user_id=?", String.class, account);
    if (raw == null) return null;
    if (raw.length() > 131072) throw unavailable();
    var value = codec.read(raw, JsonNode.class);
    if (!raw.equals(codec.canonical(value))) throw unavailable();
    return value;
  }

  private void save(String account, JsonNode value) {
    String raw = codec.canonical(value);
    if (raw.length() > 131072
        || jdbc.update("update user set reactivation_scoped_json=? where user_id=?", raw, account)
            != 1) throw unavailable();
  }

  private void requireOutside() {
    if (!mode.isFiveActorMode()
        || TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.hasResource(
            Objects.requireNonNull(jdbc.getDataSource()))
        || !(transactions instanceof JpaTransactionManager jpa)
        || jpa.getDataSource() != jdbc.getDataSource()) throw unavailable();
  }

  private <T> T transaction(Supplier<T> action) {
    var tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setTimeout(15);
    return tx.execute(
        status -> {
          if (!TransactionSynchronizationManager.isActualTransactionActive()
              || !TransactionSynchronizationManager.hasResource(
                  Objects.requireNonNull(jdbc.getDataSource()))) throw unavailable();
          return action.get();
        });
  }

  private void same(JsonNode left, JsonNode right) {
    if (left == null || right == null || !codec.canonical(left).equals(codec.canonical(right)))
      throw conflict();
  }

  private void sameNullable(JsonNode left, JsonNode right) {
    if (left != null || right != null) same(left, right);
  }

  private static void fields(JsonNode value, Set<String> expected) {
    var names = new HashSet<String>();
    value.fieldNames().forEachRemaining(names::add);
    if (!value.isObject() || !expected.equals(names)) throw unavailable();
  }

  private static void password(String value) {
    if (value == null || value.isEmpty() || value.length() > 4096) throw invalidInput();
  }

  private static String text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.chars().anyMatch(c -> c < 32 || c == 127)) throw invalidInput();
    return value;
  }

  private static String uuid(String value) {
    try {
      if (value == null || !UUID.fromString(value).toString().equals(value)) throw forbidden();
      return value;
    } catch (IllegalArgumentException invalid) {
      throw forbidden();
    }
  }

  private static RuntimeException terminal(int status) {
    return switch (status) {
      case 400 -> new BadRequestException("Chosen password rejected");
      case 403 -> forbidden();
      case 409 -> conflict();
      default -> unavailable();
    };
  }

  private static BadRequestException invalidInput() {
    return new BadRequestException("Invalid asker reactivation request");
  }

  private static ForbiddenException forbidden() {
    return new ForbiddenException("Asker reactivation is outside current authority");
  }

  private static ConflictException conflict() {
    return new ConflictException("Asker reactivation incarnation is no longer current");
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Scoped asker reactivation unavailable");
  }

  private record Initial(JsonNode row, JsonNode capsule) {
    @Override
    public String toString() {
      return "Initial[redacted]";
    }
  }

  private record Capsule(JsonNode raw, JsonNode wrapper) {
    @Override
    public String toString() {
      return "Capsule[redacted]";
    }
  }

  private record Human(
      String subject, String issuer, String client, String bearer, Instant expires) {
    boolean sameIdentity(Human other) {
      return subject.equals(other.subject)
          && issuer.equals(other.issuer)
          && client.equals(other.client)
          && bearer.equals(other.bearer);
    }

    @Override
    public String toString() {
      return "Human[redacted]";
    }
  }

  private static final class NativeRefusal extends RuntimeException {
    final int status;
    final String reply;

    NativeRefusal(int status, String reply) {
      super("Native reactivation refused");
      this.status = status;
      this.reply = reply;
    }

    @Override
    public String toString() {
      return "NativeRefusal[redacted]";
    }
  }

  /** Only an independently verified current native UPDATED can construct this callback frame. */
  public static final class VerifiedUpdate {
    private final AccountLifecycleCodec codec;
    private final JsonNode original;

    private VerifiedUpdate(AccountLifecycleCodec codec, JsonNode original) {
      this.codec = codec;
      this.original = original.deepCopy();
    }

    public String accountId() {
      return original.path("user_id").textValue();
    }

    public void requireCurrent(de.caritas.cob.userservice.api.model.User user) {
      if (!codec
          .canonical(original)
          .equals(codec.canonical(ReactivationUserSnapshotSchema.from(user, codec))))
        throw conflict();
    }

    @Override
    public String toString() {
      return "VerifiedUpdate[redacted]";
    }
  }
}
