package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.NotificationRoomLevel;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRoomLevelRepository
    extends JpaRepository<NotificationRoomLevel, Long> {

  Optional<NotificationRoomLevel> findByUserIdAndRoomId(String userId, String roomId);
}
