package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DpaSignedNotice;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DpaSignedNoticeRepository extends JpaRepository<DpaSignedNotice, Long> {

  boolean existsByTenantIdAndDpaVersion(Long tenantId, String dpaVersion);

  @Query(
      """
      select n.id from DpaSignedNotice n
      where n.noticeRole = ADMIN and n.adminRecoverySignatureAt is not null
        and n.adminNextAttemptAt <= :now and n.sentAt is null
      order by n.adminNextAttemptAt, n.id
      """)
  List<Long> findDueAdminNoticeIds(@Param("now") LocalDateTime now, Pageable limit);

  @Modifying
  @Query(
      """
      update DpaSignedNotice n set n.adminNextAttemptAt = :next
      where n.id = :id and n.noticeRole = ADMIN and n.adminRecoverySignatureAt is not null
        and n.adminNextAttemptAt <= :now and n.sentAt is null
      """)
  int reserveAdminPreparation(
      @Param("id") long id, @Param("now") LocalDateTime now, @Param("next") LocalDateTime next);

  /** Once the handoff has started, only the active sender may finish or release this claim. */
  @Modifying
  @Query(
      """
      update DpaSignedNotice n set n.adminNextAttemptAt = null
      where n.id = :id and n.noticeRole = ADMIN and n.adminRecoverySignatureAt is not null
        and n.adminNextAttemptAt is not null and n.sentAt is null
      """)
  int beginAdminHandoff(@Param("id") long id);
}
