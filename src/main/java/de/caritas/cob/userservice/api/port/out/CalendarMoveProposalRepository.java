package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarMoveProposal;
import de.caritas.cob.userservice.api.model.CalendarMoveProposal.Status;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/** Move proposals between counsellors (ADR-032, move proposals v1). */
public interface CalendarMoveProposalRepository extends CrudRepository<CalendarMoveProposal, UUID> {

  /** What a decision needs before it takes the locks: read as values, not as the entity. */
  interface Reference {
    UUID getAppointmentId();

    String getApproverId();
  }

  @Query(
      "SELECT p.appointment.id AS appointmentId, p.approverId AS approverId "
          + "FROM CalendarMoveProposal p WHERE p.id = :id")
  Optional<Reference> findReferenceById(@Param("id") UUID id);

  /** Locked, so that a withdrawal and a decision on one proposal cannot overwrite each other. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM CalendarMoveProposal p WHERE p.id = :id")
  Optional<CalendarMoveProposal> findByIdForUpdate(@Param("id") UUID id);

  @Query(
      "SELECT p FROM CalendarMoveProposal p WHERE p.appointment.id = :appointmentId "
          + "AND p.proposerId = :proposerId AND p.status = :status")
  List<CalendarMoveProposal> findOfProposer(
      @Param("appointmentId") UUID appointmentId,
      @Param("proposerId") String proposerId,
      @Param("status") Status status);

  /** The proposals one counsellor made, with their appointment and its conversation. */
  @Query(
      "SELECT p FROM CalendarMoveProposal p JOIN FETCH p.appointment a JOIN FETCH a.session s "
          + "LEFT JOIN FETCH s.consultant WHERE p.proposerId = :proposerId AND p.status = :status "
          + "ORDER BY p.createdAt, p.id")
  List<CalendarMoveProposal> findSent(
      @Param("proposerId") String proposerId, @Param("status") Status status);

  /** The proposals one counsellor is asked to decide, with their appointment and conversation. */
  @Query(
      "SELECT p FROM CalendarMoveProposal p JOIN FETCH p.appointment a JOIN FETCH a.session s "
          + "LEFT JOIN FETCH s.consultant WHERE p.approverId = :approverId AND p.status = :status "
          + "ORDER BY p.createdAt, p.id")
  List<CalendarMoveProposal> findReceived(
      @Param("approverId") String approverId, @Param("status") Status status);

  /** Every other open proposal for an appointment that was just moved by one of them. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE CalendarMoveProposal p SET p.status = :superseded, p.decidedAt = :now "
          + "WHERE p.appointment.id = :appointmentId AND p.status = :open AND p.id <> :acceptedId")
  int supersedeOthers(
      @Param("appointmentId") UUID appointmentId,
      @Param("acceptedId") UUID acceptedId,
      @Param("now") Instant now,
      @Param("open") Status open,
      @Param("superseded") Status superseded);

  /** Removes every proposal a counsellor whose account is deleted made or was asked to decide. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "DELETE FROM CalendarMoveProposal p "
          + "WHERE p.proposerId = :consultantId OR p.approverId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
