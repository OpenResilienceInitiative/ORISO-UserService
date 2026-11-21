package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.ClaimLease;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!testing")
@ConditionalOnProperty(
    prefix = "tenant.dpa.admin-notice-outbox",
    name = {"read-enabled", "dispatch-enabled"},
    havingValue = "true")
@Slf4j
class DpaAdminNoticeOutboxScheduler {

  static final String TASK_NAME = "dpa-signed-notice-exact-outbox";
  static final int PAGE_SIZE = 8;
  private static final Duration LEASE_DURATION = Duration.ofMinutes(3);
  private static final long PAGE_BUDGET_NANOS = Duration.ofSeconds(30).toNanos();
  private static final String READ_CURSOR =
      "select after_id from dpa_admin_notice_outbox_cursor where id = 1";
  private static final String WRITE_CURSOR =
      "update dpa_admin_notice_outbox_cursor set after_id = ? where id = 1";

  private final DpaSignedNoticeService notices;
  private final TenantDpaSignatureReadClient client;
  private final ScheduledTaskClaimService claims;
  private final JdbcTemplate jdbc;
  private final AtomicBoolean queued = new AtomicBoolean();

  DpaAdminNoticeOutboxScheduler(
      DpaSignedNoticeService notices,
      TenantDpaSignatureReadClient client,
      ScheduledTaskClaimService claims,
      JdbcTemplate jdbc) {
    this.notices = notices;
    this.client = client;
    this.claims = claims;
    this.jdbc = jdbc;
  }

  @Scheduled(fixedDelayString = "${tenant.dpa.admin-notice-outbox.dispatch-delay-ms:60000}")
  public void retryPending() {
    if (!client.isOutboxEnabled() || !queued.compareAndSet(false, true)) {
      return;
    }
    try {
      notices.submitAdminRecovery(this::run);
    } catch (RuntimeException failure) {
      queued.set(false);
      log.warn(
          "Could not queue exact DPA administrator notice recovery ({})",
          failure.getClass().getSimpleName());
    }
  }

  private void run() {
    try {
      TenantContext.runIn(
          TenantContext.TECHNICAL_TENANT_ID,
          () -> claims.tryClaimLease(TASK_NAME, LEASE_DURATION).ifPresent(this::runClaimed));
    } catch (RuntimeException failure) {
      log.warn(
          "Exact DPA administrator notice recovery deferred ({})",
          failure.getClass().getSimpleName());
    } finally {
      queued.set(false);
    }
  }

  private void runClaimed(ClaimLease lease) {
    try {
      var consumer =
          new DpaAdminNoticeOutboxConsumer(
              notices,
              client::readExact,
              event -> {
                if (!stillHeld(lease)) {
                  throw new IllegalStateException("DPA notice lease ended before acknowledgement");
                }
                // Receipt ownership has already committed. The lease can change during HTTP.
                // The exact, idempotent provider acknowledgement remains valid on that proof.
                client.acknowledge(event);
              });
      try {
        pullOnePage(lease, consumer);
      } catch (RuntimeException failure) {
        log.warn(
            "Exact DPA administrator notice pull deferred ({})",
            failure.getClass().getSimpleName());
      }
      if (stillHeld(lease)) {
        consumer.recover();
      }
    } finally {
      claims.release(lease);
    }
  }

  private void pullOnePage(ClaimLease lease, DpaAdminNoticeOutboxConsumer consumer) {
    if (!stillHeld(lease)) {
      return;
    }
    long started = System.nanoTime();
    long afterId = jdbc.queryForObject(READ_CURSOR, Long.class);
    var page = client.readPending(afterId, PAGE_SIZE);
    for (var event : page.events()) {
      if (System.nanoTime() - started >= PAGE_BUDGET_NANOS || !stillHeld(lease)) {
        return;
      }
      try {
        consumer.accept(event);
      } catch (RuntimeException failure) {
        // The producer retains an unacknowledged event. Revisit it on the next full sweep and
        // resume the unfinished page after the row we actually attempted.
        log.warn(
            "Exact DPA administrator notice event {} deferred ({})",
            event.id(),
            failure.getClass().getSimpleName());
        writeCursor(lease, event.id());
        return;
      }
      if (!writeCursor(lease, event.id())) {
        return;
      }
      afterId = event.id();
    }
    // The continuation may advance past current-occupant filtering even on an empty page.
    long nextAfterId = page.nextAfterId() == null ? 0L : page.nextAfterId();
    if (nextAfterId != afterId) {
      writeCursor(lease, nextAfterId);
    }
  }

  private boolean stillHeld(ClaimLease lease) {
    return claims.runIfHeld(lease, () -> {});
  }

  private boolean writeCursor(ClaimLease lease, long afterId) {
    return claims.runIfHeld(
        lease,
        () -> {
          if (jdbc.update(WRITE_CURSOR, afterId) != 1) {
            throw new IllegalStateException("DPA notice outbox checkpoint is missing");
          }
        });
  }
}
