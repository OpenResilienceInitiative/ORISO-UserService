package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TeamDiscussion;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamDiscussionRepository extends JpaRepository<TeamDiscussion, Long> {

  Optional<TeamDiscussion> findBySessionId(Long sessionId);

  Optional<TeamDiscussion> findByMatrixRoomId(String matrixRoomId);

  /**
   * Archived discussions whose archive date lies strictly before the cutoff (#1116).
   *
   * <p>Pass {@link TeamDiscussion.Status#ARCHIVED}; the status is a parameter only because JPQL
   * enum literals of a nested enum are brittle across Hibernate versions.
   */
  List<TeamDiscussion> findByStatusAndArchiveDateBefore(
      TeamDiscussion.Status status, LocalDateTime cutoff);

  /**
   * Discussions in the given status created strictly before the cutoff (#1116). With {@link
   * TeamDiscussion.Status#OPEN} this finds discussions that were never archived, so an abandoned
   * discussion is measured from its creation date under the same period as an archived one.
   */
  List<TeamDiscussion> findByStatusAndCreateDateBefore(
      TeamDiscussion.Status status, LocalDateTime cutoff);
}
