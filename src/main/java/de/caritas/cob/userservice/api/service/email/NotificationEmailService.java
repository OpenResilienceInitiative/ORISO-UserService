package de.caritas.cob.userservice.api.service.email;

import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.*;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.notification.SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Adapts existing notification producers to the installed SMTP transport. Recipient selection and
 * preferences stay in the producers; this boundary validates the whole batch before dispatch and
 * never reports acceptance when rendering, settings resolution or SMTP fails.
 */
@Service
@RequiredArgsConstructor
public class NotificationEmailService {
  private final OrisoEmailRenderer renderer;
  private final OrisoEmailBrand brand;
  private final GlobalSmtpSettingsResolver smtpSettingsResolver;
  private final OrisoEmailDispatcher dispatcher;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  public void send(MailsDTO mails) {
    if (mails == null || mails.getMails() == null) {
      throw new IllegalArgumentException("Notification mail batch is missing");
    }
    if (mails.getMails().isEmpty()) {
      return;
    }
    Long tenantId = TenantContext.getCurrentTenant();
    var deliveries =
        mails.getMails().stream()
            .map(
                mail -> {
                  var rendered = render(mail, tenantId);
                  return new Delivery(mail.getEmail(), rendered);
                })
            .toList();
    var smtp = smtpSettingsResolver.resolve();
    var settings =
        new SupervisorAddedEmailSettings(
            smtp.host(),
            smtp.port(),
            smtp.secure(),
            smtp.username(),
            smtp.password(),
            smtp.from(),
            null);
    int failures = 0;
    for (var delivery : deliveries) {
      try {
        dispatcher.sendOrThrow(settings, delivery.recipient(), delivery.email());
      } catch (SmtpSendException exception) {
        // A failed recipient must not suppress later recipients. Do not retain SMTP exception
        // details: providers may include addresses or other private message data in them.
        failures++;
      }
    }
    if (failures > 0) {
      throw new SmtpSendException(
          "Notification batch partially failed: " + failures + " of " + deliveries.size());
    }
  }

  private OrisoEmailRenderer.RenderedEmail render(MailDTO mail, Long tenantId) {
    if (mail == null || isBlank(mail.getEmail()) || isBlank(mail.getTemplate())) {
      throw new IllegalArgumentException("Notification recipient or occasion is missing");
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    if (mail.getTemplateData() != null) {
      mail.getTemplateData().stream()
          .filter(item -> item != null && item.getKey() != null && item.getValue() != null)
          .forEach(item -> attributes.put(item.getKey(), item.getValue()));
    }
    // Scheduler batches have no tenant request context and may contain several tenants. The
    // producer owns this metadata; resolve each mail independently instead of mutating thread
    // state.
    if (attributes.containsKey("tenantId")) {
      tenantId = Long.parseLong(attributes.get("tenantId"));
      if (tenantId < 0) {
        throw new IllegalArgumentException("Notification tenant id must not be negative");
      }
    }
    String appUrl = attributes.getOrDefault("url", applicationBaseUrl);
    if (isBlank(appUrl)) {
      throw new IllegalArgumentException("Notification application URL is missing");
    }
    var values = new LinkedHashMap<>(brand.valuesForTenant(appUrl, tenantId));
    boolean english =
        mail.getLanguage() != null && "en".equalsIgnoreCase(mail.getLanguage().toString());
    var tone = english ? OrisoEmailRenderer.Tone.EN : OrisoEmailRenderer.Tone.DE_FORMAL;
    String template =
        switch (mail.getTemplate()) {
          case TEMPLATE_NEW_ENQUIRY_NOTIFICATION -> "neue-anfrage";
          case TEMPLATE_NEW_DIRECT_ENQUIRY_NOTIFICATION -> "direkte-anfrage";
          case TEMPLATE_ASSIGN_ENQUIRY_NOTIFICATION -> "anfrage-zugewiesen";
          case TEMPLATE_DAILY_ENQUIRY_NOTIFICATION -> "tagesuebersicht";
          case TEMPLATE_FREE_TEXT,
                  TEMPLATE_REASSIGN_REQUEST_NOTIFICATION,
                  TEMPLATE_REASSIGN_CONFIRMATION_NOTIFICATION ->
              null;
          default ->
              throw new IllegalArgumentException(
                  "Unsupported notification occasion: " + mail.getTemplate());
        };
    // Older producer contracts do not carry topic, age or timestamps. Do not invent those facts.
    values.put("requestTopic", attributes.getOrDefault("requestTopic", "—"));
    values.put("requestPostcode", attributes.getOrDefault("plz", "—"));
    values.put("requestReceivedAt", attributes.getOrDefault("requestReceivedAt", "—"));
    values.put("requestUrl", values.get("appUrl") + "/sessions/consultant/sessionPreview");
    values.put("openRequestCount", attributes.getOrDefault("enquiries", "—"));
    values.put("oldestRequestAge", "—");
    values.put("digestGeneratedAt", Instant.now().toString());
    if (template != null) {
      return renderer.render(template, tone, values);
    }
    // Reassignment templates in the design catalogue address different roles. Keep the existing
    // advice-seeker/new-consultant semantics, using the shared authored-content layout instead.
    String subject;
    String body;
    switch (mail.getTemplate()) {
      case TEMPLATE_FREE_TEXT -> {
        subject = required(attributes, "subject");
        body = required(attributes, "text");
      }
      case TEMPLATE_REASSIGN_REQUEST_NOTIFICATION -> {
        subject = english ? "A change to your counselling" : "Änderung Ihrer Beratung";
        body =
            english
                ? "A change of counsellor has been requested for your counselling. Please sign in to review the details."
                : "Für Ihre Beratung wurde ein Wechsel der Fachkraft angefragt. Bitte melden Sie sich an, um die Einzelheiten zu prüfen.";
      }
      case TEMPLATE_REASSIGN_CONFIRMATION_NOTIFICATION -> {
        subject =
            english
                ? "A counselling handover was confirmed"
                : "Eine Beratungsübergabe wurde bestätigt";
        body =
            english
                ? "A counselling case has been transferred to you. Please sign in to view it."
                : "Eine Beratung wurde an Sie übergeben. Bitte melden Sie sich an, um sie aufzurufen.";
      }
      default -> throw new IllegalArgumentException("Unsupported notification occasion");
    }
    return renderer.renderMessage(tone, values, subject, body);
  }

  private String required(Map<String, String> attributes, String key) {
    if (isBlank(attributes.get(key))) {
      throw new IllegalArgumentException("Notification attribute is missing: " + key);
    }
    return attributes.get(key);
  }

  private record Delivery(String recipient, OrisoEmailRenderer.RenderedEmail email) {}
}
