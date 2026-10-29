package de.caritas.cob.userservice.api.service.temporaryaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.TemporaryAccountSettings;
import de.caritas.cob.userservice.api.port.out.TemporaryAccountSettingsRepository;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsService.InvalidSettingsException;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsService.StaleRevisionException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemporaryAccountSettingsServiceTest {

  @Mock private TemporaryAccountSettingsRepository repository;
  @InjectMocks private TemporaryAccountSettingsService service;

  private static TemporaryAccountSettingsValues values(
      Integer afterMinutes,
      Integer maxHours,
      Integer chatDays,
      MessageRetentionMode mode,
      long rev) {
    return new TemporaryAccountSettingsValues(afterMinutes, maxHours, chatDays, false, mode, rev);
  }

  @Test
  void current_Should_ReturnTheDocumentedDefaults_When_NothingWasSavedYet() {
    when(repository.findById(TemporaryAccountSettings.SINGLETON_ID)).thenReturn(Optional.empty());

    var current = service.current();

    assertThat(current.callDeleteAfterMinutes()).isEqualTo(15);
    assertThat(current.callMaxLifetimeHours()).isEqualTo(24);
    assertThat(current.chatInactivityDays()).isEqualTo(30);
    assertThat(current.hidePasswordField()).isFalse();
    assertThat(current.messageRetention()).isEqualTo(MessageRetentionMode.GUEST_DECIDES);
    assertThat(current.revision()).isZero();
  }

  @Test
  void update_Should_SaveAndBumpTheRevision_When_TheRevisionMatches() {
    when(repository.findLockedById(TemporaryAccountSettings.SINGLETON_ID))
        .thenReturn(Optional.empty());
    when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var saved =
        service.update(values(10, 12, 7, MessageRetentionMode.ALWAYS_PERSISTENT, 0), "admin-1");

    assertThat(saved.callDeleteAfterMinutes()).isEqualTo(10);
    assertThat(saved.callMaxLifetimeHours()).isEqualTo(12);
    assertThat(saved.chatInactivityDays()).isEqualTo(7);
    assertThat(saved.messageRetention()).isEqualTo(MessageRetentionMode.ALWAYS_PERSISTENT);
    assertThat(saved.revision()).isEqualTo(1);
  }

  @Test
  void update_Should_Refuse_When_AnotherAdminSavedInBetween() {
    var stored = TemporaryAccountSettings.defaults();
    stored.setRevision(3);
    when(repository.findLockedById(TemporaryAccountSettings.SINGLETON_ID))
        .thenReturn(Optional.of(stored));

    assertThatThrownBy(
            () ->
                service.update(values(15, 24, 30, MessageRetentionMode.GUEST_DECIDES, 2), "admin"))
        .isInstanceOf(StaleRevisionException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void update_Should_RejectAMaximumAboveTheHardCapOf24Hours() {
    assertThatThrownBy(
            () ->
                service.update(values(15, 25, 30, MessageRetentionMode.GUEST_DECIDES, 0), "admin"))
        .isInstanceOf(InvalidSettingsException.class)
        .hasMessageContaining("callMaxLifetimeHours");
  }

  @Test
  void update_Should_RejectMissingOrNonPositiveValues() {
    assertThatThrownBy(
            () ->
                service.update(
                    values(null, 24, 30, MessageRetentionMode.GUEST_DECIDES, 0), "admin"))
        .isInstanceOf(InvalidSettingsException.class);
    assertThatThrownBy(
            () -> service.update(values(0, 24, 30, MessageRetentionMode.GUEST_DECIDES, 0), "admin"))
        .isInstanceOf(InvalidSettingsException.class);
    assertThatThrownBy(
            () ->
                service.update(values(15, 24, 366, MessageRetentionMode.GUEST_DECIDES, 0), "admin"))
        .isInstanceOf(InvalidSettingsException.class);
    assertThatThrownBy(() -> service.update(values(15, 24, 30, null, 0), "admin"))
        .isInstanceOf(InvalidSettingsException.class);
  }

  @Test
  void snapshotForNewAccount_Should_CopyTheCurrentValues() {
    var stored = TemporaryAccountSettings.defaults();
    stored.setCallDeleteAfterMinutes(5);
    stored.setMessageRetention(MessageRetentionMode.ALWAYS_TEMPORARY);
    when(repository.findById(TemporaryAccountSettings.SINGLETON_ID))
        .thenReturn(Optional.of(stored));

    var snapshot =
        service.snapshotForNewAccount(
            de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.VIDEO, 42L);

    assertThat(snapshot.getMedium())
        .isEqualTo(
            de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.VIDEO);
    assertThat(snapshot.getInviteLinkId()).isEqualTo(42L);
    assertThat(snapshot.getCallDeleteAfterMinutes()).isEqualTo(5);
    assertThat(snapshot.getCallMaxLifetimeHours()).isEqualTo(24);
    assertThat(snapshot.getChatInactivityDays()).isEqualTo(30);
    assertThat(snapshot.getMessageRetention()).isEqualTo(MessageRetentionMode.ALWAYS_TEMPORARY);
    assertThat(snapshot.getKeepMessages()).isNull();
    assertThat(snapshot.getDeletedAt()).isNull();
  }
}
