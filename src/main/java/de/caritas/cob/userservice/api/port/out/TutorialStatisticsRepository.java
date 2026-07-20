package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TutorialProgress;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only aggregate queries over versioned tutorial progress (epic TOUR-06). Only counts are
 * projected — no query ever selects a user id, so no admin can obtain an individual user's tutorial
 * history through this repository.
 *
 * <p>Native queries are used because Hibernate tenant filters do not apply to aggregations;
 * therefore the tenant-scoped query carries an explicit tenant restriction that mirrors the
 * entity's {@code tenantFilter} condition (tenant 1 also owns legacy rows without a tenant id).
 */
public interface TutorialStatisticsRepository extends Repository<TutorialProgress, Long> {

  interface TutorialCountProjection {
    Long getTenantId();

    String getSurface();

    String getTourId();

    Integer getTourVersion();

    String getStatus();

    Long getTotal();
  }

  @Query(
      nativeQuery = true,
      value =
          "SELECT tp.tenant_id AS tenantId, tp.surface AS surface, tp.tour_id AS tourId, "
              + "tp.tour_version AS tourVersion, tp.status AS status, COUNT(*) AS total "
              + "FROM tutorial_progress tp "
              + "WHERE (tp.tenant_id = :tenantId OR (:tenantId = 1 AND tp.tenant_id IS NULL)) "
              + "GROUP BY tp.tenant_id, tp.surface, tp.tour_id, tp.tour_version, tp.status")
  List<TutorialCountProjection> countByTenant(@Param("tenantId") Long tenantId);

  @Query(
      nativeQuery = true,
      value =
          "SELECT tp.tenant_id AS tenantId, tp.surface AS surface, tp.tour_id AS tourId, "
              + "tp.tour_version AS tourVersion, tp.status AS status, COUNT(*) AS total "
              + "FROM tutorial_progress tp "
              + "GROUP BY tp.tenant_id, tp.surface, tp.tour_id, tp.tour_version, tp.status")
  List<TutorialCountProjection> countGlobal();
}
