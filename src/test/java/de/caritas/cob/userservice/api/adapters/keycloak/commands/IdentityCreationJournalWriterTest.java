package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.IdentityCreationAttemptRepository;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class IdentityCreationJournalWriterTest {
  @Test
  void concurrentOpenSagaCannotObtainTheSameReceiptAndCompensateItsOtherOwner() {
    var repository = mock(IdentityCreationAttemptRepository.class);
    var row = row("OPEN");
    when(repository.findFirstByRequestKeyAndStatusInOrderByUpdateDateDesc(anyString(), any()))
        .thenReturn(Optional.of(row));
    assertThatThrownBy(
            () ->
                new IdentityCreationJournalWriter(repository)
                    .begin(UUID.randomUUID(), origin(), "new-user"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void LostCreateResponseRetryUsesPersistedAttemptInsteadOfCreatingAnotherAccountAttempt() {
    var repository = mock(IdentityCreationAttemptRepository.class);
    var row = row("CREATION_REQUESTED");
    row.setCreationProof(null);
    row.setAccountId(null);
    when(repository.findFirstByRequestKeyAndStatusInOrderByUpdateDateDesc(anyString(), any()))
        .thenReturn(Optional.of(row));
    var writer = new IdentityCreationJournalWriter(repository);
    assertThat(writer.begin(UUID.randomUUID(), origin(), "new-user").attemptId())
        .isEqualTo(UUID.fromString(row.getId()));
    verify(repository).saveAndFlush(row);
  }

  @Test
  void ForeignReceiptAndForeignTenantCannotPersistACompensationIntent() {
    var repository = mock(IdentityCreationAttemptRepository.class);
    var row = row("OPEN");
    when(repository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
    var writer = new IdentityCreationJournalWriter(repository);
    var foreign =
        new KeycloakTaskCommands.CreationResult(
            UUID.fromString(row.getId()), "foreign-account", "own-proof", "OPEN");
    assertThatThrownBy(() -> writer.request(foreign, origin(), "COMPENSATION_REQUESTED"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    var owned =
        new KeycloakTaskCommands.CreationResult(
            UUID.fromString(row.getId()), "new-account", "own-proof", "OPEN");
    var invite = invite();
    invite.setTenantId(999L);
    var foreignOrigin =
        IdentityCreationOrigin.heldInvitation(
            invite, IdentityCreationOrigin.Kind.CONSULTANT, List.of("consultant"));
    assertThatThrownBy(() -> writer.request(owned, foreignOrigin, "COMPENSATION_REQUESTED"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void CommittedAttemptCannotBeCompensatedAndFailedAttemptClearsOnlyItsReplayKey() {
    var repository = mock(IdentityCreationAttemptRepository.class);
    var row = row("COMMITTED");
    when(repository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
    var writer = new IdentityCreationJournalWriter(repository);
    var receipt =
        new KeycloakTaskCommands.CreationResult(
            UUID.fromString(row.getId()), "new-account", "own-proof", "OPEN");
    assertThatThrownBy(() -> writer.request(receipt, origin(), "COMPENSATION_REQUESTED"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    row.setStatus("COMPENSATION_REQUESTED");
    row.setRequestKey("retry-key");
    writer.finish(receipt, "COMPENSATED");
    assertThat(row.getStatus()).isEqualTo("COMPENSATED");
    assertThat(row.getRequestKey()).isNull();
    assertThat(row.getCreationProof()).isEqualTo("own-proof");
  }

  @Test
  void completedAttemptReleasesOnlyRequestReplayKeyAndRetainsOwnedHistory() {
    var repository = mock(IdentityCreationAttemptRepository.class);
    var row = row("COMMIT_REQUESTED");
    row.setRequestKey("stable-name-request");
    when(repository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
    var writer = new IdentityCreationJournalWriter(repository);
    var receipt =
        new KeycloakTaskCommands.CreationResult(
            UUID.fromString(row.getId()), "new-account", "own-proof", "OPEN");
    writer.finish(receipt, "COMMITTED");
    assertThat(row.getRequestKey()).isNull();
    assertThat(row.getStatus()).isEqualTo("COMMITTED");
    assertThat(row.getAccountId()).isEqualTo("new-account");
    assertThat(row.getCreationProof()).isEqualTo("own-proof");
    assertThatThrownBy(() -> writer.request(receipt, origin(), "COMPENSATION_REQUESTED"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  private static AccountInvite invite() {
    return AccountInvite.builder()
        .id(7L)
        .tenantId(42L)
        .targetRole(AccountInviteTargetRole.COUNSELLOR)
        .purpose(AccountInvitePurpose.INVITE)
        .status(AccountInviteStatus.EMAIL_SENT)
        .build();
  }

  private static IdentityCreationOrigin origin() {
    return IdentityCreationOrigin.heldInvitation(
        invite(), IdentityCreationOrigin.Kind.CONSULTANT, List.of("consultant"));
  }

  private static IdentityCreationAttempt row(String status) {
    var row = new IdentityCreationAttempt();
    row.setId(UUID.randomUUID().toString());
    row.setAccountId("new-account");
    row.setCreationProof("own-proof");
    row.setOriginKind("INVITATION");
    row.setRegistrationKind("CONSULTANT");
    row.setInitialRoles("consultant");
    row.setProvenance("invite:7");
    row.setTenantId(42L);
    row.setStatus(status);
    return row;
  }
}
