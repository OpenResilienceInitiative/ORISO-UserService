package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Owned SELF OTP reads, enrollment and removal; runtime registration remains separate. */
@RequiredArgsConstructor
public final class SelfAppOtpMaintenance {
  private final @NonNull IdentityClientConfig mode;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull HttpServletRequest request;
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull RestTemplate http;
  private final @NonNull Clock clock;
  private final @NonNull String authServer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;
  private final @NonNull String humanIssuer;

  private de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
      removedConsultants;

  public void configureRemovedConsultantProofs(
      de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance owner) {
    removedConsultants = Objects.requireNonNull(owner);
  }

  public SelfAppOtpMaintenance(
      IdentityClientConfig mode,
      AuthenticatedUser authenticatedUser,
      HttpServletRequest request,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      RestTemplate http,
      Clock clock,
      String authServer,
      String realmName,
      String realmId) {
    this(
        mode,
        authenticatedUser,
        request,
        jdbc,
        transactions,
        actors,
        broker,
        codec,
        http,
        clock,
        authServer,
        realmName,
        realmId,
        authServer.replaceAll("/+$", "") + "/realms/" + realmName);
  }

  public boolean isFiveActorMode() {
    return mode.isFiveActorMode();
  }

  /** Actual authenticated GET /users/data; native SELF validates current grants on every read. */
  public de.caritas.cob.userservice.api.port.out.IdentityProfile readCurrentProfile() {
    if (!mode.isFiveActorMode() || TransactionSynchronizationManager.isActualTransactionActive())
      throw unavailable();
    var self = actualSelf("GET", Set.of("/users/data", "/service/users/data"));
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    if (!canonicalUuid(actor.serviceSubject())) throw unavailable();
    var projection = readProjection(self, target(self, UUID.randomUUID().toString()), actor);
    var roles = new java.util.HashSet<String>();
    var nativeRoles = projection.path("effectiveRoles");
    if (!nativeRoles.isArray() || nativeRoles.isEmpty() || nativeRoles.size() > 200)
      throw unavailable();
    for (var role : nativeRoles) {
      if (!role.isTextual() || !text(role.textValue(), 255) || !roles.add(role.textValue()))
        throw unavailable();
    }
    // A retained, removed Consultant is not an active identity. Native permission is independent;
    // this check prevents metadata from describing a locally removed identity as active again.
    if (!roles.equals(authenticatedUser.getRoles())
        || roles.contains("consultant")
            != (self.kind == SelfAccountOwner.Kind.CONSULTANT || self.consultant)
        || roles.contains("user") != (self.kind == SelfAccountOwner.Kind.USER)) throw forbidden();
    transaction(
        () -> {
          lockedDomain(self);
          return true;
        });
    return new de.caritas.cob.userservice.api.port.out.IdentityProfile(
        self.account,
        projection.path("username").textValue(),
        profileText(projection.get("firstName"), 255),
        profileText(projection.get("lastName"), 255),
        profileText(projection.get("email"), 320));
  }

