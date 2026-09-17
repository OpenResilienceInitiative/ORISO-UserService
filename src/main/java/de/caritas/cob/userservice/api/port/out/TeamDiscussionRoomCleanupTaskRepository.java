package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TeamDiscussionRoomCleanupTask;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamDiscussionRoomCleanupTaskRepository
    extends JpaRepository<TeamDiscussionRoomCleanupTask, Long> {

  List<TeamDiscussionRoomCleanupTask> findAllByOrderByCreateDateAsc();
}
