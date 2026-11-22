package de.caritas.cob.userservice.api.service.session;

import static de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask.WAITING_FOR_ROOM_DECISION;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision.Outcome;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRoomDecisionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates the room decision with centre departures that overlap first acceptance. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AgencyEnquiryRoomDecisions {

  private final AgencyEnquiryRoomDecisionRepository decisions;
  private final AgencyEnquiryRevocationTaskRepository tasks;
  private final Clock clock;
  private final JdbcTemplate jdbc;

  static boolean tracksRegistration(Session session) {
    return session.getRegistrationType() == Session.RegistrationType.REGISTERED
        && session.getConversationType() != ConversationType.LIVE_CHAT;
  }

  void begin(Session accepted) {
    decisions.save(new AgencyEnquiryRoomDecision(accepted));
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
