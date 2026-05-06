package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import java.util.List;
import java.util.Optional;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.CrudRepository;

public interface AgencyInviteLinkRepository extends CrudRepository<AgencyInviteLink, Long> {

  Optional<AgencyInviteLink> findByToken(String token);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AgencyInviteLink> findByTokenAndStatus(String token, String status);

  List<AgencyInviteLink> findAllByTenantIdOrderByCreateDateDesc(Long tenantId);

  List<AgencyInviteLink> findAllByAgencyIdInOrderByCreateDateDesc(List<Long> agencyIds);

  List<AgencyInviteLink> findAllByOrderByCreateDateDesc();
}
