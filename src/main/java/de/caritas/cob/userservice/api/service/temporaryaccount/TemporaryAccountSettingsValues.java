package de.caritas.cob.userservice.api.service.temporaryaccount;

/**
 * Platform settings for temporary accounts (ADR-029 §3–§5), as read and written by the platform
 * admin. Boxed so a missing field is a validation error, not a silent zero.
 */
public record TemporaryAccountSettingsValues(
    Integer callDeleteAfterMinutes,
    Integer callMaxLifetimeHours,
    Integer chatInactivityDays,
    Boolean hidePasswordField,
    MessageRetentionMode messageRetention,
    Long revision) {

  public static final int DEFAULT_CALL_DELETE_AFTER_MINUTES = 15;
  public static final int DEFAULT_CALL_MAX_LIFETIME_HOURS = 24;
  public static final int DEFAULT_CHAT_INACTIVITY_DAYS = 30;
  public static final MessageRetentionMode DEFAULT_MESSAGE_RETENTION =
      MessageRetentionMode.GUEST_DECIDES;

  public static final int MAX_CALL_DELETE_AFTER_MINUTES = 1440;

  /** ADR-029 §4: hard cap for call accounts. */
  public static final int MAX_CALL_LIFETIME_HOURS = 24;

  public static final int MAX_CHAT_INACTIVITY_DAYS = 365;
}
