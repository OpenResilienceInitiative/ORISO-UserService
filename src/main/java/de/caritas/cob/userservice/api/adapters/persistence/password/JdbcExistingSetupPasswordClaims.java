package de.caritas.cob.userservice.api.adapters.persistence.password;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.port.out.ExistingSetupPasswordClaims;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Same-database setup claim journal. No provider, credential, token-store or crypto operations. */
public final class JdbcExistingSetupPasswordClaims implements ExistingSetupPasswordClaims {
  private static final Set<AccountInviteTargetRole> ROLES =
      Set.of(
          AccountInviteTargetRole.TENANT_ADMIN,
          AccountInviteTargetRole.AGENCY_ADMIN,
          AccountInviteTargetRole.COUNSELLOR);
  private static final String PENDING_ORIGINAL = "{\"state\":\"PENDING\",\"v\":1}";
  private static final Set<String> ORIGINAL_FIELDS =
      Set.of("v", "accountId", "kind", "tenantId", "username", "recipient", "createDate");
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcExistingSetupPasswordClaims(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.clock = Objects.requireNonNull(clock);
    Objects.requireNonNull(jdbc.getDataSource());
  }

  @Override
  public Claim claimWithinOwner(Binding binding, String completeKeyedRequestDigest) {
    return owned(
        () -> {
          requireBinding(binding);
          require(digest(completeKeyedRequestDigest));
          Invite invite = lockInvite(binding);
          List<Saved> rows = lockSaved(binding.inviteId());
          if ("ACCEPTED".equals(invite.status()) && "COMPLETED".equals(invite.phase())) {
            require(rows.size() == 1);
            Saved saved = rows.getFirst();
            require(
                "ACKNOWLEDGED".equals(saved.status())
                    && saved.bindingMatches(binding)
                    && saved.requestDigest().equals(completeKeyedRequestDigest));
            // Historical data only: requireCurrentWithinOwner still refuses this terminal row.
            return saved.claim(binding);
          }
          require(binding.expiresAt().isAfter(LocalDateTime.now(clock)));
          require("EMAIL_SENT".equals(invite.status()) && invite.hasVerifier());
          if (!rows.isEmpty()) {
            Saved saved = rows.getFirst();
            require(saved.bindingMatches(binding));
            if (Set.of("FROZEN", "UNKNOWN").contains(saved.status())) {
              require("IN_PROGRESS".equals(invite.phase()));
              require(saved.requestDigest().equals(completeKeyedRequestDigest));
              return saved.claim(binding);
            }
            require(Set.of("REJECTED", "POLICY_REJECTED").contains(saved.status()));
            if (!"REJECTED".equals(saved.status()) || !PENDING_ORIGINAL.equals(saved.originalSql()))
              boundOriginal(saved.originalSql(), binding);
            require(Set.of("PENDING", "FAILED").contains(invite.phase()));
            Claim next =
                new Claim(
                    UUID.randomUUID(),
                    Math.addExact(saved.version(), 1),
                    binding,
                    completeKeyedRequestDigest);
            claimInvite(binding.inviteId(), invite.phase());
            require(
                jdbc.update(
                        "update identity_setup_password_claim set claim_id=?,claim_version=?,"
                            + "request_digest=?,status='FROZEN',updated_at=? where invite_id=? and claim_id=?"
                            + " and claim_version=? and status in ('REJECTED','POLICY_REJECTED')",
                        next.id().toString(),
                        next.version(),
                        next.requestDigest(),
                        now(),
                        binding.inviteId(),
                        saved.id().toString(),
                        saved.version())
                    == 1);
            return next;
          }
          require(Set.of("PENDING", "FAILED").contains(invite.phase()));
          Claim first = new Claim(UUID.randomUUID(), 1, binding, completeKeyedRequestDigest);
          claimInvite(binding.inviteId(), invite.phase());
          require(
              jdbc.update(
                      "insert into identity_setup_password_claim(invite_id,claim_id,claim_version,"
                          + "account_id,token_hash,authority_valid_until,request_digest,status,updated_at,original_sql_json)"
                          + " values(?,?,?,?,?,?,?,'FROZEN',?,?)",
                      binding.inviteId(),
                      first.id().toString(),
                      first.version(),
                      binding.accountId(),
                      binding.tokenHash(),
                      Timestamp.valueOf(binding.expiresAt()),
                      first.requestDigest(),
                      now(),
                      PENDING_ORIGINAL)
                  == 1);
          return first;
        });
  }

