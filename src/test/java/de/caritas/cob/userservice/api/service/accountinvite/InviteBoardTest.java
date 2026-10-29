package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InviteBoardTest {
  @Mock AccountInviteService service;
  @Mock InviteEmailDeliveryRepository deliveries;
  @Mock UnitQueue queue;
  @InjectMocks InviteBoard board;

  @Test
  void existingTenantAdminBelongsOnlyOnTenantTab() {
    var invite = invite(1L, "Max", AccountInviteTargetRole.TENANT_ADMIN);
    invite.setTenantIdAllocationMode(IdAllocationMode.EXISTING);
    when(service.listAllInvites(null, null, "Max")).thenReturn(List.of(invite));
    assertThat(
            board
                .list(InviteBoard.Tab.TENANT, null, null, null, null, "Max", 0, 1)
                .rows()
                .getTotalElements())
        .isEqualTo(1);
    assertThat(
            board
                .list(InviteBoard.Tab.UNIT, null, null, null, null, "Max", 0, 1)
                .rows()
                .getTotalElements())
        .isZero();
  }

  @Test
  void invitedAtSortAndStableRecipientFallbackPrecedeSlicing() {
    var a = invite(1L, "", AccountInviteTargetRole.COUNSELLOR);
    a.setLastName("");
    a.setRecipientEmail("beta@example.org");
    var b = invite(2L, "alpha", AccountInviteTargetRole.COUNSELLOR);
    b.setCreateDate(a.getCreateDate().minusDays(1));
    var c = invite(3L, "Alpha", AccountInviteTargetRole.COUNSELLOR);
    c.setCreateDate(b.getCreateDate());
    when(service.listAllInvites(null, null, null)).thenReturn(List.of(a, c, b));
    var phases = java.util.Set.of(InviteProgress.Phase.INVITED);
    assertThat(
            board
                .list(null, null, null, phases, null, null, 0, 1, "recipient", "asc")
                .rows()
                .map(row -> row.invite().getId()))
        .containsExactly(2L);
    assertThat(
            board
                .list(null, null, null, phases, null, null, 1, 1, "recipient", "asc")
                .rows()
                .map(row -> row.invite().getId()))
        .containsExactly(3L);
    assertThat(
            board
                .list(null, null, null, phases, null, null, 0, 1, "invitedAt", "desc")
                .rows()
                .map(row -> row.invite().getId()))
        .containsExactly(1L);
    assertThat(
            board
                .list(null, null, null, phases, null, null, 0, 1, "invitedAt", "asc")
                .rows()
                .map(row -> row.invite().getId()))
        .containsExactly(2L);
    assertThat(
            board
                .list(null, null, null, phases, null, null, 0, 1, null, "asc")
                .rows()
                .map(row -> row.invite().getId()))
        .containsExactly(1L);
  }

  private AccountInvite invite(Long id, String first, AccountInviteTargetRole role) {
    return AccountInvite.builder()
        .id(id)
        .firstName(first)
        .lastName("Mustermann")
        .recipientEmail(first + "@example.org")
        .targetRole(role)
        .status(AccountInviteStatus.EMAIL_SENT)
        .createDate(LocalDateTime.now())
        .build();
  }
}
