package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.mailservice.generated.web.model.Dialect;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Composes the two TAKEOVER outcomes using the seven design-system variants. */
@Component
@RequiredArgsConstructor
public class CaseHandoverMailComposer {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*[\\w.]+\\s*}}");

  private final @NonNull OrisoEmailRenderer renderer;
  private final @NonNull EmailBrandingResolver brandingResolver;
  private final @NonNull TenantEmailBrandValues brandValues;

  public OrisoEmailRenderer.RenderedEmail compose(
      CaseHandoverEmailNotification.Mail mail, String baseUrl) {
    if (mail == null || isBlank(baseUrl)) {
      throw new IllegalArgumentException("Takeover email URL is missing");
    }
    // resolveNotification verifies the absolute URL against this exact tenant's persisted URL
    // and rejects a missing brand. Never route a recipient via another tenant or a fallback URL.
    var branding = brandingResolver.resolveNotification(mail.tenantId(), baseUrl);
    var values = new LinkedHashMap<>(brandValues.values(branding, mail.tenantId()));
    values.put("appUrl", baseUrl);
    values.put(
        "requestUrl",
        baseUrl
            + "/sessions/user/view/session/"
            + mail.sessionId()
            + "?caseHandoverRequestId="
            + mail.requestId());
    values.put("settingsUrl", baseUrl + "/profile/einstellungen");
    values.put("unsubscribeUrl", baseUrl + "/profile/einstellungen/email");
    String template =
        mail.outcome() == CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED
            ? "uebergabe-angefragt"
            : "uebergabe-bestaetigt";
    var rendered = renderer.render(template, tone(mail), values);
    if (PLACEHOLDER.matcher(rendered.subject()).find()
        || PLACEHOLDER.matcher(rendered.html()).find()
        || PLACEHOLDER.matcher(rendered.text()).find()) {
      throw new IllegalArgumentException("Takeover email template data is incomplete");
    }
    return rendered;
  }

  private OrisoEmailRenderer.Tone tone(CaseHandoverEmailNotification.Mail mail) {
    if (mail.language() == null) return OrisoEmailRenderer.Tone.DE_FORMAL;
    if (mail.language() == com.neovisionaries.i18n.LanguageCode.de) {
      return mail.dialect() == Dialect.INFORMAL
          ? OrisoEmailRenderer.Tone.DE_INFORMAL
          : OrisoEmailRenderer.Tone.DE_FORMAL;
    }
    return OrisoEmailRenderer.Tone.of(mail.language());
  }
}
