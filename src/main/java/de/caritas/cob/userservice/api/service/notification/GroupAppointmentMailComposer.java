package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import java.net.URI;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Builds a content-free group appointment mail from the group owner's explicit tenant origin. */
@Service
@RequiredArgsConstructor
public class GroupAppointmentMailComposer {
  public record Composed(
      long tenantId,
      TenantSystemEmailRouteService.Route route,
      TenantSystemEmailDelivery.Purpose purpose,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email) {}

  private static final Pattern UNRESOLVED = Pattern.compile("\\{\\{\\s*[\\w.]+\\s*}}");

  private final TenantService tenants;
  private final TenantTemplateSupplier tenantTemplates;
  private final EmailBrandingResolver branding;
  private final TenantEmailBrandValues brandValues;
  private final OrisoEmailRenderer renderer;
  private final TenantSystemEmailRouteService routes;

  @Value("${multitenancy.enabled}")
  private boolean multitenancyEnabled;

  @Value("${feature.multitenancy.with.single.domain.enabled}")
  private boolean singleDomainMultitenancy;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  /**
   * Empty means the owner disabled system mail; malformed settings fail without claiming the row.
   */
  public Optional<Composed> compose(
      GroupAppointmentMailOutbox mail, GroupAppointmentMailEligibilityService.Eligible eligible) {
    var owner = eligible.series().getChatOwner();
    if (owner == null || owner.getTenantId() == null || owner.getTenantId() <= 0) {
      throw new IllegalStateException("Self-help group has no sender tenant");
    }
    long tenantId = owner.getTenantId();
    var tenant = tenants.getRestrictedTenantDataFresh(tenantId);
    if (tenant == null || !Long.valueOf(tenantId).equals(tenant.getId())) {
      throw new IllegalStateException("Self-help group sender tenant is unavailable");
    }
    if (multitenancyEnabled && !singleDomainMultitenancy && isBlank(tenant.getSubdomain())) {
      throw new IllegalStateException("Self-help group sender tenant subdomain is missing");
    }
    String baseUrl =
        requireBaseUrl(
            multitenancyEnabled ? tenantTemplates.getTenantBaseUrl(tenant) : applicationBaseUrl);
    var route = routes.resolve(tenantId);
    if (route.isEmpty()) {
      return Optional.empty();
    }
    var values =
        new LinkedHashMap<>(
            brandValues.values(branding.resolveNotification(tenantId, baseUrl), tenantId));
    values.put("appUrl", baseUrl);
    values.put("settingsUrl", baseUrl + "/profile/einstellungen");
    values.put("unsubscribeUrl", baseUrl + "/profile/einstellungen/email");
    values.put("appointmentUrl", baseUrl + "/login?seriesId=" + mail.getSeriesId());
    var localTime =
        mail.getScheduledStartUtc()
            .atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(ZoneId.of(mail.getTimezone()));
    var locale = locale(eligible.recipient().tone());
    values.put(
        "appointmentDate",
        localTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)));
    values.put("appointmentTime", localTime.format(DateTimeFormatter.ofPattern("HH:mm z", locale)));
    String templateId = template(mail);
    var email = renderer.render(templateId, eligible.recipient().tone(), values);
    if (UNRESOLVED.matcher(email.subject()).find()
        || UNRESOLVED.matcher(email.html()).find()
        || UNRESOLVED.matcher(email.text()).find()) {
      throw new IllegalStateException("Self-help appointment template data is incomplete");
    }
    return Optional.of(
        new Composed(
            tenantId,
            route.get(),
            purpose(mail.getEventType()),
            eligible.recipient().email(),
            email));
  }

  private static String template(GroupAppointmentMailOutbox mail) {
    String occasion =
        switch (mail.getEventType()) {
          case CONFIRMED -> "bestaetigt";
          case RESCHEDULED -> "verschoben";
          case CANCELLED -> "abgesagt";
          case REMINDER -> "erinnerung";
        };
    String role =
        mail.getRecipientRole() == GroupAppointmentMailOutbox.RecipientRole.COUNSELOR
            ? "beratung"
            : "teilnahme";
    return "selbsthilfe-termin-" + occasion + "-" + role;
  }

  private static TenantSystemEmailDelivery.Purpose purpose(
      GroupAppointmentMailOutbox.EventType event) {
    return switch (event) {
      case CONFIRMED -> TenantSystemEmailDelivery.Purpose.SELF_HELP_APPOINTMENT_CONFIRMED;
      case RESCHEDULED -> TenantSystemEmailDelivery.Purpose.SELF_HELP_APPOINTMENT_RESCHEDULED;
      case CANCELLED -> TenantSystemEmailDelivery.Purpose.SELF_HELP_APPOINTMENT_CANCELLED;
      case REMINDER -> TenantSystemEmailDelivery.Purpose.SELF_HELP_APPOINTMENT_REMINDER;
    };
  }

  private static Locale locale(OrisoEmailRenderer.Tone tone) {
    return Locale.forLanguageTag(
        tone == OrisoEmailRenderer.Tone.DE_INFORMAL ? "de" : tone.directory().substring(0, 2));
  }

  private static String requireBaseUrl(String value) {
    if (isBlank(value)) {
      throw new IllegalStateException("Self-help appointment app URL is missing");
    }
    URI url = URI.create(value);
    if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
        || url.getHost() == null
        || url.getUserInfo() != null
        || url.getQuery() != null
        || url.getFragment() != null) {
      throw new IllegalStateException("Self-help appointment app URL is invalid");
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
