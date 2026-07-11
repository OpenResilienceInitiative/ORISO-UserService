package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.ConsultantStatisticsRepository;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds the statistics shown on the consultant's own profile from the application-layer database
 * only (KDG compliance: no monitoring data, no message content). A consultant can only ever see
 * their own numbers: the consultant id is taken from the authenticated user, never from the
 * request. Upstream metrics without an app-layer source (sent messages, video call duration) are
 * intentionally not part of the response.
 */
@Service
@RequiredArgsConstructor
public class ConsultantStatisticsService {

  private final @NonNull ConsultantStatisticsRepository consultantStatisticsRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;

  public record ConsultantStatistics(
      String startDate,
      String endDate,
      long numberOfAssignedSessions,
      long numberOfActiveSessions) {}

  public ConsultantStatistics buildStatistics(String startDate, String endDate) {
    if (!authenticatedUser.isConsultant()) {
      throw new ForbiddenException(
          "User %s is not authorized to access consultant statistics"
              .formatted(authenticatedUser.getUserId()));
    }

    var from = parseDate(startDate);
    var toInclusive = parseDate(endDate);
    if (toInclusive.isBefore(from)) {
      throw new BadRequestException("endDate must not be before startDate");
    }

    var fromDateTime = from.atStartOfDay();
    var toDateTime = toInclusive.plusDays(1).atStartOfDay();
    var consultantId = authenticatedUser.getUserId();

    return new ConsultantStatistics(
        from.toString(),
        toInclusive.toString(),
        consultantStatisticsRepository.countAssignedSessionsCreatedInPeriod(
            consultantId, fromDateTime, toDateTime),
        consultantStatisticsRepository.countActiveSessionsInPeriod(
            consultantId, fromDateTime, toDateTime));
  }

  private LocalDate parseDate(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException | NullPointerException e) {
      throw new BadRequestException(
          "Invalid date '%s', expected format yyyy-MM-dd".formatted(value), e);
    }
  }
}
