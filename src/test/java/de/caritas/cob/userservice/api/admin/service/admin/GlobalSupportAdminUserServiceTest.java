package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSupportAdminDTO.SecondFactorStatusEnum;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import java.time.LocalDateTime;
import java.util.List;
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

  @Test
  void create_ShouldRejectCallerWhoIsNotPlatformAdmin() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);

    assertThatThrownBy(() -> globalSupportAdminUserService.create(new CreateAdminDTO()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void create_ShouldReturnPendingSecondFactorUntilEnrollment() {
    CreateAdminDTO request = new CreateAdminDTO("support", "Sam", "Support", "support@example.org");
    Admin admin = supportAdmin("gsa-1", "support");
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(createAdminService.createNewGlobalSupportAdmin(request)).thenReturn(admin);

    var result = globalSupportAdminUserService.create(request);

    assertThat(result.getId()).isEqualTo("gsa-1");
    assertThat(result.getSecondFactorStatus()).isEqualTo(SecondFactorStatusEnum.PENDING_2_FA);
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
    when(identityClient.getOtpCredential("support")).thenReturn(new OtpInfoDTO().otpSetup(true));

    var result = globalSupportAdminUserService.search("*", pageRequest);

    assertThat(result.getTotal()).isEqualTo(1);
    assertThat(result.getItems())
        .singleElement()
        .extracting(item -> item.getSecondFactorStatus())
        .isEqualTo(SecondFactorStatusEnum.ACTIVE);
    verify(retrieveAdminService).findAllByInfix("*", Admin.AdminType.SUPPORT, pageRequest);
  }

  @Test
  void requireActiveSecondFactor_ShouldAllowAuthenticatedSupportAdminWithEnrolledOtp() {
    Admin admin = supportAdmin("gsa-1", "support");
    when(authenticatedUser.isGlobalSupportAdmin()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("gsa-1");
    when(retrieveAdminService.findAdmin("gsa-1", Admin.AdminType.SUPPORT)).thenReturn(admin);
    when(identityClient.getOtpCredential("support")).thenReturn(new OtpInfoDTO().otpSetup(true));

    assertThatCode(() -> globalSupportAdminUserService.requireActiveSecondFactor())
        .doesNotThrowAnyException();
  }

  @Test
  void requireActiveSecondFactor_ShouldFailClosedWhenOtpIsNotEnrolled() {
    Admin admin = supportAdmin("gsa-1", "support");
    when(authenticatedUser.isGlobalSupportAdmin()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("gsa-1");
    when(retrieveAdminService.findAdmin("gsa-1", Admin.AdminType.SUPPORT)).thenReturn(admin);
    when(identityClient.getOtpCredential("support")).thenReturn(new OtpInfoDTO().otpSetup(false));

    assertThatThrownBy(() -> globalSupportAdminUserService.requireActiveSecondFactor())
        .isInstanceOf(AccessDeniedException.class);
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
