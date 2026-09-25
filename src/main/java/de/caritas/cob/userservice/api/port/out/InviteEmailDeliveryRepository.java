package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InviteEmailDeliveryRepository extends JpaRepository<InviteEmailDelivery, Long> {

  List<InviteEmailDelivery> findByAccountInviteIdOrderByCreateDateDesc(Long accountInviteId);

  Optional<InviteEmailDelivery> findFirstByAccountInviteIdOrderByCreateDateDesc(
      Long accountInviteId);

  /** Every delivery of these invites, newest first, for a whole invite list in one query. */
  List<InviteEmailDelivery> findByAccountInviteIdInOrderByCreateDateDesc(
      Collection<Long> accountInviteIds);
}
