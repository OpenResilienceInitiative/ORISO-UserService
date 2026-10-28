package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import java.util.function.Supplier;
import org.slf4j.Logger;

/** Bounded operator diagnostics: never emit dependency exception text, payloads or credentials. */
final class NotificationEmailDiagnostics {
  enum Stage {
    ELIGIBILITY,
    IDENTITY,
    MATRIX_MEMBERSHIP,
    MATRIX_AUTHENTICATION,
    MATRIX_EVENT,
    TENANT_POLICY,
    TENANT_SMTP,
    PLATFORM_SMTP,
    TENANT_CONTEXT,
    TEMPLATE,
    SMTP_HANDOFF
  }

  enum Reason {
    DEPENDENCY_UNAVAILABLE,
    CONFIGURATION_INVALID,
    TENANT_SETTINGS_MISSING,
    NOTIFICATION_POLICY_INVALID,
    SMTP_MODE_INVALID,
    OWN_SMTP_MISSING,
    OWN_SMTP_INCOMPLETE,
    OWN_SMTP_DISABLED,
    OWN_SMTP_INVALID,
    PLATFORM_SMTP_UNCONFIGURED,
    TENANT_UNAVAILABLE,
    TENANT_SUBDOMAIN_MISSING,
    BASE_URL_INVALID
  }

  static class Failure extends IllegalStateException {
    private final Stage stage;
    private final Reason reason;

    Failure(Stage stage, Reason reason, String message) {
      super(message);
      this.stage = stage;
      this.reason = reason;
    }
  }

  private NotificationEmailDiagnostics() {}

  static Failure failure(Stage stage, Reason reason) {
    return new Failure(stage, reason, reason.name());
  }

  static <T> T at(Stage stage, Supplier<T> operation) {
    try {
      return operation.get();
    } catch (Failure classified) {
      throw classified;
    } catch (RuntimeException unavailable) {
      throw failure(stage, Reason.DEPENDENCY_UNAVAILABLE);
    }
  }

  static void retry(
      Logger log, String occasion, long id, Stage fallback, RuntimeException failure) {
    Stage stage = fallback;
    Reason reason = Reason.DEPENDENCY_UNAVAILABLE;
    if (failure instanceof Failure classified) {
      stage = classified.stage;
      reason = classified.reason;
    } else if (failure instanceof PlatformSmtpSettingsProvider.ConfigurationException) {
      stage = Stage.PLATFORM_SMTP;
      reason = Reason.PLATFORM_SMTP_UNCONFIGURED;
    }
    log.warn(
        "Notification email {} delivery {} retry stage={} reason={}", occasion, id, stage, reason);
  }
}
