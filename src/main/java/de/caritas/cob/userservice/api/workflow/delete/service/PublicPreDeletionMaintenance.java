package de.caritas.cob.userservice.api.workflow.delete.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Original private public-start intent, strict native ACK, and one owning JPA continuation. */
@RequiredArgsConstructor
public final class PublicPreDeletionMaintenance {
  private final @NonNull IdentityClientConfig mode;
  private final @NonNull AuthenticatedUser human;
  private final @NonNull AdminScope scope;
  private final @NonNull HttpServletRequest request;
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull EntityManager entities;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull RestTemplate http;
  private final @NonNull IdentityManaging passwords;
  private final @NonNull Clock clock;
  private final @NonNull String authServer;
  private final @NonNull String publicIssuer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;
  private static final String NAMESPACE = "PRE_DELETION";
  private Map<String, Set<String>> consultantRemovalGroups = Map.of();
  private Map<
          String,
          List<
              de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ReviewedMaintenanceProfiles
                  .Profile>>
      consultantRemovalProfiles = Map.of();

  public void configureConsultantRemovalProfiles(
      Map<String, Set<String>> groups,
      Map<
              String,
              List<
                  de.caritas.cob.userservice.api.adapters.keycloak.lifecycle
                      .ReviewedMaintenanceProfiles.Profile>>
          profiles) {
    var immutable = new TreeMap<String, Set<String>>();
    groups.forEach((name, roles) -> immutable.put(name, Set.copyOf(roles)));
    if (new HashSet<>(immutable.values()).size() != immutable.size()) throw unavailable();
    consultantRemovalGroups = Map.copyOf(immutable);
    var selectors =
        new TreeMap<
            String,
            List<
                de.caritas.cob.userservice.api.adapters.keycloak.lifecycle
                    .ReviewedMaintenanceProfiles.Profile>>();
    profiles.forEach((kind, values) -> selectors.put(kind, List.copyOf(values)));
    consultantRemovalProfiles = Map.copyOf(selectors);
  }

  private de.caritas.cob.userservice.api.admin.service.agency
          .ConsultantAgencyDeletionValidationService
      consultantEligibility;
  private static final Set<String> PREFLIGHT_REASONS =
      Set.of(
          "CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS",
          "CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE",
          "CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_HAS_OPEN_ENQUIRIES");

  public void configureConsultantEligibility(
      de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyDeletionValidationService
          eligibility) {
    consultantEligibility = Objects.requireNonNull(eligibility);
  }

  /**
   * Only this owner issues a committed native refusal or authenticated no-dispatch preflight
   * terminal.
   */
  public sealed interface VerifiedTerminal
      permits VerifiedForbidden, VerifiedConflict, VerifiedValidation {
    UUID operationId();

    int status();
  }

  public static final class VerifiedForbidden extends ForbiddenException
      implements VerifiedTerminal {
    private final UUID operationId;

    private VerifiedForbidden(UUID operationId) {
      super("Pre-deletion was refused");
      this.operationId = operationId;
    }

    public UUID operationId() {
      return operationId;
    }

    public int status() {
      return 403;
    }
  }

  public static final class VerifiedConflict extends ConflictException implements VerifiedTerminal {
    private final UUID operationId;

    private VerifiedConflict(UUID operationId) {
      super("Pre-deletion was refused");
      this.operationId = operationId;
    }

    public UUID operationId() {
      return operationId;
    }

    public int status() {
      return 409;
    }
  }

  public static final class VerifiedValidation extends CustomValidationHttpStatusException
      implements VerifiedTerminal {
    private final UUID operationId;

    private VerifiedValidation(
        UUID operationId,
        de.caritas.cob.userservice.api.exception.httpresponses.customheader
                .HttpStatusExceptionReason
            reason) {
      super(reason);
      this.operationId = operationId;
    }

    public UUID operationId() {
      return operationId;
    }

    public int status() {
      return 400;
    }
  }

  private static final Set<String> SOURCE =
      Set.of(
          "v",
          "kind",
          "method",
          "route",
          "humanSubject",
          "humanIssuer",
          "humanClient",
          "humanTenantId",
          "humanCreateDate",
          "humanValidUntil",
          "forceDeleteSessions",
          "originalSql",
          "target",
          "requestDigest",
          "realmId",
          "realmName",
          "ownerClient",
          "ownerSubject",
          "mac");

  public boolean isFiveActorMode() {
    return mode.isFiveActorMode();
  }

  public void startSelfAsker(String password, Consumer<AcknowledgedPreDeletion> continuation) {
    if (password == null || password.isBlank() || password.length() > 4096)
      throw new BadRequestException("The current password is required");
    start("SELF_ASKER", human.getUserId(), null, password, continuation);
  }

  public void startAdminAsker(String account, Consumer<AcknowledgedPreDeletion> continuation) {
    start("ADMIN_ASKER", account, null, null, continuation);
  }

  public void startAdminConsultant(
      String account, Boolean force, Consumer<AcknowledgedPreDeletion> continuation) {
    start("ADMIN_CONSULTANT", account, force, null, continuation);
  }

  private record ActualHuman(
      String subject,
      String issuer,
      String client,
      Long tenant,
      long expires,
      String bearer,
      String administrativeAuthority) {}

  private record Intent(
      String operation,
      String account,
      Long tenant,
      String ownerClient,
      String ownerSubject,
      String digest,
      String target,
      String source,
      String body,
      String receipt,
      String applied,
      String status) {}

  private void start(
      String kind,
      String account,
      Boolean force,
      String password,
      Consumer<AcknowledgedPreDeletion> continuation) {
    outside();
    Objects.requireNonNull(continuation);
    uuid(account);
    var current = actualHuman();
    String operation = operation();
    route(kind, account);
    var actor = actor();
    String digest =
        codec.digest(
            "public-pre-deletion-request/v1", binding(kind, account, force, password, current));
    PreDeletionSqlSnapshot.Snapshot passwordSource = null;
    if (kind.equals("SELF_ASKER")) {
      passwordSource =
          tx(
              () -> {
                if (find(operation, true) != null) return null;
                var actual = PreDeletionSqlSnapshot.capture(jdbc, codec, false, account, true);
                exclusive(account, false);
                authorize(kind, account, current, actual);
                requireEligible(actual);
                return actual;
              });
      if (passwordSource != null) validateCurrentPassword(passwordSource.core(), password);
    }
    var authenticatedSource = passwordSource;
    Intent intent =
        tx(
            () ->
                prepare(
                    kind, account, operation, force, digest, current, actor, authenticatedSource));
    if ("PREPARED".equals(intent.status)) {
      Intent original = intent;
      intent = tx(() -> sessionPreflight(original));
    }
    if ("PREPARED".equals(intent.status) && kind.equals("ADMIN_CONSULTANT")) {
      if (consultantEligibility == null) throw unavailable();
      var preflight = new PendingConsultantPreflight(this, intent);
      try {
        var reason = consultantEligibility.validatePreflight(preflight);
        if (reason != null) {
          Intent original = intent;
          intent = tx(() -> refusePreflight(original, reason.name()));
        }
      } finally {
        preflight.active = false;
      }
    }
    if ("PREFLIGHT_REFUSED".equals(intent.status))
      throw new VerifiedValidation(
          UUID.fromString(intent.operation),
          de.caritas.cob.userservice.api.exception.httpresponses.customheader
              .HttpStatusExceptionReason.valueOf(
              source(intent).path("preflightRefusal").textValue()));
    if ("REFUSED".equals(intent.status)) throw refusal(intent, actor);
    if ("APPLIED".equals(intent.status)) {
      String result =
          kind.equals("SELF_ASKER")
              ? call(intent, "deactivate/outcome", null)
              : call(intent, command(intent), current);
      verifySameResult(intent, result);
      return;
    }
    boolean acknowledgedInThisCall = false;
    boolean frozenInThisCall = false;
    if ("PREPARED".equals(intent.status)) {
      if (kind.equals("SELF_ASKER")) {
        var source = source(intent);
        requireOriginalDeadline(source);
        if (authenticatedSource == null)
          validateCurrentPassword(source.path("originalSql"), password);
      }
      String read = call(intent, "read", current);
      NativePreparation state = projection(intent, read);
      Intent original = intent;
      intent = tx(() -> freeze(original, state));
      frozenInThisCall = true;
    }
    if ("FROZEN".equals(intent.status)) {
      String response;
      if (kind.equals("SELF_ASKER")) {
        response = frozenInThisCall ? null : savedOutcome(intent);
        if (response == null) {
          requireOriginalDeadline(source(intent));
          response = call(intent, command(intent), current);
        }
      } else response = call(intent, command(intent), current);
      String verified = verify(intent, response);
      Intent original = intent;
      intent = tx(() -> acknowledge(original, verified));
      acknowledgedInThisCall = true;
    }
    if (!"ACKNOWLEDGED".equals(intent.status)) throw unavailable();
    if (!acknowledgedInThisCall) {
      String result =
          kind.equals("SELF_ASKER")
              ? call(intent, "deactivate/outcome", null)
              : call(intent, command(intent), current);
      verifySameResult(intent, result);
    }
    var frame = new AcknowledgedPreDeletion(this, intent);
    try {
      continuation.accept(frame);
      if (!frame.completed) throw unavailable();
    } finally {
      frame.active = false;
    }
  }

