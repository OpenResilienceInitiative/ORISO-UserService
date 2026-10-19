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
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailDelivery.Purpose;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InviteMailDispatchServiceTest {
  private static final InviteMailOrigin PLATFORM_INVITE =
      InviteMailOrigin.platform(Purpose.ACCOUNT_INVITE);

  @Mock private InviteMailTransport inviteMailTransport;
  @Mock private EmailBrandingResolver emailBrandingResolver;

  private InviteMailDispatchService service(String username, String password) {
    return new InviteMailDispatchService(
        de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsFixture.configured(
            username, password),
        inviteMailTransport,
        InviteFrameMailRendererFixture.inviteFrameMailRenderer(emailBrandingResolver),
        TenantMailRoutingFixture.platformRoutes(),
        TenantMailRoutingFixture.unusedRelay());
  }

  private void givenNeutralBranding() {
    when(emailBrandingResolver.resolvePendingTenant(any()))
        .thenReturn(
            new EmailBranding(
                "ORISO",
                null,
                "#a5000a",
                "https://app.example.org/impressum",
                "https://app.example.org/datenschutz"));
  }

  @Test
  void sendReturnsReceiptFromAdminSettings() {
    givenNeutralBranding();
    InviteMailSendReceipt receipt = new InviteMailSendReceipt("to@example.org", Instant.now());
    when(inviteMailTransport.send(any(), any(), any(), any(), any())).thenReturn(receipt);

    assertThat(
            service("smtp-user", "smtp-pass")
                .send("to@example.org", "subject", "body", null, null, null, PLATFORM_INVITE))
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
            "https://app.example.org/account-invite/tok",
            null,
            "de",
            PLATFORM_INVITE);

    ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
    verify(inviteMailTransport)
        .send(any(), eq("to@example.org"), eq("Ihre Einladung"), html.capture(), plain.capture());
    assertThat(html.getValue())
        .startsWith("<!DOCTYPE html>")
        .contains("Hallo Ada, bitte bestaetigen Sie Ihr Konto.")
        .contains("https://app.example.org/account-invite/tok");
    assertThat(plain.getValue())
        .doesNotContain("<table")
        .contains("Hallo Ada, bitte bestaetigen Sie Ihr Konto.")
        .contains("https://app.example.org/account-invite/tok");
  }

  @Test
  void tenantBrandingDoesNotComeFromTransportSettings() {
    givenNeutralBranding();
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("to@example.org", Instant.now()));

    service("smtp-user", "smtp-pass")
        .send(
            "to@example.org",
            "subject",
            "body",
            null,
            42L,
            null,
            InviteMailOrigin.of(42L, Purpose.ACCOUNT_INVITE));

    verify(emailBrandingResolver).resolvePendingTenant(42L);
  }

  @Test
  void missingAdminCredentialsStopBeforeAnyTransportCall() {
    assertThatThrownBy(
            () ->
                service("", "")
                    .send("to@example.org", "subject", "body", null, null, null, PLATFORM_INVITE))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("SMTP username")
        .hasMessageContaining("SMTP password")
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

    assertThatThrownBy(
            () ->
                service("u", "p")
                    .send("to@example.org", "s", "b", null, null, null, PLATFORM_INVITE))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("handover failed");
  }

  @Test
  void renderingFailureIsConfirmedNotSent() {
    when(emailBrandingResolver.resolvePendingTenant(any()))
        .thenThrow(new IllegalStateException("branding unavailable"));

    assertThatThrownBy(
            () ->
                service("u", "p")
                    .send("to@example.org", "s", "b", null, null, null, PLATFORM_INVITE))
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

    assertThatThrownBy(
            () ->
                service("u", "p")
                    .send("to@example.org", "s", "b", null, null, null, PLATFORM_INVITE))
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            exception ->
                assertThat(exception.getDeliveryDisposition())
                    .isEqualTo(SmtpSendException.DeliveryDisposition.DELIVERY_UNCERTAIN));
  }
}
