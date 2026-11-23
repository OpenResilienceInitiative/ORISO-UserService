package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!testing")
@RequiredArgsConstructor
public class InquiryAcceptanceNoticeScheduler {
  private final InquiryAcceptanceNoticeDelivery delivery;

  @Scheduled(fixedDelayString = "${inquiry.acceptance.notice.retry-delay-ms:60000}")
  public void retryPending() {
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      delivery.dispatchPending();
    } finally {
      TenantContext.clear();
    }
  }
}