  /** Existing scheduler calls only this bounded saved-only continuation; no target is supplied. */
  public int recoverSelfAskerOutcomes(int limit, Consumer<AcknowledgedPreDeletion> continuation) {
    outside();
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("Recovery batch must be between one and100");
    Objects.requireNonNull(continuation);
    int completed = 0;
    var candidates =
        jdbc.queryForList(
            "select operation_id from identity_account_operation_intent where operation_kind=? and status in ('FROZEN','ACKNOWLEDGED') and target_json like ? order by updated_at,operation_id limit ?",
            String.class,
            NAMESPACE,
            "%\"kind\":\"SELF\"%",
            limit);
    for (String operation : candidates) {
      try {
        Intent initial =
            tx(
                () -> {
                  var found = find(operation, false);
                  if (found == null || !"SELF_ASKER".equals(source(found).path("kind").asText()))
                    throw unavailable();
                  checked(found);
                  return find(operation, true);
                });
        String response = savedOutcome(initial);
        if (response == null) continue;
        String verified = verify(initial, response);
        Intent acknowledged = tx(() -> acknowledge(initial, verified));
        var frame = new AcknowledgedPreDeletion(this, acknowledged);
        try {
          continuation.accept(frame);
          if (!frame.completed) throw unavailable();
          completed++;
        } finally {
          frame.active = false;
        }
      } catch (RuntimeException unknown) {
        // Exact immutable pending ownership remains. One unknown may not starve later rows.
      } finally {
        tx(
            () -> {
              // The retry hint column is DATETIME(6); finer clock digits must not retain a tie.
              var attemptedAt =
                  Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
              jdbc.update(
                  "update identity_account_operation_intent set updated_at=case when updated_at>=? then timestampadd(microsecond,1,updated_at) else ? end where operation_id=? and operation_kind=? and status in ('FROZEN','ACKNOWLEDGED')",
                  attemptedAt,
                  attemptedAt,
                  operation,
                  NAMESPACE);
              return null;
            });
      }
    }
    return completed;
  }

  /** Only this actual owner can create an ACK frame, after strict receipt and durable SQL ACK. */
  public static final class AcknowledgedPreDeletion {
    private final PublicPreDeletionMaintenance owner;
    private final Intent original;
    private final Thread thread = Thread.currentThread();
    private final Instant validUntil;
    private Object localResource;
    private de.caritas.cob.userservice.api.service.chat.MembersOnlyRevocation.PreparedRemoval
        related;
    private boolean active = true, completed, completingConsultant;

    private AcknowledgedPreDeletion(PublicPreDeletionMaintenance owner, Intent original) {
      this.owner = owner;
      this.original = original;
      validUntil = owner.clock.instant().plusSeconds(60);
    }

    public void requireCurrent() {
      if (!active
          || thread != Thread.currentThread()
          || !owner.clock.instant().isBefore(validUntil)) throw unavailable();
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        if (localResource == null
            || localResource
                != TransactionSynchronizationManager.getResource(owner.jdbc.getDataSource()))
          throw unavailable();
        owner.checked(original);
      } else
        owner.tx(
            () -> {
              owner.checked(original);
              return null;
            });
    }

    public String accountId() {
      requireCurrent();
      return original.account;
    }

    public Long tenantId() {
      requireCurrent();
      return original.tenant;
    }

    public void requireConsultantCurrent() {
      requireCurrent();
      if (!"ADMIN_CONSULTANT".equals(owner.source(original).path("kind").asText()))
        throw forbidden();
    }

    public void requireCurrentConsultant(Consultant independentlyLoadedOriginal) {
      requireConsultantCurrent();
      if (!TransactionSynchronizationManager.isActualTransactionActive()
          || localResource == null
          || independentlyLoadedOriginal == null
          || !owner.entities.contains(independentlyLoadedOriginal)
          || independentlyLoadedOriginal != owner.entities.find(Consultant.class, original.account))
        throw conflict();
    }

    /** Only a private issuer-bound preparation can freeze the related-row custody. */
    public void freezeRelatedRemoval(
        de.caritas.cob.userservice.api.service.chat.MembersOnlyRevocation.PreparedRemoval
            preparation) {
      requireConsultantCurrent();
      if (!TransactionSynchronizationManager.isActualTransactionActive()
          || localResource == null
          || related != null && related != preparation) throw conflict();
      var found = owner.checked(original);
      var existing = owner.relatedPlan(found);
      preparation.requireRequestFor(
          this, owner.source(found).path("forceDeleteSessions").asBoolean());
      var plan = preparation.frozenPlanFor(this, owner.codec);
      if ("FROZEN".equals(existing.path("state").asText())) {
        if (!existing.equals(plan)) throw conflict();
      } else {
        String frozen =
            PreDeletionRelatedCustody.freeze(owner.codec, owner.relatedBinding(found), plan);
        if (owner.jdbc.update(
                "update identity_account_operation_intent set applied_json=?,updated_at=? where operation_id=? and operation_kind=? and status='ACKNOWLEDGED' and applied_json=? and intent_body=? and receipt=?",
                frozen,
                owner.now(),
                found.operation,
                NAMESPACE,
                found.applied,
                found.body,
                found.receipt)
            != 1) throw conflict();
      }
      related = preparation;
    }

    public void requireFrozenRelatedRemoval(
        de.caritas.cob.userservice.api.service.chat.MembersOnlyRevocation.PreparedRemoval
            preparation) {
      requireConsultantCurrent();
      if (related != preparation
          || localResource == null
          || !owner
              .relatedPlan(owner.checked(original))
              .equals(preparation.frozenPlanFor(this, owner.codec))) throw conflict();
    }

    public void requireRelatedFinal(
        de.caritas.cob.userservice.api.service.chat.MembersOnlyRevocation.PreparedRemoval
            preparation) {
      if (!active
          || thread != Thread.currentThread()
          || !owner.clock.instant().isBefore(validUntil)
          || related != preparation
          || !completingConsultant
          || localResource == null
          || localResource
              != TransactionSynchronizationManager.getResource(owner.jdbc.getDataSource()))
        throw unavailable();
    }

    public void runOwnedLocal(Runnable localEffect) {
      owner.local(this, localEffect, false, false, null);
    }

    public void completeUser(Consumer<User> localEffect) {
      owner.local(this, null, true, false, localEffect);
    }

    public void completeConsultant(Consumer<Consultant> localEffect) {
      owner.local(this, null, true, true, localEffect);
    }

