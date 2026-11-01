package de.caritas.cob.userservice.api.model;

import static de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsValues.DEFAULT_CALL_DELETE_AFTER_MINUTES;
import static de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsValues.DEFAULT_CALL_MAX_LIFETIME_HOURS;
import static de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsValues.DEFAULT_CHAT_INACTIVITY_DAYS;
import static de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsValues.DEFAULT_MESSAGE_RETENTION;

import de.caritas.cob.userservice.api.service.temporaryaccount.MessageRetentionMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The one platform-wide row of temporary-account settings (ADR-029 §4–§5). Values only apply to
 * accounts created afterwards; each account keeps its own copy ({@link TemporaryAccountSnapshot}).
 */
@Entity
@Table(name = "temporary_account_settings")
@Getter
@Setter
@NoArgsConstructor
public class TemporaryAccountSettings {

  public static final long SINGLETON_ID = 1L;

  @Id
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "call_delete_after_minutes", nullable = false)
  private int callDeleteAfterMinutes;

  @Column(name = "call_max_lifetime_hours", nullable = false)
  private int callMaxLifetimeHours;

  @Column(name = "chat_inactivity_days", nullable = false)
  private int chatInactivityDays;

  @Column(name = "hide_password_field", nullable = false)
  private boolean hidePasswordField;

  @Enumerated(EnumType.STRING)
  @Column(name = "message_retention", nullable = false, length = 32)
  private MessageRetentionMode messageRetention;

  @Column(name = "revision", nullable = false)
  private long revision;

  @Column(name = "update_date")
  private LocalDateTime updateDate;

  @Column(name = "updated_by", length = 36)
  private String updatedBy;

  public static TemporaryAccountSettings defaults() {
    var settings = new TemporaryAccountSettings();
    settings.setId(SINGLETON_ID);
    settings.setCallDeleteAfterMinutes(DEFAULT_CALL_DELETE_AFTER_MINUTES);
    settings.setCallMaxLifetimeHours(DEFAULT_CALL_MAX_LIFETIME_HOURS);
    settings.setChatInactivityDays(DEFAULT_CHAT_INACTIVITY_DAYS);
    settings.setHidePasswordField(false);
    settings.setMessageRetention(DEFAULT_MESSAGE_RETENTION);
    settings.setRevision(0);
    return settings;
  }
}
