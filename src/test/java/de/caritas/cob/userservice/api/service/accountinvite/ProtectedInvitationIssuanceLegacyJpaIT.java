package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Same actual public/JPA graph under the existing LEGACY model. */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=LEGACY")
class ProtectedInvitationIssuanceLegacyJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  @Test
  void legacyProtectedRoleKeepsExistingCommittedSendAndSentAudit() {
    var result = service.createAndSendInvite(command(), template.getId());

    assertThat(result.invite().getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(result.invite().getTokenHash()).isNotBlank();
    assertThat(result.delivery().getStatus()).isEqualTo(InviteEmailDeliveryStatus.SENT);
    assertThat(result.rawToken()).isNotBlank();
    verify(sender).send(any(), any(), any(), any(), any(), any(), any());
    verifyNoInteractions(approvals);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM protected_invitation_authorization", Integer.class)).isZero();
  }
}
