package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HandshakeSessionRepository extends JpaRepository<HandshakeSession, String> {

  List<HandshakeSession> findAllByCounterpartIdAndStatusAndExpiryDateAfter(
      String counterpartId, HandshakeStatus status, LocalDateTime after);

  List<HandshakeSession> findAllByStatusAndExpiryDateBefore(
      HandshakeStatus status, LocalDateTime before);

  boolean existsByInitiatorIdAndCounterpartIdAndAgencyIdAndPurposeAndStatusIn(
      String initiatorId,
      String counterpartId,
      Long agencyId,
      HandshakePurpose purpose,
      List<HandshakeStatus> statuses);

  /**
   * Conditional PENDING → CONFIRMED transition. Returns the number of affected rows; only a caller
   * that sees exactly {@code 1} may create the support session and its outbox job, so two
   * concurrent confirmations can never both win.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update HandshakeSession h
         set h.status = de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus.CONFIRMED,
             h.confirmedDate = :now
       where h.id = :id
         and h.status = de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus.PENDING
         and h.expiryDate > :now
      """)
  int confirmIfStillPending(@Param("id") String id, @Param("now") LocalDateTime now);
}
