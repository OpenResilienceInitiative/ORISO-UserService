package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class UnitQueueProblemsTest {

  @Mock private AccountInviteRepository accountInviteRepository;
  @Mock private InviteEmailTemplateRepository templateRepository;
  @Mock private ReservationLedger ledger;
  @Mock private InviteDelivery delivery;
  @Mock private PlatformTransactionManager transactionManager;

  @InjectMocks private UnitQueue queue;

  @Test
  void problemsOf_Should_AskOncePerUnitType_AndMatchAdminsByTraegerAndNotTheInviteItself() {
    AccountInvite ownTraeger = waitingForAgency(1L, 500L, 1L);
    AccountInvite otherTraeger = waitingForAgency(2L, 500L, 2L);
    AccountInvite onlyItself = waitingForAgency(3L, 600L, 1L);
    AccountInvite forTenant = waitingForTenant(4L, 900L);
    when(accountInviteRepository.findPendingAgencyAdminsIn(any(), any(), any()))
        .thenReturn(List.of(admin(10L, 500L, 1L), admin(3L, 600L, 1L)));
    when(accountInviteRepository.findPendingTenantAdminsIn(any(), any(), any()))
        .thenReturn(List.of(admin(11L, null, 900L)));

    Map<Long, InviteQueueProblem> problems =
        queue.problemsOf(List.of(ownTraeger, otherTraeger, onlyItself, forTenant));

    assertThat(problems).containsOnlyKeys(2L, 3L).containsValues(InviteQueueProblem.NO_UNIT_ADMIN);
    verify(accountInviteRepository).findPendingAgencyAdminsIn(any(), any(), any());
    verify(accountInviteRepository).findPendingTenantAdminsIn(any(), any(), any());
  }

  @Test
  void problemsOf_Should_NotQuery_When_NothingWaits() {
    AccountInvite draft =
        AccountInvite.builder().id(1L).status(AccountInviteStatus.DRAFT).agencyId(500L).build();

    assertThat(queue.problemsOf(Set.of(draft))).isEmpty();
    verifyNoInteractions(accountInviteRepository);
  }

  private static AccountInvite waitingForAgency(Long id, Long agencyId, Long tenantId) {
    return AccountInvite.builder()
        .id(id)
        .status(AccountInviteStatus.WAITING_FOR_UNIT)
        .waitingForUnit(InviteUnitType.AGENCY)
        .agencyId(agencyId)
        .tenantId(tenantId)
        .build();
  }

  private static AccountInvite waitingForTenant(Long id, Long tenantId) {
    return AccountInvite.builder()
        .id(id)
        .status(AccountInviteStatus.WAITING_FOR_UNIT)
        .waitingForUnit(InviteUnitType.TENANT)
        .tenantId(tenantId)
        .build();
  }

  private static AccountInvite admin(Long id, Long agencyId, Long tenantId) {
    return AccountInvite.builder()
        .id(id)
        .status(AccountInviteStatus.DRAFT)
        .agencyId(agencyId)
        .tenantId(tenantId)
        .build();
  }
}
