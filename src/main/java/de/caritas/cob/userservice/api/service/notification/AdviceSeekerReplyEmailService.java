package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsDTOOrDefaultIfNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.Status;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * One content-free mail per distinct counsellor Matrix message, independent of browser presence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdviceSeekerReplyEmailService {
  private final @NonNull SessionRepository sessions;
  private final @NonNull TenantService tenants;
  private final @NonNull TenantTemplateSupplier tenantTemplates;
  private final @NonNull EmailBrandingResolver branding;
  private final @NonNull OrisoEmailBrand emailBrand;
  private final @NonNull OrisoEmailRenderer renderer;
  private final @NonNull TenantSystemEmailRouteService routes;
  private final @NonNull TenantSystemEmailDelivery delivery;
  private final @NonNull ReplyEmailDeliveryWriter writer;

  @Value("${identity.email-dummy-suffix:}")
  private String emailDummySuffix;

  @Value("${multitenancy.enabled}")
  private boolean multitenancyEnabled;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Value("${feature.multitenancy.with.single.domain.enabled}")
  private boolean singleDomainMultitenancy;

  public void onConsultantReply(String roomId, String eventId) {
    if (isBlank(roomId) || isBlank(eventId)) {
      return;
    }
    Session session = sessions.findByMatrixRoomId(roomId).orElse(null);
    if (session == null || session.getUser() == null) {
      return;
    }
    User user = session.getUser();
    if (user.getDeleteDate() != null || !hasUsableAddress(user) || !wantsReplyEmail(user)) {
      return;
    }
    Long tenantId = user.getTenantId();
    if (tenantId == null
        || tenantId <= 0
        || (session.getTenantId() != null && !Objects.equals(tenantId, session.getTenantId()))) {
      throw new IllegalStateException("Reply email recipient tenant is missing or inconsistent");
    }
    if (session.getId() == null) {
      throw new IllegalStateException("Reply email session id is missing");
    }

    try {
      writer.reserve(user.getUserId(), eventKey(roomId, eventId), tenantId, session.getId());
    } catch (DataIntegrityViolationException alreadyReserved) {
      // Matrix can replay this event after an interrupted batch.
    }
  }

  /** Send one durable claim, rechecking the current address and consent before SMTP. */
  public void deliverPending(long deliveryId) {
    ReplyEmailDelivery claim = writer.claim(deliveryId).orElse(null);
    if (claim == null) {
      return;
    }
    Session session = sessions.findById(claim.getSessionId()).orElse(null);
    User user = session == null ? null : session.getUser();
    if (user == null
        || !Objects.equals(user.getUserId(), claim.getRecipientUserId())
        || !Objects.equals(user.getTenantId(), claim.getTenantId())
        || (session.getTenantId() != null
            && !Objects.equals(session.getTenantId(), claim.getTenantId()))
        || user.getDeleteDate() != null
        || !hasUsableAddress(user)
        || !wantsReplyEmail(user)) {
      writer.finish(deliveryId, Status.REJECTED);
      return;
    }

    TenantSystemEmailRouteService.Route route;
    OrisoEmailRenderer.RenderedEmail email;
    try {
      route =
          routes
              .resolve(claim.getTenantId())
              .orElseThrow(() -> new IllegalStateException("Reply email SMTP route is missing"));
      delivery.requireConfigured(route);
      RestrictedTenantDTO tenant = tenants.getRestrictedTenantDataFresh(claim.getTenantId());
      if (tenant == null || !Objects.equals(tenant.getId(), claim.getTenantId())) {
        throw new IllegalStateException("Reply email tenant is unavailable");
      }
      if (multitenancyEnabled && !singleDomainMultitenancy && isBlank(tenant.getSubdomain())) {
        throw new IllegalStateException("Reply email tenant subdomain is missing");
      }
      String baseUrl =
          requireBaseUrl(
              multitenancyEnabled ? tenantTemplates.getTenantBaseUrl(tenant) : applicationBaseUrl);
      branding.resolveNotification(claim.getTenantId(), baseUrl);
      var values = emailBrand.values(baseUrl, route.emailThemeColor());
      values.put("messageUrl", baseUrl + "/sessions/user/view/session/" + session.getId());
      OrisoEmailRenderer.Tone tone = OrisoEmailRenderer.Tone.of(user.getLanguageCode());
      if (tone == OrisoEmailRenderer.Tone.DE_FORMAL && !user.isLanguageFormal()) {
        tone = OrisoEmailRenderer.Tone.DE_INFORMAL;
      }
      email = renderer.render("neue-nachricht", tone, values);
    } catch (RuntimeException setupFailure) {
      writer.retryLater(deliveryId);
      log.warn(
          "Reply email setup unavailable for delivery {} ({})",
          deliveryId,
          setupFailure.getClass().getSimpleName());
      return;
    }

    try {
      delivery.sendReply(claim.getTenantId(), route, user.getEmail(), email);
      writer.finish(deliveryId, Status.SENT);
    } catch (TenantSystemEmailRouteService.ConfigurationException configurationFailure) {
      // TenantService rejected the route before any SMTP attempt.
      writer.retryLater(deliveryId);
      log.warn("Reply email route unavailable for delivery {}", deliveryId);
    } catch (RuntimeException sendFailure) {
      // SMTP may have accepted the message before its acknowledgement was lost.
      writer.finish(deliveryId, Status.UNCERTAIN);
      throw sendFailure;
    }
  }

  private boolean hasUsableAddress(User user) {
    return !isBlank(user.getEmail())
        && (isBlank(emailDummySuffix) || !user.getEmail().endsWith(emailDummySuffix));
  }

  private static boolean wantsReplyEmail(User user) {
    return user.isNotificationsEnabled()
        && !Boolean.FALSE.equals(
            deserializeNotificationSettingsDTOOrDefaultIfNull(user)
                .getNewChatMessageNotificationEnabled());
  }

  static String eventKey(String roomId, String eventId) {
    try {
      byte[] data = (roomId + '\n' + eventId).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String requireBaseUrl(String value) {
    if (isBlank(value)) {
      throw new IllegalStateException("Reply email app URL is missing");
    }
    try {
      URI url = URI.create(value);
      if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
          || url.getHost() == null
          || url.getUserInfo() != null
          || url.getQuery() != null
          || url.getFragment() != null) {
        throw new IllegalArgumentException("invalid URL");
      }
      return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("Reply email app URL is invalid", invalid);
    }
  }
}
