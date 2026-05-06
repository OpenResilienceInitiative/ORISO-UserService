package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface AgencyInviteLinkRepository extends CrudRepository<AgencyInviteLink, Long> {

  Optional<AgencyInviteLink> findByToken(String token);

  List<AgencyInviteLink> findAllByTenantIdOrderByCreateDateDesc(Long tenantId);

  List<AgencyInviteLink> findAllByAgencyIdInOrderByCreateDateDesc(List<Long> agencyIds);

  List<AgencyInviteLink> findAllByOrderByCreateDateDesc();
}