  private static String profileText(JsonNode value, int maximum) {
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()
        || value.textValue().length() > maximum
        || value.textValue().chars().anyMatch(c -> c < 32 || c == 127)) throw unavailable();
    return value.textValue();
  }

  public de.caritas.cob.userservice.api.identity.IdentityOtpCredential readCredential() {
    if (!mode.isFiveActorMode() || TransactionSynchronizationManager.isActualTransactionActive())
      throw unavailable();
    var self = actualSelf("GET", Set.of("/users/data", "/service/users/data"));
    transaction(
        () -> {
          lockedDomain(self);
          return true;
        });
    try {
      var actor = actors.require(TaskPurpose.OTP);
      if (!canonicalUuid(actor.serviceSubject())) throw unavailable();
      var target = target(self, UUID.randomUUID().toString());
      var projection = readProjection(self, target, actor);
      var token = machine(self, actor, TaskPurpose.OTP);
      var materialized = codec.read("{}", ObjectNode.class);
      materialized.set("target", target);
      materialized.put("username", projection.path("username").textValue());
      materialized.putNull("otp");
      var headers = headers(self, token);
      headers.set("X-ORISO-Account-Target", codec.canonical(target));
      headers.set(
          "X-ORISO-Task-Context",
          codec.selfReadOtpProof(
              realmName,
              realmId,
              self.account,
              actor,
              materialized,
              Math.min(self.expires, token.validatedJwtExpiresAt())));
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || self.expires <= clock.instant().getEpochSecond()) throw unavailable();
      var uri =
          UriComponentsBuilder.fromUriString(authServer)
              .pathSegment(
                  "realms",
                  realmName,
                  "otp-config",
                  "fetch-otp-setup-info",
                  projection.path("username").textValue())
              .build()
              .encode()
              .toUri();
      var response = http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
      if (response.getStatusCode().value() != 200) throw unavailable();
      return SelfOtpReadWire.credential(codec, response.getBody());
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
  }

  public boolean startEmail(String pendingEmail) {
    return execute(null, null, pendingEmail, ids -> {}, Operation.EMAIL_START);
  }

  public boolean finishEmail(String code, BiConsumer<String, Set<Long>> applyEmailAndInvite) {
    return finishEmail(
        code,
        applyEmailAndInvite,
        ids -> {
          throw unavailable();
        });
  }

  public boolean finishEmail(
      String code,
      BiConsumer<String, Set<Long>> applyEmailAndInvite,
      Consumer<Set<Long>> markAdminInvites) {
    return execute(code, null, null, markAdminInvites, Operation.EMAIL_FINISH, applyEmailAndInvite);
  }

  public boolean delete(Consumer<Set<Long>> markPending) {
    return execute(null, null, null, markPending, Operation.DELETE);
  }

  public boolean setup(String initialCode, String secret, Consumer<Set<Long>> markActive) {
    return execute(initialCode, secret, null, markActive, Operation.APP);
  }

  private boolean execute(
      String initialCode,
      String secret,
      String pendingEmail,
      Consumer<Set<Long>> markActive,
      Operation kind) {
    return execute(initialCode, secret, pendingEmail, markActive, kind, null);
  }

  private boolean execute(
      String initialCode,
      String secret,
      String pendingEmail,
      Consumer<Set<Long>> markActive,
      Operation kind,
      BiConsumer<String, Set<Long>> applyEmailAndInvite) {
    if (!mode.isFiveActorMode() || TransactionSynchronizationManager.isActualTransactionActive())
      throw unavailable();
    if ((kind == Operation.APP && (!text(initialCode, 128) || !text(secret, 4096)))
        || (kind == Operation.EMAIL_START && !text(pendingEmail, 320))
        || (kind == Operation.EMAIL_FINISH
            && (!text(initialCode, 8)
                || !initialCode.matches("[0-9]{6,8}")
                || applyEmailAndInvite == null))
        || markActive == null)
      throw new BadRequestException("A complete app OTP request is required");
    var self = actualSelf(kind, initialCode);
    String operation = operationId();
    var actor = actors.require(TaskPurpose.OTP);
    if (!canonicalUuid(actor.serviceSubject())) throw unavailable();
    var relevantRequest =
        kind == Operation.DELETE
            ? Map.of("operation", "otp.delete")
            : kind == Operation.EMAIL_START
                ? Map.of("email", pendingEmail)
                : kind == Operation.EMAIL_FINISH
                    ? Map.of("otp", initialCode)
                    : Map.of("otp", initialCode, "secret", secret);
    String requestDigest =
        codec.digest(
            "oriso/user-maintenance-intent/request/v1",
            Map.of(
                "subject",
                self.account,
                "idempotencyKey",
                operation,
                "method",
                request.getMethod(),
                "path",
                request.getRequestURI(),
                "request",
                relevantRequest));
    // Check actual local ownership before even the token HTTP call, outside every RPC lock.
    CheckedIntent checked =
        transaction(() -> findChecked(self, operation, requestDigest, actor, kind));
    if (checked == null) {
      // Only a native-validated original grant can set the immutable first lifetime minimum.
      var originalMachine = machine(self, actor, TaskPurpose.OTP);
      long originalExpiry =
          Math.min(
              Math.min(self.expires, originalMachine.validatedJwtExpiresAt()),
              clock.instant().getEpochSecond() + 600);
      try {
        checked =
            transaction(() -> prepare(self, operation, requestDigest, actor, originalExpiry, kind));
      } catch (RuntimeException uncertain) {
        checked = transaction(() -> findChecked(self, operation, requestDigest, actor, kind));
        if (checked == null) throw unavailable();
      }
    }
    if (terminal(checked.row.status)) return "ACKNOWLEDGED".equals(checked.row.status);
    if ("PREPARED".equals(checked.row.status)) {
      var projection = read(self, checked.row, actor);
      try {
        checked =
            transaction(
                () ->
                    freeze(
                        self,
                        operation,
                        requestDigest,
                        actor,
                        projection,
                        initialCode,
                        secret,
                        kind == Operation.EMAIL_START
                            ? pendingEmail.toLowerCase(java.util.Locale.ROOT)
                            : pendingEmail,
                        kind));
      } catch (RuntimeException uncertain) {
        checked = transaction(() -> findChecked(self, operation, requestDigest, actor, kind));
        if (checked == null
            || !Set.of("FROZEN", "ACKNOWLEDGED", "TERMINAL_REFUSAL").contains(checked.row.status))
          throw unavailable();
      }
    }
    if (terminal(checked.row.status)) return "ACKNOWLEDGED".equals(checked.row.status);
    // This capability comes only from current locked owned data, never from envelope parsing.
    checked = transaction(() -> findChecked(self, operation, requestDigest, actor, kind));
    if (checked == null || !"FROZEN".equals(checked.row.status)) throw unavailable();
    final CheckedIntent frozen = checked;
    final JsonNode command;
    final AccountLifecycleCodec.VerifiedSelfAppOutcome outcome;
    try {
      command =
          codec.read(
              codec.utf8(codec.openSelfAppIntent(frozen.metadata, frozen.row.envelope)),
              JsonNode.class);
      var delivered =
          kind == Operation.EMAIL_FINISH ? transaction(() -> deliveredEmail(self, actor)) : null;
      if (delivered != null
          && !delivered.operation.equals(
              command.path("body").path("emailStartOperationId").textValue())) throw unavailable();
      var reply = invoke(self, frozen.row, actor, command, kind);
      if (kind == Operation.EMAIL_FINISH) {
        outcome =
            codec.verifySelfLinkedEmailFinishOtpReceipt(
                reply.body, realmId, actor, command, delivered.outcome);
        if (outcome.status() != reply.status) throw unavailable();
      } else if (kind == Operation.EMAIL_START) {
        var first = codec.verifySelfEmailStartOtpReceipt(reply.body, realmId, actor, command);
        if (first.status() != reply.status) throw unavailable();
        if (first.status() == 202) {
          var completed = invoke(self, frozen.row, actor, command, kind);
          outcome = codec.verifySelfEmailStartOtpReceipt(completed.body, realmId, actor, command);
          if (completed.status != 200
              || outcome.status() != 200
              || !first.sameEmailDelivery(outcome)) throw unavailable();
        } else outcome = first;
        if (outcome.status() != 200) throw unavailable();
      } else {
        outcome =
            kind == Operation.DELETE
                ? codec.verifySelfDeleteOtpReceipt(reply.body, realmId, actor, command)
                : codec.verifySelfAppOtpReceipt(reply.body, realmId, actor, command);
        if (outcome.status() != reply.status) throw unavailable();
      }
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
    try {
      return transaction(
          () ->
              acknowledge(
                  self,
                  operation,
                  requestDigest,
                  actor,
                  frozen.row.envelope,
                  outcome,
                  markActive,
                  kind,
                  applyEmailAndInvite));
    } catch (RuntimeException uncertain) {
      // A fresh durable row is the only commit witness. Do not rerun the domain callback here.
      var resolved = transaction(() -> findChecked(self, operation, requestDigest, actor, kind));
      if (resolved == null
          || !terminal(resolved.row.status)
          || !Objects.equals(outcome.receipt(), resolved.row.receipt)
          || !Objects.equals(outcome.status(), resolved.row.nativeStatus)) throw unavailable();
      return "ACKNOWLEDGED".equals(resolved.row.status);
    }
  }

  private CheckedIntent prepare(
      Self self,
      String operation,
      String digest,
      TaskActorCredentials actor,
      long expiry,
      Operation kind) {
    lockedDomain(self);
    var prior = find(operation);
    if (prior != null) return checked(self, prior, digest, actor, kind);
    if (expiry <= clock.instant().getEpochSecond()) throw unavailable();
    jdbc.update(
        "insert into identity_self_app_otp_intent(operation_id,realm_id,realm_name,account_id,tenant_id,owner_client,owner_subject,request_digest,target_json,status,authority_valid_until,pending_marker,updated_at) values(?,?,?,?,?,?,?,?,?,'PREPARED',?,false,?)",
        operation,
        realmId,
        realmName,
        self.account,
        self.tenant,
        actor.clientId(),
        actor.serviceSubject(),
        digest,
        codec.canonical(target(self, operation)),
        expiry,
        now());
    return checked(self, Objects.requireNonNull(find(operation)), digest, actor, kind);
  }

  private CheckedIntent freeze(
      Self self,
      String operation,
      String digest,
      TaskActorCredentials actor,
      JsonNode projection,
      String code,
      String secret,
      String pendingEmail,
      Operation kind) {
    var current = findChecked(self, operation, digest, actor, kind);
    if (current == null) throw unavailable();
    if (!"PREPARED".equals(current.row.status)) return current;
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(current.row.target, JsonNode.class));
    body.put("operationId", operation);
    body.put("expectedState", projection.path("state").textValue());
    if (kind == Operation.EMAIL_FINISH) {
      var delivered = deliveredEmail(self, actor);
      body.put("emailStartOperationId", delivered.operation);
    }
    if (kind == Operation.DELETE) body.putNull("otp");
    else {
      var otp = body.putObject("otp");
      otp.put("initialCode", code);
      otp.put("secret", secret);
      if (kind == Operation.EMAIL_START) {
        otp.put("email", pendingEmail);
        body.put("authorityValidUntil", current.row.expiry);
      } else otp.putNull("email");
    }
    var command = codec.read("{}", ObjectNode.class);
    command.put("username", projection.path("username").textValue());
    command.set("body", body);
    command.set("localState", localState(self));
    var owner = new SelfAccountOwner(jdbc, removedConsultants).require(self.account, self.tenant);
    var local = command.putObject("localAccount");
    local.put("kind", owner.kind().name());
    local.put("consultant", owner.consultant());
    local.put("email", kind == Operation.EMAIL_FINISH ? owner.email() : null);
    local.put("consultantEmail", kind == Operation.EMAIL_FINISH ? owner.consultantEmail() : null);
    var metadata = metadata(current.row, projection.path("state").textValue(), kind);
    String envelope =
        codec.sealSelfAppIntent(
            metadata, codec.canonical(command).getBytes(StandardCharsets.UTF_8));
    // The private pending outcome marker and dispatchable bytes have one actual DB commit.
    if (jdbc.update(
            "update identity_self_app_otp_intent set metadata_json=?,envelope=?,pending_marker=true,status='FROZEN',updated_at=? where operation_id=? and status='PREPARED' and pending_marker=false",
            codec.canonical(metadata),
            envelope,
            now(),
            operation)
        != 1) throw unavailable();
    return checked(self, Objects.requireNonNull(find(operation)), digest, actor, kind);
  }

  private boolean acknowledge(
      Self self,
      String operation,
      String digest,
      TaskActorCredentials actor,
      String envelope,
      AccountLifecycleCodec.VerifiedSelfAppOutcome outcome,
      Consumer<Set<Long>> markActive,
      Operation kind,
      BiConsumer<String, Set<Long>> applyEmailAndInvite) {
    var current = findChecked(self, operation, digest, actor, kind);
    if (current == null || !Objects.equals(envelope, current.row.envelope)) throw unavailable();
    if (terminal(current.row.status)) {
      if (!Objects.equals(outcome.receipt(), current.row.receipt)
          || !Objects.equals(outcome.status(), current.row.nativeStatus)) throw unavailable();
      return "ACKNOWLEDGED".equals(current.row.status);
    }
    if (!"FROZEN".equals(current.row.status)) throw unavailable();
    boolean success = outcome.status() == 200 || outcome.status() == 201;
    if (success) {
      var command =
          codec.read(
              codec.utf8(codec.openSelfAppIntent(current.metadata, current.row.envelope)),
              JsonNode.class);
      var nowLocal = localState(self);
      boolean apply =
          command.has("localState")
              ? codec.canonical(command.path("localState")).equals(codec.canonical(nowLocal))
              : legacyLocalEffect(kind, command, nowLocal);
      // A historical native ACK cannot reapply an older local enrollment or pointer.
      if (apply) {
        var inviteIds =
            ownedInviteIds(command.has("localState") ? command.path("localState") : nowLocal);
        if (kind == Operation.EMAIL_START) {
          var pending =
              jdbc.queryForList(
                  "select start_operation_id from identity_self_otp_pending_email where account_id=? for update",
                  String.class,
                  self.account);
          if (pending.size() > 1) throw unavailable();
          if (pending.isEmpty())
            jdbc.update(
                "insert into identity_self_otp_pending_email(account_id,start_operation_id) values(?,?)",
                self.account,
                operation);
          else if (!operation.equals(pending.getFirst()))
            throw new ConflictException("A different email setup is already owned");
        } else if (kind == Operation.EMAIL_FINISH) {
          var delivered = deliveredEmail(self, actor);
          if (!outcome.belongsToEmailStart(delivered.operation)
              || !outcome.sameEmailIdentity(delivered.outcome)
              || applyEmailAndInvite == null) throw unavailable();
          var owners = new SelfAccountOwner(jdbc, removedConsultants);
          var before = owners.require(self.account, self.tenant);
          if (command.has("localAccount")) {
            var original = command.path("localAccount");
            if (!Objects.equals(before.email(), original.path("email").textValue())
                || !Objects.equals(
                    before.consultantEmail(), original.path("consultantEmail").textValue()))
              throw unavailable();
          }
          if (self.kind.admin()) {
            owners.replaceAdminEmail(self.account, self.tenant, before, delivered.email);
            markActive.accept(inviteIds);
          } else applyEmailAndInvite.accept(delivered.email, inviteIds);
          var after = owners.require(self.account, self.tenant);
          if (!Objects.equals(after.email(), delivered.email)
              || (after.consultant() && !Objects.equals(after.consultantEmail(), delivered.email)))
            throw unavailable();
          clearPending(self, delivered.operation);
          appliedEnrollment(self, operation);
        } else if (kind == Operation.DELETE) {
          markActive.accept(inviteIds);
          var pending = command.path("localState").path("pendingEmailStartOperationId");
          if (!pending.isNull()) clearPending(self, pending.textValue());
        } else {
          markActive.accept(inviteIds);
          appliedEnrollment(self, operation);
        }
      }
    }
    if (jdbc.update(
            "update identity_self_app_otp_intent set status=?,receipt=?,native_status=?,updated_at=? where operation_id=? and status='FROZEN'",
            success ? "ACKNOWLEDGED" : "TERMINAL_REFUSAL",
            outcome.receipt(),
            outcome.status(),
            now(),
            operation)
        != 1) throw unavailable();
    return success;
  }

  private JsonNode localState(Self self) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    var value = codec.read("{}", ObjectNode.class);
    value.put("v", 1);
    var enrollment =
        jdbc.queryForList(
            "select operation_id from identity_self_otp_local_enrollment where account_id=? for update",
            String.class,
            self.account);
    var pending =
        jdbc.queryForList(
            "select start_operation_id from identity_self_otp_pending_email where account_id=? for update",
            String.class,
            self.account);
    if (enrollment.size() > 1 || pending.size() > 1) throw unavailable();
    if (enrollment.isEmpty()) value.putNull("enrollmentOperationId");
    else value.put("enrollmentOperationId", enrollment.getFirst());
    if (pending.isEmpty()) value.putNull("pendingEmailStartOperationId");
    else value.put("pendingEmailStartOperationId", pending.getFirst());
    var invites = value.putArray("invites");
    var rows =
        jdbc.queryForList(
            "select id,two_factor_status from account_invite where accepted_by_user_id=? order by id for update",
            self.account);
    if (rows.size() > 1024) throw unavailable();
    for (var row : rows) {
      var invite = invites.addObject();
      invite.put("id", ((Number) row.get("id")).toString());
      invite.put("status", (String) row.get("two_factor_status"));
    }
    return value;
  }

  private Set<Long> ownedInviteIds(JsonNode local) {
    var ids = new HashSet<Long>();
    for (var invite : local.path("invites")) {
      try {
        long id = Long.parseLong(invite.path("id").textValue());
        if (id <= 0 || !ids.add(id)) throw unavailable();
      } catch (NumberFormatException malformed) {
        throw unavailable();
      }
    }
    return Set.copyOf(ids);
  }

  private boolean legacyLocalEffect(Operation kind, JsonNode command, JsonNode current) {
    if (kind == Operation.DELETE || !current.path("enrollmentOperationId").isNull()) return false;
    var pending = current.path("pendingEmailStartOperationId");
    return pending.isNull()
        || (kind == Operation.EMAIL_FINISH
            && pending
                .asText()
                .equals(command.path("body").path("emailStartOperationId").asText()));
  }

  private void clearPending(Self self, String original) {
    if (!canonicalUuid(original)
        || jdbc.update(
                "delete from identity_self_otp_pending_email where account_id=? and start_operation_id=?",
                self.account,
                original)
            != 1) throw unavailable();
  }

  private void appliedEnrollment(Self self, String operation) {
    if (jdbc.update(
            "update identity_self_otp_local_enrollment set operation_id=? where account_id=?",
            operation,
            self.account)
        == 0)
      jdbc.update(
          "insert into identity_self_otp_local_enrollment(account_id,operation_id) values(?,?)",
          self.account,
          operation);
  }

  private CheckedIntent findChecked(
      Self self, String operation, String digest, TaskActorCredentials actor, Operation kind) {
    lockedDomain(self);
    var row = find(operation);
    return row == null ? null : checked(self, row, digest, actor, kind);
  }

  private CheckedIntent checked(
      Self self, Intent row, String digest, TaskActorCredentials actor, Operation kind) {
    if (!realmId.equals(row.realm)
        || !realmName.equals(row.realmName)
        || !self.account.equals(row.account)
        || !Objects.equals(self.tenant, row.tenant)
        || !actor.clientId().equals(row.ownerClient)
        || !actor.serviceSubject().equals(row.ownerSubject)
        || !digest.equals(row.requestDigest)
        || !codec.canonical(target(self, row.operation)).equals(row.target))
      throw new ConflictException("The app OTP operation does not match its original request");
    // A terminal result is owned historical data; expiry never grants another native effect.
    if (row.expiry <= clock.instant().getEpochSecond()
        && !terminal(row.status)
        && !(kind == Operation.EMAIL_START && "FROZEN".equals(row.status))) throw unavailable();
    JsonNode metadata = null;
    if ("PREPARED".equals(row.status)) {
      if (row.pending
          || row.metadata != null
          || row.envelope != null
          || row.receipt != null
          || row.nativeStatus != null) throw unavailable();
    } else if (Set.of("FROZEN", "ACKNOWLEDGED", "TERMINAL_REFUSAL").contains(row.status)) {
      if (!row.pending || row.metadata == null || row.envelope == null) throw unavailable();
      metadata = codec.read(row.metadata, JsonNode.class);
      String state = metadata.path("expectedState").textValue();
      if (state == null
          || !state.matches("[a-f0-9]{64}")
          || !codec.canonical(metadata(row, state, kind)).equals(row.metadata)
          || !row.metadata.equals(codec.canonical(metadata))) throw unavailable();
      var command =
          codec.read(codec.utf8(codec.openSelfAppIntent(metadata, row.envelope)), JsonNode.class);
      if (command.has("localAccount")) {
        var local = command.path("localAccount");
        if (!self.kind.name().equals(local.path("kind").textValue())
            || self.consultant != local.path("consultant").booleanValue()) throw unavailable();
      } else if (self.kind != SelfAccountOwner.Kind.USER || self.consultant) throw unavailable();
      if ("FROZEN".equals(row.status)) {
        if (row.receipt != null || row.nativeStatus != null) throw unavailable();
      } else if (row.receipt == null
          || row.nativeStatus == null
          || ("ACKNOWLEDGED".equals(row.status)
              ? !(kind == Operation.APP || kind == Operation.EMAIL_FINISH
                      ? Set.of(200, 201)
                      : Set.of(200))
                  .contains(row.nativeStatus)
              : kind != Operation.APP && kind != Operation.EMAIL_FINISH || row.nativeStatus != 401))
        throw unavailable();
    } else throw unavailable();
    return new CheckedIntent(row, metadata);
  }

  private DeliveredEmail deliveredEmail(Self self, TaskActorCredentials actor) {
    lockedDomain(self);
    var pending =
        jdbc.queryForList(
            "select start_operation_id from identity_self_otp_pending_email where account_id=? for update",
            String.class,
            self.account);
    if (pending.size() != 1 || !canonicalUuid(pending.getFirst())) throw unavailable();
    var start = find(pending.getFirst());
    if (start == null
        || !"ACKNOWLEDGED".equals(start.status)
        || !Integer.valueOf(200).equals(start.nativeStatus)) throw unavailable();
    var checked = checked(self, start, start.requestDigest, actor, Operation.EMAIL_START);
    var command =
        codec.read(
            codec.utf8(codec.openSelfAppIntent(checked.metadata, checked.row.envelope)),
            JsonNode.class);
    String email = command.path("body").path("otp").path("email").textValue();
    if (!text(email, 320)) throw unavailable();
    var result = codec.read("{}", ObjectNode.class);
    result.put("operationId", start.operation);
    result.put("accountId", self.account);
    result.put("status", 200);
    result.put("deliveryState", "SENT");
    result.set("result", codec.read("{\"info\":\"OTP mail sent\"}", JsonNode.class));
    result.put("receipt", start.receipt);
    var outcome =
        codec.verifySelfEmailStartOtpReceipt(codec.canonical(result), realmId, actor, command);
    if (outcome.status() != 200) throw unavailable();
    return new DeliveredEmail(start.operation, email, outcome);
  }

  private ObjectNode metadata(Intent row, String state, Operation kind) {
    var value = codec.read("{}", ObjectNode.class);
    value.put("v", 1);
    value.put("realmId", row.realm);
    value.put("operationId", row.operation);
    value.put("command", kind.command);
    value.put("method", kind.method);
    value.put("route", "otp-config/" + kind.route);
    value.put("accountId", row.account);
    if (row.tenant == null) value.putNull("tenantId");
    else value.put("tenantId", row.tenant);
    value.put("ownerClient", row.ownerClient);
    value.put("ownerSubject", row.ownerSubject);
    value.put(
        "originDigest",
        codec.digest("otp-origin", codec.read(row.target, JsonNode.class).path("origin")));
    value.put("workflowId", row.operation);
    value.put("requestDigest", row.requestDigest);
    value.put("expectedState", state);
    value.put("authorityValidUntil", row.expiry);
    value.put("representation", "FROZEN_HTTP_V1");
    return value;
  }

  private JsonNode read(Self self, Intent row, TaskActorCredentials actor) {
    outsideLocks(self, row);
    return readProjection(self, codec.read(row.target, JsonNode.class), actor);
  }

  private JsonNode readProjection(Self self, JsonNode target, TaskActorCredentials actor) {
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", target);
    var token = machine(self, actor, TaskPurpose.ACCOUNT_MAINTENANCE);
    var headers = headers(self, token);
    headers.set(
        "X-ORISO-Task-Context",
        codec.maintenanceProof("read", realmName, realmId, self.account, actor, body));
    JsonNode projection;
    try {
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || self.expires <= clock.instant().getEpochSecond()) throw unavailable();
      var response =
          http.exchange(
              UriComponentsBuilder.fromUriString(authServer)
                  .pathSegment(
                      "realms",
                      realmName,
                      "oriso-account-maintenance",
                      "v1",
                      "accounts",
                      self.account,
                      "read")
                  .build()
                  .encode()
                  .toUri(),
              HttpMethod.POST,
              new HttpEntity<>(codec.canonical(body), headers),
              String.class);
      if (response.getStatusCode().value() != 200) throw unavailable();
      projection = SelfOtpReadWire.projection(codec, response.getBody(), self.account, self.tenant);
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
    return projection;
  }

  private Reply invoke(
      Self self, Intent row, TaskActorCredentials actor, JsonNode command, Operation kind) {
    boolean historicalSent =
        kind == Operation.EMAIL_START
            && "FROZEN".equals(row.status)
            && row.expiry <= clock.instant().getEpochSecond();
    var body = command.path("body");
    if (historicalSent) {
      // Fresh authentication may read only the original outcome: this immutable expired body
      // cannot prepare or send in the native receiver, including absent/READY/unknown states.
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || self.expires <= clock.instant().getEpochSecond()
          || !body.path("authorityValidUntil").isIntegralNumber()
          || body.path("authorityValidUntil").longValue() != row.expiry) throw unavailable();
    } else outsideLocks(self, row);
    var token = machine(self, actor, TaskPurpose.OTP);
    var materialized = ((ObjectNode) body).deepCopy();
    materialized.put("username", command.path("username").textValue());
    var headers = headers(self, token);
    long expiry =
        Math.min(
            historicalSent ? self.expires : Math.min(row.expiry, self.expires),
            token.validatedJwtExpiresAt());
    if (expiry <= clock.instant().getEpochSecond()) throw unavailable();
    headers.set(
        "X-ORISO-Task-Context",
        kind == Operation.DELETE
            ? codec.selfDeleteOtpProof(
                realmName, realmId, self.account, actor, materialized, expiry)
            : kind == Operation.EMAIL_START
                ? codec.selfEmailStartOtpProof(
                    realmName, realmId, self.account, actor, materialized, expiry)
                : kind == Operation.EMAIL_FINISH
                    ? codec.selfLinkedEmailFinishOtpProof(
                        realmName, realmId, self.account, actor, materialized, expiry)
                    : codec.selfAppOtpProof(
                        realmName, realmId, self.account, actor, materialized, expiry));
    var uri =
        UriComponentsBuilder.fromUriString(authServer)
            .pathSegment(
                "realms", realmName, "otp-config", kind.route, command.path("username").textValue())
            .build()
            .encode()
            .toUri();
    try {
      var response =
          http.exchange(
              uri,
              HttpMethod.valueOf(kind.method),
              new HttpEntity<>(codec.canonical(body), headers),
              String.class);
      if (historicalSent && response.getStatusCode().value() != 200) throw unavailable();
      return new Reply(response.getStatusCode().value(), response.getBody());
    } catch (RestClientResponseException failure) {
      if (historicalSent
          || failure.getStatusCode().value() != 401
          || failure.getResponseBodyAsByteArray().length > 16384) throw unavailable();
      return new Reply(401, codec.utf8(failure.getResponseBodyAsByteArray()));
    }
  }

  private TaskActorTokenBroker.VerifiedMaintenanceToken machine(
      Self self, TaskActorCredentials actor, TaskPurpose purpose) {
    try {
      var token = broker.issueCheckedMaintenance(purpose);
      if (token.purpose() != purpose
          || !actor.clientId().equals(token.clientId())
          || !actor.serviceSubject().equals(token.subject())
          || !self.issuer.equals(token.issuer())) throw unavailable();
      return token;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private HttpHeaders headers(Self self, TaskActorTokenBroker.VerifiedMaintenanceToken machine) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(machine.accessToken());
    headers.set("X-ORISO-Human-Authorization", "Bearer " + self.bearer);
    return headers;
  }

  private void outsideLocks(Self self, Intent row) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || self.expires <= clock.instant().getEpochSecond()
        || row.expiry <= clock.instant().getEpochSecond()) throw unavailable();
  }

  private void lockedDomain(Self self) {
    var current = new SelfAccountOwner(jdbc, removedConsultants).require(self.account, self.tenant);
    if (current.kind() != self.kind || current.consultant() != self.consultant) throw forbidden();
  }

  private Intent find(String operation) {
    var rows =
        jdbc.query(
            "select * from identity_self_app_otp_intent where operation_id=? for update",
            (rs, index) -> intent(rs),
            operation);
    if (rows.size() > 1) throw unavailable();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static Intent intent(ResultSet row) throws SQLException {
    long tenant = row.getLong("tenant_id");
    Long actualTenant = row.wasNull() ? null : tenant;
    int status = row.getInt("native_status");
    Integer nativeStatus = row.wasNull() ? null : status;
    return new Intent(
        row.getString("operation_id"),
        row.getString("realm_id"),
        row.getString("realm_name"),
        row.getString("account_id"),
        actualTenant,
        row.getString("owner_client"),
        row.getString("owner_subject"),
        row.getString("request_digest"),
        row.getString("target_json"),
        row.getString("status"),
        row.getLong("authority_valid_until"),
        row.getString("metadata_json"),
        row.getString("envelope"),
        row.getBoolean("pending_marker"),
        row.getString("receipt"),
        nativeStatus);
  }

  private Self actualSelf(Operation kind, String code) {
    String path =
        switch (kind) {
          case APP -> "/users/2fa/app";
          case DELETE -> "/users/2fa";
          case EMAIL_START -> "/users/2fa/email";
          case EMAIL_FINISH -> "/users/2fa/email/validate/" + code;
        };
    return actualSelf(kind.method, Set.of("/service" + path, path));
  }

  private Self actualSelf(String method, Set<String> paths) {
    if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken authentication)
        || !authentication.isAuthenticated()
        || !method.equals(request.getMethod())
        || !paths.contains(request.getRequestURI())) throw forbidden();
    var jwt = authentication.getToken();
    String authorization = request.getHeader("Authorization");
    String account = jwt.getSubject();
    long now = clock.instant().getEpochSecond();
    if (!canonicalUuid(account)
        || !account.equals(authenticatedUser.getUserId())
        || !jwt.getTokenValue().equals(authenticatedUser.getAccessToken())
        || authorization == null
        || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
        || !jwt.getTokenValue().equals(authorization.substring(7))
        || jwt.getIssuer() == null
        || jwt.getIssuedAt() == null
        || jwt.getExpiresAt() == null
        || jwt.getIssuedAt().getEpochSecond() <= 0
        || jwt.getIssuedAt().getEpochSecond() > now
        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
        || jwt.getExpiresAt().getEpochSecond() <= now) throw forbidden();
    String issuer = jwt.getIssuer().toString();
    String client = jwt.getClaimAsString("azp");
    if (!humanIssuer.equals(issuer) || !text(client, 255)) throw forbidden();
    Object tenant = jwt.getClaims().get("tenantId");
    Long tenantId;
    if (tenant == null && !jwt.getClaims().containsKey("tenantId")) tenantId = null;
    else if (tenant instanceof String value && value.matches("0|[1-9][0-9]{0,18}")) {
      try {
        tenantId = Long.parseLong(value);
      } catch (NumberFormatException malformed) {
        throw forbidden();
      }
    } else if (tenant instanceof Long value && value >= 0) tenantId = value;
    else if (tenant instanceof Integer value && value >= 0) tenantId = value.longValue();
    else throw forbidden();
    if (!Objects.equals(tenantId, authenticatedUser.getTenantId())) throw forbidden();
    var owner =
        transaction(
            () -> new SelfAccountOwner(jdbc, removedConsultants).require(account, tenantId));
    return new Self(
        account,
        issuer,
        client,
        tenantId,
        jwt.getTokenValue(),
        jwt.getExpiresAt().getEpochSecond(),
        owner.kind(),
        owner.consultant());
  }

  private String operationId() {
    String id = request.getHeader("Idempotency-Key");
    if (!canonicalUuid(id))
      throw new BadRequestException("A canonical Idempotency-Key is required for app OTP setup");
    return id;
  }

  private ObjectNode target(Self self, String operation) {
    var value = codec.read("{}", ObjectNode.class);
    value.put("accountId", self.account);
    if (self.tenant == null) value.putNull("tenantId");
    else value.put("tenantId", self.tenant.toString());
    var origin = value.putObject("origin");
    origin.put("kind", "SELF");
    origin.put("humanSubject", self.account);
    origin.put("humanIssuer", self.issuer);
    origin.put("humanClient", self.client);
    origin.put("workflowId", operation);
    if (self.kind.protectedTarget())
      origin.put("humanAuthority", "PROTECTED_ACCOUNT_OPERATIONS_V1");
    else origin.putNull("humanAuthority");
    return value;
  }

  private <T> T transaction(Supplier<T> work) {
    var tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx.execute(status -> work.get());
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private static boolean terminal(String status) {
    return Set.of("ACKNOWLEDGED", "TERMINAL_REFUSAL").contains(status);
  }

  private static boolean text(String value, int limit) {
    return value != null
        && !value.isBlank()
        && value.length() <= limit
        && value.chars().noneMatch(c -> c < 32 || c == 127);
  }

  private static boolean canonicalUuid(String value) {
    try {
      return value != null
          && value.length() == 36
          && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Scoped app OTP maintenance unavailable");
  }

  private static ForbiddenException forbidden() {
    return new ForbiddenException("Current self account is not permitted");
  }

  private enum Operation {
    APP("otp.app-setup", "PUT", "setup-otp"),
    DELETE("otp.delete", "DELETE", "delete-otp"),
    EMAIL_START("otp.email-start", "PUT", "send-verification-mail"),
    EMAIL_FINISH("otp.email-finish", "POST", "setup-otp-mail");

    private final String command;
    private final String method;
    private final String route;

    Operation(String command, String method, String route) {
      this.command = command;
      this.method = method;
      this.route = route;
    }
  }

  private record DeliveredEmail(
      String operation, String email, AccountLifecycleCodec.VerifiedSelfAppOutcome outcome) {
    @Override
    public String toString() {
      return "DeliveredEmail[redacted]";
    }
  }

  private record Self(
      String account,
      String issuer,
      String client,
      Long tenant,
      String bearer,
      long expires,
      SelfAccountOwner.Kind kind,
      boolean consultant) {
    @Override
    public String toString() {
      return "Self[redacted]";
    }
  }

  private record Intent(
      String operation,
      String realm,
      String realmName,
      String account,
      Long tenant,
      String ownerClient,
      String ownerSubject,
      String requestDigest,
      String target,
      String status,
      long expiry,
      String metadata,
      String envelope,
      boolean pending,
      String receipt,
      Integer nativeStatus) {
    @Override
    public String toString() {
      return "Intent[redacted]";
    }
  }

  private static final class CheckedIntent {
    private final Intent row;
    private final JsonNode metadata;

    private CheckedIntent(Intent row, JsonNode metadata) {
      this.row = row;
      this.metadata = metadata;
    }

    @Override
    public String toString() {
      return "CheckedIntent[redacted]";
    }
  }

  private record Reply(int status, String body) {
    @Override
    public String toString() {
      return "Reply[redacted]";
    }
  }
}
