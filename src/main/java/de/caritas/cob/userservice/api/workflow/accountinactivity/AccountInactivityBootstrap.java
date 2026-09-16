package de.caritas.cob.userservice.api.workflow.accountinactivity;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Inventories dormant as well as active Keycloak identities against one immutable rollout cutoff.
 */
@Component
public class AccountInactivityBootstrap implements SmartInitializingSingleton {
  public record Report(
      Instant cutoff,
      Instant lastScan,
      boolean complete,
      int enrolled,
      int missingNew,
      int failed) {}

  public record Issue(String identityId, String reason, Instant observedAt) {}

  private final JdbcTemplate jdbc;
  private final KeycloakClient keycloak;
  private final AccountInactivityService lifecycle;
  private final Clock clock;
  private final int pageSize;
  private final org.springframework.transaction.support.TransactionTemplate inventoryTransaction;

  public AccountInactivityBootstrap(
      JdbcTemplate jdbc,
      KeycloakClient keycloak,
      AccountInactivityService lifecycle,
      Clock clock,
      org.springframework.transaction.PlatformTransactionManager transactionManager,
      @Value("${account.inactivity.bootstrap.page-size:100}") int pageSize) {
    if (pageSize < 1 || pageSize > 1000)
      throw new IllegalArgumentException("Inventory page size must be 1..1000");
    this.jdbc = jdbc;
    this.keycloak = keycloak;
    this.lifecycle = lifecycle;
    this.clock = clock;
    this.pageSize = pageSize;
    this.inventoryTransaction =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    this.inventoryTransaction.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void afterSingletonsInstantiated() {
    try {
      scan();
    } catch (RuntimeException failure) {
      org.apache.commons.logging.LogFactory.getLog(getClass())
          .warn("Account inactivity inventory unavailable: " + failure.getClass().getSimpleName());
    }
  }

  @Scheduled(cron = "${account.inactivity.bootstrap.cron:0 30 1 * * *}", zone = "UTC")
  public void scan() {
    inventoryTransaction.executeWithoutResult(
        ignored -> {
          // The database owns this lock until commit/rollback, even if remote enumeration is slow.
          // Enrollment, issue cleanup and the final report share this transaction and connection.
          jdbc.queryForObject(
              "SELECT id FROM account_inactivity_rollout WHERE id=1 FOR UPDATE", Integer.class);
          scanLocked();
        });
  }

  private void scanLocked() {
    Instant cutoff = report().cutoff();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    int enrolled = 0, missingNew = 0, failed = 0;
    boolean finished = false;
    try {
      var clients = keycloak.getRealmResource().clients().findAll();
      for (int first = 0; ; first += pageSize) {
        var page = keycloak.getUsersResource().list(first, pageSize);
        if (page.isEmpty()) {
          finished = true;
          break;
        }
        for (var listed : page) {
          String id = listed.getId();
          if (id == null) {
            failed++;
            continue;
          }
          try {
            if (lifecycle.snapshot(id).isPresent()) {
              jdbc.update("DELETE FROM account_inactivity_bootstrap_issue WHERE identity_id=?", id);
              continue;
            }
            var user = keycloak.getUsersResource().get(id).toRepresentation();
            if (user.getServiceAccountClientId() != null || isPureTechnical(user, clients)) {
              jdbc.update("DELETE FROM account_inactivity_bootstrap_issue WHERE identity_id=?", id);
              continue;
            }
            if (user.getCreatedTimestamp() == null) {
              failed++;
              issue(id, "CREATION_TIME_UNKNOWN", now);
              continue;
            }
            if (Instant.ofEpochMilli(user.getCreatedTimestamp()).isAfter(cutoff)) {
              missingNew++;
              issue(id, "POST_ROLLOUT_SNAPSHOT_MISSING", now);
              continue;
            }
            lifecycle.assignAtCreation(id, tenant(user), 24, 0, cutoff);
            enrolled++;
            jdbc.update("DELETE FROM account_inactivity_bootstrap_issue WHERE identity_id=?", id);
          } catch (RuntimeException failure) {
            failed++;
            issue(id, "IDENTITY_LOOKUP_FAILED", now);
          }
        }
      }
    } catch (RuntimeException failure) {
      org.apache.commons.logging.LogFactory.getLog(getClass())
          .warn("Account inactivity inventory aborted: " + failure.getClass().getSimpleName());
      failed++;
    }
    if (finished)
      jdbc.update("DELETE FROM account_inactivity_bootstrap_issue WHERE observed_at<?", utc(now));
    jdbc.update(
        "UPDATE account_inactivity_rollout SET"
            + " last_scan=?,inventory_complete=?,enrolled=?,missing_new=?,failed=? WHERE id=1",
        utc(now),
        finished && missingNew == 0 && failed == 0,
        enrolled,
        missingNew,
        failed);
    org.apache.commons.logging.LogFactory.getLog(getClass())
        .info(
            "Account inactivity inventory counts: enrolled="
                + enrolled
                + ", missingNew="
                + missingNew
                + ", failed="
                + failed
                + ", complete="
                + (finished && missingNew == 0 && failed == 0));
  }

  public Report report() {
    return jdbc.queryForObject(
        "SELECT * FROM account_inactivity_rollout WHERE id=1",
        (r, n) ->
            new Report(
                r.getObject("rollout_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                r.getObject("last_scan", LocalDateTime.class) == null
                    ? null
                    : r.getObject("last_scan", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                r.getBoolean("inventory_complete"),
                r.getInt("enrolled"),
                r.getInt("missing_new"),
                r.getInt("failed")));
  }

  public List<Issue> issues(String after, int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Page size must be 1..1000");
    return jdbc.query(
        "SELECT * FROM account_inactivity_bootstrap_issue WHERE identity_id>? ORDER BY identity_id"
            + " LIMIT ?",
        (r, n) ->
            new Issue(
                r.getString("identity_id"),
                r.getString("reason"),
                r.getObject("observed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
        after == null ? "" : after,
        limit);
  }

  private boolean isPureTechnical(UserRepresentation user, List<ClientRepresentation> clients) {
    var resource = keycloak.getUsersResource().get(user.getId());
    var roles = new HashSet<String>();
    resource.roles().realmLevel().listEffective().forEach(role -> roles.add(role.getName()));
    for (var client : clients)
      resource
          .roles()
          .clientLevel(client.getId())
          .listEffective()
          .forEach(role -> roles.add(role.getName()));
    return AccountInactivityIdentityRoles.isPureTechnical(roles);
  }

  private Long tenant(UserRepresentation user) {
    if (user.getAttributes() == null) return null;
    var values = user.getAttributes().get("tenantId");
    if (values == null || values.isEmpty()) return null;
    try {
      return Long.valueOf(values.getFirst());
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  private void issue(String id, String reason, Instant now) {
    if (jdbc.update(
            "UPDATE account_inactivity_bootstrap_issue SET reason=?,observed_at=? WHERE"
                + " identity_id=?",
            reason,
            utc(now),
            id)
        == 0) {
      try {
        jdbc.update(
            "INSERT INTO account_inactivity_bootstrap_issue(identity_id,reason,observed_at)"
                + " VALUES(?,?,?)",
            id,
            reason,
            utc(now));
      } catch (DuplicateKeyException concurrentInventory) {
        jdbc.update(
            "UPDATE account_inactivity_bootstrap_issue SET reason=?,observed_at=? WHERE"
                + " identity_id=?",
            reason,
            utc(now),
            id);
      }
    }
  }

  private LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
