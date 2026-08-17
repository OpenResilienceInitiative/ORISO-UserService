package de.caritas.cob.userservice.api.service.session;

import static java.util.Collections.emptyList;
import static java.util.Objects.nonNull;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForUserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.config.observability.ConsultantAgencyFallbackTelemetry;
import de.caritas.cob.userservice.api.config.observability.ConsultantAgencyFallbackTelemetry.Reason;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/** Builds user-facing session query responses and resolves their agency data. */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSessionQueryService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull AgencyService agencyService;
  private final @NonNull SessionAccessService sessionAccessService;
  private final @NonNull ConsultantAgencyFallbackTelemetry fallbackTelemetry;

  @Transactional(readOnly = true)
  public List<UserSessionResponseDTO> getSessionsForUserId(String userId) {
    List<Session> sessions = sessionRepository.findByUserUserId(userId);
    if (!isNotEmpty(sessions)) {
      return emptyList();
    }
    // A fixed label, never the user id: this string reaches a WARN log on every
    // AgencyService 403, and the Keycloak user id does not belong in application logs.
    return convertToUserSessionResponseDTO(sessions, fetchAgencies(sessions, "user session list"));
  }

  @Transactional(readOnly = true)
  public List<UserSessionResponseDTO> getSessionsByUserAndRoomIds(
      String userId, Set<String> matrixRoomIds, Set<String> roles) {
    sessionAccessService.checkForAskerRoles(roles);
    var sessions = sessionRepository.findByMatrixRoomIdIn(matrixRoomIds);
    sessions.forEach(
        session -> sessionAccessService.checkAskerPermissionForSession(session, userId, roles));
    return convertToUserSessionResponseDTO(
        sessions, fetchAgencies(sessions, "session list lookup"));
  }

  @Transactional(readOnly = true)
  public List<UserSessionResponseDTO> getSessionsByUserAndSessionIds(
      String userId, Set<Long> sessionIds, Set<String> roles) {
    sessionAccessService.checkForAskerRoles(roles);
    var sessions =
        StreamSupport.stream(sessionRepository.findAllById(sessionIds).spliterator(), false)
            .collect(Collectors.toList());
    sessions.forEach(
        session -> sessionAccessService.checkAskerPermissionForSession(session, userId, roles));
    return convertToUserSessionResponseDTO(
        sessions, fetchAgencies(sessions, "session list lookup"));
  }

  private List<AgencyDTO> fetchAgencies(List<Session> sessions, String context) {
    List<Long> agencyIds =
        sessions.stream()
            .map(Session::getAgencyId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
    return getAgenciesSafely(agencyIds, context);
  }

  private List<AgencyDTO> getAgenciesSafely(List<Long> agencyIds, String context) {
    if (agencyIds.isEmpty()) {
      return emptyList();
    }
    try {
      return agencyService.getAgencies(agencyIds);
    } catch (HttpClientErrorException.Forbidden e) {
      // Counted, not just logged: the caller still returns 200 with `agency` null,
      // so without a metric this degradation is invisible to operators. The
      // exception message is dropped — it can carry the upstream response body.
      recordAgencyFallback(context);
      return emptyList();
    }
  }

  private void recordAgencyFallback(String context) {
    fallbackTelemetry
        .record(Reason.DEPENDENCY_ERROR)
        .ifPresent(
            suppressed ->
                log.warn(
                    "AgencyService forbidden while loading agencies for {}; returning sessions "
                        + "without agency data. suppressedSincePreviousWarning={}. "
                        + "Per-call failures remain available in outbound dependency metrics.",
                    context,
                    suppressed));
  }

  private List<UserSessionResponseDTO> convertToUserSessionResponseDTO(
      List<Session> sessions, List<AgencyDTO> agencies) {
    return sessions.stream()
        .map(session -> buildUserSessionDTO(session, agencies))
        .collect(Collectors.toList());
  }

  private UserSessionResponseDTO buildUserSessionDTO(Session session, List<AgencyDTO> agencies) {
    return new UserSessionResponseDTO()
        .session(new SessionMapper().convertToSessionDTO(session))
        .agency(
            agencies.stream()
                .filter(
                    agency ->
                        agency.getId() != null
                            && session.getAgencyId() != null
                            && agency.getId().longValue() == session.getAgencyId())
                .findAny()
                .orElse(null))
        .consultant(
            nonNull(session.getConsultant())
                ? convertToSessionConsultantForUserDTO(session.getConsultant())
                : null);
  }

  private SessionConsultantForUserDTO convertToSessionConsultantForUserDTO(Consultant consultant) {
    return new SessionConsultantForUserDTO(
        consultant.getId(),
        consultant.getUsername(),
        consultant.isAbsent(),
        consultant.getAbsenceMessage(),
        null);
  }
}
