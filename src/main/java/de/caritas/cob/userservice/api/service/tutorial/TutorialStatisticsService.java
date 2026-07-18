package de.caritas.cob.userservice.api.service.tutorial;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.TutorialStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.TutorialStatisticsRepository.TutorialCountProjection;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregate tutorial-completion statistics for authorized admins (epic TOUR-06). The response is
 * scoped to the caller: platform admins receive global counts grouped per tenant, tenant admins
 * receive only their own tenant. Agency admins, consultants and advice seekers are rejected. No
 * response ever contains an individual user's tutorial history — counts only.
 */
@Service
@RequiredArgsConstructor
public class TutorialStatisticsService {

  private final @NonNull TutorialStatisticsRepository tutorialStatisticsRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull Clock clock;

  public enum TutorialStatisticsScope {
    PLATFORM,
    TENANT
  }

  public record TutorialStatistics(
      String generatedAt, TutorialStatisticsScope scope, List<TenantTutorialCounts> tenants) {}

  public record TenantTutorialCounts(Long tenantId, List<TutorialCount> counts) {}

  public record TutorialCount(
      String surface, String tourId, Integer tourVersion, String status, long total) {}

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

  private TutorialStatistics build(
      TutorialStatisticsScope scope, List<TutorialCountProjection> projections) {
    // Legacy rows may carry a null tenant id; nullsLast keeps them addressable and ordered.
    Map<Long, List<TutorialCount>> countsByTenant =
        new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
    for (var projection : projections) {
      countsByTenant
          .computeIfAbsent(projection.getTenantId(), unused -> new ArrayList<>())
          .add(
              new TutorialCount(
                  projection.getSurface(),
                  projection.getTourId(),
                  projection.getTourVersion(),
                  projection.getStatus(),
                  projection.getTotal()));
    }

    var tenants =
        countsByTenant.entrySet().stream()
            .map(entry -> new TenantTutorialCounts(entry.getKey(), List.copyOf(entry.getValue())))
            .toList();
    return new TutorialStatistics(OffsetDateTime.now(clock).toString(), scope, tenants);
  }
}
