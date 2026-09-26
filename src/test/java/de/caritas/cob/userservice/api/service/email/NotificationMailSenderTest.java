package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailDelivery;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationMailSenderTest {
  @Mock NotificationMailComposer composer;
  @Mock TenantSystemEmailRouteService routes;
  @Mock TenantSystemEmailDelivery delivery;
  @InjectMocks NotificationMailSender sender;

  private final TenantSystemEmailRouteService.Route platform =
      new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
  private final OrisoEmailRenderer.RenderedEmail rendered =
      new OrisoEmailRenderer.RenderedEmail("Subject", "<p>Body</p>", "Body");

  @Test
  void sendsThroughTheVerifiedRecipientTenant() {
    var mail = mail("enquiry-notification-consultant", "7", "7");
    when(routes.resolve(7L)).thenReturn(Optional.of(platform));
    when(composer.compose(mail, 7L)).thenReturn(rendered);
    when(delivery.sendConfirmed(
            7L,
            platform,
            TenantSystemEmailDelivery.Purpose.NEW_ENQUIRY,
            "recipient@example.org",
            rendered))
        .thenReturn(true);

    assertThat(sender.send(mail)).isTrue();

    verify(delivery)
        .sendConfirmed(
            7L,
            platform,
            TenantSystemEmailDelivery.Purpose.NEW_ENQUIRY,
            "recipient@example.org",
            rendered);
  }

  @Test
  void rejectsAnotherTenantBeforeRenderingOrDelivery() {
    assertThatThrownBy(() -> sender.send(mail("enquiry-notification-consultant", "7", "8")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenants differ");

    verify(composer, never()).compose(any(), eq(7L));
    verify(delivery, never()).sendConfirmed(anyLong(), any(), any(), any(), any());
  }

  @Test
  void skipsOnlyAnExplicitlyDisabledTenant() {
    var mail = mail("daily-enquiry-notification", "7", "7");
    when(routes.resolve(7L)).thenReturn(Optional.empty());

    assertThat(sender.send(mail)).isFalse();

    verify(composer, never()).compose(any(), eq(7L));
  }

  @Test
  void reportsPlatformSmtpFailure() {
    var mail = mail("direct-enquiry-notification-consultant", "7", "7");
    when(routes.resolve(7L)).thenReturn(Optional.of(platform));
    when(composer.compose(mail, 7L)).thenReturn(rendered);

    assertThatThrownBy(() -> sender.send(mail))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP send failed");
  }

  @Test
  void rejectsMissingRecipientTenantInsteadOfAssumingTheRequestTenant() {
    var mail = mail("assign-enquiry-notification", "7", "7");
    mail.getTemplateData().removeIf(item -> "recipientTenantId".equals(item.getKey()));

    assertThatThrownBy(() -> sender.send(mail))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recipientTenantId");
  }

  private static MailDTO mail(String template, String requestTenant, String recipientTenant) {
    return new MailDTO()
        .template(template)
        .email("recipient@example.org")
        .templateData(
            new java.util.ArrayList<>(
                List.of(
                    new TemplateDataDTO().key("tenantId").value(requestTenant),
                    new TemplateDataDTO().key("recipientTenantId").value(recipientTenant))));
  }
}
