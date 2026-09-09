package de.caritas.cob.userservice.api.workflow.casehandover.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CaseHandoverOfferExpirySchedulerTest {
  private final CaseHandoverService service = mock(CaseHandoverService.class);
  private final ScheduledTaskClaimService claims = mock(ScheduledTaskClaimService.class);
  private final TenantContextProvider contexts = new TenantContextProvider();
  private final CaseHandoverOfferExpiryScheduler scheduler =
      new CaseHandoverOfferExpiryScheduler(service, contexts, claims);

  @BeforeEach
  void setUp() {
    TenantContext.clear();
    ReflectionTestUtils.setField(contexts, "multiTenancyEnabled", true);
    ReflectionTestUtils.setField(scheduler, "claimDuration", Duration.ofMinutes(10));
  }

  @AfterEach
  void clearContext() {
    TenantContext.clear();
  }

  @Test
  void leaseLoserDoesNoWorkAndDoesNotChangeContext() {
    TenantContext.setCurrentTenant(77L);
    when(claims.tryClaim("case-handover-offer-expiry", Duration.ofMinutes(10))).thenReturn(false);
    scheduler.expireOffers();
    verifyNoInteractions(service);
    assertEquals(77L, TenantContext.getCurrentTenant());
  }

  @Test
  void leaseWinnerUsesTechnicalContextAndClearsItAfterSuccess() {
    when(claims.tryClaim("case-handover-offer-expiry", Duration.ofMinutes(10))).thenReturn(true);
    when(service.expireOffers())
        .thenAnswer(
            call -> {
              assertEquals(TenantContext.TECHNICAL_TENANT_ID, TenantContext.getCurrentTenant());
              return 1;
            });
    scheduler.expireOffers();
    assertNull(TenantContext.getCurrentTenant());
    verify(service).expireOffers();
  }

  @Test
  void leaseWinnerClearsTechnicalContextAfterFailure() {
    when(claims.tryClaim("case-handover-offer-expiry", Duration.ofMinutes(10))).thenReturn(true);
    when(service.expireOffers()).thenThrow(new IllegalStateException("Synthetic failure"));
    assertThrows(IllegalStateException.class, scheduler::expireOffers);
    assertNull(TenantContext.getCurrentTenant());
  }
}
