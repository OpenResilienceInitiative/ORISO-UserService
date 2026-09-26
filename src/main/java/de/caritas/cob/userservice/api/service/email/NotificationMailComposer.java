package de.caritas.cob.userservice.api.service.email;

import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_ASSIGN_ENQUIRY_NOTIFICATION;
import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_DAILY_ENQUIRY_NOTIFICATION;
import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_NEW_DIRECT_ENQUIRY_NOTIFICATION;
import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_NEW_ENQUIRY_NOTIFICATION;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.mailservice.generated.web.model.Dialect;
import de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Composes legacy notification data into ORISO MIME content without selecting a transport. */
@Component
@RequiredArgsConstructor
public class NotificationMailComposer {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*[\\w.]+\\s*}}");
  private static final String PREVIEW_PATH = "/sessions/consultant/sessionPreview";
  private static final String ASSIGNED_PATH = "/sessions/consultant/sessionView";

  private final OrisoEmailRenderer renderer;
  private final EmailBrandingResolver brandingResolver;
  private final TenantEmailBrandValues brandValues;

  public OrisoEmailRenderer.RenderedEmail compose(MailDTO mail, long tenantId) {
    if (mail == null || isBlank(mail.getEmail()) || isBlank(mail.getTemplate()) || tenantId < 0) {
      throw new IllegalArgumentException("Notification recipient, occasion or tenant is missing");
    }
    String template = templateFor(mail.getTemplate());
    Map<String, String> attributes = attributesOf(mail);
    String encodedTenantId = attributes.get("tenantId");
    if (encodedTenantId != null && !Long.toString(tenantId).equals(encodedTenantId)) {
      throw new IllegalArgumentException("Notification tenantId does not match recipient tenant");
    }

    String baseUrl = requireBaseUrl(attributes);
    var values =
        new LinkedHashMap<>(brandValues.values(brandingResolver.resolve(tenantId), tenantId));
    values.put("appUrl", baseUrl);
    values.put("settingsUrl", baseUrl + "/profile/settings");
    values.put("unsubscribeUrl", baseUrl + "/profile/settings/notifications");
    values.put(
        "requestUrl",
        baseUrl
            + (TEMPLATE_ASSIGN_ENQUIRY_NOTIFICATION.equals(mail.getTemplate())
                ? ASSIGNED_PATH
                : PREVIEW_PATH));

    if (TEMPLATE_DAILY_ENQUIRY_NOTIFICATION.equals(mail.getTemplate())) {
      values.put("openRequestCount", require(attributes, "enquiries"));
      values.put("oldestRequestAge", require(attributes, "oldestRequestAge"));
      values.put("digestGeneratedAt", require(attributes, "digestGeneratedAt"));
    } else {
      values.put("requestTopic", require(attributes, "requestTopic"));
      values.put("requestReceivedAt", require(attributes, "requestReceivedAt"));
      if (!TEMPLATE_NEW_DIRECT_ENQUIRY_NOTIFICATION.equals(mail.getTemplate())) {
        values.put("requestPostcode", require(attributes, "requestPostcode"));
      }
    }

    var rendered =
        renderer.render(template, toneFor(mail.getLanguage(), mail.getDialect()), values);
    if (PLACEHOLDER.matcher(rendered.subject()).find()
        || PLACEHOLDER.matcher(rendered.html()).find()
        || PLACEHOLDER.matcher(rendered.text()).find()) {
      throw new IllegalArgumentException("Notification template data is incomplete");
    }
    return rendered;
  }

  private static String templateFor(String occasion) {
    return switch (occasion) {
      case TEMPLATE_NEW_ENQUIRY_NOTIFICATION -> "neue-anfrage";
      case TEMPLATE_NEW_DIRECT_ENQUIRY_NOTIFICATION -> "direkte-anfrage";
      case TEMPLATE_ASSIGN_ENQUIRY_NOTIFICATION -> "anfrage-zugewiesen";
      case TEMPLATE_DAILY_ENQUIRY_NOTIFICATION -> "tagesuebersicht";
      default ->
          throw new IllegalArgumentException("Unsupported notification occasion: " + occasion);
    };
  }

  private static OrisoEmailRenderer.Tone toneFor(LanguageCode language, Dialect dialect) {
    if (language == null) {
      throw new IllegalArgumentException("Notification language is missing");
    }
    return switch (language) {
      case DE -> {
        if (dialect == null) {
          throw new IllegalArgumentException("German notification dialect is missing");
        }
        yield dialect == Dialect.INFORMAL
            ? OrisoEmailRenderer.Tone.DE_INFORMAL
            : OrisoEmailRenderer.Tone.DE_FORMAL;
      }
      case EN -> OrisoEmailRenderer.Tone.EN;
      case FR -> OrisoEmailRenderer.Tone.FR;
      case RU -> OrisoEmailRenderer.Tone.RU;
      case TI -> OrisoEmailRenderer.Tone.TI;
      case TR -> OrisoEmailRenderer.Tone.TR;
      default ->
          throw new IllegalArgumentException(
              "Notification language has no installed template: " + language);
    };
  }

  private static Map<String, String> attributesOf(MailDTO mail) {
    Map<String, String> attributes = new LinkedHashMap<>();
    if (mail.getTemplateData() == null) {
      return attributes;
    }
    mail.getTemplateData()
        .forEach(
            item -> {
              if (item == null || isBlank(item.getKey()) || item.getValue() == null) {
                throw new IllegalArgumentException("Notification attribute is invalid");
              }
              if (attributes.putIfAbsent(item.getKey(), item.getValue()) != null) {
                throw new IllegalArgumentException(
                    "Duplicate notification attribute: " + item.getKey());
              }
            });
    return attributes;
  }

  private static String require(Map<String, String> attributes, String key) {
    String value = attributes.get(key);
    if (isBlank(value)) {
      throw new IllegalArgumentException("Notification attribute is missing: " + key);
    }
    return value.trim();
  }

  private static String requireBaseUrl(Map<String, String> attributes) {
    String base = require(attributes, "url");
    try {
      URI uri = URI.create(base);
      if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("Notification url is invalid");
      }
      return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Notification url is invalid", exception);
    }
  }
}
