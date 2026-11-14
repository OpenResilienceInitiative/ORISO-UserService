package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface DirectInvitationMemberRepository
    extends CrudRepository<DirectInvitationMember, Long> {

  Optional<DirectInvitationMember> findBySessionIdAndUserId(Long sessionId, String userId);

  List<DirectInvitationMember> findBySessionIdAndGuestTrueAndRemovedAtIsNull(Long sessionId);
}
