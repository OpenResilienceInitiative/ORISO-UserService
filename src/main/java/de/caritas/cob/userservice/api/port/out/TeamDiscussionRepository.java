package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TeamDiscussion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TeamDiscussionRepository extends JpaRepository<TeamDiscussion, Long> {

  Optional<TeamDiscussion> findBySessionId(Long sessionId);

  Optional<TeamDiscussion> findByMatrixRoomId(String matrixRoomId);

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

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Transactional
  @Query("delete from TeamDiscussion td where td.sessionId = :sessionId")
  int deleteAllBySessionId(@Param("sessionId") Long sessionId);
}
