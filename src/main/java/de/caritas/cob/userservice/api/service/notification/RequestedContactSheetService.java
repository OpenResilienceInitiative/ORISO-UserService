package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import java.net.URI;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Sends centre contact details only after the owner explicitly requests them for a session. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RequestedContactSheetService {
  private final @NonNull SessionRepository sessions;
  private final @NonNull AgencyContactDetailsClient agencies;
  private final @NonNull TenantService tenants;
  private final @NonNull TenantTemplateSupplier tenantTemplates;
  private final @NonNull EmailBrandingResolver branding;
  private final @NonNull OrisoEmailBrand emailBrand;
  private final @NonNull OrisoEmailRenderer renderer;
  private final @NonNull TenantSystemEmailRouteService routes;
  private final @NonNull TenantSystemEmailDelivery delivery;

  @Value("${identity.email-dummy-suffix:}")
  private String emailDummySuffix;

  @Value("${multitenancy.enabled}")
  private boolean multitenancyEnabled;

  @Value("${feature.multitenancy.with.single.domain.enabled}")
  private boolean singleDomainMultitenancy;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  public void send(long sessionId, String requestingUserId) {
    Session session =
        sessions
            .findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    User seeker = session.getUser();
    if (seeker == null || !Objects.equals(seeker.getUserId(), requestingUserId)) {
      throw new ForbiddenException("Only the session owner can request contact details");
    }
    if (seeker.getDeleteDate() != null
        || (session.getStatus() != Session.SessionStatus.NEW
            && session.getStatus() != Session.SessionStatus.IN_PROGRESS)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is no longer active");
    }
    if (isBlank(seeker.getEmail())
        || (!isBlank(emailDummySuffix) && seeker.getEmail().endsWith(emailDummySuffix))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "An email address is required");
    }
    Long tenantId = session.getTenantId();
    Long agencyId = session.getAgencyId();
    if (tenantId == null
        || tenantId <= 0
        || agencyId == null
        || agencyId <= 0
        || !Objects.equals(tenantId, seeker.getTenantId())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Session has no valid agency");
    }

    var contact = agencies.read(agencyId, tenantId);
    if (isBlank(contact.name()) || (isBlank(contact.phone()) && isBlank(contact.email()))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Counselling service has no maintained contact method");
    }
    var tenant = tenants.getRestrictedTenantDataFresh(tenantId);
    if (tenant == null || !Objects.equals(tenant.getId(), tenantId)) {
      throw new IllegalStateException("Contact-sheet tenant is unavailable");
    }
    if (multitenancyEnabled && !singleDomainMultitenancy && isBlank(tenant.getSubdomain())) {
      throw new IllegalStateException("Contact-sheet tenant subdomain is missing");
    }
    String baseUrl =
        requireBaseUrl(
            multitenancyEnabled ? tenantTemplates.getTenantBaseUrl(tenant) : applicationBaseUrl);
    var route =
        routes
            .resolve(tenantId)
            .orElseThrow(() -> new IllegalStateException("Contact-sheet SMTP route is missing"));
    branding.resolveNotification(tenantId, baseUrl);
    var values = emailBrand.values(baseUrl, route.emailThemeColor());
    values.put("consultantName", contact.name());
    values.put("consultantPhone", emptyIfNull(contact.phone()));
    values.put("consultantEmail", emptyIfNull(contact.email()));
    values.put("consultantHours", emptyIfNull(contact.openingHours()));
    values.put("messageUrl", baseUrl + "/sessions/user/view/session/" + sessionId);
    var tone = OrisoEmailRenderer.Tone.of(seeker.getLanguageCode());
    if (tone == OrisoEmailRenderer.Tone.DE_FORMAL && !seeker.isLanguageFormal()) {
      tone = OrisoEmailRenderer.Tone.DE_INFORMAL;
    }
    var email = renderer.render("beraterin-kontakt", tone, values);
    if (!delivery.sendConfirmed(
        tenantId,
        route,
        TenantSystemEmailDelivery.Purpose.CONTACT_SHEET,
        seeker.getEmail(),
        email)) {
      throw new IllegalStateException("Contact-sheet SMTP delivery failed");
    }
    log.info("Requested contact sheet delivered for session {} and tenant {}", sessionId, tenantId);
  }

  private static String emptyIfNull(String value) {
    return value == null ? "" : value;
  }

  private static String requireBaseUrl(String value) {
    if (isBlank(value)) {
      throw new IllegalStateException("Contact-sheet app URL is missing");
    }
    URI url = URI.create(value);
    if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
        || url.getHost() == null
        || url.getUserInfo() != null
        || url.getQuery() != null
        || url.getFragment() != null) {
      throw new IllegalStateException("Contact-sheet app URL is invalid");
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
