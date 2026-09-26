package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpiredInviteReservationSweepTest {

  @InjectMocks private ExpiredInviteReservationSweep sweep;

  @Mock private AccountInviteService accountInviteService;
  @Mock private ScheduledTaskClaimService taskClaimService;
  @Mock private TenantContextProvider tenantContextProvider;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private IdentityAuthentication identityAuthentication;

  private final Duration claimDuration = Duration.ofMinutes(5);

  @BeforeEach
  void setUp() {
    setField(sweep, "claimDuration", claimDuration);
  }

  @AfterEach
  void clearContexts() {
    TechnicalAccessTokenContext.clear();
    TenantContext.clear();
  }

  @Test
  void expireElapsedInvites_Should_SkipWork_When_AnotherReplicaHoldsTheClaim() {
    when(taskClaimService.tryClaimLease(ExpiredInviteReservationSweep.TASK_NAME, claimDuration))
        .thenReturn(Optional.empty());

    sweep.expireElapsedInvites();

    verifyNoInteractions(accountInviteService, identityAuthentication);
  }

  @Test
  void expireElapsedInvites_Should_RunTheSweepWithATechnicalToken_AndReleaseTheClaim() {
    var lease =
        new ScheduledTaskClaimService.ClaimLease(
            ExpiredInviteReservationSweep.TASK_NAME, LocalDateTime.of(2026, 9, 21, 18, 0));
    TechnicalUserConfig technicalUser = new TechnicalUserConfig();
    technicalUser.setUsername("technical");
    technicalUser.setPassword("secret");
    when(taskClaimService.tryClaimLease(ExpiredInviteReservationSweep.TASK_NAME, claimDuration))
        .thenReturn(Optional.of(lease));
    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    when(identityAuthentication.login("technical", "secret"))
        .thenReturn(new IdentityLogin("token", 60, 60, "refresh"));
    when(accountInviteService.expireElapsedInvites())
        .thenAnswer(
            invocation -> {
              assertThat(TechnicalAccessTokenContext.get()).contains("token");
              return 1;
            });

    sweep.expireElapsedInvites();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(taskClaimService).release(lease);
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }
}
