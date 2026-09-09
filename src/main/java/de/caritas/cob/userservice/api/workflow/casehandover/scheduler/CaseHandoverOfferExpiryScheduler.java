package de.caritas.cob.userservice.api.workflow.casehandover.scheduler;

import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes handover offers nobody answered.
 *
 * <p>Without this an offer stays PENDING_RECIPIENT_ACCEPT for good: the offering counsellor keeps
 * believing the case is on its way, the recipient keeps a stale entry in their inbox, and the case
 * itself is blocked against a second offer. Expiry is what makes "I offered it and nothing
 * happened" an observable state rather than silence.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverOfferExpiryScheduler {

  private static final String TASK_NAME = "case-handover-offer-expiry";

  private final @NonNull CaseHandoverService caseHandoverService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${case.handover.offer.expiry.claim.duration:PT10M}")
  private Duration claimDuration;

  @Scheduled(cron = "${case.handover.offer.expiry.cron:0 */15 * * * ?}")
  public void expireOffers() {
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    try {
      tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
      int expired = caseHandoverService.expireOffers();
      if (expired > 0) {
        log.info("Expired {} unanswered case handover offer(s)", expired);
      }
    } finally {
      TenantContext.clear();
    }
  }
}
