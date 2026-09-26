package de.caritas.cob.userservice.api.service.email;

import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_ASSIGN_ENQUIRY_NOTIFICATION;
import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_DAILY_ENQUIRY_NOTIFICATION;
import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_NEW_DIRECT_ENQUIRY_NOTIFICATION;
import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_NEW_ENQUIRY_NOTIFICATION;

import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailDelivery;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Renders and sends one counselling notification through the recipient tenant's explicit route. */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationMailSender {
  private final @NonNull NotificationMailComposer composer;
  private final @NonNull TenantSystemEmailRouteService routes;
  private final @NonNull TenantSystemEmailDelivery delivery;

  public static boolean supports(String template) {
    return TEMPLATE_NEW_ENQUIRY_NOTIFICATION.equals(template)
        || TEMPLATE_NEW_DIRECT_ENQUIRY_NOTIFICATION.equals(template)
        || TEMPLATE_ASSIGN_ENQUIRY_NOTIFICATION.equals(template)
        || TEMPLATE_DAILY_ENQUIRY_NOTIFICATION.equals(template);
  }

  /** Returns false only when notifications are explicitly disabled for this tenant. */
  public boolean send(MailDTO mail) {
    if (mail == null) {
      throw new IllegalArgumentException("Notification mail is missing");
    }
    var purpose = purposeOf(mail.getTemplate());
    List<TemplateDataDTO> data = mail.getTemplateData();
    long recipientTenantId = tenantId(data, "recipientTenantId");
    long requestTenantId = tenantId(data, "tenantId");
    if (recipientTenantId != requestTenantId) {
      throw new IllegalArgumentException("Notification recipient and request tenants differ");
    }
    var route = routes.resolve(recipientTenantId);
    if (route.isEmpty()) {
      log.info("Notification {} disabled for tenant {}", purpose, recipientTenantId);
      return false;
    }
    var rendered = composer.compose(mail, recipientTenantId);
    if (!delivery.sendConfirmed(
        recipientTenantId, route.get(), purpose, mail.getEmail(), rendered)) {
      throw new IllegalStateException("Notification platform SMTP send failed");
    }
    return true;
  }

  private static long tenantId(List<TemplateDataDTO> data, String key) {
    if (data == null) {
      throw new IllegalArgumentException("Notification tenant metadata is missing: " + key);
    }
    String value = null;
    for (TemplateDataDTO item : data) {
      if (item != null && key.equals(item.getKey())) {
        if (value != null) {
          throw new IllegalArgumentException("Duplicate notification tenant metadata: " + key);
        }
        value = item.getValue();
      }
    }
    try {
      long id = Long.parseLong(value);
      if (id > 0) {
        return id;
      }
    } catch (NumberFormatException ignored) {
      // The named configuration error below is more useful than parse details.
    }
    throw new IllegalArgumentException("Notification tenant metadata is missing: " + key);
  }

  private static TenantSystemEmailDelivery.Purpose purposeOf(String template) {
    if (template == null) {
      throw new IllegalArgumentException("Notification occasion is missing");
    }
    return switch (template) {
      case TEMPLATE_NEW_ENQUIRY_NOTIFICATION -> TenantSystemEmailDelivery.Purpose.NEW_ENQUIRY;
      case TEMPLATE_NEW_DIRECT_ENQUIRY_NOTIFICATION ->
          TenantSystemEmailDelivery.Purpose.DIRECT_ENQUIRY;
      case TEMPLATE_ASSIGN_ENQUIRY_NOTIFICATION ->
          TenantSystemEmailDelivery.Purpose.ENQUIRY_ASSIGNED;
      case TEMPLATE_DAILY_ENQUIRY_NOTIFICATION ->
          TenantSystemEmailDelivery.Purpose.DAILY_ENQUIRY_DIGEST;
      default ->
          throw new IllegalArgumentException("Unsupported notification occasion: " + template);
    };
  }
}
