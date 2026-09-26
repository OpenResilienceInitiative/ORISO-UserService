package de.caritas.cob.userservice.api.service.email;

import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment-owned SMTP settings for platform mail. Tenant SMTP has a separate owner. */
@Component
public class PlatformSmtpSettingsProvider {
  private final String host;
  private final String port;
  private final String secure;
  private final String username;
  private final String password;
  private final String from;
  private final boolean requiredAtStartup;

  public PlatformSmtpSettingsProvider(
      @Value("${smtp.host:}") String host,
      @Value("${smtp.port:}") String port,
      @Value("${smtp.secure:}") String secure,
      @Value("${smtp.user:}") String username,
      @Value("${smtp.password:}") String password,
      @Value("${smtp.from:}") String from,
      @Value("${smtp.required:false}") boolean requiredAtStartup) {
    this.host = host;
    this.port = port;
    this.secure = secure;
    this.username = username;
    this.password = password;
    this.from = from;
    this.requiredAtStartup = requiredAtStartup;
  }

  @PostConstruct
  void validateAtStartup() {
    if (requiredAtStartup) {
      requireConfigured();
    }
  }

  public Settings requireConfigured() {
    List<String> missing = new ArrayList<>();
    if (blank(host)) missing.add("smtp.host (SMTP_HOST)");
    if (blank(username)) missing.add("smtp.user (SMTP_USER)");
    if (blank(password)) missing.add("smtp.password (SMTP_PASSWORD)");
    if (blank(from)) missing.add("smtp.from (SMTP_FROM)");
    else {
      try {
        new InternetAddress(from.trim(), true).validate();
      } catch (AddressException exception) {
        missing.add("smtp.from (SMTP_FROM)");
      }
    }

    Integer parsedPort = null;
    try {
      parsedPort = Integer.valueOf(blank(port) ? "" : port.trim());
      if (parsedPort < 1 || parsedPort > 65535) missing.add("smtp.port (SMTP_PORT)");
    } catch (NumberFormatException exception) {
      missing.add("smtp.port (SMTP_PORT)");
    }
    if (blank(secure)
        || (!"true".equalsIgnoreCase(secure.trim()) && !"false".equalsIgnoreCase(secure.trim()))) {
      missing.add("smtp.secure (SMTP_SECURE)");
    }

    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Platform SMTP is not configured: " + String.join(", ", missing));
    }
    return new Settings(
        host.trim(),
        parsedPort,
        Boolean.parseBoolean(secure.trim()),
        username.trim(),
        password,
        from.trim());
  }

  /** Safe, deployment-derived display values for a platform administrator. */
  public Summary summary() {
    boolean configured;
    try {
      requireConfigured();
      configured = true;
    } catch (IllegalStateException exception) {
      configured = false;
    }
    Integer displayPort = null;
    try {
      int value = Integer.parseInt(port == null ? "" : port.trim());
      if (value >= 1 && value <= 65535) displayPort = value;
    } catch (NumberFormatException ignored) {
      // The summary remains readable while an optional local installation is incomplete.
    }
    Boolean displaySecure = null;
    if ("true".equalsIgnoreCase(secure == null ? "" : secure.trim())) {
      displaySecure = true;
    } else if ("false".equalsIgnoreCase(secure == null ? "" : secure.trim())) {
      displaySecure = false;
    }
    return new Summary(
        blank(host) ? null : host.trim(),
        displayPort,
        displaySecure,
        blank(from) ? null : from.trim(),
        configured,
        !blank(username) && !blank(password));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record Settings(
      String host, int port, boolean secure, String username, String password, String from) {
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
