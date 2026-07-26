package de.caritas.cob.userservice.api.conversation.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.assignsession.AssignEnquiryFacade;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AcceptAnonymousEnquiryFacadeTest {

  @InjectMocks private AcceptAnonymousEnquiryFacade acceptAnonymousEnquiryFacade;

  @Mock private AssignEnquiryFacade assignEnquiryFacade;

  @Mock private LiveEventNotificationService liveEventNotificationService;

  @Mock private SessionService sessionService;

  @Mock private UserAccountService userAccountService;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void
      acceptAnonymousEnquiry_Should_loadSessionCrossTenant_And_restoreCallerTenant_When_multiTenant() {
    TenantContext.setCurrentTenant(5L);
    Session session = new EasyRandom().nextObject(Session.class);
    var tenantDuringLoad = new AtomicReference<Long>();
    when(this.sessionService.getSessionForUpdate(session.getId()))
        .thenAnswer(
            invocation -> {
              tenantDuringLoad.set(TenantContext.getCurrentTenant());
              return Optional.of(session);
            });

    this.acceptAnonymousEnquiryFacade.acceptAnonymousEnquiry(session.getId());

    // The cross-tenant session is loaded with the tenant filter disabled (technical tenant)...
    assertEquals(TenantContext.TECHNICAL_TENANT_ID, tenantDuringLoad.get());
    // ...and the caller's tenant is restored afterwards so nothing else in the request leaks.
    assertEquals(5L, TenantContext.getCurrentTenant());
  }

  @Test
  void acceptAnonymousEnquiry_Should_useServicesCorrectly_When_sessionExists() {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.sessionService.getSessionForUpdate(session.getId())).thenReturn(Optional.of(session));

    this.acceptAnonymousEnquiryFacade.acceptAnonymousEnquiry(session.getId());

    verify(this.userAccountService, times(1)).retrieveValidatedConsultant();
    verify(this.assignEnquiryFacade, times(1)).assignAnonymousEnquiry(eq(session), any());
    verify(this.liveEventNotificationService, times(1))
        .sendAcceptAnonymousEnquiryEventToUser(session.getUser().getUserId());
  }

  @Test
  void acceptAnonymousEnquiry_Should_throwNotFoundException_When_sessionDoesNotExist() {
    assertThrows(
        NotFoundException.class,
        () -> {
          this.acceptAnonymousEnquiryFacade.acceptAnonymousEnquiry(1L);
        });
  }
}
