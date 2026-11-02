package de.caritas.cob.userservice.api.service.statistics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.StatisticsDashboardSnapshot;
import de.caritas.cob.userservice.api.port.out.StatisticsDashboardSnapshotRepository;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService.DashboardScope;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService.DashboardStatistics;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSnapshotInterval;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The stored stands of the admin dashboard (differencing protection, UserService#1547). There is
 * one stand per cutoff, in the database, so every replica and every request serves the same
 * numbers. If none exists yet, the first caller calculates it under a lease; a failure or a
 * calculation running elsewhere serves the last stand, never live numbers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardStandService {

  static final String LEASE_PREFIX = "statistics-stand:";
  private static final Duration CALCULATION_LEASE = Duration.ofMinutes(5);

  private final @NonNull StatisticsDashboardSnapshotRepository repository;
  private final @NonNull AdminDashboardStandWriter writer;
  private final @NonNull ScheduledTaskClaimService claims;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull Clock clock;

  public DashboardStatistics currentStand(
      DashboardScope scope,
      Long tenantId,
      StatisticsSnapshotInterval interval,
      Supplier<DashboardStatistics> calculation) {
    var cutoff = utc(interval.latestCutoff(clock.instant()));
    var stored = read(scope, tenantId, interval, cutoff);
    if (stored.isPresent()) {
      return stored.get();
    }

    var lease = claims.tryClaimLease(LEASE_PREFIX + scope + ":" + tenantId, CALCULATION_LEASE);
    if (lease.isEmpty()) {
      return lastStand(scope, tenantId)
          .orElseThrow(
              () -> new ServiceUnavailableException("The statistics stand is being calculated"));
    }
    try {
      return read(scope, tenantId, interval, cutoff)
          .orElseGet(() -> store(scope, tenantId, interval, cutoff, calculation.get()));
    } catch (RuntimeException failure) {
      log.error(
          "Could not calculate the {} statistics stand of tenant {}", scope, tenantId, failure);
      return lastStand(scope, tenantId).orElseThrow(() -> failure);
    } finally {
      release(lease.get());
    }
  }

  private DashboardStatistics store(
      DashboardScope scope,
      Long tenantId,
      StatisticsSnapshotInterval interval,
      LocalDateTime cutoff,
      DashboardStatistics calculated) {
    try {
      writer.replace(
          StatisticsDashboardSnapshot.builder()
              .scope(scope.name())
              .tenantId(tenantId)
              .snapshotInterval(interval.name())
              .cutoffAt(cutoff)
              .computedAt(utc(clock.instant()))
              .payload(objectMapper.writeValueAsString(calculated))
              .build());
      return calculated;
    } catch (DataIntegrityViolationException lostToAnotherReplica) {
      return read(scope, tenantId, interval, cutoff).orElseThrow(() -> lostToAnotherReplica);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Could not serialize the statistics stand", failure);
    }
  }

  private Optional<DashboardStatistics> read(
      DashboardScope scope,
      Long tenantId,
      StatisticsSnapshotInterval interval,
      LocalDateTime cutoff) {
    return repository
        .findByScopeAndTenantIdAndSnapshotIntervalAndCutoffAt(
            scope.name(), tenantId, interval.name(), cutoff)
        .flatMap(this::parse);
  }

  private Optional<DashboardStatistics> lastStand(DashboardScope scope, Long tenantId) {
    return repository
        .findFirstByScopeAndTenantIdOrderByCutoffAtDesc(scope.name(), tenantId)
        .flatMap(this::parse);
  }

  /** A stand no release can read any more counts as missing and makes room for a new one. */
  private Optional<DashboardStatistics> parse(StatisticsDashboardSnapshot stand) {
    try {
      return Optional.of(objectMapper.readValue(stand.getPayload(), DashboardStatistics.class));
    } catch (JsonProcessingException unreadable) {
      log.warn("Removing unreadable statistics stand {}", stand.getId());
      repository.deleteById(stand.getId());
      return Optional.empty();
    }
  }

  private void release(ScheduledTaskClaimService.ClaimLease lease) {
    try {
      claims.release(lease);
    } catch (RuntimeException failure) {
      log.warn("Could not release statistics stand lease ({})", failure.getClass().getSimpleName());
    }
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
