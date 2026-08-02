package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSupportAdminDTO.ProvisioningStatusEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSupportAdminDTO.SecondFactorStatusEnum;
import de.caritas.cob.userservice.api.admin.service.admin.GlobalSupportAdminUserService.SupportAdminNotOperationalException;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.model.SupportAdminProfile;
import de.caritas.cob.userservice.api.model.SupportAdminProfile.SupportAdminStatus;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.SupportAccessRevoker;
import de.caritas.cob.userservice.api.port.out.SupportAdminProfileRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class GlobalSupportAdminUserServiceTest {

  @InjectMocks private GlobalSupportAdminUserService globalSupportAdminUserService;

  @Mock private RetrieveAdminService retrieveAdminService;
  @Mock private CreateAdminService createAdminService;
  @Mock private IdentityClient identityClient;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private SupportAdminProfileRepository supportAdminProfileRepository;
  @Mock private SupportAccessRevoker supportAccessRevoker;

  @Test
  void create_ShouldRejectCallerWhoIsNotPlatformAdmin() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);

    assertThatThrownBy(() -> globalSupportAdminUserService.create(new CreateAdminDTO()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void create_ShouldReleaseTheAccountOnlyAfterTheRoleWasAssigned() {
    CreateAdminDTO request = new CreateAdminDTO("support", "Sam", "Support", "support@example.org");
    Admin admin = supportAdmin("gsa-1", "support");
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(createAdminService.createNewGlobalSupportAdmin(request)).thenReturn(admin);
    echoSavedProfile();

    var result = globalSupportAdminUserService.create(request);

    var inOrder = org.mockito.Mockito.inOrder(identityClient);
    inOrder.verify(identityClient).updateRole("gsa-1", UserRole.GLOBAL_SUPPORT_ADMIN);
    inOrder.verify(identityClient).setUserEnabled("gsa-1", true);
    assertThat(result.getProvisioningStatus()).isEqualTo(ProvisioningStatusEnum.PENDING_2_FA);
    assertThat(result.getSecondFactorStatus()).isEqualTo(SecondFactorStatusEnum.PENDING_2_FA);
  }

  @Test
  void create_ShouldLeaveTheAccountBlockedWhenTheRoleAssignmentFails() {
    CreateAdminDTO request = new CreateAdminDTO("support", "Sam", "Support", "support@example.org");
    Admin admin = supportAdmin("gsa-1", "support");
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(createAdminService.createNewGlobalSupportAdmin(request)).thenReturn(admin);
    doThrow(new IllegalStateException("realm role missing"))
        .when(identityClient)
        .updateRole("gsa-1", UserRole.GLOBAL_SUPPORT_ADMIN);
    echoSavedProfile();

    var result = globalSupportAdminUserService.create(request);

    assertThat(result.getProvisioningStatus())
        .isEqualTo(ProvisioningStatusEnum.PROVISIONING_FAILED);
    verify(identityClient).setUserEnabled("gsa-1", false);
    verify(identityClient, never()).setUserEnabled("gsa-1", true);
  }

  @Test
  void search_ShouldQueryOnlySupportAdminsAndResolveLiveSecondFactorStatus() {
    PageRequest pageRequest = PageRequest.of(0, 20);
    Admin admin = supportAdmin("gsa-1", "support");
    Admin.AdminBase adminBase = mock(Admin.AdminBase.class);
    when(adminBase.getId()).thenReturn("gsa-1");
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(retrieveAdminService.findAllByInfix("*", Admin.AdminType.SUPPORT, pageRequest))
        .thenReturn(new PageImpl<>(List.of(adminBase), pageRequest, 1));
    when(retrieveAdminService.findAllById(Set.of("gsa-1"))).thenReturn(List.of(admin));
    when(supportAdminProfileRepository.findAllByAdminIdIn(Set.of("gsa-1")))
        .thenReturn(List.of(profile("gsa-1", SupportAdminStatus.PENDING_2FA)));
    when(identityClient.getOtpCredential("support")).thenReturn(new OtpInfoDTO().otpSetup(true));
    echoSavedProfile();

    var result = globalSupportAdminUserService.search("*", pageRequest);

    assertThat(result.getTotal()).isEqualTo(1);
    assertThat(result.getItems())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.getSecondFactorStatus()).isEqualTo(SecondFactorStatusEnum.ACTIVE);
              // Enrolment promotes the account without a Platform Admin doing anything else.
              assertThat(item.getProvisioningStatus()).isEqualTo(ProvisioningStatusEnum.ACTIVE);
            });
    verify(retrieveAdminService).findAllByInfix("*", Admin.AdminType.SUPPORT, pageRequest);
  }

  @Test
  void requireOperationalSupportAdmin_ShouldAllowActiveProfileWithEnrolledOtp() {
    Admin admin = supportAdmin("gsa-1", "support");
    when(authenticatedUser.isGlobalSupportAdmin()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("gsa-1");
    when(supportAdminProfileRepository.findById("gsa-1"))
        .thenReturn(Optional.of(profile("gsa-1", SupportAdminStatus.ACTIVE)));
    when(retrieveAdminService.findAdmin("gsa-1", Admin.AdminType.SUPPORT)).thenReturn(admin);
    when(identityClient.getOtpCredential("support")).thenReturn(new OtpInfoDTO().otpSetup(true));

    assertThatCode(() -> globalSupportAdminUserService.requireOperationalSupportAdmin())
        .doesNotThrowAnyException();
  }

  @Test
  void requireOperationalSupportAdmin_ShouldFailClosedWhenOtpIsNotEnrolled() {
    Admin admin = supportAdmin("gsa-1", "support");
    when(authenticatedUser.isGlobalSupportAdmin()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("gsa-1");
    when(supportAdminProfileRepository.findById("gsa-1"))
        .thenReturn(Optional.of(profile("gsa-1", SupportAdminStatus.PENDING_2FA)));
    when(retrieveAdminService.findAdmin("gsa-1", Admin.AdminType.SUPPORT)).thenReturn(admin);
    when(identityClient.getOtpCredential("support")).thenReturn(new OtpInfoDTO().otpSetup(false));

    assertThatThrownBy(() -> globalSupportAdminUserService.requireOperationalSupportAdmin())
        .isInstanceOf(SupportAdminNotOperationalException.class);
  }

  @Test
  void requireOperationalSupportAdmin_ShouldFailClosedWhenKeycloakLookupFails() {
    Admin admin = supportAdmin("gsa-1", "support");
    when(authenticatedUser.isGlobalSupportAdmin()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("gsa-1");
    when(supportAdminProfileRepository.findById("gsa-1"))
        .thenReturn(Optional.of(profile("gsa-1", SupportAdminStatus.ACTIVE)));
    when(retrieveAdminService.findAdmin("gsa-1", Admin.AdminType.SUPPORT)).thenReturn(admin);
    when(identityClient.getOtpCredential("support"))
        .thenThrow(new IllegalStateException("Keycloak unreachable"));
    echoSavedProfile();

    assertThatThrownBy(() -> globalSupportAdminUserService.requireOperationalSupportAdmin())
        .isInstanceOf(SupportAdminNotOperationalException.class);
  }

  @Test
  void requireOperationalSupportAdmin_ShouldRefuseDisabledAccountDespiteAValidToken() {
    when(authenticatedUser.isGlobalSupportAdmin()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("gsa-1");
    when(supportAdminProfileRepository.findById("gsa-1"))
        .thenReturn(Optional.of(profile("gsa-1", SupportAdminStatus.DISABLED)));

    assertThatThrownBy(() -> globalSupportAdminUserService.requireOperationalSupportAdmin())
        .isInstanceOf(SupportAdminNotOperationalException.class);
    verify(retrieveAdminService, never()).findAdmin(anyString(), any());
  }

  @Test
  void disable_ShouldRevokeRunningAccessBeforeWithdrawingTheIdentity() {
    Admin admin = supportAdmin("gsa-1", "support");
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(retrieveAdminService.findAdmin("gsa-1", Admin.AdminType.SUPPORT)).thenReturn(admin);
    when(supportAdminProfileRepository.findById("gsa-1"))
        .thenReturn(Optional.of(profile("gsa-1", SupportAdminStatus.ACTIVE)));
    when(supportAccessRevoker.revokeAllForSupportAdmin(eq("gsa-1"), anyString())).thenReturn(2);
    when(identityClient.getOtpCredential("support")).thenReturn(new OtpInfoDTO().otpSetup(true));
    echoSavedProfile();

    var result = globalSupportAdminUserService.disable("gsa-1");

    var inOrder = org.mockito.Mockito.inOrder(supportAccessRevoker, identityClient);
    inOrder.verify(supportAccessRevoker).revokeAllForSupportAdmin(eq("gsa-1"), anyString());
    inOrder.verify(identityClient).removeRoleIfPresent("gsa-1", "global-support-admin");
    inOrder.verify(identityClient).setUserEnabled("gsa-1", false);
    assertThat(result.getProvisioningStatus()).isEqualTo(ProvisioningStatusEnum.DISABLED);
  }

  @Test
  void disable_ShouldRejectCallerWhoIsNotPlatformAdmin() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);

    assertThatThrownBy(() -> globalSupportAdminUserService.disable("gsa-1"))
        .isInstanceOf(AccessDeniedException.class);
    verify(supportAccessRevoker, never()).revokeAllForSupportAdmin(anyString(), anyString());
  }

  @Test
  void enable_ShouldReprovisionAndStayUnusableUntilTheSecondFactorIsEnrolled() {
    Admin admin = supportAdmin("gsa-1", "support");
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(retrieveAdminService.findAdmin("gsa-1", Admin.AdminType.SUPPORT)).thenReturn(admin);
    when(supportAdminProfileRepository.findById("gsa-1"))
        .thenReturn(Optional.of(profile("gsa-1", SupportAdminStatus.DISABLED)));
    when(identityClient.getOtpCredential("support")).thenReturn(new OtpInfoDTO().otpSetup(false));
    echoSavedProfile();

    var result = globalSupportAdminUserService.enable("gsa-1");

    verify(identityClient).updateRole("gsa-1", UserRole.GLOBAL_SUPPORT_ADMIN);
    verify(identityClient).setUserEnabled("gsa-1", true);
    assertThat(result.getProvisioningStatus()).isEqualTo(ProvisioningStatusEnum.PENDING_2_FA);
  }

  /** Lenient: a given flow uses save or saveAndFlush, not necessarily both. */
  private void echoSavedProfile() {
    lenient()
        .when(supportAdminProfileRepository.save(any(SupportAdminProfile.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(supportAdminProfileRepository.saveAndFlush(any(SupportAdminProfile.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private SupportAdminProfile profile(String adminId, SupportAdminStatus status) {
    return SupportAdminProfile.builder()
        .adminId(adminId)
        .status(status)
        .createDate(LocalDateTime.parse("2026-07-25T12:00:00"))
        .updateDate(LocalDateTime.parse("2026-07-25T12:00:00"))
        .build();
  }

  private Admin supportAdmin(String id, String username) {
    return Admin.builder()
        .id(id)
        .tenantId(0L)
        .username(username)
        .firstName("Sam")
        .lastName("Support")
        .email("support@example.org")
        .type(Admin.AdminType.SUPPORT)
        .createDate(LocalDateTime.parse("2026-07-25T12:00:00"))
        .updateDate(LocalDateTime.parse("2026-07-25T12:00:00"))
        .build();
  }
}