  @Override
  public void requireOriginalSqlWithinOwner(Claim claim, OriginalSql actual) {
    owned(
        () -> {
          active(claim, "EMAIL_SENT", "IN_PROGRESS");
          require(claim.binding().expiresAt().isAfter(LocalDateTime.now(clock)));
          Saved saved = lockSaved(claim.binding().inviteId()).getFirst();
          String original = originalJson(actual, claim.binding());
          if (PENDING_ORIGINAL.equals(saved.originalSql())) {
            require(
                jdbc.update(
                        "update identity_setup_password_claim set original_sql_json=? where invite_id=? and claim_id=? and claim_version=? and request_digest=? and status in ('FROZEN','UNKNOWN') and original_sql_json=?",
                        original,
                        claim.binding().inviteId(),
                        claim.id().toString(),
                        claim.version(),
                        claim.requestDigest(),
                        PENDING_ORIGINAL)
                    == 1);
          } else {
            boundOriginal(saved.originalSql(), claim.binding());
            require(original.equals(saved.originalSql()));
          }
          return null;
        });
  }

  @Override
  public java.util.Optional<Claim> recoverWithinOwner(Binding binding, String requestDigest) {
    return owned(
        () -> {
          requireBinding(binding);
          require(digest(requestDigest));
          Invite invite = lockInvite(binding);
          var rows = lockSaved(binding.inviteId());
          if (!rows.isEmpty() && "POLICY_REJECTED".equals(rows.getFirst().status())) {
            var saved = rows.getFirst();
            require(saved.bindingMatches(binding));
            if (!saved.requestDigest().equals(requestDigest)) return java.util.Optional.empty();
            require(
                "EMAIL_SENT".equals(invite.status())
                    && "FAILED".equals(invite.phase())
                    && invite.hasVerifier());
            require(binding.expiresAt().plusDays(7).isAfter(LocalDateTime.now(clock)));
            return java.util.Optional.of(saved.claim(binding));
          }
          if (rows.isEmpty()
              || !Set.of("FROZEN", "UNKNOWN", "REFUSED").contains(rows.getFirst().status()))
            return java.util.Optional.empty();
          var saved = rows.getFirst();
          require(saved.bindingMatches(binding) && saved.requestDigest().equals(requestDigest));
          require(
              "EMAIL_SENT".equals(invite.status())
                  && "IN_PROGRESS".equals(invite.phase())
                  && invite.hasVerifier());
          require(binding.expiresAt().plusDays(7).isAfter(LocalDateTime.now(clock)));
          return java.util.Optional.of(saved.claim(binding));
        });
  }

  @Override
  public void requireRecoveryWithinOwner(Claim claim) {
    owned(
        () -> {
          var exact = recoverWithinOwner(claim.binding(), claim.requestDigest());
          require(exact.isPresent() && exact.get().equals(claim));
          require(claim.binding().expiresAt().plusDays(7).isAfter(LocalDateTime.now(clock)));
          return null;
        });
  }

  @Override
  public void requireCurrentWithinOwner(Claim claim) {
    owned(
        () -> {
          active(claim, "EMAIL_SENT", "IN_PROGRESS");
          require(claim.binding().expiresAt().isAfter(LocalDateTime.now(clock)));
          return null;
        });
  }

  @Override
  public void markUnknownWithinOwner(Claim claim) {
    owned(
        () -> {
          active(claim, "EMAIL_SENT", "IN_PROGRESS");
          changePhase(claim, "UNKNOWN");
          return null;
        });
  }

  @Override
  public void rejectWithinOwner(Claim claim) {
    owned(
        () -> {
          active(claim, "EMAIL_SENT", "FAILED");
          changePhase(claim, "REJECTED");
          return null;
        });
  }

  @Override
  public void rejectPolicyWithinOwner(Claim claim) {
    owned(
        () -> {
          var exact = recoverWithinOwner(claim.binding(), claim.requestDigest());
          require(exact.isPresent() && exact.get().equals(claim));
          var saved = lockSaved(claim.binding().inviteId()).getFirst();
          if ("POLICY_REJECTED".equals(saved.status())) return null;
          require(
              jdbc.update(
                      "update account_invite set provisioning_status='FAILED',provisioning_failure_reason='SETUP_PASSWORD_REJECTED',update_date=? where id=? and purpose='EXISTING_ACCOUNT_SETUP' and status='EMAIL_SENT' and provisioning_status='IN_PROGRESS'",
                      now(),
                      claim.binding().inviteId())
                  == 1);
          changePhase(claim, "POLICY_REJECTED");
          return null;
        });
  }

