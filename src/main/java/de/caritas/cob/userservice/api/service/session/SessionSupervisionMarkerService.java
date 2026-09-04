package de.caritas.cob.userservice.api.service.session;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionSupervisionDTO;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorMarkerRow;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import java.util.Collection;
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
 * <p>One batched query per list page, never one per session.
 */
@Service
@RequiredArgsConstructor
public class SessionSupervisionMarkerService {

  private final @NonNull SessionSupervisorRepository sessionSupervisorRepository;
  private final @NonNull ConsultantDisplayNameResolver consultantDisplayNameResolver;

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
    for (var entry : entries) {
      if (nonNull(entry.getSession()) && nonNull(entry.getSession().getId())) {
        sessionIds.add(entry.getSession().getId());
      }
    }
    if (sessionIds.isEmpty()) {
      return entries;
    }

    var rowsBySession = loadRowsBySession(sessionIds);
    var mapper = new SessionMapper();
    for (var entry : entries) {
      var session = entry.getSession();
      if (nonNull(session) && nonNull(session.getId())) {
        session.setSupervision(
            mapper.toSupervisionDTO(
                rowsBySession.getOrDefault(session.getId(), List.of()),
                requester.getId(),
                this::displayNameOf));
      }
    }
    return entries;
  }

  /**
   * The marker of a single session (single-session read), same query and same rule as the list.
   *
   * @param sessionId the session
   * @param requester the consultant reading it
   * @return the marker, or null when either argument is missing
   */
  public SessionSupervisionDTO buildFor(Long sessionId, Consultant requester) {
    if (isNull(sessionId) || isNull(requester)) {
      return null;
    }
    var rows = loadRowsBySession(Set.of(sessionId)).getOrDefault(sessionId, List.of());
    return new SessionMapper().toSupervisionDTO(rows, requester.getId(), this::displayNameOf);
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
