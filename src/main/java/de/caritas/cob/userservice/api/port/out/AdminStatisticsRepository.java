package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Session;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only aggregate queries for the admin statistics dashboard.
 *
 * <p>All statistics are computed from the application-layer database only (KDG compliance). Native
 * queries are used because Hibernate tenant filters do not apply to aggregations; therefore every
 * query carries an explicit tenant restriction.
 */
public interface AdminStatisticsRepository extends Repository<Session, Long> {

  interface GroupCountProjection {
    Long getGroupId();

    Long getTotal();
  }

  interface DailyCountProjection {
    Long getGroupId();

    java.sql.Date getDay();

    Long getTotal();
  }

  interface TopicCountProjection {
    Long getGroupId();

    Long getTopicId();

    Long getTotal();
  }

  /* ---------- tenant scope: grouped by agency ---------- */

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.agency_id AS groupId, COUNT(*) AS total FROM session s "
              + "WHERE s.tenant_id = :tenantId "
              + "AND s.create_date >= :fromDate AND s.create_date < :toDate "
              + "GROUP BY s.agency_id")
  List<GroupCountProjection> countNewSessionsByAgency(
      @Param("tenantId") Long tenantId,
      @Param("fromDate") LocalDateTime fromDate,
      @Param("toDate") LocalDateTime toDate);

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.agency_id AS groupId, COUNT(*) AS total FROM session s "
              + "WHERE s.tenant_id = :tenantId AND s.status = 2 "
              + "GROUP BY s.agency_id")
  List<GroupCountProjection> countActiveCasesByAgency(@Param("tenantId") Long tenantId);

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.agency_id AS groupId, COUNT(*) AS total FROM session s "
              + "WHERE s.tenant_id = :tenantId AND s.status = 2 "
              + "AND s.update_date >= :since "
              + "GROUP BY s.agency_id")
  List<GroupCountProjection> countActiveSessionsSinceByAgency(
      @Param("tenantId") Long tenantId, @Param("since") LocalDateTime since);

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.agency_id AS groupId, CAST(s.create_date AS DATE) AS day, COUNT(*) AS total "
              + "FROM session s "
              + "WHERE s.tenant_id = :tenantId AND s.create_date >= :fromDate "
              + "GROUP BY s.agency_id, CAST(s.create_date AS DATE)")
  List<DailyCountProjection> countDailyNewSessionsByAgency(
      @Param("tenantId") Long tenantId, @Param("fromDate") LocalDateTime fromDate);

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.agency_id AS groupId, s.main_topic_id AS topicId, COUNT(*) AS total "
              + "FROM session s "
              + "WHERE s.tenant_id = :tenantId AND s.main_topic_id IS NOT NULL "
              + "AND s.create_date >= :fromDate AND s.create_date < :toDate "
              + "GROUP BY s.agency_id, s.main_topic_id")
  List<TopicCountProjection> countSessionTopicsByAgency(
      @Param("tenantId") Long tenantId,
      @Param("fromDate") LocalDateTime fromDate,
      @Param("toDate") LocalDateTime toDate);

  @Query(
      nativeQuery = true,
      value =
          "SELECT ca.agency_id AS groupId, COUNT(DISTINCT ca.consultant_id) AS total "
              + "FROM consultant_agency ca "
              + "JOIN consultant c ON c.consultant_id = ca.consultant_id "
              + "WHERE ca.tenant_id = :tenantId "
              + "AND ca.delete_date IS NULL AND c.delete_date IS NULL "
              + "GROUP BY ca.agency_id")
  List<GroupCountProjection> countConsultantsByAgency(@Param("tenantId") Long tenantId);

  @Query(
      nativeQuery = true,
      value =
          "SELECT COUNT(*) FROM consultant c "
              + "WHERE c.tenant_id = :tenantId AND c.delete_date IS NULL")
  long countConsultantsForTenant(@Param("tenantId") Long tenantId);

  @Query(
      nativeQuery = true,
      value =
          "SELECT ca.agency_id AS groupId, COUNT(DISTINCT c.id) AS total "
              + "FROM chat c "
              + "JOIN chat_agency ca ON ca.chat_id = c.id "
              + "JOIN consultant co ON co.consultant_id = c.consultant_id_owner "
              + "WHERE co.tenant_id = :tenantId "
              + "AND COALESCE(c.create_date, c.initial_start_date) >= :fromDate "
              + "AND COALESCE(c.create_date, c.initial_start_date) < :toDate "
              + "GROUP BY ca.agency_id")
  List<GroupCountProjection> countGroupChatsByAgency(
      @Param("tenantId") Long tenantId,
      @Param("fromDate") LocalDateTime fromDate,
      @Param("toDate") LocalDateTime toDate);

  @Query(
      nativeQuery = true,
      value =
          "SELECT COUNT(DISTINCT c.id) FROM chat c "
              + "JOIN consultant co ON co.consultant_id = c.consultant_id_owner "
              + "WHERE co.tenant_id = :tenantId "
              + "AND COALESCE(c.create_date, c.initial_start_date) >= :fromDate "
              + "AND COALESCE(c.create_date, c.initial_start_date) < :toDate")
  long countGroupChatsForTenant(
      @Param("tenantId") Long tenantId,
      @Param("fromDate") LocalDateTime fromDate,
      @Param("toDate") LocalDateTime toDate);

  /* ---------- platform scope: grouped by tenant ---------- */

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.tenant_id AS groupId, COUNT(*) AS total FROM session s "
              + "WHERE s.tenant_id IS NOT NULL "
              + "AND s.create_date >= :fromDate AND s.create_date < :toDate "
              + "GROUP BY s.tenant_id")
  List<GroupCountProjection> countNewSessionsByTenant(
      @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.tenant_id AS groupId, COUNT(*) AS total FROM session s "
              + "WHERE s.tenant_id IS NOT NULL AND s.status = 2 "
              + "GROUP BY s.tenant_id")
  List<GroupCountProjection> countActiveCasesByTenant();

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.tenant_id AS groupId, COUNT(*) AS total FROM session s "
              + "WHERE s.tenant_id IS NOT NULL AND s.status = 2 "
              + "AND s.update_date >= :since "
              + "GROUP BY s.tenant_id")
  List<GroupCountProjection> countActiveSessionsSinceByTenant(@Param("since") LocalDateTime since);

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.tenant_id AS groupId, CAST(s.create_date AS DATE) AS day, COUNT(*) AS total "
              + "FROM session s "
              + "WHERE s.tenant_id IS NOT NULL AND s.create_date >= :fromDate "
              + "GROUP BY s.tenant_id, CAST(s.create_date AS DATE)")
  List<DailyCountProjection> countDailyNewSessionsByTenant(
      @Param("fromDate") LocalDateTime fromDate);

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.tenant_id AS groupId, s.main_topic_id AS topicId, COUNT(*) AS total "
              + "FROM session s "
              + "WHERE s.tenant_id IS NOT NULL AND s.main_topic_id IS NOT NULL "
              + "AND s.create_date >= :fromDate AND s.create_date < :toDate "
              + "GROUP BY s.tenant_id, s.main_topic_id")
  List<TopicCountProjection> countSessionTopicsByTenant(
      @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

  @Query(
      nativeQuery = true,
      value =
          "SELECT c.tenant_id AS groupId, COUNT(*) AS total FROM consultant c "
              + "WHERE c.tenant_id IS NOT NULL AND c.delete_date IS NULL "
              + "GROUP BY c.tenant_id")
  List<GroupCountProjection> countConsultantsByTenant();

  @Query(
      nativeQuery = true,
      value =
          "SELECT co.tenant_id AS groupId, COUNT(DISTINCT c.id) AS total "
              + "FROM chat c "
              + "JOIN consultant co ON co.consultant_id = c.consultant_id_owner "
              + "WHERE co.tenant_id IS NOT NULL "
              + "AND COALESCE(c.create_date, c.initial_start_date) >= :fromDate "
              + "AND COALESCE(c.create_date, c.initial_start_date) < :toDate "
              + "GROUP BY co.tenant_id")
  List<GroupCountProjection> countGroupChatsByTenant(
      @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);
}
