package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface DirectInvitationMemberRepository
    extends CrudRepository<DirectInvitationMember, Long> {

  Optional<DirectInvitationMember> findBySessionIdAndUserId(Long sessionId, String userId);

  List<DirectInvitationMember> findByInviteLinkIdAndRemovedAtIsNull(Long inviteLinkId);

  /** Removals that are due and Matrix has not confirmed yet, for the sweep (#2252). */
  @Query(
      "SELECT m.id FROM DirectInvitationMember m WHERE m.revokeDueAt IS NOT NULL"
          + " AND m.removedAt IS NULL AND m.id > :afterId ORDER BY m.id")
  List<Long> findPendingRevocationIds(@Param("afterId") Long afterId, Pageable pageable);
}
