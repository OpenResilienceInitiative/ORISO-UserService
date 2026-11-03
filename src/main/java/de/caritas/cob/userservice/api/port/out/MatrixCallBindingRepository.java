package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixCallBindingRepository extends JpaRepository<MatrixCallBinding, Long> {
  Optional<MatrixCallBinding> findBySourceRoomIdAndCallId(String sourceRoomId, String callId);

  Optional<MatrixCallBinding> findByMediaRoomId(String mediaRoomId);

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

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select b from MatrixCallBinding b where b.mediaRoomId = :room")
  Optional<MatrixCallBinding> lockByMediaRoomId(
      @org.springframework.data.repository.query.Param("room") String room);

  /** Calls of the given sessions, for the temporary-account cleanup (#1476). */
  java.util.List<MatrixCallBinding> findBySessionIdIn(java.util.Collection<Long> sessionIds);
}
