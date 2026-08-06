package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TeamDiscussionParticipant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamDiscussionParticipantRepository
    extends JpaRepository<TeamDiscussionParticipant, Long> {

  List<TeamDiscussionParticipant> findByTeamDiscussionId(Long teamDiscussionId);

  boolean existsByTeamDiscussionIdAndConsultantId(Long teamDiscussionId, String consultantId);
}
