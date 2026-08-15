package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Contract of the shared 2FA guard both onboarding flows delegate to (#1030 review).
 *
 * <p>The service-level tests prove that each flow CALLS the guard at the right moment and against
 * the right database state; these tests pin what the guard itself answers, so the two flows cannot
 * drift apart in the outcome either.
 */
class OnboardingTwoFactorGuardTest {

  private static final String ACCEPTOR_ID = "b2b2d4de-0d2a-4e1e-8f7a-3f4d9f0f1c22";
  private static final String PENDING_SECRET = "PENDINGSECRET";

  @Test
  void revalidateTwoFactorGate_unchangedResumableRow_passes() {
    assertDoesNotThrow(
        () ->
            OnboardingTwoFactorGuard.revalidateTwoFactorGate(
                resumableRow(), ACCEPTOR_ID, PENDING_SECRET));
  }

  @Test
  void revalidateTwoFactorGate_rowWithoutExpiry_passes() {
    AccountInvite row = resumableRow();
    row.setExpiresAt(null);

    assertDoesNotThrow(
        () -> OnboardingTwoFactorGuard.revalidateTwoFactorGate(row, ACCEPTOR_ID, PENDING_SECRET));
  }

  @ParameterizedTest
  @CsvSource({
    "REVOKED,REVOKED",
    "SUPERSEDED,SUPERSEDED",
    "EXPIRED,EXPIRED",
    "EMAIL_SENT,NOT_ACTIVE",
    "DRAFT,NOT_ACTIVE"
  })
  void revalidateTwoFactorGate_statusLeftAccepted_answersTheRowsOwnLinkDeathReason(
      AccountInviteStatus status, AccountInviteLinkException.Reason expectedReason) {
    AccountInvite row = resumableRow();
    row.setStatus(status);

    var exception =
        assertThrows(
            AccountInviteLinkException.class,
            () ->
                OnboardingTwoFactorGuard.revalidateTwoFactorGate(row, ACCEPTOR_ID, PENDING_SECRET));

    assertEquals(expectedReason, exception.getReason());
  }

  /** Every status that satisfies the gate means another path already consumed the link. */
  @ParameterizedTest
  @EnumSource(
      value = TwoFactorGateStatus.class,
      names = {"ACTIVE", "WAIVED", "NOT_REQUIRED", "DISABLED_BY_POLICY"})
  void revalidateTwoFactorGate_gateSatisfiedInTheMeantime_answersConsumed(
      TwoFactorGateStatus satisfied) {
    AccountInvite row = resumableRow();
    row.setTwoFactorStatus(satisfied);

    var exception =
        assertThrows(
            AccountInviteLinkException.class,
            () ->
                OnboardingTwoFactorGuard.revalidateTwoFactorGate(row, ACCEPTOR_ID, PENDING_SECRET));

    assertEquals(AccountInviteLinkException.Reason.CONSUMED, exception.getReason());
  }

  @Test
  void revalidateTwoFactorGate_resumeWindowClosedInTheMeantime_answersConsumed() {
    AccountInvite row = resumableRow();
    row.setExpiresAt(LocalDateTime.now().minusMinutes(5));

    var exception =
        assertThrows(
            AccountInviteLinkException.class,
            () ->
                OnboardingTwoFactorGuard.revalidateTwoFactorGate(row, ACCEPTOR_ID, PENDING_SECRET));

    // An ACCEPTED row is never flipped to EXPIRED, so the answer stays CONSUMED — exactly what
    // the precondition check of both activation endpoints gives for the same row.
    assertEquals(AccountInviteLinkException.Reason.CONSUMED, exception.getReason());
  }

  @Test
  void revalidateTwoFactorGate_differentAcceptor_answers400() {
    AccountInvite row = resumableRow();
    row.setAcceptedByUserId("6f6c2d5e-9a1b-4c7d-8e2f-0a1b2c3d4e5f");

    assertThrows(
        BadRequestException.class,
        () -> OnboardingTwoFactorGuard.revalidateTwoFactorGate(row, ACCEPTOR_ID, PENDING_SECRET));
  }

  @Test
  void revalidateTwoFactorGate_rotatedPendingSecret_answers400() {
    AccountInvite row = resumableRow();
    row.setTotpPendingSecret("ROTATEDSECRET");

    assertThrows(
        BadRequestException.class,
        () -> OnboardingTwoFactorGuard.revalidateTwoFactorGate(row, ACCEPTOR_ID, PENDING_SECRET));
  }

  /** A cleared secret must not match a caller that carries none either — null is not "anything". */
  @Test
  void revalidateTwoFactorGate_clearedPendingSecret_answers400() {
    AccountInvite row = resumableRow();
    row.setTotpPendingSecret(null);

    assertThrows(
        BadRequestException.class,
        () -> OnboardingTwoFactorGuard.revalidateTwoFactorGate(row, ACCEPTOR_ID, PENDING_SECRET));
  }

  @Test
  void isResumableAtTwoFactorStep_acceptedPendingAndUnexpired_isTrue() {
    assertTrue(
        OnboardingTwoFactorGuard.isResumableAtTwoFactorStep(resumableRow(), LocalDateTime.now()));
  }

  @Test
  void isResumableAtTwoFactorStep_expiryExactlyNow_staysInsideTheWindow() {
    LocalDateTime now = LocalDateTime.now();
    AccountInvite row = resumableRow();
    row.setExpiresAt(now);

    assertTrue(OnboardingTwoFactorGuard.isResumableAtTwoFactorStep(row, now));
  }

  @Test
  void isResumableAtTwoFactorStep_notAccepted_isFalse() {
    AccountInvite row = resumableRow();
    row.setStatus(AccountInviteStatus.EMAIL_SENT);

    assertFalse(OnboardingTwoFactorGuard.isResumableAtTwoFactorStep(row, LocalDateTime.now()));
  }

  /** An invite that waits at the mandatory 2FA step: consumed, gate pending, link still alive. */
  private static AccountInvite resumableRow() {
    return AccountInvite.builder()
        .id(8L)
        .status(AccountInviteStatus.ACCEPTED)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .acceptedByUserId(ACCEPTOR_ID)
        .totpPendingSecret(PENDING_SECRET)
        .expiresAt(LocalDateTime.now().plusDays(1))
        .build();
  }
}
