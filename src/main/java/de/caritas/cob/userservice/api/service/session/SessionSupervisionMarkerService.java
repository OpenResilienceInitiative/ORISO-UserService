package de.caritas.cob.userservice.api.service.session;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionSupervisionDTO;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorMarkerRow;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Fills {@code SessionDTO.supervision} — the explicit ADR-008 supervisor marker — for the
 * consultant who asked for the list (ADR-008 addendum 2026-09-04, presentation).
 *
 * <p>Before this marker the frontend had to guess "am I the supervisor here?" from {@code
 * consultant.id !== me}, which is also true for team-agency cases and for handed-over cases. The
 * marker states it: {@code supervisedByMe} is true exactly when the requester is an active {@code
 * SessionSupervisor} of the session.
 *
 * <p>The marker also carries {@code counsellorDisplayName} — the assigned consultant's internal
 * display name (#996 rule, never a real name) — because the consultant-facing list DTO exposes only
 * id/firstName/lastName and the public consultant endpoint hides the display name of a non-public
 * consultant; without it a supervisor's panel cannot title the case by its counsellor.
 *
 * <p>Two batched queries per list page (supervisor rows, counsellor names), never one per session.
 */
@Service
@RequiredArgsConstructor
public class SessionSupervisionMarkerService {

  private final @NonNull SessionSupervisorRepository sessionSupervisorRepository;
  private final @NonNull ConsultantDisplayNameResolver consultantDisplayNameResolver;
  private final @NonNull ConsultantRepository consultantRepository;

  /**
   * Sets the supervision marker on every entry that carries a session id, as seen by the requester.
   * Entries without a session (pure chat rows) are left untouched.
   *
   * @param entries the list page (returned as-is, mutated in place)
   * @param requester the consultant reading the list
   * @return the same list
   */
  public List<ConsultantSessionResponseDTO> enrich(
      List<ConsultantSessionResponseDTO> entries, Consultant requester) {
    if (isNull(entries) || entries.isEmpty() || isNull(requester)) {
      return entries;
    }
    Set<Long> sessionIds = new LinkedHashSet<>();
    Set<String> counsellorIds = new LinkedHashSet<>();
    for (var entry : entries) {
      if (nonNull(entry.getSession()) && nonNull(entry.getSession().getId())) {
        sessionIds.add(entry.getSession().getId());
        if (nonNull(entry.getConsultant()) && nonNull(entry.getConsultant().getId())) {
          counsellorIds.add(entry.getConsultant().getId());
        }
      }
    }
    if (sessionIds.isEmpty()) {
      return entries;
    }

    var rowsBySession = loadRowsBySession(sessionIds);
    var counsellorNames = loadCounsellorDisplayNames(counsellorIds);
    var mapper = new SessionMapper();
    for (var entry : entries) {
      var session = entry.getSession();
      if (nonNull(session) && nonNull(session.getId())) {
        var counsellorId = nonNull(entry.getConsultant()) ? entry.getConsultant().getId() : null;
        session.setSupervision(
            mapper.toSupervisionDTO(
                rowsBySession.getOrDefault(session.getId(), List.of()),
                requester.getId(),
                this::displayNameOf,
                nonNull(counsellorId) ? counsellorNames.get(counsellorId) : null));
      }
    }
    return entries;
  }

  /**
   * The marker of a single session (single-session read), same query and same rule as the list. The
   * counsellor name comes straight from {@code session.getConsultant()} — no extra query.
   *
   * @param session the loaded session
   * @param requester the consultant reading it
   * @return the marker, or null when the session, its id or the requester is missing
   */
  public SessionSupervisionDTO buildFor(Session session, Consultant requester) {
    if (isNull(session) || isNull(session.getId()) || isNull(requester)) {
      return null;
    }
    var sessionId = session.getId();
    var rows = loadRowsBySession(Set.of(sessionId)).getOrDefault(sessionId, List.of());
    return new SessionMapper()
        .toSupervisionDTO(
            rows,
            requester.getId(),
            this::displayNameOf,
            consultantDisplayNameResolver.resolveInternalDisplayName(session.getConsultant()));
  }

  /** One query for all counsellors of the page; id → internal display name (#996 rule). */
  private Map<String, String> loadCounsellorDisplayNames(Collection<String> consultantIds) {
    Map<String, String> names = new HashMap<>();
    if (consultantIds.isEmpty()) {
      return names;
    }
    for (var consultant : consultantRepository.findAllByIdIn(new ArrayList<>(consultantIds))) {
      names.put(
          consultant.getId(), consultantDisplayNameResolver.resolveInternalDisplayName(consultant));
    }
    return names;
  }

  private Map<Long, List<SessionSupervisorMarkerRow>> loadRowsBySession(
      Collection<Long> sessionIds) {
    return sessionSupervisorRepository.findActiveMarkerRowsBySessionIdIn(sessionIds).stream()
        .collect(Collectors.groupingBy(SessionSupervisorMarkerRow::sessionId));
  }

  private String displayNameOf(SessionSupervisorMarkerRow row) {
    return consultantDisplayNameResolver.resolveInternalDisplayName(
        row.internalDisplayName(), row.displayName(), row.username());
  }
}
