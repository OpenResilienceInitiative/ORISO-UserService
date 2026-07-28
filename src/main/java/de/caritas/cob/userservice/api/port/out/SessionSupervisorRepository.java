package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.SessionSupervisor;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionSupervisorRepository extends JpaRepository<SessionSupervisor, Long> {

  /**
   * Find all active supervisors for a session.
   *
   * @param sessionId the session ID
   * @return list of active supervisors
   */
  List<SessionSupervisor> findBySessionIdAndIsActiveTrue(Long sessionId);

  /**
   * Find ALL supervisor rows for a session, regardless of active state. Used to inspect
   * consent-pending rows (ADR-008 item 4): the consent state lives inside the {@code notes} JSON,
   * not a queryable column, so callers decode and filter in Java. Supervisor rows per session are
   * few, so a full fetch is cheap.
   *
   * @param sessionId the session ID
   * @return all supervisor rows for the session
   */
  List<SessionSupervisor> findBySessionId(Long sessionId);

  /**
   * Find all sessions supervised by a consultant.
   *
   * @param consultantId the consultant ID
   * @return list of supervised sessions
   */
  @Query(
      "SELECT ss FROM SessionSupervisor ss "
          + "WHERE ss.supervisorConsultant.id = :consultantId "
          + "AND ss.isActive = true")
  List<SessionSupervisor> findActiveSupervisionsByConsultantId(
      @Param("consultantId") String consultantId);

  /**
   * Find active supervisor relationship for a session and consultant.
   *
   * @param sessionId the session ID
   * @param consultantId the consultant ID
   * @return optional supervisor relationship
   */
  Optional<SessionSupervisor> findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(
      Long sessionId, String consultantId);

  /**
   * Count active supervisors for a session.
   *
   * @param sessionId the session ID
   * @return count of active supervisors
   */
  long countBySessionIdAndIsActiveTrue(Long sessionId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from SessionSupervisor supervisor where supervisor.session.id = :sessionId")
  int deleteAllBySessionId(@Param("sessionId") Long sessionId);

  /**
   * Deletes every supervision row that references the given consultant, through either the
   * supervisor or the "added by" column.
   *
   * <p>Both columns are {@code NOT NULL} in the schema, so a row cannot outlive either consultant
   * it points at. Deleting is therefore forced rather than chosen.
   *
   * @param consultantId the consultant ID
   * @return number of deleted rows
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "delete from SessionSupervisor supervisor where supervisor.supervisorConsultant.id ="
          + " :consultantId or supervisor.addedByConsultant.id = :consultantId")
  int deleteAllByConsultantId(@Param("consultantId") String consultantId);
}
