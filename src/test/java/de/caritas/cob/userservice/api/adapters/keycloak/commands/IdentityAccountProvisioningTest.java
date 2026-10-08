package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class IdentityAccountProvisioningTest {
  @Test
  void localRollbackPersistsAnOwnedIntentBeforeFailedProviderCompensationAndRetainsRetryState() {
    var commands = org.mockito.Mockito.mock(IdentityProvisioningCommands.class);
    var journal = org.mockito.Mockito.mock(IdentityCreationJournalWriter.class);
    var row = new de.caritas.cob.userservice.api.model.IdentityCreationAttempt();
    row.setId(java.util.UUID.randomUUID().toString());
    row.setAccountId("new-account");
    row.setCreationProof("owned-proof");
    row.setOriginKind("INVITATION");
    row.setRegistrationKind("CONSULTANT");
    row.setTenantId(42L);
    row.setInitialRoles("consultant");
    row.setProvenance("invite:7");
    row.setStatus("OPEN");
    org.mockito.Mockito.when(journal.ownedAttempt("new-account")).thenReturn(row);
    org.mockito.Mockito.doThrow(new IllegalStateException("provider unavailable"))
        .when(commands)
        .compensate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    new IdentityAccountProvisioning(commands, journal, mock(IdentityCreationEffects.class))
        .compensateForLocalRollback("new-account");
    var order = org.mockito.Mockito.inOrder(journal, commands);
    order.verify(journal).ownedAttempt("new-account");
    order
        .verify(journal)
        .requestAfterSaga(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("COMPENSATION_REQUESTED"));
    order
        .verify(commands)
        .compensate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(journal, org.mockito.Mockito.never())
        .finish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void FailedCompensationKeepsReceiptAndIntentForSafeRetryWithoutGeneralAccountDelete() {
    var commands = mock(IdentityProvisioningCommands.class);
    var journal = mock(IdentityCreationJournalWriter.class);
    var service =
        new IdentityAccountProvisioning(commands, journal, mock(IdentityCreationEffects.class));
    var origin = origin();
    var receipt =
        new KeycloakTaskCommands.CreationResult(
            UUID.randomUUID(), "new-account", "opaque-own-attempt-proof", "OPEN");
    doThrow(new IllegalStateException("identity unavailable"))
        .doNothing()
        .when(commands)
        .compensate(eq(receipt), any());

    assertThatThrownBy(() -> service.compensate(receipt, origin))
        .isInstanceOf(IllegalStateException.class);
    verify(journal).requestAfterSaga(eq(receipt), eq(origin), eq("COMPENSATION_REQUESTED"));
    verify(journal, never()).finish(any(), any());
    service.compensate(receipt, origin);
    verify(journal).finish(receipt, "COMPENSATED");
    verify(commands, times(2)).compensate(eq(receipt), any());
    verifyNoMoreInteractions(commands);
  }

  @Test
  void InitialCommandCannotUseTenantOrRolesOutsideTheVerifiedInvitation() {
    var commands = mock(IdentityProvisioningCommands.class);
    var journal = mock(IdentityCreationJournalWriter.class);
    var service =
        new IdentityAccountProvisioning(commands, journal, mock(IdentityCreationEffects.class));
    var payload =
        new KeycloakTaskCommands.AccountCreation(
            "new",
            "new@example.org",
            null,
            null,
            "de",
            999L,
            "secret",
            false,
            List.of("tenant-admin"),
            "CONSULTANT");
    assertThatThrownBy(() -> service.create(UUID.randomUUID(), payload, origin()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(commands, journal);
  }

  @Test
  void setupReadIsExactToTheOriginalHumanCreatorAndCapturedInitialScope() {
    var commands = mock(IdentityProvisioningCommands.class);
    var journal = mock(IdentityCreationJournalWriter.class);
    var service =
        new IdentityAccountProvisioning(commands, journal, mock(IdentityCreationEffects.class));
    var row = creationRow();
    row.setOriginKind("HUMAN_ADMIN");
    row.setProvenance("human-admin:creator");
    when(journal.ownedAttempt("new-account")).thenReturn(row);
    var jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("verified-human")
            .header("alg", "RS256")
            .subject("creator")
            .claim("tenantId", "42")
            .claim("realm_access", Map.of("roles", List.of("consultant-create")))
            .build();
    var authentication =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtAuthenticationToken(
            jwt,
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "AUTHORIZATION_CONSULTANT_CREATE")));
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(authentication);
    try {
      service.setupProjection("new-account");
      verify(commands)
          .ownedRead(
              eq("new-account"),
              argThat(
                  proof ->
                      "HUMAN_ADMIN".equals(proof.originKind())
                          && "account.read".equals(proof.operation())
                          && "new-account".equals(proof.target())
                          && "42".equals(proof.tenantId())
                          && proof.roles().equals(List.of("consultant"))));
      clearInvocations(commands);
      row.setProvenance("human-admin:another-creator");
      assertThatThrownBy(() -> service.setupProjection("new-account"))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      row.setProvenance("human-admin:creator");
      row.setStatus("COMPENSATED");
      assertThatThrownBy(() -> service.setupProjection("new-account"))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      verifyNoInteractions(commands);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  @Test
  void heldInvitationRelationsRejectAnotherAccountTenantAndTerminalInvite() {
    var commands = mock(IdentityProvisioningCommands.class);
    var journal = mock(IdentityCreationJournalWriter.class);
    var service =
        new IdentityAccountProvisioning(commands, journal, mock(IdentityCreationEffects.class));
    var invites = mock(de.caritas.cob.userservice.api.port.out.AccountInviteRepository.class);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "invites", invites);
    var row = creationRow();
    row.setAuthorizedAgencyIds("700");
    when(journal.ownedAttempt("new-account")).thenReturn(row);
    var invite =
        AccountInvite.builder()
            .id(7L)
            .tenantId(42L)
            .agencyId(700L)
            .provisionedUserId("new-account")
            .status(AccountInviteStatus.EMAIL_SENT)
            .provisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
    when(invites.findById(7L)).thenReturn(Optional.of(invite));
    assertThat(service.heldCreationInvitation("new-account")).contains(invite);
    invite.setProvisionedUserId("foreign-account");
    assertThatThrownBy(() -> service.heldCreationInvitation("new-account"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    invite.setProvisionedUserId("new-account");
    invite.setTenantId(999L);
    assertThatThrownBy(() -> service.heldCreationInvitation("new-account"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    invite.setTenantId(42L);
    invite.setStatus(AccountInviteStatus.ACCEPTED);
    assertThatThrownBy(() -> service.heldCreationInvitation("new-account"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(commands);
  }

  private static IdentityCreationAttempt creationRow() {
    var row = new IdentityCreationAttempt();
    row.setId(UUID.randomUUID().toString());
    row.setAccountId("new-account");
    row.setCreationProof("owned-proof");
    row.setOriginKind("INVITATION");
    row.setRegistrationKind("CONSULTANT");
    row.setTenantId(42L);
    row.setInitialRoles("consultant");
    row.setProvenance("invite:7");
    row.setStatus("OPEN");
    return row;
  }

  private static IdentityCreationOrigin origin() {
    var invite =
        AccountInvite.builder()
            .id(7L)
            .tenantId(42L)
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .purpose(AccountInvitePurpose.INVITE)
            .status(AccountInviteStatus.EMAIL_SENT)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
    return IdentityCreationOrigin.heldInvitation(
        invite, IdentityCreationOrigin.Kind.CONSULTANT, List.of("consultant"));
  }
}