  @Override
  public void refuseWithinOwner(Claim claim) {
    owned(
        () -> {
          var exact = recoverWithinOwner(claim.binding(), claim.requestDigest());
          require(exact.isPresent() && exact.get().equals(claim));
          var saved = lockSaved(claim.binding().inviteId()).getFirst();
          if ("REFUSED".equals(saved.status())) return null;
          require(Set.of("FROZEN", "UNKNOWN").contains(saved.status()));
          changePhase(claim, "REFUSED");
          return null;
        });
  }

  @Override
  public void acknowledgeWithinOwner(Claim claim) {
    owned(
        () -> {
          active(claim, "ACCEPTED", "COMPLETED");
          changePhase(claim, "ACKNOWLEDGED");
          return null;
        });
  }

  private void active(Claim claim, String status, String phase) {
    require(
        claim != null
            && claim.id() != null
            && claim.version() > 0
            && digest(claim.requestDigest()));
    requireBinding(claim.binding());
    Invite invite = lockInvite(claim.binding());
    require(status.equals(invite.status()) && phase.equals(invite.phase()));
    if ("IN_PROGRESS".equals(phase)) require(invite.hasVerifier());
    List<Saved> rows = lockSaved(claim.binding().inviteId());
    require(rows.size() == 1);
    Saved saved = rows.getFirst();
    require(saved.bindingMatches(claim.binding()) && saved.claim(claim.binding()).equals(claim));
    require(Set.of("FROZEN", "UNKNOWN").contains(saved.status()));
  }

  private Invite lockInvite(Binding binding) {
    List<Invite> rows =
        jdbc.query(
            "select purpose,status,provisioning_status,provisioned_user_id,"
                + "token_hash,expires_at,target_role,tenant_id,setup_bound_username,recipient_email,"
                + "initial_password_verifier from account_invite where id=? for update",
            (rs, n) -> {
              require("EXISTING_ACCOUNT_SETUP".equals(rs.getString("purpose")));
              require(binding.accountId().equals(rs.getString("provisioned_user_id")));
              require(binding.tokenHash().equals(rs.getString("token_hash")));
              require(Timestamp.valueOf(binding.expiresAt()).equals(rs.getTimestamp("expires_at")));
              require(binding.role().name().equals(rs.getString("target_role")));
              long tenant = rs.getLong("tenant_id");
              Long actualTenant = rs.wasNull() ? null : tenant;
              require(Objects.equals(binding.tenantId(), actualTenant));
              require(binding.username().equals(rs.getString("setup_bound_username")));
              require(binding.email().equals(rs.getString("recipient_email")));
              String status = rs.getString("status");
              String phase = rs.getString("provisioning_status");
              require(status != null && phase != null);
              return new Invite(
                  status, phase, text(rs.getString("initial_password_verifier"), 255));
            },
            binding.inviteId());
    require(rows.size() == 1);
    return rows.getFirst();
  }

  private List<Saved> lockSaved(long inviteId) {
    List<Saved> rows =
        jdbc.query(
            "select claim_id,claim_version,account_id,token_hash,authority_valid_until,"
                + "request_digest,status,original_sql_json from identity_setup_password_claim where invite_id=? for update",
            (rs, n) -> saved(rs),
            inviteId);
    require(rows.size() <= 1);
    return rows;
  }

  private static Saved saved(ResultSet rs) throws SQLException {
    return new Saved(
        UUID.fromString(rs.getString("claim_id")),
        rs.getLong("claim_version"),
        rs.getString("account_id"),
        rs.getString("token_hash"),
        rs.getTimestamp("authority_valid_until").toLocalDateTime(),
        rs.getString("request_digest"),
        rs.getString("status"),
        rs.getString("original_sql_json"));
  }

  private void claimInvite(long inviteId, String phase) {
    require(
        jdbc.update(
                "update account_invite set provisioning_status='IN_PROGRESS',"
                    + "provisioning_failure_reason=null,update_date=? where id=? and purpose='EXISTING_ACCOUNT_SETUP'"
                    + " and status='EMAIL_SENT' and provisioning_status=?",
                now(),
                inviteId,
                phase)
            == 1);
  }

