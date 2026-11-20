package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DpaSignedNotice;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DpaSignedNoticeRepository extends JpaRepository<DpaSignedNotice, Long> {

  boolean existsByTenantIdAndDpaVersion(Long tenantId, String dpaVersion);

  Optional<DpaSignedNotice> findByAdminSourceEventId(Long eventId);

  /** Lock a completed hint delivery before binding it to a verified outbox event. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select n from DpaSignedNotice n
      where n.tenantId = :tenantId and n.dpaVersion = :version and n.noticeRole = ADMIN
        and n.sentAt is not null and n.adminSourceEventId is null
        and n.adminSourceSignedAtEpochMillis is null
      """)
  Optional<DpaSignedNotice> lockDeliveredAdminHint(
      @Param("tenantId") long tenantId, @Param("version") String version);

  @Modifying
  @Query(
      """
      update DpaSignedNotice n
      set n.adminSourceEventId = :eventId, n.adminSourceSignedAtEpochMillis = :signedAt
      where n.id = :id and n.noticeRole = ADMIN and n.sentAt is not null
        and n.adminSourceEventId is null and n.adminSourceSignedAtEpochMillis is null
      """)
  int bindDeliveredAdminHint(
      @Param("id") long id, @Param("eventId") long eventId, @Param("signedAt") long signedAt);

  @Query(
      """
      select n.id from DpaSignedNotice n
      where n.noticeRole = ADMIN and n.adminRecoverySignatureAt is not null
        and n.adminNextAttemptAt <= :now and n.sentAt is null
        and n.adminSourceEventId is null
      order by n.adminNextAttemptAt, n.id
      """)
  List<Long> findDueAdminNoticeIds(@Param("now") LocalDateTime now, Pageable limit);

  @Query(
      """
      select n.id from DpaSignedNotice n
      where n.noticeRole = ADMIN and n.adminSourceEventId is not null
        and n.adminNextAttemptAt <= :now and n.sentAt is null
      order by n.adminNextAttemptAt, n.id
      """)
  List<Long> findDueExactAdminNoticeIds(@Param("now") LocalDateTime now, Pageable limit);

  /** Preserve exact outbox ownership when the attempted delivery is confirmed unsent. */
  @Modifying
  @Query(
      """
      update DpaSignedNotice n set n.adminNextAttemptAt = :next
      where n.id = :id and n.adminSourceEventId = :eventId and n.noticeRole = ADMIN
        and n.adminNextAttemptAt is null and n.sentAt is null
      """)
  int rearmExactAdminNotice(
      @Param("id") long id, @Param("eventId") long eventId, @Param("next") LocalDateTime next);

  @Modifying
  @Query(
      """
      update DpaSignedNotice n set n.adminNextAttemptAt = :next
      where n.id = :id and n.noticeRole = ADMIN and n.adminRecoverySignatureAt is not null
        and n.adminNextAttemptAt <= :now and n.sentAt is null
      """)
  int reserveAdminPreparation(
      @Param("id") long id, @Param("now") LocalDateTime now, @Param("next") LocalDateTime next);

  /** Locks only the still-pending row held by this exact reservation. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select n from DpaSignedNotice n
      where n.id = :id and n.noticeRole = ADMIN and n.adminRecoverySignatureAt is not null
        and n.adminNextAttemptAt = :reservedUntil and n.sentAt is null
      """)
  Optional<DpaSignedNotice> lockAdminPreparation(
      @Param("id") long id, @Param("reservedUntil") LocalDateTime reservedUntil);
}
