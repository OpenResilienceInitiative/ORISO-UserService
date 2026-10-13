package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsDTOOrDefaultIfNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.Status;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Durable, content-free email for actual supervisor feedback in a case's protected Matrix room. The
 * browser's intent classifies the event; it never grants room or case authority.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackMessageEmailService {
  private final @NonNull SessionRepository sessions;
  private final @NonNull SessionSupervisorRepository supervision;
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull ConsultantAgencyRepository agencies;
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

  /**
   * Persist the intent before returning success to the browser. Matrix can make its event visible
   * after this POST, so event verification happens in the retrying worker.
   */
  public void onFeedbackIntent(String roomId, String eventId, AuthenticatedUser caller) {
    if (isBlank(roomId) || isBlank(eventId) || caller == null || !caller.isConsultant()) {
      throw new IllegalArgumentException(
          "Feedback mail intent requires a consultant and Matrix event");
    }
    Consultant actor = consultants.findByIdAndDeleteDateIsNull(caller.getUserId()).orElse(null);
    FeedbackCase currentCase = resolveCase(roomId);
    if (actor == null
        || currentCase == null
        || !Objects.equals(actor.getTenantId(), caller.getTenantId())
        || !isCurrentActor(currentCase, actor)) {
      // A caller flag cannot enroll an arbitrary Matrix room or case into email delivery.
      return;
    }
    try {
      writer.reserveFeedbackIntent(
          actor.getId(),
          actor.getMatrixUserId(),
          roomId,
          eventId,
          AdviceSeekerReplyEmailService.eventKey(roomId, eventId),
          currentCase.session().getTenantId(),
          currentCase.session().getId());
    } catch (DataIntegrityViolationException replay) {
      // An acknowledged intent may be retried after a lost HTTP response.
    }
  }

  /** Resolve one persisted intent into one unique claim per currently eligible recipient. */
  public void resolveIntent(long id) {
    ReplyEmailDelivery intent = writer.claim(id).orElse(null);
    if (intent == null) {
      return;
    }
    if (intent.getRecipientKind() != RecipientKind.FEEDBACK_INTENT) {
      writer.finish(id, Status.REJECTED);
      return;
    }
    try {
      FeedbackCase currentCase = resolveCase(intent.getSourceRoomId());
      Consultant actor =
          consultants.findByIdAndDeleteDateIsNull(intent.getRecipientUserId()).orElse(null);
      if (!matchesIntent(currentCase, intent, actor)) {
        writer.finish(id, Status.REJECTED);
        return;
      }
      List<String> members = currentMembers(intent.getSourceRoomId());
      if (!members.contains(actor.getMatrixUserId())) {
        writer.finish(id, Status.REJECTED);
        return;
      }
      if (!verifiedMatrixEvent(intent, actor)) {
        writer.finish(id, Status.REJECTED);
        return;
      }
      for (Consultant candidate : recipients(currentCase, actor)) {
        Consultant recipient =
            consultants.findByIdAndDeleteDateIsNull(candidate.getId()).orElse(null);
        if (recipient == null
            || Objects.equals(actor.getId(), recipient.getId())
            || !eligibleRecipient(recipient, currentCase.session())
            || !members.contains(recipient.getMatrixUserId())) {
          continue;
        }
        try {
          writer.reserveFeedbackRecipient(recipient.getId(), intent);
        } catch (DataIntegrityViolationException replay) {
          // A prior partial fan-out already reserved this event for this recipient.
        }
      }
      writer.finish(id, Status.RESOLVED);
    } catch (RuntimeException unavailable) {
      writer.retryLater(id);
      log.warn(
          "Feedback intent {} cannot be resolved yet ({})",
          id,
          unavailable.getClass().getSimpleName());
    }
  }

  /** Recheck live authority, preference and routing before the SMTP attempt. */
  public void deliverPending(long id) {
    ReplyEmailDelivery claim = writer.claim(id).orElse(null);
    if (claim == null) {
      return;
    }
    if (claim.getRecipientKind() != RecipientKind.FEEDBACK) {
      writer.finish(id, Status.REJECTED);
      return;
    }
    FeedbackCase currentCase;
    Consultant actor;
    Consultant recipient;
    try {
      currentCase = resolveCase(claim.getSourceRoomId());
      actor =
          consultants
              .findByMatrixUserIdAndDeleteDateIsNull(claim.getSourceMatrixUserId())
              .orElse(null);
      recipient = consultants.findByIdAndDeleteDateIsNull(claim.getRecipientUserId()).orElse(null);
      if (!matchesClaim(currentCase, claim, actor, recipient)) {
        writer.finish(id, Status.REJECTED);
        return;
      }
      List<String> members = currentMembers(claim.getSourceRoomId());
      if (!members.contains(actor.getMatrixUserId())
          || !members.contains(recipient.getMatrixUserId())) {
        writer.finish(id, Status.REJECTED);
        return;
      }
      if (!verifiedMatrixEvent(claim, actor)) {
        writer.finish(id, Status.REJECTED);
        return;
      }
    } catch (RuntimeException unavailable) {
      retryPreflight(id, unavailable);
      return;
    }

    TenantSystemEmailRouteService.Route route;
    OrisoEmailRenderer.RenderedEmail email;
    try {
      route =
          routes
              .resolve(claim.getTenantId())
              .orElseThrow(() -> new IllegalStateException("Feedback email SMTP route is missing"));
      delivery.requireConfigured(route);
      var tenant = tenants.getRestrictedTenantDataFresh(claim.getTenantId());
      if (tenant == null || !Objects.equals(tenant.getId(), claim.getTenantId())) {
        throw new IllegalStateException("Feedback email tenant is unavailable");
      }
      if (multitenancyEnabled && !singleDomainMultitenancy && isBlank(tenant.getSubdomain())) {
        throw new IllegalStateException("Feedback email tenant subdomain is missing");
      }
      String baseUrl =
          AdviceSeekerReplyEmailService.requireBaseUrl(
              multitenancyEnabled ? tenantTemplates.getTenantBaseUrl(tenant) : applicationBaseUrl);
      var tenantBrand = branding.resolveNotification(claim.getTenantId(), baseUrl);
      var values = emailBrand.valuesForResolvedBrand(baseUrl, tenantBrand);
      values.put("platformName", values.get("offeringName"));
      if (tenantBrand.logoUrl() != null) {
        values.put("logoUrl", tenantBrand.logoUrl());
      }
      values.put(
          "messageUrl",
          baseUrl
              + "/sessions/consultant/sessionView/session/"
              + currentCase.session().getId()
              + "?channel=supervision&at="
              + URLEncoder.encode(claim.getSourceEventId(), StandardCharsets.UTF_8));
      var tone = OrisoEmailRenderer.Tone.of(recipient.getLanguageCode());
      if (tone == OrisoEmailRenderer.Tone.DE_FORMAL && !recipient.isLanguageFormal()) {
        tone = OrisoEmailRenderer.Tone.DE_INFORMAL;
      }
      email = renderer.render("rueckmeldung", tone, values);
    } catch (RuntimeException unavailable) {
      retryPreflight(id, unavailable);
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
      retryPreflight(id, unavailable);
    } catch (RuntimeException uncertain) {
      writer.finish(id, Status.UNCERTAIN);
      throw uncertain;
    }
  }

  private FeedbackCase resolveCase(String roomId) {
    if (isBlank(roomId)) {
      return null;
    }
    List<SessionSupervisor> active = supervision.findByMatrixRoomIdAndIsActiveTrue(roomId);
    if (active.isEmpty() || active.getFirst().getSession() == null) {
      return null;
    }
    Long sessionId = active.getFirst().getSession().getId();
    if (sessionId == null
        || active.stream()
            .anyMatch(
                row ->
                    row.getSession() == null
                        || !Objects.equals(row.getSession().getId(), sessionId))) {
      return null;
    }
    Session session = sessions.findById(sessionId).orElse(null);
    if (session == null
        || session.getTenantId() == null
        || session.getTenantId() <= 0
        || session.getAgencyId() == null
        || session.getAgencyId() <= 0
        || session.getConsultant() == null
        || session.getStatus() != Session.SessionStatus.IN_PROGRESS
        || Boolean.TRUE.equals(session.getSupervisionOptedOut())
        || isBlank(session.getMatrixRoomId())
        || roomId.equals(session.getMatrixRoomId())) {
      return null;
    }
    return new FeedbackCase(session, active);
  }

  private boolean matchesIntent(FeedbackCase context, ReplyEmailDelivery intent, Consultant actor) {
    return context != null
        && actor != null
        && Objects.equals(context.session().getId(), intent.getSessionId())
        && Objects.equals(context.session().getTenantId(), intent.getTenantId())
        && Objects.equals(actor.getId(), intent.getRecipientUserId())
        && Objects.equals(actor.getMatrixUserId(), intent.getSourceMatrixUserId())
        && !isBlank(intent.getSourceEventId())
        && isCurrentActor(context, actor);
  }

  private boolean matchesClaim(
      FeedbackCase context, ReplyEmailDelivery claim, Consultant actor, Consultant recipient) {
    return context != null
        && actor != null
        && recipient != null
        && Objects.equals(context.session().getId(), claim.getSessionId())
        && Objects.equals(context.session().getTenantId(), claim.getTenantId())
        && !isBlank(claim.getSourceEventId())
        && isCurrentActor(context, actor)
        && !Objects.equals(actor.getId(), recipient.getId())
        && recipients(context, actor).stream()
            .anyMatch(candidate -> Objects.equals(candidate.getId(), recipient.getId()))
        && eligibleRecipient(recipient, context.session());
  }

  private boolean isCurrentActor(FeedbackCase context, Consultant actor) {
    if (!currentConsultantForCase(actor, context.session())) {
      return false;
    }
    if (Objects.equals(context.session().getConsultant().getId(), actor.getId())) {
      return true;
    }
    return actor.isSupervisor()
        && context.supervisors().stream()
            .anyMatch(
                row ->
                    row.getSupervisorConsultant() != null
                        && Objects.equals(row.getSupervisorConsultant().getId(), actor.getId()));
  }

  private List<Consultant> recipients(FeedbackCase context, Consultant actor) {
    if (Objects.equals(context.session().getConsultant().getId(), actor.getId())) {
      Set<String> seen = new HashSet<>();
      List<Consultant> result = new ArrayList<>();
      for (SessionSupervisor row : context.supervisors()) {
        Consultant candidate = row.getSupervisorConsultant();
        if (candidate != null && candidate.isSupervisor() && seen.add(candidate.getId())) {
          result.add(candidate);
        }
      }
      return result;
    }
    return List.of(context.session().getConsultant());
  }

  private boolean eligibleRecipient(Consultant recipient, Session session) {
    return currentConsultantForCase(recipient, session)
        && !recipient.isAbsent()
        && recipient.isNotificationsEnabled()
        && (isBlank(recipient.getNotificationsSettings())
            || !Boolean.FALSE.equals(
                deserializeNotificationSettingsDTOOrDefaultIfNull(recipient)
                    .getFeedbackNotificationEnabled()))
        && !doNotDisturb.isInDoNotDisturb(recipient.getId())
        && !isBlank(recipient.getEmail())
        && (isBlank(emailDummySuffix) || !recipient.getEmail().endsWith(emailDummySuffix));
  }

  private boolean currentConsultantForCase(Consultant consultant, Session session) {
    return consultant != null
        && consultant.getId() != null
        && consultant.getDeleteDate() == null
        && !isBlank(consultant.getMatrixUserId())
        && Objects.equals(consultant.getTenantId(), session.getTenantId())
        && agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            consultant.getId(), session.getAgencyId());
  }

  private List<String> currentMembers(String roomId) {
    return matrix
        .getRoomMembers(roomId)
        .orElseThrow(() -> new IllegalStateException("Feedback room membership is unavailable"));
  }

  private boolean verifiedMatrixEvent(ReplyEmailDelivery claim, Consultant actor) {
    String token = matrix.loginAsUserAccessToken(actor.getMatrixUserId());
    if (isBlank(token)) {
      throw new IllegalStateException("Feedback Matrix token is unavailable");
    }
    Map<String, Object> event =
        matrix
            .getRoomEvent(claim.getSourceRoomId(), claim.getSourceEventId(), token)
            .orElseThrow(() -> new IllegalStateException("Feedback Matrix event is unavailable"));
    if (!Objects.equals(claim.getSourceEventId(), event.get("event_id"))
        || !Objects.equals(actor.getMatrixUserId(), event.get("sender"))
        || !Objects.equals("m.room.encrypted", event.get("type"))) {
      return false;
    }
    if (!(event.get("content") instanceof Map<?, ?> content)
        || !(content.get("ciphertext") instanceof String ciphertext)
        || isBlank(ciphertext)) {
      return false;
    }
    if (content.get("m.relates_to") instanceof Map<?, ?> relation) {
      Object relationType = relation.get("rel_type");
      return !"m.replace".equals(relationType) && !"m.annotation".equals(relationType);
    }
    return true;
  }

  private void retryPreflight(long id, RuntimeException unavailable) {
    writer.retryLater(id);
    log.warn(
        "Feedback email {} preflight unavailable ({})", id, unavailable.getClass().getSimpleName());
  }

  private record FeedbackCase(Session session, List<SessionSupervisor> supervisors) {}
}
