package de.caritas.cob.userservice.api.workflow.delete.service;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.ClaimedWork;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteDatabaseAskerAction.PreparedUserDeletion;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** One held deletion intent owns its native ACK and every bounded downstream cleanup. */
@RequiredArgsConstructor
@Slf4j
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
  private java.util.function.Consumer<AcknowledgedDeletion> cleanup;
  private jakarta.persistence.EntityManager entities;

  /** The configured finite consumer receives only a freshly verified private ACK frame. */
  public void configureAcknowledgedCleanup(
      java.util.function.Consumer<AcknowledgedDeletion> cleanup) {
    this.cleanup = Objects.requireNonNull(cleanup);
  }

  /** Missing or foreign JPA ownership remains closed before any downstream external effect. */
  public void configureJpaCleanupOwner(jakarta.persistence.EntityManager entities) {
    this.entities = Objects.requireNonNull(entities);
    requireJpaOwner();
  }

  /** Only this owner issues a live frame from an authenticated durable acknowledgement. */
  public static final class AcknowledgedDeletion {
    private final ScheduledAskerMaintenance owner;
    private final FrozenDeletion frozen;
    private final String receipt;
    private final Thread thread = Thread.currentThread();
    private boolean active = true;
    private Object callbackResource;
    private Object inactivityValidationResource;
    private PreparedUserDeletion finalPreparation;
    private boolean finalAbsencePrepared;
    private PreparedUserDeletion mediaPreparation;
    private boolean absenceMediaPreparation;
    private java.util.Map<String, Boolean> inactivityMedia;

    private AcknowledgedDeletion(
        ScheduledAskerMaintenance owner, FrozenDeletion frozen, String receipt) {
      this.owner = owner;
      this.frozen = frozen;
      this.receipt = receipt;
    }

    public void requireCurrent() {
      requireLive();
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        if (callbackResource == null
            || callbackResource
                != TransactionSynchronizationManager.getResource(
                    Objects.requireNonNull(owner.jdbc.getDataSource()))) throw unavailable();
        owner.requireAcknowledged(this);
      } else {
        owner.transaction(
            () -> {
              owner.requireAcknowledged(this);
              return null;
            });
      }
    }

    public void requireCurrentUser(User independentlyLoadedOriginal) {
      requireCurrent();
      if (independentlyLoadedOriginal == null
          || !owner.sameSnapshot(
              frozen.originalSql,
              AskerDeletionSqlSnapshot.from(independentlyLoadedOriginal, owner.codec),
              frozen.claim.inactivity == null)) throw unavailable();
    }

    public String accountId() {
      requireCurrent();
      return frozen.claim.account;
    }

    public Long tenantId() {
      requireCurrent();
      return frozen.claim.intent.tenant;
    }

    public boolean hasLocalUser() {
      requireCurrent();
      return !frozen.originalSql.isNull();
    }

    public boolean inactivityCause() {
      requireCurrent();
      return frozen.claim.inactivity != null;
    }

    public boolean completed() {
      outsideSql();
      return owner.transaction(() -> "APPLIED".equals(owner.requireAcknowledged(this).status));
    }

    public void runOwnedLocal(Runnable action) {
      Objects.requireNonNull(action);
      outsideSql();
      owner.transaction(
          () -> {
            if (!"ACKNOWLEDGED".equals(owner.requireAcknowledged(this).status)) throw unavailable();
            invokeOwned(action);
            owner.entities.flush();
            owner.requireAcknowledged(this);
            return null;
          });
    }

    /**
     * The final action must issue its real preparation before owned inactivity media is forgotten.
     */
    public void beforeFinalLocalDeletion(
        de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteDatabaseAskerAction
                .PreparedUserDeletion
            prepared) {
      Objects.requireNonNull(prepared);
      outsideSql();
      if (frozen.claim.inactivity == null || frozen.originalSql.isNull()) throw unavailable();
      prepared.requireFor(this);
      mediaPreparation = prepared;
      try {
        frozen.claim.inactivity.forgetDeletionMedia(this);
      } finally {
        mediaPreparation = null;
      }
      prepared.requireFor(this);
      finalPreparation = prepared;
    }

    /** Only original realm-only absence can use the fixed three-action inactivity continuation. */
    public void beforeFinalLocalAbsence() {
      outsideSql();
      if (frozen.claim.inactivity == null || !frozen.originalSql.isNull()) throw unavailable();
      requireCurrent();
      absenceMediaPreparation = true;
      try {
        frozen.claim.inactivity.forgetDeletionMedia(this);
      } finally {
        absenceMediaPreparation = false;
      }
      requireCurrent();
      finalAbsencePrepared = true;
    }

    public boolean completeOwnedLocalDeletion(Runnable finalDelete) {
      Objects.requireNonNull(finalDelete);
      outsideSql();
      return owner.transaction(
          () -> {
            var intent = owner.requireAcknowledged(this);
            if ("APPLIED".equals(intent.status)) return true;
            if (frozen.claim.inactivity != null) {
              if (frozen.originalSql.isNull()) {
                if (!finalAbsencePrepared) throw unavailable();
              } else {
                if (finalPreparation == null) throw unavailable();
                invokeOwned(() -> finalPreparation.requireFor(this));
              }
            }
            invokeOwned(finalDelete);
            owner.entities.flush();
            var actual =
                AskerDeletionSqlSnapshot.capture(
                    owner.jdbc, owner.codec, frozen.claim.account, true);
            if (actual != null) {
              if (!owner.sameSnapshot(frozen.originalSql, actual, frozen.claim.inactivity == null))
                throw unavailable();
              owner.requireAcknowledged(this);
              return false;
            }
            // The original envelope and native receipt survive the final local row removal.
            owner.requireAcknowledgedIntent(this, frozen.claim.inactivity != null);
            if (owner.jdbc.update(
                    "update identity_maintenance_intent set status='APPLIED' where operation_id=? and claim_token=? and status='ACKNOWLEDGED' and frozen_body=? and receipt_json=?",
                    intent.operation,
                    frozen.claim.generation,
                    frozen.envelope,
                    receipt)
                != 1) throw unavailable();
            if (frozen.claim.inactivity != null)
              validateInactivity(() -> frozen.claim.inactivity.completeDeletionOwnedSql(this));
            owner.requireAcknowledged(this);
            return true;
          });
    }

    private void invokeOwned(Runnable action) {
      if (callbackResource != null) throw unavailable();
      callbackResource =
          Objects.requireNonNull(
              TransactionSynchronizationManager.getResource(
                  Objects.requireNonNull(owner.jdbc.getDataSource())));
      try {
        action.run();
      } finally {
        callbackResource = null;
      }
    }

    private void requireLive() {
      if (!active || thread != Thread.currentThread()) throw unavailable();
    }

    /** Only the same private frame in its actual owning SQL resource can check this claim. */
    public void requireClaimedInactivitySql(ClaimedWork candidate) {
      requireLive();
      Object resource =
          TransactionSynchronizationManager.getResource(
              Objects.requireNonNull(owner.jdbc.getDataSource()));
      if (candidate != frozen.claim.inactivity
          || resource == null
          || !TransactionSynchronizationManager.isActualTransactionActive()
          || resource != inactivityValidationResource && resource != callbackResource)
        throw unavailable();
    }

    /** A work owner can revoke only its own live frame when its original claim scope ends. */
    public void closeInactivityClaim(ClaimedWork candidate) {
      if (candidate != frozen.claim.inactivity || thread != Thread.currentThread())
        throw unavailable();
      active = false;
    }

    public void requireInactivityAppliedIntentSql(ClaimedWork candidate) {
      requireClaimedInactivitySql(candidate);
      var actual = owner.requireIntent(frozen.claim);
      if (!"APPLIED".equals(actual.status)
          || !frozen.envelope.equals(actual.frozen)
          || !receipt.equals(actual.receipt)
          || !receipt.equals(
              owner.codec.verifyMaintenanceDeleteReceipt(
                  actual.receipt,
                  owner.realmId,
                  owner.actor(frozen.claim),
                  owner.codec.read(frozen.body, JsonNode.class)))) throw unavailable();
    }

    /** Existing prior access custody needs its actual acknowledged SUSPEND proof. */
    public void requirePriorInactivityCustody() {
      requireCurrent();
      if (frozen.claim.inactivity == null) throw unavailable();
      owner.requirePriorInactivityCustody(this);
    }

    /** The actual same preparation must be active around each owned media-forget call. */
    public void requirePreparedInactivityMedia() {
      requireCurrent();
      if (frozen.claim.inactivity == null) throw unavailable();
      if (frozen.originalSql.isNull()) {
        if (!absenceMediaPreparation) throw unavailable();
      } else {
        if (mediaPreparation == null) throw unavailable();
        mediaPreparation.requireFor(this);
      }
    }

    private void validateInactivity(Runnable check) {
      if (inactivityValidationResource != null) throw unavailable();
      inactivityValidationResource =
          Objects.requireNonNull(
              TransactionSynchronizationManager.getResource(
                  Objects.requireNonNull(owner.jdbc.getDataSource())));
      try {
        check.run();
      } finally {
        inactivityValidationResource = null;
      }
    }

    private void outsideSql() {
      requireLive();
      if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    }
  }

  /** Only an actual held inactivity deletion claim may enter this separate existing cause. */
  public boolean performInactivityDeletion(
      de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.ClaimedWork
          work) {
    Objects.requireNonNull(work);
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    if (cleanup == null || entities == null) return false;
    requireJpaOwner();
    work.requireCurrent();
    String originalRow = work.originalRowJson(), originalSource = work.originalSourceJson();
    var checked = transaction(() -> prepareInactivity(work, originalRow, originalSource));
    FrozenDeletion frozen;
    if (checked.intent.frozen != null) frozen = frozen(checked, checked.intent.frozen);
    else {
      String state = read(checked);
      frozen = transaction(() -> freeze(checked, state));
    }
    if (frozen == null) throw unavailable();
    if (!Set.of("ACKNOWLEDGED", "APPLIED").contains(checked.intent.status)) {
      String response = request("delete", checked, frozen.body);
      String receipt =
          codec.verifyMaintenanceDeleteReceipt(
              response, realmId, actor(checked), codec.read(frozen.body, JsonNode.class));
      if (!transaction(() -> acknowledge(frozen, receipt))) throw unavailable();
    }
    var durable = transaction(() -> lockedIntent(checked));
    String receipt =
        codec.verifyMaintenanceDeleteReceipt(
            durable.receipt, realmId, actor(checked), codec.read(frozen.body, JsonNode.class));
    var frame = new AcknowledgedDeletion(this, frozen, receipt);
    boolean complete = false;
    try {
      frame.requireCurrent();
      if (!frame.completed()) {
        work.captureDeletionMedia(frame);
        frame.inactivityMedia = transaction(() -> inactivityMedia(frame));
        cleanup.accept(frame);
      }
      complete = frame.completed();
      return complete;
    } catch (RuntimeException uncertain) {
      // A real final commit may have succeeded before its reply was lost. Only the same
      // privately issued frame's two-journal/absence proof can resolve that uncertainty.
      if (frame.completed()) {
        complete = true;
        return true;
      }
      throw uncertain;
    } finally {
      if (!complete) frame.active = false;
    }
  }

  private ClaimedDeletion prepareInactivity(
      ClaimedWork work, String originalRow, String originalSource) {
    work.requireOwnedSql();
    var row = codec.read(originalRow, JsonNode.class);
    codec.claimedInactivityDeletionRowDigest(row);
    var source = codec.read(originalSource, JsonNode.class);
    if (!"DELETE".equals(work.action())
        || work.humanInitiated()
        || work.terminalOnly()
        || !source.path("admin").isArray()
        || !source.path("admin").isEmpty()
        || !source.path("consultant").isArray()
        || !source.path("consultant").isEmpty()
        || !source.path("user").isArray()
        || source.path("user").size() > 1
        || row.path("dueAtMicros").longValue()
            > Math.addExact(
                Math.multiplyExact(clock.instant().getEpochSecond(), 1_000_000L),
                clock.instant().getNano() / 1000)) throw unavailable();
    requireExclusiveLocalUser(work.identityId());
    var actual = AskerDeletionSqlSnapshot.capture(jdbc, codec, work.identityId(), true);
    if (source.path("user").isEmpty() != (actual == null)) throw unavailable();
    var original =
        actual == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : actual;
    LocalDateTime claimedAt =
        LocalDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(
                row.path("claimedAtMicros").longValue() / 1_000_000L,
                row.path("claimedAtMicros").longValue() % 1_000_000L * 1000),
            java.time.ZoneOffset.UTC);
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var rows =
        jdbc.query(
            "select * from identity_maintenance_intent where operation_id=? or"
                + " (subject_type='INACTIVITY_ASKER' and subject_id=? and deletion_started_at=?) for update",
            (rs, index) -> intent(rs),
            work.workId(),
            work.identityId(),
            claimedAt);
    if (rows.size() > 1) throw unavailable();
    if (rows.isEmpty()) {
      jdbc.update(
          "insert into identity_maintenance_intent(operation_id,realm_id,realm_name,subject_type,"
              + "subject_id,deletion_started_at,tenant_id,claim_token,claim_until,status,owner_client,owner_subject)"
              + " values(?,?,?,'INACTIVITY_ASKER',?,?,?,?,?,'PREPARED',?,?)",
          work.workId(),
          realmId,
          realmName,
          work.identityId(),
          claimedAt,
          work.tenantId(),
          UUID.randomUUID().toString(),
          LocalDateTime.now(clock).plusSeconds(120),
          actor.clientId(),
          actor.serviceSubject());
    }
    var intent =
        jdbc.queryForObject(
            "select * from identity_maintenance_intent where operation_id=? for update",
            (rs, index) -> intent(rs),
            work.workId());
    if (intent == null
        || !"INACTIVITY_ASKER".equals(intent.subjectType)
        || !work.identityId().equals(intent.subjectId)
        || !claimedAt.equals(intent.deletionStarted)
        || !realmId.equals(intent.realmId)
        || !realmName.equals(intent.realmName)
        || !Objects.equals(work.tenantId(), intent.tenant)
        || !actor.clientId().equals(intent.ownerClient)
        || !actor.serviceSubject().equals(intent.ownerSubject)
        || !Set.of("PREPARED", "FROZEN", "ACKNOWLEDGED").contains(intent.status)
        || "PREPARED".equals(intent.status) && (intent.frozen != null || intent.receipt != null)
        || "FROZEN".equals(intent.status) && (intent.frozen == null || intent.receipt != null)
        || "ACKNOWLEDGED".equals(intent.status)
            && (intent.frozen == null || intent.receipt == null)) throw unavailable();
    String generation = UUID.randomUUID().toString();
    if (jdbc.update(
            "update identity_maintenance_intent set claim_token=?,claim_until=?,last_attempt_at=? where operation_id=?",
            generation,
            LocalDateTime.now(clock).plusSeconds(120),
            LocalDateTime.now(clock),
            intent.operation)
        != 1) throw unavailable();
    intent =
        jdbc.queryForObject(
            "select * from identity_maintenance_intent where operation_id=? for update",
            (rs, index) -> intent(rs),
            work.workId());
    var claim =
        new ClaimedDeletion(
            null,
            work.identityId(),
            claimedAt,
            generation,
            intent,
            original,
            work,
            originalRow,
            originalSource);
    if (intent.frozen != null) {
      var saved = frozen(claim, intent.frozen).originalSql;
      if (!saved.equals(original)) throw unavailable();
    }
    return claim;
  }

  public void performDeletionWorkflow() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    if (cleanup == null || entities == null) return;
    requireJpaOwner();
    var acquired = claims.tryClaimLease(TASK, Duration.ofHours(12));
    if (acquired.isEmpty()) return;
    var lease = acquired.get();
    try {
      int pending = 0;
      // Only local persisted candidates are scanned. Native enumeration never grants a target.
      for (String account :
          jdbc.queryForList(
              "select u.user_id from user u left join identity_maintenance_intent i"
                  + " on i.subject_type='ASKER' and i.subject_id=u.user_id"
                  + " and i.deletion_started_at=u.delete_date where u.delete_date is not null"
                  + " order by case when i.last_attempt_at is null then 0 else 1 end,"
                  + " i.last_attempt_at,u.user_id",
              String.class)) {
        try {
          maintain(lease, account);
        } catch (RuntimeException failure) {
          // Keep the exact local intent, including an unknown frozen RPC, for a later run.
          // A refusal for one account never permits cleanup or prevents another account's ACK.
          pending++;
        }
      }
      if (pending > 0)
        log.warn("Scheduled scoped identity deletion retained {} pending account(s)", pending);
    } finally {
      claims.release(lease);
    }
  }

  private void maintain(ScheduledTaskClaimService.ClaimLease lease, String account) {
    ClaimedDeletion checked = transaction(() -> prepare(lease, account));
    if (checked == null) return;
    FrozenDeletion frozen;
    if ("PREPARED".equals(checked.intent.status)) {
      String state = read(checked);
      frozen = transaction(() -> freeze(checked, state));
    } else frozen = frozen(checked, checked.intent.frozen);
    if (frozen == null) return;
    if (!"ACKNOWLEDGED".equals(checked.intent.status)) {
      String response = request("delete", frozen.claim, frozen.body);
      String receipt =
          codec.verifyMaintenanceDeleteReceipt(
              response, realmId, actor(checked), codec.read(frozen.body, JsonNode.class));
      if (!transaction(() -> acknowledge(frozen, receipt))) throw unavailable();
    }
    var durable = transaction(() -> lockedIntent(checked));
    String receipt =
        codec.verifyMaintenanceDeleteReceipt(
            durable.receipt, realmId, actor(checked), codec.read(frozen.body, JsonNode.class));
    var frame = new AcknowledgedDeletion(this, frozen, receipt);
    try {
      frame.requireCurrent();
      cleanup.accept(frame);
    } finally {
      frame.active = false;
    }
  }

  private ClaimedDeletion prepare(ScheduledTaskClaimService.ClaimLease lease, String account) {
    if (!held(lease)) return null;
    User user = lockedUser(account);
    if (user == null || user.getDeleteDate() == null) return null;
    requireExclusiveLocalUser(account);
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
      user.setDeletionLifecycleState(DeletionLifecycleState.HARD_DELETE_IN_PROGRESS);
      saveLifecycle(user);
      var original = AskerDeletionSqlSnapshot.capture(jdbc, codec, account, true);
      if (original == null) throw unavailable();
      String prepared = preparedEnvelope(original);
      String operation = UUID.randomUUID().toString();
      jdbc.update(
          "insert into identity_maintenance_intent(operation_id,realm_id,realm_name,subject_type,"
              + "subject_id,deletion_started_at,tenant_id,claim_token,claim_until,status,"
              + "owner_client,owner_subject,frozen_body) values(?,?,?,'ASKER',?,?,?,?,?,'PREPARED',?,?,?)",
          operation,
          realmId,
          realmName,
          account,
          user.getDeleteDate(),
          user.getTenantId(),
          UUID.randomUUID().toString(),
          lease.claimedUntil(),
          actor.clientId(),
          actor.serviceSubject(),
          prepared);
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
        || !"ASKER".equals(intent.subjectType)
        || !account.equals(intent.subjectId)
        || !user.getDeleteDate().equals(intent.deletionStarted)
        || !Set.of("PREPARED", "FROZEN", "ACKNOWLEDGED").contains(intent.status))
      throw unavailable();
    if ("PREPARED".equals(intent.status)
            && (intent.frozen == null || intent.receipt != null || intent.expectedState != null)
        || "FROZEN".equals(intent.status) && (intent.frozen == null || intent.receipt != null)
        || "ACKNOWLEDGED".equals(intent.status)
            && (intent.frozen == null || intent.receipt == null)) throw unavailable();
    JsonNode preparedOriginal = null;
    if ("PREPARED".equals(intent.status)) {
      preparedOriginal = preparedSource(intent.frozen);
      var before = AskerDeletionSqlSnapshot.capture(jdbc, codec, account, true);
      if (!sameSnapshot(preparedOriginal, before, false)) throw unavailable();
    }
    String generation = UUID.randomUUID().toString();
    if (jdbc.update(
            "update identity_maintenance_intent set claim_token=?,claim_until=?,last_attempt_at=? where operation_id=?",
            generation,
            lease.claimedUntil(),
            LocalDateTime.now(clock),
            intent.operation)
        != 1) throw unavailable();
    if (!"ACKNOWLEDGED".equals(intent.status)) {
      user.setDeletionLifecycleState(DeletionLifecycleState.HARD_DELETE_IN_PROGRESS);
      saveLifecycle(user);
    }
    var actual = AskerDeletionSqlSnapshot.capture(jdbc, codec, account, true);
    var claimed =
        new ClaimedDeletion(lease, account, user.getDeleteDate(), generation, intent, actual);
    if ("PREPARED".equals(intent.status)) {
      claimed =
          new ClaimedDeletion(
              lease, account, user.getDeleteDate(), generation, intent, preparedOriginal);
    } else if (intent.frozen != null) {
      var original = frozen(claimed, intent.frozen).originalSql;
      if (!sameSnapshot(original, actual, "ACKNOWLEDGED".equals(intent.status)))
        throw unavailable();
      claimed =
          new ClaimedDeletion(lease, account, user.getDeleteDate(), generation, intent, original);
    }
    return claimed;
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
        || !(result.path("tenantId").isNull() || result.path("tenantId").isTextual())
        || !Objects.equals(
            claim.intent.tenant == null ? null : claim.intent.tenant.toString(),
            result.path("tenantId").isNull() ? null : result.path("tenantId").textValue())
        || !result.path("state").isTextual()
        || !result.path("state").asText().matches("[0-9a-f]{64}")
        || !result.path("username").isTextual()
        || result.path("username").textValue().isBlank()
        || result.path("username").textValue().length() > 255
        || !claim.originalSql.isNull()
            && !new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                .decodeUsername(claim.originalSql.path("username").textValue())
                .equals(result.path("username").textValue())
        || !result.path("effectiveRoles").isArray()
        || result.path("effectiveRoles").size() != 1
        || !"user".equals(result.path("effectiveRoles").get(0).textValue())) throw unavailable();
    for (String field : java.util.List.of("enabled", "emailVerified", "passwordChangeRequired"))
      if (!result.path(field).isBoolean()) throw unavailable();
    for (String field : java.util.List.of("email", "firstName", "lastName", "preferredLanguage"))
      if (!result.path(field).isNull()
          && (!result.path(field).isTextual() || result.path(field).textValue().length() > 255))
        throw unavailable();
    return result.path("state").textValue();
  }

  private FrozenDeletion freeze(ClaimedDeletion claim, String state) {
    if (!current(claim)) return null;
    var intent = requireIntent(claim);
    if (!claim.generation.equals(intent.generation)) return null;
    if (!"PREPARED".equals(intent.status)) {
      if (intent.frozen == null) throw unavailable();
      return frozen(claim, intent.frozen);
    }
    String previous = intent.frozen;
    if (claim.inactivity == null) {
      if (!claim.originalSql.equals(preparedSource(previous))) throw unavailable();
    } else if (previous != null) throw unavailable();
    String body =
        codec.canonical(
            Map.of(
                "target",
                target(claim),
                "operationId",
                intent.operation,
                "expectedState",
                state,
                "change",
                Map.of()));
    String envelope = envelope(claim, codec.read(body, JsonNode.class));
    var parameters =
        new java.util.ArrayList<Object>(
            List.of(state, envelope, intent.operation, claim.generation));
    if (previous != null) parameters.add(previous);
    if (jdbc.update(
            "update identity_maintenance_intent set expected_state=?,frozen_body=?,status='FROZEN'"
                + " where operation_id=? and claim_token=? and status='PREPARED' and receipt_json is null and "
                + (previous == null ? "frozen_body is null" : "frozen_body=?"),
            parameters.toArray())
        != 1) throw unavailable();
    var saved = requireIntent(claim);
    if (!"FROZEN".equals(saved.status) || !envelope.equals(saved.frozen) || saved.receipt != null)
      throw unavailable();
    return frozen(claim, envelope);
  }

  private boolean acknowledge(FrozenDeletion frozen, String receipt) {
    var claim = frozen.claim;
    if (!current(claim)) return false;
    var intent = requireIntent(claim);
    if (!claim.generation.equals(intent.generation)) return false;
    if (!"FROZEN".equals(intent.status) || !frozen.envelope.equals(intent.frozen))
      throw unavailable();
    if (!receipt.equals(
        codec.verifyMaintenanceDeleteReceipt(
            receipt, realmId, actor(claim), codec.read(frozen.body, JsonNode.class))))
      throw unavailable();
    if (jdbc.update(
            "update identity_maintenance_intent set receipt_json=?,status='ACKNOWLEDGED'"
                + " where operation_id=? and claim_token=? and status='FROZEN' and frozen_body=? and receipt_json is null",
            receipt,
            intent.operation,
            claim.generation,
            frozen.envelope)
        != 1) throw unavailable();
    // Native ACK is durable, while owned downstream cleanup remains pending and irreversible.
    if (claim.inactivity == null
        && jdbc.update(
                "update user set deletion_lifecycle_state='HARD_DELETE_PARTIAL_FAILURE' where user_id=? and deletion_lifecycle_state='HARD_DELETE_IN_PROGRESS'",
                claim.account)
            != 1) throw unavailable();
    var saved = requireIntent(claim);
    if (!"ACKNOWLEDGED".equals(saved.status)
        || !frozen.envelope.equals(saved.frozen)
        || !receipt.equals(saved.receipt)
        || !(claim.inactivity == null
            ? sameSnapshot(
                frozen.originalSql,
                AskerDeletionSqlSnapshot.capture(jdbc, codec, claim.account, true),
                true)
            : Objects.equals(
                frozen.originalSql.isNull() ? null : frozen.originalSql,
                AskerDeletionSqlSnapshot.capture(jdbc, codec, claim.account, true))))
      throw unavailable();
    return true;
  }

  private boolean current(ClaimedDeletion claim) {
    if (claim.inactivity == null) {
      if (!held(claim.lease)) return false;
    } else claim.inactivity.requireOwnedSql();
    requireExclusiveLocalUser(claim.account);
    var actual = AskerDeletionSqlSnapshot.capture(jdbc, codec, claim.account, true);
    return claim.originalSql.isNull()
        ? actual == null
        : sameSnapshot(claim.originalSql, actual, false);
  }

  private void requireJpaOwner() {
    if (!(transactions instanceof JpaTransactionManager jpa)
        || entities == null
        || !entities.isOpen()
        || jpa.getDataSource() != jdbc.getDataSource()
        || jpa.getEntityManagerFactory() == null
        || jpa.getEntityManagerFactory() != entities.getEntityManagerFactory()) throw unavailable();
  }

  private void requireExclusiveLocalUser(String account) {
    for (String table : java.util.List.of("admin", "consultant"))
      if (jdbc.queryForObject(
              "select count(*) from " + table + " where " + table + "_id=?", Long.class, account)
          != 0L) throw unavailable();
  }

  private boolean sameSnapshot(JsonNode original, JsonNode actual, boolean partial) {
    if (original == null || actual == null || !original.isObject() || !actual.isObject())
      return false;
    var compared = actual.deepCopy();
    if (partial
        && "HARD_DELETE_PARTIAL_FAILURE"
            .equals(compared.path("deletion_lifecycle_state").textValue()))
      ((com.fasterxml.jackson.databind.node.ObjectNode) compared)
          .put("deletion_lifecycle_state", "HARD_DELETE_IN_PROGRESS");
    return original.equals(compared);
  }

  private Intent requireIntent(ClaimedDeletion claim) {
    var actual = lockedIntent(claim);
    actor(claim);
    if (!claim.generation.equals(actual.generation)
        || !(claim.inactivity == null
            ? claim.lease.claimedUntil().equals(actual.claimedUntil)
            : actual.claimedUntil != null
                && claim.intent.claimedUntil.equals(actual.claimedUntil)
                && actual.claimedUntil.isAfter(LocalDateTime.now(clock)))
        || !realmId.equals(actual.realmId)
        || !realmName.equals(actual.realmName)
        || !(claim.inactivity == null ? "ASKER" : "INACTIVITY_ASKER").equals(actual.subjectType)
        || !claim.account.equals(actual.subjectId)
        || !claim.deletionStarted.equals(actual.deletionStarted)
        || !Objects.equals(claim.intent.tenant, actual.tenant)
        || !claim.intent.ownerClient.equals(actual.ownerClient)
        || !claim.intent.ownerSubject.equals(actual.ownerSubject)) throw unavailable();
    return actual;
  }

  private Intent requireAcknowledgedIntent(AcknowledgedDeletion frame) {
    return requireAcknowledgedIntent(frame, false);
  }

  private Intent requireAcknowledgedIntent(AcknowledgedDeletion frame, boolean removedUser) {
    requireJpaOwner();
    if (!frame.active
        || frame.thread != Thread.currentThread()
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(
            Objects.requireNonNull(jdbc.getDataSource()))
        || !entities.isJoinedToTransaction()
        || frame.frozen.claim.inactivity == null && !held(frame.frozen.claim.lease))
      throw unavailable();
    if (frame.frozen.claim.inactivity != null)
      frame.validateInactivity(() -> frame.frozen.claim.inactivity.lockDeletionOwnerSql(frame));
    var intent = requireIntent(frame.frozen.claim);
    if (!Set.of("ACKNOWLEDGED", "APPLIED").contains(intent.status)
        || !frame.frozen.envelope.equals(intent.frozen)
        || !frame.receipt.equals(intent.receipt)) throw unavailable();
    var saved = frozen(frame.frozen.claim, intent.frozen);
    if (!frame.frozen.originalSql.equals(saved.originalSql)
        || !Objects.equals(
            intent.expectedState,
            codec.read(saved.body, JsonNode.class).path("expectedState").textValue())
        || !frame.receipt.equals(
            codec.verifyMaintenanceDeleteReceipt(
                intent.receipt,
                realmId,
                actor(frame.frozen.claim),
                codec.read(saved.body, JsonNode.class)))) throw unavailable();
    requireExclusiveLocalUser(frame.frozen.claim.account);
    if (frame.frozen.claim.inactivity != null) {
      frame.validateInactivity(
          () -> {
            if (removedUser) frame.frozen.claim.inactivity.requireDeletionRemovedSql(frame);
            else frame.frozen.claim.inactivity.requireDeletionCurrentSql(frame);
          });
      if (!removedUser
          && !"APPLIED".equals(intent.status)
          && frame.inactivityMedia != null
          && !frame.inactivityMedia.equals(inactivityMedia(frame))) throw unavailable();
    }
    return intent;
  }

  private Intent requireAcknowledged(AcknowledgedDeletion frame) {
    var intent = requireAcknowledgedIntent(frame);
    var actual = AskerDeletionSqlSnapshot.capture(jdbc, codec, frame.frozen.claim.account, true);
    if ("APPLIED".equals(intent.status)
        ? actual != null
        : frame.frozen.originalSql.isNull()
            ? actual != null
            : !sameSnapshot(
                frame.frozen.originalSql, actual, frame.frozen.claim.inactivity == null))
      throw unavailable();
    return intent;
  }

  private java.util.Map<String, Boolean> inactivityMedia(AcknowledgedDeletion frame) {
    if (frame.frozen.claim.inactivity == null) throw unavailable();
    String currentOwner =
        jdbc.queryForObject(
            "select access_work_id from account_inactivity where identity_id=? and work_id=? for update",
            String.class,
            frame.frozen.claim.account,
            frame.frozen.claim.inactivity.workId());
    if (!frame.frozen.claim.inactivity.workId().equals(currentOwner)) throw unavailable();
    var values = new TreeMap<String, Boolean>();
    jdbc.query(
        "select matrix_user_id,original_locked from account_inactivity_matrix_state where identity_id=? order by matrix_user_id for update",
        rs -> {
          String id = rs.getString(1);
          if (id == null
              || id.isBlank()
              || values.put(id, rs.getBoolean(2)) != null
              || rs.wasNull()) throw unavailable();
        },
        frame.frozen.claim.account);
    var expected = new java.util.TreeSet<String>();
    var sql = frame.frozen.originalSql;
    if (!sql.isNull() && !sql.path("matrix_user_id").isNull())
      expected.add(sql.path("matrix_user_id").textValue());
    if (values.size() > 2 || !values.keySet().equals(expected)) throw unavailable();
    return java.util.Collections.unmodifiableMap(values);
  }

  private void requirePriorInactivityCustody(AcknowledgedDeletion frame) {
    // The old row has no independently retained SQL incarnation. Never infer it from
    // a current row or signed native target alone; the fixed custody column is pending TDD.
    throw unavailable();
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
        rs.getString("owner_subject"),
        rs.getString("subject_type"),
        rs.getString("subject_id"),
        time(rs, "deletion_started_at"),
        time(rs, "claim_until"),
        rs.getString("receipt_json"),
        rs.getString("expected_state"));
  }

  private FrozenDeletion frozen(ClaimedDeletion claim, String envelope) {
    var saved = codec.read(envelope, com.fasterxml.jackson.databind.node.ObjectNode.class);
    var all = new java.util.HashSet<String>();
    saved.fieldNames().forEachRemaining(all::add);
    if (!all.equals(
            claim.inactivity == null
                ? Set.of("representation", "originalSql", "body", "bindingDigest")
                : Set.of(
                    "representation",
                    "originalRow",
                    "rowDigest",
                    "originalSql",
                    "body",
                    "bindingDigest"))
        || !envelope.equals(codec.canonical(saved))
        || !saved.path("bindingDigest").isTextual()) throw unavailable();
    String binding = saved.remove("bindingDigest").textValue();
    if (!binding.equals(
        claim.inactivity == null
            ? codec.claimedScheduledDeletionIntentDigest(saved)
            : codec.claimedInactivityDeletionIntentDigest(saved))) throw unavailable();
    if (claim.inactivity != null
        && (!codec.canonical(saved.path("originalRow")).equals(claim.originalRow)
            || !Objects.equals(
                saved.path("rowDigest").textValue(),
                codec.claimedInactivityDeletionRowDigest(saved.path("originalRow")))))
      throw unavailable();
    var value = saved.path("body");
    String body = codec.canonical(value);
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
    return new FrozenDeletion(claim, body, envelope, saved.path("originalSql"));
  }

  /** Fixed source data only; an explicit null body cannot dispatch a native command. */
  private String preparedEnvelope(JsonNode original) {
    var value =
        codec.read(
            codec.canonical(
                Map.of(
                    "representation",
                    "CLAIMED_SCHEDULED_ASKER_DELETE_V1",
                    "originalSql",
                    original,
                    "body",
                    com.fasterxml.jackson.databind.node.NullNode.getInstance())),
            com.fasterxml.jackson.databind.node.ObjectNode.class);
    value.put("bindingDigest", codec.claimedScheduledDeletionIntentDigest(value));
    return codec.canonical(value);
  }

  private JsonNode preparedSource(String envelope) {
    if (envelope == null) throw unavailable();
    var value = codec.read(envelope, com.fasterxml.jackson.databind.node.ObjectNode.class);
    var fields = new java.util.HashSet<String>();
    value.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(Set.of("representation", "originalSql", "body", "bindingDigest"))
        || !envelope.equals(codec.canonical(value))
        || !value.path("body").isNull()
        || !value.path("bindingDigest").isTextual()) throw unavailable();
    String binding = value.remove("bindingDigest").textValue();
    String expected = codec.claimedScheduledDeletionIntentDigest(value);
    if (!java.security.MessageDigest.isEqual(
        binding.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw unavailable();
    return value.path("originalSql");
  }

  private String envelope(ClaimedDeletion claim, JsonNode body) {
    if (claim.inactivity != null) {
      var value =
          codec.read(
              codec.canonical(
                  Map.of(
                      "representation",
                      "CLAIMED_INACTIVITY_DELETE_V1",
                      "originalRow",
                      codec.read(claim.originalRow, JsonNode.class),
                      "rowDigest",
                      codec.claimedInactivityDeletionRowDigest(
                          codec.read(claim.originalRow, JsonNode.class)),
                      "originalSql",
                      claim.originalSql,
                      "body",
                      body)),
              com.fasterxml.jackson.databind.node.ObjectNode.class);
      value.put("bindingDigest", codec.claimedInactivityDeletionIntentDigest(value));
      return codec.canonical(value);
    }
    var value =
        codec.read(
            codec.canonical(
                Map.of(
                    "representation",
                    "CLAIMED_SCHEDULED_ASKER_DELETE_V1",
                    "originalSql",
                    claim.originalSql,
                    "body",
                    body)),
            com.fasterxml.jackson.databind.node.ObjectNode.class);
    value.put("bindingDigest", codec.claimedScheduledDeletionIntentDigest(value));
    return codec.canonical(value);
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
    requireJpaOwner();
    transaction(
        () -> {
          if (!current(claim)) throw unavailable();
          var intent = requireIntent(claim);
          if ("read".equals(command)) {
            if (!"PREPARED".equals(intent.status)
                || intent.expectedState != null
                || intent.receipt != null) throw unavailable();
            if (claim.inactivity == null) {
              if (!claim.originalSql.equals(preparedSource(intent.frozen))) throw unavailable();
            } else if (intent.frozen != null) throw unavailable();
          } else if (!"delete".equals(command)
              || !"FROZEN".equals(intent.status)
              || !body.equals(frozen(claim, intent.frozen).body)
              || !Objects.equals(
                  intent.expectedState,
                  codec.read(body, JsonNode.class).path("expectedState").textValue()))
            throw unavailable();
          return null;
        });
    var actor = actor(claim);
    var token = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
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
      return http.exchange(endpoint, HttpMethod.POST, new HttpEntity<>(body, headers), String.class)
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
      String ownerSubject,
      String subjectType,
      String subjectId,
      LocalDateTime deletionStarted,
      LocalDateTime claimedUntil,
      String receipt,
      String expectedState) {}

  private static final class ClaimedDeletion {
    private final ScheduledTaskClaimService.ClaimLease lease;
    private final String account;
    private final LocalDateTime deletionStarted;
    private final String generation;
    private final Intent intent;
    private final JsonNode originalSql;
    private final ClaimedWork inactivity;
    private final String originalRow;
    private final String originalSource;

    private ClaimedDeletion(
        ScheduledTaskClaimService.ClaimLease lease,
        String account,
        LocalDateTime deletionStarted,
        String generation,
        Intent intent,
        JsonNode originalSql) {
      this(lease, account, deletionStarted, generation, intent, originalSql, null, null, null);
    }

    private ClaimedDeletion(
        ScheduledTaskClaimService.ClaimLease lease,
        String account,
        LocalDateTime deletionStarted,
        String generation,
        Intent intent,
        JsonNode originalSql,
        ClaimedWork inactivity,
        String originalRow,
        String originalSource) {
      this.lease = lease;
      this.account = account;
      this.deletionStarted = deletionStarted;
      this.generation = generation;
      this.intent = intent;
      this.originalSql = originalSql;
      this.inactivity = inactivity;
      this.originalRow = originalRow;
      this.originalSource = originalSource;
    }
  }

  private static final class FrozenDeletion {
    private final ClaimedDeletion claim;
    private final String body;
    private final String envelope;
    private final JsonNode originalSql;

    private FrozenDeletion(
        ClaimedDeletion claim, String body, String envelope, JsonNode originalSql) {
      this.claim = claim;
      this.body = body;
      this.envelope = envelope;
      this.originalSql = originalSql;
    }
  }
}
