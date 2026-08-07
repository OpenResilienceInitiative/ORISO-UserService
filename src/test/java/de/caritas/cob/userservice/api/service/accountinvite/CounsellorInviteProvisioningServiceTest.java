package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.accountinvite.CounsellorInviteProvisioningService.ProvisionCounsellorCommand;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CounsellorInviteProvisioningServiceTest {

  private final AccountInviteService accountInviteService = mock(AccountInviteService.class);
  private final AccountInviteRepository accountInviteRepository =
      mock(AccountInviteRepository.class);
  private final ConsultantAdminFacade consultantAdminFacade = mock(ConsultantAdminFacade.class);
  private final ConsultantRepository consultantRepository = mock(ConsultantRepository.class);
  private final CreateConsultantSaga createConsultantSaga = mock(CreateConsultantSaga.class);
  private final IdentityAuthentication identityAuthentication = mock(IdentityAuthentication.class);
  private final IdentityClientConfig identityClientConfig = mock(IdentityClientConfig.class);

  private CounsellorInviteProvisioningService service;

  @BeforeEach
  void setUp() {
    service =
        new CounsellorInviteProvisioningService(
            accountInviteService,
            accountInviteRepository,
            consultantAdminFacade,
            consultantRepository,
            createConsultantSaga,
            identityAuthentication,
            identityClientConfig);
    var technicalUser = new TechnicalUserConfig();
    technicalUser.setUsername("technical-user");
    technicalUser.setPassword("technical-password");
    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    when(identityAuthentication.login("technical-user", "technical-password"))
        .thenReturn(new IdentityLogin("technical-token", 60, 60, null));
    when(accountInviteRepository.save(any(AccountInvite.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void failedAgencyAssignmentMarksInviteFailedAndKeepsItRetryable() {
    AccountInvite invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(consultantAdminFacade.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("partially-created-consultant")));
    Consultant partiallyCreatedConsultant = mock(Consultant.class);
    when(consultantRepository.findById("partially-created-consultant"))
        .thenReturn(Optional.of(partiallyCreatedConsultant));
    doThrow(new IllegalStateException("agency assignment failed"))
        .when(consultantAdminFacade)
        .createNewConsultantAgency(
            org.mockito.ArgumentMatchers.eq("partially-created-consultant"),
            any(CreateConsultantAgencyDTO.class));

    assertThatThrownBy(
            () ->
                service.acceptInvite(
                    "raw-token",
                    new ProvisionCounsellorCommand(
                        "invited-counsellor", "test-password", true, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("agency assignment failed");

    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(invite.getProvisioningStatus()).isEqualTo(AccountInviteProvisioningStatus.FAILED);
    assertThat(invite.getProvisionedUserId()).isNull();
    verify(createConsultantSaga).rollbackCreateNewConsultant(partiallyCreatedConsultant);
  }

  @Test
  void inviteAlreadyBeingProvisionedDoesNotCreateDuplicateConsultant() {
    AccountInvite invite = activeCounsellorInvite();
    invite.setProvisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS);
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);

    assertThatThrownBy(
            () ->
                service.acceptInvite(
                    "raw-token",
                    new ProvisionCounsellorCommand(
                        "invited-counsellor", "test-password", true, null)))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class)
        .hasMessageContaining("already in progress");

    verifyNoInteractions(consultantAdminFacade);
  }

  @Test
  void acceptCounsellorInviteUsesInviteTenantAndRestoresRequestTenant() {
    AccountInvite invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(consultantAdminFacade.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenAnswer(
            invocation -> {
              assertThat(TenantContext.getCurrentTenant()).isEqualTo(79L);
              assertThat(TechnicalAccessTokenContext.get()).contains("technical-token");
              return new ConsultantAdminResponseDTO()
                  .embedded(new ConsultantDTO().id("created-consultant"));
            });
    when(accountInviteService.acceptInvite("raw-token", "created-consultant")).thenReturn(invite);
    TenantContext.setCurrentTenant(1L);

    try {
      service.acceptInvite(
          "raw-token",
          new ProvisionCounsellorCommand("invited-counsellor", "test-password", true, null));

      assertThat(TenantContext.getCurrentTenant()).isEqualTo(1L);
      assertThat(TechnicalAccessTokenContext.get()).isEmpty();
    } finally {
      TenantContext.clear();
    }
  }

  private static AccountInvite activeCounsellorInvite() {
    return AccountInvite.builder()
        .targetRole(AccountInviteTargetRole.COUNSELLOR)
        .tenantId(79L)
        .recipientEmail("lisa.simpson@oriso.org")
        .firstName("Lisa")
        .lastName("Simpson")
        .agencyId(275L)
        .departmentId(2L)
        .status(AccountInviteStatus.EMAIL_SENT)
        .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
        .expiresAt(LocalDateTime.now().plusDays(1))
        .createDate(LocalDateTime.now())
        .build();
  }
}
