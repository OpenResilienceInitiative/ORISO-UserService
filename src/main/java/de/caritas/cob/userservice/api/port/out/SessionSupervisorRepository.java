package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.SessionSupervisor;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}

