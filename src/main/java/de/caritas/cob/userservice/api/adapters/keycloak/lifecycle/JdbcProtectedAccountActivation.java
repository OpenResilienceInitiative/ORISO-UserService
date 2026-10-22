package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.HistoricalAcceptance;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedAccounts.PendingOwnedAccount;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCommitted;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCreated;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap.Completion;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap.PreparedBootstrap;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns local protected-account effects; native activation requires its committed private handle.
 */
public final class JdbcProtectedAccountActivation {
  private final JdbcTemplate jdbc;
  private final JdbcProtectedInvitationJournal journal;
  private final ProtectedAccountCodec accounts;
  private final AccountLifecycleCodec json;
  private final ProtectedOtpBootstrap otp;
  private final AdminRepository admins;
  private final AccountInactivityEnrollmentService inactivity;
  private final Clock clock;

  public JdbcProtectedAccountActivation(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedAccountCodec accounts,
      AccountLifecycleCodec json,
      ProtectedOtpBootstrap otp,
      AdminRepository admins,
      AccountInactivityEnrollmentService inactivity,
      Clock clock) {
    ProtectedInvitationSqlOwner.require(jdbc, manager);
    journal.requireOwner(jdbc, manager);
    this.jdbc = jdbc;
    this.journal = journal;
    this.accounts = Objects.requireNonNull(accounts);
    this.json = Objects.requireNonNull(json);
    this.otp = Objects.requireNonNull(otp);
    this.admins = Objects.requireNonNull(admins);
    this.inactivity = Objects.requireNonNull(inactivity);
    this.clock = Objects.requireNonNull(clock);
  }

  public IdentityOtpCredential stage(
      AcceptanceClaim claim,
      PendingOwnedAccount pending,
      PreparedBootstrap prepared,
      Policy policy) {
    outsideSql();
    if (pending == null || prepared == null || policy == null) throw unavailable();
    return owned(
        claim,
        () -> {
          var created = pending.created();
          sameClaim(claim, pending.claim());
          requireCreated(claim, created);
          var previous = rows(claim);
          if (!previous.isEmpty()) {
            var saved = checked(claim, previous.getFirst());
            if (!"WAITING_OTP".equals(saved.state())) throw unavailable();
            return otp.currentSetup(claim);
          }
          JsonNode scope = scope(claim);
          // Reserved creation additionally needs TenantCreationClient's verified result.
          if (!"EXISTING".equals(scope.path("tenantMode").asText())) throw unavailable();
          if (policy.months() < 1
              || policy.revision() < 0
              || policy.capturedAt() == null
              || policy.capturedAt().isAfter(clock.instant().plusSeconds(30))) throw unavailable();
          String id = canonicalUuid(created.accountId());
          var invitation =
              jdbc.queryForMap(
                  "SELECT recipient_email,first_name,last_name FROM account_invite WHERE id=?",
                  inviteId(claim));
          String email = (String) invitation.get("recipient_email");
          String first = fallback((String) invitation.get("first_name"), email.split("@", 2)[0]);
          String last = fallback((String) invitation.get("last_name"), "Admin");
          if (!Objects.equals(email, created.email())
              || !first.equals(created.firstName())
              || !last.equals(created.lastName())) throw unavailable();
          if (count("SELECT COUNT(*) FROM admin WHERE admin_id=?", id) != 0
              || count("SELECT COUNT(*) FROM account_inactivity WHERE identity_id=?", id) != 0)
            throw unavailable();
          String kind = created.registrationKind();
          long tenant = created.tenantId();
          if (!("TENANT_ADMIN".equals(kind) && tenant > 0
              || "PLATFORM_ADMIN".equals(kind) && tenant == 0)) throw unavailable();
          var admin =
              Admin.builder()
                  .id(id)
                  .tenantId(tenant)
                  .username(email)
                  .email(email)
                  .firstName(first)
                  .lastName(last)
                  .type(
                      "PLATFORM_ADMIN".equals(kind)
                          ? Admin.AdminType.SUPER
                          : Admin.AdminType.TENANT)
                  .build();
          admins.saveAndFlush(admin);
          inactivity.enroll(id, tenant, policy);
          IdentityOtpCredential material = otp.stagePrepared(claim, prepared);
          if (material == null || material.secret() == null || material.secret().isBlank())
            throw unavailable();
          JsonNode effects =
              json.read(
                  json.canonical(
                      Map.of("admin", adminSnapshot(id), "inactivity", inactivitySnapshot(id))),
                  JsonNode.class);
          String effectJson = json.canonical(effects);
          String digest = json.digest("protected-local-registration/v1", effects);
          Timestamp now = Timestamp.from(clock.instant());
          jdbc.update(
              "INSERT INTO protected_account_activation"
                  + " (invite_id,claim_id,attempt_id,execution_id,account_id,state,created_checkpoint,local_effects_json,local_effects_digest,local_outbox_id,created_at,updated_at)"
                  + " VALUES (?,?,?,?,?,'WAITING_OTP',?,?,?,?,?,?)",
              inviteId(claim),
              claim.claimId().toString(),
              claim.attemptId().toString(),
              claim.executionId().toString(),
              id,
              accounts.checkpoint(created),
              effectJson,
              digest,
              UUID.randomUUID().toString(),
              now,
              now);
          if (jdbc.update(
                  "UPDATE protected_invitation_acceptance SET account_id=?,updated_at=? WHERE invite_id=? AND account_id IS NULL",
                  id,
                  now,
                  inviteId(claim))
              != 1) throw unavailable();
          if (jdbc.update(
                  "UPDATE account_invite SET status='ACCEPTED',accepted_by_user_id=?,accepted_at=?,"
                      + "provisioning_status='IN_PROGRESS',provisioning_failure_reason=NULL,update_date=? WHERE id=? AND status='EMAIL_SENT'",
                  id,
                  now,
                  now,
                  inviteId(claim))
              != 1) throw unavailable();
          return material;
        });
  }

