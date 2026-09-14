package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TeamDiscussionParticipant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamDiscussionParticipantRepository
    extends JpaRepository<TeamDiscussionParticipant, Long> {

  List<TeamDiscussionParticipant> findByTeamDiscussionId(Long teamDiscussionId);

  boolean existsByTeamDiscussionIdAndConsultantId(Long teamDiscussionId, String consultantId);

  /** Persisted participation retains a revocation retry until Matrix confirms removal. */
  @Query(
      "select p from TeamDiscussionParticipant p, TeamDiscussion td, Session s"
          + " where p.teamDiscussionId = td.id and td.sessionId = s.id"
          + " and not exists (select ca.id from ConsultantAgency ca"
          + " where ca.consultant.id = p.consultantId and ca.agencyId = s.agencyId"
          + " and ca.deleteDate is null)")
  List<TeamDiscussionParticipant> findParticipantsWithoutAgencyAccess();

  /**
   * Removes every participant record of one discussion (#1116). The table has no foreign key to
   * {@code team_discussion}, so a purge has to delete the participants explicitly.
   *
   * @return the number of rows removed
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from TeamDiscussionParticipant p where p.teamDiscussionId = :teamDiscussionId")
  int deleteByTeamDiscussionId(@Param("teamDiscussionId") Long teamDiscussionId);
}
