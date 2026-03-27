package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.scheduler;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service.InactiveAccountNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InactiveAccountNotificationSchedulerTest {

  @InjectMocks private InactiveAccountNotificationScheduler scheduler;

  @Mock private InactiveAccountNotificationService service;

  @Mock private TenantContextProvider tenantContextProvider;

  @Test
  void notifyInactiveAccounts_shouldRunWhenEnabled() {
    setField(scheduler, "inactiveAccountNotificationEnabled", true);
    scheduler.notifyInactiveAccounts();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(service).scanAndNotifyInactiveAccounts();
  }

  @Test
  void notifyInactiveAccounts_shouldNotRunWhenDisabled() {
    setField(scheduler, "inactiveAccountNotificationEnabled", false);
    scheduler.notifyInactiveAccounts();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(service, never()).scanAndNotifyInactiveAccounts();
  }
}
