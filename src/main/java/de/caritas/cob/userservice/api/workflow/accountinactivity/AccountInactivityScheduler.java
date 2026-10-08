package de.caritas.cob.userservice.api.workflow.accountinactivity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Global scan: deliberately does not inherit a request's tenant filter. */
@Component
@ConditionalOnProperty(prefix = "account.inactivity", name = "enabled", havingValue = "true")
public class AccountInactivityScheduler {
  private final AccountInactivityService lifecycle;
  private final boolean dryRun;

  public AccountInactivityScheduler(
      AccountInactivityService lifecycle,
      @Value("${account.inactivity.dry-run:true}") boolean dryRun) {
    this.lifecycle = lifecycle;
    this.dryRun = dryRun;
  }

  @Scheduled(cron = "${account.inactivity.cron:0 0 2 * * *}", zone = "UTC")
  public void scan() {
    if (!dryRun) {
      lifecycle.scan(false);
      return;
    }
    var counts =
        new java.util.EnumMap<AccountInactivityService.PlannedAction, Long>(
            AccountInactivityService.PlannedAction.class);
    String cursor = "";
    while (true) {
      var page = lifecycle.candidateReport(cursor, 200);
      if (page.isEmpty()) break;
      for (var candidate : page) counts.merge(candidate.plannedAction(), 1L, Long::sum);
      cursor = page.getLast().snapshot().identityId();
    }
    org.apache.commons.logging.LogFactory.getLog(AccountInactivityScheduler.class)
        .info("Account inactivity dry-run candidate counts: " + counts);
  }
}
