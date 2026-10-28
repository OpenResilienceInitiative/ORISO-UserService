package de.caritas.cob.userservice.api.service.temporaryaccount;

import static de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsValues.MAX_CALL_DELETE_AFTER_MINUTES;
import static de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsValues.MAX_CALL_LIFETIME_HOURS;
import static de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsValues.MAX_CHAT_INACTIVITY_DAYS;

import de.caritas.cob.userservice.api.model.TemporaryAccountSettings;
import de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot;
import de.caritas.cob.userservice.api.port.out.TemporaryAccountSettingsRepository;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the platform settings for temporary accounts (ADR-029 §4–§5). */
@Service
@RequiredArgsConstructor
public class TemporaryAccountSettingsService {

  private final @NonNull TemporaryAccountSettingsRepository repository;

  @Transactional(readOnly = true)
  public TemporaryAccountSettingsValues current() {
    return toValues(
        repository
            .findById(TemporaryAccountSettings.SINGLETON_ID)
            .orElseGet(TemporaryAccountSettings::defaults));
  }

  /** Saves when {@code revision} matches the stored one; the result carries the next revision. */
  @Transactional
  public TemporaryAccountSettingsValues update(
      TemporaryAccountSettingsValues values, String updatedBy) {
    validate(values);
    var stored =
        repository
            .findLockedById(TemporaryAccountSettings.SINGLETON_ID)
            .orElseGet(TemporaryAccountSettings::defaults);
    if (stored.getRevision() != values.revision()) {
      throw new StaleRevisionException();
    }
    stored.setCallDeleteAfterMinutes(values.callDeleteAfterMinutes());
    stored.setCallMaxLifetimeHours(values.callMaxLifetimeHours());
    stored.setChatInactivityDays(values.chatInactivityDays());
    stored.setHidePasswordField(values.hidePasswordField());
    stored.setMessageRetention(values.messageRetention());
    stored.setRevision(stored.getRevision() + 1);
    stored.setUpdateDate(LocalDateTime.now(ZoneOffset.UTC));
    stored.setUpdatedBy(updatedBy);
    return toValues(repository.saveAndFlush(stored));
  }

  /** The copy a new temporary account keeps for its whole life. */
  @Transactional(readOnly = true)
  public TemporaryAccountSnapshot snapshotForNewAccount(
      InviteLinkChatType medium, Long inviteLinkId) {
    var settings = current();
    return TemporaryAccountSnapshot.builder()
        .medium(medium)
        .inviteLinkId(inviteLinkId)
        .callDeleteAfterMinutes(settings.callDeleteAfterMinutes())
        .callMaxLifetimeHours(settings.callMaxLifetimeHours())
        .chatInactivityDays(settings.chatInactivityDays())
        .messageRetention(settings.messageRetention())
        .build();
  }

  private static void validate(TemporaryAccountSettingsValues values) {
    if (values == null) {
      throw new InvalidSettingsException("Settings are required");
    }
    requireRange(
        "callDeleteAfterMinutes",
        values.callDeleteAfterMinutes(),
        1,
        MAX_CALL_DELETE_AFTER_MINUTES);
    requireRange("callMaxLifetimeHours", values.callMaxLifetimeHours(), 1, MAX_CALL_LIFETIME_HOURS);
    requireRange("chatInactivityDays", values.chatInactivityDays(), 1, MAX_CHAT_INACTIVITY_DAYS);
    if (values.hidePasswordField() == null) {
      throw new InvalidSettingsException("hidePasswordField is required");
    }
    if (values.messageRetention() == null) {
      throw new InvalidSettingsException("messageRetention is required");
    }
    if (values.revision() == null || values.revision() < 0) {
      throw new InvalidSettingsException("revision is required");
    }
  }

  private static void requireRange(String field, Integer value, int min, int max) {
    if (value == null || value < min || value > max) {
      throw new InvalidSettingsException(
          String.format("%s must be between %d and %d", field, min, max));
    }
  }

  private static TemporaryAccountSettingsValues toValues(TemporaryAccountSettings settings) {
    return new TemporaryAccountSettingsValues(
        settings.getCallDeleteAfterMinutes(),
        settings.getCallMaxLifetimeHours(),
        settings.getChatInactivityDays(),
        settings.isHidePasswordField(),
        settings.getMessageRetention(),
        settings.getRevision());
  }

  public static class InvalidSettingsException extends RuntimeException {
    public InvalidSettingsException(String message) {
      super(message);
    }
  }

  public static class StaleRevisionException extends RuntimeException {
    public StaleRevisionException() {
      super("Reload the current settings before saving");
    }
  }
}
