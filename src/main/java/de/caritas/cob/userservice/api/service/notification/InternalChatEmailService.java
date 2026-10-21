package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsDTOOrDefaultIfNull;
import static de.caritas.cob.userservice.api.service.notification.NotificationEmailDiagnostics.*;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.Status;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Durable, content-free email for ordinary counsellor internal-group messages. */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternalChatEmailService {
  // CREATED once provisioned; IN_PROGRESS while an existing counsellor gets another agency.
  private static final Set<ConsultantStatus> ACTIVE_CONSULTANT_STATUSES =
      Set.of(ConsultantStatus.CREATED, ConsultantStatus.IN_PROGRESS);

  private static final Set<String> USER_MESSAGE_TYPES =
      Set.of("m.text", "m.emote", "m.image", "m.file", "m.audio", "m.video");

  private final @NonNull SessionRepository sessions;
  private final @NonNull GroupChatParticipantRepository participants;
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull NotificationAccountEligibility accountEligibility;
  private final @NonNull MatrixSynapseService matrix;
  private final @NonNull ReplyEmailDeliveryWriter writer;
  private final @NonNull DoNotDisturbService doNotDisturb;
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

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Value("${feature.multitenancy.with.single.domain.enabled}")
  private boolean singleDomainMultitenancy;

  /** No client classification grants group authority: the stored primary room decides. */
  public void onMessageIntent(String roomId, String eventId, AuthenticatedUser caller) {
    if (caller == null || !caller.isConsultant()) return;
    Consultant actor = consultants.findByIdAndDeleteDateIsNull(caller.getUserId()).orElse(null);
    if (actor == null || !Objects.equals(actor.getTenantId(), caller.getTenantId())) return;
    reserve(roomId, eventId, actor);
  }

  /** The Matrix sync producer shares the browser producer's durable event/recipient key. */
  public void onMatrixMessage(String roomId, String eventId, String matrixSender) {
    if (isBlank(matrixSender)) return;
    Consultant actor = consultants.findByMatrixUserIdAndDeleteDateIsNull(matrixSender).orElse(null);
    if (actor != null) reserve(roomId, eventId, actor);
  }

  private void reserve(String roomId, String eventId, Consultant actor) {
    if (isBlank(roomId) || isBlank(eventId)) return;
    Session session = resolveGroup(roomId);
    if (!currentParticipant(session, actor, participantIds(session))) return;
    String eventKey = AdviceSeekerReplyEmailService.eventKey(roomId, eventId);
    try {
      writer.reserveInternalIntent(
          actor.getId(),
          actor.getMatrixUserId(),
          roomId,
          eventId,
          eventKey,
          session.getTenantId(),
          session.getId());
    } catch (DataIntegrityViolationException failure) {
      // Both producers and HTTP retries converge on one durable intent; anything else is a fault.
      if (!writer.hasInternalIntent(actor.getId(), eventKey)) throw failure;
    }
  }

  /** Validate the actual encrypted event before reserving one claim per current counsellor. */
  public void resolveIntent(long id) {
    ReplyEmailDelivery intent = writer.claim(id).orElse(null);
    if (intent == null) return;
    if (intent.getRecipientKind() != RecipientKind.INTERNAL_INTENT) {
      writer.finish(id, Status.REJECTED);
      return;
    }
    try {
      if (!tenantAllowsMail(
          id,
          intent.getTenantId(),
          at(
              Stage.TENANT_CONTEXT,
              () -> tenants.getRestrictedTenantDataFresh(intent.getTenantId())))) {
        writer.finish(id, Status.REJECTED);
        return;
      }
      Session session = resolveGroup(intent.getSourceRoomId());
      Consultant actor =
          consultants.findByIdAndDeleteDateIsNull(intent.getRecipientUserId()).orElse(null);
      Set<String> participantIds = participantIds(session);
      if (!matches(session, intent, actor, participantIds)) {
        writer.finish(id, Status.REJECTED);
        return;
      }
      List<String> members = currentMembers(intent.getSourceRoomId());
      if (!members.contains(actor.getMatrixUserId()) || !verifiedMatrixEvent(intent, actor)) {
        writer.finish(id, Status.REJECTED);
        return;
      }
      for (String participantId : participantIds) {
        Consultant recipient = consultants.findByIdAndDeleteDateIsNull(participantId).orElse(null);
        if (!eligibleRecipient(session, actor, recipient, participantIds)
            || !members.contains(recipient.getMatrixUserId())) continue;
        try {
          writer.reserveInternalRecipient(recipient.getId(), intent);
        } catch (DataIntegrityViolationException replay) {
          // A partial fan-out is safe to resolve again; any other constraint is a fault to retry.
          if (!writer.hasInternalRecipient(recipient.getId(), intent.getEventKey())) throw replay;
        }
      }
      writer.finish(id, Status.RESOLVED);
    } catch (RuntimeException unavailable) {
      retryPreflight(intent, unavailable);
    }
  }

  /** Recheck current membership, tenancy and the dedicated preference immediately before mail. */
  public void deliverPending(long id) {
    ReplyEmailDelivery claim = writer.claim(id).orElse(null);
    if (claim == null) return;
    if (claim.getRecipientKind() != RecipientKind.INTERNAL) {
      writer.finish(id, Status.REJECTED);
      return;
    }
    Session session;
    Consultant actor;
    Consultant recipient;
    try {
      session = resolveGroup(claim.getSourceRoomId());
      actor =
          consultants
              .findByMatrixUserIdAndDeleteDateIsNull(claim.getSourceMatrixUserId())
              .orElse(null);
      recipient = consultants.findByIdAndDeleteDateIsNull(claim.getRecipientUserId()).orElse(null);
      Set<String> participantIds = participantIds(session);
      if (!matches(session, claim, actor, participantIds)
          || !eligibleRecipient(session, actor, recipient, participantIds)) {
        writer.finish(id, Status.REJECTED);
        return;
      }
      List<String> members = currentMembers(claim.getSourceRoomId());
      if (!members.contains(actor.getMatrixUserId())
          || !members.contains(recipient.getMatrixUserId())
          || !verifiedMatrixEvent(claim, actor)) {
        writer.finish(id, Status.REJECTED);
        return;
      }
    } catch (RuntimeException unavailable) {
      retryPreflight(claim, unavailable);
      return;
    }

    TenantSystemEmailRouteService.Route route;
    OrisoEmailRenderer.RenderedEmail email;
    Stage setupStage = Stage.TENANT_POLICY;
    try {
      var selectedRoute = routes.resolve(claim.getTenantId());
      if (selectedRoute.isEmpty()) {
        writer.finish(id, Status.REJECTED);
        log.info("Internal-chat email {} suppressed by tenant notification policy", id);
        return;
      }
      route = selectedRoute.get();
      setupStage = Stage.TENANT_SMTP;
      delivery.requireConfigured(route);
      setupStage = Stage.TENANT_CONTEXT;
      var tenant = tenants.getRestrictedTenantDataFresh(claim.getTenantId());
      if (!tenantAllowsMail(id, claim.getTenantId(), tenant)) {
        writer.finish(id, Status.REJECTED);
        return;
      }
      if (multitenancyEnabled && !singleDomainMultitenancy && isBlank(tenant.getSubdomain())) {
        throw failure(Stage.TENANT_CONTEXT, Reason.TENANT_SUBDOMAIN_MISSING);
      }
      String baseUrl =
          AdviceSeekerReplyEmailService.requireBaseUrl(
              multitenancyEnabled ? tenantTemplates.getTenantBaseUrl(tenant) : applicationBaseUrl);
      setupStage = Stage.TEMPLATE;
      var tenantBrand = branding.resolveNotification(claim.getTenantId(), baseUrl);
      var values = emailBrand.valuesForResolvedBrand(baseUrl, tenantBrand);
      values.put("platformName", values.get("offeringName"));
      if (tenantBrand.logoUrl() != null) values.put("logoUrl", tenantBrand.logoUrl());
      String room =
          URLEncoder.encode(session.getMatrixRoomId(), StandardCharsets.UTF_8).replace("+", "%20");
      values.put(
          "messageUrl",
          baseUrl + "/sessions/consultant/sessionView/" + room + "/" + session.getId());
      values.put("unsubscribeUrl", baseUrl + "/profile/einstellungen/email?mail=interne-nachricht");
      var tone = OrisoEmailRenderer.Tone.of(recipient.getLanguageCode());
      if (tone == OrisoEmailRenderer.Tone.DE_FORMAL && !recipient.isLanguageFormal())
        tone = OrisoEmailRenderer.Tone.DE_INFORMAL;
      email = renderer.render("neue-nachricht-beratung", tone, values);
    } catch (RuntimeException unavailable) {
      retryPreflight(claim, setupStage, unavailable);
      return;
    }
    try {
      delivery.sendReply(
          claim.getTenantId(),
          route,
          recipient.getEmail(),
          email,
          java.util.UUID.fromString(claim.getCorrelationId()));
      writer.finish(id, Status.SENT);
    } catch (TenantSystemEmailRouteService.ConfigurationException unavailable) {
      retryPreflight(claim, unavailable);
    } catch (RuntimeException uncertain) {
      writer.finish(id, Status.UNCERTAIN);
      throw uncertain;
    }
  }

  /**
   * FE#1725 story 40: the internal group chats tile switch; an absent switch means on. A missing
   * Träger fails closed: no mail, never retried.
   */
  private static boolean tenantAllowsMail(long id, Long tenantId, RestrictedTenantDTO tenant) {
    if (tenant == null || !Objects.equals(tenant.getId(), tenantId)) {
      log.warn("Internal-chat email {} rejected: Träger {} is unavailable", id, tenantId);
      return false;
    }
    boolean allowed =
        tenant.getSettings() == null
            || !Boolean.FALSE.equals(
                tenant.getSettings().getFeatureConsultantEmailInternalGroupEnabled());
    if (!allowed) {
      log.info("Internal-chat email {} suppressed by the Träger's internal group switch", id);
    }
    return allowed;
  }

  private Session resolveGroup(String roomId) {
    if (isBlank(roomId)) return null;
    Session session = sessions.findByMatrixRoomId(roomId).orElse(null);
    return session != null
            && session.getId() != null
            && session.getTenantId() != null
            && session.getTenantId() > 0
            && roomId.equals(session.getMatrixRoomId())
            && session.getConversationType() == ConversationType.INTERNAL_GROUP
            && session.getStatus() == Session.SessionStatus.IN_PROGRESS
        ? session
        : null;
  }

  private boolean currentParticipant(
      Session session, Consultant consultant, Set<String> participantIds) {
    return session != null
        && consultant != null
        && consultant.getId() != null
        && consultant.getDeleteDate() == null
        && ACTIVE_CONSULTANT_STATUSES.contains(consultant.getStatus())
        && !isBlank(consultant.getMatrixUserId())
        && Objects.equals(consultant.getTenantId(), session.getTenantId())
        && participantIds.contains(consultant.getId());
  }

  private boolean matches(
      Session session, ReplyEmailDelivery claim, Consultant actor, Set<String> participantIds) {
    return currentParticipant(session, actor, participantIds)
        && accountEligibility.allows(actor.getId())
        && Objects.equals(session.getId(), claim.getSessionId())
        && Objects.equals(session.getTenantId(), claim.getTenantId())
        && Objects.equals(actor.getMatrixUserId(), claim.getSourceMatrixUserId())
        && !isBlank(claim.getSourceEventId());
  }

  private boolean eligibleRecipient(
      Session session, Consultant actor, Consultant recipient, Set<String> participantIds) {
    return currentParticipant(session, recipient, participantIds)
        && actor != null
        && !Objects.equals(actor.getId(), recipient.getId())
        && !recipient.isAbsent()
        && recipient.isNotificationsEnabled()
        && (isBlank(recipient.getNotificationsSettings())
            || !Boolean.FALSE.equals(
                deserializeNotificationSettingsDTOOrDefaultIfNull(recipient)
                    .getInternalChatNotificationEnabled()))
        && accountEligibility.allows(recipient.getId())
        && !doNotDisturb.isInDoNotDisturb(recipient.getId())
        && !isBlank(recipient.getEmail())
        && (isBlank(emailDummySuffix) || !recipient.getEmail().endsWith(emailDummySuffix));
  }

  private Set<String> participantIds(Session session) {
    if (session == null) return Set.of();
    return participants.findByChatId(session.getId()).stream()
        .map(GroupChatParticipant::getConsultantId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private List<String> currentMembers(String roomId) {
    return at(
        Stage.MATRIX_MEMBERSHIP,
        () ->
            matrix
                .getRoomMembers(roomId)
                .orElseThrow(
                    () -> failure(Stage.MATRIX_MEMBERSHIP, Reason.DEPENDENCY_UNAVAILABLE)));
  }

  private boolean verifiedMatrixEvent(ReplyEmailDelivery claim, Consultant actor) {
    String token =
        at(
            Stage.MATRIX_AUTHENTICATION,
            () -> matrix.loginAsUserAccessToken(actor.getMatrixUserId()));
    if (isBlank(token)) throw failure(Stage.MATRIX_AUTHENTICATION, Reason.DEPENDENCY_UNAVAILABLE);
    // A 404 is terminal (unknown or browser-invented event); any other failure stays retryable.
    Map<String, Object> event =
        at(
                Stage.MATRIX_EVENT,
                () ->
                    matrix.findRoomEvent(claim.getSourceRoomId(), claim.getSourceEventId(), token))
            .orElse(null);
    if (event == null) return false;
    if (!Objects.equals(claim.getSourceEventId(), event.get("event_id"))
        || !Objects.equals(actor.getMatrixUserId(), event.get("sender"))
        || !(event.get("content") instanceof Map<?, ?> content)) return false;
    if (!(Objects.equals("m.room.encrypted", event.get("type"))
        ? content.get("ciphertext") instanceof String ciphertext && !isBlank(ciphertext)
        : Objects.equals("m.room.message", event.get("type")) && isUserMessage(content)))
      return false;
    if (content.get("m.relates_to") instanceof Map<?, ?> relation) {
      Object type = relation.get("rel_type");
      return !"m.replace".equals(type) && !"m.annotation".equals(type);
    }
    return true;
  }

  /** Rooms created without encryption carry plaintext; notices and empty bodies are no message. */
  private static boolean isUserMessage(Map<?, ?> content) {
    if (!(content.get("msgtype") instanceof String msgtype)
        || !USER_MESSAGE_TYPES.contains(msgtype)) return false;
    return (content.get("body") instanceof String body && !isBlank(body))
        || content.containsKey("url")
        || content.containsKey("file");
  }

  private void retryPreflight(ReplyEmailDelivery row, RuntimeException unavailable) {
    retryPreflight(row, Stage.ELIGIBILITY, unavailable);
  }

  private void retryPreflight(ReplyEmailDelivery row, Stage stage, RuntimeException unavailable) {
    long id = row.getId();
    writer.retryLater(id);
    NotificationEmailDiagnostics.retry(log, "internal-chat", id, stage, unavailable);
  }
}
