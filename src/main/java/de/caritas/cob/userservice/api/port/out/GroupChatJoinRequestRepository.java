package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface GroupChatJoinRequestRepository extends CrudRepository<GroupChatJoinRequest, Long> {

  Optional<GroupChatJoinRequest> findFirstBySeriesIdAndConsultantIdAndStatusOrderByIdDesc(
      Long seriesId, String consultantId, Status status);

  Optional<GroupChatJoinRequest> findFirstBySeriesIdAndConsultantIdOrderByIdDesc(
      Long seriesId, String consultantId);

  List<GroupChatJoinRequest> findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
      Collection<Long> seriesIds, Status status);
}
