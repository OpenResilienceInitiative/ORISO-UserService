package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.HistoricalAcceptance;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedAccounts.PendingOwnedAccount;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCommitted;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCreated;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedRecovery;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap.Completion;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap.PreparedBootstrap;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantCreationClient.VerifiedTenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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
  private final JdbcProtectedAccountCreation creation;

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
    this(jdbc, manager, journal, accounts, json, otp, admins, inactivity, clock, null);
  }

  public JdbcProtectedAccountActivation(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedAccountCodec accounts,
      AccountLifecycleCodec json,
      ProtectedOtpBootstrap otp,
      AdminRepository admins,
      AccountInactivityEnrollmentService inactivity,
      Clock clock,
      JdbcProtectedAccountCreation creation) {
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
    this.creation = creation;
  }

  public IdentityOtpCredential stage(
      AcceptanceClaim claim,
      PendingOwnedAccount pending,
      PreparedBootstrap prepared,
      Policy policy) {
    return stageLocal(claim, pending, prepared, policy, null);
  }

  private IdentityOtpCredential stageLocal(
      AcceptanceClaim claim,
      PendingOwnedAccount pending,
      PreparedBootstrap prepared,
      Policy policy,
      VerifiedTenant verifiedTenant) {
    outsideSql();
    if (pending == null || prepared == null || policy == null) throw unavailable();
    return owned(
        claim,
        () -> {
          var created = pending.created();
          sameClaim(claim, pending.claim());
          requireCreated(claim, created);
          JsonNode scope = scope(claim);
          String tenantReceipt = checkedTenant(claim, scope, verifiedTenant);
          var previous = rows(claim);
          if (!previous.isEmpty()) {
            var saved = checked(claim, previous.getFirst());
            if (!"WAITING_OTP".equals(saved.state())) throw unavailable();
            if (!equal(accounts.checkpoint(created), saved.checkpoint())) throw unavailable();
            JsonNode effects = json.read(saved.effects(), JsonNode.class);
            if (tenantReceipt == null
                ? effects.has("tenantBootstrapReceipt")
                : !equal(tenantReceipt, effects.path("tenantBootstrapReceipt").asText(null)))
              throw unavailable();
            return otp.currentSetupWithinOwner(claim);
          }
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
                      tenantReceipt == null
                          ? Map.of("admin", adminSnapshot(id), "inactivity", inactivitySnapshot(id))
                          : Map.of(
                              "admin",
                              adminSnapshot(id),
                              "inactivity",
                              inactivitySnapshot(id),
                              "tenantBootstrapReceipt",
                              tenantReceipt)),
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

  public IdentityOtpCredential stageReserved(
      AcceptanceClaim claim,
      PendingOwnedAccount pending,
      PreparedBootstrap prepared,
      Policy policy,
      VerifiedTenant tenant) {
    if (tenant == null) throw unavailable();
    return stageLocal(claim, pending, prepared, policy, tenant);
  }

  private String checkedTenant(AcceptanceClaim claim, JsonNode scope, VerifiedTenant tenant) {
    if ("EXISTING".equals(scope.path("tenantMode").asText())) {
      if (tenant != null) throw unavailable();
      return null;
    }
    if (!"RESERVED_NEW".equals(scope.path("tenantMode").asText())
        || tenant == null
        || tenant.tenantId() != scope.path("targetTenantIds").get(0).longValue()
        || !claim.claimId().equals(tenant.claimId())
        || !claim.attemptId().equals(tenant.attemptId())
        || !claim.executionId().equals(tenant.executionId())
        || tenant.receipt() == null
        || tenant.receipt().isBlank()
        || tenant.receipt().length() > 16384) throw unavailable();
    return tenant.receipt();
  }

  public Phase phase(AcceptanceClaim claim) {
    outsideSql();
    return readPhase(claim, null);
  }

  /** Original saved phase data cannot authorize a new recipient operation. */
  public Phase phase(HistoricalAcceptance historical) {
    outsideSql();
    if (historical == null) throw unavailable();
    return readPhase(historical.claim(), historical);
  }

  private Phase readPhase(AcceptanceClaim claim, HistoricalAcceptance historical) {
    return owned(
        claim,
        historical != null,
        () -> {
          var saved = rows(claim);
          if (saved.isEmpty()) {
            var cleaned = cleanupRows(claim);
            if (!cleaned.isEmpty()) {
              if (cleaned.size() != 1 || historical == null) throw unavailable();
              checkedUnstagedCleanup(claim, historical, cleaned.getFirst());
              return Phase.LOCAL_CLEANED;
            }
            return Phase.UNSTAGED;
          }
          if (saved.size() != 1) throw unavailable();
          if ("LOCAL_CLEANED".equals(saved.getFirst().state())) {
            var cleanup = cleanupRows(claim);
            if (cleanup.size() != 1) throw unavailable();
            checkedCleanup(claim, saved.getFirst(), cleanup.getFirst());
            return Phase.LOCAL_CLEANED;
          }
          var row =
              Set.of("READY", "COMMITTED").contains(saved.getFirst().state())
                  ? historicallyChecked(claim, saved.getFirst())
                  : checked(claim, saved.getFirst(), historical != null);
          try {
            return Phase.valueOf(row.state());
          } catch (IllegalArgumentException failure) {
            throw unavailable();
          }
        });
  }

  public IdentityOtpCredential currentSetup(AcceptanceClaim claim) {
    outsideSql();
    return owned(
        claim,
        () -> {
          Row row = checked(claim, one(claim));
          if (!"WAITING_OTP".equals(row.state())) throw unavailable();
          return Objects.requireNonNull(otp.currentSetupWithinOwner(claim));
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
          Completion current = otp.completedWithinOwner(claim);
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
    acknowledge(ready.claim, ready.outcome, committed, false);
  }

  private void acknowledge(
      AcceptanceClaim claim,
      LocalOutcome outcome,
      VerifiedCommitted committed,
      boolean historical) {
    owned(
        claim,
        historical,
        () -> {
          Row row =
              historical ? historicallyChecked(claim, one(claim)) : checked(claim, one(claim));
          if (!Set.of("READY", "COMMITTED").contains(row.state())
              || !equal(row.readyDigest(), outcome.effectsDigest())
              || outcome.journalVersion() != 1
              || !row.outbox().equals(outcome.outboxId().toString())
              || !claim.claimId().equals(committed.claimId())
              || !claim.attemptId().equals(committed.attemptId())
              || !row.accountId().equals(committed.accountId())
              || !outcome.equals(committed.localOutcome())
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
              inviteId(claim));
          if (jdbc.update(
                  "UPDATE account_invite SET provisioning_status='COMPLETED',provisioning_failure_reason=NULL,active_recipient_key=NULL,update_date=?"
                      + " WHERE id=? AND status IN ('ACCEPTED','EXPIRED','REVOKED','SUPERSEDED') AND accepted_by_user_id=?",
                  now,
                  inviteId(claim),
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
    outsideSql();
    if (historical == null) throw unavailable();
    AcceptanceClaim claim = historical.claim();
    return owned(
        claim,
        true,
        () -> {
          Row row = historicallyChecked(claim, one(claim));
          return new HistoricalReady(
              claim,
              accounts.restore(claim, row.checkpoint()),
              new LocalOutcome(1, UUID.fromString(row.outbox()), row.readyDigest()));
        });
  }

  public void acknowledge(HistoricalReady historical, VerifiedCommitted committed) {
    outsideSql();
    if (historical == null || committed == null) throw unavailable();
    acknowledge(historical.claim, historical.outcome, committed, true);
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

  public LocalOutcome discard(AcceptanceClaim claim, VerifiedRecovery recovery) {
    outsideSql();
    return discardOwned(claim, null, recovery);
  }

  public LocalOutcome discard(HistoricalAcceptance historical, VerifiedRecovery recovery) {
    outsideSql();
    if (historical == null) throw unavailable();
    return discardOwned(historical.claim(), historical, recovery);
  }

  /** Invalidate only an expired, unfinished local registration inside its cancellation SQL. */
  public void restrictExpiredWithinOwner(HistoricalAcceptance historical) {
    if (historical == null
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
      throw unavailable();
    var claim = historical.claim();
    owned(
        claim,
        true,
        () -> {
          Row row = checked(claim, one(claim), true);
          if (!Set.of("WAITING_OTP", "READY").contains(row.state())) throw unavailable();
          if ("READY".equals(row.state())) historicallyChecked(claim, row);
          Instant deadline = Instant.parse(scope(claim).path("validUntil").asText());
          if (deadline.isAfter(clock.instant())) throw unavailable();
          if (jdbc.update(
                  "UPDATE account_invite SET status='EXPIRED',token_hash=NULL,update_date=?"
                      + " WHERE id=? AND status='ACCEPTED' AND provisioning_status='IN_PROGRESS'"
                      + " AND accepted_by_user_id=? AND expires_at=?",
                  Timestamp.from(clock.instant()),
                  inviteId(claim),
                  row.accountId(),
                  Timestamp.from(deadline))
              != 1) throw unavailable();
          return null;
        });
  }

  private LocalOutcome discardOwned(
      AcceptanceClaim claim, HistoricalAcceptance historical, VerifiedRecovery recovery) {
    if (recovery == null) throw unavailable();
    return owned(
        claim,
        true,
        () -> {
          var saved = rows(claim);
          if (saved.isEmpty()) return discardUnstaged(claim, historical, recovery);
          if (saved.size() != 1) throw unavailable();
          Row row = saved.getFirst();
          VerifiedCreated original = accounts.restore(claim, row.checkpoint());
          requireCreated(claim, original);
          if (!Set.of("RECOVERY_CLAIMED", "COMPENSATED").contains(recovery.state())
              || recovery.generation() < 1
              || recovery.executionId() == null
              || !claim.frozen().approvalId().equals(recovery.approvalId())
              || !claim.attemptId().equals(recovery.attemptId())
              || !row.accountId().equals(recovery.accountId())
              || !row.accountId().equals(original.accountId())
              || !equal(original.creationProof(), recovery.creationProof())) throw unavailable();
          var previous = cleanupRows(claim);
          if (!previous.isEmpty()) {
            if (previous.size() != 1 || !"LOCAL_CLEANED".equals(row.state())) throw unavailable();
            JsonNode body = checkedCleanup(claim, row, previous.getFirst());
            if (!recovery.executionId().toString().equals(body.path("recoveryId").asText())
                || recovery.generation() != body.path("generation").asLong(-1)
                || !equal(
                    proofDigest(recovery.creationProof()),
                    body.path("creationProofDigest").asText())) throw unavailable();
            return previous.getFirst().outcome();
          }
          if (!Set.of("WAITING_OTP", "READY").contains(row.state())) throw unavailable();
          checked(claim, row, true);
          if ("READY".equals(row.state())) historicallyChecked(claim, row);
          UUID incarnation =
              row.otpIncarnation() == null
                  ? claim.executionId()
                  : UUID.fromString(canonicalUuid(row.otpIncarnation()));
          UUID outbox = UUID.randomUUID();
          var body =
              json.read(
                  json.canonical(
                      Map.ofEntries(
                          Map.entry("inviteId", Long.toString(inviteId(claim))),
                          Map.entry("approvalId", claim.frozen().approvalId().toString()),
                          Map.entry("claimId", claim.claimId().toString()),
                          Map.entry("attemptId", claim.attemptId().toString()),
                          Map.entry("executionId", claim.executionId().toString()),
                          Map.entry("accountId", row.accountId()),
                          Map.entry("recoveryId", recovery.executionId().toString()),
                          Map.entry("generation", recovery.generation()),
                          Map.entry("creationProofDigest", proofDigest(recovery.creationProof())),
                          Map.entry("registrationEffectsDigest", row.digest()),
                          Map.entry("bootstrapIncarnation", incarnation.toString()),
                          Map.entry("previousState", row.state()),
                          Map.entry("outboxId", outbox.toString()))),
                  JsonNode.class);
          String digest = json.digest("protected-local-cleanup/v1", body);
          otp.discardWithinOwner(
              new VerifiedDiscard(
                  inviteId(claim),
                  claim.claimId(),
                  claim.attemptId(),
                  claim.executionId(),
                  row.accountId(),
                  incarnation,
                  recovery.executionId(),
                  recovery.generation()));
          if (jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", row.accountId())
                  != 1
              || jdbc.update("DELETE FROM admin WHERE admin_id=?", row.accountId()) != 1)
            throw unavailable();
          Timestamp now = Timestamp.from(clock.instant());
          jdbc.update(
              "INSERT INTO protected_account_cleanup (invite_id,body_json,effects_digest,outbox_id,created_at) VALUES (?,?,?,?,?)",
              inviteId(claim),
              json.canonical(body),
              digest,
              outbox.toString(),
              now);
          if (jdbc.update(
                  "UPDATE protected_account_activation SET state='LOCAL_CLEANED',updated_at=? WHERE invite_id=? AND state=?",
                  now,
                  inviteId(claim),
                  row.state())
              != 1) throw unavailable();
          return new LocalOutcome(1, outbox, digest);
        });
  }

  private LocalOutcome discardUnstaged(
      AcceptanceClaim claim, HistoricalAcceptance historical, VerifiedRecovery recovery) {
    if (creation == null || historical == null || historical.inviteId() != inviteId(claim))
      throw unavailable();
    var metadata = creation.metadataWithinOwner(historical);
    accounts.checkRecovery(metadata, recovery);
    if (metadata == null
        || !claim.frozen().approvalId().equals(metadata.approvalId())
        || !claim.attemptId().equals(metadata.attemptId())
        || metadata.creationFingerprint() == null
        || !metadata.creationFingerprint().matches("[0-9a-f]{64}")
        || !claim.frozen().approvalId().equals(recovery.approvalId())
        || !claim.attemptId().equals(recovery.attemptId())
        || recovery.executionId() == null
        || claim.executionId().equals(recovery.executionId())
        || recovery.generation() < 1
        || !Set.of("ABANDONED", "RECOVERY_CLAIMED", "COMPENSATED").contains(recovery.state()))
      throw unavailable();
    String account = recovery.accountId();
    boolean abandoned = "ABANDONED".equals(recovery.state());
    if (abandoned != (account == null) || abandoned != (recovery.creationProof() == null))
      throw unavailable();
    if (account != null) {
      canonicalUuid(account);
      if (count("SELECT COUNT(*) FROM admin WHERE admin_id=?", account) != 0
          || count("SELECT COUNT(*) FROM account_inactivity WHERE identity_id=?", account) != 0)
        throw unavailable();
    }
    var original = creation.createdWithinOwner(historical);
    if (original.isPresent()) {
      requireCreated(claim, original.get());
      if (!Objects.equals(account, original.get().accountId())
          || !equal(recovery.creationProof(), original.get().creationProof())) throw unavailable();
    }
    if (jdbc.queryForObject(
            "SELECT account_id FROM protected_invitation_acceptance WHERE invite_id=?",
            String.class,
            inviteId(claim))
        != null) throw unavailable();
    var previous = cleanupRows(claim);
    if (previous.size() > 1) throw unavailable();
    UUID outbox =
        previous.isEmpty() ? UUID.randomUUID() : UUID.fromString(previous.getFirst().outbox());
    Map<String, Object> fields = new TreeMap<>();
    fields.put("inviteId", Long.toString(inviteId(claim)));
    fields.put("approvalId", claim.frozen().approvalId().toString());
    fields.put("claimId", claim.claimId().toString());
    fields.put("attemptId", claim.attemptId().toString());
    fields.put("executionId", claim.executionId().toString());
    fields.put("accountId", account);
    fields.put("recoveryId", recovery.executionId().toString());
    fields.put("generation", recovery.generation());
    fields.put("creationProofDigest", abandoned ? null : proofDigest(recovery.creationProof()));
    fields.put("creationFingerprint", metadata.creationFingerprint());
    fields.put("registrationDigest", claim.registrationDigest());
    fields.put("bootstrapIncarnation", claim.executionId().toString());
    fields.put("previousState", "UNSTAGED");
    fields.put("outboxId", outbox.toString());
    String body = json.canonical(fields);
    String digest = json.digest("protected-local-cleanup/v1", fields);
    if (!previous.isEmpty()) {
      var old = previous.getFirst();
      if (!body.equals(old.body()) || !equal(digest, old.digest())) throw unavailable();
      return old.outcome();
    }
    otp.discardWithinOwner(
        new VerifiedDiscard(
            inviteId(claim),
            claim.claimId(),
            claim.attemptId(),
            claim.executionId(),
            account,
            claim.executionId(),
            recovery.executionId(),
            recovery.generation()));
    if (jdbc.update(
            "INSERT INTO protected_account_cleanup (invite_id,body_json,effects_digest,outbox_id,created_at) VALUES (?,?,?,?,?)",
            inviteId(claim),
            body,
            digest,
            outbox.toString(),
            Timestamp.from(clock.instant()))
        != 1) throw unavailable();
    return new LocalOutcome(1, outbox, digest);
  }

  private String proofDigest(String proof) {
    if (proof == null || proof.isBlank() || proof.length() > 16384) throw unavailable();
    return json.digest("protected-local-cleanup-creation/v1", Map.of("creationProof", proof));
  }

  private JsonNode checkedCleanup(AcceptanceClaim claim, Row row, Cleanup cleanup) {
    JsonNode body = json.read(cleanup.body(), JsonNode.class);
    JsonNode effects = json.read(row.effects(), JsonNode.class);
    VerifiedCreated original = accounts.restore(claim, row.checkpoint());
    requireCreated(claim, original);
    String incarnation =
        row.otpIncarnation() == null
            ? claim.executionId().toString()
            : canonicalUuid(row.otpIncarnation());
    if (!equal(cleanup.digest(), json.digest("protected-local-cleanup/v1", body))
        || !equal(row.digest(), json.digest("protected-local-registration/v1", effects))
        || !claim.claimId().toString().equals(row.claimId())
        || !claim.attemptId().toString().equals(row.attemptId())
        || !claim.executionId().toString().equals(row.executionId())
        || !row.accountId().equals(original.accountId())
        || !equal(proofDigest(original.creationProof()), body.path("creationProofDigest").asText())
        || !incarnation.equals(body.path("bootstrapIncarnation").asText())
        || !Long.toString(inviteId(claim)).equals(body.path("inviteId").asText())
        || !claim.frozen().approvalId().toString().equals(body.path("approvalId").asText())
        || !claim.claimId().toString().equals(body.path("claimId").asText())
        || !claim.attemptId().toString().equals(body.path("attemptId").asText())
        || !claim.executionId().toString().equals(body.path("executionId").asText())
        || !row.accountId().equals(body.path("accountId").asText())
        || !equal(row.digest(), body.path("registrationEffectsDigest").asText())
        || !cleanup.outbox().equals(body.path("outboxId").asText())
        || !Set.of("WAITING_OTP", "READY").contains(body.path("previousState").asText())
        || count("SELECT COUNT(*) FROM admin WHERE admin_id=?", row.accountId()) != 0
        || count("SELECT COUNT(*) FROM account_inactivity WHERE identity_id=?", row.accountId())
            != 0) throw unavailable();
    return body;
  }

  private java.util.List<Cleanup> cleanupRows(AcceptanceClaim claim) {
    return jdbc.query(
        "SELECT body_json,effects_digest,outbox_id FROM protected_account_cleanup WHERE invite_id=? FOR UPDATE",
        (rs, i) -> new Cleanup(rs.getString(1), rs.getString(2), rs.getString(3)),
        inviteId(claim));
  }

  private void checkedUnstagedCleanup(
      AcceptanceClaim claim, HistoricalAcceptance historical, Cleanup cleanup) {
    if (creation == null || historical.inviteId() != inviteId(claim)) throw unavailable();
    JsonNode body = json.read(cleanup.body(), JsonNode.class);
    var keys = new java.util.HashSet<String>();
    body.fieldNames().forEachRemaining(keys::add);
    var metadata = creation.metadataWithinOwner(historical);
    if (!keys.equals(Set.of("inviteId", "approvalId", "claimId", "attemptId", "executionId",
            "accountId", "recoveryId", "generation", "creationProofDigest", "creationFingerprint",
            "registrationDigest", "bootstrapIncarnation", "previousState", "outboxId"))
        || !cleanup.body().equals(json.canonical(body))
        || !equal(cleanup.digest(), json.digest("protected-local-cleanup/v1", body))
        || !Long.toString(inviteId(claim)).equals(body.path("inviteId").asText())
        || !claim.frozen().approvalId().toString().equals(body.path("approvalId").asText())
        || !claim.claimId().toString().equals(body.path("claimId").asText())
        || !claim.attemptId().toString().equals(body.path("attemptId").asText())
        || !claim.executionId().toString().equals(body.path("executionId").asText())
        || !claim.executionId().toString().equals(body.path("bootstrapIncarnation").asText())
        || !claim.registrationDigest().equals(body.path("registrationDigest").asText())
        || !metadata.creationFingerprint().equals(body.path("creationFingerprint").asText())
        || !"UNSTAGED".equals(body.path("previousState").asText())
        || !cleanup.outbox().equals(body.path("outboxId").asText())
        || !body.path("generation").isIntegralNumber()
        || !body.path("generation").canConvertToLong()
        || body.path("generation").asLong() < 1
        || claim.executionId().toString().equals(body.path("recoveryId").asText()))
      throw unavailable();
    canonicalUuid(body.path("recoveryId").asText());
    canonicalUuid(cleanup.outbox());
    String account = body.path("accountId").textValue();
    if (account == null) {
      if (!body.path("accountId").isNull() || !body.path("creationProofDigest").isNull())
        throw unavailable();
    } else {
      canonicalUuid(account);
      if (!body.path("creationProofDigest").isTextual()
          || !body.path("creationProofDigest").asText().matches("[0-9a-f]{64}")
          || count("SELECT COUNT(*) FROM admin WHERE admin_id=?", account) != 0
          || count("SELECT COUNT(*) FROM account_inactivity WHERE identity_id=?", account) != 0)
        throw unavailable();
    }
    var known = creation.createdWithinOwner(historical);
    if (known.isPresent()) {
      requireCreated(claim, known.get());
      if (!Objects.equals(account, known.get().accountId())
          || !equal(proofDigest(known.get().creationProof()), body.path("creationProofDigest").asText()))
        throw unavailable();
    }
    if (jdbc.queryForObject(
        "SELECT account_id FROM protected_invitation_acceptance WHERE invite_id=?",
        String.class, inviteId(claim)) != null) throw unavailable();
  }

  private record Cleanup(String body, String digest, String outbox) {
    LocalOutcome outcome() {
      return new LocalOutcome(1, UUID.fromString(outbox), digest);
    }

    @Override
    public String toString() {
      return "ProtectedCleanup[redacted]";
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
    private final UUID recoveryId;
    private final long recoveryGeneration;

    private VerifiedDiscard(
        long inviteId,
        UUID claimId,
        UUID attemptId,
        UUID executionId,
        String accountId,
        UUID bootstrapIncarnation,
        UUID recoveryId,
        long recoveryGeneration) {
      this.inviteId = inviteId;
      this.claimId = claimId;
      this.attemptId = attemptId;
      this.executionId = executionId;
      this.accountId = accountId;
      this.bootstrapIncarnation = bootstrapIncarnation;
      this.recoveryId = recoveryId;
      this.recoveryGeneration = recoveryGeneration;
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

    public UUID recoveryId() {
      return recoveryId;
    }

    public long recoveryGeneration() {
      return recoveryGeneration;
    }

    @Override
    public String toString() {
      return "ProtectedVerifiedDiscard[redacted]";
    }
  }

  private <T> T owned(AcceptanceClaim claim, Supplier<T> action) {
    return owned(claim, false, action);
  }

  private <T> T owned(AcceptanceClaim claim, boolean historical, Supplier<T> action) {
    long invite = inviteId(claim);
    Supplier<T> checked =
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
                  "SELECT response_json FROM protected_invitation_authorization WHERE invite_id=?"
                      + (historical ? "" : " AND state='READY'"),
                  String.class,
                  invite);
          if (!(historical
                  ? Objects.equals(token, claim.tokenHash())
                  : equal(token, claim.tokenHash()))
              || !equal(
                  json.canonical(json.read(response, JsonNode.class)),
                  claim.approval().responseJson())) throw unavailable();
          return action.get();
        };
    return historical
        ? journal.withHistoricalInvite(invite, checked)
        : journal.withRecipientInvite(invite, checked);
  }

  private Row historicallyChecked(AcceptanceClaim claim, Row row) {
    if (!Set.of("READY", "COMMITTED").contains(row.state())
        || !claim.claimId().toString().equals(row.claimId())
        || !claim.attemptId().toString().equals(row.attemptId())
        || !claim.executionId().toString().equals(row.executionId())) throw unavailable();
    VerifiedCreated restored = accounts.restore(claim, row.checkpoint());
    requireCreated(claim, restored);
    if (!row.accountId().equals(restored.accountId())) throw unavailable();
    JsonNode effects = json.read(row.effects(), JsonNode.class);
    if (!equal(row.digest(), json.digest("protected-local-registration/v1", effects))
        || !row.accountId().equals(effects.path("admin").path("id").asText())
        || !row.accountId().equals(effects.path("inactivity").path("id").asText())
        || effects.path("admin").path("tenantId").asLong(-1) != restored.tenantId()
        || effects.path("inactivity").path("tenantId").asLong(-1) != restored.tenantId()
        || row.otpIncarnation() == null
        || row.otpReceipt() == null
        || row.otpReceipt().isBlank()
        || row.otpReceipt().length() > 16384) throw unavailable();
    canonicalUuid(row.otpIncarnation());
    String ready =
        json.digest(
            "protected-local-ready/v1",
            Map.of(
                "registrationEffectsDigest", row.digest(),
                "otpIncarnation", row.otpIncarnation(),
                "otpReceipt", row.otpReceipt()));
    if (!equal(ready, row.readyDigest())) throw unavailable();
    var invite =
        jdbc.queryForMap(
            "SELECT accepted_by_user_id FROM account_invite WHERE id=?", inviteId(claim));
    String accepted =
        jdbc.queryForObject(
            "SELECT account_id FROM protected_invitation_acceptance WHERE invite_id=?",
            String.class,
            inviteId(claim));
    if (!row.accountId().equals(invite.get("accepted_by_user_id"))
        || !row.accountId().equals(accepted)) throw unavailable();
    return row;
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
    return checked(claim, row, false);
  }

  private Row checked(AcceptanceClaim claim, Row row, boolean reducing) {
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
    if (!(reducing
            ? Set.of("ACCEPTED", "EXPIRED", "REVOKED", "SUPERSEDED").contains(invite.get("status"))
            : "ACCEPTED".equals(invite.get("status")))
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
