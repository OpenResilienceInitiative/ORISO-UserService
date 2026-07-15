package de.caritas.cob.userservice.api.service.statistics;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.ConsultantStatisticsRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds the statistics shown on the consultant's own profile from the application-layer database
 * only (KDG compliance: no monitoring data, no message content). A consultant can only ever see
 * their own numbers: the consultant id is taken from the authenticated user, never from the
 * request. Upstream metrics without an app-layer source (video call duration, appointments) are
 * intentionally not part of the response. {@code numberOfSentMessages} is pseudonymized at rest
 * (see {@link ConsultantMessageStatService}), never linked to the consultant id in the database.
 */
@Service
@RequiredArgsConstructor
public class ConsultantStatisticsService {

  private final @NonNull ConsultantStatisticsRepository consultantStatisticsRepository;
  private final @NonNull ConsultantMessageStatService consultantMessageStatService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  public record ConsultantStatistics(
      String startDate,
      String endDate,
      long numberOfAssignedSessions,
      long numberOfActiveSessions,
      long numberOfSentMessages) {}

  public ConsultantStatistics buildStatistics(String startDate, String endDate) {
    // SecurityConfig already gates this endpoint on CONSULTANT_DEFAULT; this check is
    // defense-in-depth and must therefore mirror the same authority, not the narrower
    // "consultant" role literal (group-chat-consultant / supervisor-consultant also carry
    // CONSULTANT_DEFAULT but not the "consultant" role).
    var grantedAuthorities = authenticatedUser.getGrantedAuthorities();
    if (grantedAuthorities == null || !grantedAuthorities.contains(CONSULTANT_DEFAULT)) {
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
    var tenantId = resolveTenantId();

    return new ConsultantStatistics(
        from.toString(),
        toInclusive.toString(),
        consultantStatisticsRepository.countAssignedSessionsCreatedInPeriod(
            consultantId, tenantId, fromDateTime, toDateTime),
        consultantStatisticsRepository.countActiveSessionsInPeriod(
            consultantId, tenantId, fromDateTime, toDateTime),
        consultantMessageStatService.countForConsultant(
            consultantId, tenantId, fromDateTime, toDateTime));
  }

  private Long resolveTenantId() {
    var contextTenant = TenantContext.getCurrentTenant();
    if (contextTenant != null && !TenantContext.TECHNICAL_TENANT_ID.equals(contextTenant)) {
      return contextTenant;
    }
    return authenticatedUser.getTenantId();
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
