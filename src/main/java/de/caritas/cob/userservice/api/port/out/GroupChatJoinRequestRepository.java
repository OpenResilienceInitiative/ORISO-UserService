package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Via;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface GroupChatJoinRequestRepository extends CrudRepository<GroupChatJoinRequest, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT request FROM GroupChatJoinRequest request WHERE request.id = :id")
  Optional<GroupChatJoinRequest> findByIdForUpdate(@Param("id") Long id);

  Optional<GroupChatJoinRequest> findFirstBySeriesIdAndConsultantIdAndStatusOrderByIdDesc(
      Long seriesId, String consultantId, Status status);

  Optional<GroupChatJoinRequest> findFirstBySeriesIdAndConsultantIdOrderByIdDesc(
      Long seriesId, String consultantId);

  /** Direct-link call knocks (#1475). */
  /** A scalar read, so the knock entity is not loaded before it is locked. */
  @Query("SELECT request.sessionId FROM GroupChatJoinRequest request WHERE request.id = :id")
  Optional<Long> findSessionIdById(@Param("id") Long id);

  /** Newest first, locked: a knock admitted meanwhile must not be superseded from a stale read. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT request FROM GroupChatJoinRequest request WHERE request.sessionId = :sessionId"
          + " AND request.userId = :userId AND request.via = :via"
          + " AND request.status = de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status.PENDING"
          + " ORDER BY request.id DESC")
  List<GroupChatJoinRequest> findPendingCallKnocksForUpdate(
      @Param("sessionId") Long sessionId, @Param("userId") String userId, @Param("via") Via via);

  Optional<GroupChatJoinRequest> findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
      Long sessionId, String userId, Via via, Status status);

  Optional<GroupChatJoinRequest> findFirstBySessionIdAndUserIdAndViaOrderByIdDesc(
      Long sessionId, String userId, Via via);

  @Query(
      "SELECT request FROM GroupChatJoinRequest request, Session session"
          + " WHERE request.sessionId = session.id AND session.consultant.id = :consultantId"
          + " AND request.via = :via AND request.status = :status"
          + " AND request.requestedAt >= :since"
          + " AND (:sessionId IS NULL OR request.sessionId = :sessionId)"
          + " ORDER BY request.requestedAt ASC, request.id ASC")
  List<GroupChatJoinRequest> findCallKnocksForConsultant(
      @Param("consultantId") String consultantId,
      @Param("via") Via via,
      @Param("status") Status status,
      @Param("since") java.time.LocalDateTime since,
      @Param("sessionId") Long sessionId);

  /** Same knocks, locked: "admit all" decides on the current rows, not on an earlier read. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT request FROM GroupChatJoinRequest request"
          + " WHERE request.sessionId IN"
          + " (SELECT session.id FROM Session session WHERE session.consultant.id = :consultantId)"
          + " AND request.via = :via AND request.status = :status"
          + " AND request.requestedAt >= :since"
          + " AND (:sessionId IS NULL OR request.sessionId = :sessionId)"
          + " ORDER BY request.requestedAt ASC, request.id ASC")
  List<GroupChatJoinRequest> findCallKnocksForConsultantForUpdate(
      @Param("consultantId") String consultantId,
      @Param("via") Via via,
      @Param("status") Status status,
      @Param("since") java.time.LocalDateTime since,
      @Param("sessionId") Long sessionId);

  /** Call share link knocks (#1487): one guest on one link. */
  Optional<GroupChatJoinRequest> findFirstByInviteLinkIdAndUserIdAndViaAndStatusOrderByIdDesc(
      Long inviteLinkId, String userId, Via via, Status status);

  /** The same, locked: seating a guest and declining the admission serialise on this row. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT request FROM GroupChatJoinRequest request WHERE request.inviteLinkId = :linkId"
          + " AND request.userId = :userId AND request.via = :via AND request.status = :status"
          + " ORDER BY request.id DESC")
  List<GroupChatJoinRequest> findCallLinkKnocksForUpdate(
      @Param("linkId") Long inviteLinkId,
      @Param("userId") String userId,
      @Param("via") Via via,
      @Param("status") Status status,
      Pageable page);

  List<GroupChatJoinRequest> findByInviteLinkIdAndViaAndStatus(
      Long inviteLinkId, Via via, Status status);

  List<GroupChatJoinRequest> findByInviteLinkIdAndUserIdAndVia(
      Long inviteLinkId, String userId, Via via);

  /**
   * Waiting guests on call share links (#1487). Scoped to a room: every link of a call there, for
   * any counsellor of that call (the caller checks call membership). Unscoped: the links the
   * counsellor created.
   */
  @Query(
      "SELECT request FROM GroupChatJoinRequest request, AgencyInviteLink link"
          + " WHERE request.inviteLinkId = link.id"
          + " AND (:roomId IS NOT NULL OR link.consultantId = :consultantId)"
          + " AND link.linkKind = 'CALL' AND link.status = 'ACTIVE'"
          + " AND request.via = :via AND request.status = :status"
          + " AND request.requestedAt >= :since"
          + " AND (:roomId IS NULL OR request.inviteLinkId IN (SELECT scoped.id FROM AgencyInviteLink scoped,"
          + " MatrixCallBinding binding WHERE scoped.callBindingId = binding.id"
          + " AND binding.sourceRoomId = :roomId))"
          + " ORDER BY request.requestedAt ASC, request.id ASC")
  List<GroupChatJoinRequest> findCallLinkKnocksForConsultant(
      @Param("consultantId") String consultantId,
      @Param("via") Via via,
      @Param("status") Status status,
      @Param("since") java.time.LocalDateTime since,
      @Param("roomId") String roomId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT request FROM GroupChatJoinRequest request"
          + " WHERE request.inviteLinkId IN"
          + " (SELECT link.id FROM AgencyInviteLink link"
          + " WHERE (:roomId IS NOT NULL OR link.consultantId = :consultantId)"
          + " AND link.linkKind = 'CALL' AND link.status = 'ACTIVE')"
          + " AND request.via = :via AND request.status = :status"
          + " AND request.requestedAt >= :since"
          + " AND (:roomId IS NULL OR request.inviteLinkId IN (SELECT scoped.id FROM AgencyInviteLink scoped,"
          + " MatrixCallBinding binding WHERE scoped.callBindingId = binding.id"
          + " AND binding.sourceRoomId = :roomId))"
          + " ORDER BY request.requestedAt ASC, request.id ASC")
  List<GroupChatJoinRequest> findCallLinkKnocksForConsultantForUpdate(
      @Param("consultantId") String consultantId,
      @Param("via") Via via,
      @Param("status") Status status,
      @Param("since") java.time.LocalDateTime since,
      @Param("roomId") String roomId);

  List<GroupChatJoinRequest> findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
      Collection<Long> seriesIds, Status status);

  @Query(
      "SELECT request FROM GroupChatJoinRequest request"
          + " WHERE request.status = :status AND request.admissionRequestedAt IS NOT NULL"
          + " AND request.via = de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Via.INVITE_LINK"
          + " AND (request.admissionLastAttemptAt IS NULL"
          + " OR request.admissionLastAttemptAt < :retryBefore)"
          + " ORDER BY CASE WHEN request.admissionLastAttemptAt IS NULL THEN 0 ELSE 1 END ASC,"
          + " request.admissionLastAttemptAt ASC, request.admissionRequestedAt ASC, request.id ASC")
  List<GroupChatJoinRequest> findAdmissionsReady(
      @Param("status") Status status,
      @Param("retryBefore") java.time.LocalDateTime retryBefore,
      Pageable pageable);
}
