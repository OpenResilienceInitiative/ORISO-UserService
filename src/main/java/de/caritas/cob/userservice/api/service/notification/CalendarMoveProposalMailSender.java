package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsOrDefaultIfNull;
import static de.caritas.cob.userservice.api.service.helper.MailService.safeFailureReason;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Sends the move proposal mail (ADR-032, template {@code calendar-move-proposal}) to the counsellor
 * asked, through their Träger's mail route. Their switch for appointment mails decides whether it
 * is sent. The mail names the colleague who proposes and the two times, never the advice seeker and
 * nothing of the conversation. Failures are logged without the recipient or the times.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalendarMoveProposalMailSender {

  /**
   * TenantService accepts a closed list of purposes and treats every notification purpose alike
   * (the Träger's notification switch). Until it knows one for this mail, it travels as the generic
   * notice the inquiry-accepted mail uses too.
   */
  static final TenantSystemEmailDelivery.Purpose PURPOSE =
      TenantSystemEmailDelivery.Purpose.FREE_TEXT_NOTICE;

  private static final Pattern UNRESOLVED = Pattern.compile("\\{\\{\\s*[\\w.]+\\s*}}");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final ConsultantRepository consultants;
  private final IdentityClientConfig identityClientConfig;
  private final TenantSystemEmailRouteService routes;
  private final TenantSystemEmailDelivery delivery;
  private final TenantService tenants;
  private final TenantTemplateSupplier tenantTemplates;
  private final EmailBrandingResolver branding;
  private final TenantEmailBrandValues brandValues;
  private final OrisoEmailRenderer renderer;

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancyEnabled = true;

  @Value("${app.base.url:}")
  private String applicationBaseUrl;

  @Value("${calendar.zone:Europe/Berlin}")
  private String zone = "Europe/Berlin";

  @Async
  public void send(CalendarMoveProposalEmailNotification.Mail mail) {
    Long tenantId = null;
    try {
      var approver =
          TenantContext.supplyAcrossTenants(
                  () -> consultants.findByIdAndDeleteDateIsNull(mail.approverId()))
              .orElse(null);
      if (approver == null || !wantsAppointmentMail(approver)) {
        return;
      }
      tenantId = approver.getTenantId();
      if (tenantId == null || tenantId <= 0) {
        throw new IllegalStateException("The counsellor asked has no tenant");
      }
      var route = routes.resolve(tenantId);
      if (route.isEmpty()) {
        return;
      }
      var tenant = tenants.getRestrictedTenantDataFresh(tenantId);
      if (tenant == null || !Objects.equals(tenant.getId(), tenantId)) {
        throw new IllegalStateException("The tenant of the counsellor asked is unavailable");
      }
      String baseUrl =
          baseUrl(
              multitenancyEnabled ? tenantTemplates.getTenantBaseUrl(tenant) : applicationBaseUrl);
      var tone = renderer.deliveryTone(toneOf(approver));
      var email =
          renderer.render(
              EmailSupplier.TEMPLATE_CALENDAR_MOVE_PROPOSAL,
              tone,
              values(mail, tenantId, baseUrl, tone));
      if (UNRESOLVED.matcher(email.subject()).find()
          || UNRESOLVED.matcher(email.html()).find()
          || UNRESOLVED.matcher(email.text()).find()) {
        throw new IllegalStateException("Move proposal template data is incomplete");
      }
      if (!delivery.sendConfirmed(tenantId, route.get(), PURPOSE, approver.getEmail(), email)) {
        log.error(
            "Calendar move proposal mail was rejected by platform SMTP for tenant {}", tenantId);
      }
    } catch (RuntimeException failure) {
      // No recipient, no name and no time in the log.
      log.error(
          "Calendar move proposal mail failed for tenant {}: {}",
          tenantId,
          safeFailureReason(failure));
    }
  }

  private LinkedHashMap<String, String> values(
      CalendarMoveProposalEmailNotification.Mail mail,
      long tenantId,
      String baseUrl,
      OrisoEmailRenderer.Tone tone) {
    var values =
        new LinkedHashMap<>(
            brandValues.values(branding.resolveNotification(tenantId, baseUrl), tenantId));
    values.put("appUrl", baseUrl);
    values.put("settingsUrl", baseUrl + "/profile/einstellungen");
    values.put("unsubscribeUrl", baseUrl + "/profile/einstellungen/email");
    values.put("calendarUrl", baseUrl + "/booking/events");
    values.put("proposerName", isBlank(mail.proposerName()) ? "" : mail.proposerName());
    var locale = localeOf(tone);
    values.put("currentDate", date(mail.currentStart(), locale));
    values.put("currentTime", time(mail.currentStart()));
    values.put("proposedDate", date(mail.proposedStart(), locale));
    values.put("proposedTime", time(mail.proposedStart()));
    return values;
  }

  private boolean wantsAppointmentMail(Consultant consultant) {
    var email = consultant.getEmail();
    var dummySuffix = identityClientConfig.getEmailDummySuffix();
    return consultant.isNotificationsEnabled()
        && deserializeNotificationSettingsOrDefaultIfNull(consultant)
            .isAppointmentNotificationEnabled()
        && !isBlank(email)
        && (isBlank(dummySuffix) || !email.endsWith(dummySuffix));
  }

  /** The counsellor's language; German, formal unless they chose otherwise, where none fits. */
  static OrisoEmailRenderer.Tone toneOf(Consultant consultant) {
    OrisoEmailRenderer.Tone tone;
    try {
      tone =
          consultant.getLanguageCode() == null
              ? OrisoEmailRenderer.Tone.DE_FORMAL
              : OrisoEmailRenderer.Tone.of(consultant.getLanguageCode());
    } catch (IllegalArgumentException noTemplateInThatLanguage) {
      tone = OrisoEmailRenderer.Tone.DE_FORMAL;
    }
    return tone == OrisoEmailRenderer.Tone.DE_FORMAL && !consultant.isLanguageFormal()
        ? OrisoEmailRenderer.Tone.DE_INFORMAL
        : tone;
  }

  private String date(Instant instant, Locale locale) {
    return instant
        .atZone(ZoneId.of(zone))
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
  }

  private String time(Instant instant) {
    return instant.atZone(ZoneId.of(zone)).format(TIME);
  }

  private static Locale localeOf(OrisoEmailRenderer.Tone tone) {
    return Locale.forLanguageTag(tone.directory().substring(0, 2));
  }

  private static String baseUrl(String value) {
    if (isBlank(value)) {
      throw new IllegalStateException("Move proposal mail app URL is missing");
    }
    URI url = URI.create(value);
    if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
        || url.getHost() == null
        || url.getUserInfo() != null
        || url.getQuery() != null
        || url.getFragment() != null) {
      throw new IllegalStateException("Move proposal mail app URL is invalid");
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
