package de.caritas.cob.userservice.api.workflow.groupchatreminder.scheduler;

import de.caritas.cob.userservice.api.service.notification.GroupAppointmentMailWorker;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in until the role-safe appointment link and its dependent PRs are deployed. */
@Component
@RequiredArgsConstructor
public class GroupAppointmentMailScheduler {
  private final GroupAppointmentMailWorker worker;
  private final TenantContextProvider tenantContextProvider;

  @Value("${group.appointment.mail.enabled:false}")
  private boolean enabled;

  @Scheduled(fixedDelayString = "${group.appointment.mail.poll-delay-ms:60000}")
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
