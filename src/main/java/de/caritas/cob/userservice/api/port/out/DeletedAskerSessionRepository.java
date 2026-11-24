package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DeletedAskerSession;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeletedAskerSessionRepository extends JpaRepository<DeletedAskerSession, Long> {

  boolean existsByConsultantIdAndFormerSessionId(String consultantId, Long formerSessionId);

  List<DeletedAskerSession> findByConsultantIdAndDeletedAtAfterOrderByDeletedAtDesc(
      String consultantId, LocalDateTime deletedAfter);

  List<DeletedAskerSession>
      findByConsultantIdAndArchivedAtIsNullAndDeletedAtAfterOrderByDeletedAtDesc(
          String consultantId, LocalDateTime deletedAfter);

  List<DeletedAskerSession>
      findByConsultantIdAndArchivedAtIsNotNullAndDeletedAtAfterOrderByDeletedAtDesc(
          String consultantId, LocalDateTime deletedAfter);

  Optional<DeletedAskerSession> findByConsultantIdAndFormerSessionId(
      String consultantId, Long formerSessionId);

  /**
   * Removes placeholders older than the cutoff (ORISO-Frontend#1159).
   *
   * @return number of deleted rows
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from DeletedAskerSession d where d.deletedAt < :cutoff")
  int deleteDeletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
