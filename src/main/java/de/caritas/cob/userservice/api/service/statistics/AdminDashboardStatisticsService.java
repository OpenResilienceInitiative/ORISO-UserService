package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.DailyCountProjection;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.GroupCountProjection;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.TopicCountProjection;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/**
 * Builds the admin statistics dashboard from the application-layer database only (KDG compliance:
 * no monitoring data, no message content, no per-client drill-down).
 *
 * <p>Re-identification protection (small-cell suppression): counselling statistics of a target
 * (tenant or agency) are only returned when they aggregate over at least {@link
 * #SMALL_CELL_MINIMUM_COUNSELORS} counsellor accounts. Otherwise the target is marked {@code
 * suppressed} and all counselling metrics are omitted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardStatisticsService {

  public static final int SMALL_CELL_MINIMUM_COUNSELORS = 2;

  private static final int CHART_WEEKS = 5;
  private static final int TOPIC_MONTHS = 4;
  private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

  private final @NonNull AdminStatisticsRepository adminStatisticsRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull Clock clock;
  private final @NonNull Environment environment;

  /**
   * Dev-only escape hatch for end-to-end testing with small test datasets. Fail-closed: when the
   * prod profile is active, suppression stays enforced regardless of this property.
   */
  @Value("${statistics.small-cell-suppression.enabled:true}")
  private boolean smallCellSuppressionEnabled = true;

  public enum TargetType {
    TENANT,
    AGENCY
  }

  public enum DashboardScope {
    PLATFORM,
    TENANT,
    AGENCY
  }

  public record DashboardStatistics(
      String generatedAt,
      DashboardScope scope,
      boolean suppressionDisabled,
      List<TargetStatistics> targets) {}

  public record TargetStatistics(
      TargetType targetType,
      Long tenantId,
      Long agencyId,
      long counselorCount,
      boolean suppressed,
      Metrics metrics,
      List<DailyCount> dailyNewSessions,
      List<MonthlyTopic> monthlyTopTopics,
      PeriodCounts sessionCountsByPeriod,
      PeriodCounts groupChatCountsByPeriod) {}

  public record Metrics(
      long enquiriesCurrentMonth,
      long enquiriesPreviousMonth,
      long activeCases,
      long activeConversationsToday,
      long groupChatsTotal) {}

  public record DailyCount(String date, long count) {}

  public record MonthlyTopic(String month, Long topicId, long sessionCount, int sharePercent) {}

  public record PeriodCounts(
      long today, long yesterday, long thisWeek, long total, long thisYear, long lastYear) {}

  private record DateRanges(
      LocalDateTime todayStart,
      LocalDateTime tomorrowStart,
      LocalDateTime yesterdayStart,
      LocalDateTime weekStart,
      LocalDateTime chartStart,
      LocalDateTime currentMonthStart,
      LocalDateTime nextMonthStart,
      LocalDateTime previousMonthStart,
      LocalDateTime yearStart,
      LocalDateTime lastYearStart,
      List<YearMonth> topicMonths) {}

  /** Aggregated raw counts for one group (agency or tenant), before suppression. */
  private record GroupData(
      long counselorCount,
      long enquiriesCurrentMonth,
      long enquiriesPreviousMonth,
      long activeCases,
      long activeConversationsToday,
      Map<String, Long> dailyNewSessions,
      Map<String, Map<Long, Long>> topicCountsByMonth,
      PeriodCounts sessionCountsByPeriod,
      PeriodCounts groupChatCountsByPeriod) {}

  public DashboardStatistics buildDashboard() {
    var ranges = buildDateRanges();
    var suppressionActive = resolveSuppressionActive();

    if (authenticatedUser.isPlatformAdmin()) {
      return buildPlatformDashboard(ranges, suppressionActive);
    }
    if (authenticatedUser.isTenantSuperAdmin() || authenticatedUser.isSingleTenantAdmin()) {
      return buildTenantDashboard(ranges, resolveTenantId(), suppressionActive);
    }
    if (authenticatedUser.isRestrictedAgencyAdmin() || authenticatedUser.isAgencySuperAdmin()) {
      return buildAgencyDashboard(ranges, resolveTenantId(), suppressionActive);
    }
    throw new ForbiddenException(
        "User %s is not authorized to access admin statistics"
            .formatted(authenticatedUser.getUserId()));
  }

  /**
   * Small-cell suppression may only be switched off outside production. If the prod profile is
   * active, the property is ignored and suppression stays enforced (fail-closed).
   */
  private boolean resolveSuppressionActive() {
    if (smallCellSuppressionEnabled) {
      return true;
    }
    if (environment.acceptsProfiles(Profiles.of("prod"))) {
      log.warn(
          "statistics.small-cell-suppression.enabled=false was requested, but the prod profile "
              + "is active - small-cell suppression stays enforced (fail-closed)");
      return true;
    }
    log.info("Small-cell suppression is DISABLED for admin statistics (non-production profile)");
    return false;
  }

  private Long resolveTenantId() {
    var contextTenant = TenantContext.getCurrentTenant();
    if (contextTenant != null && !TenantContext.TECHNICAL_TENANT_ID.equals(contextTenant)) {
      return contextTenant;
    }
    return authenticatedUser.getTenantId();
  }

  /* ---------- platform scope ---------- */

  private DashboardStatistics buildPlatformDashboard(DateRanges ranges, boolean suppressionActive) {
    var groups =
        collectGroups(
            ranges,
            adminStatisticsRepository::countConsultantsByTenant,
            adminStatisticsRepository::countNewSessionsByTenant,
            adminStatisticsRepository::countActiveCasesByTenant,
            adminStatisticsRepository::countActiveSessionsSinceByTenant,
            adminStatisticsRepository::countDailyNewSessionsByTenant,
            adminStatisticsRepository::countSessionTopicsByTenant,
            adminStatisticsRepository::countGroupChatsByTenant);

    var targets =
        groups.entrySet().stream()
            .filter(entry -> entry.getKey() != null)
            .map(
                entry ->
                    toTargetStatistics(
                        TargetType.TENANT,
                        entry.getKey(),
                        null,
                        entry.getValue(),
                        suppressionActive))
            .toList();

    return new DashboardStatistics(
        OffsetDateTime.now(clock).toString(), DashboardScope.PLATFORM, !suppressionActive, targets);
  }

  /* ---------- tenant scope ---------- */

  private DashboardStatistics buildTenantDashboard(
      DateRanges ranges, Long tenantId, boolean suppressionActive) {
    requireTenant(tenantId);
    var groups = collectAgencyGroups(ranges, tenantId);
    var targets = new ArrayList<TargetStatistics>();

    targets.add(buildTenantTotalTarget(ranges, tenantId, groups, suppressionActive));
    groups.entrySet().stream()
        .filter(entry -> entry.getKey() != null)
        .forEach(
            entry ->
                targets.add(
                    toTargetStatistics(
                        TargetType.AGENCY,
                        tenantId,
                        entry.getKey(),
                        entry.getValue(),
                        suppressionActive)));

    return new DashboardStatistics(
        OffsetDateTime.now(clock).toString(), DashboardScope.TENANT, !suppressionActive, targets);
  }

  /* ---------- agency scope ---------- */

  private DashboardStatistics buildAgencyDashboard(
      DateRanges ranges, Long tenantId, boolean suppressionActive) {
    requireTenant(tenantId);
    var allowedAgencyIds =
        adminAgencyRepository.findByAdminId(authenticatedUser.getUserId()).stream()
            .map(adminAgency -> adminAgency.getAgencyId())
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

    var groups = collectAgencyGroups(ranges, tenantId);
    var targets =
        groups.entrySet().stream()
            .filter(entry -> entry.getKey() != null && allowedAgencyIds.contains(entry.getKey()))
            .map(
                entry ->
                    toTargetStatistics(
                        TargetType.AGENCY,
                        tenantId,
                        entry.getKey(),
                        entry.getValue(),
                        suppressionActive))
            .toList();

    return new DashboardStatistics(
        OffsetDateTime.now(clock).toString(), DashboardScope.AGENCY, !suppressionActive, targets);
  }

  private static void requireTenant(Long tenantId) {
    if (tenantId == null) {
      throw new ForbiddenException("No tenant context available for admin statistics");
    }
  }

  /* ---------- group collection ---------- */

  private Map<Long, GroupData> collectAgencyGroups(DateRanges ranges, Long tenantId) {
    return collectGroups(
        ranges,
        () -> adminStatisticsRepository.countConsultantsByAgency(tenantId),
        (from, to) -> adminStatisticsRepository.countNewSessionsByAgency(tenantId, from, to),
        () -> adminStatisticsRepository.countActiveCasesByAgency(tenantId),
        since -> adminStatisticsRepository.countActiveSessionsSinceByAgency(tenantId, since),
        from -> adminStatisticsRepository.countDailyNewSessionsByAgency(tenantId, from),
        (from, to) -> adminStatisticsRepository.countSessionTopicsByAgency(tenantId, from, to),
        (from, to) -> adminStatisticsRepository.countGroupChatsByAgency(tenantId, from, to));
  }

  private interface RangeQuery {
    List<GroupCountProjection> query(LocalDateTime from, LocalDateTime to);
  }

  private interface SinceQuery {
    List<GroupCountProjection> query(LocalDateTime since);
  }

  private interface DailyQuery {
    List<DailyCountProjection> query(LocalDateTime from);
  }

  private interface TopicQuery {
    List<TopicCountProjection> query(LocalDateTime from, LocalDateTime to);
  }

  private interface PlainQuery {
    List<GroupCountProjection> query();
  }

  @SuppressWarnings("java:S107")
  private Map<Long, GroupData> collectGroups(
      DateRanges ranges,
      PlainQuery counselors,
      RangeQuery newSessions,
      PlainQuery activeCases,
      SinceQuery activeSince,
      DailyQuery daily,
      TopicQuery topics,
      RangeQuery groupChats) {

    var counselorCounts = toCountMap(counselors.query());
    var enquiriesCurrent =
        toCountMap(newSessions.query(ranges.currentMonthStart(), ranges.nextMonthStart()));
    var enquiriesPrevious =
        toCountMap(newSessions.query(ranges.previousMonthStart(), ranges.currentMonthStart()));
    var activeCaseCounts = toCountMap(activeCases.query());
    var activeTodayCounts = toCountMap(activeSince.query(ranges.todayStart()));
    var sessionPeriods = collectPeriodCounts(ranges, newSessions);
    var chatPeriods = collectPeriodCounts(ranges, groupChats);
    var dailyCounts = toDailyMap(daily.query(ranges.chartStart()));
    var topicDistributions = collectTopicDistributions(ranges, topics);

    var groupIds = new HashSet<Long>();
    groupIds.addAll(counselorCounts.keySet());
    groupIds.addAll(enquiriesCurrent.keySet());
    groupIds.addAll(enquiriesPrevious.keySet());
    groupIds.addAll(activeCaseCounts.keySet());
    groupIds.addAll(sessionPeriods.keySet());
    groupIds.addAll(chatPeriods.keySet());
    groupIds.addAll(dailyCounts.keySet());
    groupIds.addAll(topicDistributions.keySet());

    var groups = new LinkedHashMap<Long, GroupData>();
    for (var groupId : groupIds) {
      groups.put(
          groupId,
          new GroupData(
              counselorCounts.getOrDefault(groupId, 0L),
              enquiriesCurrent.getOrDefault(groupId, 0L),
              enquiriesPrevious.getOrDefault(groupId, 0L),
              activeCaseCounts.getOrDefault(groupId, 0L),
              activeTodayCounts.getOrDefault(groupId, 0L),
              dailyCounts.getOrDefault(groupId, Map.of()),
              topicDistributions.getOrDefault(groupId, Map.of()),
              sessionPeriods.getOrDefault(groupId, zeroPeriods()),
              chatPeriods.getOrDefault(groupId, zeroPeriods())));
    }
    return groups;
  }

  private Map<Long, PeriodCounts> collectPeriodCounts(DateRanges ranges, RangeQuery query) {
    var today = toCountMap(query.query(ranges.todayStart(), ranges.tomorrowStart()));
    var yesterday = toCountMap(query.query(ranges.yesterdayStart(), ranges.todayStart()));
    var thisWeek = toCountMap(query.query(ranges.weekStart(), ranges.tomorrowStart()));
    var total = toCountMap(query.query(EPOCH, ranges.tomorrowStart()));
    var thisYear = toCountMap(query.query(ranges.yearStart(), ranges.tomorrowStart()));
    var lastYear = toCountMap(query.query(ranges.lastYearStart(), ranges.yearStart()));

    var groupIds = new HashSet<Long>();
    groupIds.addAll(today.keySet());
    groupIds.addAll(yesterday.keySet());
    groupIds.addAll(thisWeek.keySet());
    groupIds.addAll(total.keySet());
    groupIds.addAll(thisYear.keySet());
    groupIds.addAll(lastYear.keySet());

    var result = new HashMap<Long, PeriodCounts>();
    for (var groupId : groupIds) {
      result.put(
          groupId,
          new PeriodCounts(
              today.getOrDefault(groupId, 0L),
              yesterday.getOrDefault(groupId, 0L),
              thisWeek.getOrDefault(groupId, 0L),
              total.getOrDefault(groupId, 0L),
              thisYear.getOrDefault(groupId, 0L),
              lastYear.getOrDefault(groupId, 0L)));
    }
    return result;
  }

  /** Returns groupId → (month → (topicId → session count)). */
  private Map<Long, Map<String, Map<Long, Long>>> collectTopicDistributions(
      DateRanges ranges, TopicQuery query) {
    var result = new HashMap<Long, Map<String, Map<Long, Long>>>();
    for (var month : ranges.topicMonths()) {
      var from = month.atDay(1).atStartOfDay();
      var to = month.plusMonths(1).atDay(1).atStartOfDay();
      for (var row : query.query(from, to)) {
        if (row.getTopicId() == null) {
          continue;
        }
        result
            .computeIfAbsent(row.getGroupId(), key -> new HashMap<>())
            .computeIfAbsent(month.toString(), key -> new HashMap<>())
            .merge(row.getTopicId(), row.getTotal() == null ? 0L : row.getTotal(), Long::sum);
      }
    }
    return result;
  }

  /* ---------- target building ---------- */

  private TargetStatistics buildTenantTotalTarget(
      DateRanges ranges,
      Long tenantId,
      Map<Long, GroupData> agencyGroups,
      boolean suppressionActive) {
    var counselorCount = adminStatisticsRepository.countConsultantsForTenant(tenantId);
    var chatPeriods =
        new PeriodCounts(
            adminStatisticsRepository.countGroupChatsForTenant(
                tenantId, ranges.todayStart(), ranges.tomorrowStart()),
            adminStatisticsRepository.countGroupChatsForTenant(
                tenantId, ranges.yesterdayStart(), ranges.todayStart()),
            adminStatisticsRepository.countGroupChatsForTenant(
                tenantId, ranges.weekStart(), ranges.tomorrowStart()),
            adminStatisticsRepository.countGroupChatsForTenant(
                tenantId, EPOCH, ranges.tomorrowStart()),
            adminStatisticsRepository.countGroupChatsForTenant(
                tenantId, ranges.yearStart(), ranges.tomorrowStart()),
            adminStatisticsRepository.countGroupChatsForTenant(
                tenantId, ranges.lastYearStart(), ranges.yearStart()));

    var total = sumGroups(agencyGroups.values(), counselorCount, chatPeriods);
    return toTargetStatistics(TargetType.TENANT, tenantId, null, total, suppressionActive);
  }

  private GroupData sumGroups(
      Iterable<GroupData> groups, long counselorCount, PeriodCounts chatPeriods) {
    long enquiriesCurrent = 0;
    long enquiriesPrevious = 0;
    long activeCases = 0;
    long activeToday = 0;
    long sessionsToday = 0;
    long sessionsYesterday = 0;
    long sessionsThisWeek = 0;
    long sessionsTotal = 0;
    long sessionsThisYear = 0;
    long sessionsLastYear = 0;
    var daily = new TreeMap<String, Long>();
    var topicCountsByMonth = new HashMap<String, Map<Long, Long>>();

    for (var group : groups) {
      enquiriesCurrent += group.enquiriesCurrentMonth();
      enquiriesPrevious += group.enquiriesPreviousMonth();
      activeCases += group.activeCases();
      activeToday += group.activeConversationsToday();
      sessionsToday += group.sessionCountsByPeriod().today();
      sessionsYesterday += group.sessionCountsByPeriod().yesterday();
      sessionsThisWeek += group.sessionCountsByPeriod().thisWeek();
      sessionsTotal += group.sessionCountsByPeriod().total();
      sessionsThisYear += group.sessionCountsByPeriod().thisYear();
      sessionsLastYear += group.sessionCountsByPeriod().lastYear();
      group.dailyNewSessions().forEach((day, count) -> daily.merge(day, count, Long::sum));
      group
          .topicCountsByMonth()
          .forEach(
              (month, topicCounts) ->
                  topicCounts.forEach(
                      (topicId, count) ->
                          topicCountsByMonth
                              .computeIfAbsent(month, key -> new HashMap<>())
                              .merge(topicId, count, Long::sum)));
    }

    return new GroupData(
        counselorCount,
        enquiriesCurrent,
        enquiriesPrevious,
        activeCases,
        activeToday,
        daily,
        topicCountsByMonth,
        new PeriodCounts(
            sessionsToday,
            sessionsYesterday,
            sessionsThisWeek,
            sessionsTotal,
            sessionsThisYear,
            sessionsLastYear),
        chatPeriods);
  }

  private TargetStatistics toTargetStatistics(
      TargetType targetType,
      Long tenantId,
      Long agencyId,
      GroupData data,
      boolean suppressionActive) {
    var suppressed = suppressionActive && data.counselorCount() < SMALL_CELL_MINIMUM_COUNSELORS;

    if (suppressed) {
      return new TargetStatistics(
          targetType,
          tenantId,
          agencyId,
          data.counselorCount(),
          true,
          null,
          List.of(),
          List.of(),
          null,
          null);
    }

    var metrics =
        new Metrics(
            data.enquiriesCurrentMonth(),
            data.enquiriesPreviousMonth(),
            data.activeCases(),
            data.activeConversationsToday(),
            data.groupChatCountsByPeriod().total());
    var daily =
        data.dailyNewSessions().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new DailyCount(entry.getKey(), entry.getValue()))
            .toList();

    return new TargetStatistics(
        targetType,
        tenantId,
        agencyId,
        data.counselorCount(),
        false,
        metrics,
        daily,
        buildMonthlyTopTopics(data.topicCountsByMonth()),
        data.sessionCountsByPeriod(),
        data.groupChatCountsByPeriod());
  }

  private List<MonthlyTopic> buildMonthlyTopTopics(
      Map<String, Map<Long, Long>> topicCountsByMonth) {
    var monthlyTopics = new ArrayList<MonthlyTopic>();
    for (var entry : new TreeMap<>(topicCountsByMonth).descendingMap().entrySet()) {
      var counts = entry.getValue();
      var monthTotal = counts.values().stream().mapToLong(Long::longValue).sum();
      counts.entrySet().stream()
          .max(Map.Entry.comparingByValue())
          .ifPresent(
              top ->
                  monthlyTopics.add(
                      new MonthlyTopic(
                          entry.getKey(),
                          top.getKey(),
                          top.getValue(),
                          monthTotal > 0
                              ? Math.toIntExact(Math.round(top.getValue() * 100.0 / monthTotal))
                              : 0)));
    }
    return monthlyTopics;
  }

  /* ---------- helpers ---------- */

  private DateRanges buildDateRanges() {
    var today = LocalDate.now(clock);
    var weekStart = today.with(DayOfWeek.MONDAY);
    var currentMonth = YearMonth.from(today);
    var topicMonths = new ArrayList<YearMonth>();
    for (int i = 0; i < TOPIC_MONTHS; i++) {
      topicMonths.add(currentMonth.minusMonths(i));
    }

    return new DateRanges(
        today.atStartOfDay(),
        today.plusDays(1).atStartOfDay(),
        today.minusDays(1).atStartOfDay(),
        weekStart.atStartOfDay(),
        weekStart.minusWeeks(CHART_WEEKS - 1L).atStartOfDay(),
        currentMonth.atDay(1).atStartOfDay(),
        currentMonth.plusMonths(1).atDay(1).atStartOfDay(),
        currentMonth.minusMonths(1).atDay(1).atStartOfDay(),
        today.withDayOfYear(1).atStartOfDay(),
        today.withDayOfYear(1).minusYears(1).atStartOfDay(),
        topicMonths);
  }

  private static Map<Long, Long> toCountMap(List<GroupCountProjection> rows) {
    var result = new HashMap<Long, Long>();
    for (var row : rows) {
      result.merge(row.getGroupId(), row.getTotal() == null ? 0L : row.getTotal(), Long::sum);
    }
    return result;
  }

  private static Map<Long, Map<String, Long>> toDailyMap(List<DailyCountProjection> rows) {
    var result = new HashMap<Long, Map<String, Long>>();
    for (var row : rows) {
      if (row.getDay() == null) {
        continue;
      }
      result
          .computeIfAbsent(row.getGroupId(), key -> new TreeMap<>())
          .merge(row.getDay().toString(), row.getTotal() == null ? 0L : row.getTotal(), Long::sum);
    }
    return result;
  }

  private static PeriodCounts zeroPeriods() {
    return new PeriodCounts(0, 0, 0, 0, 0, 0);
  }
}
