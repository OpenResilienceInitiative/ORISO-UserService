package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
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
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class CounsellorInviteProvisioningServiceTest {

  private final AccountInviteService accountInviteService = mock(AccountInviteService.class);
  private final AccountInviteRepository accountInviteRepository =
      mock(AccountInviteRepository.class);
  private final ConsultantRepository consultantRepository = mock(ConsultantRepository.class);
  private final CreateConsultantSaga createConsultantSaga = mock(CreateConsultantSaga.class);
  private final IdentityAuthentication identityAuthentication = mock(IdentityAuthentication.class);
  private final IdentityClientConfig identityClientConfig = mock(IdentityClientConfig.class);
  private final CounsellorAgencyAdminGrantService counsellorAgencyAdminGrantService =
      mock(CounsellorAgencyAdminGrantService.class);
  private final de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository
      consultantTopicRepository =
          mock(de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository.class);

  private final ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService =
      mock(ConsultantAgencyRelationCreatorService.class);
  private final ConsultantInitialRolesForInvite initialRolePolicy =
      mock(ConsultantInitialRolesForInvite.class);
  private final AgencyFacts agencyFacts = mock(AgencyFacts.class);

  private final de.caritas.cob.userservice.api.adapters.keycloak.commands
          .IdentityAccountProvisioning
      identityProvisioning =
          mock(
              de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityAccountProvisioning
                  .class);
  private CounsellorInviteProvisioningService service;

  @BeforeEach
  void setUp() {
    // The row is still in the status the test put it in (no racing revoke here).
    org.mockito.Mockito.lenient()
        .when(accountInviteRepository.holdInStatus(any(), any(), any()))
        .thenReturn(1);
    service =
        new CounsellorInviteProvisioningService(
            identityProvisioning,
            accountInviteService,
            accountInviteRepository,
            consultantRepository,
            createConsultantSaga,
            counsellorAgencyAdminGrantService,
            consultantAgencyRelationCreatorService,
            new AcceptTimeAgencyCheck(agencyFacts, identityAuthentication, identityClientConfig),
            initialRolePolicy,
            consultantTopicRepository);
    when(initialRolePolicy.resolve(any()))
        .thenReturn(
            new ConsultantInitialRolesForInvite.InitialRoles(
                new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
                    .id(275L)
                    .tenantId(79L),
                java.util.Set.of("consultant")));
    when(agencyFacts.find(275L))
        .thenReturn(Optional.of(new AgencyFacts.Agency(275L, 79L, false, List.of())));
    var technicalUser = new TaskIdentityCredentials();
    technicalUser.setClientId("technical-user");
    technicalUser.setClientSecret("technical-password");
    when(identityClientConfig.getTaskIdentity(org.mockito.ArgumentMatchers.any()))
        .thenReturn(technicalUser);
    when(identityAuthentication.loginTask(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new IdentityLogin("technical-token", 60, 60, null));
    when(accountInviteRepository.save(any(AccountInvite.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = AccountInviteTargetRole.class,
      names = {"COUNSELLOR", "AGENCY_ADMIN"})
  void inviteesOwnPasswordIsPermanentBeforeTheInviteIsAccepted(AccountInviteTargetRole role) {
    var invite = activeCounsellorInvite();
    invite.setAgencyIdAllocationMode(
        de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode.AUTO);
    invite.setAgencyReservationToken("owned-agency-token");
    invite.setTargetRole(role);
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("created-consultant")));
    when(accountInviteService.acceptInvite("raw-token", "created-consultant")).thenReturn(invite);

    acceptThroughWizard(
        invite,
        "raw-token",
        new ProvisionCounsellorCommand(
            "invited-counsellor",
            "self-chosen-password",
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true));

    var order =
        org.mockito.Mockito.inOrder(
            createConsultantSaga, accountInviteService, identityProvisioning);
    order
        .verify(createConsultantSaga)
        .createInvitedConsultant(
            org.mockito.ArgumentMatchers.argThat(
                dto -> "self-chosen-password".equals(dto.getPassword())),
            any());
    order.verify(accountInviteService).acceptInvite("raw-token", "created-consultant");
    order.verify(identityProvisioning).completeCreatedAccount("created-consultant");
  }

  @Test
  void rejectingInitialPasswordBeforeAnyAccountLeavesTheInviteRetryable() {
    var invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(), any()))
        .thenThrow(new IllegalStateException("atomic password rejected"));
    assertThatThrownBy(
            () ->
                service.acceptInvite(
                    "raw-token",
                    new ProvisionCounsellorCommand(
                        "invited-counsellor", "self-chosen-password", true, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("atomic password rejected");
    verify(createConsultantSaga).createInvitedConsultant(any(), any());
    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(invite.getProvisioningStatus()).isEqualTo(AccountInviteProvisioningStatus.FAILED);
    verifyNoInteractions(
        identityProvisioning,
        consultantAgencyRelationCreatorService,
        counsellorAgencyAdminGrantService);
    verify(accountInviteService, never()).acceptInvite(any(), any());
  }

  @Test
  void failedAgencyAssignmentMarksInviteFailedAndKeepsItRetryable() {
    AccountInvite invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("partially-created-consultant")));
    Consultant partiallyCreatedConsultant = mock(Consultant.class);
    when(consultantRepository.findById("partially-created-consultant"))
        .thenReturn(Optional.of(partiallyCreatedConsultant));
    doThrow(new IllegalStateException("agency assignment failed"))
        .when(consultantAgencyRelationCreatorService)
        .createOwnedCreationRelations(
            org.mockito.ArgumentMatchers.eq("partially-created-consultant"), any(), any(), any());

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

  @ParameterizedTest(name = "{0}")
  @MethodSource("agenciesThatMayNotAccept")
  void acceptInvite_Should_RefuseBeforeCreatingTheConsultant_When_TheAgencyIsGoneOrForeign(
      String why, Optional<AgencyFacts.Agency> agency) {
    AccountInvite invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(initialRolePolicy.resolve(any()))
        .thenReturn(
            new ConsultantInitialRolesForInvite.InitialRoles(
                new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
                    .id(275L)
                    .tenantId(79L),
                java.util.Set.of("consultant")));
    when(agencyFacts.find(275L)).thenReturn(agency);

    assertThatThrownBy(
            () ->
                service.acceptInvite(
                    "raw-token",
                    new ProvisionCounsellorCommand(
                        "invited-counsellor", "test-password", true, null)))
        .isInstanceOf(NotFoundException.class);

    verify(createConsultantSaga, never()).createInvitedConsultant(any(), any());
    assertThat(invite.getProvisioningStatus()).isEqualTo(AccountInviteProvisioningStatus.FAILED);
  }

  // A 403 from AgencyService also surfaces as an empty result.
  static Stream<Arguments> agenciesThatMayNotAccept() {
    return Stream.of(
        Arguments.of("not found or forbidden", Optional.empty()),
        Arguments.of("deleted", Optional.of(new AgencyFacts.Agency(275L, 79L, true, List.of()))),
        Arguments.of(
            "of another tenant", Optional.of(new AgencyFacts.Agency(275L, 80L, false, List.of()))));
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

    verify(createConsultantSaga, never()).createInvitedConsultant(any(), any());
  }

  @Test
  void acceptCounsellorInvite_mapsTheAvatarChoiceOntoTheCreateConsultantDTO() {
    CreateConsultantDTO created = captureCreatedConsultant("ICON", "motif-24");

    assertThat(created.getAvatarKind()).isEqualTo(CreateConsultantDTO.AvatarKindEnum.ICON);
    assertThat(created.getAvatarId()).isEqualTo("motif-24");
  }

  @Test
  void acceptCounsellorInvite_ignoresAnUnknownAvatarKindInsteadOfFailing() {
    CreateConsultantDTO created = captureCreatedConsultant("<script>alert(1)</script>", "motif-24");

    assertThat(created.getAvatarKind()).isNull();
  }

  private CreateConsultantDTO captureCreatedConsultant(String avatarKind, String avatarId) {
    AccountInvite invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("created-consultant")));
    when(accountInviteService.acceptInvite("raw-token", "created-consultant")).thenReturn(invite);

    service.acceptInvite(
        "raw-token",
        new ProvisionCounsellorCommand(
            "invited-counsellor",
            "test-password",
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            avatarKind,
            avatarId));

    ArgumentCaptor<CreateConsultantDTO> captor = ArgumentCaptor.forClass(CreateConsultantDTO.class);
    verify(createConsultantSaga).createInvitedConsultant(captor.capture(), any());
    return captor.getValue();
  }

  @Test
  void acceptCounsellorInviteUsesInviteTenantAndRestoresRequestTenant() {
    AccountInvite invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
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

  @Test
  void theTechnicalTokenIsAmbientOnlyForTheRemoteProvisioningCalls() {
    AccountInvite invite = activeCounsellorInvite();
    invite.setAgencyIdAllocationMode(
        de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode.AUTO);
    invite.setAgencyReservationToken("owned-agency-token");
    var ambientDuringDatabaseWrites = new java.util.ArrayList<Optional<String>>();
    var ambientDuringAgencyAssignment = new java.util.ArrayList<Optional<String>>();
    var ambientDuringAdminGrant = new java.util.ArrayList<Optional<String>>();
    var ambientDuringInviteAccept = new java.util.ArrayList<Optional<String>>();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(accountInviteRepository.save(any(AccountInvite.class)))
        .thenAnswer(
            invocation -> {
              ambientDuringDatabaseWrites.add(TechnicalAccessTokenContext.get());
              return invocation.getArgument(0);
            });
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenAnswer(
            invocation -> {
              assertThat(TechnicalAccessTokenContext.get()).contains("technical-token");
              return new ConsultantAdminResponseDTO()
                  .embedded(new ConsultantDTO().id("created-consultant"));
            });
    org.mockito.Mockito.doAnswer(
            invocation -> {
              ambientDuringAgencyAssignment.add(TechnicalAccessTokenContext.get());
              return null;
            })
        .when(consultantAgencyRelationCreatorService)
        .createOwnedCreationRelations(
            org.mockito.ArgumentMatchers.eq("created-consultant"), any(), any(), any());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              ambientDuringAdminGrant.add(TechnicalAccessTokenContext.get());
              return null;
            })
        .when(counsellorAgencyAdminGrantService)
        .grantAgencyAdmin(any(), any(), any());
    when(accountInviteService.acceptInvite("raw-token", "created-consultant"))
        .thenAnswer(
            invocation -> {
              ambientDuringInviteAccept.add(TechnicalAccessTokenContext.get());
              return invite;
            });

    acceptThroughWizard(
        invite,
        "raw-token",
        new ProvisionCounsellorCommand(
            "invited-counsellor",
            "test-password",
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.List.of(2L),
            true));

    assertThat(ambientDuringAgencyAssignment).containsExactly(Optional.of("technical-token"));
    // Keycloak admin REST and local writes never need the service-to-service bearer.
    assertThat(ambientDuringAdminGrant).containsExactly(Optional.empty());
    assertThat(ambientDuringInviteAccept).containsExactly(Optional.empty());
    assertThat(ambientDuringDatabaseWrites).isNotEmpty().allMatch(Optional::isEmpty);
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }

  @Test
  void aFailedTechnicalLoginMarksTheInviteFailedInsteadOfLeavingItInProgress() {
    AccountInvite invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(identityAuthentication.loginTask(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("identity provider unavailable"));

    assertThatThrownBy(
            () ->
                service.acceptInvite(
                    "raw-token",
                    new ProvisionCounsellorCommand(
                        "invited-counsellor", "test-password", true, null)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(invite.getProvisioningStatus()).isEqualTo(AccountInviteProvisioningStatus.FAILED);
    assertThat(invite.getProvisioningFailureReason())
        .isEqualTo("Service authentication unavailable");
    verify(createConsultantSaga, never()).createInvitedConsultant(any(), any());
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }

  @Test
  void theRollbackOfAPartiallyCreatedConsultantRunsWithTheTechnicalToken() {
    AccountInvite invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("partially-created-consultant")));
    Consultant partiallyCreatedConsultant = mock(Consultant.class);
    when(consultantRepository.findById("partially-created-consultant"))
        .thenReturn(Optional.of(partiallyCreatedConsultant));
    doThrow(new IllegalStateException("agency assignment failed"))
        .when(consultantAgencyRelationCreatorService)
        .createOwnedCreationRelations(
            org.mockito.ArgumentMatchers.eq("partially-created-consultant"), any(), any(), any());
    var ambientDuringRollback = new java.util.ArrayList<Optional<String>>();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              ambientDuringRollback.add(TechnicalAccessTokenContext.get());
              return null;
            })
        .when(createConsultantSaga)
        .rollbackCreateNewConsultant(partiallyCreatedConsultant);

    assertThatThrownBy(
        () ->
            service.acceptInvite(
                "raw-token",
                new ProvisionCounsellorCommand("invited-counsellor", "test-password", true, null)));

    assertThat(ambientDuringRollback).containsExactly(Optional.of("technical-token"));
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }

  @Test
  void newAgencyRegistrationMakesTheInviteeTheAgencyAdmin() {
    // The invitee just created this Beratungsstelle, so they administrate it.
    AccountInvite invite = activeCounsellorInvite();
    invite.setAgencyIdAllocationMode(
        de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode.AUTO);
    invite.setAgencyReservationToken("owned-agency-token");
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("created-consultant")));
    when(accountInviteService.acceptInvite("raw-token", "created-consultant")).thenReturn(invite);

    acceptThroughWizard(
        invite,
        "raw-token",
        new ProvisionCounsellorCommand(
            "invited-counsellor",
            "test-password",
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.List.of(2L),
            true));

    verify(counsellorAgencyAdminGrantService).grantAgencyAdmin("created-consultant", 275L, invite);
  }

  @Test
  void existingAgencyRegistrationGrantsNoAgencyAdminRights() {
    AccountInvite invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("created-consultant")));
    when(accountInviteService.acceptInvite("raw-token", "created-consultant")).thenReturn(invite);

    service.acceptInvite(
        "raw-token",
        new ProvisionCounsellorCommand("invited-counsellor", "test-password", true, null));

    verifyNoInteractions(counsellorAgencyAdminGrantService);
  }

  @Test
  void theChosenTopicsAreStoredForTheInvitesCentre() {
    AccountInvite invite = activeCounsellorInvite();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("created-consultant")));
    when(accountInviteService.acceptInvite("raw-token", "created-consultant")).thenReturn(invite);

    service.acceptInvite(
        "raw-token",
        new ProvisionCounsellorCommand("invited-counsellor", "test-password", true, null));

    var order =
        org.mockito.Mockito.inOrder(
            consultantAgencyRelationCreatorService, consultantTopicRepository);
    order
        .verify(consultantAgencyRelationCreatorService)
        .createOwnedCreationRelations(eq("created-consultant"), any(), any(), any());
    order
        .verify(consultantTopicRepository)
        .assignUnscopedTopicsToAgency("created-consultant", invite.getAgencyId());
  }

  @Test
  void anAgencyAdminWhoAlsoCounsels_getsTheChosenTopicsStoredForTheInvitesCentre() {
    AccountInvite invite = activeCounsellorInvite();
    invite.setTargetRole(AccountInviteTargetRole.AGENCY_ADMIN);
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("created-consultant")));
    when(accountInviteService.acceptInvite("raw-token", "created-consultant")).thenReturn(invite);

    service.acceptInvite(
        "raw-token",
        new ProvisionCounsellorCommand(
            "invited-counsellor",
            "test-password",
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.List.of(2L),
            true));

    var order =
        org.mockito.Mockito.inOrder(
            consultantAgencyRelationCreatorService, consultantTopicRepository);
    order
        .verify(consultantAgencyRelationCreatorService)
        .createOwnedCreationRelations(eq("created-consultant"), any(), any(), any());
    order
        .verify(consultantTopicRepository)
        .assignUnscopedTopicsToAgency("created-consultant", invite.getAgencyId());
  }

  @Test
  void newAgencyInviteWithoutDepartmentIsAcceptedWhenTopicsWereChosen() {
    // A reserved-agency invite carries no department yet — the wizard's topic selection is the
    // department. Requiring departmentId would make every new-Beratungsstelle invite a 400.
    AccountInvite invite = activeCounsellorInvite();
    invite.setAgencyIdAllocationMode(
        de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode.AUTO);
    invite.setAgencyReservationToken("owned-agency-token");
    invite.setDepartmentId(null);
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("created-consultant")));
    when(accountInviteService.acceptInvite("raw-token", "created-consultant")).thenReturn(invite);

    acceptThroughWizard(
        invite,
        "raw-token",
        new ProvisionCounsellorCommand(
            "invited-counsellor",
            "test-password",
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.List.of(7L),
            true));

    verify(counsellorAgencyAdminGrantService).grantAgencyAdmin("created-consultant", 275L, invite);
  }

  @Test
  void aWaivedInviteClearsTheSecondFactorRequirementTheCreatePathSet() {
    // An administrator excused this counsellor on the invite. Without this the
    // waiver would be granted and then silently ignored at first login.
    Consultant provisioned = provisionWith(TwoFactorGateStatus.WAIVED, true);

    assertThat(provisioned.getTwoFactorRequired()).isFalse();
    verify(consultantRepository).save(provisioned);
  }

  @Test
  void anInviteeIsNotAskedToReplaceThePasswordTheyJustChose() {
    // CreateConsultantDTOCreationInputAdapter.isPasswordChangeRequired() is unconditionally true,
    // which is right when an administrator types the password and hands it over. Here the
    // counsellor typed it themselves and nobody else has ever seen it, so the non-dismissible
    // "replace your password" screen is asking them to replace their own secret with another one.
    Consultant provisioned = provisionWith(TwoFactorGateStatus.PENDING_SETUP, true);

    assertThat(provisioned.getPasswordChangeRequired()).isFalse();
    verify(consultantRepository).save(provisioned);
  }

  @Test
  void aPendingInviteLeavesTheSecondFactorRequirementInPlace() {
    Consultant provisioned = provisionWith(TwoFactorGateStatus.PENDING_SETUP, true);

    assertThat(provisioned.getTwoFactorRequired()).isTrue();
  }

  @Test
  void nothingIsWritten_When_neitherRequirementNeedsCorrecting() {
    Consultant provisioned = provisionWith(TwoFactorGateStatus.PENDING_SETUP, false);

    assertThat(provisioned.getTwoFactorRequired()).isTrue();
    assertThat(provisioned.getPasswordChangeRequired()).isFalse();
    verify(consultantRepository, org.mockito.Mockito.never()).save(any(Consultant.class));
  }

  private Consultant provisionWith(TwoFactorGateStatus status, boolean passwordChangeRequired) {
    AccountInvite invite = activeCounsellorInvite();
    invite.setTwoFactorStatus(status);
    // The admin create path has already marked it required by the time we look.
    Consultant provisioned =
        Consultant.builder()
            .id("created-consultant")
            .username("invited-counsellor")
            .firstName("Lisa")
            .lastName("Simpson")
            .email("lisa.simpson@example.org")
            .twoFactorRequired(true)
            .passwordChangeRequired(passwordChangeRequired)
            .build();
    when(accountInviteService.findInviteByToken("raw-token")).thenReturn(invite);
    when(createConsultantSaga.createInvitedConsultant(any(CreateConsultantDTO.class), any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("created-consultant")));
    when(consultantRepository.findByIdAndDeleteDateIsNull("created-consultant"))
        .thenReturn(Optional.of(provisioned));
    when(accountInviteService.acceptInvite("raw-token", "created-consultant")).thenReturn(invite);

    service.acceptInvite(
        "raw-token",
        new ProvisionCounsellorCommand("invited-counsellor", "test-password", true, null));

    return provisioned;
  }

  private AccountInvite acceptThroughWizard(
      AccountInvite held, String token, ProvisionCounsellorCommand command) {
    return service.acceptInvite(token, command, WizardAccept.decidedFrom(held, () -> {}));
  }

  private static AccountInvite activeCounsellorInvite() {
    return AccountInvite.builder()
        .id(1L)
        .purpose(AccountInvitePurpose.INVITE)
        .targetRole(AccountInviteTargetRole.COUNSELLOR)
        .tenantId(79L)
        .recipientEmail("lisa.simpson@example.org")
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
