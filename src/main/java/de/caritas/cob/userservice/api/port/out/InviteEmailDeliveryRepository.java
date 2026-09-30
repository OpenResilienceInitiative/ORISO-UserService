package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InviteEmailDeliveryRepository extends JpaRepository<InviteEmailDelivery, Long> {

  List<InviteEmailDelivery> findByAccountInviteIdOrderByCreateDateDesc(Long accountInviteId);

  Optional<InviteEmailDelivery> findFirstByAccountInviteIdOrderByCreateDateDesc(
      Long accountInviteId);

  /**
   * The newest delivery of each of these invites, for a whole invite list in one query; on a tie of
   * create dates the higher ID comes first.
   */
  @Query(
      "SELECT d FROM InviteEmailDelivery d"
          + " WHERE d.accountInviteId IN :accountInviteIds"
          + " AND d.createDate = (SELECT MAX(n.createDate) FROM InviteEmailDelivery n"
          + " WHERE n.accountInviteId = d.accountInviteId)"
          + " ORDER BY d.id DESC")
  List<InviteEmailDelivery> findLatestByAccountInviteIdIn(
      @Param("accountInviteIds") Collection<Long> accountInviteIds);
}
