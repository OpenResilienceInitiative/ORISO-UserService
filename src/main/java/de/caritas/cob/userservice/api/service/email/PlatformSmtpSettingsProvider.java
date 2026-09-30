package de.caritas.cob.userservice.api.service.email;

import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reads one coherent platform SMTP snapshot from Admin Settings via the technical identity. */
@Component
@RequiredArgsConstructor
public class PlatformSmtpSettingsProvider {
  private final @NonNull ApplicationSettingsService applicationSettingsService;

  public Settings requireConfigured() {
    return requireConfigured(applicationSettingsService);
  }

  public static Settings requireConfigured(ApplicationSettingsService applicationSettingsService) {
    ApplicationSettingsSmtpCredentialsDTO source =
        applicationSettingsService
            .getGlobalSmtpSettingsSnapshot()
            .orElseThrow(
                () ->
                    new ConfigurationException(
                        "Platform SMTP is not configured in Admin Settings: SMTP settings are missing"));

    return requireConfigured(source);
  }

  private static Settings requireConfigured(ApplicationSettingsSmtpCredentialsDTO source) {

    List<String> missing = new ArrayList<>();
    if (!Boolean.TRUE.equals(source.getGlobalFeatureSystemNotificationEmailsEnabled()))
      missing.add("system notification emails enabled");
    if (!Boolean.TRUE.equals(source.getGlobalSmtpEnabled())) missing.add("SMTP enabled");
    if (blank(source.getGlobalSmtpHost())) missing.add("SMTP host");
    if (blank(source.getGlobalSmtpUsername())) missing.add("SMTP username");
    if (blank(source.getGlobalSmtpPassword())) missing.add("SMTP password");
    if (blank(source.getGlobalSmtpFrom())) missing.add("SMTP sender");
    else {
      try {
        new InternetAddress(source.getGlobalSmtpFrom().trim(), true).validate();
      } catch (AddressException exception) {
        missing.add("SMTP sender");
      }
    }

    Integer port = null;
    try {
      port = Integer.valueOf(source.getGlobalSmtpPort().trim());
      if (port < 1 || port > 65535) missing.add("SMTP port");
    } catch (NullPointerException | NumberFormatException exception) {
      missing.add("SMTP port");
    }
    if (source.getGlobalSmtpSecure() == null) missing.add("SMTP security mode");

    if (!missing.isEmpty()) {
      throw new ConfigurationException(
          "Platform SMTP is incomplete in Admin Settings: " + String.join(", ", missing));
    }
    return new Settings(
        source.getGlobalSmtpHost().trim(),
        port,
        source.getGlobalSmtpSecure(),
        source.getGlobalSmtpUsername().trim(),
        source.getGlobalSmtpPassword(),
        source.getGlobalSmtpFrom().trim(),
        source.getGlobalSmtpEmailThemeColor());
  }

  /** Safe fields from one saved Admin Settings snapshot for a platform administrator. */
  public Summary summary() {
    ApplicationSettingsSmtpCredentialsDTO source =
        applicationSettingsService.getGlobalSmtpSettingsSnapshot().orElse(null);
    if (source == null) {
      return new Summary(null, null, null, null, false, false);
    }
    boolean configured;
    try {
      requireConfigured(source);
      configured = true;
    } catch (ConfigurationException exception) {
      configured = false;
    }
    Integer displayPort = null;
    try {
      int value = Integer.parseInt(source.getGlobalSmtpPort().trim());
      if (value >= 1 && value <= 65535) displayPort = value;
    } catch (NullPointerException | NumberFormatException ignored) {
      // Saved settings may be incomplete; the other safe fields are still useful.
    }
    return new Summary(
        blank(source.getGlobalSmtpHost()) ? null : source.getGlobalSmtpHost().trim(),
        displayPort,
        source.getGlobalSmtpSecure(),
        blank(source.getGlobalSmtpFrom()) ? null : source.getGlobalSmtpFrom().trim(),
        configured,
        !blank(source.getGlobalSmtpUsername()) && !blank(source.getGlobalSmtpPassword()));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  /** Only provider-owned, value-free validation errors may be shown to an administrator. */
  public static class ConfigurationException extends IllegalStateException {
    public ConfigurationException(String message) {
      super(message);
    }
  }

  public record Settings(
      String host,
      int port,
      boolean secure,
      String username,
      String password,
      String from,
      String emailThemeColor) {
    @Override
    public String toString() {
      return "PlatformSmtpSettings[host=" + host + ", port=" + port + ", secure=" + secure + "]";
    }
  }

  public record Summary(
      String host,
      Integer port,
      Boolean secure,
      String from,
      boolean configured,
      boolean credentialsPresent) {}
}
