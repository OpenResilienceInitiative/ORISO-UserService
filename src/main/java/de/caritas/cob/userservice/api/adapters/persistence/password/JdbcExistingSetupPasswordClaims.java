package de.caritas.cob.userservice.api.adapters.persistence.password;

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
            require("REJECTED".equals(saved.status()));
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
                            + " and claim_version=? and status='REJECTED'",
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
                          + "account_id,token_hash,authority_valid_until,request_digest,status,updated_at)"
                          + " values(?,?,?,?,?,?,?,'FROZEN',?)",
                      binding.inviteId(),
                      first.id().toString(),
                      first.version(),
                      binding.accountId(),
                      binding.tokenHash(),
                      Timestamp.valueOf(binding.expiresAt()),
                      first.requestDigest(),
                      now())
                  == 1);
          return first;
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
          if (rows.isEmpty() || !Set.of("FROZEN", "UNKNOWN").contains(rows.getFirst().status()))
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
          active(claim, "EMAIL_SENT", "IN_PROGRESS");
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
                + "request_digest,status from identity_setup_password_claim where invite_id=? for update",
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
        rs.getString("status"));
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

  private <T> T owned(Supplier<T> work) {
    require(
        TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.hasResource(jdbc.getDataSource())
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    try {
      return work.get();
    } catch (DataAccessException | IllegalArgumentException | ArithmeticException invalid) {
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
      String status) {
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
