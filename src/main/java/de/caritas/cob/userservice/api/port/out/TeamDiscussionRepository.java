package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TeamDiscussion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamDiscussionRepository extends JpaRepository<TeamDiscussion, Long> {

  Optional<TeamDiscussion> findBySessionId(Long sessionId);

  Optional<TeamDiscussion> findByMatrixRoomId(String matrixRoomId);
}
