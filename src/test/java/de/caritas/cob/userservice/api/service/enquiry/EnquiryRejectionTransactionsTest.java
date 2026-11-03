package de.caritas.cob.userservice.api.service.enquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.EnquiryRejection;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EnquiryRejectionTransactionsTest {
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final EnquiryRejectionRepository rejections = mock(EnquiryRejectionRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final ConsultantAgencyRepository agencies = mock(ConsultantAgencyRepository.class);
  private final TeamDiscussionRepository discussions = mock(TeamDiscussionRepository.class);
  private final AccountInactivityService lifecycle = mock(AccountInactivityService.class);
  private final AuthenticatedUser caller = mock(AuthenticatedUser.class);
  private final TenantService tenants = mock(TenantService.class);
  private EnquiryRejectionTransactions transactions;

  @BeforeEach
  void ownSubmittedEnquiryOfTheSingleTenant() {
    TenantContext.clear();
    transactions =
        new EnquiryRejectionTransactions(
            sessions,
            rejections,
            consultants,
            agencies,
            mock(UserRepository.class),
            discussions,
            mock(EventNotificationRepository.class),
            lifecycle,
            caller,
            mock(MatrixFeedUpdateSignalService.class),
            Clock.systemUTC(),
            tenants);
    var seeker = new User();
    seeker.setUserId("seeker");
    var session = new Session();
    session.setId(5L);
    session.setTenantId(1L);
    session.setAgencyId(70L);
    session.setUser(seeker);
    session.setStatus(Session.SessionStatus.NEW);
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setEnquiryMessageDate(LocalDateTime.now());
    var actor = new Consultant();
    actor.setId("actor");
    actor.setTenantId(1L);
    when(caller.isConsultant()).thenReturn(true);
    when(caller.getUserId()).thenReturn("actor");
    when(caller.getTenantId()).thenReturn(null);
    when(sessions.findByIdForUpdate(5L)).thenReturn(Optional.of(session));
    when(consultants.findByIdAndDeleteDateIsNull("actor")).thenReturn(Optional.of(actor));
    when(agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull("actor", 70L))
        .thenReturn(true);
    when(rejections.findForUpdate(5L)).thenReturn(Optional.empty());
    when(discussions.findBySessionId(5L)).thenReturn(Optional.empty());
    when(lifecycle.snapshot("actor")).thenReturn(Optional.empty());
    when(rejections.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void singleTenantTokenWithoutTenantClaimUsesTheConfiguredTenant() {
    ReflectionTestUtils.setField(transactions, "multitenancyEnabled", false);
    var tenant = new RestrictedTenantDTO();
    tenant.setId(1L);
    when(tenants.getSingleTenancyTenantDataFresh()).thenReturn(tenant);

    var attempt = transactions.begin(5L);

    assertThat(attempt.decision().getTenantId()).isEqualTo(1L);
    assertThat(attempt.decision().getState()).isEqualTo(EnquiryRejection.State.PENDING);
  }

  @Test
  void multiTenantTokenWithoutTenantClaimIsRefusedWithoutAFallback() {
    ReflectionTestUtils.setField(transactions, "multitenancyEnabled", true);
    TenantContext.setCurrentTenant(1L);

    assertThatThrownBy(() -> transactions.begin(5L)).isInstanceOf(ForbiddenException.class);
    verify(tenants, never()).getSingleTenancyTenantDataFresh();
    verify(rejections, never()).saveAndFlush(any());
  }

  @Test
  void singleTenantLookupOutageIsUnavailableNotForbiddenAndWritesNothing() {
    ReflectionTestUtils.setField(transactions, "multitenancyEnabled", false);
    when(tenants.getSingleTenancyTenantDataFresh())
        .thenThrow(new org.springframework.web.client.ResourceAccessException("down"));

    assertThatThrownBy(() -> transactions.begin(5L))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException
                .class);
    verify(rejections, never()).saveAndFlush(any());
  }

  @Test
  void unrelatedLookupFailureIsNotMaskedAsADenial() {
    ReflectionTestUtils.setField(transactions, "multitenancyEnabled", false);
    when(tenants.getSingleTenancyTenantDataFresh()).thenThrow(new IllegalStateException("bug"));

    assertThatThrownBy(() -> transactions.begin(5L)).isInstanceOf(IllegalStateException.class);
    verify(rejections, never()).saveAndFlush(any());
  }
}
