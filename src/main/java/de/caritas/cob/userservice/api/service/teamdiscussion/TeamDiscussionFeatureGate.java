package de.caritas.cob.userservice.api.service.teamdiscussion;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.conversationfeature.ConversationFeature;
import de.caritas.cob.userservice.api.service.conversationfeature.ConversationFeatureGate;
import de.caritas.cob.userservice.api.service.conversationfeature.ConversationKind;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Enforces the Team-Chat switch at the backend boundary (US#473 / ADR-016, ORISO-Frontend#2491).
 *
 * <p>The team chat is controlled like Supervision: the effective switch is the Träger's {@code
 * featureTeamDiscussionEnabled} AND the enquiry's Beratungsstelle's {@code
 * featureTeamDiscussionEnabled}, read by {@link ConversationFeatureGate} with the same semantics
 * (unset = on, unreadable = 403, provider outage = 503). An AgencyService that does not know the
 * agency value yet serves none, which reads as unset, so on.
 *
 * <p>The deploy property {@code team-discussion.enabled} (default true) stays as a global kill
 * switch: false refuses the team chat on every Träger before any setting is read.
 *
 * <p>Whether the team chat continues after acceptance ({@code
 * featureTeamDiscussionAfterAcceptEnabled}) is read the same way, but unset counts as off, and it
 * only counts while the master switch is on.
 */
@Service
@RequiredArgsConstructor
public class TeamDiscussionFeatureGate {

  private final @NonNull ConversationFeatureGate conversationFeatureGate;

  @Value("${team-discussion.enabled:true}")
  private boolean teamDiscussionEnabled;

  /**
   * @throws CustomValidationHttpStatusException 403 (FEATURE_DISABLED) when switched off on any
   *     level or not readable
   * @throws de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException 503
   *     when the TenantService or AgencyService cannot be reached
   */
  public void requireEnabled(Session session) {
    requireEnabled(session.getTenantId(), session.getAgencyId());
  }

  /** As {@link #requireEnabled(Session)} for a given Beratungsstelle (D4: a former one's chat). */
  public void requireEnabled(Long tenantId, Long agencyId) {
    if (!teamDiscussionEnabled) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.FEATURE_DISABLED, HttpStatus.FORBIDDEN);
    }
    // The team chat has only a master switch; the kind merely selects no per-kind switch.
    conversationFeatureGate.require(
        ConversationFeature.TEAM_DISCUSSION, ConversationKind.ONE_ON_ONE, tenantId, agencyId);
  }

  /**
   * Whether the team chat is switched on for this case (kill switch, Träger AND Beratungsstelle).
   * Switched off or not readable answers false.
   *
   * @throws de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException 503
   *     when the settings cannot be reached
   */
  public boolean isEnabled(Session session) {
    try {
      requireEnabled(session);
      return true;
    } catch (CustomValidationHttpStatusException off) {
      return false;
    }
  }

  /**
   * Whether the enquiry's team chat continues after acceptance instead of being archived: master
   * switch AND {@code featureTeamDiscussionAfterAcceptEnabled}, each Träger AND Beratungsstelle.
   * Switched off, unset or not readable answers false (archive as before).
   *
   * @throws de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException 503
   *     when the settings cannot be reached: the caller must not archive on an outage
   */
  public boolean continuesAfterAccept(Session session) {
    if (!teamDiscussionEnabled) {
      return false;
    }
    try {
      conversationFeatureGate.require(
          List.of(
              ConversationFeature.TEAM_DISCUSSION,
              ConversationFeature.TEAM_DISCUSSION_AFTER_ACCEPT),
          ConversationKind.ONE_ON_ONE,
          session.getTenantId(),
          session.getAgencyId());
      return true;
    } catch (CustomValidationHttpStatusException off) {
      return false;
    }
  }
}
