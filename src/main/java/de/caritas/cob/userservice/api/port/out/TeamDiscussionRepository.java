package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamDiscussionRepository extends JpaRepository<TeamDiscussion, Long> {

  /** FE#2209: tenant-independent, see {@code SessionRepository#countAnyTenantByMatrixRoomId}. */
  @Query(
      value = "SELECT COUNT(*) FROM team_discussion WHERE matrix_room_id = :room",
      nativeQuery = true)
  long countAnyTenantByMatrixRoomId(@Param("room") String room);

  /**
   * The case's newest discussion. A case has more than one only after it moved to another
   * Beratungsstelle (D4); callers that care filter by {@link TeamDiscussion#getAgencyId()}.
   */
  default Optional<TeamDiscussion> findBySessionId(Long sessionId) {
    return findFirstBySessionIdOrderByIdDesc(sessionId);
  }

  Optional<TeamDiscussion> findFirstBySessionIdOrderByIdDesc(Long sessionId);

  /** Every discussion of the case, newest first (more than one after a move, D4). */
  List<TeamDiscussion> findAllBySessionIdOrderByIdDesc(Long sessionId);

  Optional<TeamDiscussion> findByMatrixRoomId(String matrixRoomId);

  @Query(
      "select td from TeamDiscussion td, Session s where td.sessionId = s.id and td.id > :afterId"
          + " and (td.readOnlyApplied = false or td.status <> :archived)"
          + " and (s.consultant is not null or s.status <> :newStatus) order by td.id")
  List<TeamDiscussion> findPendingArchiveRepairs(
      @Param("newStatus") SessionStatus newStatus,
      @Param("archived") TeamDiscussion.Status archived,
      @Param("afterId") long afterId,
      Pageable page);

  /** Open team rooms this consultant actually joined, restricted to one agency. */
  @Query(
      "select distinct td.matrixRoomId from TeamDiscussion td, Session s,"
          + " TeamDiscussionParticipant p where td.sessionId = s.id"
          + " and p.teamDiscussionId = td.id and p.consultantId = :consultantId"
          + " and coalesce(td.agencyId, s.agencyId) = :agencyId and td.status = :status")
  List<String> findRoomIdsForParticipantInAgency(
      @Param("consultantId") String consultantId,
      @Param("agencyId") Long agencyId,
      @Param("status") TeamDiscussion.Status status);

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
