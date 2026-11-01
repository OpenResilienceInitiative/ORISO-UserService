package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ChatMatrixRoom;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface ChatMatrixRoomRepository extends CrudRepository<ChatMatrixRoom, String> {
  List<ChatMatrixRoom> findBySeries_IdOrderByOccurrenceIndexAsc(Long seriesId);
}
