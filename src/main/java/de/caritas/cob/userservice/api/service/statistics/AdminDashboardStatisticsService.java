package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CommunicationStat.Kind;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.DailyCountProjection;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.GroupCountProjection;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.HourlyCountProjection;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.KindCountProjection;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.TopicCountProjection;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSetting;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSnapshotInterval;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Builds the admin statistics dashboard from the application-layer database only (KDG compliance:
 * no monitoring data, no message content, no per-client drill-down).
 *
 * <p>Re-identification protection (small-cell suppression): counselling statistics of a target
 * (platform, tenant or agency) are only returned when they aggregate over at least the minimum
 * number of counsellor accounts configured for that level in the global statistics settings ({@link
 * StatisticsSettingsService}). Otherwise the target is marked {@code suppressed} and all
 * counselling metrics are omitted, the counsellor count included. The minimum counts active
 * counsellors, not registered accounts ({@link AdminStatisticsRepository#ACTIVE_CONSULTANT}). A
 * counsellor's own statistics never pass through here.
 *
 * <p>Subtraction protection (UserService#1560): a shown total that includes a hidden row would give
 * it away as "total minus the shown rows". So when a row is hidden, what the total leaves over
 * after the shown rows must be at least the row minimum in counsellors; if it is not, the smallest
 * shown rows are hidden too until it is, and if none is left the total is hidden as well. A shown
 * total is always the true total, so no number is misleading. A stored stand goes through the same
 * rule on every read ({@link #withCurrentThresholds}), so it cannot show what the live path hides.
 *
 * <p>Differencing protection: a number that changes between two page loads can point at one case
 * even above the threshold. So each admin role reads a fixed stand ({@link
 * AdminDashboardStandService}) unless the global statistics settings set its interval to live. The
 * current thresholds also apply to a stored stand: raising one takes effect at once, lowering one
 * reveals nothing before the next stand. A stand holds full German calendar days only, up to its
 * reference day: no "today" and, in a weekly stand, no single days at all. Who is in counselling
 * right now stays, as of the calculation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardStatisticsService {

  /** The platform-wide stand has no tenant of its own; platform admins carry this technical id. */
  public static final Long PLATFORM_TENANT_ID = 0L;

  private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
  private static final ZoneId GERMAN_ZONE = ZoneId.of(StatisticsSnapshotInterval.CUTOFF_ZONE);

  private final @NonNull AdminStatisticsRepository adminStatisticsRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull Clock clock;
  private final @NonNull Environment environment;
  private final @NonNull StatisticsSettingsService statisticsSettingsService;
  private final @NonNull AdminDashboardStandService dashboardStands;

  /**
   * Only the profiles for a developer's machine and the automated tests may switch suppression off,
   * and only when no other profile is active next to them. Everything else - prod, staging, dev, a
   * missing or a new profile - keeps it on.
   */
  private static final Set<String> SUPPRESSION_MAY_BE_DISABLED_ON = Set.of("local", "testing");

  /**
   * Escape hatch for end-to-end testing with small test datasets. Fail-closed: it only takes effect
   * on the {@code local} and {@code testing} profiles; on any other profile, or none, suppression
   * stays enforced regardless of this property.
   */
  @Value("${statistics.small-cell-suppression.enabled:true}")
  private boolean smallCellSuppressionEnabled = true;

  public enum TargetType {
    PLATFORM,
    TENANT,
    AGENCY
  }

  public enum DashboardScope {
    PLATFORM(StatisticsSetting.PLATFORM_SNAPSHOT_INTERVAL),
    TENANT(StatisticsSetting.TENANT_SNAPSHOT_INTERVAL),
    AGENCY(StatisticsSetting.AGENCY_SNAPSHOT_INTERVAL);

    private final StatisticsSetting snapshotSetting;

    DashboardScope(StatisticsSetting snapshotSetting) {
      this.snapshotSetting = snapshotSetting;
    }

    public StatisticsSetting snapshotSetting() {
      return snapshotSetting;
    }
  }

  /**
   * @param chartWeeks weeks covered by {@code dailyNewSessions}, the current week included
   * @param topicMonths months covered by {@code monthlyTopTopics}, the current month included
   * @param snapshotInterval how often this role gets a new stand; LIVE means every request
   * @param dataUntil the newest full day of a fixed stand (ISO date); null when live
   */
  public record DashboardStatistics(
      String generatedAt,
      DashboardScope scope,
      boolean suppressionDisabled,
      int chartWeeks,
      int topicMonths,
      StatisticsSnapshotInterval snapshotInterval,
      String dataUntil,
      List<TargetStatistics> targets) {}

  public record TargetStatistics(
      TargetType targetType,
      Long tenantId,
      Long agencyId,
      long counselorCount,
      boolean suppressed,
      Metrics metrics,
      List<DailyCount> dailyNewSessions,
      List<WeeklyCount> weeklyNewSessions,
      List<MonthlyTopic> monthlyTopTopics,
      PeriodCounts sessionCountsByPeriod,
      PeriodCounts groupChatCountsByPeriod) {}

  /**
   * The four communication counts cover the current German calendar month (in a fixed stand: up to
   * its reference day). They are boxed so a stand stored by an older release, which lacks them,
   * still reads (as null); a freshly calculated target always carries a number, 0 included. Voice
   * messages are not offered: session rooms are end-to-end encrypted, so the server cannot tell
   * them apart from text messages.
   */
  public record Metrics(
      long enquiriesCurrentMonth,
      long enquiriesPreviousMonth,
      long activeCases,
      Long activeConversationsToday,
      long groupChatsTotal,
      Long messagesCounselorsCurrentMonth,
      Long messagesAdviceSeekersCurrentMonth,
      Long audioCallsCurrentMonth,
      Long videoCallsCurrentMonth) {}

  /** Raw current-month communication counts of one group, before suppression. */
  private record CommunicationCounts(
      long counsellorMessages, long adviceSeekerMessages, long audioCalls, long videoCalls) {

    static final CommunicationCounts NONE = new CommunicationCounts(0, 0, 0, 0);

    CommunicationCounts plus(CommunicationCounts other) {
      return new CommunicationCounts(
          counsellorMessages + other.counsellorMessages,
          adviceSeekerMessages + other.adviceSeekerMessages,
          audioCalls + other.audioCalls,
          videoCalls + other.videoCalls);
    }
  }

  public record DailyCount(String date, long count) {}

  public record WeeklyCount(String weekStart, long count) {}

  public record MonthlyTopic(String month, Long topicId, long sessionCount, int sharePercent) {}

  /**
   * @param today null in a fixed stand: the running day is never part of it
   * @param yesterday the newest full day of a stand; null in a weekly stand, which has no days
   */
  public record PeriodCounts(
      Long today, Long yesterday, long thisWeek, long total, long thisYear, long lastYear) {}

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
      List<TopicMonth> topicMonths,
      boolean fixedStand) {}

  private record TopicMonth(YearMonth month, LocalDateTime from, LocalDateTime to) {}

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
      PeriodCounts groupChatCountsByPeriod,
      CommunicationCounts communication) {}

  public DashboardStatistics buildDashboard() {
    if (authenticatedUser.isPlatformAdmin()) {
      return currentDashboard(DashboardScope.PLATFORM, PLATFORM_TENANT_ID);
    }
    if (authenticatedUser.isTenantSuperAdmin() || authenticatedUser.isSingleTenantAdmin()) {
      return currentDashboard(DashboardScope.TENANT, resolveTenantId());
    }
    if (authenticatedUser.isRestrictedAgencyAdmin() || authenticatedUser.isAgencySuperAdmin()) {
      return onlyOwnAgencies(currentDashboard(DashboardScope.AGENCY, resolveTenantId()));
    }
    throw new ForbiddenException(
        "User %s is not authorized to access admin statistics"
            .formatted(authenticatedUser.getUserId()));
  }

  private DashboardStatistics currentDashboard(DashboardScope scope, Long tenantId) {
    requireTenant(tenantId);
    var interval = statisticsSettingsService.snapshotInterval(scope.snapshotSetting());
    if (interval == StatisticsSnapshotInterval.LIVE) {
      return calculateDashboard(scope, tenantId, interval, clock.instant());
    }
    var stand =
        dashboardStands.currentStand(
            scope, tenantId, interval, now -> calculateDashboard(scope, tenantId, interval, now));
    return withCurrentThresholds(stand);
  }

  /**
   * Calculates one dashboard from the live data, with no reference to the caller: a stand is shared
   * by every admin of its scope and tenant. The agency scope therefore holds all agencies of the
   * tenant; {@link #onlyOwnAgencies} narrows it per request.
   *
   * @param now the moment the stand's cutoff was taken from, so its days always match that cutoff
   */
  public DashboardStatistics calculateDashboard(
      DashboardScope scope, Long tenantId, StatisticsSnapshotInterval interval, Instant now) {
    var windows = resolveTimeWindows();
    var ranges =
        interval == StatisticsSnapshotInterval.LIVE
            ? buildLiveRanges(windows)
            : buildStandRanges(windows, interval.referenceDay(now));
    var suppression = resolveSuppression();
    var calculated =
        switch (scope) {
          case PLATFORM -> buildPlatformDashboard(ranges, windows, interval, suppression);
          case TENANT -> buildTenantDashboard(ranges, windows, interval, tenantId, suppression);
          case AGENCY -> buildAgencyDashboard(ranges, windows, interval, tenantId, suppression);
        };
    var dashboard =
        withTargets(
            calculated,
            calculated.suppressionDisabled(),
            withoutDerivableRows(scope, calculated.targets(), suppression));
    return interval == StatisticsSnapshotInterval.LIVE ? dashboard : asFixedStand(dashboard, now);
  }

  /** Takes out what a fixed stand must not hold, so no client can read it, shown or not. */
  private static DashboardStatistics asFixedStand(DashboardStatistics dashboard, Instant now) {
    var interval = dashboard.snapshotInterval();
    return new DashboardStatistics(
        dashboard.generatedAt(),
        dashboard.scope(),
        dashboard.suppressionDisabled(),
        dashboard.chartWeeks(),
        dashboard.topicMonths(),
        interval,
        interval.referenceDay(now).toString(),
        dashboard.targets().stream().map(target -> asFixedStand(target, interval)).toList());
  }

  private static TargetStatistics asFixedStand(
      TargetStatistics target, StatisticsSnapshotInterval interval) {
    if (target.suppressed()) {
      return target;
    }
    var weekly = interval == StatisticsSnapshotInterval.WEEKLY;
    var metrics = target.metrics();
    return new TargetStatistics(
        target.targetType(),
        target.tenantId(),
        target.agencyId(),
        target.counselorCount(),
        false,
        new Metrics(
            metrics.enquiriesCurrentMonth(),
            metrics.enquiriesPreviousMonth(),
            metrics.activeCases(),
            null,
            metrics.groupChatsTotal(),
            metrics.messagesCounselorsCurrentMonth(),
            metrics.messagesAdviceSeekersCurrentMonth(),
            metrics.audioCallsCurrentMonth(),
            metrics.videoCallsCurrentMonth()),
        weekly ? List.of() : target.dailyNewSessions(),
        weekly ? toWeeks(target.dailyNewSessions()) : List.of(),
        target.monthlyTopTopics(),
        withoutDays(target.sessionCountsByPeriod(), weekly),
        withoutDays(target.groupChatCountsByPeriod(), weekly));
  }

  private static PeriodCounts withoutDays(PeriodCounts counts, boolean weekly) {
    return new PeriodCounts(
        null,
        weekly ? null : counts.yesterday(),
        counts.thisWeek(),
        counts.total(),
        counts.thisYear(),
        counts.lastYear());
  }

  private static List<WeeklyCount> toWeeks(List<DailyCount> days) {
    var weeks = new TreeMap<String, Long>();
    days.forEach(
        day ->
            weeks.merge(
                LocalDate.parse(day.date())
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .toString(),
                day.count(),
                Long::sum));
    return weeks.entrySet().stream()
        .map(week -> new WeeklyCount(week.getKey(), week.getValue()))
        .toList();
  }

  /** Read per request, so a removed assignment applies at once, also to a stored stand. */
  private DashboardStatistics onlyOwnAgencies(DashboardStatistics dashboard) {
    var allowedAgencyIds =
        adminAgencyRepository.findByAdminId(authenticatedUser.getUserId()).stream()
            .map(adminAgency -> adminAgency.getAgencyId())
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return withTargets(
        dashboard,
        dashboard.suppressionDisabled(),
        dashboard.targets().stream()
            .filter(target -> allowedAgencyIds.contains(target.agencyId()))
            .toList());
  }

  /**
   * A stored stand went through the thresholds of its time; this applies today's on top. A raised
   * threshold hides a row at once. A lowered one cannot bring back what was never stored.
   */
  private DashboardStatistics withCurrentThresholds(DashboardStatistics stand) {
    var suppression = resolveSuppression();
    var targets =
        stand.targets().stream()
            .map(
                target ->
                    target.suppressed() || isBelowThreshold(target, suppression)
                        // also scrubs the exact count a stand of an earlier release still holds
                        ? suppressedTarget(
                            target.targetType(), target.tenantId(), target.agencyId())
                        : target)
            .toList();
    return withTargets(
        stand, !suppression.active(), withoutDerivableRows(stand.scope(), targets, suppression));
  }

  /**
   * A shown total must not let anyone work out a hidden row as "total minus the shown rows". What
   * the total leaves over, in counsellors, has to reach the row minimum; if it does not, the
   * smallest shown rows are hidden as well, one by one, until it does. If it still does not when no
   * shown row is left, the total itself is hidden: the three minimums are set independently, so a
   * total can pass its own threshold and still be little more than the hidden rows. The agency
   * scope has no total.
   *
   * <p>The left-over is read from the counts of the shown rows only, so it works on a stored stand,
   * which no longer holds the count of a hidden row. The total counts the same people as its rows
   * (each once), so a counsellor in several shown agencies is counted once in the total and once
   * per agency in the rows: the left-over is smaller than the true one, which hides more, never
   * less.
   */
  private static List<TargetStatistics> withoutDerivableRows(
      DashboardScope scope, List<TargetStatistics> targets, Suppression suppression) {
    if (!suppression.active() || scope == DashboardScope.AGENCY) {
      return targets;
    }
    var totalType = scope == DashboardScope.PLATFORM ? TargetType.PLATFORM : TargetType.TENANT;
    var rowType = scope == DashboardScope.PLATFORM ? TargetType.TENANT : TargetType.AGENCY;
    var total = targets.stream().filter(target -> target.targetType() == totalType).findFirst();
    var rows = targets.stream().filter(target -> target.targetType() == rowType).toList();
    if (total.isEmpty()
        || total.get().suppressed()
        || rows.stream().noneMatch(TargetStatistics::suppressed)) {
      return targets;
    }

    var minimum = suppression.minimumCounsellors(rowType);
    var shown = rows.stream().filter(row -> !row.suppressed()).toList();
    var leftOver =
        total.get().counselorCount()
            - shown.stream().mapToLong(TargetStatistics::counselorCount).sum();
    var additionallyHidden =
        Collections.newSetFromMap(new IdentityHashMap<TargetStatistics, Boolean>());
    var smallestFirst =
        shown.stream()
            .sorted(
                Comparator.comparingLong(TargetStatistics::counselorCount)
                    .thenComparing(
                        row -> rowType == TargetType.AGENCY ? row.agencyId() : row.tenantId(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    for (var row : smallestFirst) {
      if (leftOver >= minimum) {
        break;
      }
      additionallyHidden.add(row);
      leftOver += row.counselorCount();
    }
    var totalHidden = leftOver < minimum;
    return targets.stream()
        .map(
            target ->
                additionallyHidden.contains(target) || (totalHidden && target == total.get())
                    ? suppressedTarget(target.targetType(), target.tenantId(), target.agencyId())
                    : target)
        .toList();
  }

  private static boolean isBelowThreshold(TargetStatistics target, Suppression suppression) {
    return suppression.active()
        && target.counselorCount() < suppression.minimumCounsellors(target.targetType());
  }

  private static DashboardStatistics withTargets(
      DashboardStatistics dashboard, boolean suppressionDisabled, List<TargetStatistics> targets) {
    return new DashboardStatistics(
        dashboard.generatedAt(),
        dashboard.scope(),
        suppressionDisabled,
        dashboard.chartWeeks(),
        dashboard.topicMonths(),
        dashboard.snapshotInterval(),
        dashboard.dataUntil(),
        targets);
  }

  /**
   * Small-cell suppression may only be switched off when every active profile is local or testing.
   * With any other profile active, or none, the property is ignored and suppression stays enforced
   * (fail-closed).
   */
  private boolean resolveSuppressionActive() {
    if (smallCellSuppressionEnabled) {
      return true;
    }
    var activeProfiles = Set.of(environment.getActiveProfiles());
    if (activeProfiles.isEmpty() || !SUPPRESSION_MAY_BE_DISABLED_ON.containsAll(activeProfiles)) {
      log.warn(
          "statistics.small-cell-suppression.enabled=false was requested, but the active profiles "
              + "are not only local or testing - small-cell suppression stays enforced "
              + "(fail-closed)");
      return true;
    }
    log.info("Small-cell suppression is DISABLED for admin statistics (local or testing profile)");
    return false;
  }

  /**
   * Whether small-cell suppression applies, and the threshold of each aggregated view from the
   * global statistics settings - read once per dashboard, so every row of one response uses the
   * same rule.
   */
  private Suppression resolveSuppression() {
    return new Suppression(
        resolveSuppressionActive(),
        statisticsSettingsService.minimumCounsellors(
            StatisticsSetting.PLATFORM_MINIMUM_COUNSELLORS),
        statisticsSettingsService.minimumCounsellors(StatisticsSetting.TENANT_MINIMUM_COUNSELLORS),
        statisticsSettingsService.minimumCounsellors(StatisticsSetting.AGENCY_MINIMUM_COUNSELLORS));
  }

  private record Suppression(boolean active, int platform, int tenant, int agency) {
    int minimumCounsellors(TargetType targetType) {
      return switch (targetType) {
        case PLATFORM -> platform;
        case TENANT -> tenant;
        case AGENCY -> agency;
      };
    }
  }

  /**
   * The chart and topic time windows from the global statistics settings, read once per dashboard
   * so every row of one response covers the same period.
   */
  private TimeWindows resolveTimeWindows() {
    return new TimeWindows(
        statisticsSettingsService.chartWeeks(), statisticsSettingsService.topicMonths());
  }

  /** Weeks of the requests chart and months of the topic ranking, the current one included. */
  private record TimeWindows(int chartWeeks, int topicMonths) {}

  private Long resolveTenantId() {
    var contextTenant = TenantContext.getCurrentTenant();
    if (contextTenant != null && !TenantContext.TECHNICAL_TENANT_ID.equals(contextTenant)) {
      return contextTenant;
    }
    return authenticatedUser.getTenantId();
  }

  /* ---------- platform scope ---------- */

  private DashboardStatistics buildPlatformDashboard(
      DateRanges ranges,
      TimeWindows windows,
      StatisticsSnapshotInterval interval,
      Suppression suppression) {
    var groups =
        collectGroups(
            ranges,
            adminStatisticsRepository::countConsultantsByTenant,
            adminStatisticsRepository::countNewSessionsByTenant,
            adminStatisticsRepository::countActiveCasesByTenant,
            adminStatisticsRepository::countActiveSessionsSinceByTenant,
            adminStatisticsRepository::countDailyNewSessionsByTenant,
            adminStatisticsRepository::countHourlyNewSessionsByTenant,
            adminStatisticsRepository::countSessionTopicsByTenant,
            adminStatisticsRepository::countGroupChatsByTenant,
            adminStatisticsRepository::countCounsellorMessagesByTenant,
            adminStatisticsRepository::countCommunicationByTenant);

    var tenantGroups = groups.entrySet().stream().filter(entry -> entry.getKey() != null).toList();

    var targets = new ArrayList<TargetStatistics>();
    targets.add(buildPlatformTotalTarget(tenantGroups, suppression));
    tenantGroups.forEach(
        entry ->
            targets.add(
                toTargetStatistics(
                    TargetType.TENANT, entry.getKey(), null, entry.getValue(), suppression)));

    return new DashboardStatistics(
        OffsetDateTime.now(clock).toString(),
        DashboardScope.PLATFORM,
        !suppression.active(),
        windows.chartWeeks(),
        windows.topicMonths(),
        interval,
        null,
        targets);
  }

  /**
   * The whole platform as one row (OBP #93). Every consultant and every group chat belongs to
   * exactly one tenant, so the sum over the tenant rows is the platform total. It is suppressed
   * only when the platform itself is below its threshold, not when each single tenant is.
   */
  private TargetStatistics buildPlatformTotalTarget(
      List<Map.Entry<Long, GroupData>> tenantGroups, Suppression suppression) {
    var groups = tenantGroups.stream().map(Map.Entry::getValue).toList();
    var counselorCount = groups.stream().mapToLong(GroupData::counselorCount).sum();
    var chatPeriods = zeroPeriods();
    for (var group : groups) {
      chatPeriods = addPeriods(chatPeriods, group.groupChatCountsByPeriod());
    }
    var total = sumGroups(groups, counselorCount, chatPeriods);
    return toTargetStatistics(TargetType.PLATFORM, null, null, total, suppression);
  }

  private static PeriodCounts addPeriods(PeriodCounts first, PeriodCounts second) {
    return new PeriodCounts(
        first.today() + second.today(),
        first.yesterday() + second.yesterday(),
        first.thisWeek() + second.thisWeek(),
        first.total() + second.total(),
        first.thisYear() + second.thisYear(),
        first.lastYear() + second.lastYear());
  }

  /* ---------- tenant scope ---------- */

  private DashboardStatistics buildTenantDashboard(
      DateRanges ranges,
      TimeWindows windows,
      StatisticsSnapshotInterval interval,
      Long tenantId,
      Suppression suppression) {
    var groups = collectAgencyGroups(ranges, tenantId);
    var targets = new ArrayList<TargetStatistics>();

    targets.add(buildTenantTotalTarget(ranges, tenantId, groups, suppression));
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
                        suppression)));

    return new DashboardStatistics(
        OffsetDateTime.now(clock).toString(),
        DashboardScope.TENANT,
        !suppression.active(),
        windows.chartWeeks(),
        windows.topicMonths(),
        interval,
        null,
        targets);
  }

  /* ---------- agency scope ---------- */

  private DashboardStatistics buildAgencyDashboard(
      DateRanges ranges,
      TimeWindows windows,
      StatisticsSnapshotInterval interval,
      Long tenantId,
      Suppression suppression) {
    var groups = collectAgencyGroups(ranges, tenantId);
    var targets =
        groups.entrySet().stream()
            .filter(entry -> entry.getKey() != null)
            .map(
                entry ->
                    toTargetStatistics(
                        TargetType.AGENCY, tenantId, entry.getKey(), entry.getValue(), suppression))
            .toList();

    return new DashboardStatistics(
        OffsetDateTime.now(clock).toString(),
        DashboardScope.AGENCY,
        !suppression.active(),
        windows.chartWeeks(),
        windows.topicMonths(),
        interval,
        null,
        targets);
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
        (from, to) -> adminStatisticsRepository.countHourlyNewSessionsByAgency(tenantId, from, to),
        (from, to) -> adminStatisticsRepository.countSessionTopicsByAgency(tenantId, from, to),
        (from, to) -> adminStatisticsRepository.countGroupChatsByAgency(tenantId, from, to),
        (from, to) -> adminStatisticsRepository.countCounsellorMessagesByAgency(tenantId, from, to),
        (from, to) -> adminStatisticsRepository.countCommunicationByAgency(tenantId, from, to));
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

  private interface HourlyQuery {
    List<HourlyCountProjection> query(LocalDateTime from, LocalDateTime to);
  }

  private interface TopicQuery {
    List<TopicCountProjection> query(LocalDateTime from, LocalDateTime to);
  }

  private interface KindQuery {
    List<KindCountProjection> query(LocalDateTime from, LocalDateTime to);
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
      HourlyQuery hourly,
      TopicQuery topics,
      RangeQuery groupChats,
      RangeQuery counsellorMessages,
      KindQuery communication) {

    var counselorCounts = toCountMap(counselors.query());
    var enquiriesCurrent =
        toCountMap(newSessions.query(ranges.currentMonthStart(), ranges.nextMonthStart()));
    var enquiriesPrevious =
        toCountMap(newSessions.query(ranges.previousMonthStart(), ranges.currentMonthStart()));
    var activeCaseCounts = toCountMap(activeCases.query());
    var activeTodayCounts = toCountMap(activeSince.query(ranges.todayStart()));
    var sessionPeriods = collectPeriodCounts(ranges, newSessions);
    var chatPeriods = collectPeriodCounts(ranges, groupChats);
    var dailyCounts =
        ranges.fixedStand()
            ? toGermanDays(hourly.query(ranges.chartStart(), ranges.tomorrowStart()))
            : toDailyMap(daily.query(ranges.chartStart()));
    var topicDistributions = collectTopicDistributions(ranges, topics);
    var communicationCounts = collectCommunicationCounts(ranges, counsellorMessages, communication);

    var groupIds = new HashSet<Long>();
    groupIds.addAll(counselorCounts.keySet());
    groupIds.addAll(enquiriesCurrent.keySet());
    groupIds.addAll(enquiriesPrevious.keySet());
    groupIds.addAll(activeCaseCounts.keySet());
    groupIds.addAll(sessionPeriods.keySet());
    groupIds.addAll(chatPeriods.keySet());
    groupIds.addAll(dailyCounts.keySet());
    groupIds.addAll(topicDistributions.keySet());
    groupIds.addAll(communicationCounts.keySet());

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
              chatPeriods.getOrDefault(groupId, zeroPeriods()),
              communicationCounts.getOrDefault(groupId, CommunicationCounts.NONE)));
    }
    return groups;
  }

  /** Current-month counsellor messages, advice-seeker messages and calls per group. */
  private static Map<Long, CommunicationCounts> collectCommunicationCounts(
      DateRanges ranges, RangeQuery counsellorMessages, KindQuery communication) {
    var result = new HashMap<Long, CommunicationCounts>();
    for (var row : counsellorMessages.query(ranges.currentMonthStart(), ranges.nextMonthStart())) {
      result.merge(
          row.getGroupId(),
          new CommunicationCounts(row.getTotal() == null ? 0L : row.getTotal(), 0, 0, 0),
          CommunicationCounts::plus);
    }
    for (var row : communication.query(ranges.currentMonthStart(), ranges.nextMonthStart())) {
      long total = row.getTotal() == null ? 0L : row.getTotal();
      CommunicationCounts counts;
      try {
        counts =
            switch (Kind.valueOf(row.getKind())) {
              case ADVICE_SEEKER_MESSAGE -> new CommunicationCounts(0, total, 0, 0);
              case AUDIO_CALL -> new CommunicationCounts(0, 0, total, 0);
              case VIDEO_CALL -> new CommunicationCounts(0, 0, 0, total);
            };
      } catch (IllegalArgumentException | NullPointerException unknownKind) {
        continue;
      }
      result.merge(row.getGroupId(), counts, CommunicationCounts::plus);
    }
    return result;
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
    for (var topicMonth : ranges.topicMonths()) {
      var month = topicMonth.month();
      for (var row : query.query(topicMonth.from(), topicMonth.to())) {
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
      Suppression suppression) {
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
    return toTargetStatistics(TargetType.TENANT, tenantId, null, total, suppression);
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
    var communication = CommunicationCounts.NONE;

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
      communication = communication.plus(group.communication());
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
        chatPeriods,
        communication);
  }

  private TargetStatistics toTargetStatistics(
      TargetType targetType,
      Long tenantId,
      Long agencyId,
      GroupData data,
      Suppression suppression) {
    var suppressed =
        suppression.active() && data.counselorCount() < suppression.minimumCounsellors(targetType);

    if (suppressed) {
      return suppressedTarget(targetType, tenantId, agencyId);
    }

    var metrics =
        new Metrics(
            data.enquiriesCurrentMonth(),
            data.enquiriesPreviousMonth(),
            data.activeCases(),
            data.activeConversationsToday(),
            data.groupChatCountsByPeriod().total(),
            data.communication().counsellorMessages(),
            data.communication().adviceSeekerMessages(),
            data.communication().audioCalls(),
            data.communication().videoCalls());
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
        List.of(),
        buildMonthlyTopTopics(data.topicCountsByMonth()),
        data.sessionCountsByPeriod(),
        data.groupChatCountsByPeriod());
  }

  /** A hidden row says nothing about its people, so its counsellor count is 0, not the real one. */
  private static TargetStatistics suppressedTarget(
      TargetType targetType, Long tenantId, Long agencyId) {
    return new TargetStatistics(
        targetType, tenantId, agencyId, 0, true, null, List.of(), List.of(), List.of(), null, null);
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

  private DateRanges buildLiveRanges(TimeWindows windows) {
    var today = LocalDate.now(clock);
    var weekStart = today.with(DayOfWeek.MONDAY);
    var currentMonth = YearMonth.from(today);
    var topicMonths = new ArrayList<TopicMonth>();
    for (int i = 0; i < windows.topicMonths(); i++) {
      var month = currentMonth.minusMonths(i);
      topicMonths.add(
          new TopicMonth(
              month, month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay()));
    }

    return new DateRanges(
        today.atStartOfDay(),
        today.plusDays(1).atStartOfDay(),
        today.minusDays(1).atStartOfDay(),
        weekStart.atStartOfDay(),
        weekStart.minusWeeks(windows.chartWeeks() - 1L).atStartOfDay(),
        currentMonth.atDay(1).atStartOfDay(),
        currentMonth.plusMonths(1).atDay(1).atStartOfDay(),
        currentMonth.minusMonths(1).atDay(1).atStartOfDay(),
        today.withDayOfYear(1).atStartOfDay(),
        today.withDayOfYear(1).minusYears(1).atStartOfDay(),
        topicMonths,
        false);
  }

  /**
   * The ranges of a fixed stand, in German calendar days and read from its reference day: "this
   * week" and "this month" are the ones that day belongs to, and nothing reaches past its end. The
   * "today" range is empty; {@link #asFixedStand} removes its values.
   */
  private DateRanges buildStandRanges(TimeWindows windows, LocalDate referenceDay) {
    var end = germanDayStart(referenceDay.plusDays(1));
    var weekStart = referenceDay.with(DayOfWeek.MONDAY);
    var currentMonth = YearMonth.from(referenceDay);
    var yearStart = referenceDay.withDayOfYear(1);
    var topicMonths = new ArrayList<TopicMonth>();
    for (int i = 0; i < windows.topicMonths(); i++) {
      var month = currentMonth.minusMonths(i);
      topicMonths.add(
          new TopicMonth(
              month,
              germanDayStart(month.atDay(1)),
              i == 0 ? end : germanDayStart(month.plusMonths(1).atDay(1))));
    }

    return new DateRanges(
        end,
        end,
        germanDayStart(referenceDay),
        germanDayStart(weekStart),
        germanDayStart(weekStart.minusWeeks(windows.chartWeeks() - 1L)),
        germanDayStart(currentMonth.atDay(1)),
        end,
        germanDayStart(currentMonth.minusMonths(1).atDay(1)),
        germanDayStart(yearStart),
        germanDayStart(yearStart.minusYears(1)),
        topicMonths,
        true);
  }

  /** Where a German calendar day starts in the stored time (UTC). */
  private static LocalDateTime germanDayStart(LocalDate day) {
    return LocalDateTime.ofInstant(day.atStartOfDay(GERMAN_ZONE).toInstant(), ZoneOffset.UTC);
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

  private static Map<Long, Map<String, Long>> toGermanDays(List<HourlyCountProjection> rows) {
    var result = new HashMap<Long, Map<String, Long>>();
    for (var row : rows) {
      if (row.getDay() == null || row.getHourOfDay() == null) {
        continue;
      }
      var germanDay =
          row.getDay()
              .atTime(row.getHourOfDay().intValue(), 0)
              .atOffset(ZoneOffset.UTC)
              .atZoneSameInstant(GERMAN_ZONE)
              .toLocalDate();
      result
          .computeIfAbsent(row.getGroupId(), key -> new TreeMap<>())
          .merge(germanDay.toString(), row.getTotal() == null ? 0L : row.getTotal(), Long::sum);
    }
    return result;
  }

  private static PeriodCounts zeroPeriods() {
    return new PeriodCounts(0L, 0L, 0, 0, 0, 0);
  }
}
