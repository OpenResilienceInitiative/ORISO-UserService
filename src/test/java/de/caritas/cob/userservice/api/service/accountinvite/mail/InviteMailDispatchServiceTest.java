package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InviteMailDispatchServiceTest {
  @Mock private InviteMailTransport inviteMailTransport;
  @Mock private EmailBrandingResolver emailBrandingResolver;

  private InviteMailDispatchService service(String username, String password) {
    return new InviteMailDispatchService(
        new PlatformSmtpSettingsProvider(
            "smtp.example.org", "587", "false", username, password, "noreply@example.org", false),
        inviteMailTransport,
        InviteFrameMailRendererFixture.inviteFrameMailRenderer(emailBrandingResolver));
  }

  private void givenNeutralBranding() {
    when(emailBrandingResolver.resolve(any())).thenReturn(EmailBranding.neutral());
  }

  @Test
  void sendReturnsReceiptFromDeploymentOwnedServer() {
    givenNeutralBranding();
    InviteMailSendReceipt receipt = new InviteMailSendReceipt("to@example.org", Instant.now());
    when(inviteMailTransport.send(any(), any(), any(), any(), any())).thenReturn(receipt);

    assertThat(service("smtp-user", "smtp-pass").send("to@example.org", "subject", "body"))
        .isSameAs(receipt);
    verify(inviteMailTransport)
        .send(
            eq(
                new InviteSmtpSettings(
                    "smtp.example.org",
                    587,
                    false,
                    "smtp-user",
                    "smtp-pass",
                    "noreply@example.org")),
            eq("to@example.org"),
            eq("subject"),
            anyString(),
            anyString());
  }

  @Test
  void sendWrapsAuthoredBodyInBrandedHtmlAndPlainText() {
    givenNeutralBranding();
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("to@example.org", Instant.now()));

    service("smtp-user", "smtp-pass")
        .send(
            "to@example.org",
            "Ihre Einladung",
            "Hallo Ada, bitte bestaetigen Sie Ihr Konto.",
            "https://app.oriso.org/account-invite/tok",
            null,
            "de");

    ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
    verify(inviteMailTransport)
        .send(any(), eq("to@example.org"), eq("Ihre Einladung"), html.capture(), plain.capture());
    assertThat(html.getValue())
        .startsWith("<!DOCTYPE html>")
        .contains("Hallo Ada, bitte bestaetigen Sie Ihr Konto.")
        .contains("https://app.oriso.org/account-invite/tok");
    assertThat(plain.getValue())
        .doesNotContain("<table")
        .contains("Hallo Ada, bitte bestaetigen Sie Ihr Konto.")
        .contains("https://app.oriso.org/account-invite/tok");
  }

  @Test
  void tenantBrandingDoesNotComeFromTransportSettings() {
    givenNeutralBranding();
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("to@example.org", Instant.now()));

    service("smtp-user", "smtp-pass").send("to@example.org", "subject", "body", null, 42L, null);

    verify(emailBrandingResolver).resolve(42L);
  }

  @Test
  void missingDeploymentCredentialsStopBeforeAnyTransportCall() {
    assertThatThrownBy(() -> service("", "").send("to@example.org", "subject", "body"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("SMTP_USER")
        .hasMessageContaining("SMTP_PASSWORD")
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            exception ->
                assertThat(exception.getCategory())
                    .isEqualTo(SmtpSendException.Category.SMTP_DISABLED_OR_INCOMPLETE));
    verifyNoInteractions(inviteMailTransport);
  }

  @Test
  void transportFailurePropagatesWithoutRetry() {
    givenNeutralBranding();
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenThrow(new SmtpSendException("handover failed", new RuntimeException("io")));

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("handover failed");
  }

  @Test
  void renderingFailureIsConfirmedNotSent() {
    when(emailBrandingResolver.resolve(any()))
        .thenThrow(new IllegalStateException("branding unavailable"));

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            exception -> {
              assertThat(exception.getDeliveryDisposition())
                  .isEqualTo(SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT);
              assertThat(exception).hasCauseInstanceOf(IllegalStateException.class);
            });
    verifyNoInteractions(inviteMailTransport);
  }

  @Test
  void unexpectedTransportFailureKeepsDeliveryUncertain() {
    givenNeutralBranding();
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("connection disappeared"));

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            exception ->
                assertThat(exception.getDeliveryDisposition())
                    .isEqualTo(SmtpSendException.DeliveryDisposition.DELIVERY_UNCERTAIN));
  }
}
