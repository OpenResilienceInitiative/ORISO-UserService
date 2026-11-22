package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface SupportRoomRepository extends CrudRepository<SupportRoom, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select room from SupportRoom room where room.id = :roomId")
  Optional<SupportRoom> lockById(@Param("roomId") String roomId);

  @Query(
      """
      select room.id from SupportRoom room
      where (room.status in :expirable and room.expiryDate <= :now)
         or (room.status = :pending and room.retryAt <= :now)
      order by coalesce(room.retryAt, room.expiryDate), room.id
      """)
  List<String> findClosureDue(
      @Param("expirable") List<SupportRoomStatus> expirable,
      @Param("pending") SupportRoomStatus pending,
      @Param("now") LocalDateTime now,
      Pageable page);

  List<SupportRoom> findAllByStatusAndConsultantIdOrStatusAndSupportAdminId(
      SupportRoomStatus consultantStatus,
      String consultantId,
      SupportRoomStatus supportAdminStatus,
      String supportAdminId);

  /** FE#2209: a known ORISO room is never accepted as a call room. */
  boolean existsByMatrixRoomId(String matrixRoomId);
}
