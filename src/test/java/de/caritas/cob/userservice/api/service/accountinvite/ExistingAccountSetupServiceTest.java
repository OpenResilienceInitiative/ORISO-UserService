package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordChangeRequirement;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class ExistingAccountSetupServiceTest {

  @Mock AccountInviteRepository invites;
  @Mock AdminRepository admins;
  @Mock ConsultantRepository consultants;
  @Mock IdentityProfileLookup identities;
  @Mock IdentityRoleLookup roles;
  @Mock IdentityPasswordChangeRequirement passwordChangeRequirement;
  @Mock InitialPasswordVerifier initialPasswords;
  @Mock IdentityPasswordUpdater passwords;
  @Mock PlatformTransactionManager transactions;
  @InjectMocks ExistingAccountSetupService service;

  @BeforeEach
  void transactionBoundary() {
    lenient()
        .when(transactions.getTransaction(any()))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
  }

  @Test
  void changesOnlyTheBoundExistingAdminAndConsumesTheClaimOnce() {
    var invite = setupInvite();
    givenLiveAdmin();
    when(invites.claimExistingAccountSetup(eq(11L), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(invites.completeExistingAccountSetup(
            eq(11L), any(), any(), any(), any(), any(), any(), eq("admin-11"), any()))
        .thenReturn(1);

    service.confirm("mailed-token", "new-secret");

    verify(passwords).updatePassword("admin-11", "new-secret");
    verify(initialPasswords).matches("new-secret", "salted-verifier");
    verify(invites)
        .completeExistingAccountSetup(
            eq(11L),
            eq(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP),
            any(),
            any(),
            any(),
            any(),
            any(),
            eq("admin-11"),
            any());
  }

  @Test
  void encodedCounsellorUsernameMatchesTheSameEncodedKeycloakIdentity() {
    String encoded = new UsernameTranscoder().encodeUsername("counsellor@example.org");
    givenLiveCounsellor(encoded, encoded);

    service.requireSameCurrentIdentity(
        "consultant-11",
        AccountInviteTargetRole.COUNSELLOR,
        42L,
        "counsellor@example.org",
        encoded);
  }

  @Test
  void lowercaseEncodedKeycloakUsernameMatchesTheSameCounsellor() {
    String encoded = new UsernameTranscoder().encodeUsername("counsellor@example.org");
    givenLiveCounsellor(encoded, encoded.toLowerCase(java.util.Locale.ROOT));

    service.requireSameCurrentIdentity(
        "consultant-11",
        AccountInviteTargetRole.COUNSELLOR,
        42L,
        "counsellor@example.org",
        encoded);
  }

  @Test
  void rawAdminUsernameMatchesEncodedKeycloakIdentity() {
    givenLiveAdmin(List.of("tenant-admin"));
    when(identities.findById("admin-11"))
        .thenReturn(
            Optional.of(
                new IdentityProfile(
                    "admin-11",
                    new UsernameTranscoder().encodeUsername("admin@example.org"),
                    null,
                    null,
                    "admin@example.org")));

    service.requireSameCurrentIdentity(
        "admin-11",
        AccountInviteTargetRole.TENANT_ADMIN,
        42L,
        "admin@example.org",
        "admin@example.org");
  }

  @Test
  void rawAdminUsernameMatchesRawKeycloakIdentity() {
    givenLiveAdmin();

    service.requireSameCurrentIdentity(
        "admin-11",
        AccountInviteTargetRole.TENANT_ADMIN,
        42L,
        "admin@example.org",
        "admin@example.org");
  }

  @Test
  void changedKeycloakUsernameCannotAuthorizeCounsellorSetup() {
    String encoded = new UsernameTranscoder().encodeUsername("counsellor@example.org");
    String other = new UsernameTranscoder().encodeUsername("other@example.org");
    givenLiveCounsellor(encoded, other);

    assertThatThrownBy(
            () ->
                service.requireSameCurrentIdentity(
                    "consultant-11",
                    AccountInviteTargetRole.COUNSELLOR,
                    42L,
                    "counsellor@example.org",
                    encoded))
        .isInstanceOf(ConflictException.class);
    verify(passwords, never()).updatePassword(any(), any());
  }

  @Test
  void malformedEncodedCounsellorUsernameCannotAuthorizeSetup() {
    givenLiveCounsellor("enc.***", "enc.***");

    assertThatThrownBy(
            () ->
                service.requireSameCurrentIdentity(
                    "consultant-11",
                    AccountInviteTargetRole.COUNSELLOR,
                    42L,
                    "counsellor@example.org",
                    "enc.***"))
        .isInstanceOf(ConflictException.class);
    verify(passwords, never()).updatePassword(any(), any());
  }

  @Test
  void overlappingConfirmationThatLostTheClaimNeverTouchesThePassword() {
    var invite = setupInvite();
    when(invites.claimExistingAccountSetup(eq(11L), any(), any(), any(), any(), any()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.confirm("mailed-token", "new-secret"))
        .isInstanceOf(ConflictException.class);
    verify(passwords, never()).updatePassword(any(), any());
  }

  @Test
  void expiredLinkNeverClaimsOrChangesTheIdentity() {
    var invite = setupInvite();
    invite.setExpiresAt(LocalDateTime.now().minusSeconds(1));

    assertThatThrownBy(() -> service.confirm("mailed-token", "new-secret"))
        .isInstanceOfSatisfying(
            AccountInviteLinkException.class,
            failure ->
                assertThat(failure.getReason())
                    .isEqualTo(AccountInviteLinkException.Reason.EXPIRED));
    verify(invites, never()).claimExistingAccountSetup(any(), any(), any(), any(), any(), any());
    verify(passwords, never()).updatePassword(any(), any());
  }

  @Test
  void persistedDeadLinkStatesRetainTheirNamedPublicReason() {
    var invite = setupInvite();
    var expected =
        java.util.Map.of(
            AccountInviteStatus.EXPIRED, AccountInviteLinkException.Reason.EXPIRED,
            AccountInviteStatus.REVOKED, AccountInviteLinkException.Reason.REVOKED,
            AccountInviteStatus.SUPERSEDED, AccountInviteLinkException.Reason.SUPERSEDED);
    expected.forEach(
        (status, reason) -> {
          invite.setStatus(status);
          assertThatThrownBy(() -> service.confirm("mailed-token", "new-secret"))
              .isInstanceOfSatisfying(
                  AccountInviteLinkException.class,
                  failure -> assertThat(failure.getReason()).isEqualTo(reason));
        });
    verify(invites, never()).claimExistingAccountSetup(any(), any(), any(), any(), any(), any());
    verify(passwords, never()).updatePassword(any(), any());
  }

  @Test
  void changedSavedEmailInvalidatesTheLinkBeforePasswordMutation() {
    setupInvite();
    when(invites.claimExistingAccountSetup(eq(11L), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(admins.findByIdAndType("admin-11", Admin.AdminType.TENANT))
        .thenReturn(Optional.of(admin("different@example.org")));

    assertThatThrownBy(() -> service.confirm("mailed-token", "new-secret"))
        .isInstanceOf(AccountInviteLinkException.class);
    verify(invites)
        .revokeStaleExistingAccountSetup(
            eq(11L), any(), any(), any(), any(), eq("SETUP_IDENTITY_CHANGED"), any());
    verify(passwords, never()).updatePassword(any(), any());
  }

  @Test
  void removedCurrentKeycloakRoleInvalidatesTheLinkBeforePasswordMutation() {
    setupInvite();
    givenLiveAdmin(List.of());
    when(invites.claimExistingAccountSetup(eq(11L), any(), any(), any(), any(), any()))
        .thenReturn(1);

    assertThatThrownBy(() -> service.confirm("mailed-token", "new-secret"))
        .isInstanceOf(AccountInviteLinkException.class);
    verify(invites)
        .revokeStaleExistingAccountSetup(
            eq(11L), any(), any(), any(), any(), eq("SETUP_IDENTITY_CHANGED"), any());
    verify(passwords, never()).updatePassword(any(), any());
  }

  @Test
  void normalPasswordResetRemovesTemporaryRequirementAndInvalidatesOldSetupLink() {
    setupInvite();
    givenLiveAdmin();
    when(passwordChangeRequirement.requiresPasswordChange("admin-11")).thenReturn(false);
    when(invites.claimExistingAccountSetup(eq(11L), any(), any(), any(), any(), any()))
        .thenReturn(1);

    assertThatThrownBy(() -> service.confirm("mailed-token", "new-secret"))
        .isInstanceOfSatisfying(
            AccountInviteLinkException.class,
            failure ->
                assertThat(failure.getReason())
                    .isEqualTo(AccountInviteLinkException.Reason.REVOKED));
    verify(invites)
        .revokeStaleExistingAccountSetup(
            eq(11L), any(), any(), any(), any(), eq("SETUP_TEMPORARY_PASSWORD_REPLACED"), any());
    verify(passwords, never()).updatePassword(any(), any());
  }

  @Test
  void uncertainProviderOutcomeKeepsTheClaimAndRecordsAnOperatorReason() {
    var invite = setupInvite();
    givenLiveAdmin();
    when(invites.claimExistingAccountSetup(eq(11L), any(), any(), any(), any(), any()))
        .thenReturn(1);
    doThrow(new IllegalStateException("provider detail"))
        .when(passwords)
        .updatePassword("admin-11", "new-secret");

    assertThatThrownBy(() -> service.confirm("mailed-token", "new-secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("provider detail");
    verify(invites)
        .recordIndeterminateExistingAccountSetup(
            eq(11L), any(), any(), any(), eq("SETUP_OUTCOME_INDETERMINATE"), any());
    verify(invites, never())
        .releaseExistingAccountSetup(any(), any(), any(), any(), any(), any(), any());
    verify(invites, never())
        .completeExistingAccountSetup(
            any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void finalisationFailureAfterProviderSuccessRetainsTheClaimForOperatorReview() {
    setupInvite();
    givenLiveAdmin();
    when(invites.claimExistingAccountSetup(eq(11L), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(invites.completeExistingAccountSetup(
            eq(11L), any(), any(), any(), any(), any(), any(), eq("admin-11"), any()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.confirm("mailed-token", "new-secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("operator review");
    verify(passwords).updatePassword("admin-11", "new-secret");
    verify(invites)
        .recordIndeterminateExistingAccountSetup(
            eq(11L), any(), any(), any(), eq("SETUP_OUTCOME_INDETERMINATE"), any());
    verify(invites, never())
        .releaseExistingAccountSetup(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void administratorKnownInitialPasswordIsRejectedBeforeKeycloakMutation() {
    var invite = setupInvite();
    givenLiveAdmin();
    when(invites.claimExistingAccountSetup(eq(11L), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(initialPasswords.matches("same-secret", "salted-verifier")).thenReturn(true);

    assertThatThrownBy(() -> service.confirm("mailed-token", "same-secret"))
        .hasMessageContaining("different from the initial password");
    verify(invites)
        .releaseExistingAccountSetup(
            eq(11L), any(), any(), any(), any(), eq("SETUP_PASSWORD_UNCHANGED"), any());
    verify(passwords, never()).updatePassword(any(), any());
  }

  @Test
  void indeterminateClaimRejectsReplayWithANamedRecoveryReason() {
    var invite = setupInvite();
    invite.setProvisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS);
    invite.setProvisioningFailureReason("SETUP_OUTCOME_INDETERMINATE");

    assertThatThrownBy(() -> service.confirm("mailed-token", "another-secret"))
        .isInstanceOfSatisfying(
            AccountInviteLinkException.class,
            failure ->
                org.assertj.core.api.Assertions.assertThat(failure.getReason())
                    .isEqualTo(AccountInviteLinkException.Reason.SETUP_OPERATOR_REVIEW_REQUIRED));
    verify(passwords, never()).updatePassword(any(), any());
  }

  private AccountInvite setupInvite() {
    AccountInvite invite =
        AccountInvite.builder()
            .id(11L)
            .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
            .status(AccountInviteStatus.EMAIL_SENT)
            .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .tenantId(42L)
            .recipientEmail("admin@example.org")
            .provisionedUserId("admin-11")
            .setupBoundUsername("admin@example.org")
            .initialPasswordVerifier("salted-verifier")
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
    when(invites.findByTokenHash(any())).thenReturn(Optional.of(invite));
    return invite;
  }

  private void givenLiveAdmin() {
    givenLiveAdmin(List.of("tenant-admin"));
  }

  private void givenLiveAdmin(List<String> currentRoles) {
    when(admins.findByIdAndType("admin-11", Admin.AdminType.TENANT))
        .thenReturn(Optional.of(admin("admin@example.org")));
    when(identities.findById("admin-11"))
        .thenReturn(
            Optional.of(
                new IdentityProfile(
                    "admin-11", "admin@example.org", null, null, "admin@example.org")));
    when(roles.findAllByUserId("admin-11")).thenReturn(currentRoles);
    lenient().when(passwordChangeRequirement.requiresPasswordChange("admin-11")).thenReturn(true);
  }

  private void givenLiveCounsellor(String storedUsername, String keycloakUsername) {
    when(consultants.findByIdAndDeleteDateIsNull("consultant-11"))
        .thenReturn(
            Optional.of(
                Consultant.builder()
                    .id("consultant-11")
                    .tenantId(42L)
                    .username(storedUsername)
                    .firstName("C")
                    .lastName("D")
                    .email("counsellor@example.org")
                    .build()));
    when(identities.findById("consultant-11"))
        .thenReturn(
            Optional.of(
                new IdentityProfile(
                    "consultant-11", keycloakUsername, null, null, "counsellor@example.org")));
    lenient().when(roles.findAllByUserId("consultant-11")).thenReturn(List.of("consultant"));
    lenient()
        .when(passwordChangeRequirement.requiresPasswordChange("consultant-11"))
        .thenReturn(true);
  }

  private Admin admin(String email) {
    return Admin.builder()
        .id("admin-11")
        .type(Admin.AdminType.TENANT)
        .tenantId(42L)
        .username("admin@example.org")
        .email(email)
        .firstName("A")
        .lastName("D")
        .build();
  }
}
