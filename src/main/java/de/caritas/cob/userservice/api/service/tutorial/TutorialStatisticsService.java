package de.caritas.cob.userservice.api.service.tutorial;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.TutorialStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.TutorialStatisticsRepository.TutorialCountProjection;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSetting;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregate tutorial-completion statistics for authorized admins (epic TOUR-06). The response is
 * scoped to the caller: platform admins receive global counts grouped per tenant, tenant admins
 * receive only their own tenant. Agency admins, consultants and advice seekers are rejected. No
 * response ever contains an individual user's tutorial history — counts only.
 *
 * <p>Small-cell suppression (UserService#527): a {@code (tenant, surface, tour, version)} group
 * that fewer users than the tenant minimum of the global statistics settings contributed to is
 * returned as one row with {@code suppressed = true}, no status and a total of 0. Each user has one
 * row per group, so the contributors of a group are the sum of its status counts. The response
 * holds no totals across groups, so a hidden group cannot be derived from a shown one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TutorialStatisticsService {

  private final @NonNull TutorialStatisticsRepository tutorialStatisticsRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull Clock clock;
  private final @NonNull StatisticsSettingsService statisticsSettingsService;
  private final @NonNull Environment environment;

  /**
   * Escape hatch for end-to-end testing with small test datasets, the same switch as the
   * counselling statistics. Fail-closed: it only takes effect on the {@code local} and {@code
   * testing} profiles; on any other profile, or none, suppression stays enforced.
   */
  @Value("${statistics.small-cell-suppression.enabled:true}")
  private boolean smallCellSuppressionEnabled = true;

  private static final Profiles SUPPRESSION_MAY_BE_DISABLED = Profiles.of("local", "testing");

  public enum TutorialStatisticsScope {
    PLATFORM,
    TENANT
  }

  /**
   * @param smallCellMinimum users a group needs before its counts are shown; 0 when suppression is
   *     switched off (local and testing profiles only)
   */
  public record TutorialStatistics(
      String generatedAt,
      TutorialStatisticsScope scope,
      List<TenantTutorialCounts> tenants,
      int smallCellMinimum) {}

  public record TenantTutorialCounts(Long tenantId, List<TutorialCount> counts) {}

  /**
   * A suppressed row stands for a whole group: {@code status} is null and {@code total} is 0, never
   * the real number.
   */
  public record TutorialCount(
      String surface,
      String tourId,
      Integer tourVersion,
      String status,
      long total,
      boolean suppressed) {}

  private record Group(Long tenantId, String surface, String tourId, Integer tourVersion) {}

  @Transactional(readOnly = true)
  public TutorialStatistics getStatistics() {
    if (authenticatedUser.isPlatformAdmin()) {
      return build(TutorialStatisticsScope.PLATFORM, tutorialStatisticsRepository.countGlobal());
    }
    if (authenticatedUser.isTenantSuperAdmin() || authenticatedUser.isSingleTenantAdmin()) {
      var tenantId = resolveTenantId();
      if (tenantId == null) {
        throw new ForbiddenException("No tenant context available for tutorial statistics");
      }
      return build(
          TutorialStatisticsScope.TENANT, tutorialStatisticsRepository.countByTenant(tenantId));
    }
    throw new ForbiddenException(
        "User %s is not authorized to access tutorial statistics"
            .formatted(authenticatedUser.getUserId()));
  }

  private Long resolveTenantId() {
    var contextTenant = TenantContext.getCurrentTenant();
    if (contextTenant != null && !TenantContext.TECHNICAL_TENANT_ID.equals(contextTenant)) {
      return contextTenant;
    }
    return authenticatedUser.getTenantId();
  }

  private boolean resolveSuppressionActive() {
    if (smallCellSuppressionEnabled) {
      return true;
    }
    if (!environment.acceptsProfiles(SUPPRESSION_MAY_BE_DISABLED)) {
      log.warn(
          "statistics.small-cell-suppression.enabled=false was requested, but no local or testing "
              + "profile is active - small-cell suppression stays enforced (fail-closed)");
      return true;
    }
    log.info("Small-cell suppression is DISABLED for tutorial statistics (local or testing)");
    return false;
  }

  private TutorialStatistics build(
      TutorialStatisticsScope scope, List<TutorialCountProjection> projections) {
    var suppressionActive = resolveSuppressionActive();
    var minimum =
        suppressionActive
            ? statisticsSettingsService.minimumCounsellors(
                StatisticsSetting.TENANT_MINIMUM_COUNSELLORS)
            : 0;

    Map<Group, List<TutorialCountProjection>> groups = new LinkedHashMap<>();
    for (var projection : projections) {
      groups
          .computeIfAbsent(
              new Group(
                  projection.getTenantId(),
                  projection.getSurface(),
                  projection.getTourId(),
                  projection.getTourVersion()),
              unused -> new ArrayList<>())
          .add(projection);
    }

    // Legacy rows may carry a null tenant id; nullsLast keeps them addressable and ordered.
    Map<Long, List<TutorialCount>> countsByTenant =
        new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
    groups.forEach(
        (group, rows) -> {
          var target =
              countsByTenant.computeIfAbsent(group.tenantId(), unused -> new ArrayList<>());
          var contributors = rows.stream().mapToLong(TutorialCountProjection::getTotal).sum();
          if (suppressionActive && contributors < minimum) {
            target.add(
                new TutorialCount(
                    group.surface(), group.tourId(), group.tourVersion(), null, 0, true));
            return;
          }
          rows.forEach(
              row ->
                  target.add(
                      new TutorialCount(
                          group.surface(),
                          group.tourId(),
                          group.tourVersion(),
                          row.getStatus(),
                          row.getTotal(),
                          false)));
        });

    var tenants =
        countsByTenant.entrySet().stream()
            .map(entry -> new TenantTutorialCounts(entry.getKey(), List.copyOf(entry.getValue())))
            .toList();
    return new TutorialStatistics(OffsetDateTime.now(clock).toString(), scope, tenants, minimum);
  }
}