    @Override
    public String toString() {
      return "AcknowledgedPreDeletion[ownership=redacted]";
    }
  }

  private void local(
      AcknowledgedPreDeletion frame,
      Runnable action,
      boolean complete,
      boolean consultant,
      Consumer<?> effect) {
    outside();
    frame.requireCurrent();
    if (frame.completed) throw unavailable();
    tx(
        () -> {
          Intent intent = checked(frame.original);
          if (!"ACKNOWLEDGED".equals(intent.status)) throw unavailable();
          JsonNode original = source(intent).path("originalSql");
          if (complete && consultant != original.path("kind").asText().equals("CONSULTANT"))
            throw forbidden();
          var before = snapshot(intent, true);
          frame.localResource = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
          try {
            if (complete) {
              Class<?> type = consultant ? Consultant.class : User.class;
              entities.detach(entities.getReference(type, intent.account));
              Object actual = entities.find(type, intent.account, LockModeType.PESSIMISTIC_WRITE);
              if (actual == null) throw conflict();
              if (consultant) {
                if (frame.related == null) throw unavailable();
                var plan = frame.related.frozenPlanFor(frame, codec);
                if (!relatedPlan(intent).equals(plan)) throw conflict();
                frame.completingConsultant = true;
                frame.related.requireReadyFor(frame);
                @SuppressWarnings("unchecked")
                var callback = (Consumer<Consultant>) effect;
                callback.accept((Consultant) actual);
                frame.related.requireAppliedFor(frame);
              } else {
                @SuppressWarnings("unchecked")
                var callback = (Consumer<User>) effect;
                callback.accept((User) actual);
              }
            } else action.run();
            entities.flush();
            requireAdministratorPreserved(intent);
            var after = snapshot(intent, true);
            if (complete) {
              requirePostState(before, after, consultant);
              if (jdbc.update(
                      "update identity_account_operation_intent set status='APPLIED',domain_applied=true,applied_json=?,updated_at=? where operation_id=? and operation_kind=? and status='ACKNOWLEDGED' and intent_body=? and receipt=?",
                      completedSql(intent, after),
                      now(),
                      intent.operation,
                      NAMESPACE,
                      intent.body,
                      intent.receipt)
                  != 1) throw conflict();
            } else if (!before.core().equals(after.core())) throw conflict();
          } finally {
            frame.completingConsultant = false;
            frame.localResource = null;
          }
          return null;
        });
    if (complete) frame.completed = true;
  }

  private void requirePostState(
      PreDeletionSqlSnapshot.Snapshot before,
      PreDeletionSqlSnapshot.Snapshot after,
      boolean consultant) {
    if (!PreDeletionSqlSnapshot.sameBirth(before.core(), after.core())) throw conflict();
    var changed =
        new HashSet<>(
            Set.of(
                "update_date",
                "delete_date",
                "deletion_lifecycle_state",
                "deletion_read_only_until",
                "deletion_paused_by"));
    if (consultant)
      changed.addAll(
          Set.of(
              "status",
              "public_slug",
              "pending_public_slug",
              "public_slug_status",
              "public_slug_reviewed_at"));
    var left = before.full().deepCopy();
    var right = after.full().deepCopy();
    ((ObjectNode) left).remove(changed);
    ((ObjectNode) right).remove(changed);
    if (!left.equals(right)
        || after.full().path("delete_date").isNull()
        || after.full().path("deletion_read_only_until").isNull()
        || !"READ_ONLY_SAFEGUARD".equals(after.full().path("deletion_lifecycle_state").asText())
        || consultant && !"IN_DELETION".equals(after.full().path("status").asText()))
      throw conflict();
  }

  private Intent prepare(
      String kind,
      String account,
      String operation,
      Boolean force,
      String digest,
      ActualHuman current,
      TaskActorCredentials actor,
      PreDeletionSqlSnapshot.Snapshot authenticatedSource) {
    var actual =
        PreDeletionSqlSnapshot.capture(jdbc, codec, kind.equals("ADMIN_CONSULTANT"), account, true);
    exclusive(account, kind.equals("ADMIN_CONSULTANT"));
    authorize(kind, account, current, actual);
    if (authenticatedSource != null && !authenticatedSource.core().equals(actual.core()))
      throw conflict();
    Intent old = find(operation, true);
    if (old != null) {
      var source = source(old);
      matchHuman(source, current);
      if (!digest.equals(old.digest)
          || !kind.equals(source.path("kind").asText())
          || !source
              .path("forceDeleteSessions")
              .equals(codec.read(codec.canonical(force), JsonNode.class))) throw conflict();
      checked(old);
      return old;
    }
    requireEligible(actual);
    if (!jdbc.queryForList(
            "select operation_id from identity_account_operation_intent where account_id=? and operation_kind=? and status in ('PREPARED','FROZEN','ACKNOWLEDGED') for update",
            account,
            NAMESPACE)
        .isEmpty()) throw conflict();
    String humanBirth = humanBirth(kind, current);
    var target = target(kind, account, operation, current, actual.core());
    var value = codec.read("{}", ObjectNode.class);
    value.put("v", 1);
    value.put("kind", kind);
    value.put("method", request.getMethod());
    value.put("route", request.getRequestURI());
    value.put("humanSubject", current.subject);
    value.put("humanIssuer", current.issuer);
    value.put("humanClient", current.client);
    if (current.tenant == null) value.putNull("humanTenantId");
    else value.put("humanTenantId", current.tenant);
    value.put("humanCreateDate", humanBirth);
    value.put("humanValidUntil", current.expires);
    value.set("forceDeleteSessions", codec.read(codec.canonical(force), JsonNode.class));
    value.set("originalSql", actual.core());
    if (kind.equals("ADMIN_CONSULTANT")) {
      var preserved = AdministratorPreservation.capture(jdbc, codec, account, true);
      if (preserved != null) {
        requireRemovalCatalogue(preserved);
        if (!preserved.path("tenantId").equals(actual.core().path("tenantId"))
            || !new UsernameTranscoder()
                .decodeUsername(preserved.path("username").asText())
                .equals(
                    new UsernameTranscoder()
                        .decodeUsername(actual.core().path("username").asText())))
          throw forbidden();
        value.set("preservedAdmin", preserved);
      }
    }
    value.set("target", target);
    value.put("requestDigest", digest);
    value.put("realmId", realmId);
    value.put("realmName", realmName);
    value.put("ownerClient", actor.clientId());
    value.put("ownerSubject", actor.serviceSubject());
    value.put("mac", codec.digest("public-pre-deletion-source/v1", value));
    jdbc.update(
        "insert into identity_account_operation_intent(operation_id,operation_kind,realm_id,realm_name,account_id,tenant_id,owner_client,owner_subject,request_digest,target_json,source_json,status,domain_applied,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,'PREPARED',false,?)",
        operation,
        NAMESPACE,
        realmId,
        realmName,
        account,
        actual.core().path("tenantId").isNull() ? null : actual.core().path("tenantId").longValue(),
        actor.clientId(),
        actor.serviceSubject(),
        digest,
        codec.canonical(target),
        codec.canonical(value),
        now());
    return find(operation, true);
  }

  /** Read-only PREPARED frame issued only to the fixed agency eligibility validator. */
  public static final class PendingConsultantPreflight {
    private final PublicPreDeletionMaintenance owner;
    private final Intent intent;
    private final Thread thread = Thread.currentThread();
    private boolean active = true;

    private PendingConsultantPreflight(PublicPreDeletionMaintenance owner, Intent intent) {
      this.owner = owner;
      this.intent = intent;
    }

    private void checked() {
      if (!active || thread != Thread.currentThread()) throw unavailable();
      var held = owner.checked(intent);
      if (!"PREPARED".equals(held.status)
          || !"ADMIN_CONSULTANT".equals(owner.source(held).path("kind").asText()))
        throw unavailable();
      var human = owner.actualHuman();
      owner.matchHuman(owner.source(held), human);
      owner.authorize("ADMIN_CONSULTANT", held.account, human, owner.snapshot(held, true));
    }

    public String accountId() {
      return intent.account;
    }

    public void requireCurrent() {
      owner.outside();
      owner.tx(
          () -> {
            checked();
            return null;
          });
    }

    public void runOwnedLocal(Runnable validation) {
      owner.outside();
      owner.tx(
          () -> {
            checked();
            validation.run();
            checked();
            return null;
          });
    }
  }

  private Intent sessionPreflight(Intent original) {
    var intent = checked(original);
    if (!"PREPARED".equals(intent.status) || intent.body != null || intent.receipt != null)
      throw conflict();
    var data = source(intent);
    if (!"ADMIN_CONSULTANT".equals(data.path("kind").asText())
        || data.path("forceDeleteSessions").asBoolean()
        || jdbc.queryForList(
                "select id from session where consultant_id=? and status in (?,?) for update",
                intent.account,
                de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS.ordinal(),
                de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_ARCHIVE.ordinal())
            .isEmpty()) return intent;
    return refusePreflight(intent, "CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS");
  }

  private Intent refusePreflight(Intent original, String reason) {
    var intent = checked(original);
    if (!PREFLIGHT_REASONS.contains(reason)
        || !"PREPARED".equals(intent.status)
        || intent.body != null
        || intent.receipt != null) throw conflict();
    var refusal = (ObjectNode) source(intent).deepCopy();
    refusal.remove("mac");
    refusal.put("preflightRefusal", reason);
    refusal.put("mac", codec.digest("public-pre-deletion-source/v1", refusal));
    if (jdbc.update(
            "update identity_account_operation_intent set status='PREFLIGHT_REFUSED',source_json=?,updated_at=? where operation_id=? and operation_kind=? and status='PREPARED' and source_json=? and intent_body is null and receipt is null",
            codec.canonical(refusal),
            now(),
            intent.operation,
            NAMESPACE,
            intent.source)
        != 1) throw conflict();
    return find(intent.operation, true);
  }

  private void validateCurrentPassword(JsonNode originalSql, String password) {
    outside();
    String encoded = originalSql.path("username").asText();
    String decoded = new UsernameTranscoder().decodeUsername(encoded);
    boolean accepted;
    try {
      accepted = passwords.validatePasswordIgnoring2fa(decoded, password);
      if (!accepted && !encoded.equals(decoded))
        accepted = passwords.validatePasswordIgnoring2fa(encoded, password);
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
    if (!accepted) throw new BadRequestException("The current password was not accepted");
  }

  private void requireEligible(PreDeletionSqlSnapshot.Snapshot actual) {
    if (!actual.full().path("delete_date").isNull()
        || !actual.full().path("deletion_lifecycle_state").isNull()
            && !"ACTIVE".equals(actual.full().path("deletion_lifecycle_state").asText()))
      throw conflict();
  }

  private Intent freeze(Intent original, NativePreparation state) {
    Intent found = checked(original);
    if (!"PREPARED".equals(found.status) || found.body != null) throw conflict();
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(found.target, JsonNode.class));
    body.put("operationId", found.operation);
    body.put("expectedState", state.state());
    body.set("change", codec.read(codec.canonical(state.change()), JsonNode.class));
    if (jdbc.update(
            "update identity_account_operation_intent set intent_body=?,status='FROZEN',updated_at=? where operation_id=? and operation_kind=? and status='PREPARED' and intent_body is null",
            codec.canonical(body),
            now(),
            found.operation,
            NAMESPACE)
        != 1) throw conflict();
    return find(found.operation, true);
  }

  private Intent acknowledge(Intent original, String receipt) {
    Intent found = checked(original);
    verify(found, receipt);
    if ("ACKNOWLEDGED".equals(found.status) || "APPLIED".equals(found.status)) {
      if (!Objects.equals(found.receipt, receipt)) throw conflict();
      return found;
    }
    if (!"FROZEN".equals(found.status) || !Objects.equals(original.body, found.body))
      throw conflict();
    String related =
        source(found).path("kind").asText().equals("ADMIN_CONSULTANT")
            ? PreDeletionRelatedCustody.pending(codec, relatedBinding(found, receipt))
            : null;
    if (jdbc.update(
            "update identity_account_operation_intent set receipt=?,applied_json=?,status='ACKNOWLEDGED',updated_at=? where operation_id=? and operation_kind=? and status='FROZEN' and intent_body=?",
            receipt,
            related,
            now(),
            found.operation,
            NAMESPACE,
            found.body)
        != 1) throw conflict();
    return find(found.operation, true);
  }

  private Intent checked(Intent original) {
    var data = source(original);
    boolean consultant = data.path("kind").asText().equals("ADMIN_CONSULTANT");
    exclusive(original.account, consultant);
    requireAdministratorPreserved(original);
    var actual = PreDeletionSqlSnapshot.capture(jdbc, codec, consultant, original.account, true);
    Intent found = find(original.operation, true);
    if (found == null
        || !original.source.equals(found.source)
        || !original.target.equals(found.target)
        || !original.digest.equals(found.digest)
        || original.body != null && !original.body.equals(found.body)) throw conflict();
    JsonNode expected =
        "APPLIED".equals(found.status) ? appliedCore(found) : data.path("originalSql");
    if (!PreDeletionSqlSnapshot.requireCore(expected).equals(actual.core())) throw conflict();
    if (Set.of("ACKNOWLEDGED", "APPLIED").contains(found.status)) verify(found, found.receipt);
    if ("ACKNOWLEDGED".equals(found.status)) {
      if (consultant) relatedPlan(found);
      else if (found.applied != null) throw conflict();
    }
    return found;
  }

  private String completedSql(Intent intent, PreDeletionSqlSnapshot.Snapshot after) {
    if (!source(intent).has("preservedAdmin")) return codec.canonical(after.core());
    var value =
        codec.read(
            codec.canonical(
                Map.of(
                    "representation",
                    "CONSULTANT_IDENTITY_REMOVED_V1",
                    "operationId",
                    intent.operation,
                    "binding",
                    relatedBinding(intent),
                    "consultant",
                    after.core(),
                    "deleteDate",
                    after.full().path("delete_date"))),
            ObjectNode.class);
    value.put("mac", codec.digest("public-pre-deletion-source/v1", value));
    return codec.canonical(value);
  }

  private JsonNode completedRemoval(Intent intent) {
    if (!"APPLIED".equals(intent.status) || !source(intent).has("preservedAdmin"))
      throw unavailable();
    var value = codec.read(intent.applied, ObjectNode.class);
    JsonNode administratorDeletion = value.remove("administratorDeletion");
    var fields = new HashSet<String>();
    value.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(
            Set.of("representation", "operationId", "binding", "consultant", "deleteDate", "mac"))
        || !"CONSULTANT_IDENTITY_REMOVED_V1".equals(value.path("representation").asText())
        || !intent.operation.equals(value.path("operationId").asText())
        || !relatedBinding(intent).equals(value.path("binding"))
        || !value.path("deleteDate").isTextual()
        || Instant.parse(value.path("deleteDate").asText()).getEpochSecond() <= 0)
      throw unavailable();
    String mac = value.remove("mac").asText();
    if (!mac.equals(codec.digest("public-pre-deletion-source/v1", value))) throw unavailable();
    value.put("mac", mac);
    if (administratorDeletion != null) {
      requireAdministratorDeletion(intent, mac, administratorDeletion);
      value.set("administratorDeletion", administratorDeletion);
    }
    var original = source(intent).path("originalSql");
    var result = PreDeletionSqlSnapshot.requireCore(value.path("consultant"));
    for (String field :
        Set.of("kind", "accountId", "tenantId", "createDate", "username", "matrixUserId"))
      if (!original.path(field).equals(result.path(field))) throw unavailable();
    if (!intent.receipt.equals(verify(intent, intent.receipt))) throw unavailable();
    return value;
  }

  private void requireAdministratorDeletion(Intent original, String binding, JsonNode value) {
    var data = value.deepCopy();
    var fields = new HashSet<String>();
    data.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(
            Set.of("representation", "removalBinding", "administrator", "body", "receipt", "mac"))
        || !"SUBSEQUENT_ADMINISTRATOR_DELETION_V1".equals(data.path("representation").asText())
        || !binding.equals(data.path("removalBinding").asText())
        || !source(original).path("preservedAdmin").equals(data.path("administrator")))
      throw unavailable();
    String mac = ((ObjectNode) data).remove("mac").asText();
    if (!mac.equals(codec.digest("public-pre-deletion-source/v1", data))) throw unavailable();
    var body = data.path("body");
    if (!original.account.equals(body.path("target").path("accountId").asText())
        || !source(original)
            .path("target")
            .path("tenantId")
            .equals(body.path("target").path("tenantId"))
        || !"HUMAN_ADMIN".equals(body.path("target").path("origin").path("kind").asText())
        || original.account.equals(body.path("target").path("origin").path("humanSubject").asText())
        || !body.path("change").isObject()
        || !body.path("change").isEmpty()) throw unavailable();
    if (!data.path("receipt")
        .asText()
        .equals(
            codec.verifyMaintenanceDeleteReceipt(
                data.path("receipt").asText(), realmId, actor(), body))) throw unavailable();
  }

  public String retainedConsultantRemovalBinding(
      de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService.ScopedRemoval
          removal) {
    removal.requireActive();
    removal.requireJpaOwner(entities.getEntityManagerFactory());
    var completed = completedConsultantRemoval(removal.accountId());
    if (completed == null) throw unavailable();
    if (jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?",
            Long.class,
            removal.accountId())
        == 0L) {
      requireCompletedRetainedConsultantPurge(removal, completed);
    } else {
      completed.requireRetainedConsultant(false);
      completed.captureAdministrator();
    }
    return completed.binding();
  }

  public void requireCompletedRetainedConsultantPurge(
      de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService.ScopedRemoval
          removal) {
    removal.requireActive();
    removal.requireJpaOwner(entities.getEntityManagerFactory());
    var completed = completedConsultantRemoval(removal.accountId());
    if (completed == null) throw unavailable();
    requireCompletedRetainedConsultantPurge(removal, completed);
  }

  private void requireCompletedRetainedConsultantPurge(
      de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService.ScopedRemoval
          removal,
      CompletedConsultantRemoval completed) {
    if (!completed.applied.has("administratorDeletion")) throw unavailable();
    var subsequent = completed.applied.path("administratorDeletion");
    requireAdministratorDeletion(completed.original, completed.binding(), subsequent);
    if (!subsequent
        .path("body")
        .path("operationId")
        .asText()
        .equals(request.getHeader("Idempotency-Key"))) throw unavailable();
    for (String table : Set.of("consultant", "language", "consultant_topic", "consultant_agency"))
      if (jdbc.queryForObject(
              "select count(*) from " + table + " where consultant_id=?",
              Long.class,
              removal.accountId())
          != 0L) throw unavailable();
    if (AdministratorPreservation.capture(jdbc, codec, removal.accountId(), true) != null
        || jdbc.queryForObject(
                "select count(*) from admin_agency where admin_id=?",
                Long.class,
                removal.accountId())
            != 0L) throw unavailable();
    var rows =
        jdbc.queryForList(
            "select * from identity_maintenance_intent where subject_type='CONSULTANT' and subject_id=? for update",
            removal.accountId());
    if (rows.size() != 1) throw unavailable();
    var row = rows.getFirst();
    if (!"APPLIED".equals(row.get("status"))
        || !realmId.equals(row.get("realm_id"))
        || !realmName.equals(row.get("realm_name"))
        || !completed.original.ownerClient.equals(row.get("owner_client"))
        || !completed.original.ownerSubject.equals(row.get("owner_subject"))
        || !Objects.equals(
            completed.original.tenant,
            row.get("tenant_id") == null ? null : ((Number) row.get("tenant_id")).longValue())
        || !completed.receipt().equals(row.get("receipt_json"))
        || row.get("expected_state") != null) throw unavailable();
    String raw = (String) row.get("frozen_body");
    var envelope = codec.read(raw, ObjectNode.class);
    var fields = new HashSet<String>();
    envelope.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(Set.of("representation", "originalSql", "body", "bindingDigest"))
        || !raw.equals(codec.canonical(envelope))) throw unavailable();
    String mac = envelope.remove("bindingDigest").asText();
    if (!mac.equals(codec.claimedScheduledConsultantDeletionIntentDigest(envelope))
        || !"CLAIMED_RETAINED_CONSULTANT_CLEANUP_V1"
            .equals(envelope.path("representation").asText())) throw unavailable();
    var local = envelope.path("originalSql");
    var body = envelope.path("body");
    if (!removal.accountId().equals(local.path("consultantId").asText())
        || !completed.applied.path("deleteDate").equals(local.path("deleteDate"))
        || !completed
            .applied
            .path("deleteDate")
            .asText()
            .equals(
                ((java.sql.Timestamp) row.get("deletion_started_at"))
                    .toLocalDateTime()
                    .toInstant(java.time.ZoneOffset.UTC)
                    .toString())
        || !completed.operation().equals(body.path("removalOperation").asText())
        || !completed.binding().equals(body.path("removalBinding").asText())
        || !row.get("operation_id").equals(body.path("operationId").asText())
        || !AdministratorPreservation.sameBirth(
            completed.preservedAdministrator(), body.path("preservedAdmin"))) throw unavailable();
    for (String field : Set.of("tenantId", "createDate", "username", "matrixUserId"))
      if (!completed.applied.path("consultant").path(field).equals(local.path(field)))
        throw unavailable();
  }

  /** Called only inside the actual private Administrator producer's verified DELETED callback. */
  public void recordSubsequentAdministratorDeletion(
      de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService.ScopedRemoval
          removal,
      String binding,
      String nativeBody,
      String receipt) {
    removal.requireActive();
    removal.requireJpaOwner(entities.getEntityManagerFactory());
    if (TransactionSynchronizationManager.getResource(removal) != removal
        || !entities.isJoinedToTransaction()) throw unavailable();
    var completed = completedConsultantRemoval(removal.accountId());
    if (completed == null || !completed.binding().equals(binding)) throw unavailable();
    completed.requireRetainedConsultant(false);
    if (AdministratorPreservation.capture(jdbc, codec, removal.accountId(), true) != null
        || jdbc.queryForObject(
                "select count(*) from admin_agency where admin_id=?",
                Long.class,
                removal.accountId())
            != 0L) throw unavailable();
    var value =
        codec.read(
            codec.canonical(
                Map.of(
                    "representation",
                    "SUBSEQUENT_ADMINISTRATOR_DELETION_V1",
                    "removalBinding",
                    binding,
                    "administrator",
                    completed.preservedAdministrator(),
                    "body",
                    codec.read(nativeBody, JsonNode.class),
                    "receipt",
                    receipt)),
            ObjectNode.class);
    value.put("mac", codec.digest("public-pre-deletion-source/v1", value));
    requireAdministratorDeletion(completed.original, binding, value);
    var applied = codec.read(completed.original.applied, ObjectNode.class);
    if (applied.has("administratorDeletion")) {
      if (!applied.path("administratorDeletion").equals(value)) throw unavailable();
      return;
    }
    applied.set("administratorDeletion", value);
    if (jdbc.update(
            "update identity_account_operation_intent set applied_json=? where operation_id=? and operation_kind=? and status='APPLIED' and applied_json=?",
            codec.canonical(applied),
            completed.operation(),
            NAMESPACE,
            completed.original.applied)
        != 1) throw unavailable();
  }

  private JsonNode appliedCore(Intent intent) {
    return source(intent).has("preservedAdmin")
        ? completedRemoval(intent).path("consultant")
        : codec.read(intent.applied, JsonNode.class);
  }

  /**
   * Fixed retained-Consultant proof only. It confers no authority over the remaining native Admin.
   */
  static final class CompletedConsultantRemoval {
    private final PublicPreDeletionMaintenance owner;
    private final Intent original;
    private final JsonNode applied;

    private CompletedConsultantRemoval(
        PublicPreDeletionMaintenance owner, Intent original, JsonNode applied) {
      this.owner = owner;
      this.original = original;
      this.applied = applied;
    }

    String operation() {
      return original.operation;
    }

    String binding() {
      return applied.path("mac").asText();
    }

    String receipt() {
      return original.receipt;
    }

    JsonNode preservedAdministrator() {
      return sourceAdministrator();
    }

    private JsonNode sourceAdministrator() {
      return owner.source(original).path("preservedAdmin");
    }

    JsonNode captureAdministrator() {
      var current =
          AdministratorPreservation.capture(owner.jdbc, owner.codec, original.account, true);
      if (current == null) {
        if (!applied.has("administratorDeletion")) throw unavailable();
        owner.requireAdministratorDeletion(
            original, binding(), applied.path("administratorDeletion"));
        return sourceAdministrator();
      }
      if (applied.has("administratorDeletion")
          || !AdministratorPreservation.sameBirth(sourceAdministrator(), current))
        throw unavailable();
      return current;
    }

    void requireAdministratorPreserved(JsonNode captured) {
      var current = captureAdministrator();
      if (applied.has("administratorDeletion")) {
        if (!AdministratorPreservation.sameBirth(captured, current)) throw unavailable();
      } else if (!captured.equals(current)) throw unavailable();
    }

    void requireRetainedConsultant(boolean mayBeAbsent) {
      if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
      Intent actual = owner.find(original.operation, true);
      if (actual == null
          || !original.source.equals(actual.source)
          || !original.body.equals(actual.body)
          || !original.receipt.equals(actual.receipt)
          || !applied.equals(owner.completedRemoval(actual))) throw unavailable();
      var rows =
          owner.jdbc.queryForList(
              "select consultant_id,tenant_id,create_date,delete_date,username from consultant where consultant_id=? for update",
              original.account);
      if (rows.isEmpty() && mayBeAbsent) return;
      if (rows.size() != 1) throw unavailable();
      var row = rows.getFirst();
      var core = applied.path("consultant");
      if (!original.account.equals(row.get("consultant_id"))
          || !Objects.equals(
              original.tenant,
              row.get("tenant_id") == null ? null : ((Number) row.get("tenant_id")).longValue())
          || !core.path("username").asText().equals(row.get("username"))
          || !core.path("createDate")
              .asText()
              .equals(
                  ((java.sql.Timestamp) row.get("create_date"))
                      .toLocalDateTime()
                      .toInstant(java.time.ZoneOffset.UTC)
                      .toString())
          || row.get("delete_date") == null
          || !applied
              .path("deleteDate")
              .asText()
              .equals(
                  ((java.sql.Timestamp) row.get("delete_date"))
                      .toLocalDateTime()
                      .toInstant(java.time.ZoneOffset.UTC)
                      .toString())) throw unavailable();
    }
  }

  CompletedConsultantRemoval completedConsultantRemoval(String account) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    CompletedConsultantRemoval result = null;
    for (String operation :
        jdbc.queryForList(
            "select operation_id from identity_account_operation_intent where operation_kind=? and account_id=? and status='APPLIED' order by operation_id for update",
            String.class,
            NAMESPACE,
            account)) {
      Intent intent = find(operation, true);
      if (!source(intent).has("preservedAdmin")) continue;
      if (result != null) throw unavailable();
      result = new CompletedConsultantRemoval(this, intent, completedRemoval(intent));
    }
    return result;
  }

  private JsonNode relatedBinding(Intent intent) {
    return relatedBinding(intent, intent.receipt);
  }

  private JsonNode relatedBinding(Intent intent, String receipt) {
    return PreDeletionRelatedCustody.binding(
        codec,
        intent.operation,
        intent.account,
        intent.ownerClient,
        intent.ownerSubject,
        source(intent).path("mac").asText(),
        intent.body,
        receipt);
  }

  private JsonNode relatedPlan(Intent intent) {
    return PreDeletionRelatedCustody.require(codec, intent.applied, relatedBinding(intent));
  }

  private PreDeletionSqlSnapshot.Snapshot snapshot(Intent intent, boolean lock) {
    return PreDeletionSqlSnapshot.capture(
        jdbc,
        codec,
        source(intent).path("kind").asText().equals("ADMIN_CONSULTANT"),
        intent.account,
        lock);
  }

  private Intent find(String operation, boolean lock) {
    var rows =
        jdbc.query(
            "select * from identity_account_operation_intent where operation_id=?"
                + (lock ? " for update" : ""),
            (r, i) -> {
              if (!NAMESPACE.equals(r.getString("operation_kind"))
                  || !realmId.equals(r.getString("realm_id"))
                  || !realmName.equals(r.getString("realm_name"))) throw conflict();
              String phase = r.getString("status");
              if (!Set.of(
                          "PREPARED",
                          "FROZEN",
                          "ACKNOWLEDGED",
                          "APPLIED",
                          "REFUSED",
                          "PREFLIGHT_REFUSED")
                      .contains(phase)
                  || r.getBoolean("domain_applied") != "APPLIED".equals(phase)
                  || "APPLIED".equals(phase) && r.getString("applied_json") == null
                  || !Set.of("APPLIED", "ACKNOWLEDGED").contains(phase)
                      && r.getString("applied_json") != null) throw conflict();
              return new Intent(
                  r.getString("operation_id"),
                  r.getString("account_id"),
                  r.getObject("tenant_id", Long.class),
                  r.getString("owner_client"),
                  r.getString("owner_subject"),
                  r.getString("request_digest"),
                  r.getString("target_json"),
                  r.getString("source_json"),
                  r.getString("intent_body"),
                  r.getString("receipt"),
                  r.getString("applied_json"),
                  r.getString("status"));
            },
            operation);
    if (rows.size() > 1) throw conflict();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private JsonNode source(Intent intent) {
    if (intent == null || intent.source == null) throw unavailable();
    var value = codec.read(intent.source, ObjectNode.class);
    var fields = new HashSet<String>();
    value.fieldNames().forEachRemaining(fields::add);
    boolean preflightRefused = "PREFLIGHT_REFUSED".equals(intent.status);
    var sourceFields = new HashSet<>(SOURCE);
    if (preflightRefused) sourceFields.add("preflightRefusal");
    if (value.has("preservedAdmin")) {
      sourceFields.add("preservedAdmin");
      var preserved = AdministratorPreservation.require(value.path("preservedAdmin"));
      if (!"ADMIN_CONSULTANT".equals(value.path("kind").asText())
          || !intent.account.equals(preserved.path("accountId").asText())
          || !value.path("originalSql").path("tenantId").equals(preserved.path("tenantId")))
        throw unavailable();
    }
    if (!fields.equals(sourceFields)
        || preflightRefused
            && (!"ADMIN_CONSULTANT".equals(value.path("kind").textValue())
                || !PREFLIGHT_REASONS.contains(value.path("preflightRefusal").asText())
                || value.path("forceDeleteSessions").asBoolean()
                    && "CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS"
                        .equals(value.path("preflightRefusal").textValue()))
        || !value.path("v").isIntegralNumber()
        || !value.path("v").canConvertToInt()
        || value.path("v").intValue() != 1
        || !Set.of("SELF_ASKER", "ADMIN_ASKER", "ADMIN_CONSULTANT")
            .contains(value.path("kind").asText())) throw unavailable();
    String signature = value.path("mac").textValue();
    value.remove("mac");
    if (signature == null
        || !signature.matches("[0-9a-f]{64}")
        || !signature.equals(codec.digest("public-pre-deletion-source/v1", value)))
      throw unavailable();
    value.put("mac", signature);
    var actor = actor();
    if (!realmId.equals(value.path("realmId").asText())
        || !realmName.equals(value.path("realmName").asText())
        || !actor.clientId().equals(intent.ownerClient)
        || !actor.serviceSubject().equals(intent.ownerSubject)
        || !intent.ownerClient.equals(value.path("ownerClient").asText())
        || !intent.ownerSubject.equals(value.path("ownerSubject").asText())
        || !intent.digest.equals(value.path("requestDigest").asText())
        || !intent.account.equals(value.path("originalSql").path("accountId").asText())
        || !codec.read(intent.target, JsonNode.class).equals(value.path("target")))
      throw unavailable();
    var original = PreDeletionSqlSnapshot.requireCore(value.path("originalSql"));
    String kind = value.path("kind").asText();
    if (!kind.equals("ADMIN_CONSULTANT") && !"USER".equals(original.path("kind").asText())
        || kind.equals("ADMIN_CONSULTANT") && !"CONSULTANT".equals(original.path("kind").asText())
        || !Objects.equals(
            intent.tenant,
            original.path("tenantId").isNull() ? null : original.path("tenantId").longValue())
        || !intent.digest.matches("[0-9a-f]{64}")
        || !"DELETE".equals(value.path("method").textValue())
        || !publicIssuer.equals(value.path("humanIssuer").textValue())
        || !value.path("humanValidUntil").isIntegralNumber()
        || !value.path("humanValidUntil").canConvertToLong()
        || value.path("humanValidUntil").longValue() <= 0
        || !value.path("humanCreateDate").isTextual()
        || !value.path("forceDeleteSessions").isNull()
            && !value.path("forceDeleteSessions").isBoolean()) throw unavailable();
    if (!kind.equals("ADMIN_CONSULTANT") && !value.path("forceDeleteSessions").isNull())
      throw unavailable();
    uuid(value.path("humanSubject").textValue());
    if (value.path("humanClient").textValue() == null
        || value.path("humanClient").asText().isBlank()
        || value.path("humanClient").asText().length() > 255) throw unavailable();
    var birth = Instant.parse(value.path("humanCreateDate").asText());
    if (birth.getEpochSecond() <= 0
        || !birth.toString().equals(value.path("humanCreateDate").asText())) throw unavailable();
    var humanTenant = value.path("humanTenantId");
    if (!humanTenant.isNull()
        && (!humanTenant.isIntegralNumber()
            || !humanTenant.canConvertToLong()
            || humanTenant.longValue() < 0)) throw unavailable();
    Long tenant = humanTenant.isNull() ? null : humanTenant.longValue();
    String suffix = routeSuffix(kind, intent.account);
    if (!Set.of(suffix, "/service" + suffix).contains(value.path("route").textValue()))
      throw unavailable();
    var authorityNode = value.path("target").path("origin").path("humanAuthority");
    String originalAuthority = authorityNode.textValue();
    if (kind.equals("SELF_ASKER")
        ? !authorityNode.isNull()
        : originalAuthority == null
            || !Set.of("ORDINARY_ACCOUNT_OPERATIONS_V1", "PROTECTED_ACCOUNT_OPERATIONS_V1")
                .contains(originalAuthority)) throw unavailable();
    var originalHuman =
        new ActualHuman(
            value.path("humanSubject").asText(),
            publicIssuer,
            value.path("humanClient").asText(),
            tenant,
            value.path("humanValidUntil").longValue(),
            null,
            originalAuthority);
    if (kind.equals("SELF_ASKER")
            && (!intent.account.equals(originalHuman.subject)
                || !Objects.equals(intent.tenant, tenant))
        || !target(kind, intent.account, intent.operation, originalHuman, original)
            .equals(value.path("target"))) throw unavailable();
    if ("PREPARED".equals(intent.status) || preflightRefused) {
      if (intent.body != null || intent.receipt != null) throw unavailable();
    } else {
      var body = codec.read(intent.body, JsonNode.class);
      var bodyFields = new HashSet<String>();
      body.fieldNames().forEachRemaining(bodyFields::add);
      if (!body.isObject()
          || !bodyFields.equals(Set.of("target", "operationId", "expectedState", "change"))
          || !body.path("target").equals(value.path("target"))
          || !intent.operation.equals(body.path("operationId").textValue())
          || !body.path("expectedState").isTextual()
          || !body.path("expectedState").asText().matches("[0-9a-f]{64}")
          || !validPreDeletionChange(value, body.path("change"))
          || "FROZEN".equals(intent.status) && intent.receipt != null
          || !"FROZEN".equals(intent.status) && intent.receipt == null) throw unavailable();
    }
    return value;
  }

  private void authorize(
      String kind, String account, ActualHuman current, PreDeletionSqlSnapshot.Snapshot actual) {
    if (kind.equals("SELF_ASKER")) {
      if (!account.equals(current.subject)
          || !human.isAdviceSeeker()
          || !Objects.equals(
              current.tenant,
              actual.core().path("tenantId").isNull()
                  ? null
                  : actual.core().path("tenantId").longValue())) throw forbidden();
    } else {
      if (human.getGrantedAuthorities() == null
          || !human.getGrantedAuthorities().contains(Authority.AuthorityValue.USER_ADMIN))
        throw forbidden();
      humanBirth(kind, current);
      scope.assertMay(
          kind.equals("ADMIN_CONSULTANT")
              ? AdminScope.Target.counsellor(account)
              : AdminScope.Target.adviceSeeker(account));
    }
  }

  private String humanBirth(String kind, ActualHuman current) {
    String table = kind.equals("SELF_ASKER") ? "user" : "admin",
        id = kind.equals("SELF_ASKER") ? "user_id" : "admin_id";
    var rows =
        jdbc.queryForList(
            "select * from " + table + " where " + id + "=? for update", current.subject);
    if (rows.size() != 1) throw forbidden();
    var row = rows.getFirst();
    Long tenant = row.get("tenant_id") == null ? null : ((Number) row.get("tenant_id")).longValue();
    if (!Objects.equals(tenant, current.tenant)) throw forbidden();
    if (!kind.equals("SELF_ASKER")
        && (Boolean.TRUE.equals(row.get("managed_paused"))
            || !Set.of("AGENCY", "TENANT", "SUPER").contains(row.get("type")))) throw forbidden();
    Object birth = row.get("create_date");
    if (!(birth instanceof Timestamp date) || date.toInstant().getEpochSecond() <= 0)
      throw forbidden();
    return date.toInstant().toString();
  }

  private void matchHuman(JsonNode source, ActualHuman current) {
    if (!current.subject.equals(source.path("humanSubject").asText())
        || !current.issuer.equals(source.path("humanIssuer").asText())
        || !current.client.equals(source.path("humanClient").asText())
        || !Objects.equals(
            current.tenant,
            source.path("humanTenantId").isNull() ? null : source.path("humanTenantId").longValue())
        || !humanBirth(source.path("kind").asText(), current)
            .equals(source.path("humanCreateDate").asText())) throw forbidden();
  }

  private void exclusive(String account, boolean consultant) {
    if (!consultant
            && !jdbc.queryForList("select admin_id from admin where admin_id=? for update", account)
                .isEmpty()
        || !jdbc.queryForList(
                consultant
                    ? "select user_id from user where user_id=? for update"
                    : "select consultant_id from consultant where consultant_id=? for update",
                account)
            .isEmpty()) throw forbidden();
  }

  private Map<String, Object> binding(
      String kind, String account, Boolean force, String password, ActualHuman current) {
    var data = new TreeMap<String, Object>();
    data.put("kind", kind);
    data.put("account", account);
    data.put("forceDeleteSessions", force);
    data.put("password", password);
    data.put("method", request.getMethod());
    data.put("route", request.getRequestURI());
    data.put("humanSubject", current.subject);
    data.put("humanIssuer", current.issuer);
    data.put("humanClient", current.client);
    data.put("humanTenantId", current.tenant);
    return data;
  }

  private JsonNode target(
      String kind, String account, String operation, ActualHuman current, JsonNode original) {
    var target = codec.read("{}", ObjectNode.class);
    target.put("accountId", account);
    if (original.path("tenantId").isNull()) target.putNull("tenantId");
    else target.put("tenantId", original.path("tenantId").asText());
    var origin = target.putObject("origin");
    origin.put("kind", kind.equals("SELF_ASKER") ? "SELF" : "HUMAN_ADMIN");
    origin.put("workflowId", operation);
    origin.put("humanSubject", current.subject);
    origin.put("humanIssuer", current.issuer);
    origin.put("humanClient", current.client);
    if (kind.equals("SELF_ASKER")) origin.putNull("humanAuthority");
    else origin.put("humanAuthority", current.administrativeAuthority);
    return target;
  }

  private ActualHuman actualHuman() {
    if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken token)
        || !token.isAuthenticated()) throw forbidden();
    var jwt = token.getToken();
    String raw = request.getHeader("Authorization");
    Object client = jwt.getClaims().get("azp");
    if (raw == null
        || raw.length() > 32775
        || !raw.startsWith("Bearer ")
        || !raw.substring(7).equals(jwt.getTokenValue())
        || !jwt.getTokenValue().equals(human.getAccessToken())
        || !Objects.equals(jwt.getSubject(), human.getUserId())
        || jwt.getIssuer() == null
        || !publicIssuer.equals(jwt.getIssuer().toString())
        || !(client instanceof String selected)
        || selected.isBlank()
        || selected.length() > 255
        || jwt.getIssuedAt() == null
        || jwt.getIssuedAt().getEpochSecond() <= 0
        || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(5))
        || jwt.getExpiresAt() == null
        || !jwt.getExpiresAt().isAfter(clock.instant())
        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())) throw forbidden();
    Long tenant = null;
    if (jwt.hasClaim("tenantId")) {
      Object value = jwt.getClaims().get("tenantId");
      if (!(value instanceof String text) || !text.matches("0|[1-9][0-9]{0,18}")) throw forbidden();
      tenant = Long.valueOf(text);
    }
    if (!Objects.equals(tenant, human.getTenantId())) throw forbidden();
    uuid(jwt.getSubject());
    return new ActualHuman(
        jwt.getSubject(),
        publicIssuer,
        (String) client,
        tenant,
        jwt.getExpiresAt().getEpochSecond(),
        raw,
        human.hasRestrictedAgencyPriviliges() && !human.hasTenantLevelAdminRole()
            ? "ORDINARY_ACCOUNT_OPERATIONS_V1"
            : "PROTECTED_ACCOUNT_OPERATIONS_V1");
  }

  private record NativePreparation(String state, Map<String, Object> change) {}

  private NativePreparation projection(Intent intent, String raw) {
    var node = codec.read(raw, JsonNode.class);
    var fields = new HashSet<String>();
    node.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(
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
        || !intent.account.equals(node.path("id").textValue())
        || !node.path("state").isTextual()
        || !node.path("state").asText().matches("[0-9a-f]{64}")) throw unavailable();
    var actual =
        tx(
            () -> {
              checked(intent);
              return snapshot(intent, true);
            });
    if (!new UsernameTranscoder()
            .decodeUsername(actual.full().path("username").asText())
            .equalsIgnoreCase(node.path("username").asText())
        || !Objects.equals(actual.full().path("email").textValue(), node.path("email").textValue())
        || !Objects.equals(
            intent.tenant == null ? null : intent.tenant.toString(),
            node.path("tenantId").textValue())) throw forbidden();
    for (String field : List.of("email", "firstName", "lastName", "tenantId", "preferredLanguage"))
      if (!node.path(field).isNull()
          && (!node.path(field).isTextual() || node.path(field).asText().length() > 512))
        throw unavailable();
    if (!node.path("username").isTextual()
        || node.path("username").asText().isBlank()
        || node.path("username").asText().length() > 255
        || !node.path("effectiveRoles").isArray()
        || node.path("effectiveRoles").size() > 32) throw unavailable();
    var roles = new TreeSet<String>();
    for (var role : node.path("effectiveRoles")) {
      if (!role.isTextual()
          || role.asText().isBlank()
          || role.asText().length() > 255
          || !roles.add(role.asText())) throw forbidden();
    }
    boolean consultant = source(intent).path("kind").asText().equals("ADMIN_CONSULTANT");
    Map<String, Object> change = Map.of();
    if (source(intent).has("preservedAdmin")) {
      change = consultantRemovalChange(source(intent).path("preservedAdmin"), roles);
    } else if (!(consultant
        ? roles.equals(Set.of("consultant"))
            || roles.equals(Set.of("consultant", "group-chat-consultant"))
        : roles.equals(Set.of("user")))) throw forbidden();
    for (String field : List.of("enabled", "emailVerified", "passwordChangeRequired"))
      if (!node.path(field).isBoolean()) throw unavailable();
    return new NativePreparation(node.path("state").textValue(), change);
  }

  private String command(Intent intent) {
    return source(intent).has("preservedAdmin") ? "profile-transition" : "deactivate";
  }

  private void requireAdministratorPreserved(Intent intent) {
    var source = source(intent);
    if (!"ADMIN_CONSULTANT".equals(source.path("kind").asText())) return;
    var actual = AdministratorPreservation.capture(jdbc, codec, intent.account, true);
    if (source.has("preservedAdmin")
        ? !source.path("preservedAdmin").equals(actual)
        : actual != null) throw conflict();
  }

  private String protectedFamily(JsonNode preserved) {
    return switch (preserved.path("type").asText()) {
      case "TENANT" -> "TENANT_ADMIN";
      case "SUPER" -> "PLATFORM_ADMIN";
      default -> throw forbidden();
    };
  }

  private void requireRemovalCatalogue(JsonNode preserved) {
    if (preserved.path("type").asText().equals("AGENCY")) {
      if (consultantRemovalGroups.isEmpty()) throw forbidden();
    } else if (consultantRemovalProfiles
        .getOrDefault(protectedFamily(preserved), List.of())
        .isEmpty()) throw forbidden();
  }

  private Map<String, Object> consultantRemovalChange(JsonNode preserved, Set<String> current) {
    requireRemovalCatalogue(preserved);
    if (!current.contains("consultant")) throw forbidden();
    var desired = new TreeSet<>(current);
    desired.remove("consultant");
    desired.remove("group-chat-consultant");
    if (preserved.path("type").asText().equals("AGENCY")) {
      if (!desired.contains("restricted-agency-admin")
          || consultantRemovalGroups.values().stream().filter(current::equals).count() != 1)
        throw forbidden();
      var groups =
          consultantRemovalGroups.entrySet().stream()
              .filter(e -> e.getValue().equals(desired))
              .toList();
      if (groups.size() != 1) throw forbidden();
      return Map.of(
          "transition", "REMOVE_CONSULTANT_IDENTITY", "group", groups.getFirst().getKey());
    }
    var profiles = consultantRemovalProfiles.get(protectedFamily(preserved));
    if (profiles.stream().filter(p -> p.realmRoles().equals(current)).count() != 1)
      throw forbidden();
    var next = profiles.stream().filter(p -> p.realmRoles().equals(desired)).toList();
    if (next.size() != 1) throw forbidden();
    return Map.of(
        "transition",
        "REMOVE_CONSULTANT_IDENTITY",
        "profileId",
        next.getFirst().id(),
        "profileVersion",
        next.getFirst().version());
  }

  private boolean validPreDeletionChange(JsonNode source, JsonNode change) {
    if (!change.isObject()) return false;
    if (!source.has("preservedAdmin")) return change.isEmpty();
    var fields = new HashSet<String>();
    change.fieldNames().forEachRemaining(fields::add);
    if (!"REMOVE_CONSULTANT_IDENTITY".equals(change.path("transition").asText())) return false;
    var preserved = source.path("preservedAdmin");
    if ("AGENCY".equals(preserved.path("type").asText())) {
      var roles = consultantRemovalGroups.get(change.path("group").asText());
      return fields.equals(Set.of("transition", "group"))
          && roles != null
          && roles.contains("restricted-agency-admin")
          && !roles.contains("consultant")
          && !roles.contains("group-chat-consultant");
    }
    return fields.equals(Set.of("transition", "profileId", "profileVersion"))
        && change.path("profileVersion").isIntegralNumber()
        && consultantRemovalProfiles.getOrDefault(protectedFamily(preserved), List.of()).stream()
                .filter(
                    p ->
                        p.id().equals(change.path("profileId").asText())
                            && p.version() == change.path("profileVersion").longValue()
                            && !p.realmRoles().contains("consultant")
                            && !p.realmRoles().contains("group-chat-consultant"))
                .count()
            == 1;
  }

  private String savedOutcome(Intent intent) {
    try {
      return call(intent, "deactivate/outcome", null);
    } catch (UnknownOutcome unknown) {
      return null;
    }
  }

  private String call(Intent intent, String command, ActualHuman current) {
    outside();
    tx(
        () -> {
          checked(intent);
          return null;
        });
    var actor = actor();
    var token = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    long now = clock.instant().getEpochSecond();
    if (token.actor() != TaskActor.ACCOUNT_ADMIN
        || token.purpose() != TaskPurpose.ACCOUNT_MAINTENANCE
        || !actor.clientId().equals(token.clientId())
        || !actor.serviceSubject().equals(token.subject())
        || !publicIssuer.equals(token.issuer())
        || token.validatedJwtIssuedAt() <= 0
        || token.validatedJwtIssuedAt() > now + 5
        || token.validatedJwtExpiresAt() <= now
        || token.validatedJwtExpiresAt() <= token.validatedJwtIssuedAt()) throw unavailable();
    String body =
        command.equals("read")
            ? codec.canonical(Map.of("target", codec.read(intent.target, JsonNode.class)))
            : intent.body;
    if (body == null) throw unavailable();
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setCacheControl("no-store");
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    headers.setBearerAuth(token.accessToken());
    if (!command.equals("deactivate/outcome")) {
      if (current == null || !current.equals(actualHuman())) throw forbidden();
      headers.set("X-ORISO-Human-Authorization", current.bearer);
    }
    headers.set(
        "X-ORISO-Task-Context",
        command.equals("deactivate/outcome")
            ? codec.preDeletionOutcomeProof(
                realmName, realmId, intent.account, actor, codec.read(body, JsonNode.class))
            : codec.maintenanceProof(
                command,
                realmName,
                realmId,
                intent.account,
                actor,
                codec.read(body, JsonNode.class)));
    var uri =
        UriComponentsBuilder.fromUriString(authServer)
            .pathSegment(
                "realms", realmName, "oriso-account-maintenance", "v1", "accounts", intent.account);
    if (command.equals("deactivate/outcome")) uri.pathSegment("deactivate", "outcome");
    else uri.pathSegment(command);
    try {
      var reply =
          http.exchange(
              uri.build().encode().toUri(),
              HttpMethod.POST,
              new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers),
              byte[].class);
      if (reply.getStatusCode().value() != 200
          || reply.getBody() == null
          || reply.getBody().length == 0
          || reply.getBody().length > 16384
          || !"no-store".equals(reply.getHeaders().getCacheControl())) throw unavailable();
      return codec.utf8(reply.getBody());
    } catch (HttpStatusCodeException refused) {
      if (command.equals("deactivate/outcome") && refused.getStatusCode().value() == 409)
        throw new UnknownOutcome();
      if (command.equals(command(intent))
          && Set.of(403, 409).contains(refused.getStatusCode().value())) {
        try {
          var terminal =
              codec.verifyMaintenanceNoEffectReceipt(
                  codec.utf8(refused.getResponseBodyAsByteArray()),
                  realmId,
                  actor,
                  codec.read(body, JsonNode.class),
                  command);
          if (terminal.status() != refused.getStatusCode().value()
              || refused.getResponseHeaders() == null
              || !"no-store".equals(refused.getResponseHeaders().getCacheControl()))
            throw unavailable();
          String receipt =
              codec.canonical(
                  codec.read(codec.utf8(refused.getResponseBodyAsByteArray()), JsonNode.class));
          tx(
              () -> {
                var found = checked(intent);
                if (!"FROZEN".equals(found.status)) throw conflict();
                if (jdbc.update(
                        "update identity_account_operation_intent set status='REFUSED',receipt=?,updated_at=? where operation_id=? and operation_kind=? and status='FROZEN' and intent_body=?",
                        receipt,
                        now(),
                        intent.operation,
                        NAMESPACE,
                        intent.body)
                    != 1) throw conflict();
                return null;
              });
          throw refusal(find(intent.operation, false), actor);
        } catch (ForbiddenException | ConflictException known) {
          throw known;
        } catch (RuntimeException unsigned) {
          throw unavailable();
        }
      }
      throw unavailable();
    } catch (UnknownOutcome known) {
      throw known;
    } catch (ForbiddenException | ConflictException known) {
      throw known;
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
  }

  private String verify(Intent intent, String response) {
    try {
      return source(intent).has("preservedAdmin")
          ? codec.verifyMaintenanceProfileTransitionReceipt(
              response, realmId, actor(), codec.read(intent.body, JsonNode.class))
          : codec.verifyMaintenancePreDeletionReceipt(
              response, realmId, actor(), codec.read(intent.body, JsonNode.class));
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
  }

  private void verifySameResult(Intent intent, String result) {
    if (!verify(intent, result).equals(intent.receipt)) throw conflict();
  }

  private RuntimeException refusal(Intent intent, TaskActorCredentials actor) {
    if (intent == null || !"REFUSED".equals(intent.status)) throw unavailable();
    var result =
        codec.verifyMaintenanceNoEffectReceipt(
            intent.receipt,
            realmId,
            actor,
            codec.read(intent.body, JsonNode.class),
            command(intent));
    return result.status() == 403
        ? new VerifiedForbidden(UUID.fromString(intent.operation))
        : new VerifiedConflict(UUID.fromString(intent.operation));
  }

  private TaskActorCredentials actor() {
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    if (actor.actor() != TaskActor.ACCOUNT_ADMIN) throw unavailable();
    return actor;
  }

  private void requireOriginalDeadline(JsonNode source) {
    if (!source.path("humanValidUntil").isIntegralNumber()
        || source.path("humanValidUntil").longValue() <= clock.instant().getEpochSecond())
      throw unavailable();
  }

  private void route(String kind, String account) {
    if (!"DELETE".equals(request.getMethod())) throw forbidden();
    String suffix = routeSuffix(kind, account);
    if (!Set.of(suffix, "/service" + suffix).contains(request.getRequestURI())) throw forbidden();
  }

  private String routeSuffix(String kind, String account) {
    return kind.equals("SELF_ASKER")
        ? "/users/account"
        : kind.equals("ADMIN_ASKER")
            ? "/useradmin/askers/" + account
            : "/useradmin/consultants/" + account;
  }

  private String operation() {
    String value = request.getHeader("Idempotency-Key");
    try {
      uuid(value);
      return value;
    } catch (RuntimeException invalid) {
      throw new BadRequestException("A canonical Idempotency-Key is required");
    }
  }

  private static void uuid(String value) {
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw forbidden();
    } catch (RuntimeException invalid) {
      throw forbidden();
    }
  }

  private void outside() {
    if (!mode.isFiveActorMode()
        || TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) throw unavailable();
    if (!(transactions instanceof JpaTransactionManager manager)
        || manager.getDataSource() != jdbc.getDataSource()
        || manager.getEntityManagerFactory() != entities.getEntityManagerFactory())
      throw unavailable();
  }

  private <T> T tx(Supplier<T> action) {
    return new TransactionTemplate(transactions).execute(ignored -> action.get());
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private static final class UnknownOutcome extends RuntimeException {}

  private static ForbiddenException forbidden() {
    return new ForbiddenException("Pre-deletion is not authorised");
  }

  private static ConflictException conflict() {
    return new ConflictException("Original pre-deletion ownership changed");
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Original pre-deletion outcome is unavailable");
  }
}
