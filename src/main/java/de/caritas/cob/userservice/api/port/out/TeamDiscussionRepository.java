package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TeamDiscussion;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TeamDiscussionRepository extends JpaRepository<TeamDiscussion, Long> {

  Optional<TeamDiscussion> findBySessionId(Long sessionId);

  Optional<TeamDiscussion> findByMatrixRoomId(String matrixRoomId);

  @Query(
      "select td from TeamDiscussion td, Session s where td.sessionId = s.id"
          + " and (td.readOnlyApplied = false and (s.consultant is not null or s.status <> :newStatus))")
  List<TeamDiscussion> findPendingArchiveRepairs(
      @org.springframework.data.repository.query.Param("newStatus")
          de.caritas.cob.userservice.api.model.Session.SessionStatus newStatus);

  /** Open team rooms this consultant actually joined, restricted to one agency. */
  @Query(
      "select distinct td.matrixRoomId from TeamDiscussion td, Session s,"
          + " TeamDiscussionParticipant p where td.sessionId = s.id"
          + " and p.teamDiscussionId = td.id and p.consultantId = :consultantId"
          + " and s.agencyId = :agencyId and td.status = :status")
  List<String> findRoomIdsForParticipantInAgency(
      @org.springframework.data.repository.query.Param("consultantId") String consultantId,
      @org.springframework.data.repository.query.Param("agencyId") Long agencyId,
      @org.springframework.data.repository.query.Param("status") TeamDiscussion.Status status);

  /**
   * Discussions whose session no longer exists (#1118). The schema has no foreign key from {@code
   * team_discussion} to {@code session}, so nothing else reveals these rows.
   *
   * @return every discussion that points at a deleted session
   */
  @Query(
      "select td from TeamDiscussion td where not exists"
          + " (select s.id from Session s where s.id = td.sessionId)")
  List<TeamDiscussion> findAllOrphaned();

  /**
   * Archived discussions whose archive date lies strictly before the cutoff (#1116).
   *
   * <p>Pass {@link TeamDiscussion.Status#ARCHIVED}; the status is a parameter only because JPQL
   * enum literals of a nested enum are brittle across Hibernate versions.
   */
  List<TeamDiscussion> findByStatusAndArchiveDateBefore(
      TeamDiscussion.Status status, LocalDateTime cutoff);
}
