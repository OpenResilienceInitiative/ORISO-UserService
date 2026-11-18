package de.caritas.cob.userservice.api.service.statistics;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.SessionAppointment.Kind;
import de.caritas.cob.userservice.api.port.out.ConsultantStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantStatisticsRepository.AppointmentKindCount;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds the statistics shown on the consultant's own profile from the application-layer database
 * only (KDG compliance: no monitoring data, no message content). A consultant can only ever see
 * their own numbers: the consultant id is taken from the authenticated user, never from the
 * request. {@code numberOfSentMessages} is pseudonymized at rest (see {@link
 * ConsultantMessageStatService}), never linked to the consultant id in the database.
 *
 * <p>Appointments (ORISO-Frontend#2299) are the 1:1 appointments of the consultant's own sessions:
 * realised ones (scheduled, start in the period and already in the past) per kind, and cancelled
 * ones whose start lies in the period. Appointment periods are calendar days in Berlin time.
 *
 * <p>An optional breakdown splits the period into days, Monday-to-Sunday weeks or calendar months,
 * clipped to the period, so the profile can export one CSV row per part.
 */
@Service
@RequiredArgsConstructor
public class ConsultantStatisticsService {

  static final ZoneId STATISTICS_ZONE = ZoneId.of("Europe/Berlin");

  /** A month broken into days is the finest breakdown; a year has 12 months, a month 6 weeks. */
  private static final int MAX_BREAKDOWN_PARTS = 31;

  private final @NonNull ConsultantStatisticsRepository consultantStatisticsRepository;
  private final @NonNull ConsultantMessageStatService consultantMessageStatService;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull Clock clock;

  /** How a period is split for the breakdown. */
  public enum Breakdown {
    DAY,
    WEEK,
    MONTH
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ConsultantStatistics(
      String startDate,
      String endDate,
      long numberOfAssignedSessions,
      long numberOfActiveSessions,
      long numberOfSentMessages,
      long numberOfAppointments,
      long numberOfVideoAppointments,
      long numberOfPhoneAppointments,
      long numberOfOnsiteAppointments,
      long numberOfChatAppointments,
      long numberOfCancelledAppointments,
      List<ConsultantStatistics> breakdown) {}

  public ConsultantStatistics buildStatistics(String startDate, String endDate) {
    return buildStatistics(startDate, endDate, null);
  }

  public ConsultantStatistics buildStatistics(String startDate, String endDate, String breakdown) {
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
    var parts = breakdown == null ? null : split(from, toInclusive, parseBreakdown(breakdown));

    var consultantId = authenticatedUser.getUserId();
    var tenantId = resolveTenantId();
    var total = statisticsFor(consultantId, tenantId, from, toInclusive);
    if (parts == null) {
      return total;
    }
    var breakdownRows = new ArrayList<ConsultantStatistics>();
    for (var part : parts) {
      breakdownRows.add(statisticsFor(consultantId, tenantId, part[0], part[1]));
    }
    return withBreakdown(total, breakdownRows);
  }

  private ConsultantStatistics statisticsFor(
      String consultantId, Long tenantId, LocalDate from, LocalDate toInclusive) {
    var fromDateTime = from.atStartOfDay();
    var toDateTime = toInclusive.plusDays(1).atStartOfDay();
    var fromInstant = from.atStartOfDay(STATISTICS_ZONE).toInstant();
    var toInstant = toInclusive.plusDays(1).atStartOfDay(STATISTICS_ZONE).toInstant();
    var realised = realisedAppointmentsByKind(consultantId, tenantId, fromInstant, toInstant);

    return new ConsultantStatistics(
        from.toString(),
        toInclusive.toString(),
        consultantStatisticsRepository.countAssignedSessionsCreatedInPeriod(
            consultantId, tenantId, fromDateTime, toDateTime),
        consultantStatisticsRepository.countActiveSessionsInPeriod(
            consultantId, tenantId, fromDateTime, toDateTime),
        consultantMessageStatService.countForConsultant(
            consultantId, tenantId, fromDateTime, toDateTime),
        realised.values().stream().mapToLong(Long::longValue).sum(),
        realised.getOrDefault(Kind.VIDEO, 0L),
        realised.getOrDefault(Kind.PHONE, 0L),
        realised.getOrDefault(Kind.ONSITE, 0L),
        realised.getOrDefault(Kind.CHAT, 0L),
        consultantStatisticsRepository.countCancelledAppointmentsStartingIn(
            consultantId, tenantId, fromInstant, toInstant),
        null);
  }

  /** Per kind; appointments without a kind are kept under a null key and count in the total. */
  private Map<Kind, Long> realisedAppointmentsByKind(
      String consultantId, Long tenantId, Instant from, Instant to) {
    var now = Instant.now(clock);
    var realisedUntil = to.isBefore(now) ? to : now;
    var byKind = new HashMap<Kind, Long>();
    if (from.isBefore(realisedUntil)) {
      for (AppointmentKindCount count :
          consultantStatisticsRepository.countScheduledAppointmentsByKindStartingIn(
              consultantId, tenantId, from, realisedUntil)) {
        byKind.merge(count.getKind(), count.getTotal(), Long::sum);
      }
    }
    return byKind;
  }

  private static ConsultantStatistics withBreakdown(
      ConsultantStatistics total, List<ConsultantStatistics> breakdown) {
    return new ConsultantStatistics(
        total.startDate(),
        total.endDate(),
        total.numberOfAssignedSessions(),
        total.numberOfActiveSessions(),
        total.numberOfSentMessages(),
        total.numberOfAppointments(),
        total.numberOfVideoAppointments(),
        total.numberOfPhoneAppointments(),
        total.numberOfOnsiteAppointments(),
        total.numberOfChatAppointments(),
        total.numberOfCancelledAppointments(),
        List.copyOf(breakdown));
  }

  /** The parts of {@code [from, to]}, each as {first day, last day}, clipped to the period. */
  private static List<LocalDate[]> split(LocalDate from, LocalDate to, Breakdown breakdown) {
    var parts = new ArrayList<LocalDate[]>();
    var start = from;
    while (!start.isAfter(to)) {
      var end =
          switch (breakdown) {
            case DAY -> start;
            case WEEK -> start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            case MONTH -> start.with(TemporalAdjusters.lastDayOfMonth());
          };
      if (end.isAfter(to)) {
        end = to;
      }
      parts.add(new LocalDate[] {start, end});
      if (parts.size() > MAX_BREAKDOWN_PARTS) {
        throw new BadRequestException(
            "A breakdown may have at most %d parts".formatted(MAX_BREAKDOWN_PARTS));
      }
      start = end.plusDays(1);
    }
    return parts;
  }

  private static Breakdown parseBreakdown(String value) {
    try {
      return Breakdown.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(
          "Invalid breakdown '%s', expected DAY, WEEK or MONTH".formatted(value), e);
    }
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
