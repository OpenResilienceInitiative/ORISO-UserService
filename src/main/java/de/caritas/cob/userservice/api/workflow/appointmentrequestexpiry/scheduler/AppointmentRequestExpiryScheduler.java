package de.caritas.cob.userservice.api.workflow.appointmentrequestexpiry.scheduler;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.service.sessionappointment.AppointmentRequestExpiry;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Declines unanswered appointment requests once their start has come (ORISO-Frontend#2287). The
 * update is idempotent, so two instances running it at once do no harm.
 */
@Component
@RequiredArgsConstructor
public class AppointmentRequestExpiryScheduler {

  private final AppointmentRequestExpiry expiry;
  private final TenantContextProvider tenantContextProvider;
  private final Clock clock;

  @Value("${appointment.request-expiry.enabled:true}")
  private Boolean enabled;

  @Scheduled(cron = "${appointment.request-expiry.cron:0 * * * * ?}")
  public void declineUnansweredRequests() {
    if (!isTrue(enabled)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    try {
      expiry.declineUnanswered(Instant.now(clock));
    } finally {
      TenantContext.clear();
    }
  }
}
