package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.SupportAccessSession;
import de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportAccessSessionRepository
    extends JpaRepository<SupportAccessSession, String> {

  Optional<SupportAccessSession> findByHandshakeId(String handshakeId);

  List<SupportAccessSession> findAllByStatusAndExpiryDateBefore(
      SupportAccessSessionStatus status, LocalDateTime before);

  List<SupportAccessSession> findAllByStatus(SupportAccessSessionStatus status, Pageable pageable);

  List<SupportAccessSession> findAllByStatusInAndConsultantId(
      List<SupportAccessSessionStatus> statuses, String consultantId);

  List<SupportAccessSession> findAllByStatusInAndSupportAdminId(
      List<SupportAccessSessionStatus> statuses, String supportAdminId);

  boolean existsBySupportAdminIdAndConsultantIdAndStatusIn(
      String supportAdminId, String consultantId, List<SupportAccessSessionStatus> statuses);

  List<SupportAccessSession> findAllBySupportAdminIdAndStatusIn(
      String supportAdminId, List<SupportAccessSessionStatus> statuses);

  /**
   * Conditional move into REVOCATION_PENDING. Expiry, manual termination, and disabling a support
   * admin all race for the same session; only the caller that changes exactly one row owns the
   * transition and may enqueue the revocation job.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update SupportAccessSession s
         set s.status = de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus.REVOCATION_PENDING,
             s.closeReason = :reason,
             s.revocationStartedDate = :now
       where s.id = :id
         and s.status in (
             de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus.PROVISIONING,
             de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus.ACTIVE)
      """)
  int beginRevocation(
      @Param("id") String id, @Param("reason") String reason, @Param("now") LocalDateTime now);

  long countByStatus(SupportAccessSessionStatus status);

  /** Withdrawal that has been unproven for too long — the operational alert. */
  long countByStatusAndRevocationStartedDateBefore(
      SupportAccessSessionStatus status, LocalDateTime before);

  /**
   * Sessions past their lease that are not terminal yet. A non-zero value means a support identity
   * may still reach a room it should have been removed from.
   */
  long countByStatusInAndExpiryDateBefore(
      List<SupportAccessSessionStatus> statuses, LocalDateTime before);
}
