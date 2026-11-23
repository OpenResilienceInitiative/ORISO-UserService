package de.caritas.cob.userservice.api.service.session;

import static de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask.WAITING_FOR_ROOM_DECISION;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision.Outcome;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRoomDecisionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates the room decision with centre departures that overlap first acceptance. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AgencyEnquiryRoomDecisions {

  static final int SWEEP_TRANSACTION_TIMEOUT_SECONDS = 5;
  private static final Duration PREPARATION_LEASE = Duration.ofMinutes(2);

  private final AgencyEnquiryRoomDecisionRepository decisions;
  private final AgencyEnquiryRevocationTaskRepository tasks;
  private final Clock clock;
  private final JdbcTemplate jdbc;

  static boolean tracksRegistration(Session session) {
    return session.getRegistrationType() == Session.RegistrationType.REGISTERED
        && session.getConversationType() != ConversationType.LIVE_CHAT;
  }

  void begin(Session accepted, SessionStatus openingStatus) {
    decisions.save(
        new AgencyEnquiryRoomDecision(
            accepted, openingStatus, LocalDateTime.now(clock).plus(PREPARATION_LEASE)));
  }

  /** Start the work, or settle an expired preparation and return its original open status. */
  Optional<SessionStatus> startExternalWork(Session current) {
    var decision =
        decisions
            .lockDecision(current.getId(), current.getOwnershipRevision())
            .filter(
                candidate ->
                    candidate.getOutcome() == Outcome.PENDING && candidate.matches(current))
            .filter(candidate -> candidate.getPreparedUntil() != null)
            .orElseThrow(this::preparationChanged);
    if (decision.getPreparedUntil().isAfter(LocalDateTime.now(clock))) {
      decision.clearPreparation();
      return Optional.empty();
    }
    if (!hasOpeningStatus(decision)) {
      throw preparationChanged();
    }
    settle(decision, Outcome.REOPENED);
    return Optional.of(decision.getOpeningStatus());
  }

  private ConflictException preparationChanged() {
    return new ConflictException("Enquiry room preparation is no longer current");
  }

  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      readOnly = true,
      timeout = SWEEP_TRANSACTION_TIMEOUT_SECONDS)
  List<AgencyEnquiryRoomDecision> expiredPreparations(int limit) {
    return decisions.findByPreparedUntilLessThanEqualOrderByPreparedUntilAscIdAsc(
        LocalDateTime.now(clock), PageRequest.of(0, limit));
  }

  /** Lock decision after session, the same order used by the foreground room decision. */
  Optional<SessionStatus> reopenExpiredPreparation(
      AgencyEnquiryRoomDecision candidate, @Nullable Session current) {
    var decision =
        decisions
            .lockDecision(candidate.getSessionId(), candidate.getOwnershipRevision())
            .orElse(null);
    if (decision == null
        || decision.getOutcome() != Outcome.PENDING
        || decision.getPreparedUntil() == null
        || decision.getPreparedUntil().isAfter(LocalDateTime.now(clock))) {
      return Optional.empty();
    }
    if (current == null
        || current.getStatus() != SessionStatus.IN_PROGRESS
        || current.getConsultant() == null
        || !decision.matches(current)
        || !hasOpeningStatus(decision)) {
      // Keep ambiguous rows and their tasks held. They must not block the bounded sweep.
      decision.clearPreparation();
      return Optional.empty();
    }
    settle(decision, Outcome.REOPENED);
    return Optional.of(decision.getOpeningStatus());
  }

  private boolean hasOpeningStatus(AgencyEnquiryRoomDecision decision) {
    return decision.getOpeningStatus() != null
        && AgencyOpenEnquiryMembership.OPEN_STATUSES.contains(decision.getOpeningStatus());
  }

  void resolve(Session accepted, Outcome outcome) {
    // Take the decision row lock before the allocation. A later departure must see the terminal
    // outcome on its current read, even when the acceptance and its decision commit together.
    decisions
        .lockDecision(accepted.getId(), accepted.getOwnershipRevision())
        .filter(decision -> decision.getOutcome() == Outcome.PENDING && decision.matches(accepted))
        .ifPresent(decision -> settle(decision, outcome));
  }

  private void settle(AgencyEnquiryRoomDecision decision, Outcome outcome) {
    decision.resolve(outcome, nextEvent());
    if (outcome == Outcome.REUSED) {
      tasks.discardRoomDecisionTasks(decision.getId(), WAITING_FOR_ROOM_DECISION);
    } else {
      tasks.releaseRoomDecisionTasks(
          decision.getId(), WAITING_FOR_ROOM_DECISION, LocalDateTime.now(clock));
    }
  }

  long nextEvent() {
    return jdbc.queryForObject(
        "SELECT NEXT VALUE FOR " + AgencyEnquiryRoomDecision.EVENT_SEQUENCE, Long.class);
  }

  List<AgencyEnquiryRoomDecision> affectedBy(ConsultantAgency removed, long departureOrder) {
    return decisions.lockAffectedDecisions(
        removed.getTenantId(), removed.getAgencyId(), departureOrder);
  }

  Optional<AgencyEnquiryRoomDecision> forTask(AgencyEnquiryRevocationTask task, Session current) {
    return decisions
        .findById(task.getRoomDecisionId())
        .filter(decision -> decision.matches(task, current));
  }
}
