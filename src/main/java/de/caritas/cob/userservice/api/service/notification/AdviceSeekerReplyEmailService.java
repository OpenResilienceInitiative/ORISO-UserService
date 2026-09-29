package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsDTOOrDefaultIfNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.Status;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.layout.EmailColors;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.net.URI;
import java.net.URLEncoder;
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
  private final @NonNull DoNotDisturbService doNotDisturb;
  private final @NonNull MatrixCaseReplyActorAuthorizer replyActors;

  @Value("${identity.email-dummy-suffix:}")
  private String emailDummySuffix;

  @Value("${multitenancy.enabled}")
  private boolean multitenancyEnabled;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Value("${feature.multitenancy.with.single.domain.enabled}")
  private boolean singleDomainMultitenancy;

  public void onConsultantReply(String roomId, String eventId, String senderMatrixUserId) {
    if (isBlank(roomId) || isBlank(eventId) || isBlank(senderMatrixUserId)) {
      return;
    }
    Session session = sessions.findByMatrixRoomId(roomId).orElse(null);
    if (!isReplySession(session, roomId) || session.getUser() == null) {
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
    if (!replyActors.isCurrentWriter(session, senderMatrixUserId, false)) {
      return;
    }

    try {
      writer.reserve(
          RecipientKind.ASKER,
          user.getUserId(),
          senderMatrixUserId,
          roomId,
          eventKey(roomId, eventId),
          tenantId,
          session.getId());
    } catch (DataIntegrityViolationException alreadyReserved) {
      // Matrix can replay this event after an interrupted batch.
    }
  }

  /** Claim only a seeker's message to the currently assigned counsellor of a 1:1 consultation. */
  public void onAdviceSeekerMessage(String roomId, String eventId, String senderMatrixUserId) {
    if (isBlank(roomId) || isBlank(eventId) || isBlank(senderMatrixUserId)) {
      return;
    }
    Session session = sessions.findByMatrixRoomId(roomId).orElse(null);
    if (!isConsultationMessage(session, roomId)
        || session.getUser() == null
        || session.getUser().getDeleteDate() != null
        || !Objects.equals(session.getUser().getTenantId(), session.getTenantId())
        || !senderMatrixUserId.equals(session.getUser().getMatrixUserId())) {
      return;
    }
    Consultant consultant = session.getConsultant();
    if (!isEligibleConsultant(consultant, session)) {
      return;
    }
    try {
      writer.reserve(
          RecipientKind.CONSULTANT,
          consultant.getId(),
          senderMatrixUserId,
          roomId,
          eventKey(roomId, eventId),
          session.getTenantId(),
          session.getId());
    } catch (DataIntegrityViolationException alreadyReserved) {
      // Matrix replay of the same event is collapsed by recipient kind, recipient and event key.
    }
  }

  /** Send one durable claim, rechecking the current address and consent before SMTP. */
  public void deliverPending(long deliveryId) {
    ReplyEmailDelivery claim = writer.claim(deliveryId).orElse(null);
    if (claim == null) {
      return;
    }
    Session session;
    try {
      session = sessions.findById(claim.getSessionId()).orElse(null);
    } catch (RuntimeException unavailable) {
      retryUnavailablePreflight(deliveryId, unavailable);
      return;
    }
    boolean consultantMail = claim.getRecipientKind() == RecipientKind.CONSULTANT;
    if (session == null
        || isBlank(claim.getSourceRoomId())
        || isBlank(claim.getSourceMatrixUserId())
        || !Objects.equals(claim.getSourceRoomId(), session.getMatrixRoomId())) {
      writer.finish(deliveryId, Status.REJECTED);
      return;
    }
    String recipientAddress;
    String template;
    OrisoEmailRenderer.Tone tone;
    String actionPath;
    if (consultantMail) {
      Consultant consultant = session.getConsultant();
      if (!isConsultationMessage(session, session.getMatrixRoomId())
          || session.getUser() == null
          || session.getUser().getDeleteDate() != null
          || !Objects.equals(session.getUser().getTenantId(), claim.getTenantId())
          || !Objects.equals(session.getUser().getMatrixUserId(), claim.getSourceMatrixUserId())
          || consultant == null
          || !Objects.equals(consultant.getId(), claim.getRecipientUserId())
          || !Objects.equals(session.getTenantId(), claim.getTenantId())) {
        writer.finish(deliveryId, Status.REJECTED);
        return;
      }
      boolean eligible;
      try {
        eligible = isEligibleConsultant(consultant, session);
      } catch (RuntimeException unavailable) {
        retryUnavailablePreflight(deliveryId, unavailable);
        return;
      }
      if (!eligible) {
        writer.finish(deliveryId, Status.REJECTED);
        return;
      }
      boolean currentMembers;
      try {
        currentMembers =
            replyActors.hasCurrentRoomMembers(
                session, claim.getSourceMatrixUserId(), consultant.getMatrixUserId());
      } catch (RuntimeException unavailable) {
        retryUnavailablePreflight(deliveryId, unavailable);
        return;
      }
      if (!currentMembers) {
        writer.finish(deliveryId, Status.REJECTED);
        return;
      }
      recipientAddress = consultant.getEmail();
      template = "neue-nachricht-beratung";
      try {
        tone = tone(consultant.getLanguageCode(), consultant.isLanguageFormal());
      } catch (RuntimeException unavailable) {
        retryUnavailablePreflight(deliveryId, unavailable);
        return;
      }
      String room =
          URLEncoder.encode(session.getMatrixRoomId(), StandardCharsets.UTF_8).replace("+", "%20");
      actionPath = "/sessions/consultant/sessionView/" + room + "/" + session.getId();
    } else {
      User user = session.getUser();
      if (user == null
          || !isReplySession(session, claim.getSourceRoomId())
          || !Objects.equals(user.getUserId(), claim.getRecipientUserId())
          || !Objects.equals(user.getTenantId(), claim.getTenantId())
          || !Objects.equals(session.getTenantId(), claim.getTenantId())
          || user.getDeleteDate() != null
          || !hasUsableAddress(user)
          || !wantsReplyEmail(user)) {
        writer.finish(deliveryId, Status.REJECTED);
        return;
      }
      boolean actorAllowed;
      try {
        actorAllowed = replyActors.isCurrentWriter(session, claim.getSourceMatrixUserId(), true);
      } catch (RuntimeException unavailable) {
        retryUnavailablePreflight(deliveryId, unavailable);
        return;
      }
      if (!actorAllowed) {
        writer.finish(deliveryId, Status.REJECTED);
        return;
      }
      recipientAddress = user.getEmail();
      template = "neue-nachricht";
      try {
        tone = tone(user.getLanguageCode(), user.isLanguageFormal());
      } catch (RuntimeException unavailable) {
        retryUnavailablePreflight(deliveryId, unavailable);
        return;
      }
      actionPath = "/sessions/user/view/session/" + session.getId();
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
      var recipientBrand = branding.resolveNotification(claim.getTenantId(), baseUrl);
      var values = emailBrand.values(baseUrl, route.emailThemeColor());
      // Notification copy and footer identity stay platform-neutral. Only validated visual
      // theming from this exact recipient tenant may vary between installations.
      if (recipientBrand.logoUrl() != null) {
        values.put("logoUrl", recipientBrand.logoUrl());
      }
      if (!EmailColors.PLATFORM_ACCENT_DARK.equals(recipientBrand.accentColor())) {
        values.put("primaryColor", emailBrand.readablePrimary(recipientBrand.accentColor()));
        values.put("accentColor", recipientBrand.accentColor());
      }
      values.put("messageUrl", baseUrl + actionPath);
      email = renderer.render(template, tone, values);
    } catch (RuntimeException setupFailure) {
      writer.retryLater(deliveryId);
      log.warn(
          "Reply email setup unavailable for delivery {} ({})",
          deliveryId,
          setupFailure.getClass().getSimpleName());
      return;
    }

    try {
      delivery.sendReply(
          claim.getTenantId(),
          route,
          recipientAddress,
          email,
          java.util.UUID.fromString(claim.getCorrelationId()));
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
    return hasUsableAddress(user.getEmail());
  }

  private boolean hasUsableAddress(String address) {
    return !isBlank(address) && (isBlank(emailDummySuffix) || !address.endsWith(emailDummySuffix));
  }

  private boolean isEligibleConsultant(Consultant consultant, Session session) {
    return consultant != null
        && consultant.getId() != null
        && (session.getUser() == null || !consultant.getId().equals(session.getUser().getUserId()))
        && consultant.getDeleteDate() == null
        && !Boolean.FALSE.equals(consultant.getNotifyNewChatMessageFromAdviceSeeker())
        && (isBlank(consultant.getNotificationsSettings())
            || (consultant.isNotificationsEnabled()
                && Boolean.TRUE.equals(
                    deserializeNotificationSettingsDTOOrDefaultIfNull(consultant)
                        .getNewChatMessageNotificationEnabled())))
        && !doNotDisturb.isInDoNotDisturb(consultant.getId())
        && hasUsableAddress(consultant.getEmail())
        && Objects.equals(consultant.getTenantId(), session.getTenantId());
  }

  private static boolean isConsultationMessage(Session session, String roomId) {
    return session != null
        && session.getId() != null
        && session.getTenantId() != null
        && session.getTenantId() > 0
        && !isBlank(roomId)
        && Objects.equals(roomId, session.getMatrixRoomId())
        && session.isConsentGateApplicable()
        && session.getStatus() == Session.SessionStatus.IN_PROGRESS;
  }

  private static boolean isReplySession(Session session, String roomId) {
    return session != null
        && session.getId() != null
        && session.getTenantId() != null
        && session.getTenantId() > 0
        && !isBlank(roomId)
        && Objects.equals(roomId, session.getMatrixRoomId())
        && session.isConsentGateApplicable()
        && session.getStatus() == Session.SessionStatus.IN_PROGRESS;
  }

  private static OrisoEmailRenderer.Tone tone(
      com.neovisionaries.i18n.LanguageCode language, boolean formal) {
    var tone = OrisoEmailRenderer.Tone.of(language);
    return tone == OrisoEmailRenderer.Tone.DE_FORMAL && !formal
        ? OrisoEmailRenderer.Tone.DE_INFORMAL
        : tone;
  }

  private void retryUnavailablePreflight(long deliveryId, RuntimeException unavailable) {
    writer.retryLater(deliveryId);
    log.warn(
        "Reply email preflight unavailable for delivery {} ({})",
        deliveryId,
        unavailable.getClass().getSimpleName());
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
