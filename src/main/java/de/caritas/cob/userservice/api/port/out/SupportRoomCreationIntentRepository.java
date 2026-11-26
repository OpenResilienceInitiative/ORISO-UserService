package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.SupportRoomCreationIntent;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface SupportRoomCreationIntentRepository
    extends CrudRepository<SupportRoomCreationIntent, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select intent from SupportRoomCreationIntent intent where intent.handshakeId = :id")
  Optional<SupportRoomCreationIntent> lockByHandshakeId(@Param("id") String handshakeId);

  @Query(
      """
      select intent.handshakeId from SupportRoomCreationIntent intent
      where intent.retryAt <= :now
      order by intent.retryAt, intent.handshakeId
      """)
  List<String> findDueIds(@Param("now") LocalDateTime now, Pageable page);
}
