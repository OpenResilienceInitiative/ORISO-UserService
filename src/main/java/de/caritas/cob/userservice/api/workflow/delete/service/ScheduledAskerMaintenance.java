package de.caritas.cob.userservice.api.workflow.delete.service;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Scheduled asker native ACK only; downstream ownership/cleanup has a separate gate. */
@RequiredArgsConstructor
public final class ScheduledAskerMaintenance {
  private static final String TASK = "account-deletion";
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull ScheduledTaskClaimService claims;
  private final @NonNull Clock clock;
  private final @NonNull DeletionLifecycleService lifecycle;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull RestTemplate http;
  private final @NonNull String authServer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;

  public void performDeletionWorkflow() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    var acquired = claims.tryClaimLease(TASK, Duration.ofHours(12));
    if (acquired.isEmpty()) return;
    var lease = acquired.get();
    try {
      // Only local persisted candidates are scanned. Native enumeration never grants a target.
      for (String account :
          jdbc.queryForList(
              "select user_id from user where delete_date is not null order by user_id",
              String.class)) {
        ClaimedDeletion checked = transaction(() -> prepare(lease, account));
        if (checked == null || "ACKNOWLEDGED".equals(checked.intent.status)) continue;
        FrozenDeletion frozen;
        if (checked.intent.frozen != null) {
          frozen = frozen(checked, checked.intent.frozen);
        } else {
          String state = read(checked);
          frozen = transaction(() -> freeze(checked, state));
        }
        if (frozen == null) continue;
        String response = request("delete", frozen.claim, frozen.body);
        var actor = actor(frozen.claim);
        String receipt =
            codec.verifyMaintenanceDeleteReceipt(
                response, realmId, actor, codec.read(frozen.body, JsonNode.class));
        transaction(() -> acknowledge(frozen, receipt));
      }
    } finally {
      claims.release(lease);
    }
  }

  private ClaimedDeletion prepare(ScheduledTaskClaimService.ClaimLease lease, String account) {
    if (!held(lease)) return null;
    User user = lockedUser(account);
    if (user == null || user.getDeleteDate() == null) return null;
    if (!UUID.fromString(account).toString().equals(account)) throw unavailable();
    var rows =
        jdbc.query(
            "select * from identity_maintenance_intent where subject_type='ASKER'"
                + " and subject_id=? and deletion_started_at=? for update",
            (rs, index) -> intent(rs),
            account,
            user.getDeleteDate());
    if (rows.size() > 1) throw unavailable();
    Intent intent = rows.isEmpty() ? null : rows.get(0);
    lifecycle.normalizeUserLifecycle(user);
    boolean retry =
        user.getDeletionLifecycleState() == DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE
            || intent != null
                && user.getDeletionLifecycleState()
                    == DeletionLifecycleState.HARD_DELETE_IN_PROGRESS;
    if (!retry && !lifecycle.isReadyForHardDelete(user)) {
      saveLifecycle(user);
      return null;
    }
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    if (intent == null) {
      String operation = UUID.randomUUID().toString();
      jdbc.update(
          "insert into identity_maintenance_intent(operation_id,realm_id,realm_name,subject_type,"
              + "subject_id,deletion_started_at,tenant_id,claim_token,claim_until,status,"
              + "owner_client,owner_subject) values(?,?,?,'ASKER',?,?,?,?,?,'PREPARED',?,?)",
          operation,
          realmId,
          realmName,
          account,
          user.getDeleteDate(),
          user.getTenantId(),
          UUID.randomUUID().toString(),
          lease.claimedUntil(),
          actor.clientId(),
          actor.serviceSubject());
      intent =
          jdbc.queryForObject(
              "select * from identity_maintenance_intent where operation_id=? for update",
              (rs, index) -> intent(rs),
              operation);
    }
    if (intent == null
        || !realmId.equals(intent.realmId)
        || !realmName.equals(intent.realmName)
        || !Objects.equals(user.getTenantId(), intent.tenant)
        || !actor.clientId().equals(intent.ownerClient)
        || !actor.serviceSubject().equals(intent.ownerSubject)
        || !Set.of("PREPARED", "FROZEN", "ACKNOWLEDGED").contains(intent.status))
      throw unavailable();
    String generation = UUID.randomUUID().toString();
    jdbc.update(
        "update identity_maintenance_intent set claim_token=?,claim_until=? where operation_id=?",
        generation,
        lease.claimedUntil(),
        intent.operation);
    if (!"ACKNOWLEDGED".equals(intent.status)) {
      user.setDeletionLifecycleState(DeletionLifecycleState.HARD_DELETE_IN_PROGRESS);
      saveLifecycle(user);
    }
    return new ClaimedDeletion(lease, account, user.getDeleteDate(), generation, intent);
  }

  private String read(ClaimedDeletion claim) {
    var result =
        codec.read(
            request("read", claim, codec.canonical(Map.of("target", target(claim)))),
            JsonNode.class);
    var keys = new java.util.HashSet<String>();
    result.fieldNames().forEachRemaining(keys::add);
    if (!keys.equals(
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
                "state"))
        || !claim.account.equals(result.path("id").textValue())
        || !Objects.equals(
            claim.intent.tenant == null ? null : claim.intent.tenant.toString(),
            result.path("tenantId").isNull() ? null : result.path("tenantId").textValue())
        || !result.path("state").isTextual()
        || !result.path("state").asText().matches("[0-9a-f]{64}")) throw unavailable();
    return result.path("state").textValue();
  }

  private FrozenDeletion freeze(ClaimedDeletion claim, String state) {
    if (!current(claim)) return null;
    var intent = lockedIntent(claim);
    if (!claim.generation.equals(intent.generation)) return null;
    if (intent.frozen != null) return frozen(claim, intent.frozen);
    if (!"PREPARED".equals(intent.status)) throw unavailable();
    String body =
        codec.canonical(
            Map.of(
                "target", target(claim),
                "operationId", intent.operation,
                "expectedState", state,
                "change", Map.of()));
    jdbc.update(
        "update identity_maintenance_intent set expected_state=?,frozen_body=?,status='FROZEN'"
            + " where operation_id=? and claim_token=?",
        state,
        body,
        intent.operation,
        claim.generation);
    return frozen(claim, body);
  }

  private boolean acknowledge(FrozenDeletion frozen, String receipt) {
    var claim = frozen.claim;
    if (!current(claim)) return false;
    var intent = lockedIntent(claim);
    if (!claim.generation.equals(intent.generation)) return false;
    if (!"FROZEN".equals(intent.status) || !frozen.body.equals(intent.frozen)) throw unavailable();
    jdbc.update(
        "update identity_maintenance_intent set receipt_json=?,status='ACKNOWLEDGED'"
            + " where operation_id=? and claim_token=?",
        receipt,
        intent.operation,
        claim.generation);
    // Native ACK is durable, while owned downstream cleanup remains pending and irreversible.
    jdbc.update(
        "update user set deletion_lifecycle_state='HARD_DELETE_PARTIAL_FAILURE' where user_id=?",
        claim.account);
    return true;
  }

  private boolean current(ClaimedDeletion claim) {
    if (!held(claim.lease)) return false;
    var user = lockedUser(claim.account);
    return user != null
        && claim.deletionStarted.equals(user.getDeleteDate())
        && Objects.equals(claim.intent.tenant, user.getTenantId())
        && user.getDeletionLifecycleState() == DeletionLifecycleState.HARD_DELETE_IN_PROGRESS;
  }

  private boolean held(ScheduledTaskClaimService.ClaimLease lease) {
    if (!TASK.equals(lease.taskName())) return false;
    var rows =
        jdbc.query(
            "select claimed_until from scheduled_task_claim where task_name=? for update",
            (rs, index) -> rs.getTimestamp(1).toLocalDateTime(),
            TASK);
    return rows.size() == 1
        && lease.claimedUntil().equals(rows.get(0))
        && rows.get(0).isAfter(LocalDateTime.now(clock));
  }

  private User lockedUser(String account) {
    var rows =
        jdbc.query(
            "select user_id,tenant_id,delete_date,deletion_lifecycle_state,"
                + "deletion_read_only_until,deletion_paused_until from user"
                + " where user_id=? for update",
            (rs, index) -> {
              var user = new User();
              user.setUserId(rs.getString("user_id"));
              user.setTenantId(rs.getObject("tenant_id", Long.class));
              user.setDeleteDate(time(rs, "delete_date"));
              String state = rs.getString("deletion_lifecycle_state");
              user.setDeletionLifecycleState(
                  state == null ? null : DeletionLifecycleState.valueOf(state));
              user.setDeletionReadOnlyUntil(time(rs, "deletion_read_only_until"));
              user.setDeletionPausedUntil(time(rs, "deletion_paused_until"));
              return user;
            },
            account);
    if (rows.size() > 1) throw unavailable();
    return rows.isEmpty() ? null : rows.get(0);
  }

  private void saveLifecycle(User user) {
    jdbc.update(
        "update user set deletion_lifecycle_state=?,deletion_read_only_until=?,"
            + "deletion_paused_until=? where user_id=?",
        user.getDeletionLifecycleState().name(),
        user.getDeletionReadOnlyUntil(),
        user.getDeletionPausedUntil(),
        user.getUserId());
  }

  private Intent lockedIntent(ClaimedDeletion claim) {
    var intent =
        jdbc.queryForObject(
            "select * from identity_maintenance_intent where operation_id=? for update",
            (rs, index) -> intent(rs),
            claim.intent.operation);
    if (intent == null) throw unavailable();
    return intent;
  }

  private Intent intent(ResultSet rs) throws SQLException {
    return new Intent(
        rs.getString("operation_id"),
        rs.getString("realm_id"),
        rs.getString("realm_name"),
        rs.getObject("tenant_id", Long.class),
        rs.getString("claim_token"),
        rs.getString("status"),
        rs.getString("frozen_body"),
        rs.getString("owner_client"),
        rs.getString("owner_subject"));
  }

  private FrozenDeletion frozen(ClaimedDeletion claim, String body) {
    var value = codec.read(body, JsonNode.class);
    var fields = new java.util.HashSet<String>();
    value.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(Set.of("target", "operationId", "expectedState", "change"))
        || !claim.intent.operation.equals(value.path("operationId").asText())
        || !codec.canonical(target(claim)).equals(codec.canonical(value.path("target")))
        || !value.path("expectedState").isTextual()
        || !value.path("expectedState").asText().matches("[0-9a-f]{64}")
        || !value.path("change").isObject()
        || !value.path("change").isEmpty()
        || !body.equals(codec.canonical(value))) throw unavailable();
    return new FrozenDeletion(claim, body);
  }

  private Map<String, Object> target(ClaimedDeletion claim) {
    Map<String, Object> origin = new TreeMap<>();
    origin.put("kind", "DELETION");
    origin.put("workflowId", claim.intent.operation);
    for (String key : Set.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
      origin.put(key, null);
    Map<String, Object> target = new TreeMap<>();
    target.put("accountId", claim.account);
    target.put("tenantId", claim.intent.tenant == null ? null : claim.intent.tenant.toString());
    target.put("origin", origin);
    return target;
  }

  private TaskActorCredentials actor(ClaimedDeletion claim) {
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    if (!actor.clientId().equals(claim.intent.ownerClient)
        || !actor.serviceSubject().equals(claim.intent.ownerSubject)) throw unavailable();
    return actor;
  }

  private String request(String command, ClaimedDeletion claim, String body) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    var actor = actor(claim);
    var token = broker.issue(TaskPurpose.ACCOUNT_MAINTENANCE);
    if (token.actor() != TaskActor.ACCOUNT_ADMIN) throw unavailable();
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(token.accessToken());
    headers.set(
        "X-ORISO-Task-Context",
        codec.maintenanceProof(
            command, realmName, realmId, claim.account, actor, codec.read(body, JsonNode.class)));
    var endpoint =
        UriComponentsBuilder.fromUriString(authServer)
            .pathSegment(
                "realms",
                realmName,
                "oriso-account-maintenance",
                "v1",
                "accounts",
                claim.account,
                command)
            .build()
            .encode()
            .toUri();
    try {
      return http
          .exchange(endpoint, HttpMethod.POST, new HttpEntity<>(body, headers), String.class)
          .getBody();
    } catch (RuntimeException failure) {
      // Never propagate provider bodies, headers, bearer or native diagnostics into scheduler logs.
      throw unavailable();
    }
  }

  private <T> T transaction(Supplier<T> work) {
    var template = new TransactionTemplate(transactions);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template.execute(status -> work.get());
  }

  private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toLocalDateTime();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Scheduled scoped identity deletion unavailable");
  }

  private record Intent(
      String operation,
      String realmId,
      String realmName,
      Long tenant,
      String generation,
      String status,
      String frozen,
      String ownerClient,
      String ownerSubject) {}

  private static final class ClaimedDeletion {
    private final ScheduledTaskClaimService.ClaimLease lease;
    private final String account;
    private final LocalDateTime deletionStarted;
    private final String generation;
    private final Intent intent;

    private ClaimedDeletion(
        ScheduledTaskClaimService.ClaimLease lease,
        String account,
        LocalDateTime deletionStarted,
        String generation,
        Intent intent) {
      this.lease = lease;
      this.account = account;
      this.deletionStarted = deletionStarted;
      this.generation = generation;
      this.intent = intent;
    }
  }

  private static final class FrozenDeletion {
    private final ClaimedDeletion claim;
    private final String body;

    private FrozenDeletion(ClaimedDeletion claim, String body) {
      this.claim = claim;
      this.body = body;
    }
  }
}
