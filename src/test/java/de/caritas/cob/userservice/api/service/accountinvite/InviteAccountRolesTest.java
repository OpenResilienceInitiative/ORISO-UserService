package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InviteAccountRolesTest {

  @Mock private ConsultantRepository consultantRepository;
  @Mock private AdminRepository adminRepository;
  @InjectMocks private InviteAccountRoles accountRoles;

  @Test
  void of_Should_LookUpAWholePageInOneQueryPerTable() {
    AccountInvite counsellor = accepted(1L, "c-1", AccountInviteTargetRole.COUNSELLOR);
    AccountInvite promoted = accepted(2L, "c-2", AccountInviteTargetRole.COUNSELLOR);
    AccountInvite admin = accepted(3L, "a-3", AccountInviteTargetRole.AGENCY_ADMIN);
    AccountInvite pending =
        AccountInvite.builder()
            .id(4L)
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .status(AccountInviteStatus.EMAIL_SENT)
            .build();
    when(consultantRepository.findAllById(any()))
        .thenReturn(List.of(consultant("c-1"), consultant("c-2")));
    when(adminRepository.findAllByIdIn(anySet()))
        .thenReturn(List.of(admin("c-2", AdminType.AGENCY), admin("a-3", AdminType.AGENCY)));

    Map<Long, List<AccountInviteTargetRole>> roles =
        accountRoles.of(List.of(counsellor, promoted, admin, pending));

    assertThat(roles.get(1L)).containsExactly(AccountInviteTargetRole.COUNSELLOR);
    assertThat(roles.get(2L))
        .containsExactly(AccountInviteTargetRole.AGENCY_ADMIN, AccountInviteTargetRole.COUNSELLOR);
    assertThat(roles.get(3L)).containsExactly(AccountInviteTargetRole.AGENCY_ADMIN);
    assertThat(roles).doesNotContainKey(4L);
    verify(consultantRepository, times(1)).findAllById(any());
    verify(adminRepository, times(1)).findAllByIdIn(anySet());
  }

  @Test
  void of_Should_AskNothing_When_NoInviteOnThePageHasAnAccount() {
    AccountInvite pending =
        AccountInvite.builder()
            .id(4L)
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .status(AccountInviteStatus.DRAFT)
            .build();

    assertThat(accountRoles.of(List.of(pending))).isEmpty();
    verify(consultantRepository, times(0)).findAllById(any());
    verify(adminRepository, times(0)).findAllByIdIn(anySet());
  }

  private static AccountInvite accepted(long id, String accountId, AccountInviteTargetRole role) {
    return AccountInvite.builder()
        .id(id)
        .targetRole(role)
        .status(AccountInviteStatus.ACCEPTED)
        .provisionedUserId(accountId)
        .acceptedAt(LocalDateTime.now())
        .build();
  }

  private static Consultant consultant(String id) {
    Consultant consultant = new Consultant();
    consultant.setId(id);
    return consultant;
  }

  private static Admin admin(String id, AdminType type) {
    Admin admin = new Admin();
    admin.setId(id);
    admin.setType(type);
    return admin;
  }
}