  public enum Phase {
    UNSTAGED,
    WAITING_OTP,
    READY,
    COMMITTED,
    LOCAL_CLEANED
  }

  public Phase phase(AcceptanceClaim claim) {
    throw unavailable();
  }

  public IdentityOtpCredential currentSetup(AcceptanceClaim claim) {
    outsideSql();
    return owned(
        claim,
        () -> {
          Row row = checked(claim, one(claim));
          if (!"WAITING_OTP".equals(row.state())) throw unavailable();
          return Objects.requireNonNull(otp.currentSetup(claim));
        });
  }

  public VerifiedCreated created(AcceptanceClaim claim) {
    outsideSql();
    return owned(
        claim,
        () -> {
          Row row = checked(claim, one(claim));
          return accounts.restore(claim, row.checkpoint());
        });
  }

  public ReadyActivation ready(AcceptanceClaim claim, Completion completion) {
    outsideSql();
    if (completion == null) throw unavailable();
    return owned(
        claim,
        () -> {
          Row row = checked(claim, one(claim));
          Completion current = otp.completed(claim);
          requireCompletion(claim, row, current);
          requireCompletion(claim, row, completion);
          if (!completion.bootstrapIncarnation().equals(current.bootstrapIncarnation())
              || !equal(completion.receipt(), current.receipt())) throw unavailable();
          String digest =
              json.digest(
                  "protected-local-ready/v1",
                  Map.of(
                      "registrationEffectsDigest",
                      row.digest(),
                      "otpIncarnation",
                      current.bootstrapIncarnation().toString(),
                      "otpReceipt",
                      current.receipt()));
          if ("WAITING_OTP".equals(row.state())) {
            jdbc.update(
                "UPDATE protected_account_activation SET state='READY',otp_incarnation=?,otp_receipt=?,ready_effects_digest=?,updated_at=? WHERE invite_id=? AND state='WAITING_OTP'",
                current.bootstrapIncarnation().toString(),
                current.receipt(),
                digest,
                Timestamp.from(clock.instant()),
                inviteId(claim));
          } else if (!Set.of("READY", "COMMITTED").contains(row.state())
              || !current.bootstrapIncarnation().toString().equals(row.otpIncarnation())
              || !equal(current.receipt(), row.otpReceipt())
              || !equal(digest, row.readyDigest())) throw unavailable();
          return new ReadyActivation(
              claim,
              accounts.restore(claim, row.checkpoint()),
              new LocalOutcome(1, UUID.fromString(row.outbox()), digest));
        });
  }