  private void changePhase(Claim claim, String phase) {
    require(
        jdbc.update(
                "update identity_setup_password_claim set status=?,updated_at=?"
                    + " where invite_id=? and claim_id=? and claim_version=? and request_digest=?"
                    + " and status in ('FROZEN','UNKNOWN')",
                phase,
                now(),
                claim.binding().inviteId(),
                claim.id().toString(),
                claim.version(),
                claim.requestDigest())
            == 1);
  }

  private static String originalJson(OriginalSql original, Binding binding) {
    require(original != null && binding != null);
    String kind =
        switch (binding.role()) {
          case COUNSELLOR -> "CONSULTANT";
          case AGENCY_ADMIN -> "AGENCY_ADMIN";
          case TENANT_ADMIN -> "TENANT_ADMIN";
          default -> throw unavailable();
        };
    require(
        binding.accountId().equals(original.accountId())
            && UUID.fromString(original.accountId()).toString().equals(original.accountId())
            && kind.equals(original.kind())
            && Objects.equals(binding.tenantId(), original.tenantId())
            && binding.username().equals(original.username())
            && binding.email().equals(original.recipient())
            && text(original.createDate(), 64)
            && LocalDateTime.parse(original.createDate()).toString().equals(original.createDate()));
    var fields = new TreeMap<String, Object>();
    fields.put("v", 1);
    fields.put("accountId", original.accountId());
    fields.put("kind", original.kind());
    fields.put("tenantId", original.tenantId());
    fields.put("username", original.username());
    fields.put("recipient", original.recipient());
    fields.put("createDate", original.createDate());
    try {
      return JSON.writeValueAsString(fields);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw unavailable();
    }
  }

  private static void boundOriginal(String saved, Binding binding) {
    require(text(saved, 4096));
    try {
      JsonNode value = JSON.readTree(saved);
      require(value != null && value.isObject() && value.size() == ORIGINAL_FIELDS.size());
      var names = new java.util.HashSet<String>();
      value.fieldNames().forEachRemaining(names::add);
      require(
          names.equals(ORIGINAL_FIELDS)
              && value.path("v").isIntegralNumber()
              && value.path("v").canConvertToInt()
              && value.path("v").intValue() == 1);
      for (String field : Set.of("accountId", "kind", "username", "recipient", "createDate"))
        require(value.path(field).isTextual() && text(value.path(field).textValue(), 512));
      var tenant = value.path("tenantId");
      require(tenant.isNull() || (tenant.isIntegralNumber() && tenant.canConvertToLong()));
      var original =
          new OriginalSql(
              value.path("accountId").textValue(),
              value.path("kind").textValue(),
              tenant.isNull() ? null : tenant.longValue(),
              value.path("username").textValue(),
              value.path("recipient").textValue(),
              value.path("createDate").textValue());
      require(saved.equals(originalJson(original, binding)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw unavailable();
    }
  }

  private <T> T owned(Supplier<T> work) {
    require(
        TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.hasResource(jdbc.getDataSource())
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    try {
      return work.get();
    } catch (DataAccessException
        | IllegalArgumentException
        | ArithmeticException
        | java.time.DateTimeException invalid) {
      throw unavailable();
    }
  }

  private static void requireBinding(Binding b) {
    require(
        b != null
            && b.inviteId() > 0
            && text(b.accountId(), 36)
            && digest(b.tokenHash())
            && b.expiresAt() != null
            && b.role() != null
            && ROLES.contains(b.role())
            && text(b.username(), 512)
            && text(b.email(), 512));
  }

  private static boolean text(String value, int max) {
    return value != null && !value.isBlank() && value.length() <= max;
  }

  private static boolean digest(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private Timestamp now() {
    return Timestamp.valueOf(LocalDateTime.now(clock));
  }

  private static void require(boolean condition) {
    if (!condition) throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Existing setup password claim unavailable");
  }

  private record Invite(String status, String phase, boolean hasVerifier) {}

  private record Saved(
      UUID id,
      long version,
      String accountId,
      String tokenHash,
      LocalDateTime expiry,
      String requestDigest,
      String status,
      String originalSql) {
    boolean bindingMatches(Binding binding) {
      return version > 0
          && accountId.equals(binding.accountId())
          && tokenHash.equals(binding.tokenHash())
          && expiry.equals(binding.expiresAt());
    }

    Claim claim(Binding binding) {
      return new Claim(id, version, binding, requestDigest);
    }
  }
}
