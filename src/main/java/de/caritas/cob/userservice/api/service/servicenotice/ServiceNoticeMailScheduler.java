package de.caritas.cob.userservice.api.service.servicenotice;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Off unless {@code service.notice.mail.enabled=true}. Rate control: at most {@code
 * service.notice.mail.batch-size} mails per run, one run per {@code
 * service.notice.mail.poll-delay-ms} (defaults 20 per 60 s).
 */
@Component
public class ServiceNoticeMailScheduler {
  private final ServiceNoticeMailWorker worker;
  private final TenantContextProvider tenantContextProvider;
  private final boolean enabled;

  public ServiceNoticeMailScheduler(
      ServiceNoticeMailWorker worker,
      TenantContextProvider tenantContextProvider,
      @Value("${service.notice.mail.enabled:false}") boolean enabled) {
    this.worker = worker;
    this.tenantContextProvider = tenantContextProvider;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${service.notice.mail.poll-delay-ms:60000}")
  public void deliverDueMail() {
    if (!enabled) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    try {
      worker.dispatchDue();
    } finally {
      TenantContext.clear();
    }
  }
}