  public void acknowledge(ReadyActivation ready, VerifiedCommitted committed) {
    outsideSql();
    if (ready == null || committed == null) throw unavailable();
    owned(
        ready.claim,
        () -> {
          Row row = checked(ready.claim, one(ready.claim));
          if (!Set.of("READY", "COMMITTED").contains(row.state())
              || !equal(row.readyDigest(), ready.outcome.effectsDigest())
              || !row.outbox().equals(ready.outcome.outboxId().toString())
              || !ready.claim.claimId().equals(committed.claimId())
              || !ready.claim.attemptId().equals(committed.attemptId())
              || !row.accountId().equals(committed.accountId())
              || !ready.outcome.equals(committed.localOutcome())
              || committed.terminalReceipt() == null
              || committed.terminalReceipt().isBlank()
              || committed.terminalReceipt().length() > 16384) throw unavailable();
          if ("COMMITTED".equals(row.state())) {
            if (!equal(row.nativeReceipt(), committed.terminalReceipt())) throw unavailable();
            return null;
          }
          Timestamp now = Timestamp.from(clock.instant());
          jdbc.update(
              "UPDATE protected_account_activation SET state='COMMITTED',native_commit_receipt=?,updated_at=? WHERE invite_id=? AND state='READY'",
              committed.terminalReceipt(),
              now,
              inviteId(ready.claim));
          if (jdbc.update(
                  "UPDATE account_invite SET provisioning_status='COMPLETED',provisioning_failure_reason=NULL,active_recipient_key=NULL,update_date=?"
                      + " WHERE id=? AND status='ACCEPTED' AND accepted_by_user_id=?",
                  now,
                  inviteId(ready.claim),
                  row.accountId())
              != 1) throw unavailable();
          return null;
        });
  }

  public static final class ReadyActivation {
    private final AcceptanceClaim claim;
    private final VerifiedCreated created;
    private final LocalOutcome outcome;

    private ReadyActivation(AcceptanceClaim claim, VerifiedCreated created, LocalOutcome outcome) {
      this.claim = claim;
      this.created = created;
      this.outcome = outcome;
    }

    public VerifiedCreated created() {
      return created;
    }

    public LocalOutcome outcome() {
      return outcome;
    }

    @Override
    public String toString() {
      return "ProtectedReadyActivation[redacted]";
    }
  }

  public HistoricalReady historicalReady(HistoricalAcceptance historical) {
    throw unavailable();
  }

  public void acknowledge(HistoricalReady historical, VerifiedCommitted committed) {
    throw unavailable();
  }

  /** Data for a fresh status read; deliberately incompatible with the activation transport. */
  public static final class HistoricalReady {
    private final AcceptanceClaim claim;
    private final VerifiedCreated created;
    private final LocalOutcome outcome;

    private HistoricalReady(AcceptanceClaim claim, VerifiedCreated created, LocalOutcome outcome) {
      this.claim = claim;
      this.created = created;
      this.outcome = outcome;
    }

    public VerifiedCreated created() {
      return created;
    }

    public LocalOutcome outcome() {
      return outcome;
    }

    @Override
    public String toString() {
      return "ProtectedHistoricalReady[redacted]";
    }
  }

  /** Only this account-effects owner can authorize removal of its exact encrypted OTP rows. */
  public static final class VerifiedDiscard {
    private final long inviteId;
    private final UUID claimId;
    private final UUID attemptId;
    private final UUID executionId;
    private final String accountId;
    private final UUID bootstrapIncarnation;

    private VerifiedDiscard(
        long inviteId,
        UUID claimId,
        UUID attemptId,
        UUID executionId,
        String accountId,
        UUID bootstrapIncarnation) {
      this.inviteId = inviteId;
      this.claimId = claimId;
      this.attemptId = attemptId;
      this.executionId = executionId;
      this.accountId = accountId;
      this.bootstrapIncarnation = bootstrapIncarnation;
    }

    public long inviteId() {
      return inviteId;
    }

    public UUID claimId() {
      return claimId;
    }

    public UUID attemptId() {
      return attemptId;
    }

    public UUID executionId() {
      return executionId;
    }

