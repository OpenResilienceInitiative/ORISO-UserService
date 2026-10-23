package de.caritas.cob.userservice.api.service.identity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.Authority;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport.FrozenProfile;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual SQL authorization, private native scope and exact profile roll-forward after unknowns. */
public final class HumanAdminAccountMaintenance {
  private final IdentityClientConfig mode;
  private ReviewedOrdinaryGroups reviewedGroups = new ReviewedOrdinaryGroups(java.util.Map.of());
  private java.util.Map<String, List<Set<String>>> reviewedSelfProfiles = java.util.Map.of();
  private final AuthenticatedUser actor;
  private final AdminScope scope;
  private final JdbcTemplate jdbc;
  private final HttpServletRequest request;
  private final HumanAdminMaintenanceTransport transport;
  private final Clock clock;
  private final String publicIssuer;
  private final TransactionTemplate transactions;
  private final ObjectMapper json =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);

  /** Data-only installed profile projection. Receiving authority remains independent. */
  public void configureReviewedSelfProfileRoles(java.util.Map<String, List<Set<String>>> profiles) {
    Objects.requireNonNull(profiles);
    var kinds = Set.of("USER", "CONSULTANT", "AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");
    if (!kinds.containsAll(profiles.keySet()))
      throw new IllegalArgumentException("Unknown SELF SQL kind");
    var immutable = new java.util.TreeMap<String, List<Set<String>>>();
    profiles.forEach(
        (kind, configured) -> {
          if (configured == null || configured.size() > 20)
            throw new IllegalArgumentException("Invalid SELF profile catalog");
          var copies = new java.util.ArrayList<Set<String>>();
          for (Set<String> roles : configured) {
            if (roles == null
                || roles.isEmpty()
                || roles.size() > 32
                || roles.stream()
                    .anyMatch(role -> role == null || !role.matches("[a-z][a-z0-9-]{0,63}")))
              throw new IllegalArgumentException("Invalid SELF full role set");
            var copy = Set.copyOf(roles);
            if (copies.contains(copy))
              throw new IllegalArgumentException("Duplicate SELF full role set");
            copies.add(copy);
          }
          immutable.put(kind, List.copyOf(copies));
        });
    reviewedSelfProfiles = java.util.Map.copyOf(immutable);
  }

  /** Root supplies only the already validated immutable lifecycle group map. */
  public void configureReviewedOrdinaryGroups(java.util.Map<String, Set<String>> groups) {
    reviewedGroups = new ReviewedOrdinaryGroups(groups);
  }

  public HumanAdminAccountMaintenance(
      IdentityClientConfig mode,
      AuthenticatedUser actor,
      AdminScope scope,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      HttpServletRequest request,
      HumanAdminMaintenanceTransport transport,
      Clock clock,
      String publicIssuer) {
    this.mode = Objects.requireNonNull(mode);
    this.actor = Objects.requireNonNull(actor);
    this.scope = Objects.requireNonNull(scope);
    this.jdbc = Objects.requireNonNull(jdbc);
    this.request = Objects.requireNonNull(request);
    this.transport = Objects.requireNonNull(transport);
    this.clock = Objects.requireNonNull(clock);
    this.publicIssuer = Objects.requireNonNull(publicIssuer);
    javax.sql.DataSource owner = null;
    if (transactions instanceof JpaTransactionManager jpa) owner = jpa.getDataSource();
    else if (transactions instanceof DataSourceTransactionManager sql) owner = sql.getDataSource();
    if (owner == null || owner != jdbc.getDataSource()) throw unavailable();
    this.transactions = new TransactionTemplate(transactions);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public boolean isFiveActorMode() {
    return mode.isFiveActorMode();
  }

  public HumanAdminMaintenanceTransport.Projection read(String accountId) {
    requireOutsideSql();
    Human human = currentHuman();
    AdminRow row = authorizedRow(accountId, false);
    try (var checked = new CheckedAccount(row, row, human, UUID.randomUUID(), null)) {
      var result = transport.read(checked);
      requireProjection(row, result);
      return result;
    }
  }

  public HumanAdminMaintenanceTransport.FactorStatus factorStatus(String accountId) {
    requireOutsideSql();
    Human human = currentHuman();
    AdminRow row = authorizedRow(accountId, false);
    try (var checked = new CheckedAccount(row, row, human, UUID.randomUUID(), null)) {
      var result = transport.factorStatus(checked);
      if (result == null || result.twoFactor() == null) throw unavailable();
      requireProjection(row, result.account());
      return result.twoFactor();
    }
  }

  public de.caritas.cob.userservice.api.model.Admin updateAdminProfile(
      String accountId, IdentityProfileUpdate profile) {
    return (de.caritas.cob.userservice.api.model.Admin) updateProfile(accountId, profile, null);
  }

  public de.caritas.cob.userservice.api.model.Consultant updateConsultantProfile(
      de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService
              .ScopedEdit
          edit) {
    Objects.requireNonNull(edit);
    if (edit.selfService()) throw forbidden();
    requireOutsideSql();
    currentHuman();
    authorizedRow(edit.accountId(), false);
    return (de.caritas.cob.userservice.api.model.Consultant)
        updateProfile(edit.accountId(), edit.profile(), edit);
  }

  public de.caritas.cob.userservice.api.model.Consultant updateOwnConsultantProfile(
      de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService
              .ScopedEdit
          edit) {
    Objects.requireNonNull(edit);
    requireOutsideSql();
    if (!edit.selfService()
        || !actor.isConsultant()
        || !"PUT".equals(request.getMethod())
        || !Set.of("/users/data", "/service/users/data").contains(request.getRequestURI())
        || !currentHuman().subject.equals(edit.accountId())) throw forbidden();
    var owner = authorizedRow(edit.accountId(), false, true);
    if (reviewedSelfProfiles.getOrDefault(selfKind(owner), List.of()).isEmpty()) throw forbidden();
    edit.requireSelfInput();
    return (de.caritas.cob.userservice.api.model.Consultant)
        updateProfile(edit.accountId(), edit.profile(), edit, true);
  }

  private Object updateProfile(
      String accountId,
      IdentityProfileUpdate profile,
      de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService
              .ScopedEdit
          edit) {
    return updateProfile(accountId, profile, edit, false);
  }

  private Object updateProfile(
      String accountId,
      IdentityProfileUpdate profile,
      de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService
              .ScopedEdit
          edit,
      boolean self) {
    requireOutsideSql();
    if (!Set.of("PATCH", "PUT").contains(request.getMethod())) throw forbidden();
    Human human = currentHuman();
    UUID operation = operationId();
    AdminRow current = authorizedRow(accountId, false, self);
    // Consultant-only read scopes never authorize the Admin profile writer.
    if ("CONSULTANT".equals(current.type) && edit == null) throw forbidden();
    if (edit != null && !"CONSULTANT".equals(current.type) && current.secondary == null)
      throw forbidden();
    validateProfile(current, profile);
    Intent prior = intent(operation, false);
    ConsultantOriginal fullOriginal =
        edit == null || prior == null ? null : decode(prior.original, ConsultantOriginal.class);
    AdminRow original =
        prior == null
            ? current
            : edit == null ? decode(prior.original, AdminRow.class) : fullOriginal.row;
    if (!current.sameIdentity(original)) throw conflict();
    String fullSql =
        edit == null
            ? null
            : fullOriginal == null ? consultantSnapshot(accountId, false) : fullOriginal.sql;
    if (edit != null && (prior == null || !"APPLIED".equals(prior.status))) edit.validate();
    try (var checked =
        new CheckedAccount(current, original, human, operation, profile, edit, fullSql, self)) {
      checked.applied = prior != null && "APPLIED".equals(prior.status);
      checked.terminalOnly = prior != null && "NO_EFFECT".equals(prior.status);
      HumanAdminMaintenanceTransport.Projection initialRoleProjection = null;
      if (edit != null && edit.groupChatRequested() != null) {
        reviewedGroups.requireConfigured();
        java.util.List<String> roles;
        if (prior == null) {
          initialRoleProjection = transport.read(checked);
          requireProjection(current, initialRoleProjection);
          roles = initialRoleProjection.effectiveRoles();
        } else {
          try {
            var savedRoles = json.readTree(prior.desired).path("roleOriginal");
            if (!savedRoles.isArray() || savedRoles.isEmpty()) throw conflict();
            var values = new java.util.ArrayList<String>();
            for (var role : savedRoles) {
              if (!role.isTextual()) throw conflict();
              values.add(role.textValue());
            }
            roles = values;
          } catch (java.io.IOException invalid) {
            throw unavailable();
          }
        }
        checked.role = reviewedGroups.groupChat(edit.groupChatRequested(), roles);
      }
      final var initialRoleState = initialRoleProjection;
      String digest = transport.profileRequestDigest(checked, profile, operation);
      if (digest == null || !digest.matches("[0-9a-f]{64}")) throw unavailable();
      String originalJson =
          edit == null ? encode(original) : encode(new ConsultantOriginal(original, fullSql));
      String desiredJson =
          edit == null
              ? encode(profile)
              : checked.role == null
                  ? encode(java.util.Map.of("profile", profile, "publicEdit", edit.intentJson()))
                  : encode(
                      java.util.Map.of(
                          "profile",
                          profile,
                          "publicEdit",
                          edit.intentJson(),
                          "roleOriginal",
                          checked.role.original(),
                          "roleDesired",
                          checked.role.desired(),
                          "group",
                          checked.role.group()));
      checked.terminals = verifiedTerminals(checked);
      Intent intent =
          recoverCommit(
              () -> transaction(() -> prepare(checked, digest, originalJson, desiredJson)),
              () -> transaction(() -> checkedIntent(checked, digest, originalJson, desiredJson)));
      if ("NO_EFFECT".equals(intent.status)) {
        var verified = transport.verifyProfileNoEffect(checked, frozen(intent), intent.receipt);
        throw definiteProfileRefusal(checked, verified.status());
      }
      if ("APPLIED".equals(intent.status)) {
        checked.applied = true;
        requireDesired(authorizedRow(accountId, false, self), original, profile);
        var originalFrozen = frozen(intent);
        String originalReceipt =
            transport.verifyProfileReceipt(checked, originalFrozen, intent.receipt);
        // Rejoin current receiving authority using only the original acknowledged native command.
        // A refusal here cannot replace the already successful local/native receipt or redo SQL.
        var replay = transport.executeProfile(checked, originalFrozen);
        if (!(replay instanceof HumanAdminMaintenanceTransport.ProfileApplied applied)
            || !originalReceipt.equals(
                transport.verifyProfileReceipt(checked, originalFrozen, applied.receipt())))
          throw unavailable();
        return profileResultOrConsultant(checked);
      }
      if ("PREPARED".equals(intent.status)) {
        var state = initialRoleState == null ? transport.read(checked) : initialRoleState;
        requireProjection(checked, state);
        if (checked.role != null
            && !java.util.Set.copyOf(checked.role.original())
                .equals(java.util.Set.copyOf(state.effectiveRoles()))) throw conflict();
        FrozenProfile frozen = transport.freezeProfile(checked, profile, operation, state.state());
        String body = encode(frozen);
        intent =
            recoverCommit(
                () -> transaction(() -> freeze(checked, digest, originalJson, desiredJson, body)),
                () ->
                    transaction(
                        () -> {
                          var found = checkedIntent(checked, digest, originalJson, desiredJson);
                          if (found == null
                              || !Set.of("FROZEN", "ACKNOWLEDGED", "APPLIED").contains(found.status)
                              || !body.equals(found.body)) throw unavailable();
                          return found;
                        }));
      }
      FrozenProfile frozen = frozen(intent);
      String receipt;
      if ("ACKNOWLEDGED".equals(intent.status)) {
        receipt = transport.verifyProfileReceipt(checked, frozen, intent.receipt);
      } else if ("FROZEN".equals(intent.status)) {
        var outcome = transport.executeProfile(checked, frozen);
        if (outcome instanceof HumanAdminMaintenanceTransport.ProfileNoEffect refused) {
          var verified = transport.verifyProfileNoEffect(checked, frozen, refused.receipt());
          final var priorFrozen = intent;
          recoverCommit(
              () ->
                  transaction(
                      () ->
                          markNoEffect(
                              checked,
                              digest,
                              originalJson,
                              desiredJson,
                              priorFrozen.body,
                              verified.receipt())),
              () ->
                  transaction(
                      () -> {
                        var saved = checkedIntent(checked, digest, originalJson, desiredJson);
                        if (saved == null
                            || !"NO_EFFECT".equals(saved.status)
                            || !Objects.equals(saved.body, priorFrozen.body)
                            || !Objects.equals(saved.receipt, verified.receipt()))
                          throw unavailable();
                        return saved;
                      }));
          throw definiteProfileRefusal(checked, verified.status());
        }
        if (!(outcome instanceof HumanAdminMaintenanceTransport.ProfileApplied applied))
          throw unavailable();
        receipt = applied.receipt();
        final Intent originalIntent = intent;
        intent =
            recoverCommit(
                () ->
                    transaction(
                        () ->
                            acknowledge(
                                checked,
                                digest,
                                originalJson,
                                desiredJson,
                                originalIntent.body,
                                receipt)),
                () ->
                    transaction(
                        () -> {
                          var found = checkedIntent(checked, digest, originalJson, desiredJson);
                          if (found == null
                              || !Set.of("ACKNOWLEDGED", "APPLIED").contains(found.status)
                              || !Objects.equals(receipt, found.receipt)
                              || !Objects.equals(originalIntent.body, found.body))
                            throw unavailable();
                          return found;
                        }));
      } else throw unavailable();
      // No other legitimate admin-profile issuer may pass this account's pending SQL intent.
      // Confirm that the actual native fields still describe this acknowledged request.
      transport.verifyProfileReceipt(checked, frozen, receipt);
      var currentNative = transport.read(checked);
      requireProjection(checked, currentNative);
      if (checked.role != null
          && !java.util.Set.copyOf(checked.role.desired())
              .equals(java.util.Set.copyOf(currentNative.effectiveRoles()))) throw conflict();
      if (!Objects.equals(currentNative.firstName(), profile.firstName())
          || !Objects.equals(currentNative.lastName(), profile.lastName())
          || !Objects.equals(currentNative.email(), profile.email())) throw conflict();
      try {
        transaction(
            () -> {
              apply(checked, digest, originalJson, desiredJson, receipt);
              return null;
            });
      } catch (ConflictException | ForbiddenException refusal) {
        throw refusal;
      } catch (RuntimeException uncertain) {
        var found = transaction(() -> checkedIntent(checked, digest, originalJson, desiredJson));
        if (found == null
            || !"APPLIED".equals(found.status)
            || !Objects.equals(receipt, found.receipt)) throw unavailable();
        requireDesired(authorizedRow(accountId, false, self), original, profile);
      }
      return profileResultOrConsultant(checked);
    }
  }

  private Object profileResultOrConsultant(CheckedAccount checked) {
    if (checked.edit == null) return profileResult(checked);
    return transaction(
        () -> {
          requireDesired(
              authorizedRow(checked.actual.account, true, checked.self),
              checked.original,
              checked.profile);
          var found = intent(checked.workflow, true);
          requireConsultantPostcondition(checked, Objects.requireNonNull(found));
          return checked.edit.current();
        });
  }

  private record ConsultantOriginal(AdminRow row, String sql) {
    @Override
    public String toString() {
      return "ConsultantOriginal[profile=redacted]";
    }
  }

  private String consultantSnapshot(String id, boolean lock) {
    return ConsultantProfileSqlSnapshot.capture(jdbc, id, lock, this::encode);
  }

  private void requireConsultantPostcondition(CheckedAccount checked, Intent intent) {
    if (checked.edit != null
        && (!"APPLIED".equals(intent.status)
            || intent.applied == null
            || !Objects.equals(intent.applied, consultantSnapshot(checked.actual.account, true))))
      throw conflict();
  }

  private void requireConsultantSnapshot(CheckedAccount checked, String status) {
    if (checked.edit != null
        && !Set.of("APPLIED", "NO_EFFECT").contains(status)
        && !Objects.equals(checked.fullSql, consultantSnapshot(checked.actual.account, true)))
      throw conflict();
  }

  private de.caritas.cob.userservice.api.model.Admin profileResult(CheckedAccount checked) {
    return transaction(
        () -> {
          requireDesired(
              authorizedRow(checked.actual.account, true, checked.self),
              checked.original,
              checked.profile);
          return jdbc.queryForObject(
              "select admin_id,tenant_id,username,type,first_name,last_name,email,managed_paused,id_old,create_date,update_date from admin where admin_id=?",
              (r, index) ->
                  de.caritas.cob.userservice.api.model.Admin.builder()
                      .id(r.getString("admin_id"))
                      .tenantId(nullableLong(r, "tenant_id"))
                      .username(r.getString("username"))
                      .type(
                          de.caritas.cob.userservice.api.model.Admin.AdminType.valueOf(
                              r.getString("type")))
                      .firstName(r.getString("first_name"))
                      .lastName(r.getString("last_name"))
                      .email(r.getString("email"))
                      .managedPaused(r.getBoolean("managed_paused"))
                      .oldId(nullableLong(r, "id_old"))
                      .createDate(
                          r.getTimestamp("create_date") == null
                              ? null
                              : r.getTimestamp("create_date").toLocalDateTime())
                      .updateDate(
                          r.getTimestamp("update_date") == null
                              ? null
                              : r.getTimestamp("update_date").toLocalDateTime())
                      .build(),
              checked.actual.account);
        });
  }

  private record StoredTerminal(
      String operation, String digest, String body, String receipt, String status) {}

  private java.util.Map<String, StoredTerminal> verifiedTerminals(CheckedAccount checked) {
    var rows =
        jdbc.query(
            "select operation_id,request_digest,native_body,native_receipt,status from general_admin_identity_intent where account_id=? and operation_id<>? and status='NO_EFFECT'",
            (r, n) ->
                new StoredTerminal(
                    r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)),
            checked.accountId(),
            checked.workflowId().toString());
    var verified = new java.util.HashMap<String, StoredTerminal>();
    for (var row : rows) {
      transport.verifyStoredProfileNoEffect(
          checked,
          canonicalUuid(row.operation),
          row.digest,
          decode(row.body, FrozenProfile.class),
          row.receipt);
      if (verified.put(row.operation, row) != null) throw unavailable();
    }
    return java.util.Map.copyOf(verified);
  }

  private Intent markNoEffect(
      CheckedAccount checked,
      String digest,
      String original,
      String desired,
      String frozen,
      String receipt) {
    var saved = checkedIntent(checked, digest, original, desired);
    requireConsultantSnapshot(checked, saved.status);
    var current = authorizedRow(checked.actual.account, true, checked.self);
    if (!"FROZEN".equals(saved.status)
        || !Objects.equals(saved.body, frozen)
        || saved.receipt != null
        || !current.equals(checked.original)) throw conflict();
    if (jdbc.update(
            "update general_admin_identity_intent set status='NO_EFFECT',native_receipt=?,updated_at=? where operation_id=? and status='FROZEN' and native_body=? and native_receipt is null",
            receipt,
            Timestamp.from(clock.instant()),
            checked.workflow.toString(),
            frozen)
        != 1) throw unavailable();
    return Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
  }

  /** A closed signal minted only after a verified refusal is durably terminal. */
  public sealed interface DefiniteProfileNoEffect
      permits DefiniteProfileForbidden, DefiniteProfileConflict {
    UUID operationId();
  }

  public static final class DefiniteProfileForbidden extends ForbiddenException
      implements DefiniteProfileNoEffect {
    private final UUID operation;

    private DefiniteProfileForbidden(UUID operation) {
      super("Managed account operation is not authorized");
      this.operation = operation;
    }

    @Override
    public UUID operationId() {
      return operation;
    }
  }

  public static final class DefiniteProfileConflict extends ConflictException
      implements DefiniteProfileNoEffect {
    private final UUID operation;

    private DefiniteProfileConflict(UUID operation) {
      super("Managed account operation conflicts with its current state");
      this.operation = operation;
    }

    @Override
    public UUID operationId() {
      return operation;
    }
  }

  private RuntimeException definiteProfileRefusal(CheckedAccount checked, int status) {
    if (status == 403) return new DefiniteProfileForbidden(checked.workflow);
    if (status == 409) return new DefiniteProfileConflict(checked.workflow);
    throw unavailable();
  }

  private Intent prepare(CheckedAccount checked, String digest, String original, String desired) {
    if (checked.role != null) {
      var lifecycle =
          jdbc.queryForList(
              "select status from account_inactivity where identity_id=? for update",
              String.class,
              checked.actual.account);
      if (lifecycle.size() > 1 || !lifecycle.isEmpty() && !"ACTIVE".equals(lifecycle.getFirst()))
        throw conflict();
    }
    var row = authorizedRow(checked.actual.account, true, checked.self);
    var found = checkedIntent(checked, digest, original, desired);
    requireConsultantSnapshot(checked, found == null ? "PREPARED" : found.status);
    if (found != null) {
      requireOwnedRow(row, checked.original, checked.profile, found.status);
      return found;
    }
    if (!row.equals(checked.original)) throw conflict();
    var pending =
        jdbc.query(
            "select operation_id,request_digest,native_body,native_receipt,status from general_admin_identity_intent where account_id=? and operation_id<>? and status<>'APPLIED' for update",
            (r, n) ->
                new StoredTerminal(
                    r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)),
            row.account,
            checked.workflow.toString());
    for (var other : pending)
      if (!"NO_EFFECT".equals(other.status)
          || !other.equals(checked.terminals.get(other.operation))) throw conflict();
    Instant now = clock.instant();
    if (jdbc.update(
            "insert into general_admin_identity_intent(operation_id,account_id,tenant_id,human_subject,human_issuer,human_client,request_digest,original_json,desired_json,status,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,'PREPARED',?,?)",
            checked.workflow.toString(),
            row.account,
            row.tenant,
            checked.human.subject,
            checked.human.issuer,
            checked.human.client,
            digest,
            original,
            desired,
            Timestamp.from(now),
            Timestamp.from(now))
        != 1) throw unavailable();
    return Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
  }

  private Intent freeze(
      CheckedAccount checked, String digest, String original, String desired, String body) {
    var row = authorizedRow(checked.actual.account, true, checked.self);
    var found = Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
    requireConsultantSnapshot(checked, found.status);
    requireOwnedRow(row, checked.original, checked.profile, found.status);
    if (!"PREPARED".equals(found.status)) {
      if (!body.equals(found.body)) throw conflict();
      return found;
    }
    if (jdbc.update(
            "update general_admin_identity_intent set native_body=?,status='FROZEN',updated_at=? where operation_id=? and status='PREPARED' and native_body is null and native_receipt is null",
            body,
            Timestamp.from(clock.instant()),
            checked.workflow.toString())
        != 1) throw conflict();
    return Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
  }

  private Intent acknowledge(
      CheckedAccount checked,
      String digest,
      String original,
      String desired,
      String body,
      String receipt) {
    var row = authorizedRow(checked.actual.account, true, checked.self);
    var found = Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
    requireConsultantSnapshot(checked, found.status);
    requireOwnedRow(row, checked.original, checked.profile, found.status);
    if (!body.equals(found.body)) throw conflict();
    if (Set.of("ACKNOWLEDGED", "APPLIED").contains(found.status)) {
      if (!Objects.equals(receipt, found.receipt)) throw conflict();
      return found;
    }
    if (!"FROZEN".equals(found.status)
        || receipt == null
        || jdbc.update(
                "update general_admin_identity_intent set native_receipt=?,status='ACKNOWLEDGED',updated_at=? where operation_id=? and status='FROZEN' and native_receipt is null",
                receipt,
                Timestamp.from(clock.instant()),
                checked.workflow.toString())
            != 1) throw conflict();
    return Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
  }

  private void apply(
      CheckedAccount checked, String digest, String original, String desired, String receipt) {
    var row = authorizedRow(checked.actual.account, true, checked.self);
    var found = Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
    requireConsultantSnapshot(checked, found.status);
    if (!receipt.equals(found.receipt)) throw unavailable();
    if ("APPLIED".equals(found.status)) {
      requireDesired(row, checked.original, checked.profile);
      return;
    }
    if (!"ACKNOWLEDGED".equals(found.status) || !row.equals(checked.original)) throw conflict();
    var profile = checked.profile;
    if ("CONSULTANT".equals(row.type)) {
      if (jdbc.update(
              "update consultant set first_name=?,last_name=?,email=?,update_date=? where consultant_id=? and delete_date is null and username=? and ((tenant_id=?) or (tenant_id is null and ? is null)) and first_name=? and last_name=? and email=? and ((update_date=?) or (update_date is null and ? is null))",
              profile.firstName(),
              profile.lastName(),
              profile.email(),
              Timestamp.from(clock.instant()),
              row.account,
              row.username,
              row.tenant,
              row.tenant,
              row.firstName,
              row.lastName,
              row.email,
              timestamp(row.updated),
              timestamp(row.updated))
          != 1) throw conflict();
    } else if (jdbc.update(
            "update admin set first_name=?,last_name=?,email=?,update_date=? where admin_id=? and username=? and type=? and ((tenant_id=?) or (tenant_id is null and ? is null)) and first_name=? and last_name=? and email=? and ((update_date=?) or (update_date is null and ? is null))",
            profile.firstName(),
            profile.lastName(),
            profile.email(),
            Timestamp.from(clock.instant()),
            row.account,
            row.username,
            row.type,
            row.tenant,
            row.tenant,
            row.firstName,
            row.lastName,
            row.email,
            timestamp(row.updated),
            timestamp(row.updated))
        != 1) throw conflict();
    if (row.secondary != null) {
      var secondary = row.secondary;
      if (jdbc.update(
              "update consultant set first_name=?,last_name=?,email=?,update_date=? where consultant_id=? and username=? and ((tenant_id=?) or (tenant_id is null and ? is null)) and delete_date is null and first_name=? and last_name=? and email=? and ((update_date=?) or (update_date is null and ? is null))",
              profile.firstName(),
              profile.lastName(),
              profile.email(),
              Timestamp.from(clock.instant()),
              secondary.account,
              secondary.username,
              secondary.tenant,
              secondary.tenant,
              secondary.firstName,
              secondary.lastName,
              secondary.email,
              timestamp(secondary.updated),
              timestamp(secondary.updated))
          != 1) throw conflict();
    }
    if (checked.edit != null) {
      checked.edit.apply();
      checked.edit.flush();
    }
    requireDesired(authorizedRow(row.account, true, checked.self), row, profile);
    if (jdbc.update(
            "update general_admin_identity_intent set status='APPLIED',applied_json=?,updated_at=? where operation_id=? and status='ACKNOWLEDGED' and native_receipt=? and applied_json is null",
            checked.edit == null ? null : consultantSnapshot(checked.actual.account, true),
            Timestamp.from(clock.instant()),
            checked.workflow.toString(),
            receipt)
        != 1) throw conflict();
  }

  private Intent checkedIntent(
      CheckedAccount checked, String digest, String original, String desired) {
    currentHumanMatching(checked.human);
    var found = intent(checked.workflow, true);
    if (found == null) return null;
    if (!checked.actual.account.equals(found.account)
        || !Objects.equals(checked.actual.tenant, found.tenant)
        || !checked.human.subject.equals(found.humanSubject)
        || !checked.human.issuer.equals(found.humanIssuer)
        || !checked.human.client.equals(found.humanClient)
        || !digest.equals(found.digest)
        || !original.equals(found.original)
        || !desired.equals(found.desired)) throw conflict();
    if (!Set.of("PREPARED", "FROZEN", "ACKNOWLEDGED", "APPLIED", "NO_EFFECT").contains(found.status)
        || "PREPARED".equals(found.status) && (found.body != null || found.receipt != null)
        || !"PREPARED".equals(found.status) && found.body == null
        || Set.of("ACKNOWLEDGED", "APPLIED", "NO_EFFECT").contains(found.status)
            && found.receipt == null
        || "FROZEN".equals(found.status) && found.receipt != null
        || !"APPLIED".equals(found.status) && found.applied != null
        || checked.edit != null && "APPLIED".equals(found.status) && found.applied == null)
      throw unavailable();
    return found;
  }

  private Intent intent(UUID operation, boolean lock) {
    var rows =
        jdbc.query(
            "select * from general_admin_identity_intent where operation_id=?"
                + (lock ? " for update" : ""),
            (r, index) ->
                new Intent(
                    r.getString("account_id"),
                    nullableLong(r, "tenant_id"),
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
            operation.toString());
    if (rows.size() > 1) throw unavailable();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private AdminRow authorizedRow(String account, boolean lock) {
    return authorizedRow(account, lock, false);
  }

  private AdminRow authorizedRow(String account, boolean lock, boolean self) {
    if (self && !TransactionSynchronizationManager.isActualTransactionActive())
      return transaction(() -> authorizedRow(account, true, true));
    canonicalUuid(account);
    var rows =
        jdbc.query(
            "select a.admin_id,a.tenant_id,a.username,a.type,a.first_name,a.last_name,a.email,a.update_date,c.consultant_id,c.tenant_id as consultant_tenant,c.username as consultant_username,c.first_name as consultant_first,c.last_name as consultant_last,c.email as consultant_email,c.delete_date as consultant_deleted,c.update_date as consultant_updated,u.user_id as user_collision from (select cast(? as char(36)) as requested_id) owned left join admin a on a.admin_id=owned.requested_id left join consultant c on c.consultant_id=owned.requested_id left join user u on u.user_id=owned.requested_id"
                + (lock ? " for update" : ""),
            (r, index) -> {
              if (r.getString("user_collision") != null) throw forbidden();
              ConsultantRow secondary =
                  r.getString("consultant_id") == null
                      ? null
                      : new ConsultantRow(
                          r.getString("consultant_id"), nullableLong(r, "consultant_tenant"),
                          r.getString("consultant_username"), r.getString("consultant_first"),
                          r.getString("consultant_last"), r.getString("consultant_email"),
                          instant(r, "consultant_deleted"), instant(r, "consultant_updated"));
              if (r.getString("admin_id") == null) {
                if (secondary == null) return null;
                if (secondary.deleted != null) throw forbidden();
                return new AdminRow(
                    secondary.account,
                    secondary.tenant,
                    secondary.username,
                    "CONSULTANT",
                    secondary.firstName,
                    secondary.lastName,
                    secondary.email,
                    secondary.updated,
                    null);
              }
              return new AdminRow(
                  r.getString("admin_id"),
                  nullableLong(r, "tenant_id"),
                  r.getString("username"),
                  r.getString("type"),
                  r.getString("first_name"),
                  r.getString("last_name"),
                  r.getString("email"),
                  instant(r, "update_date"),
                  secondary);
            },
            account);
    if (rows.isEmpty() || rows.size() == 1 && rows.getFirst() == null)
      throw new NotFoundException("Managed account was not found");
    if (rows.size() != 1) throw forbidden();
    var row = rows.getFirst();
    if (self) {
      if (!currentHuman().subject.equals(account)
          || !Objects.equals(actor.getTenantId(), row.tenant)) throw forbidden();
      var owner =
          de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAccountOwner.inspect(
              jdbc, account, row.tenant);
      if (!(owner.kind()
                  == de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAccountOwner
                      .Kind.CONSULTANT
              && "CONSULTANT".equals(row.type)
          || owner.consultant() && row.secondary != null)) throw forbidden();
      var secondary = row.secondary;
      if (secondary != null
          && (secondary.deleted != null
              || !Objects.equals(row.tenant, secondary.tenant)
              || !decodedUsername(row.username).equals(decodedUsername(secondary.username))))
        throw forbidden();
      return row;
    }
    var authorities = actor.getGrantedAuthorities();
    if (authorities == null
        || !("CONSULTANT".equals(row.type)
                && authorities.contains(Authority.AuthorityValue.USER_ADMIN)
            || "TENANT".equals(row.type)
                && authorities.contains(Authority.AuthorityValue.TENANT_ADMIN)
            || "AGENCY".equals(row.type)
                && authorities.contains(Authority.AuthorityValue.USER_ADMIN)
            || "SUPER".equals(row.type)
                && actor.isPlatformAdmin()
                && authorities.contains(Authority.AuthorityValue.TENANT_ADMIN))) throw forbidden();
    var secondary = row.secondary;
    if (secondary != null
        && (secondary.deleted != null
            || !row.account.equals(secondary.account)
            || !Objects.equals(row.tenant, secondary.tenant)
            || !decodedUsername(row.username).equals(decodedUsername(secondary.username))))
      throw forbidden();
    scope.assertMay(
        "CONSULTANT".equals(row.type)
            ? AdminScope.Target.counsellor(account)
            : AdminScope.Target.admin(account));
    if (secondary != null) scope.assertMay(AdminScope.Target.counsellor(account));
    return row;
  }

  private Human currentHuman() {
    if (!mode.isFiveActorMode()
        || !(request.getUserPrincipal() instanceof JwtAuthenticationToken authentication)
        || !authentication.isAuthenticated()) throw forbidden();
    var jwt = authentication.getToken();
    Object azp = jwt.getClaims().get("azp");
    String raw = request.getHeader("Authorization");
    if (raw == null
        || raw.length() > 32775
        || !raw.regionMatches(true, 0, "Bearer ", 0, 7)
        || !jwt.getTokenValue().equals(raw.substring(7))
        || !jwt.getTokenValue().equals(actor.getAccessToken())
        || !Objects.equals(jwt.getSubject(), actor.getUserId())
        || jwt.getIssuer() == null
        || !publicIssuer.equals(jwt.getIssuer().toString())
        || !(azp instanceof String client)
        || client.isBlank()
        || client.length() > 255
        || jwt.getIssuedAt() == null
        || jwt.getIssuedAt().getEpochSecond() <= 0
        || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(5))
        || jwt.getExpiresAt() == null
        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
        || !jwt.getExpiresAt().isAfter(clock.instant())) throw forbidden();
    canonicalUuid(jwt.getSubject());
    Object suppliedTenant = jwt.getClaims().get("tenantId");
    Long tenant = null;
    if (jwt.getClaims().containsKey("tenantId")) {
      if (!(suppliedTenant instanceof String value) || !value.matches("0|[1-9][0-9]{0,18}"))
        throw forbidden();
      try {
        tenant = Long.valueOf(value);
      } catch (NumberFormatException invalid) {
        throw forbidden();
      }
    }
    if (!Objects.equals(tenant, actor.getTenantId())) throw forbidden();
    return new Human(jwt.getSubject(), publicIssuer, client, raw, jwt.getExpiresAt());
  }

  private void currentHumanMatching(Human expected) {
    if (!expected.equals(currentHuman())) throw forbidden();
  }

  private void requireOutsideSql() {
    if (!mode.isFiveActorMode()
        || TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.hasResource(
            Objects.requireNonNull(jdbc.getDataSource()))) throw unavailable();
  }

  private UUID operationId() {
    String supplied = request.getHeader("Idempotency-Key");
    try {
      return canonicalUuid(supplied);
    } catch (RuntimeException invalid) {
      throw new BadRequestException(
          "A canonical Idempotency-Key is required for this account change");
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
      return parsed;
    } catch (RuntimeException invalid) {
      throw forbidden();
    }
  }

  private static void requireProjection(
      AdminRow row, HumanAdminMaintenanceTransport.Projection projection) {
    if (projection == null
        || !row.account.equals(projection.id())
        || !Objects.equals(row.tenant, projection.tenantId())
        || !decodedUsername(row.username).equals(projection.username())) throw unavailable();
  }

  private void requireProjection(
      CheckedAccount checked, HumanAdminMaintenanceTransport.Projection projection) {
    requireProjection(checked.actual, projection);
    if (!checked.self) return;
    var roles = projection.effectiveRoles();
    if (roles == null
        || roles.isEmpty()
        || Set.copyOf(roles).size() != roles.size()
        || !roles.contains("consultant")
        || !reviewedSelfProfiles
            .getOrDefault(selfKind(checked.actual), List.of())
            .contains(Set.copyOf(roles))) throw forbidden();
  }

  private static String selfKind(AdminRow row) {
    return switch (row.type) {
      case "CONSULTANT" -> "CONSULTANT";
      case "AGENCY" -> "AGENCY_ADMIN";
      case "TENANT" -> "TENANT_ADMIN";
      case "SUPER" -> "PLATFORM_ADMIN";
      default -> throw forbidden();
    };
  }

  private static void validateProfile(AdminRow row, IdentityProfileUpdate profile) {
    if (profile == null
        || !Objects.equals(row.username, profile.username())
        || !Objects.equals(row.tenant, profile.tenantId())) throw forbidden();
    for (String value :
        List.of(
            profile.firstName() == null ? "" : profile.firstName(),
            profile.lastName() == null ? "" : profile.lastName(),
            profile.email() == null ? "" : profile.email()))
      if (value.isBlank()
          || value.length() > 255
          || value.chars().anyMatch(Character::isISOControl))
        throw new BadRequestException("Managed profile fields are invalid");
  }

  private static void requireOwnedRow(
      AdminRow row, AdminRow original, IdentityProfileUpdate desired, String status) {
    if ("APPLIED".equals(status)) requireDesired(row, original, desired);
    else if ("NO_EFFECT".equals(status)) {
      if (!row.sameIdentity(original)) throw conflict();
    } else if (!row.equals(original)) throw conflict();
  }

  private static void requireDesired(
      AdminRow row, AdminRow original, IdentityProfileUpdate desired) {
    if (!row.sameIdentity(original)
        || !Objects.equals(row.firstName, desired.firstName())
        || !Objects.equals(row.lastName, desired.lastName())
        || !Objects.equals(row.email, desired.email())
        || row.secondary != null
            && (!Objects.equals(row.secondary.firstName, desired.firstName())
                || !Objects.equals(row.secondary.lastName, desired.lastName())
                || !Objects.equals(row.secondary.email, desired.email()))) throw conflict();
  }

  private static String decodedUsername(String value) {
    if (value == null) throw forbidden();
    return new de.caritas.cob.userservice.api.helper.UsernameTranscoder().decodeUsername(value);
  }

  private FrozenProfile frozen(Intent intent) {
    if (intent == null || intent.body == null) throw unavailable();
    return decode(intent.body, FrozenProfile.class);
  }

  private <T> T transaction(Supplier<T> action) {
    return transactions.execute(ignored -> action.get());
  }

  private <T> T recoverCommit(Supplier<T> action, Supplier<T> resolve) {
    try {
      return action.get();
    } catch (ConflictException | ForbiddenException | BadRequestException refusal) {
      throw refusal;
    } catch (RuntimeException unknown) {
      T saved = resolve.get();
      if (saved == null) throw unavailable();
      return saved;
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

  private static Long nullableLong(ResultSet row, String name) throws SQLException {
    long value = row.getLong(name);
    return row.wasNull() ? null : value;
  }

  private static String instant(ResultSet row, String name) throws SQLException {
    Timestamp value = row.getTimestamp(name);
    return value == null ? null : value.toInstant().toString();
  }

  private static Timestamp timestamp(String value) {
    return value == null ? null : Timestamp.from(Instant.parse(value));
  }

  private record Human(
      String subject, String issuer, String client, String rawBearer, Instant expires) {
    @Override
    public String toString() {
      return "Human[authorization=redacted]";
    }
  }

  private record AdminRow(
      String account,
      Long tenant,
      String username,
      String type,
      String firstName,
      String lastName,
      String email,
      String updated,
      ConsultantRow secondary) {
    boolean sameIdentity(AdminRow other) {
      return account.equals(other.account)
          && Objects.equals(tenant, other.tenant)
          && username.equals(other.username)
          && type.equals(other.type)
          && (secondary == null
              ? other.secondary == null
              : other.secondary != null && secondary.sameIdentity(other.secondary));
    }

    @Override
    public String toString() {
      return "AdminRow[profile=redacted]";
    }
  }

  private record ConsultantRow(
      String account,
      Long tenant,
      String username,
      String firstName,
      String lastName,
      String email,
      String deleted,
      String updated) {
    boolean sameIdentity(ConsultantRow other) {
      return account.equals(other.account)
          && Objects.equals(tenant, other.tenant)
          && Objects.equals(username, other.username)
          && Objects.equals(deleted, other.deleted);
    }

    @Override
    public String toString() {
      return "ConsultantRow[profile=redacted]";
    }
  }

  private record Intent(
      String account,
      Long tenant,
      String humanSubject,
      String humanIssuer,
      String humanClient,
      String digest,
      String original,
      String desired,
      String body,
      String receipt,
      String status,
      String applied) {
    @Override
    public String toString() {
      return "Intent[ownership=redacted]";
    }
  }

  /** Only the actual SQL/human issuer creates this live frame. DTOs and saved tuples cannot. */
  public final class CheckedAccount implements AutoCloseable {
    private final AdminRow actual, original;
    private final boolean self;
    private final Human human;
    private final UUID workflow;
    private final IdentityProfileUpdate profile;
    private final de.caritas.cob.userservice.api.admin.service.consultant.update
            .ConsultantUpdateService.ScopedEdit
        edit;
    private final String fullSql;
    private boolean applied, terminalOnly;
    private java.util.Map<String, StoredTerminal> terminals = java.util.Map.of();
    private ReviewedOrdinaryGroups.Transition role;
    private final String method = request.getMethod();
    private final String path = request.getRequestURI();
    private final Thread owner = Thread.currentThread();
    private final Instant deadline = clock.instant().plusSeconds(60);
    private boolean active = true;

    private CheckedAccount(
        AdminRow actual,
        AdminRow original,
        Human human,
        UUID workflow,
        IdentityProfileUpdate profile) {
      this(actual, original, human, workflow, profile, null, null, false);
    }

    private CheckedAccount(
        AdminRow actual,
        AdminRow original,
        Human human,
        UUID workflow,
        IdentityProfileUpdate profile,
        de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService
                .ScopedEdit
            edit,
        String fullSql,
        boolean self) {
      this.self = self;
      this.edit = edit;
      this.fullSql = fullSql;
      this.actual = actual;
      this.original = original;
      this.human = human;
      this.workflow = workflow;
      this.profile = profile;
    }

    public String accountId() {
      return actual.account;
    }

    public boolean selfService() {
      return self;
    }

    public boolean protectedSelf() {
      return self && Set.of("TENANT", "SUPER").contains(actual.type);
    }

    public String profileCommand() {
      if (role != null) return "ordinary-profile";
      return self && profile != null && !Objects.equals(original.email, profile.email())
          ? "email"
          : "profile";
    }

    public Long tenantId() {
      return actual.tenant;
    }

    public String humanSubject() {
      return human.subject;
    }

    public String humanIssuer() {
      return human.issuer;
    }

    public String humanClient() {
      return human.client;
    }

    public String rawHumanBearer() {
      requireCurrent();
      return human.rawBearer;
    }

    public UUID workflowId() {
      return workflow;
    }

    public String requestMethod() {
      return method;
    }

    public String requestPath() {
      return path;
    }

    public String originalSqlState() {
      return encode(original);
    }

    public IdentityProfileUpdate desiredProfile() {
      return profile;
    }

    public String reviewedGroup() {
      return role == null ? null : role.group();
    }

    public java.util.List<String> originalRoles() {
      return role == null ? java.util.List.of() : role.original();
    }

    public java.util.List<String> desiredRoles() {
      return role == null ? java.util.List.of() : role.desired();
    }

    public void requireCurrent() {
      if (!active
          || owner != Thread.currentThread()
          || !clock.instant().isBefore(deadline)
          || !clock.instant().isBefore(human.expires)
          || !Objects.equals(method, request.getMethod())
          || !Objects.equals(path, request.getRequestURI())) throw forbidden();
      requireOutsideSql();
      currentHumanMatching(human);
      if (!actual.equals(authorizedRow(actual.account, false, self))) throw conflict();
      if (edit != null
          && !applied
          && !terminalOnly
          && !Objects.equals(fullSql, consultantSnapshot(actual.account, false))) throw conflict();
    }

    @Override
    public void close() {
      active = false;
    }

    @Override
    public String toString() {
      return "CheckedAccount[authorization=redacted]";
    }
  }

  private static ConflictException conflict() {
    return new ConflictException("The account operation conflicts with current state");
  }

  private static ForbiddenException forbidden() {
    return new ForbiddenException("Managed account operation is not authorized");
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Checked admin maintenance is unavailable");
  }
}
