package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.SessionAppointment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface SessionAppointmentRepository extends CrudRepository<SessionAppointment, UUID> {

  @Query(
      "SELECT a FROM SessionAppointment a WHERE a.session.id = :sessionId ORDER BY a.startTime DESC")
  List<SessionAppointment> findNewestOfSession(
      @Param("sessionId") Long sessionId, Pageable pageable);

  /** Locked, so that a cancellation and a reschedule of one appointment cannot overwrite. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM SessionAppointment a WHERE a.id = :id AND a.session.id = :sessionId")
  Optional<SessionAppointment> findOfSessionForUpdate(
      @Param("id") UUID id, @Param("sessionId") Long sessionId);

  /**
   * Locked like {@link #findOfSessionForUpdate}, by the appointment alone: a move proposal
   * (ADR-032) names only the appointment, and the first people opening one direct invitation create
   * one conversation (#2252). The session is not locked with it.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM SessionAppointment a WHERE a.id = :id")
  Optional<SessionAppointment> findByIdForUpdate(@Param("id") UUID id);

  /**
   * The counsellor's upcoming appointments of their own and co-access sessions, and the requests
   * waiting for them in their own sessions (ORISO-Frontend#2287): a request is seen only by the
   * counsellor of its conversation, never through co-access. Declined requests are left out.
   */
  @Query(
      "SELECT a FROM SessionAppointment a JOIN FETCH a.session s JOIN FETCH s.user "
          + "WHERE ((a.status = 'SCHEDULED' "
          + "AND (s.consultant.id = :consultantId OR s.id IN :coAccessSessionIds)) "
          + "OR (a.status = 'REQUESTED' AND s.consultant.id = :consultantId "
          + "AND a.startTime > :now)) "
          + "AND a.endTime > :now "
          + "ORDER BY a.startTime")
  List<SessionAppointment> findUpcomingOfConsultant(
      @Param("consultantId") String consultantId,
      @Param("coAccessSessionIds") Collection<Long> coAccessSessionIds,
      @Param("now") Instant now,
      Pageable pageable);

  /**
   * The asker's upcoming appointments and their own requests still waiting (#2287); a request whose
   * start has come is expired, even before the sweep declines it.
   */
  @Query(
      "SELECT a FROM SessionAppointment a JOIN FETCH a.session s LEFT JOIN FETCH s.consultant "
          + "WHERE s.user.userId = :userId "
          + "AND (a.status = 'SCHEDULED' OR (a.status = 'REQUESTED' AND a.startTime > :now)) "
          + "AND a.endTime > :now "
          + "ORDER BY a.startTime")
  List<SessionAppointment> findUpcomingOfAsker(
      @Param("userId") String userId, @Param("now") Instant now, Pageable pageable);

  /**
   * The scheduled appointments of a counsellor's own and co-access sessions that overlap the window
   * (calendar, ADR-032).
   */
  @Query(
      "SELECT a FROM SessionAppointment a JOIN FETCH a.session s JOIN FETCH s.user "
          + "WHERE (s.consultant.id = :consultantId OR s.id IN :coAccessSessionIds) "
          + "AND a.status = 'SCHEDULED' AND a.startTime < :to AND a.endTime > :from "
          + "ORDER BY a.startTime")
  List<SessionAppointment> findScheduledOfConsultantBetween(
      @Param("consultantId") String consultantId,
      @Param("coAccessSessionIds") Collection<Long> coAccessSessionIds,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);

  /**
   * The scheduled appointments of the counsellor's own direct invitations that nobody opened yet
   * (ORISO-Frontend#2252): they have no conversation, so {@link #findScheduledOfConsultantBetween}
   * cannot find them, yet the counsellor's time is taken all the same.
   */
  @Query(
      "SELECT a FROM SessionAppointment a WHERE a.session IS NULL AND a.status = 'SCHEDULED' "
          + "AND a.startTime < :to AND a.endTime > :from "
          + "AND EXISTS (SELECT l.id FROM AgencyInviteLink l WHERE l.appointmentId = a.id "
          + "AND l.consultantId = :consultantId AND l.linkKind = 'COUNSELLOR') "
          + "ORDER BY a.startTime")
  List<SessionAppointment> findUnopenedInvitationsOfConsultantBetween(
      @Param("consultantId") String consultantId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);

  @Query(
      "SELECT a FROM SessionAppointment a JOIN FETCH a.session s LEFT JOIN FETCH s.consultant "
          + "WHERE s.user.userId = :userId AND a.status = 'SCHEDULED' "
          + "AND a.startTime < :to AND a.endTime > :from "
          + "ORDER BY a.startTime")
  List<SessionAppointment> findScheduledOfAskerBetween(
      @Param("userId") String userId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);

  /**
   * The appointments that take a counsellor's time in the window, read with a lock (self-booking,
   * ORISO-Frontend#1050, #2287): scheduled and requested ones in their own conversations, scheduled
   * ones in conversations they hold co-access to, as their calendar shows them. A locking read sees
   * what other transactions committed after this one began, which a plain read at REPEATABLE READ
   * does not: the last check before a booking is stored must not miss the booking that won the
   * race, also not one in a co-access conversation, whose booking locks another counsellor's row.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT a FROM SessionAppointment a WHERE a.startTime < :to AND a.endTime > :from AND ("
          + "(a.session.consultant.id = :consultantId AND a.status IN ('SCHEDULED', 'REQUESTED'))"
          + " OR (a.session.id IN :coAccessSessionIds AND a.status = 'SCHEDULED'))")
  List<SessionAppointment> lockScheduledOfConsultantBetween(
      @Param("consultantId") String consultantId,
      @Param("coAccessSessionIds") Collection<Long> coAccessSessionIds,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /**
   * The conversation's scheduled and requested appointments that have not ended, read with a lock:
   * the self-booking limit counts what other transactions committed meanwhile (security review); a
   * request waiting for the counsellor counts as well (ORISO-Frontend#2287), until its start: then
   * it is expired.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT a FROM SessionAppointment a WHERE a.session.id = :sessionId "
          + "AND (a.status = 'SCHEDULED' OR (a.status = 'REQUESTED' AND a.startTime > :now)) "
          + "AND a.endTime > :now")
  List<SessionAppointment> lockUpcomingOfSession(
      @Param("sessionId") Long sessionId, @Param("now") Instant now);

  /**
   * The requests waiting for the counsellor of their conversation that overlap the window
   * (ORISO-Frontend#2287): they take the counsellor's time from other advice seekers and show
   * provisionally in that counsellor's own calendar, but not in a colleague's view, through
   * co-access or in the subscription feed.
   */
  @Query(
      "SELECT a FROM SessionAppointment a JOIN FETCH a.session s JOIN FETCH s.user "
          + "WHERE s.consultant.id = :consultantId "
          + "AND a.status = 'REQUESTED' AND a.startTime < :to AND a.endTime > :from "
          + "ORDER BY a.startTime")
  List<SessionAppointment> findRequestedOfConsultantBetween(
      @Param("consultantId") String consultantId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /**
   * Declines every request nobody answered by its start (ORISO-Frontend#2287, Frank 17 Nov 2026):
   * at the start itself it is expired. Marks the conversation notice as pending.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE SessionAppointment a SET a.status = :declined, a.autoDeclined = true, "
          + "a.noticePending = true WHERE a.status = :requested AND a.startTime <= :now")
  int declineRequestsStartedBy(
      @Param("now") Instant now,
      @Param("requested") SessionAppointment.Status requested,
      @Param("declined") SessionAppointment.Status declined);

  /** Claims the pending notice of an automatic decline; 1 for the one caller that got it. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE SessionAppointment a SET a.noticePending = false "
          + "WHERE a.id = :id AND a.autoDeclined = true AND a.noticePending = true")
  int claimExpiryNotice(@Param("id") UUID id);
}