    public String accountId() {
      return accountId;
    }

    public UUID bootstrapIncarnation() {
      return bootstrapIncarnation;
    }

    @Override
    public String toString() {
      return "ProtectedVerifiedDiscard[redacted]";
    }
  }

  private <T> T owned(AcceptanceClaim claim, Supplier<T> action) {
    long invite = inviteId(claim);
    return journal.withRecipientInvite(
        invite,
        () -> {
          if (!claim.frozen().equals(journal.pending(invite))) throw unavailable();
          var row =
              jdbc.queryForMap(
                  "SELECT * FROM protected_invitation_acceptance WHERE invite_id=? FOR UPDATE",
                  invite);
          if (!claim.frozen().approvalId().toString().equals(row.get("approval_id"))
              || !claim.claimId().toString().equals(row.get("claim_id"))
              || !claim.attemptId().toString().equals(row.get("attempt_id"))
              || !claim.executionId().toString().equals(row.get("execution_id"))
              || ((Number) row.get("journal_version")).longValue() != 1
              || !equal(claim.registrationDigest(), (String) row.get("registration_digest")))
            throw unavailable();
          String token =
              jdbc.queryForObject(
                  "SELECT token_hash FROM account_invite WHERE id=?", String.class, invite);
          String response =
              jdbc.queryForObject(
                  "SELECT response_json FROM protected_invitation_authorization WHERE invite_id=? AND state='READY'",
                  String.class,
                  invite);
          if (!equal(token, claim.tokenHash())
              || !equal(
                  json.canonical(json.read(response, JsonNode.class)),
                  claim.approval().responseJson())) throw unavailable();
          return action.get();
        });
  }

  private void requireCreated(AcceptanceClaim claim, VerifiedCreated created) {
    if (created == null
        || !claim.claimId().equals(created.claimId())
        || !claim.attemptId().equals(created.attemptId())
        || !claim.executionId().equals(created.executionId())
        || !claim.frozen().approvalId().equals(created.approvalId())
        || !claim.frozen().issuerRealm().equals(created.realmId())
        || !Long.toString(inviteId(claim)).equals(created.inviteId())
        || !scope(claim).path("protectedKind").asText().equals(created.registrationKind())
        || scope(claim).path("targetTenantIds").get(0).longValue() != created.tenantId())
      throw unavailable();
  }

  private void sameClaim(AcceptanceClaim a, AcceptanceClaim b) {
    if (a == null
        || b == null
        || !a.frozen().equals(b.frozen())
        || !a.claimId().equals(b.claimId())
        || !a.attemptId().equals(b.attemptId())
        || !a.executionId().equals(b.executionId())
        || !equal(a.registrationDigest(), b.registrationDigest())) throw unavailable();
  }

  private void requireCompletion(AcceptanceClaim claim, Row row, Completion completion) {
    if (completion == null
        || !claim.claimId().equals(completion.claimId())
        || !claim.attemptId().equals(completion.attemptId())
        || !claim.executionId().equals(completion.executionId())
        || !row.accountId().equals(completion.accountId())
        || completion.bootstrapIncarnation() == null
        || completion.receipt() == null
        || completion.receipt().isBlank()
        || completion.receipt().length() > 16384) throw unavailable();
  }

  private Row checked(AcceptanceClaim claim, Row row) {
    if (!claim.claimId().toString().equals(row.claimId())
        || !claim.attemptId().toString().equals(row.attemptId())
        || !claim.executionId().toString().equals(row.executionId())) throw unavailable();
    VerifiedCreated restored = accounts.restore(claim, row.checkpoint());
    requireCreated(claim, restored);
    if (!row.accountId().equals(restored.accountId())) throw unavailable();
    JsonNode effects = json.read(row.effects(), JsonNode.class);
    if (!equal(row.digest(), json.digest("protected-local-registration/v1", effects))
        || !effects.path("admin").equals(adminSnapshot(row.accountId()))
        || !effects.path("inactivity").equals(inactivitySnapshot(row.accountId())))
      throw unavailable();
    var invite =
        jdbc.queryForMap(
            "SELECT status,accepted_by_user_id FROM account_invite WHERE id=?", inviteId(claim));
    String account =
        jdbc.queryForObject(
            "SELECT account_id FROM protected_invitation_acceptance WHERE invite_id=?",
            String.class,
            inviteId(claim));
    if (!"ACCEPTED".equals(invite.get("status"))
        || !row.accountId().equals(invite.get("accepted_by_user_id"))
        || !row.accountId().equals(account)) throw unavailable();
    return row;
  }

