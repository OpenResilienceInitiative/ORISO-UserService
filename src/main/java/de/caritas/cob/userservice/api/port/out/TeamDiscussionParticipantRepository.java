package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TeamDiscussionParticipant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TeamDiscussionParticipantRepository
    extends JpaRepository<TeamDiscussionParticipant, Long> {

  List<TeamDiscussionParticipant> findByTeamDiscussionId(Long teamDiscussionId);

  boolean existsByTeamDiscussionIdAndConsultantId(Long teamDiscussionId, String consultantId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Transactional
  @Query("delete from TeamDiscussionParticipant p where p.teamDiscussionId = :teamDiscussionId")
  int deleteAllByTeamDiscussionId(@Param("teamDiscussionId") Long teamDiscussionId);
}
