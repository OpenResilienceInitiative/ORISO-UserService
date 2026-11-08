package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DraftMessage;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DraftMessageRepository extends JpaRepository<DraftMessage, Long> {

  Optional<DraftMessage> findByUserIdAndScopeKeyAndProtectedScopeKeyIsNull(
      String userId, String scopeKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DraftMessage> findForUpdateByUserIdAndScopeKeyAndProtectedScopeKeyIsNull(
      String userId, String scopeKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select draft from DraftMessage draft
      where draft.id = :id and draft.userId = :userId and draft.protectedScopeKey is null
        and (draft.tenantId = :tenantId
             or (draft.tenantId is null and (:tenantId is null or :tenantId = 1)))
      """)
  Optional<DraftMessage> findOwnedLegacyForUpdate(
      @Param("id") long id, @Param("userId") String userId, @Param("tenantId") Long tenantId);

  @Query(
      """
      select draft from DraftMessage draft
      where draft.userId = :userId and draft.protectedScopeKey = :scopeKey
        and (draft.tenantId = :tenantId or (draft.tenantId is null and :tenantId is null))
      """)
  Optional<DraftMessage> findProtected(
      @Param("userId") String userId,
      @Param("scopeKey") String scopeKey,
      @Param("tenantId") Long tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select draft from DraftMessage draft
      where draft.userId = :userId and draft.protectedScopeKey = :scopeKey
        and (draft.tenantId = :tenantId or (draft.tenantId is null and :tenantId is null))
      """)
  Optional<DraftMessage> findProtectedForUpdate(
      @Param("userId") String userId,
      @Param("scopeKey") String scopeKey,
      @Param("tenantId") Long tenantId);

  List<DraftMessage> findByUserIdAndProtectedScopeKeyIsNullOrderByUpdateDateDescIdDesc(
      String userId, Pageable pageable);

  @Query(
      """
      select draft from DraftMessage draft
      where draft.userId = :userId
        and (:legacyOnly = false or draft.protectedScopeKey is null)
        and (draft.tenantId = :tenantId
             or (draft.tenantId is null and
                 (:tenantId is null or (:tenantId = 1 and draft.protectedScopeKey is null))))
      order by draft.updateDate desc, draft.id desc
      """)
  List<DraftMessage> findForProtectedFeed(
      @Param("userId") String userId,
      @Param("tenantId") Long tenantId,
      @Param("legacyOnly") boolean legacyOnly,
      Pageable pageable);

  void deleteByUserIdAndScopeKeyAndProtectedScopeKeyIsNull(String userId, String scopeKey);

  /**
   * Removes all legacy and protected drafts of a user during account deletion (#983, KDG epic
   * #1010).
   */
  @Transactional
  void deleteByUserId(String userId);
}
