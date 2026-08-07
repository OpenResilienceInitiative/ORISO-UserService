package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface SupportRoomRepository extends CrudRepository<SupportRoom, String> {

  List<SupportRoom> findAllByStatusAndExpiryDateBefore(
      SupportRoomStatus status,
      LocalDateTime before,
      org.springframework.data.domain.Pageable page);

  List<SupportRoom> findAllByStatusAndConsultantIdOrStatusAndSupportAdminId(
      SupportRoomStatus consultantStatus,
      String consultantId,
      SupportRoomStatus supportAdminStatus,
      String supportAdminId);
}
