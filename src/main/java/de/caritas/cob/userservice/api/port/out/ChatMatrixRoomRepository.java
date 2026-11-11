package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ChatMatrixRoom;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ChatMatrixRoomRepository extends CrudRepository<ChatMatrixRoom, String> {
  List<ChatMatrixRoom> findBySeries_IdOrderByOccurrenceIndexAsc(Long seriesId);

  /**
   * A locking read returns the latest committed row, also when the surrounding transaction's
   * snapshot is older than a room reserved in a transaction of its own.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from ChatMatrixRoom r where r.roomId = :roomId")
  Optional<ChatMatrixRoom> findByRoomIdForUpdate(@Param("roomId") String roomId);
}
