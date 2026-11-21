package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupRosterRemovalIntent;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRosterRemovalIntentRepository
    extends JpaRepository<GroupRosterRemovalIntent, Long> {

  record RetryRoute(String ownerId, Long seriesId) {}

  /** Read only immutable routing values before locking the owner and series. */
  @Query(
      """
      select new de.caritas.cob.userservice.api.port.out.GroupRosterRemovalIntentRepository$RetryRoute(
          intent.ownerId, intent.seriesId)
      from GroupRosterRemovalIntent intent where intent.id = :id
      """)
  Optional<RetryRoute> findRetryRouteById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select intent from GroupRosterRemovalIntent intent where intent.id = :id")
  Optional<GroupRosterRemovalIntent> lockById(@Param("id") Long id);

  @Query(
      """
      select intent.id from GroupRosterRemovalIntent intent
      where intent.retryAt <= :now
      order by intent.retryAt, intent.id
      """)
  List<Long> findDueIds(@Param("now") LocalDateTime now, Pageable page);

  List<GroupRosterRemovalIntent>
      findBySeriesIdAndSupervisionIdIsNullAndDispatchSettledIsFalseAndConsultantIdIn(
          Long seriesId, Collection<String> consultantIds);

  List<GroupRosterRemovalIntent> findBySeriesIdAndRoomIdAndMemberIdAndDispatchSettledIsFalse(
      Long seriesId, String roomId, String memberId);

  /** A successful roster edit supersedes group-room intents and leaves side-room work pending. */
  @Modifying
  @Query(
      """
      delete from GroupRosterRemovalIntent intent
      where intent.seriesId = :seriesId and intent.supervisionId is null and intent.dispatchSettled = true
      """)
  void discardGroupRoomIntents(@Param("seriesId") Long seriesId);
}
