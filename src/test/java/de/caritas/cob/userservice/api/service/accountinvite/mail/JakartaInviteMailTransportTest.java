package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class JakartaInviteMailTransportTest {

  /**
   * Hardening (bug ORISO-Admin#569 follow-up): without {@code mail.smtp.starttls.required} a
   * malicious or misconfigured SMTP server that does not offer STARTTLS silently downgrades the
   * session to plaintext — including the AUTH exchange, i.e. the operator SMTP credentials.
   */
  @Test
  void buildSessionProperties_Should_requireStartTls_When_settingsNotSecure() {
    Properties properties = JakartaInviteMailTransport.buildSessionProperties(insecureSettings());

    assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
  }

  /** The negotiated TLS certificate must match the configured host (MITM defense in depth). */
  @Test
  void buildSessionProperties_Should_checkServerIdentity_When_settingsNotSecure() {
    Properties properties = JakartaInviteMailTransport.buildSessionProperties(insecureSettings());

    assertThat(properties.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
  }

  @Test
  void buildSessionProperties_Should_enableImplicitTlsAndCheckServerIdentity_When_settingsSecure() {
    Properties properties = JakartaInviteMailTransport.buildSessionProperties(secureSettings());

    assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
    // Implicit TLS sessions must not also announce STARTTLS.
    assertThat(properties.getProperty("mail.smtp.starttls.enable")).isNull();
  }

  @Test
  void buildSessionProperties_Should_keepConnectionBasicsAndTimeouts() {
    Properties properties = JakartaInviteMailTransport.buildSessionProperties(insecureSettings());

    assertThat(properties.getProperty("mail.smtp.auth")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.host")).isEqualTo("mail.example.org");
    assertThat(properties.getProperty("mail.smtp.port")).isEqualTo("587");
    assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
    assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("10000");
    assertThat(properties.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
  }

  private static InviteSmtpSettings insecureSettings() {
    return new InviteSmtpSettings(
        "mail.example.org", 587, false, "user", "secret", "noreply@example.org");
  }

  private static InviteSmtpSettings secureSettings() {
    return new InviteSmtpSettings(
        "mail.example.org", 465, true, "user", "secret", "noreply@example.org");
  }
}
