package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends CrudRepository<User, String> {

  @EntityGraph(attributePaths = "userAgencies")
  Optional<User> findByUserIdAndDeleteDateIsNull(String userId);

  Optional<User> findByEmailAndDeleteDateIsNull(String email);

  Optional<User> findByMatrixUserIdAndDeleteDateIsNull(String matrixUserId);

  /**
   * Every not deleted user row holding one of the given Matrix ids, in one query. The call code
   * resolves a whole conversation's members at once (#1656) and decides ambiguity itself.
   */
  List<User> findAllByMatrixUserIdInAndDeleteDateIsNull(Collection<String> matrixUserIds);

  List<User> findAllByDeleteDateNotNull();

  List<User> findAllByDeletionLifecycleStateOrderByCreateDateAsc(
      DeletionLifecycleState deletionLifecycleState);

  /**
   * Bounded, oldest-first variant. The durable repair queue is drained a page at a time so one
   * scheduler run has a predictable upper cost; the remainder is picked up by the next run.
   */
  List<User> findAllByDeletionLifecycleStateOrderByCreateDateAsc(
      DeletionLifecycleState deletionLifecycleState, Pageable pageable);

  List<User> findAllByDeleteDateIsNull();

  /**
   * Username lookups must tolerate duplicates: user.username carries no unique constraint and
   * generated anonymous usernames repeat across service restarts (the in-memory id registry
   * resets), so a single-result finder would throw {@code IncorrectResultSizeDataAccessException}
   * as soon as two rows share a name — which 500s the anonymous invite-link redeem. Callers pick
   * the oldest matching row deterministically.
   */
  List<User> findAllByUsernameInAndDeleteDateIsNullOrderByCreateDateAsc(
      Collection<String> usernames);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<User> findAllByUsernameInOrderByCreateDateAsc(Collection<String> usernames);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT user FROM User user WHERE user.userId = :userId")
  Optional<User> findByUserIdForUpdate(@Param("userId") String userId);

  /** Stores why the nightly run kept this advice seeker (#1521); a deleted row is not touched. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE User user
         SET user.deletionFailureReason = :reason,
             user.deletionFailedAt = :failedAt
       WHERE user.userId = :userId
      """)
  int recordHardDeleteFailure(
      @Param("userId") String userId,
      @Param("reason") String reason,
      @Param("failedAt") LocalDateTime failedAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE User user
         SET user.deletionLifecycleState = :readyState
       WHERE user.userId = :userId
         AND user.deleteDate IS NOT NULL
         AND user.deletionLifecycleState = :claimedState
      """)
  int releaseUserHardDeleteClaim(
      @Param("userId") String userId,
      @Param("claimedState") DeletionLifecycleState claimedState,
      @Param("readyState") DeletionLifecycleState readyState);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE User user
         SET user.deletionLifecycleState = :readyState
       WHERE user.deleteDate IS NOT NULL
         AND user.deletionLifecycleState = :claimedState
      """)
  int releaseInterruptedUserHardDeleteClaims(
      @Param("claimedState") DeletionLifecycleState claimedState,
      @Param("readyState") DeletionLifecycleState readyState);

  /** Returns report IDs using the same session membership as the mapped collection. */
  @Query(
      """
      SELECT u.userId FROM User u
       WHERE u.sessions IS EMPTY
         AND NOT EXISTS (SELECT 1 FROM UserAgency ua WHERE ua.user = u)
      """)
  List<String> findIdsWithoutSessionOrChatRelations();

  /**
   * Find all users whose create date is older than given date and having no new registered session
   * after the given create date and no running sessions.
   *
   * @param date the date to check
   * @return a list of {@link User}
   */
  @Query(
      value =
          "SELECT u FROM User u "
              + "WHERE u.deleteDate IS NULL "
              + "  AND u.createDate < ?1 "
              + "  AND NOT EXISTS ( SELECT 1 FROM UserAgency ua WHERE u = ua.user)"
              + "  AND NOT EXISTS ( "
              + "    SELECT 1 FROM Session s1 "
              + "    WHERE u = s1.user "
              + "      AND s1.status > 0 "
              + "      AND s1.enquiryMessageDate IS NOT NULL "
              + "      AND s1.matrixRoomId IS NOT NULL "
              + "  )"
              + "  AND EXISTS ("
              + "    SELECT 1 FROM Session s2 "
              + "      WHERE u = s2.user "
              + "        AND s2.status = 0 "
              + "        AND s2.enquiryMessageDate IS NULL "
              + "        AND s2.matrixRoomId IS NULL "
              + "        AND s2.createDate < ?1 "
              + "  )"
              + "  AND NOT EXISTS ("
              + "    SELECT 1 FROM Session s3 "
              + "    WHERE u = s3.user "
              + "    AND s3.status = 0 "
              + "    AND s3.enquiryMessageDate IS NULL "
              + "    AND s3.matrixRoomId IS NULL "
              + "    AND s3.createDate >= ?1"
              + ")")
  List<User> findAllByDeleteDateNullAndNoRunningSessionsAndCreateDateOlderThan(LocalDateTime date);

  /**
   * Ids of temporary accounts created before {@code createdBefore} and not already being deleted.
   */
  @Query(
      "SELECT u.userId FROM User u WHERE u.temporaryAccount = true AND u.deleteDate IS NULL"
          + " AND u.temporaryAccountSnapshot.medium IS NULL AND u.createDate < ?1")
  List<String> findTemporaryAccountIdsCreatedBefore(LocalDateTime createdBefore);

  /**
   * Direct-link temporary accounts (ADR-029, #1476) whose identities still exist. Their own
   * snapshotted lifetimes decide when they go, not {@code user.temporary.deleteWorkflow.maxAge}.
   */
  @Query(
      "SELECT u.userId FROM User u WHERE u.temporaryAccount = true AND u.deleteDate IS NULL"
          + " AND u.temporaryAccountSnapshot.medium IS NOT NULL"
          + " AND u.temporaryAccountSnapshot.deletedAt IS NULL")
  List<String> findLiveDirectLinkTemporaryAccountIds();

  /**
   * Advice seekers of a Träger that still count as accounts: not marked for deletion and not one of
   * the technical {@code group-chat-system} users, which go with their Träger (#1466).
   */
  @Query(
      "SELECT COUNT(u) FROM User u WHERE u.tenantId = :tenantId AND u.deleteDate IS NULL"
          + " AND u.userId NOT LIKE 'group-chat-system%'")
  long countActiveAdviceSeekersOfTenant(@Param("tenantId") Long tenantId);

  /**
   * Advice seekers of a Träger, marked or not, without the technical group-chat users; the Träger
   * row goes only once this is 0 (ORISO-Admin#1525).
   */
  @Query(
      "SELECT COUNT(u) FROM User u WHERE u.tenantId = :tenantId"
          + " AND u.userId NOT LIKE 'group-chat-system%'")
  long countAdviceSeekersOfTenant(@Param("tenantId") Long tenantId);

  /** The technical group-chat users of a Träger; they go with it, last (ORISO-Admin#1525). */
  @Query(
      "SELECT u FROM User u WHERE u.tenantId = :tenantId"
          + " AND u.userId LIKE 'group-chat-system%'")
  List<User> findTechnicalUsersOfTenant(@Param("tenantId") Long tenantId);

  /**
   * Advice seekers whose only Beratungsstelle is the given one, by session or membership
   * (ORISO-Admin#1524). Deleting the Beratungsstelle deletes them; the technical group-chat users
   * go with their Träger only.
   */
  @Query(
      "SELECT u "
          + "FROM User u WHERE u.deleteDate IS NULL"
          + " AND u.userId NOT LIKE 'group-chat-system%'"
          + " AND (EXISTS (SELECT 1 FROM Session s WHERE s.user = u AND s.agencyId = :agencyId)"
          + " OR EXISTS (SELECT 1 FROM UserAgency ua WHERE ua.user = u AND ua.agencyId = :agencyId))"
          + " AND NOT EXISTS (SELECT 1 FROM Session s2 WHERE s2.user = u"
          + " AND s2.agencyId <> :agencyId AND s2.deleteDate IS NULL)"
          + " AND NOT EXISTS (SELECT 1 FROM UserAgency ua2 WHERE ua2.user = u"
          + " AND ua2.agencyId <> :agencyId)")
  List<User> findActiveAdviceSeekersOnlyInAgency(@Param("agencyId") Long agencyId);

  /** How many {@link #findActiveAdviceSeekersOnlyInAgency(Long)} returns. */
  @Query(
      "SELECT COUNT(u) "
          + "FROM User u WHERE u.deleteDate IS NULL"
          + " AND u.userId NOT LIKE 'group-chat-system%'"
          + " AND (EXISTS (SELECT 1 FROM Session s WHERE s.user = u AND s.agencyId = :agencyId)"
          + " OR EXISTS (SELECT 1 FROM UserAgency ua WHERE ua.user = u AND ua.agencyId = :agencyId))"
          + " AND NOT EXISTS (SELECT 1 FROM Session s2 WHERE s2.user = u"
          + " AND s2.agencyId <> :agencyId AND s2.deleteDate IS NULL)"
          + " AND NOT EXISTS (SELECT 1 FROM UserAgency ua2 WHERE ua2.user = u"
          + " AND ua2.agencyId <> :agencyId)")
  long countActiveAdviceSeekersOnlyInAgency(@Param("agencyId") Long agencyId);

  /**
   * The advice seekers {@link #countActiveAdviceSeekersOfTenant(Long)} counts (ORISO-Admin#1525).
   */
  @Query(
      "SELECT u FROM User u WHERE u.tenantId = :tenantId AND u.deleteDate IS NULL"
          + " AND u.userId NOT LIKE 'group-chat-system%'")
  List<User> findActiveAdviceSeekersOfTenant(@Param("tenantId") Long tenantId);
}
