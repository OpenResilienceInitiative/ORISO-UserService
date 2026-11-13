package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixCallBindingRepository extends JpaRepository<MatrixCallBinding, Long> {
  Optional<MatrixCallBinding> findBySourceRoomIdAndCallId(String sourceRoomId, String callId);

  Optional<MatrixCallBinding> findByMediaRoomId(String mediaRoomId);

  /** Media rooms of the calls still running in this conversation (ORISO-Admin#1518 follow-up). */
  @org.springframework.data.jpa.repository.Query(
      "select b.mediaRoomId from MatrixCallBinding b where b.sourceRoomId = :room"
          + " and b.endedAt is null order by b.id")
  java.util.List<String> findRunningMediaRoomIds(
      @org.springframework.data.repository.query.Param("room") String sourceRoomId);

  /** A call ran or runs in this conversation room (#1487 knock scope). */
  boolean existsBySourceRoomId(String sourceRoomId);

  @org.springframework.data.jpa.repository.Query(
      "select b.mediaRoomId from MatrixCallBinding b where b.mediaRoomId in :rooms")
  java.util.Set<String> findKnownMediaRooms(
      @org.springframework.data.repository.query.Param("rooms") java.util.Collection<String> rooms);

  @org.springframework.data.jpa.repository.Query(
      "select b.mediaRoomId from MatrixCallBinding b where b.endedAt is null "
          + "and b.mediaObservedAt is null and (b.nextObservationAttemptAt is null or b.nextObservationAttemptAt <= :now) "
          + "order by coalesce(b.nextObservationAttemptAt, 0), b.id")
  java.util.List<String> findObservationCandidates(
      @org.springframework.data.repository.query.Param("now") long now,
      org.springframework.data.domain.Pageable page);

  @org.springframework.data.jpa.repository.Query(
      "select b.mediaRoomId from MatrixCallBinding b left join b.devices d "
          + "where b.endedAt is null and b.mediaObservedAt is not null "
          + "group by b.mediaRoomId, b.startedAt, b.inviteExpiresAt "
          + "having (b.startedAt is null and b.inviteExpiresAt <= :now) "
          + "or (b.startedAt is not null and max(d.expiresAt) <= :now) order by b.mediaRoomId")
  java.util.List<String> findExpiryCandidates(
      @org.springframework.data.repository.query.Param("now") long now,
      org.springframework.data.domain.Pageable page);

  /**
   * FE#2209 review: session calls nobody was ever seen in and whose call room was never observed,
   * well after the invite ran out. Without this they would never end.
   */
  @org.springframework.data.jpa.repository.Query(
      "select b.mediaRoomId from MatrixCallBinding b where b.endedAt is null "
          + "and b.sessionId is not null and b.startedAt is null and b.mediaObservedAt is null "
          + "and b.inviteExpiresAt <= :cutoff order by b.id")
  java.util.List<String> findNeverObservedSessionCalls(
      @org.springframework.data.repository.query.Param("cutoff") long cutoff,
      org.springframework.data.domain.Pageable page);

  /**
   * The call bound to a call room, locked. FE#2209 round 3: locked through the primary key only. A
   * locking read through the media-room index would hold that index entry while waiting for the
   * row, and a takeover (which holds the row and moves it to another call room) would then wait for
   * the entry: a deadlock. A row that moved meanwhile is not this room's call any more.
   */
  default Optional<MatrixCallBinding> lockByMediaRoomId(String room) {
    return findIdByMediaRoomId(room)
        .flatMap(this::lockById)
        .filter(binding -> room.equals(binding.getMediaRoomId()));
  }

  @org.springframework.data.jpa.repository.Query(
      "select b.id from MatrixCallBinding b where b.mediaRoomId = :room")
  Optional<Long> findIdByMediaRoomId(
      @org.springframework.data.repository.query.Param("room") String room);

  /**
   * FE#2209 round 3: the call row by id, locked. Session-call paths lock the binding first, then
   * the session, then observation and end rows, then the share link and its knocks.
   */
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select b from MatrixCallBinding b where b.id = :id")
  Optional<MatrixCallBinding> lockById(
      @org.springframework.data.repository.query.Param("id") Long id);

  /** Every call of one conversation room, locked in id order (admit-all, same lock order). */
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select b from MatrixCallBinding b where b.sourceRoomId = :room order by b.id")
  java.util.List<MatrixCallBinding> lockBySourceRoomId(
      @org.springframework.data.repository.query.Param("room") String room);

  /** Calls of the given sessions, for the temporary-account cleanup (#1476). */
  java.util.List<MatrixCallBinding> findBySessionIdIn(java.util.Collection<Long> sessionIds);
}
