package de.caritas.cob.userservice.api.service.servicenotice;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import org.junit.jupiter.api.Test;

class ServiceNoticeMailSchedulerTest {
  private final ServiceNoticeMailWorker worker = mock(ServiceNoticeMailWorker.class);
  private final TenantContextProvider tenantContext = mock(TenantContextProvider.class);

  @Test
  void sendsNothingUnlessExplicitlySwitchedOn() {
    new ServiceNoticeMailScheduler(worker, tenantContext, false).deliverDueMail();

    verifyNoInteractions(worker);
  }

  @Test
  void whenSwitchedOnItDeliversInTheTechnicalTenantContext() {
    new ServiceNoticeMailScheduler(worker, tenantContext, true).deliverDueMail();

    verify(tenantContext).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(worker).dispatchDue();
  }
}
