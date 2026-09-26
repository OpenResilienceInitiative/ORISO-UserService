package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface GroupChatJoinRequestRepository extends CrudRepository<GroupChatJoinRequest, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT request FROM GroupChatJoinRequest request WHERE request.id = :id")
  Optional<GroupChatJoinRequest> findByIdForUpdate(@Param("id") Long id);

  Optional<GroupChatJoinRequest> findFirstBySeriesIdAndConsultantIdAndStatusOrderByIdDesc(
      Long seriesId, String consultantId, Status status);

  Optional<GroupChatJoinRequest> findFirstBySeriesIdAndConsultantIdOrderByIdDesc(
      Long seriesId, String consultantId);

  List<GroupChatJoinRequest> findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
      Collection<Long> seriesIds, Status status);
}
