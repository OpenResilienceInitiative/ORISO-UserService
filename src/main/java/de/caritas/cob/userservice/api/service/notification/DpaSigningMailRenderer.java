package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.RenderedEmail;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.Tone;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import lombok.NonNull;
import org.springframework.stereotype.Component;

/**
 * Renders the DPA ("AVV") signing mail from the design system's {@code avv-unterschrift} template
 * (ADR-020), branded like the invite mail. Preview and send both come through here, so the Admin
 * wizard shows exactly what the signatory receives.
 */
@Component
public class DpaSigningMailRenderer {

  static final String TEMPLATE_ID = "avv-unterschrift";

  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm 'Uhr'", Locale.GERMAN);

  // The DPA is a German contract; its dates are German wall-clock time, never UTC.
  private static final ZoneId MAIL_ZONE = ZoneId.of("Europe/Berlin");

  private final EmailBrandingResolver emailBrandingResolver;
  private final TenantEmailBrandValues tenantEmailBrandValues;
  private final OrisoEmailRenderer orisoEmailRenderer;

  public DpaSigningMailRenderer(
      @NonNull EmailBrandingResolver emailBrandingResolver,
      @NonNull TenantEmailBrandValues tenantEmailBrandValues,
      @NonNull OrisoEmailRenderer orisoEmailRenderer) {
    this.emailBrandingResolver = emailBrandingResolver;
    this.tenantEmailBrandValues = tenantEmailBrandValues;
    this.orisoEmailRenderer = orisoEmailRenderer;
  }

  /**
   * @param tenantId tenant whose branding the mail carries; a reserved, not yet created tenant
   *     yields platform branding
   * @param tenantName the Träger name shown in subject and body, already resolved by the caller
   * @param signLink absolute, origin-checked sign link
   */
  public RenderedEmail render(
      Long tenantId, String tenantName, String signLink, Instant providedAt, Instant expiresAt) {
    Map<String, String> values =
        tenantEmailBrandValues.values(emailBrandingResolver.resolve(tenantId));
    values.put("tenantName", tenantName);
    values.put("dpaUrl", signLink);
    values.put("dpaProvidedAt", germanDateTime(providedAt));
    values.put("dpaExpiresAt", germanDateTime(expiresAt));
    return orisoEmailRenderer.render(TEMPLATE_ID, Tone.DE_FORMAL, values);
  }

  private static String germanDateTime(Instant instant) {
    return DATE_TIME.format(instant.atZone(MAIL_ZONE));
  }
}
