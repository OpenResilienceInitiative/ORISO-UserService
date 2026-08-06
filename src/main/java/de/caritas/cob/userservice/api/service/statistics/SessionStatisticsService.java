package de.caritas.cob.userservice.api.service.statistics;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.statistics.model.SessionStatisticsResultDTO;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SessionStatisticsService {

  private final @NonNull SessionRepository sessionRepository;

  /**
   * Retrieve session data via session ID or Matrix room ID. If both are given, the session ID takes
   * precedence.
   *
   * @param sessionId the session id
   * @param matrixRoomId Matrix room ID
   * @return an {@link SessionStatisticsResultDTO} instance.
   */
  public SessionStatisticsResultDTO retrieveSession(Long sessionId, String matrixRoomId) {

    checkRequestParameter(sessionId, matrixRoomId);
    Optional<Session> session = retrieveSessionViaSessionIdOrMatrixRoomId(sessionId, matrixRoomId);
    return buildSessionStatisticsResultDTO(
        session.orElseThrow(
            () ->
                new NotFoundException(
                    "Session with id %s or Matrix room ID %s not found", sessionId, matrixRoomId)));
  }

  private void checkRequestParameter(Long sessionId, String matrixRoomId) {
    if (isNull(sessionId) && isNull(matrixRoomId)) {
      throw new BadRequestException("sessionId or matrixRoomId required");
    }
  }

  private Optional<Session> retrieveSessionViaSessionIdOrMatrixRoomId(
      Long sessionId, String matrixRoomId) {
    if (nonNull(sessionId)) {
      return sessionRepository.findById(sessionId);
    } else {
      return sessionRepository.findByMatrixRoomId(matrixRoomId);
    }
  }

  private SessionStatisticsResultDTO buildSessionStatisticsResultDTO(Session session) {
    return new SessionStatisticsResultDTO()
        .id(session.getId())
        .matrixRoomId(session.getMatrixRoomId())
        .agencyId(session.getAgencyId())
        .consultingType(session.getConsultingTypeId())
        .isTeamSession(session.isTeamSession())
        .createDate(session.getCreateDate() != null ? session.getCreateDate().toString() : null)
        .messageDate(
            session.getEnquiryMessageDate() != null
                ? session.getEnquiryMessageDate().toString()
                : null)
        .postcode(session.getPostcode());
  }
}
