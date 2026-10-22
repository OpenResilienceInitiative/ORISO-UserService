package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.model.AccountInvite;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

/**
 * The generic public accept fallback cannot choose a protected native identity. Dedicated protected
 * registration and its provenance-bound resume are owned by the recipient coordinator separately.
 */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=FIVE_ACTORS")
class ProtectedInvitationGenericAcceptanceJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  private static final String TOKEN = "A".repeat(43);
  private static final String CALLER_CHOSEN_SUBJECT = "4775e840-038d-435e-a25c-3f9d416d43a6";

  @ParameterizedTest
  @EnumSource(value = AccountInviteTargetRole.class, names = {"TENANT_ADMIN", "PLATFORM_ADMIN"})
  void aGenericCallerCannotClaimAProtectedInvitationWithItsOwnSubject(AccountInviteTargetRole role) {
    var sent = deliveredRow(role);

    assertThatThrownBy(() -> service.acceptInvite(TOKEN, CALLER_CHOSEN_SUBJECT))
        .isInstanceOf(AccessDeniedException.class);

    var current = invites.findById(sent.getId()).orElseThrow();
    assertThat(current.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(current.getAcceptedByUserId()).isNull();
    assertThat(current.getAcceptedAt()).isNull();
    assertThat(current.getTokenHash()).isEqualTo(AccountInviteService.hash(TOKEN));
    assertThat(current.getEmailVerificationStatus()).isEqualTo(EmailVerificationStatus.PENDING);
    verifyNoInteractions(approvals, sender);
  }

  @Test
  void aProtectedPendingTwoFactorResumeCannotBypassTheDedicatedRecipientCoordinator() {
    var accepted = deliveredRow(AccountInviteTargetRole.TENANT_ADMIN);
    accepted.setStatus(AccountInviteStatus.ACCEPTED);
    accepted.setAcceptedByUserId("6ae75be6-d23e-48be-a413-2be06d122868");
    accepted.setAcceptedAt(LocalDateTime.now().withNano(0));
    accepted.setTwoFactorStatus(TwoFactorGateStatus.PENDING_SETUP);
    invites.saveAndFlush(accepted);

    assertThatThrownBy(() -> service.acceptInvite(TOKEN, CALLER_CHOSEN_SUBJECT))
        .isInstanceOf(AccessDeniedException.class);

    var current = invites.findById(accepted.getId()).orElseThrow();
    assertThat(current.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(current.getAcceptedByUserId()).isEqualTo(accepted.getAcceptedByUserId());
    assertThat(current.getAcceptedAt()).isEqualTo(accepted.getAcceptedAt());
    assertThat(current.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.PENDING_SETUP);
    verifyNoInteractions(approvals, sender);
  }

  @Test
  void scenarioCOrdinaryInvitationKeepsTheExistingGenericAcceptance() {
    var sent = deliveredRow(AccountInviteTargetRole.AGENCY_ADMIN);

    var accepted = assertDoesNotThrow(() -> service.acceptInvite(TOKEN, CALLER_CHOSEN_SUBJECT));

    assertThat(accepted.getId()).isEqualTo(sent.getId());
    assertThat(accepted.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(accepted.getAcceptedByUserId()).isEqualTo(CALLER_CHOSEN_SUBJECT);
    assertThat(invites.findById(sent.getId()).orElseThrow().getEmailVerificationStatus())
        .isEqualTo(EmailVerificationStatus.VERIFIED);
    verifyNoInteractions(approvals, sender);
  }

  private AccountInvite deliveredRow(AccountInviteTargetRole role) {
    // Local delivered-link state is the input to this public acceptance seam. This setup does not
    // claim a protected send/native approval; those have independent owning SQL/native gates.
    var invite = service.createInvite(command());
    invite.setTargetRole(role);
    if (role == AccountInviteTargetRole.PLATFORM_ADMIN) {
      invite.setTenantId(0L);
      invite.setTenantIdReservationToken(null);
      invite.setTenantIdAllocationMode(null);
    } else if (role == AccountInviteTargetRole.AGENCY_ADMIN) {
      invite.setAgencyId(23L);
    }
    invite.setStatus(AccountInviteStatus.EMAIL_SENT);
    invite.setTokenHash(AccountInviteService.hash(TOKEN));
    invite.setExpiresAt(LocalDateTime.now().plusDays(1).withNano(0));
    return invites.saveAndFlush(invite);
  }
}

/** A real legacy send/token remains acceptable through the historical generic path. */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=LEGACY")
class ProtectedInvitationGenericAcceptanceLegacyJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  @Test
  void legacyProtectedInvitationKeepsAcceptanceAndPendingTwoFactorResume() {
    var sent = assertDoesNotThrow(() -> service.createAndSendInvite(command(), template.getId()));
    var subject = "5040be83-8145-4359-86e3-15dc5ed7f882";

    var accepted = assertDoesNotThrow(() -> service.acceptInvite(sent.rawToken(), subject));
    var resumed = assertDoesNotThrow(() -> service.acceptInvite(sent.rawToken(), subject));

    assertThat(accepted.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(accepted.getAcceptedByUserId()).isEqualTo(subject);
    assertThat(resumed.getId()).isEqualTo(accepted.getId());
    assertThat(resumed.getAcceptedByUserId()).isEqualTo(subject);
    assertThat(resumed.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.PENDING_SETUP);
    verifyNoInteractions(approvals);
  }
}
