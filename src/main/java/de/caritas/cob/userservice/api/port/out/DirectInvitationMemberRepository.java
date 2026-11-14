package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface DirectInvitationMemberRepository
    extends CrudRepository<DirectInvitationMember, Long> {

  Optional<DirectInvitationMember> findBySessionIdAndUserId(Long sessionId, String userId);

  /** Locked: one join attempt or confirmation at a time per person and conversation (#2252). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT m FROM DirectInvitationMember m WHERE m.sessionId = :sessionId"
          + " AND m.userId = :userId")
  Optional<DirectInvitationMember> findBySessionIdAndUserIdForUpdate(
      @Param("sessionId") Long sessionId, @Param("userId") String userId);

  /** The row the sweep removes, locked so a join attempt of the same person waits for it. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT m FROM DirectInvitationMember m WHERE m.id = :id")
  Optional<DirectInvitationMember> findByIdForUpdate(@Param("id") Long id);

  List<DirectInvitationMember> findByInviteLinkIdAndRemovedAtIsNull(Long inviteLinkId);

  /**
   * Removals Matrix has not confirmed yet whose next attempt is due at {@code now}, for the sweep
   * (#2252). A failed attempt moves {@code revokeDueAt} on, so a lasting failure is retried with a
   * delay instead of every run.
   */
  @Query(
      "SELECT m.id FROM DirectInvitationMember m WHERE m.revokeDueAt IS NOT NULL"
          + " AND m.revokeDueAt <= :now AND m.removedAt IS NULL AND m.id > :afterId"
          + " ORDER BY m.id")
  List<Long> findPendingRevocationIds(
      @Param("now") LocalDateTime now, @Param("afterId") Long afterId, Pageable pageable);
}
