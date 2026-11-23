package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class UnitQueueProblemsTest {

  @Mock private AccountInviteRepository accountInviteRepository;
  @Mock private InviteEmailTemplateRepository templateRepository;
  @Mock private ReservationLedger ledger;
  @Mock private InviteDelivery delivery;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private TenantIdAllocationClient tenantIdAllocationClient;
  @Mock private ObjectProvider<IdentityClientConfig> identityClientConfig;
  @Mock private ObjectProvider<IdentityAuthentication> identityAuthentication;

  private UnitQueue queue;

  @BeforeEach
  void createQueue() {
    queue =
        new UnitQueue(
            accountInviteRepository,
            templateRepository,
            ledger,
            delivery,
            transactionManager,
            tenantIdAllocationClient,
            identityClientConfig,
            identityAuthentication);
  }

  @Test
  void problemsOf_Should_AskOncePerUnitType_AndMatchAdminsByTraegerAndNotTheInviteItself() {
    AccountInvite ownTraeger = waitingForAgency(1L, 500L, 1L);
    AccountInvite otherTraeger = waitingForAgency(2L, 500L, 2L);
    AccountInvite onlyItself = waitingForAgency(3L, 600L, 1L);
    AccountInvite forTenant = waitingForTenant(4L, 900L, "current-proof");
    AccountInvite forPreviousReservation = waitingForTenant(5L, 900L, "previous-proof");
    AccountInvite withoutProof = waitingForTenant(6L, 900L, null);
    AccountInvite tenantAdmin = admin(11L, null, 900L);
    tenantAdmin.setTenantIdReservationToken("current-proof");
    when(accountInviteRepository.findPendingAgencyAdminsIn(any(), any(), any()))
        .thenReturn(List.of(admin(10L, 500L, 1L), admin(3L, 600L, 1L)));
    when(accountInviteRepository.findPendingTenantAdminsIn(any(), any(), any()))
        .thenReturn(List.of(tenantAdmin));

    Map<Long, InviteQueueProblem> problems =
        queue.problemsOf(
            List.of(
                ownTraeger,
                otherTraeger,
                onlyItself,
                forTenant,
                forPreviousReservation,
                withoutProof));

    assertThat(problems)
        .containsOnlyKeys(2L, 3L, 5L, 6L)
        .containsValues(InviteQueueProblem.NO_UNIT_ADMIN);
    verify(accountInviteRepository).findPendingAgencyAdminsIn(any(), any(), any());
    verify(accountInviteRepository).findPendingTenantAdminsIn(any(), any(), any());
    verifyNoInteractions(tenantIdAllocationClient);
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

  private static AccountInvite waitingForTenant(Long id, Long tenantId, String proof) {
    return AccountInvite.builder()
        .id(id)
        .status(AccountInviteStatus.WAITING_FOR_UNIT)
        .waitingForUnit(InviteUnitType.TENANT)
        .tenantId(tenantId)
        .waitingTenantReservationToken(proof)
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
