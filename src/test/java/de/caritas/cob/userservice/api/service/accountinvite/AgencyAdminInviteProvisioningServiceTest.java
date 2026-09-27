package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgencyAdminInviteProvisioningServiceTest {

  private static final String TOKEN = "agency-admin-token";
  private static final String ADMIN_ID = "admin-1";

  @Mock private AccountInviteService accountInviteService;
  @Mock private AccountInviteRepository accountInviteRepository;
  @Mock private CreateAdminService createAdminService;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private AdminRepository adminRepository;
  @Mock private IdentityAccountRemover identityAccountRemover;
  @Mock private AcceptTimeAgencyCheck acceptTimeAgencyCheck;

  @InjectMocks private AgencyAdminInviteProvisioningService service;

  private AccountInvite invite;
  private final AccountInviteLinkException failure =
      new AccountInviteLinkException(AccountInviteLinkException.Reason.REVOKED);

  @BeforeEach
  void anInviteWhoseAcceptStepFailsAfterTheAdminExists() {
    invite =
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.AGENCY_ADMIN)
            .status(AccountInviteStatus.EMAIL_SENT)
            .tenantId(79L)
            .agencyId(275L)
            .recipientEmail("ada@example.org")
            .firstName("Ada")
            .lastName("Lovelace")
            .build();
    when(accountInviteService.findInviteByToken(TOKEN)).thenReturn(invite);
    when(createAdminService.createNewAgencyAdminInTenant(any()))
        .thenReturn(
            Admin.builder()
                .id(ADMIN_ID)
                .username("ada")
                .firstName("Ada")
                .lastName("Lovelace")
                .email("ada@example.org")
                .build());
    when(accountInviteService.acceptInvite(TOKEN, ADMIN_ID)).thenThrow(failure);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void acceptAsAgencyAdmin_Should_DeleteTheAdminRowsAndTheIdentity_When_ALaterStepFails() {
    assertThatThrownBy(() -> service.acceptAsAgencyAdmin(TOKEN, "ada", "pw")).isSameAs(failure);

    var order = inOrder(adminAgencyRepository, adminRepository, identityAccountRemover);
    order.verify(adminAgencyRepository).deleteByAdminId(ADMIN_ID);
    order.verify(adminRepository).deleteById(ADMIN_ID);
    order.verify(identityAccountRemover).rollbackUser(ADMIN_ID);
    assertThat(invite.getProvisioningStatus()).isEqualTo(AccountInviteProvisioningStatus.FAILED);
  }

  @Test
  void acceptAsAgencyAdmin_Should_StillRemoveTheIdentity_When_TheRowCleanupFails() {
    var cleanupFailure = new IllegalStateException("database unavailable");
    doThrow(cleanupFailure).when(adminAgencyRepository).deleteByAdminId(ADMIN_ID);

    assertThatThrownBy(() -> service.acceptAsAgencyAdmin(TOKEN, "ada", "pw"))
        .isSameAs(failure)
        .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(cleanupFailure));

    verify(identityAccountRemover).rollbackUser(ADMIN_ID);
  }
}