  private JsonNode adminSnapshot(String id) {
    var rows =
        jdbc.query(
            "SELECT admin_id,tenant_id,username,first_name,last_name,email,type,managed_paused FROM admin WHERE admin_id=? FOR UPDATE",
            (rs, i) ->
                Map.of(
                    "id",
                    rs.getString(1),
                    "tenantId",
                    rs.getLong(2),
                    "username",
                    rs.getString(3),
                    "firstName",
                    rs.getString(4),
                    "lastName",
                    rs.getString(5),
                    "email",
                    rs.getString(6),
                    "type",
                    rs.getString(7),
                    "managedPaused",
                    rs.getBoolean(8)),
            id);
    if (rows.size() != 1) throw unavailable();
    return json.read(json.canonical(rows.getFirst()), JsonNode.class);
  }

  private JsonNode inactivitySnapshot(String id) {
    var rows =
        jdbc.query(
            "SELECT identity_id,tenant_id,assigned_months,revision,last_activity,due_at,status,attempts FROM account_inactivity WHERE identity_id=? FOR UPDATE",
            (rs, i) ->
                Map.of(
                    "id",
                    rs.getString(1),
                    "tenantId",
                    rs.getLong(2),
                    "months",
                    rs.getInt(3),
                    "revision",
                    rs.getLong(4),
                    "lastActivity",
                    rs.getTimestamp(5).toInstant().toString(),
                    "dueAt",
                    rs.getTimestamp(6).toInstant().toString(),
                    "state",
                    rs.getString(7),
                    "attempts",
                    rs.getInt(8)),
            id);
    if (rows.size() != 1) throw unavailable();
    return json.read(json.canonical(rows.getFirst()), JsonNode.class);
  }

  private Row one(AcceptanceClaim claim) {
    var found = rows(claim);
    if (found.size() != 1) throw unavailable();
    return found.getFirst();
  }

  private java.util.List<Row> rows(AcceptanceClaim claim) {
    return jdbc.query(
        "SELECT * FROM protected_account_activation WHERE invite_id=? FOR UPDATE",
        (rs, i) ->
            new Row(
                rs.getString("claim_id"),
                rs.getString("attempt_id"),
                rs.getString("execution_id"),
                rs.getString("account_id"),
                rs.getString("state"),
                rs.getString("created_checkpoint"),
                rs.getString("local_effects_json"),
                rs.getString("local_effects_digest"),
                rs.getString("local_outbox_id"),
                rs.getString("otp_incarnation"),
                rs.getString("otp_receipt"),
                rs.getString("ready_effects_digest"),
                rs.getString("native_commit_receipt")),
        inviteId(claim));
  }

  private int count(String query, String id) {
    return Objects.requireNonNull(jdbc.queryForObject(query, Integer.class, id));
  }

  private JsonNode scope(AcceptanceClaim claim) {
    return json.read(claim.frozen().requestJson(), JsonNode.class).path("scope");
  }

  private long inviteId(AcceptanceClaim claim) {
    if (claim == null || claim.frozen() == null || claim.approval() == null) throw unavailable();
    try {
      String text = scope(claim).path("inviteId").asText();
      long id = Long.parseLong(text);
      if (id < 1 || !Long.toString(id).equals(text)) throw unavailable();
      return id;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static String canonicalUuid(String value) {
    try {
      String uuid = UUID.fromString(value).toString();
      if (!uuid.equals(value)) throw unavailable();
      return uuid;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static String fallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static boolean equal(String a, String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
  }

  private record Row(
      String claimId,
      String attemptId,
      String executionId,
      String accountId,
      String state,
      String checkpoint,
      String effects,
      String digest,
      String outbox,
      String otpIncarnation,
      String otpReceipt,
      String readyDigest,
      String nativeReceipt) {
    @Override
    public String toString() {
      return "ProtectedActivationRow[redacted]";
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Protected local activation is not available");
  }
}
