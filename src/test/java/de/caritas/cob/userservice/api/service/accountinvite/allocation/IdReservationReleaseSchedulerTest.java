package de.caritas.cob.userservice.api.service.accountinvite.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdReservationReleaseSchedulerTest {

  @InjectMocks private IdReservationReleaseScheduler scheduler;

  @Mock private IdReservationReleaseProcessor processor;
  @Mock private ScheduledTaskClaimService taskClaimService;
  @Mock private TenantContextProvider tenantContextProvider;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private IdentityAuthentication identityAuthentication;

  private final Duration claimDuration = Duration.ofMinutes(5);

  @BeforeEach
  void setUp() {
    setField(scheduler, "claimDuration", claimDuration);
  }

  @AfterEach
  void clearContexts() {
    TechnicalAccessTokenContext.clear();
    TenantContext.clear();
  }

  @Test
  void retryPendingReleases_ShouldSkipWork_WhenClaimIsLost() {
    when(taskClaimService.tryClaim(IdReservationReleaseScheduler.TASK_NAME, claimDuration))
        .thenReturn(false);

    scheduler.retryPendingReleases();

    verifyNoInteractions(
        processor, tenantContextProvider, identityClientConfig, identityAuthentication);
  }

  @Test
  void retryPendingReleases_ShouldAuthenticateAndContinueAfterOneTaskFails() {
    TechnicalUserConfig technicalUser = new TechnicalUserConfig();
    technicalUser.setUsername("technical");
    technicalUser.setPassword("secret");
    when(taskClaimService.tryClaim(IdReservationReleaseScheduler.TASK_NAME, claimDuration))
        .thenReturn(true);
    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    when(identityAuthentication.login("technical", "secret"))
        .thenReturn(new IdentityLogin("token", 60, 60, "refresh"));
    when(processor.pendingTaskIds()).thenReturn(List.of(1L, 2L));
    doThrow(new IllegalStateException("database unavailable")).when(processor).process(1L);

    scheduler.retryPendingReleases();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(processor).process(1L);
    verify(processor).process(2L);
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
    assertThat(TenantContext.contextIsSet()).isFalse();
  }
}
