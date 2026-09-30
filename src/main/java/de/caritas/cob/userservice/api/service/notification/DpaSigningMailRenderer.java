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
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Renders the DPA signing mail ("Vertragsunterlagen") from the design system's {@code
 * avv-unterschrift} template (ADR-020), branded like the invite mail. Preview and send both come
 * through here, so the Admin wizard shows exactly what the signatory receives.
 */
@Component
public class DpaSigningMailRenderer {

  static final String TEMPLATE_ID = "avv-unterschrift";

  // German-only on purpose: the DPA is a German contract and the dispatch contract carries no
  // language. "für" takes the accusative, the fine print's "zwischen … und" the dative.
  static final String GENERIC_TENANT_NAME = "Ihre Organisation";
  static final String GENERIC_TENANT_NAME_DATIVE = "Ihrer Organisation";

  /**
   * The footer's sender is the platform operator, not the Träger the mail is about: the fine print
   * reads "Vertragsverhältnis zwischen {{orgName}} und {{tenantNameDative}}", so a Träger overlay
   * would name the Träger as its own contract partner.
   */
  private static final Long OPERATOR_IS_SENDER = null;

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
   * @param tenantName the Träger name shown in subject and body, or {@code null} while the tenant
   *     is only reserved — the mail then says "Ihre Organisation"
   * @param signLink absolute, origin-checked sign link
   */
  public RenderedEmail render(
      Long tenantId, String tenantName, String signLink, Instant providedAt, Instant expiresAt) {
    Map<String, String> values =
        tenantEmailBrandValues.values(emailBrandingResolver.resolve(tenantId), OPERATOR_IS_SENDER);
    boolean named = StringUtils.isNotBlank(tenantName);
    values.put("tenantName", named ? tenantName : GENERIC_TENANT_NAME);
    values.put("tenantNameDative", named ? tenantName : GENERIC_TENANT_NAME_DATIVE);
    values.put("dpaUrl", signLink);
    values.put("dpaProvidedAt", germanDateTime(providedAt));
    values.put("dpaExpiresAt", germanDateTime(expiresAt));
    return orisoEmailRenderer.render(TEMPLATE_ID, Tone.DE_FORMAL, values);
  }

  private static String germanDateTime(Instant instant) {
    return DATE_TIME.format(instant.atZone(MAIL_ZONE));
  }
}
